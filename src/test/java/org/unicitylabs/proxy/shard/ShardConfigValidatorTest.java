package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ShardConfigValidatorTest {

    @Test
    @DisplayName("Test valid 1-bit sharding configuration")
    void testValid1BitConfig() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "2", "http://shard0.example.com",
            "3", "http://shard1.example.com"
        ));

        ShardRouter router = new ShardRouter(config);
        assertDoesNotThrow(() -> ShardConfigValidator.validate(router, config));
    }

    @Test
    @DisplayName("Test valid 2-bit sharding configuration")
    void testValid2BitConfig() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "4", "http://shard-00.example.com",
            "5", "http://shard-01.example.com",
            "6", "http://shard-10.example.com",
            "7", "http://shard-11.example.com"
        ));

        ShardRouter router = new ShardRouter(config);
        assertDoesNotThrow(() -> ShardConfigValidator.validate(router, config));
    }

    @Test
    @DisplayName("Test valid no-sharding configuration (single target)")
    void testValidNoShardConfig() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "1", "http://single.example.com"
        ));

        ShardRouter router = new ShardRouter(config);
        assertDoesNotThrow(() -> ShardConfigValidator.validate(router, config));
    }

    @Test
    @DisplayName("Test invalid configuration - missing shard")
    void testInvalidMissingShard() {
        // Only defines 00, 01, 10 but missing 11
        ShardConfig config = new ShardConfig(1, Map.of(
            "4", "http://shard-00.example.com",
            "5", "http://shard-01.example.com",
            "6", "http://shard-10.example.com"
        ));

        ShardRouter router = new ShardRouter(config);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config)
        );

        assertTrue(exception.getMessage().contains("Incomplete shard configuration"));
        assertTrue(exception.getMessage().contains("uncovered"));
    }

    @Test
    @DisplayName("Test invalid configuration - only one shard in 1-bit config")
    void testInvalidOneShard() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "2", "http://shard0.example.com"
        ));

        ShardRouter router = new ShardRouter(config);
        assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config)
        );
    }

    @Test
    @DisplayName("Test valid mixed-depth configuration")
    void testValidMixedDepth() {
        // prefix "2" covers all ending in 0 (half the space)
        // prefix "5" covers ...01 (quarter of the space)
        // prefix "7" covers ...11 (quarter of the space)
        ShardConfig config = new ShardConfig(1, Map.of(
            "2", "http://shard0.example.com",
            "5", "http://shard-01.example.com",
            "7", "http://shard-11.example.com"
        ));

        ShardRouter router = new ShardRouter(config);
        assertDoesNotThrow(() -> ShardConfigValidator.validate(router, config));
    }

    @Test
    @DisplayName("Test empty configuration throws exception")
    void testEmptyConfig() {
        ShardConfig config = new ShardConfig(1, Map.of());

        assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(null, config)
        );
    }

    @Test
    @DisplayName("Test null targets throws exception")
    void testNullTargets() {
        ShardConfig config = new ShardConfig(1, null);

        assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(null, config)
        );
    }

    @Test
    @DisplayName("Test invalid prefix in configuration")
    void testInvalidPrefix() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "0", "http://invalid.example.com",  // "0" is invalid
            "2", "http://shard0.example.com"
        ));

        // Router construction will fail with invalid prefix
        assertThrows(IllegalArgumentException.class, () -> {
            new ShardRouter(config);
        });
    }
}
