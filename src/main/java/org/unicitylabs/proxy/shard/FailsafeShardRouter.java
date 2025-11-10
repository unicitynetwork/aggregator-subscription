package org.unicitylabs.proxy.shard;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A failsafe shard router that allows the application to start even when
 * the shard configuration is invalid. This router throws exceptions when
 * used for actual routing, but allows the Admin UI to remain accessible
 * so that the configuration can be fixed.
 */
public class FailsafeShardRouter implements ShardRouter {
    public FailsafeShardRouter() {
    }

    @Override
    public String routeByRequestId(String requestIdHex) {
        throw new IllegalStateException(
            "Shard routing is unavailable due to invalid configuration. " +
            "Please fix the shard configuration via the Admin UI."
        );
    }

    @Override
    public Optional<String> routeByShardId(String shardId) {
        throw new IllegalStateException(
            "Shard routing is unavailable due to invalid configuration. " +
            "Please fix the shard configuration via the Admin UI."
        );
    }

    @Override
    public String getRandomTarget() {
        throw new IllegalStateException(
            "Shard routing is unavailable due to invalid configuration. " +
            "Please fix the shard configuration via the Admin UI."
        );
    }

    @Override
    public List<String> getAllTargets() {
        // Return empty list instead of throwing, as this might be called for informational purposes
        return Collections.emptyList();
    }
}
