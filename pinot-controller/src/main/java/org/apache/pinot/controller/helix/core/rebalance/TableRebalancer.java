/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.helix.core.rebalance;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import org.I0Itec.zkclient.exception.ZkBadVersionException;
import org.apache.commons.configuration.Configuration;
import org.apache.helix.AccessOption;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixManager;
import org.apache.helix.PropertyKey;
import org.apache.helix.ZNRecord;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.pinot.common.assignment.InstancePartitions;
import org.apache.pinot.common.assignment.InstancePartitionsType;
import org.apache.pinot.common.assignment.InstancePartitionsUtils;
import org.apache.pinot.common.config.TableConfig;
import org.apache.pinot.common.config.instance.InstanceAssignmentConfigUtils;
import org.apache.pinot.common.utils.CommonConstants.Helix.StateModel.RealtimeSegmentOnlineOfflineStateModel;
import org.apache.pinot.common.utils.CommonConstants.Helix.TableType;
import org.apache.pinot.controller.helix.core.assignment.instance.InstanceAssignmentDriver;
import org.apache.pinot.controller.helix.core.assignment.segment.SegmentAssignment;
import org.apache.pinot.controller.helix.core.assignment.segment.SegmentAssignmentFactory;
import org.apache.pinot.controller.helix.core.assignment.segment.SegmentAssignmentUtils;
import org.apache.pinot.spi.stream.StreamConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The {@code TableRebalancer} class can be used to rebalance a table (reassign instances and segments for a table).
 *
 * <p>Running the rebalancer in {@code dry-run} mode will only return the target instance and segment assignment without
 * applying any change to the cluster. This mode returns immediately.
 *
 * <p>If instance reassignment is enabled, the rebalancer will reassign the instances based on the instance assignment
 * config from the table config, persist the instance partitions if not in {@code dry-run} mode, and reassign segments
 * based on the new instance assignment. Otherwise, the rebalancer will skip the instance reassignment and reassign
 * segments based on the existing instance assignment.
 *
 * <p>For segment reassignment, 2 modes are offered:
 * <ul>
 *   <li>
 *     With-downtime rebalance: the IdealState is replaced with the target segment assignment in one go and there are no
 *     guarantees around replica availability. This mode returns immediately without waiting for ExternalView to reach
 *     the target segment assignment. Disabled tables will always be rebalanced with downtime.
 *   </li>
 *   <li>
 *     No-downtime rebalance: care is taken to ensure that the configured number of replicas of any segment are
 *     available (ONLINE or CONSUMING) at all times. This mode returns after ExternalView reaching the target segment
 *     assignment.
 *     <p>In the following edge case scenarios, if {@code best-efforts} is disabled, rebalancer will fail the rebalance
 *     because the no-downtime contract cannot be achieved, and table might end up in a middle stage. User needs to
 *     check the rebalance result, solve the issue, and run the rebalance again if necessary. If {@code best-efforts} is
 *     enabled, rebalancer will log a warning and continue the rebalance, but the no-downtime contract will not be
 *     guaranteed.
 *     <ul>
 *       <li>
 *         Segment falls into ERROR state in ExternalView -> with best-efforts, count ERROR state as good state
 *       </li>
 *       <li>
 *         ExternalView has not converged within the maximum wait time -> with best-efforts, continue to the next stage
 *       </li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>NOTE: If the controller that handles the rebalance goes down/restarted, the rebalance isn't automatically resumed
 * by other controllers.
 */
public class TableRebalancer {
  private static final Logger LOGGER = LoggerFactory.getLogger(TableRebalancer.class);

  // TODO: make them configurable
  private static final long EXTERNAL_VIEW_CHECK_INTERVAL_MS = 1_000L; // 1 second
  private static final long EXTERNAL_VIEW_STABILIZATION_MAX_WAIT_MS = 60 * 60_000L; // 1 hour

  private final HelixManager _helixManager;
  private final HelixDataAccessor _helixDataAccessor;

  public TableRebalancer(HelixManager helixManager) {
    _helixManager = helixManager;
    _helixDataAccessor = helixManager.getHelixDataAccessor();
  }

  public RebalanceResult rebalance(TableConfig tableConfig, Configuration rebalanceConfig) {
    long startTimeMs = System.currentTimeMillis();
    String tableNameWithType = tableConfig.getTableName();

    boolean dryRun =
        rebalanceConfig.getBoolean(RebalanceConfigConstants.DRY_RUN, RebalanceConfigConstants.DEFAULT_DRY_RUN);
    boolean reassignInstances = rebalanceConfig
        .getBoolean(RebalanceConfigConstants.REASSIGN_INSTANCES, RebalanceConfigConstants.DEFAULT_REASSIGN_INSTANCES);
    boolean includeConsuming = rebalanceConfig
        .getBoolean(RebalanceConfigConstants.INCLUDE_CONSUMING, RebalanceConfigConstants.DEFAULT_INCLUDE_CONSUMING);
    boolean downtime =
        rebalanceConfig.getBoolean(RebalanceConfigConstants.DOWNTIME, RebalanceConfigConstants.DEFAULT_DOWNTIME);
    int minReplicasToKeepUpForNoDowntime = rebalanceConfig
        .getInt(RebalanceConfigConstants.MIN_REPLICAS_TO_KEEP_UP_FOR_NO_DOWNTIME,
            RebalanceConfigConstants.DEFAULT_MIN_REPLICAS_TO_KEEP_UP_FOR_NO_DOWNTIME);
    boolean bestEfforts = rebalanceConfig
        .getBoolean(RebalanceConfigConstants.BEST_EFFORTS, RebalanceConfigConstants.DEFAULT_BEST_EFFORTS);
    LOGGER.info(
        "Start rebalancing table: {} with dryRun: {}, reassignInstances: {}, includeConsuming: {}, downtime: {}, minReplicasToKeepUpForNoDowntime: {}, bestEfforts: {}",
        tableNameWithType, dryRun, reassignInstances, includeConsuming, downtime, minReplicasToKeepUpForNoDowntime,
        bestEfforts);

    // Validate table config
    try {
      // Do not allow rebalancing HLC real-time table
      if (tableConfig.getTableType() == TableType.REALTIME && new StreamConfig(tableNameWithType,
          tableConfig.getIndexingConfig().getStreamConfigs()).hasHighLevelConsumerType()) {
        LOGGER.warn("Cannot rebalance table: {} with high-level consumer, aborting the rebalance", tableNameWithType);
        return new RebalanceResult(RebalanceResult.Status.FAILED, "Cannot rebalance table with high-level consumer",
            null, null);
      }
    } catch (Exception e) {
      LOGGER.warn("Caught exception while validating table config for table: {}, aborting the rebalance",
          tableNameWithType, e);
      return new RebalanceResult(RebalanceResult.Status.FAILED, "Caught exception while validating table config: " + e,
          null, null);
    }

    // Fetch ideal state
    PropertyKey idealStatePropertyKey = _helixDataAccessor.keyBuilder().idealStates(tableNameWithType);
    IdealState currentIdealState;
    try {
      currentIdealState = _helixDataAccessor.getProperty(idealStatePropertyKey);
    } catch (Exception e) {
      LOGGER.warn("Caught exception while fetching IdealState for table: {}, aborting the rebalance", tableNameWithType,
          e);
      return new RebalanceResult(RebalanceResult.Status.FAILED, "Caught exception while fetching IdealState: " + e,
          null, null);
    }
    if (currentIdealState == null) {
      LOGGER.warn("Cannot find the IdealState for table: {}, aborting the rebalance", tableNameWithType);
      return new RebalanceResult(RebalanceResult.Status.FAILED, "Cannot find the IdealState for table", null, null);
    }
    if (!currentIdealState.isEnabled() && !downtime) {
      LOGGER.warn("Cannot rebalance disabled table: {} without downtime, aborting the rebalance", tableNameWithType);
      return new RebalanceResult(RebalanceResult.Status.FAILED, "Cannot rebalance disabled table without downtime",
          null, null);
    }

    LOGGER.info("Fetching/calculating instance partitions for table: {}", tableNameWithType);
    Map<InstancePartitionsType, InstancePartitions> instancePartitionsMap = new TreeMap<>();
    try {
      if (tableConfig.getTableType() == TableType.OFFLINE) {
        instancePartitionsMap.put(InstancePartitionsType.OFFLINE,
            getInstancePartitions(tableConfig, InstancePartitionsType.OFFLINE, reassignInstances, dryRun));
      } else {
        instancePartitionsMap.put(InstancePartitionsType.CONSUMING,
            getInstancePartitions(tableConfig, InstancePartitionsType.CONSUMING, reassignInstances, dryRun));
        instancePartitionsMap.put(InstancePartitionsType.COMPLETED,
            getInstancePartitions(tableConfig, InstancePartitionsType.COMPLETED, reassignInstances, dryRun));
      }
    } catch (Exception e) {
      LOGGER
          .warn("Caught exception while fetching/calculating instance partitions for table: {}, aborting the rebalance",
              tableNameWithType, e);
      return new RebalanceResult(RebalanceResult.Status.FAILED,
          "Caught exception while fetching/calculating instance partitions: " + e, null, null);
    }

    LOGGER.info("Calculating the target assignment for table: {}", tableNameWithType);
    SegmentAssignment segmentAssignment = SegmentAssignmentFactory.getSegmentAssignment(_helixManager, tableConfig);
    Map<String, Map<String, String>> currentAssignment = currentIdealState.getRecord().getMapFields();
    Map<String, Map<String, String>> targetAssignment;
    try {
      targetAssignment = segmentAssignment.rebalanceTable(currentAssignment, instancePartitionsMap, rebalanceConfig);
    } catch (Exception e) {
      LOGGER.warn("Caught exception while calculating target assignment for table: {}, aborting the rebalance",
          tableNameWithType, e);
      return new RebalanceResult(RebalanceResult.Status.FAILED,
          "Caught exception while calculating target assignment: " + e, instancePartitionsMap, null);
    }

    if (currentAssignment.equals(targetAssignment)) {
      LOGGER.info("Table: {} is already balanced", tableNameWithType);
      if (reassignInstances) {
        if (dryRun) {
          return new RebalanceResult(RebalanceResult.Status.DONE,
              "Instance reassigned in dry-run mode, table is already balanced", instancePartitionsMap,
              targetAssignment);
        } else {
          return new RebalanceResult(RebalanceResult.Status.DONE, "Instance reassigned, table is already balanced",
              instancePartitionsMap, targetAssignment);
        }
      } else {
        return new RebalanceResult(RebalanceResult.Status.NO_OP, "Table is already balanced", instancePartitionsMap,
            targetAssignment);
      }
    }

    if (dryRun) {
      LOGGER.info("Rebalancing table: {} in dry-run mode, returning the target assignment", tableNameWithType);
      return new RebalanceResult(RebalanceResult.Status.DONE, "Dry-run mode", instancePartitionsMap, targetAssignment);
    }

    if (downtime) {
      LOGGER.info("Rebalancing table: {} with downtime", tableNameWithType);

      while (true) {
        // Reuse current IdealState to update the IdealState in cluster
        ZNRecord idealStateRecord = currentIdealState.getRecord();
        idealStateRecord.setMapFields(targetAssignment);
        currentIdealState.setNumPartitions(targetAssignment.size());
        currentIdealState.setReplicas(Integer.toString(targetAssignment.values().iterator().next().size()));

        // Check version and update IdealState
        try {
          Preconditions.checkState(_helixDataAccessor.getBaseDataAccessor()
              .set(idealStatePropertyKey.getPath(), idealStateRecord, idealStateRecord.getVersion(),
                  AccessOption.PERSISTENT), "Failed to update IdealState");
          LOGGER.info("Finished rebalancing table: {} with downtime in {}ms.", tableNameWithType,
              System.currentTimeMillis() - startTimeMs);
          return new RebalanceResult(RebalanceResult.Status.DONE,
              "Success with downtime (replaced IdealState with the target segment assignment, ExternalView might not reach the target segment assignment yet)",
              instancePartitionsMap, targetAssignment);
        } catch (ZkBadVersionException e) {
          LOGGER.info("IdealState version changed for table: {}, re-calculating the target assignment",
              tableNameWithType);
          try {
            IdealState idealState = _helixDataAccessor.getProperty(idealStatePropertyKey);
            // IdealState might be null if table got deleted, throwing exception to abort the rebalance
            Preconditions.checkState(idealState != null, "Failed to find the IdealState");
            currentIdealState = idealState;
            currentAssignment = currentIdealState.getRecord().getMapFields();
            targetAssignment =
                segmentAssignment.rebalanceTable(currentAssignment, instancePartitionsMap, rebalanceConfig);
          } catch (Exception e1) {
            LOGGER.warn(
                "Caught exception while re-calculating the target assignment for table: {}, aborting the rebalance",
                tableNameWithType, e1);
            return new RebalanceResult(RebalanceResult.Status.FAILED,
                "Caught exception while re-calculating the target assignment: " + e1, instancePartitionsMap,
                targetAssignment);
          }
        } catch (Exception e) {
          LOGGER.warn("Caught exception while updating IdealState for table: {}, aborting the rebalance",
              tableNameWithType, e);
          return new RebalanceResult(RebalanceResult.Status.FAILED, "Caught exception while updating IdealState: " + e,
              instancePartitionsMap, targetAssignment);
        }
      }
    }

    // Calculate the min available replicas for no-downtime rebalance
    int numCurrentReplicas = currentAssignment.values().iterator().next().size();
    int numTargetReplicas = targetAssignment.values().iterator().next().size();
    // Use the smaller one to determine the min available replicas
    int numReplicas = Math.min(numCurrentReplicas, numTargetReplicas);
    int minAvailableReplicas;
    if (minReplicasToKeepUpForNoDowntime >= 0) {
      // For non-negative value, use it as min available replicas
      if (minReplicasToKeepUpForNoDowntime >= numReplicas) {
        LOGGER.warn(
            "Illegal config for minReplicasToKeepUpForNoDowntime: {} for table: {}, must be less than number of replicas (current: {}, target: {}), aborting the rebalance",
            minReplicasToKeepUpForNoDowntime, tableNameWithType, numCurrentReplicas, numTargetReplicas);
        return new RebalanceResult(RebalanceResult.Status.FAILED, "Illegal min available replicas config",
            instancePartitionsMap, targetAssignment);
      }
      minAvailableReplicas = minReplicasToKeepUpForNoDowntime;
    } else {
      // For negative value, use it as max unavailable replicas
      minAvailableReplicas = Math.max(numReplicas + minReplicasToKeepUpForNoDowntime, 0);
    }

    LOGGER.info("Rebalancing table: {} with minAvailableReplicas: {}, bestEfforts: {}", tableNameWithType,
        minAvailableReplicas, bestEfforts);
    int expectedVersion = currentIdealState.getRecord().getVersion();
    while (true) {
      // Wait for ExternalView to converge before updating the next IdealState
      IdealState idealState;
      try {
        idealState = waitForExternalViewToConverge(tableNameWithType, bestEfforts);
      } catch (Exception e) {
        LOGGER.warn("Caught exception while waiting for ExternalView to converge for table: {}, aborting the rebalance",
            tableNameWithType, e);
        return new RebalanceResult(RebalanceResult.Status.FAILED,
            "Caught exception while waiting for ExternalView to converge: " + e, instancePartitionsMap,
            targetAssignment);
      }

      // Re-calculate the target assignment if IdealState changed while waiting for ExternalView to converge
      if (idealState.getRecord().getVersion() != expectedVersion) {
        LOGGER.info(
            "IdealState version changed while waiting for ExternalView to converge for table: {}, re-calculating the target assignment",
            tableNameWithType);
        try {
          currentIdealState = idealState;
          currentAssignment = currentIdealState.getRecord().getMapFields();
          targetAssignment =
              segmentAssignment.rebalanceTable(currentAssignment, instancePartitionsMap, rebalanceConfig);
          expectedVersion = currentIdealState.getRecord().getVersion();
        } catch (Exception e) {
          LOGGER
              .warn("Caught exception while re-calculating the target assignment for table: {}, aborting the rebalance",
                  tableNameWithType, e);
          return new RebalanceResult(RebalanceResult.Status.FAILED,
              "Caught exception while re-calculating the target assignment: " + e, instancePartitionsMap,
              targetAssignment);
        }
      }

      if (currentAssignment.equals(targetAssignment)) {
        LOGGER.info("Finished rebalancing table: {} with minAvailableReplicas: {}, bestEfforts: {} in {}ms.",
            tableNameWithType, minAvailableReplicas, bestEfforts, System.currentTimeMillis() - startTimeMs);
        return new RebalanceResult(RebalanceResult.Status.DONE,
            "Success with minAvailableReplicas: " + minAvailableReplicas
                + " (both IdealState and ExternalView should reach the target segment assignment)",
            instancePartitionsMap, targetAssignment);
      }

      Map<String, Map<String, String>> nextAssignment =
          getNextAssignment(currentAssignment, targetAssignment, minAvailableReplicas);
      LOGGER.info("Got the next assignment for table: {} with number of segments to be moved to each instance: {}",
          tableNameWithType,
          SegmentAssignmentUtils.getNumSegmentsToBeMovedPerInstance(currentAssignment, nextAssignment));

      // Reuse current IdealState to update the IdealState in cluster
      ZNRecord idealStateRecord = currentIdealState.getRecord();
      idealStateRecord.setMapFields(nextAssignment);
      currentIdealState.setNumPartitions(nextAssignment.size());
      currentIdealState.setReplicas(Integer.toString(nextAssignment.values().iterator().next().size()));

      // Check version and update IdealState
      try {
        Preconditions.checkState(_helixDataAccessor.getBaseDataAccessor()
                .set(idealStatePropertyKey.getPath(), idealStateRecord, expectedVersion, AccessOption.PERSISTENT),
            "Failed to update IdealState");
        currentAssignment = nextAssignment;
        expectedVersion++;
        LOGGER.info("Successfully updated the IdealState for table: {}", tableNameWithType);
      } catch (ZkBadVersionException e) {
        LOGGER.info("Version changed while updating IdealState for table: {}", tableNameWithType);
      } catch (Exception e) {
        LOGGER
            .warn("Caught exception while updating IdealState for table: {}, aborting the rebalance", tableNameWithType,
                e);
        return new RebalanceResult(RebalanceResult.Status.FAILED, "Caught exception while updating IdealState: " + e,
            instancePartitionsMap, targetAssignment);
      }
    }
  }

  private InstancePartitions getInstancePartitions(TableConfig tableConfig,
      InstancePartitionsType instancePartitionsType, boolean reassignInstances, boolean dryRun) {
    String tableNameWithType = tableConfig.getTableName();
    if (reassignInstances) {
      if (InstanceAssignmentConfigUtils.allowInstanceAssignment(tableConfig, instancePartitionsType)) {
        LOGGER.info("Reassigning {} instances for table: {}", instancePartitionsType, tableNameWithType);
        InstanceAssignmentDriver instanceAssignmentDriver = new InstanceAssignmentDriver(tableConfig);
        InstancePartitions instancePartitions = instanceAssignmentDriver.assignInstances(instancePartitionsType,
            _helixDataAccessor.getChildValues(_helixDataAccessor.keyBuilder().instanceConfigs()));
        if (!dryRun) {
          LOGGER.info("Persisting instance partitions: {}", instancePartitions);
          InstancePartitionsUtils.persistInstancePartitions(_helixManager.getHelixPropertyStore(), instancePartitions);
        }
        return instancePartitions;
      } else {
        // Use default instance partitions if reassign is enabled and instance assignment is not allowed
        InstancePartitions instancePartitions = InstancePartitionsUtils
            .computeDefaultInstancePartitions(_helixManager, tableConfig, instancePartitionsType);
        LOGGER.warn("Cannot assign {} instances for table: {}, using default instance partitions: {}",
            instancePartitionsType, tableNameWithType, instancePartitions);
        if (!dryRun) {
          String instancePartitionsName = instancePartitions.getInstancePartitionsName();
          LOGGER.info("Removing instance partitions: {}", instancePartitionsName);
          InstancePartitionsUtils
              .removeInstancePartitions(_helixManager.getHelixPropertyStore(), instancePartitionsName);
        }
        return instancePartitions;
      }
    } else {
      return InstancePartitionsUtils
          .fetchOrComputeInstancePartitions(_helixManager, tableConfig, instancePartitionsType);
    }
  }

  private IdealState waitForExternalViewToConverge(String tableNameWithType, boolean bestEfforts)
      throws InterruptedException, TimeoutException {
    long endTimeMs = System.currentTimeMillis() + EXTERNAL_VIEW_STABILIZATION_MAX_WAIT_MS;

    IdealState idealState;
    do {
      idealState = _helixDataAccessor.getProperty(_helixDataAccessor.keyBuilder().idealStates(tableNameWithType));
      // IdealState might be null if table got deleted, throwing exception to abort the rebalance
      Preconditions.checkState(idealState != null, "Failed to find the IdealState");

      ExternalView externalView =
          _helixDataAccessor.getProperty(_helixDataAccessor.keyBuilder().externalView(tableNameWithType));
      // ExternalView might be null when table is just created, skipping check for this iteration
      if (externalView != null) {
        if (isExternalViewConverged(tableNameWithType, externalView.getRecord().getMapFields(),
            idealState.getRecord().getMapFields(), bestEfforts)) {
          LOGGER.info("ExternalView converged for table: {}", tableNameWithType);
          return idealState;
        }
      }

      Thread.sleep(EXTERNAL_VIEW_CHECK_INTERVAL_MS);
    } while (System.currentTimeMillis() < endTimeMs);

    if (bestEfforts) {
      LOGGER.warn("ExternalView has not converged within: {}ms for table: {}, continuing the rebalance (best-efforts)",
          EXTERNAL_VIEW_STABILIZATION_MAX_WAIT_MS, tableNameWithType);
      return idealState;
    } else {
      throw new TimeoutException("Timeout while waiting for ExternalView to converge");
    }
  }

  /**
   * NOTE: Only check the segments and instances in the IdealState. It is okay to have extra segments or instances in
   * ExternalView as long as the instance states for all the segments in IdealState are reached. For ERROR state in
   * ExternalView, if using best-efforts, log a warning and treat it as good state; if not, throw an exception to abort
   * the rebalance because we are not able to get out of the ERROR state.
   */
  @VisibleForTesting
  static boolean isExternalViewConverged(String tableNameWithType,
      Map<String, Map<String, String>> externalViewSegmentStates,
      Map<String, Map<String, String>> idealStateSegmentStates, boolean bestEfforts) {
    for (Map.Entry<String, Map<String, String>> entry : idealStateSegmentStates.entrySet()) {
      String segmentName = entry.getKey();
      Map<String, String> externalViewInstanceStateMap = externalViewSegmentStates.get(segmentName);
      Map<String, String> idealStateInstanceStateMap = entry.getValue();

      for (Map.Entry<String, String> instanceStateEntry : idealStateInstanceStateMap.entrySet()) {
        // Ignore OFFLINE state in IdealState
        String idealStateInstanceState = instanceStateEntry.getValue();
        if (idealStateInstanceState.equals(RealtimeSegmentOnlineOfflineStateModel.OFFLINE)) {
          continue;
        }

        // ExternalView should contain the segment
        if (externalViewInstanceStateMap == null) {
          return false;
        }

        // Check whether the instance state in ExternalView matches the IdealState
        String instanceName = instanceStateEntry.getKey();
        String externalViewInstanceState = externalViewInstanceStateMap.get(instanceName);
        if (!idealStateInstanceState.equals(externalViewInstanceState)) {
          if (RealtimeSegmentOnlineOfflineStateModel.ERROR.equals(externalViewInstanceState)) {
            if (bestEfforts) {
              LOGGER
                  .warn("Found ERROR instance: {} for segment: {}, table: {}, counting it as good state (best-efforts)",
                      instanceName, segmentName, tableNameWithType);
            } else {
              LOGGER.warn("Found ERROR instance: {} for segment: {}, table: {}", instanceName, segmentName,
                  tableNameWithType);
              throw new IllegalStateException("Found segments in ERROR state");
            }
          } else {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static Map<String, Map<String, String>> getNextAssignment(Map<String, Map<String, String>> currentAssignment,
      Map<String, Map<String, String>> targetAssignment, int minAvailableReplicas) {
    Map<String, Map<String, String>> nextAssignment = new TreeMap<>();

    for (Map.Entry<String, Map<String, String>> entry : currentAssignment.entrySet()) {
      String segmentName = entry.getKey();
      nextAssignment.put(segmentName,
          getNextInstanceStateMap(entry.getValue(), targetAssignment.get(segmentName), minAvailableReplicas));
    }

    return nextAssignment;
  }

  @VisibleForTesting
  @SuppressWarnings("Duplicates")
  static Map<String, String> getNextInstanceStateMap(Map<String, String> currentInstanceStateMap,
      Map<String, String> targetInstanceStateMap, int minAvailableReplicas) {
    Map<String, String> nextInstanceStateMap = new TreeMap<>();

    // Add all the common instances
    for (Map.Entry<String, String> entry : targetInstanceStateMap.entrySet()) {
      String instanceName = entry.getKey();
      if (currentInstanceStateMap.containsKey(instanceName)) {
        nextInstanceStateMap.put(instanceName, entry.getValue());
      }
    }

    // Add current instances until the min available replicas achieved
    int instancesToKeep = minAvailableReplicas - nextInstanceStateMap.size();
    if (instancesToKeep > 0) {
      for (Map.Entry<String, String> entry : currentInstanceStateMap.entrySet()) {
        String instanceName = entry.getKey();
        if (!nextInstanceStateMap.containsKey(instanceName)) {
          nextInstanceStateMap.put(instanceName, entry.getValue());
          if (--instancesToKeep == 0) {
            break;
          }
        }
      }
    }

    // Add target instances until the number of instances matched
    int instancesToAdd = targetInstanceStateMap.size() - nextInstanceStateMap.size();
    if (instancesToAdd > 0) {
      for (Map.Entry<String, String> entry : targetInstanceStateMap.entrySet()) {
        String instanceName = entry.getKey();
        if (!nextInstanceStateMap.containsKey(instanceName)) {
          nextInstanceStateMap.put(instanceName, entry.getValue());
          if (--instancesToAdd == 0) {
            break;
          }
        }
      }
    }

    return nextInstanceStateMap;
  }
}
