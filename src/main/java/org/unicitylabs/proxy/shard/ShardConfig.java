package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class ShardConfig {
    @JsonProperty("version")
    private int version;

    @JsonProperty("mode")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ShardingMode mode;

    @JsonProperty("shards")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ShardInfo> shards;

    @JsonProperty("bftShards")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<BftShardInfo> bftShards;

    public ShardConfig() {}

    public ShardConfig(int version, List<ShardInfo> shards) {
        this(version, ShardingMode.APP_SHARD, shards, null);
    }

    public ShardConfig(int version, ShardingMode mode, List<ShardInfo> shards, List<BftShardInfo> bftShards) {
        this.version = version;
        this.mode = mode;
        this.shards = shards;
        this.bftShards = bftShards;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public ShardingMode getMode() {
        return mode != null ? mode : ShardingMode.APP_SHARD;
    }

    public void setMode(ShardingMode mode) {
        this.mode = mode;
    }

    public List<ShardInfo> getShards() {
        return shards;
    }

    public void setShards(List<ShardInfo> shards) {
        this.shards = shards;
    }

    public List<BftShardInfo> getBftShards() {
        return bftShards;
    }

    public void setBftShards(List<BftShardInfo> bftShards) {
        this.bftShards = bftShards;
    }

    @Override
    public String toString() {
        return "ShardConfig{version=" + version
            + ", mode=" + getMode()
            + ", shards=" + shards
            + ", bftShards=" + bftShards
            + "}";
    }
}
