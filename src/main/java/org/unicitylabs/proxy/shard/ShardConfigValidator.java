package org.unicitylabs.proxy.shard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

public class ShardConfigValidator {
    private static final Logger logger = LoggerFactory.getLogger(ShardConfigValidator.class);

    /**
     * Validates shard configuration with connectivity checks enabled.
     */
    public static void validate(ShardRouter router, ShardConfig config) {
        validate(router, config, true);
    }

    /**
     * Validates shard configuration.
     * @param router the shard router
     * @param config the shard configuration
     * @param validateConnectivity whether to validate connectivity by calling getBlockHeight on each shard
     */
    public static void validate(ShardRouter router, ShardConfig config, boolean validateConnectivity) {
        if (config.getShards() == null || config.getShards().isEmpty()) {
            throw new IllegalArgumentException("Shard configuration has no shards");
        }

        validateUniqueShardIds(config.getShards(), validateConnectivity);

        if (router instanceof DefaultShardRouter defaultRouter) {
            validateTreeCompleteness(defaultRouter.getRootNode());
        } else if (! (router instanceof FailsafeShardRouter)) {
            throw new IllegalArgumentException("Unsupported Router instance: " + router.getClass());
        }
    }

    private static void validateUniqueShardIds(List<ShardInfo> shards, boolean validateConnectivity) {
        if (shards == null) {
            return;
        }

        Set<Integer> seenIds = new HashSet<>();
        for (ShardInfo shard : shards) {
            if (!seenIds.add(shard.id())) {
                throw new IllegalArgumentException("Duplicate shard ID: " + shard.id());
            }

            validateShardUrl(shard.url(), shard.id());
        }

        if (validateConnectivity) {
            for (ShardInfo shard : shards) {
                validateShardConnectivity(shard.url(), shard.id());
            }
        }
    }

    private static void validateShardUrl(String url, int shardId) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Shard " + shardId + " has empty or null URL");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Shard " + shardId + " has malformed URL: " + url + " - " + e.getMessage());
        }

        if (uri.getQuery() != null) {
            throw new IllegalArgumentException("Shard " + shardId + " URL must not contain query parameters: " + url);
        }

        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("Shard " + shardId + " URL must not contain fragment: " + url);
        }

        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("Shard " + shardId + " URL must have a scheme (http or https): " + url);
        }

        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Shard " + shardId + " URL must have a host: " + url);
        }
    }

    private static void validateShardConnectivity(String url, int shardId) {
        logger.debug("Validating connectivity to shard {} at {}", shardId, url);
        try {
            JsonRpcAggregatorClient client = new JsonRpcAggregatorClient(url);
            long blockHeight = client.getBlockHeight().get();
            logger.debug("Shard {} at {} is reachable (block height: {})", shardId, url, blockHeight);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("Shard %d at %s is not reachable or not a valid aggregator: %s",
                    shardId, url, e.getMessage()),
                e
            );
        }
    }

    private static void validateTreeCompleteness(ShardTreeNode node) {
        validateTreeCompleteness(node, "", 0);
    }

    private static void validateTreeCompleteness(ShardTreeNode node, String pathBits, int depth) {
        if (node == null) {
            throw new IllegalArgumentException(
                String.format("Routing tree has null node at depth %d (path: %s)",
                    depth, pathBits.isEmpty() ? "root" : pathBits)
            );
        }

        if (node.isLeaf()) {
            if (node.getTargetUrl() == null || node.getTargetUrl().isEmpty()) {
                throw new IllegalArgumentException(
                    String.format("Leaf node has no target URL at depth %d (path: %s)",
                        depth, pathBits.isEmpty() ? "root" : pathBits)
                );
            }
        } else {
            if (node.getLeft() == null) {
                throw new IllegalArgumentException(
                    String.format("Incomplete routing tree: missing left child for request IDs with binary suffix: 0%s", pathBits)
                );
            }
            if (node.getRight() == null) {
                throw new IllegalArgumentException(
                    String.format("Incomplete routing tree: missing right child for request IDs with binary suffix: 1%s", pathBits)
                );
            }

            validateTreeCompleteness(node.getLeft(), "0" + pathBits, depth + 1);
            validateTreeCompleteness(node.getRight(), "1" + pathBits, depth + 1);
        }
    }
}
