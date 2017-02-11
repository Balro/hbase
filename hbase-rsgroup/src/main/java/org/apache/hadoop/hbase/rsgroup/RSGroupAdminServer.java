/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.rsgroup;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.constraint.ConstraintException;
import org.apache.hadoop.hbase.master.AssignmentManager;
import org.apache.hadoop.hbase.master.LoadBalancer;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.RegionPlan;
import org.apache.hadoop.hbase.master.RegionState;
import org.apache.hadoop.hbase.master.ServerManager;
import org.apache.hadoop.hbase.master.TableLockManager.TableLock;
import org.apache.hadoop.hbase.util.Address;

/**
 * Service to support Region Server Grouping (HBase-6721).
 */
@InterfaceAudience.Private
public class RSGroupAdminServer implements RSGroupAdmin {
  private static final Log LOG = LogFactory.getLog(RSGroupAdminServer.class);

  private MasterServices master;
  //List of servers that are being moved from one group to another
  //Key=host:port,Value=targetGroup
  private ConcurrentMap<Address,String> serversInTransition =
      new ConcurrentHashMap<Address, String>();
  private RSGroupInfoManager rsGroupInfoManager;

  public RSGroupAdminServer(MasterServices master,
                            RSGroupInfoManager RSGroupInfoManager) throws IOException {
    this.master = master;
    this.rsGroupInfoManager = RSGroupInfoManager;
  }

  @Override
  public RSGroupInfo getRSGroupInfo(String groupName) throws IOException {
    RSGroupInfoManager m = getRSGroupInfoManager();
    synchronized (m) {
      return m.getRSGroup(groupName);
    }
  }

  @Override
  public RSGroupInfo getRSGroupInfoOfTable(TableName tableName) throws IOException {
    RSGroupInfoManager m = getRSGroupInfoManager();
    String groupName = null;
    synchronized (m) {
      groupName = getRSGroupInfoManager().getRSGroupOfTable(tableName);
      return groupName == null? null: getRSGroupInfoManager().getRSGroup(groupName);
    }
  }

  private RSGroupInfo getAndCheckRSGroup(String name)
  throws IOException {
    if (StringUtils.isEmpty(name)) {
      throw new ConstraintException("The target group cannot be null.");
    }
    RSGroupInfo rsgi = getRSGroupInfo(name);
    if (rsgi == null) {
      throw new ConstraintException("Group does not exist: " + name);
    }
    return rsgi;
  }

  @Override
  public void moveServers(Set<Address> servers, String targetGroupName)
      throws IOException {
    if (servers == null) {
      throw new ConstraintException("The list of servers cannot be null.");
    }
    if (servers.size() < 1) {
      return;
    }
    RSGroupInfo targetGrp = getAndCheckRSGroup(targetGroupName);
    RSGroupInfoManager manager = getRSGroupInfoManager();
    synchronized (manager) {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preMoveServers(servers, targetGroupName);
      }
      Address firstServer = servers.iterator().next();
      //we only allow a move from a single source group
      //so this should be ok
      RSGroupInfo srcGrp = manager.getRSGroupOfServer(firstServer);
      //only move online servers (from default)
      //or servers from other groups
      //this prevents bogus servers from entering groups
      if (srcGrp == null) {
        throw new ConstraintException(
            "Server "+firstServer+" does not have a group.");
      }
      if (RSGroupInfo.DEFAULT_GROUP.equals(srcGrp.getName())) {
        Set<Address> onlineServers = new HashSet<Address>();
        for(ServerName server: master.getServerManager().getOnlineServers().keySet()) {
          onlineServers.add(server.getAddress());
        }
        for(Address el: servers) {
          if(!onlineServers.contains(el)) {
            throw new ConstraintException(
                "Server "+el+" is not an online server in default group.");
          }
        }
      }

      if(srcGrp.getServers().size() <= servers.size() &&
          srcGrp.getTables().size() > 0) {
        throw new ConstraintException("Cannot leave a group "+srcGrp.getName()+
            " that contains tables " +"without servers.");
      }

      String sourceGroupName = getRSGroupInfoManager()
          .getRSGroupOfServer(srcGrp.getServers().iterator().next()).getName();
      getAndCheckRSGroup(sourceGroupName);
      if (sourceGroupName.equals(targetGroupName)) {
        throw new ConstraintException(
            "Target group is the same as source group: "+targetGroupName);
      }
      for (Address server: servers) {
        if (serversInTransition.containsKey(server)) {
          throw new ConstraintException(
              "Server list contains a server that is already being moved: "+server);
        }
        String tmpGroup = getRSGroupInfoManager().getRSGroupOfServer(server).getName();
        if (sourceGroupName != null && !tmpGroup.equals(sourceGroupName)) {
          throw new ConstraintException(
              "Move server request should only come from one source group. "+
              "Expecting only "+sourceGroupName+" but contains "+tmpGroup);
        }
      }

      try {
        //update the servers as in transition
        for (Address server : servers) {
          serversInTransition.put(server, targetGroupName);
        }

        getRSGroupInfoManager().moveServers(servers, sourceGroupName, targetGroupName);
        boolean found;
        // Appy makes note that if we were passed in a List of servers,
        // we'd save having to do stuff like the below.
        List<Address> tmpServers = Lists.newArrayList(servers);
        do {
          found = false;
          for (Iterator<Address> iter = tmpServers.iterator();
               iter.hasNext(); ) {
            Address rs = iter.next();
            //get online regions
            List<HRegionInfo> regions = new LinkedList<HRegionInfo>();
            for (Map.Entry<HRegionInfo, ServerName> el :
                master.getAssignmentManager().getRegionStates().getRegionAssignments().entrySet()) {
              if (el.getValue().getAddress().equals(rs)) {
                regions.add(el.getKey());
              }
            }
            for (RegionState state :
                master.getAssignmentManager().getRegionStates().getRegionsInTransition().values()) {
              if (state.getServerName().getAddress().equals(rs)) {
                regions.add(state.getRegion());
              }
            }

            //unassign regions for a server
            LOG.info("Unassigning " + regions.size() +
                " regions from server " + rs + " for move to " + targetGroupName);
            if (regions.size() > 0) {
              //TODO bulk unassign or throttled unassign?
              for (HRegionInfo region : regions) {
                //regions might get assigned from tables of target group
                //so we need to filter
                if (!targetGrp.containsTable(region.getTable())) {
                  master.getAssignmentManager().unassign(region);
                  found = true;
                }
              }
            }
            if (!found) {
              iter.remove();
            }
          }
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            LOG.warn("Sleep interrupted", e);
            Thread.currentThread().interrupt();
          }
        } while (found);
      } finally {
        //remove from transition
        for (Address server : servers) {
          serversInTransition.remove(server);
        }
      }
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postMoveServers(servers, targetGroupName);
      }
      LOG.info("Move server done: "+sourceGroupName+"->"+targetGroupName);
    }
  }

  @Override
  public void moveTables(Set<TableName> tables, String targetGroup) throws IOException {
    if (tables == null) {
      throw new ConstraintException(
          "The list of servers cannot be null.");
    }
    if(tables.size() < 1) {
      LOG.debug("moveTables() passed an empty set. Ignoring.");
      return;
    }
    RSGroupInfoManager manager = getRSGroupInfoManager();
    synchronized (manager) {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preMoveTables(tables, targetGroup);
      }

      if(targetGroup != null) {
        RSGroupInfo destGroup = manager.getRSGroup(targetGroup);
        if(destGroup == null) {
          throw new ConstraintException("Target group does not exist: "+targetGroup);
        }
        if(destGroup.getServers().size() < 1) {
          throw new ConstraintException("Target group must have at least one server.");
        }
      }

      for(TableName table : tables) {
        String srcGroup = manager.getRSGroupOfTable(table);
        if(srcGroup != null && srcGroup.equals(targetGroup)) {
          throw new ConstraintException(
              "Source group is the same as target group for table "+table+" :"+srcGroup);
        }
      }
      manager.moveTables(tables, targetGroup);
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postMoveTables(tables, targetGroup);
      }
    }
    for(TableName table: tables) {
      TableLock lock = master.getTableLockManager().writeLock(table, "Group: table move");
      try {
        lock.acquire();
        for (HRegionInfo region :
            master.getAssignmentManager().getRegionStates().getRegionsOfTable(table)) {
          master.getAssignmentManager().unassign(region);
        }
      } finally {
        lock.release();
      }
    }
  }

  @Override
  public void addRSGroup(String name) throws IOException {
    if (master.getMasterCoprocessorHost() != null) {
      master.getMasterCoprocessorHost().preAddRSGroup(name);
    }
    RSGroupInfoManager m = getRSGroupInfoManager();
    synchronized(m) {
      m.addRSGroup(new RSGroupInfo(name));
    }
    if (master.getMasterCoprocessorHost() != null) {
      master.getMasterCoprocessorHost().postAddRSGroup(name);
    }
  }

  @Override
  public void removeRSGroup(String name) throws IOException {
    RSGroupInfoManager manager = getRSGroupInfoManager();
    synchronized (manager) {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preRemoveRSGroup(name);
      }
      RSGroupInfo RSGroupInfo = getRSGroupInfoManager().getRSGroup(name);
      if(RSGroupInfo == null) {
        throw new ConstraintException("Group "+name+" does not exist");
      }
      int tableCount = RSGroupInfo.getTables().size();
      if (tableCount > 0) {
        throw new ConstraintException("Group "+name+" must have no associated tables: "+tableCount);
      }
      int serverCount = RSGroupInfo.getServers().size();
      if(serverCount > 0) {
        throw new ConstraintException(
            "Group "+name+" must have no associated servers: "+serverCount);
      }
      for(NamespaceDescriptor ns: master.listNamespaceDescriptors()) {
        String nsGroup = ns.getConfigurationValue(RSGroupInfo.NAMESPACEDESC_PROP_GROUP);
        if(nsGroup != null &&  nsGroup.equals(name)) {
          throw new ConstraintException("Group "+name+" is referenced by namespace: "+ns.getName());
        }
      }
      manager.removeRSGroup(name);
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postRemoveRSGroup(name);
      }
    }
  }

  @Override
  public boolean balanceRSGroup(String groupName) throws IOException {
    ServerManager serverManager = master.getServerManager();
    AssignmentManager assignmentManager = master.getAssignmentManager();
    LoadBalancer balancer = master.getLoadBalancer();

    boolean balancerRan;
    synchronized (balancer) {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preBalanceRSGroup(groupName);
      }
      if (getRSGroupInfo(groupName) == null) {
        throw new ConstraintException("Group does not exist: "+groupName);
      }
      // Only allow one balance run at at time.
      Map<String, RegionState> groupRIT = rsGroupGetRegionsInTransition(groupName);
      if (groupRIT.size() > 0) {
        LOG.debug("Not running balancer because " +
          groupRIT.size() +
          " region(s) in transition: " +
          StringUtils.abbreviate(
              master.getAssignmentManager().getRegionStates().getRegionsInTransition().toString(),
              256));
        return false;
      }
      if (serverManager.areDeadServersInProgress()) {
        LOG.debug("Not running balancer because processing dead regionserver(s): " +
            serverManager.getDeadServers());
        return false;
      }

      //We balance per group instead of per table
      List<RegionPlan> plans = new ArrayList<RegionPlan>();
      for(Map.Entry<TableName, Map<ServerName, List<HRegionInfo>>> tableMap:
          getRSGroupAssignmentsByTable(groupName).entrySet()) {
        LOG.info("Creating partial plan for table "+tableMap.getKey()+": "+tableMap.getValue());
        List<RegionPlan> partialPlans = balancer.balanceCluster(tableMap.getValue());
        LOG.info("Partial plan for table "+tableMap.getKey()+": "+partialPlans);
        if (partialPlans != null) {
          plans.addAll(partialPlans);
        }
      }
      long startTime = System.currentTimeMillis();
      balancerRan = plans != null;
      if (plans != null && !plans.isEmpty()) {
        LOG.info("Group balance "+groupName+" starting with plan count: "+plans.size());
        for (RegionPlan plan: plans) {
          LOG.info("balance " + plan);
          assignmentManager.balance(plan);
        }
        LOG.info("Group balance "+groupName+" completed after "+
            (System.currentTimeMillis()-startTime)+" seconds");
      }
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postBalanceRSGroup(groupName, balancerRan);
      }
    }
    return balancerRan;
  }

  @Override
  public List<RSGroupInfo> listRSGroups() throws IOException {
    RSGroupInfoManager m = getRSGroupInfoManager();
    synchronized (m) {
      return m.listRSGroups();
    }
  }

  @Override
  public RSGroupInfo getRSGroupOfServer(Address hostPort) throws IOException {
    RSGroupInfoManager m = getRSGroupInfoManager();
    synchronized (m) {
      return m.getRSGroupOfServer(hostPort);
    }
  }

  @InterfaceAudience.Private
  // Private because the client needs to be careful using rsGroupInfoManager;
  // All modifications on rsGroupInfoManager must be under a synchronize on
  // rsGroupInfoManager
  private RSGroupInfoManager getRSGroupInfoManager() throws IOException {
    return rsGroupInfoManager;
  }

  private Map<String, RegionState> rsGroupGetRegionsInTransition(String groupName)
      throws IOException {
    Map<String, RegionState> rit = Maps.newTreeMap();
    AssignmentManager am = master.getAssignmentManager();
    RSGroupInfo RSGroupInfo = getRSGroupInfo(groupName);
    for(TableName tableName : RSGroupInfo.getTables()) {
      for(HRegionInfo regionInfo: am.getRegionStates().getRegionsOfTable(tableName)) {
        RegionState state =
            master.getAssignmentManager().getRegionStates().getRegionTransitionState(regionInfo);
        if(state != null) {
          rit.put(regionInfo.getEncodedName(), state);
        }
      }
    }
    return rit;
  }

  private Map<TableName, Map<ServerName, List<HRegionInfo>>>
      getRSGroupAssignmentsByTable(String groupName) throws IOException {
    Map<TableName, Map<ServerName, List<HRegionInfo>>> result = Maps.newHashMap();
    RSGroupInfo RSGroupInfo = getRSGroupInfo(groupName);
    Map<TableName, Map<ServerName, List<HRegionInfo>>> assignments = Maps.newHashMap();
    for(Map.Entry<HRegionInfo, ServerName> entry:
        master.getAssignmentManager().getRegionStates().getRegionAssignments().entrySet()) {
      TableName currTable = entry.getKey().getTable();
      ServerName currServer = entry.getValue();
      HRegionInfo currRegion = entry.getKey();
      if(RSGroupInfo.getTables().contains(currTable)) {
        if(!assignments.containsKey(entry.getKey().getTable())) {
          assignments.put(currTable, new HashMap<ServerName, List<HRegionInfo>>());
        }
        if(!assignments.get(currTable).containsKey(currServer)) {
          assignments.get(currTable).put(currServer, new ArrayList<HRegionInfo>());
        }
        assignments.get(currTable).get(currServer).add(currRegion);
      }
    }

    Map<ServerName, List<HRegionInfo>> serverMap = Maps.newHashMap();
    for(ServerName serverName: master.getServerManager().getOnlineServers().keySet()) {
      if(RSGroupInfo.getServers().contains(serverName.getAddress())) {
        serverMap.put(serverName, Collections.EMPTY_LIST);
      }
    }

    //add all tables that are members of the group
    for(TableName tableName : RSGroupInfo.getTables()) {
      if(assignments.containsKey(tableName)) {
        result.put(tableName, new HashMap<ServerName, List<HRegionInfo>>());
        result.get(tableName).putAll(serverMap);
        result.get(tableName).putAll(assignments.get(tableName));
        LOG.debug("Adding assignments for "+tableName+": "+assignments.get(tableName));
      }
    }

    return result;
  }

  public void prepareRSGroupForTable(HTableDescriptor desc) throws IOException {
    String groupName =
        master.getNamespaceDescriptor(desc.getTableName().getNamespaceAsString())
                .getConfigurationValue(RSGroupInfo.NAMESPACEDESC_PROP_GROUP);
    if (groupName == null) {
      groupName = RSGroupInfo.DEFAULT_GROUP;
    }
    RSGroupInfo RSGroupInfo = getRSGroupInfo(groupName);
    if (RSGroupInfo == null) {
      throw new ConstraintException("RSGroup " + groupName + " does not exist.");
    }
    if (!RSGroupInfo.containsTable(desc.getTableName())) {
      LOG.debug("Pre-moving table " + desc.getTableName() + " to rsgroup " + groupName);
      moveTables(Sets.newHashSet(desc.getTableName()), groupName);
    }
  }

  public void cleanupRSGroupForTable(TableName tableName) throws IOException {
    try {
      RSGroupInfo group = getRSGroupInfoOfTable(tableName);
      if (group != null) {
        LOG.debug("Removing deleted table from table rsgroup " + group.getName());
        moveTables(Sets.newHashSet(tableName), null);
      }
    } catch (ConstraintException ex) {
      LOG.debug("Failed to perform rsgroup information cleanup for table: " + tableName, ex);
    } catch (IOException ex) {
      LOG.debug("Failed to perform rsgroup information cleanup for table: " + tableName, ex);
    }
  }

  @Override
  public void close() throws IOException {
  }
}
