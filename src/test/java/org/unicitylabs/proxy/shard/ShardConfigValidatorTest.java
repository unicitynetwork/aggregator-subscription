package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShardConfigValidatorTest {

    @Test
    @DisplayName("Test valid 1-bit sharding configuration")
    void testValid1BitConfig() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo("0", "2", "http://shard0.example.com"),
            new ShardInfo("1", "3", "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        ShardConfigValidator.validate(router, config);
    }

    @Test
    @DisplayName("Test valid 2-bit sharding configuration")
    void testValid2BitConfig() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo("0", "4", "http://shard-00.example.com"),
            new ShardInfo("1", "5", "http://shard-01.example.com"),
            new ShardInfo("2", "6", "http://shard-10.example.com"),
            new ShardInfo("3", "7", "http://shard-11.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        ShardConfigValidator.validate(router, config);
    }

    @Test
    @DisplayName("Test valid no-sharding configuration (single target)")
    void testValidNoShardConfig() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo("0", "1", "http://single.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        ShardConfigValidator.validate(router, config);
    }

    @Test
    @DisplayName("Test invalid configuration - missing 'left' shard")
    void testInvalidMissingLeftShard() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo("1", "5", "http://shard-01.example.com"),
            new ShardInfo("0", "6", "http://shard-10.example.com"),
            new ShardInfo("2", "7", "http://shard-11.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config)
        );

        assertEquals("Incomplete routing tree: missing left child for request IDs with binary suffix: 00", exception.getMessage());
    }

    @Test
    @DisplayName("Test invalid configuration - missing 'right' shard")
    void testInvalidMissingRightShard() {
        ShardConfig config = new ShardConfig(1, List.of(
                new ShardInfo("0", "4", "http://shard-00.example.com"),
                new ShardInfo("1", "5", "http://shard-01.example.com"),
                new ShardInfo("2", "6", "http://shard-10.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ShardConfigValidator.validate(router, config)
        );

        assertEquals("Incomplete routing tree: missing right child for request IDs with binary suffix: 11", exception.getMessage());
    }

    @Test
    @DisplayName("Test invalid configuration - only one shard in 1-bit config")
    void testInvalidOneShard() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo("0", "2", "http://shard0.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        var exception = assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config)
        );
        assertEquals("Incomplete routing tree: missing right child for request IDs with binary suffix: 1", exception.getMessage());
    }

    @Test
    @DisplayName("Test invalid configuration - missing URL in a shard configuration tree node")
    void testInvalidMissingUrl() {
        ShardConfig config = new ShardConfig(1, List.of(
                new ShardInfo("0", "1", "http://shard0.example.com")
        ));

        DefaultShardRouter router = new DefaultShardRouter(config);
        router.getRootNode().setTargetUrl(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ShardConfigValidator.validate(router, config)
        );

        assertEquals("Leaf node has no target URL at depth 0 (path: root)", exception.getMessage());
    }

    @Test
    @DisplayName("Test valid mixed-depth configuration")
    void testValidMixedDepth() {
        // suffix "2" covers all ending in 0 (half the space)
        // suffix "5" covers ...01 (quarter of the space)
        // suffix "7" covers ...11 (quarter of the space)
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo("0", "2", "http://shard0.example.com"),
            new ShardInfo("1", "5", "http://shard-01.example.com"),
            new ShardInfo("2", "7", "http://shard-11.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        assertDoesNotThrow(() -> ShardConfigValidator.validate(router, config));
    }

    @Test
    @DisplayName("Test empty configuration throws exception")
    void testEmptyConfig() {
        ShardConfig config = new ShardConfig(1, List.of());

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
    @DisplayName("Test invalid suffix in configuration")
    void testInvalidSuffix() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            ShardInfo shardInfo = new ShardInfo("0", "0", "http://invalid.example.com");
            new ShardSuffix(shardInfo);
        });
        assertEquals("Invalid suffix: 0 (must be at least 1)", exception.getMessage());
    }
}
