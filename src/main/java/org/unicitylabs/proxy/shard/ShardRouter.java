package org.unicitylabs.proxy.shard;

import java.util.List;
import java.util.Optional;

public interface ShardRouter {
    String routeByRequestId(String requestIdHex);

    Optional<String> routeByShardId(int shardId);

    String getRandomTarget();

    List<String> getAllTargets();
}
