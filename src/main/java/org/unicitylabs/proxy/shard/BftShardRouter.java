package org.unicitylabs.proxy.shard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Routes requests to BFT shard targets by MSB-first prefix matching against
 * the raw bytes of a decoded {@code stateId}.
 *
 * <p>Bit order matches {@code bft-go-base/types/sharding.go:Comparator()} and
 * the aggregator's bft-shard admission path. See {@link BftShardPrefix} for
 * the exact bit-numbering contract.
 *
 * <p>This router does not support {@link #routeByShardId(int)} — BFT shard
 * IDs are bit strings, not integers, so integer-shardId routing throws.
 */
public class BftShardRouter implements ShardRouter {
    /**
     * Expected length of a decoded v2 state ID.
     */
    public static final int STATE_ID_LENGTH_BYTES = 32;

    private static final Logger logger = LoggerFactory.getLogger(BftShardRouter.class);
    private static final HexFormat HEX = HexFormat.of();

    private final List<Entry> entries;
    private final List<String> allTargets;

    public BftShardRouter(ShardConfig config) {
        if (config.getMode() != ShardingMode.BFT_SHARD) {
            throw new IllegalArgumentException(
                "BftShardRouter requires ShardConfig.mode == bft-shard, got: " + config.getMode());
        }
        List<BftShardInfo> bftShards = config.getBftShards();
        if (bftShards == null || bftShards.isEmpty()) {
            throw new IllegalArgumentException("bft-shard config has no bftShards entries");
        }
        this.entries = new ArrayList<>(bftShards.size());
        for (BftShardInfo info : bftShards) {
            entries.add(new Entry(new BftShardPrefix(info.prefix()), info.url()));
        }
        this.allTargets = entries.stream()
            .map(Entry::url)
            .distinct()
            .toList();

        logger.info("BftShardRouter initialized with {} shards, {} unique targets",
            entries.size(), allTargets.size());
        for (Entry e : entries) {
            logger.debug("  shard prefix='{}' -> {}", e.prefix(), e.url());
        }
    }

    @Override
    public ShardingMode getMode() {
        return ShardingMode.BFT_SHARD;
    }

    @Override
    public String routeByStateId(String stateIdHex) {
        if (stateIdHex == null || stateIdHex.isBlank()) {
            throw new IllegalArgumentException("stateId cannot be null or empty");
        }
        String normalized = stateIdHex;
        if (normalized.regionMatches(true, 0, "0x", 0, 2)) {
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

        for (Entry e : entries) {
            if (e.prefix().matches(keyBytes)) {
                if (logger.isTraceEnabled()) {
                    logger.trace("stateId {} matched shard prefix '{}' -> {}",
                        stateIdHex, e.prefix(), e.url());
                }
                return e.url();
            }
        }

        throw new IllegalStateException(
            "No bft shard matches stateId prefix for '" + stateIdHex + "'");
    }

    @Override
    public Optional<String> routeByShardId(String shardIdLabel) {
        if (shardIdLabel == null) {
            throw new IllegalArgumentException("shardId cannot be null");
        }
        // Validate as a bitstring; mismatching other formats produces a clear error
        // instead of silently returning empty.
        for (int i = 0; i < shardIdLabel.length(); i++) {
            char c = shardIdLabel.charAt(i);
            if (c != '0' && c != '1') {
                throw new IllegalArgumentException(
                    "Invalid shard ID format for bft-shard mode (expected binary bitstring): '"
                        + shardIdLabel + "'");
            }
        }
        for (Entry e : entries) {
            if (e.prefix().bits().equals(shardIdLabel)) {
                return Optional.of(e.url());
            }
        }
        return Optional.empty();
    }

    @Override
    public String getRandomTarget() {
        if (allTargets.isEmpty()) {
            throw new IllegalStateException("No shard targets configured");
        }
        return allTargets.get(ThreadLocalRandom.current().nextInt(allTargets.size()));
    }

    @Override
    public List<String> getAllTargets() {
        return Collections.unmodifiableList(allTargets);
    }

    /**
     * Package-private accessor for validators.
     */
    List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    record Entry(BftShardPrefix prefix, String url) {
    }
}
