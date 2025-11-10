package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ShardConfig {
    @JsonProperty("version")
    private int version;

    @JsonProperty("shards")
    private List<ShardInfo> shards;

    public ShardConfig() {}

    public ShardConfig(int version, List<ShardInfo> shards) {
        this.version = version;
        this.shards = shards;
        validateUniqueShardIds();
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<ShardInfo> getShards() {
        return shards;
    }

    public void setShards(List<ShardInfo> shards) {
        this.shards = shards;
        validateUniqueShardIds();
    }

    private void validateUniqueShardIds() {
        if (shards == null) {
            return;
        }

        Set<Integer> seenIds = new HashSet<>();
        for (ShardInfo shard : shards) {
            if (!seenIds.add(shard.id())) {
                throw new IllegalArgumentException("Duplicate shard ID: " + shard.id());
            }
        }
    }

    @Override
    public String toString() {
        return "ShardConfig{version=" + version + ", shards=" + shards + "}";
    }
}
