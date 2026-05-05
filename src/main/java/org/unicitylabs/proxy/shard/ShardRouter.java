package org.unicitylabs.proxy.shard;

import java.util.List;
import java.util.Optional;

public interface ShardRouter {
    /**
     * Returns the sharding mode this router implements.
     */
    ShardingMode getMode();

    /**
     * Route by v2 {@code stateId} (32 raw bytes, hex-encoded; 34-byte legacy
     * imprints are rejected). Both modes use this method as the sole
     * routing-by-key entry point; only the bit-matching rule differs.
     */
    String routeByStateId(String stateIdHex);

    /**
     * Route by explicit shard ID label. Interpretation is mode-specific:
     * app-shard parses it as a sentinel-prefixed integer; bft-shard matches
     * it as a prefix bitstring (e.g. {@code "0"}, {@code "10"}, {@code "101"}).
     *
     * @return the target URL, or {@link Optional#empty()} if no shard matches
     */
    Optional<String> routeByShardId(String shardIdLabel);

    String getRandomTarget();

    List<String> getAllTargets();
}
