package io.ray.streaming.runtime.util;

import io.ray.api.Ray;
import io.ray.api.id.UniqueId;
import io.ray.api.runtimecontext.NodeInfo;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** RayUtils is the utility class to access ray runtime api. */
public class RayUtils {

  /**
   * Get all node info from GCS
   *
   * @return node info list
   */
  public static List<NodeInfo> getAllNodeInfo() {
    if (Ray.getRuntimeContext().isLocalMode()) {
      // only for single process(for unit test)
      return mockContainerResources();
    }
    return Ray.getRuntimeContext().getAllNodeInfo();
  }

  /**
   * Get all alive node info map
   *
   * @return node info map, key is unique node id , value is node info
   */
  public static Map<UniqueId, NodeInfo> getAliveNodeInfoMap() {
    return getAllNodeInfo().stream()
        .filter(nodeInfo -> nodeInfo.isAlive)
        .collect(Collectors.toMap(nodeInfo -> nodeInfo.nodeId, nodeInfo -> nodeInfo));
  }

  private static List<NodeInfo> mockContainerResources() {
    List<NodeInfo> nodeInfos = new LinkedList<>();

    for (int i = 1; i <= 5; i++) {
      Map<String, Double> resources = new HashMap<>();
      resources.put("CPU", (double) i);
      resources.put("MEM", 16.0);

      byte[] nodeIdBytes = new byte[UniqueId.LENGTH];
      for (int byteIndex = 0; byteIndex < UniqueId.LENGTH; ++byteIndex) {
        nodeIdBytes[byteIndex] = String.valueOf(i).getBytes()[0];
      }
      NodeInfo nodeInfo =
          new NodeInfo(
              new UniqueId(nodeIdBytes),
              "localhost" + i,
              "localhost" + i,
              -1,
              "",
              "",
              true,
              resources,
              new HashMap<>());
      nodeInfos.add(nodeInfo);
    }
    return nodeInfos;
  }

  public static boolean isInClusterMode() {
    if (Ray.isInitialized() && null != Ray.internal() && !Ray.getRuntimeContext().isLocalMode()) {
      return true;
    }
    return false;
  }

  public static boolean externalTsdbEnabled() {
    return null != System.getenv("ENABLE_TSDB_STATS");
  }
}
