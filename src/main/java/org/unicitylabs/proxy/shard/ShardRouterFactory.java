package org.unicitylabs.proxy.shard;

/**
 * Builds the appropriate {@link ShardRouter} for a given {@link ShardConfig}.
 *
 * <p>Centralizes router selection so that every call site doesn't need to
 * know about the mode enum or which implementation maps to which mode.
 */
public final class ShardRouterFactory {
    private ShardRouterFactory() {
    }

    public static ShardRouter create(ShardConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("ShardConfig cannot be null");
        }
        return switch (config.getMode()) {
            case APP_SHARD -> new DefaultShardRouter(config);
            case BFT_SHARD -> new BftShardRouter(config);
        };
    }
}
