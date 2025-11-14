package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class ShardConfig {
    @JsonProperty("version")
    private int version;

    @JsonProperty("shards")
    private List<ShardInfo> shards;

    public ShardConfig() {}

    public ShardConfig(int version, List<ShardInfo> shards) {
        this.version = version;
        this.shards = shards;
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
    }

    @Override
    public String toString() {
        return "ShardConfig{version=" + version + ", shards=" + shards + "}";
    }
}
