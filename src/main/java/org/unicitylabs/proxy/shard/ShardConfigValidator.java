package org.unicitylabs.proxy.shard;

import java.util.*;

public class ShardConfigValidator {
    public static void validate(ShardRouter router, ShardConfig config) {
        if (config.getShards() == null || config.getShards().isEmpty()) {
            throw new IllegalArgumentException("Shard configuration has no shards");
        }

        if (router instanceof DefaultShardRouter defaultRouter) {
            validateTreeCompleteness(defaultRouter.getRootNode());
        } else if (! (router instanceof FailsafeShardRouter)) {
            throw new IllegalArgumentException("Unsupported Router instance: " + router.getClass());
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
