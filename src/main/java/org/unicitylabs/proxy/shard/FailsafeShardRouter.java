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
    public ShardingMode getMode() {
        return ShardingMode.APP_SHARD;
    }

    @Override
    public String routeByStateId(String stateIdHex) {
        throw new IllegalStateException(unavailableMessage());
    }

    @Override
    public Optional<String> routeByShardId(String shardIdLabel) {
        throw new IllegalStateException(unavailableMessage());
    }

    @Override
    public String getRandomTarget() {
        throw new IllegalStateException(unavailableMessage());
    }

    @Override
    public List<String> getAllTargets() {
        return Collections.emptyList();
    }

    private static String unavailableMessage() {
        return "Shard routing is unavailable due to invalid configuration. " +
            "Please fix the shard configuration via the Admin UI.";
    }
}
