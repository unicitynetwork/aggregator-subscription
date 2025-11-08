package org.unicitylabs.proxy.shard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Routes requests to shard targets based on request ID suffixes.
 * Uses a binary tree index for O(log n) lookup performance.
 */
public class ShardRouter {
    private static final Logger logger = LoggerFactory.getLogger(ShardRouter.class);

    private final List<ShardSuffix> suffixes;
    private final List<String> allTargets;
    private final Random random;
    private final ShardTreeNode rootNode;

    public ShardRouter(ShardConfig config) {
        this.suffixes = new ArrayList<>();
        this.random = new Random();

        // Parse all suffixes
        for (var entry : config.getTargets().entrySet()) {
            ShardSuffix suffix = new ShardSuffix(entry.getKey(), entry.getValue());
            suffixes.add(suffix);
        }

        // Extract unique target URLs for random selection
        this.allTargets = suffixes.stream()
            .map(ShardSuffix::getTargetUrl)
            .distinct()
            .toList();

        // Build binary tree index for fast lookup
        this.rootNode = buildRoutingTree();

        logger.info("ShardRouter initialized with {} suffixes, {} unique targets",
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
                // Extract bit at position bitIndex (0 = LSB)
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
    public String routeByRequestId(String requestIdHex) {
        if (requestIdHex == null || requestIdHex.isEmpty()) {
            return getRandomTarget();
        }

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
            return getRandomTarget();
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

            // Safety check to prevent infinite loop
            if (bitIndex > 256) {
                logger.error("Tree traversal exceeded 256 bits for request ID: {}", requestIdHex);
                return getRandomTarget();
            }
        }

        if (current != null && current.isLeaf()) {
            logger.trace("Request ID {} routed via tree to {}",
                requestIdHex, current.getTargetUrl());
            return current.getTargetUrl();
        }

        throw new RuntimeException("Error: could not find a matching shard for Request ID: " + requestIdHex);
    }

    /**
     * Get a random target for requests without a request ID.
     * @return Random target URL
     */
    public String getRandomTarget() {
        if (allTargets.isEmpty()) {
            throw new IllegalStateException("No shard targets configured");
        }
        return allTargets.get(random.nextInt(allTargets.size()));
    }

    /**
     * Get all configured target URLs.
     */
    public List<String> getAllTargets() {
        return Collections.unmodifiableList(allTargets);
    }

    /**
     * Get number of configured suffixes.
     */
    public int getSuffixCount() {
        return suffixes.size();
    }
}
