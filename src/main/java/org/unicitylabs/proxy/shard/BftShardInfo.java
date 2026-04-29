package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * Configuration for a single BFT shard.
 *
 * <p>Immutable descriptor for one shard in a {@link ShardingMode#BFT_SHARD}
 * partition. The prefix is a binary bitstring (e.g. {@code "0"}, {@code "1"},
 * {@code "101"}) that is matched MSB-first against the raw bytes of the
 * decoded {@code stateId}.
 *
 * <p>Prefix length {@code 0} is rejected: a single-shard BFT partition is an
 * anti-pattern for this mode — use {@code app-shard} or a non-sharded setup
 * instead.
 */
public record BftShardInfo(String prefix, String url) {
    public static final int MAX_PREFIX_BITS = 256;

    @JsonCreator
    public BftShardInfo(
            @JsonProperty("prefix") String prefix,
            @JsonProperty("url") String url
    ) {
        if (prefix == null) {
            throw new IllegalArgumentException("BFT shard prefix cannot be null");
        }
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException(
                "BFT shard prefix cannot be empty; use app-shard mode for non-sharded setups");
        }
        if (prefix.length() > MAX_PREFIX_BITS) {
            throw new IllegalArgumentException(
                "BFT shard prefix exceeds maximum length (" + MAX_PREFIX_BITS + " bits): '" + prefix + "'");
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c != '0' && c != '1') {
                throw new IllegalArgumentException(
                    "BFT shard prefix must be a binary bitstring (only '0' and '1'): '" + prefix + "'");
            }
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("BFT shard URL cannot be null or empty");
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("BFT shard URL is malformed: '" + url + "'", e);
        }

        this.prefix = prefix;
        this.url = url;
    }

    @Override
    public String toString() {
        return String.format("BftShardInfo{prefix='%s', url='%s'}", prefix, url);
    }
}
