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
        if (config == null) {
            throw new IllegalArgumentException("Shard configuration is null");
        }

        ShardingMode mode = config.getMode();

        if (router instanceof FailsafeShardRouter) {
            return;
        }

        if (mode == ShardingMode.BFT_SHARD) {
            validateBftShardConfig(config, validateConnectivity);
            if (!(router instanceof BftShardRouter)) {
                throw new IllegalArgumentException(
                    "bft-shard config requires BftShardRouter, got: " + router.getClass());
            }
        } else {
            validateAppShardConfig(config, validateConnectivity);
            if (router instanceof DefaultShardRouter defaultRouter) {
                validateTreeCompleteness(defaultRouter.getRootNode());
            } else {
                throw new IllegalArgumentException(
                    "app-shard config requires DefaultShardRouter, got: " + router.getClass());
            }
        }
    }

    private static void validateAppShardConfig(ShardConfig config, boolean validateConnectivity) {
        if (config.getShards() == null || config.getShards().isEmpty()) {
            throw new IllegalArgumentException("Shard configuration has no shards");
        }
        if (config.getBftShards() != null && !config.getBftShards().isEmpty()) {
            throw new IllegalArgumentException(
                "app-shard mode must not populate 'bftShards'; set mode to 'bft-shard' or remove the entries");
        }
        validateUniqueShardIds(config.getShards(), validateConnectivity);
    }

    private static void validateBftShardConfig(ShardConfig config, boolean validateConnectivity) {
        List<BftShardInfo> bftShards = config.getBftShards();
        if (bftShards == null || bftShards.isEmpty()) {
            throw new IllegalArgumentException("bft-shard configuration has no bftShards entries");
        }
        if (config.getShards() != null && !config.getShards().isEmpty()) {
            throw new IllegalArgumentException(
                "bft-shard mode must not populate 'shards'; set mode to 'app-shard' or remove the entries");
        }

        Set<String> seenPrefixes = new HashSet<>();
        for (BftShardInfo shard : bftShards) {
            if (!seenPrefixes.add(shard.prefix())) {
                throw new IllegalArgumentException("Duplicate bft shard prefix: '" + shard.prefix() + "'");
            }
            validateUrl(shard.url(), "bft shard '" + shard.prefix() + "'");
        }

        validatePrefixFreeAndCovering(bftShards);

        if (validateConnectivity) {
            for (BftShardInfo shard : bftShards) {
                validateShardConnectivity(shard.url(), "bft shard '" + shard.prefix() + "'");
            }
        }
    }

    /**
     * Enforces that the prefix set is both prefix-free (no prefix is a prefix
     * of another) and covering (every possible bitstring has exactly one
     * matching prefix).
     *
     * <p>Checked by inserting each prefix into a binary tree and then verifying
     * that every internal node has two children and every leaf has zero.
     */
    private static void validatePrefixFreeAndCovering(List<BftShardInfo> shards) {
        PrefixNode root = new PrefixNode();
        for (BftShardInfo shard : shards) {
            insertPrefix(root, shard.prefix());
        }
        validateCoverage(root, "");
    }

    private static void insertPrefix(PrefixNode root, String prefix) {
        PrefixNode current = root;
        for (int i = 0; i < prefix.length(); i++) {
            if (current.terminal) {
                throw new IllegalArgumentException(
                    "bft shard prefixes are not prefix-free: '" + prefix + "' extends an existing shard");
            }
            char bit = prefix.charAt(i);
            if (bit == '0') {
                if (current.zero == null) current.zero = new PrefixNode();
                current = current.zero;
            } else {
                if (current.one == null) current.one = new PrefixNode();
                current = current.one;
            }
        }
        if (current.terminal) {
            throw new IllegalArgumentException(
                "Duplicate bft shard prefix: '" + prefix + "'");
        }
        if (current.zero != null || current.one != null) {
            throw new IllegalArgumentException(
                "bft shard prefixes are not prefix-free: '" + prefix + "' is a prefix of an existing shard");
        }
        current.terminal = true;
    }

    private static void validateCoverage(PrefixNode node, String path) {
        if (node.terminal) {
            return;
        }
        if (node.zero == null) {
            throw new IllegalArgumentException(
                "bft shard set is not covering: no shard matches stateIds with prefix '" + path + "0'");
        }
        if (node.one == null) {
            throw new IllegalArgumentException(
                "bft shard set is not covering: no shard matches stateIds with prefix '" + path + "1'");
        }
        validateCoverage(node.zero, path + "0");
        validateCoverage(node.one, path + "1");
    }

    private static final class PrefixNode {
        PrefixNode zero;
        PrefixNode one;
        boolean terminal;
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

            validateUrl(shard.url(), "Shard " + shard.id());
        }

        if (validateConnectivity) {
            for (ShardInfo shard : shards) {
                validateShardConnectivity(shard.url(), "Shard " + shard.id());
            }
        }
    }

    private static void validateUrl(String url, String shardLabel) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(shardLabel + " has empty or null URL");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(shardLabel + " has malformed URL: " + url + " - " + e.getMessage());
        }

        if (uri.getQuery() != null) {
            throw new IllegalArgumentException(shardLabel + " URL must not contain query parameters: " + url);
        }

        if (uri.getFragment() != null) {
            throw new IllegalArgumentException(shardLabel + " URL must not contain fragment: " + url);
        }

        if (uri.getScheme() == null) {
            throw new IllegalArgumentException(shardLabel + " URL must have a scheme (http or https): " + url);
        }

        if (uri.getHost() == null) {
            throw new IllegalArgumentException(shardLabel + " URL must have a host: " + url);
        }
    }

    private static void validateShardConnectivity(String url, String shardLabel) {
        logger.debug("Validating connectivity to {} at {}", shardLabel, url);
        try {
            JsonRpcAggregatorClient client = new JsonRpcAggregatorClient(url);
            long blockHeight = client.getBlockHeight().get();
            logger.debug("{} at {} is reachable (block height: {})", shardLabel, url, blockHeight);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("%s at %s is not reachable or not a valid aggregator: %s",
                    shardLabel, url, e.getMessage()),
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
                    depth, pathBits.isBlank() ? "root" : pathBits)
            );
        }

        if (node.isLeaf()) {
            if (node.getTargetUrl() == null || node.getTargetUrl().isBlank()) {
                throw new IllegalArgumentException(
                    String.format("Leaf node has no target URL at depth %d (path: %s)",
                        depth, pathBits.isBlank() ? "root" : pathBits)
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
