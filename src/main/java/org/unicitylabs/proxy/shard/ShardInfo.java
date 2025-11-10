package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for a single shard.
 * Immutable value object representing a shard's ID, suffix pattern, and target URL.
 */
public record ShardInfo(int id, String url) {
    @JsonCreator
    public ShardInfo(
            @JsonProperty("id") int id,
            @JsonProperty("url") String url
    ) {
        if (id < 0) {
            throw new IllegalArgumentException("Shard ID cannot be negative");
        }
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Shard URL cannot be null or empty");
        }

        this.id = id;
        this.url = url;
    }

    @Override
    public String toString() {
        return String.format("ShardInfo{id=%s, url='%s'}", id, url);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ShardInfo other)) return false;
        return id == other.id;
    }

    @Override
    public int hashCode() {
        return id;
    }
}
