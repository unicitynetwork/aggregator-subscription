package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;

/**
 * Configuration for a single shard.
 * Immutable value object representing a shard's ID, suffix pattern, and target URL.
 */
public class ShardInfo {
    private final BigInteger id;
    private final BigInteger suffix;
    private final String url;

    @JsonCreator
    public ShardInfo(
        @JsonProperty("id") String id,
        @JsonProperty("suffix") String suffix,
        @JsonProperty("url") String url
    ) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Shard ID cannot be null or empty");
        }
        if (suffix == null || suffix.isEmpty()) {
            throw new IllegalArgumentException("Shard suffix cannot be null or empty");
        }
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Shard URL cannot be null or empty");
        }

        try {
            this.id = new BigInteger(id, 10);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Shard ID must be a valid decimal integer: " + id, e);
        }

        try {
            this.suffix = new BigInteger(suffix, 10);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Shard suffix must be a valid decimal integer: " + suffix, e);
        }

        this.url = url;
    }

    public BigInteger getId() {
        return id;
    }

    public BigInteger getSuffix() {
        return suffix;
    }

    public String getUrl() {
        return url;
    }

    @Override
    public String toString() {
        return String.format("ShardInfo{id=%s, suffix=%s, url='%s'}", id, suffix, url);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ShardInfo other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
