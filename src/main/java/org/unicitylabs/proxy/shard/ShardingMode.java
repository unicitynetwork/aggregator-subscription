package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Sharding mode for the gateway.
 *
 * <p>The two modes use different shard-ID semantics; they are not
 * interchangeable.
 *
 * <ul>
 *   <li>{@link #APP_SHARD}: routes JSON-RPC by {@code stateId}, using the
 *       legacy app-shard LSB-first integer shard-ID convention.</li>
 *   <li>{@link #BFT_SHARD}: routes v2 JSON-RPC by {@code stateId}, MSB-first
 *       prefix matching against bitstring shard IDs. Used for multi-shard BFT
 *       aggregator partitions.</li>
 * </ul>
 */
public enum ShardingMode {
    APP_SHARD("app-shard"),
    BFT_SHARD("bft-shard");

    private final String jsonValue;

    ShardingMode(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    @JsonValue
    public String jsonValue() {
        return jsonValue;
    }

    @JsonCreator
    public static ShardingMode fromJson(String value) {
        if (value == null || value.isBlank()) {
            return APP_SHARD;
        }
        for (ShardingMode mode : values()) {
            if (mode.jsonValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
            "Unknown sharding mode: '" + value + "' (expected one of: app-shard, bft-shard)");
    }
}
