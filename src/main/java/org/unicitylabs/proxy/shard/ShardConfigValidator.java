package org.unicitylabs.proxy.shard;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

public class ShardConfigValidator {
    public static void validate(ShardRouter router, ShardConfig config) {
        if (config.getShards() == null || config.getShards().isEmpty()) {
            throw new IllegalArgumentException("Shard configuration has no shards");
        }

        validateUniqueShardIds(config.getShards());

        if (router instanceof DefaultShardRouter defaultRouter) {
            validateTreeCompleteness(defaultRouter.getRootNode());
        } else if (! (router instanceof FailsafeShardRouter)) {
            throw new IllegalArgumentException("Unsupported Router instance: " + router.getClass());
        }
    }

    private static void validateUniqueShardIds(List<ShardInfo> shards) {
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

        // Ensure no query parameters
        if (uri.getQuery() != null) {
            throw new IllegalArgumentException("Shard " + shardId + " URL must not contain query parameters: " + url);
        }

        // Ensure no fragment
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("Shard " + shardId + " URL must not contain fragment: " + url);
        }

        // Ensure scheme is present
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("Shard " + shardId + " URL must have a scheme (http or https): " + url);
        }

        // Ensure host is present
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Shard " + shardId + " URL must have a host: " + url);
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
