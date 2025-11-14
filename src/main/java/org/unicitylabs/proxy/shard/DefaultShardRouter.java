package org.unicitylabs.proxy.shard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Routes requests to shard targets based on request ID suffixes or explicit shard IDs.
 * Uses a binary tree index for O(log n) lookup performance.
 */
public class DefaultShardRouter implements ShardRouter {
    private static final Logger logger = LoggerFactory.getLogger(ShardRouter.class);

    private final List<ShardSuffix> suffixes;
    private final List<String> allTargets;
    private final Map<Integer, String> shardIdToUrl;
    private final Random random;
    private final ShardTreeNode rootNode;

    public DefaultShardRouter(ShardConfig config) {
        this.suffixes = new ArrayList<>();
        this.shardIdToUrl = new HashMap<>();
        this.random = new Random();

        // Parse all shard configurations
        for (ShardInfo shardInfo : config.getShards()) {
            ShardSuffix suffix = new ShardSuffix(shardInfo);
            suffixes.add(suffix);
            shardIdToUrl.put(shardInfo.id(), shardInfo.url());
        }

        // Extract unique target URLs for random selection
        this.allTargets = suffixes.stream()
            .map(ShardSuffix::getTargetUrl)
            .distinct()
            .toList();

        // Build binary tree index for fast lookup
        this.rootNode = buildRoutingTree();

        logger.info("ShardRouter initialized with {} shards, {} unique targets",
            suffixes.size(), allTargets.size());

        for (ShardSuffix suffix : suffixes) {
            logger.debug("  {}", suffix);
        }
    }

    /**
     * Build a binary routing tree from the suffix configuration.
     * The tree is traversed from LSB to MSB of the request ID.
     *
     * @return Root node of the routing tree
     */
    private ShardTreeNode buildRoutingTree() {
        ShardTreeNode root = new ShardTreeNode();

        for (ShardSuffix suffix : suffixes) {
            ShardTreeNode current = root;
            int bitLength = suffix.getBitLength();

            // Walk from LSB to MSB, creating nodes as needed
            for (int bitIndex = 0; bitIndex < bitLength; bitIndex++) {
                // Extract a bit at position bitIndex (0 = LSB)
                boolean bitValue = suffix.getSuffixBits().testBit(bitIndex);

                if (bitValue) {
                    // Bit is 1, go right
                    if (current.getRight() == null) {
                        current.setRight(new ShardTreeNode());
                    }
                    current = current.getRight();
                } else {
                    // Bit is 0, go left
                    if (current.getLeft() == null) {
                        current.setLeft(new ShardTreeNode());
                    }
                    current = current.getLeft();
                }
            }

            // Mark this node as a leaf with the target URL
            current.setTargetUrl(suffix.getTargetUrl());
        }

        return root;
    }

    /**
     * Route based on request ID using binary tree traversal.
     * Walks the tree from LSB to MSB until hitting a leaf node.
     *
     * @param requestIdHex Request ID as hex string (256 bits = 64 hex chars)
     * @return Target URL to route to
     */
    @Override
    public String routeByRequestId(String requestIdHex) {
        // Normalize: remove any 0x prefix if present
        String normalizedHex = requestIdHex.toLowerCase();
        if (normalizedHex.startsWith("0x")) {
            normalizedHex = normalizedHex.substring(2);
        }

        // Parse request ID to extract bits
        java.math.BigInteger requestId;
        try {
            requestId = new java.math.BigInteger(normalizedHex, 16);
        } catch (NumberFormatException e) {
            logger.warn("Invalid request ID format: {}, using random target", requestIdHex);
            throw new IllegalArgumentException("Invalid request ID format: '" + requestIdHex + "'");
        }

        // Walk the tree from LSB to MSB
        ShardTreeNode current = rootNode;
        int bitIndex = 0;

        while (current != null && !current.isLeaf()) {
            boolean bitValue = requestId.testBit(bitIndex);

            if (bitValue) {
                current = current.getRight();
            } else {
                current = current.getLeft();
            }

            bitIndex++;
        }

        if (current != null && current.isLeaf()) {
            logger.trace("Request ID {} routed via tree to {}",
                requestIdHex, current.getTargetUrl());
            return current.getTargetUrl();
        }

        throw new RuntimeException("Error: could not find a matching shard for Request ID: " + requestIdHex);
    }

    /**
     * Route based on explicit shard ID.
     *
     * @param shardId Shard ID as integer
     * @return Target URL to route to, or null if shard ID not found
     */
    @Override
    public Optional<String> routeByShardId(int shardId) {
        String targetUrl = shardIdToUrl.get(shardId);
        if (targetUrl != null) {
            logger.trace("Shard ID {} routed to {}", shardId, targetUrl);
            return Optional.of(targetUrl);
        } else {
            logger.warn("Shard ID {} not found in configuration", shardId);
            return Optional.empty();
        }
    }

    /**
     * Get a random target for requests without a request ID.
     * @return Random target URL
     */
    @Override
    public String getRandomTarget() {
        if (allTargets.isEmpty()) {
            throw new IllegalStateException("No shard targets configured");
        }
        return allTargets.get(random.nextInt(allTargets.size()));
    }

    /**
     * Get all configured target URLs.
     */
    @Override
    public List<String> getAllTargets() {
        return Collections.unmodifiableList(allTargets);
    }

    /**
     * Get the root node of the routing tree for validation purposes.
     * Package-private to allow ShardConfigValidator to access it.
     */
    ShardTreeNode getRootNode() {
        return rootNode;
    }
}
