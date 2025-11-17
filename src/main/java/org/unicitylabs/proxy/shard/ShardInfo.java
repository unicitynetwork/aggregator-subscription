package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * Configuration for a single shard.
 * Immutable value object representing a shard's ID and target URL. The ID is used to derive the Request ID suffix pattern for routing.
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
        try {
            //noinspection ResultOfMethodCallIgnored
            URI.create(url); // Attempt to parse
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Shard URL is malformed: '" + url + "'");
        }

        this.id = id;
        this.url = url;
    }

    @Override
    public String toString() {
        return String.format("ShardInfo{id=%s, url='%s'}", id, url);
    }
}
