package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * API response DTO for shard configuration.
 */
public record ShardIdsResponse(
        @JsonProperty("version") int version,
        @JsonProperty("shardIds") List<Integer> shardIds
) {
    public static ShardIdsResponse fromShardConfig(ShardConfig config) {
        List<Integer> ids = config.getShards().stream()
                .map(ShardInfo::id)
                .toList();
        return new ShardIdsResponse(config.getVersion(), ids);
    }
}
