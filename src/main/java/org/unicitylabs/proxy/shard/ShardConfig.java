package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class ShardConfig {
    @JsonProperty("version")
    private int version;

    @JsonProperty("targets")
    private Map<String, String> targets;

    public ShardConfig() {}

    public ShardConfig(int version, Map<String, String> targets) {
        this.version = version;
        this.targets = targets;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, String> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, String> targets) {
        this.targets = targets;
    }

    @Override
    public String toString() {
        return "ShardConfig{version=" + version + ", targets=" + targets + "}";
    }
}
