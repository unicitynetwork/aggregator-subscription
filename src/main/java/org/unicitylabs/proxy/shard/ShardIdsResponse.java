package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * API response DTO for the {@code /config/shards} endpoint.
 *
 * <p>Shape depends on the sharding mode:
 * <ul>
 *   <li>{@code app-shard}: {@code shardIds} populated (sentinel-prefixed integers);
 *       {@code bftShardPrefixes} omitted.</li>
 *   <li>{@code bft-shard}: {@code bftShardPrefixes} populated (binary bitstrings);
 *       {@code shardIds} omitted.</li>
 * </ul>
 * Clients should switch on {@code mode} to decide which list to read.
 */
public record ShardIdsResponse(
        @JsonProperty("version") int version,
        @JsonProperty("mode") String mode,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("shardIds") List<Integer> shardIds,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("bftShardPrefixes") List<String> bftShardPrefixes
) {
    public static ShardIdsResponse fromShardConfig(ShardConfig config) {
        ShardingMode mode = config.getMode();
        if (mode == ShardingMode.BFT_SHARD) {
            List<String> prefixes = config.getBftShards() == null ? List.of()
                : config.getBftShards().stream().map(BftShardInfo::prefix).toList();
            return new ShardIdsResponse(config.getVersion(), mode.jsonValue(), null, prefixes);
        }
        List<Integer> ids = config.getShards() == null ? List.of()
            : config.getShards().stream().map(ShardInfo::id).toList();
        return new ShardIdsResponse(config.getVersion(), mode.jsonValue(), ids, null);
    }
}
