package org.unicitylabs.proxy.shard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Routes requests to shard targets by matching the v2 {@code stateId} against
 * sentinel-integer-encoded shard suffixes using the aggregator's v2 SMT bit
 * layout: bit {@code d} = bit {@code d%8} of byte {@code d/8} (LSB-first
 * within each byte; bytes walked from byte 0 upward). This matches the
 * aggregator's {@code MatchesShardPrefix} exactly.
 *
 * <p>Uses a binary tree index for O(log n) lookup performance.
 */
public class DefaultShardRouter implements ShardRouter {
    /**
     * Expected length of a decoded v2 state ID.
     */
    public static final int STATE_ID_LENGTH_BYTES = 32;

    private static final Logger logger = LoggerFactory.getLogger(ShardRouter.class);
    private static final java.util.HexFormat HEX = java.util.HexFormat.of();

    private final List<ShardSuffix> suffixes;
    private final List<String> allTargets;
    private final Map<Integer, String> shardIdToUrl;
    private final Random random;
    private final ShardTreeNode rootNode;

    public DefaultShardRouter(ShardConfig config) {
        this.suffixes = new ArrayList<>();
        this.shardIdToUrl = new HashMap<>();
        this.random = new Random();

        for (ShardInfo shardInfo : config.getShards()) {
            ShardSuffix suffix = new ShardSuffix(shardInfo);
            suffixes.add(suffix);
            shardIdToUrl.put(shardInfo.id(), shardInfo.url());
        }

        this.allTargets = suffixes.stream()
            .map(ShardSuffix::getTargetUrl)
            .distinct()
            .toList();

        this.rootNode = buildRoutingTree();

        logger.info("ShardRouter initialized with {} shards, {} unique targets",
            suffixes.size(), allTargets.size());

        for (ShardSuffix suffix : suffixes) {
            logger.debug("  {}", suffix);
        }
    }

    /**
     * Builds the binary routing tree. Each suffix is a sentinel-prefixed
     * integer; bit {@code i} (for i in 0..bitLength-1) of the suffix defines
     * which child to follow at depth {@code i}. This matches the LSB-first
     * suffix encoding expected by the aggregator's bitmask convention.
     */
    private ShardTreeNode buildRoutingTree() {
        ShardTreeNode root = new ShardTreeNode();

        for (ShardSuffix suffix : suffixes) {
            ShardTreeNode current = root;
            int bitLength = suffix.getBitLength();

            for (int bitIndex = 0; bitIndex < bitLength; bitIndex++) {
                boolean bitValue = suffix.getSuffixBits().testBit(bitIndex);

                if (bitValue) {
                    if (current.getRight() == null) {
                        current.setRight(new ShardTreeNode());
                    }
                    current = current.getRight();
                } else {
                    if (current.getLeft() == null) {
                        current.setLeft(new ShardTreeNode());
                    }
                    current = current.getLeft();
                }
            }

            current.setTargetUrl(suffix.getTargetUrl());
        }

        return root;
    }

    @Override
    public ShardingMode getMode() {
        return ShardingMode.APP_SHARD;
    }

    @Override
    public String routeByStateId(String stateIdHex) {
        if (stateIdHex == null || stateIdHex.isBlank()) {
            throw new IllegalArgumentException("stateId cannot be null or empty");
        }
        String normalized = stateIdHex.toLowerCase();
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        }
        byte[] keyBytes;
        try {
            keyBytes = HEX.parseHex(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid state ID format: '" + stateIdHex + "'", e);
        }
        if (keyBytes.length != STATE_ID_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                "Invalid state ID length: expected " + STATE_ID_LENGTH_BYTES
                    + " raw bytes, got " + keyBytes.length);
        }

        ShardTreeNode current = rootNode;
        int bitIndex = 0;

        while (current != null && !current.isLeaf()) {
            int b = (keyBytes[bitIndex / 8] >> (bitIndex % 8)) & 1;
            current = (b == 1) ? current.getRight() : current.getLeft();
            bitIndex++;
        }

        if (current != null && current.isLeaf()) {
            if (logger.isTraceEnabled()) {
                logger.trace("stateId {} routed via tree to {}", stateIdHex, current.getTargetUrl());
            }
            return current.getTargetUrl();
        }

        throw new RuntimeException("Error: could not find a matching shard for stateId: " + stateIdHex);
    }

    @Override
    public Optional<String> routeByShardId(String shardIdLabel) {
        int shardId;
        try {
            shardId = Integer.parseInt(shardIdLabel);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid shard ID format for app-shard mode (expected integer): '" + shardIdLabel + "'", e);
        }
        String targetUrl = shardIdToUrl.get(shardId);
        if (targetUrl != null) {
            logger.trace("Shard ID {} routed to {}", shardId, targetUrl);
            return Optional.of(targetUrl);
        } else {
            logger.warn("Shard ID {} not found in configuration", shardId);
            return Optional.empty();
        }
    }

    @Override
    public String getRandomTarget() {
        if (allTargets.isEmpty()) {
            throw new IllegalStateException("No shard targets configured");
        }
        return allTargets.get(random.nextInt(allTargets.size()));
    }

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
