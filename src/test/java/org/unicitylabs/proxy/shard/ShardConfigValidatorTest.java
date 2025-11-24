package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShardConfigValidatorTest {

    @Test
    @DisplayName("Test valid 1-bit sharding configuration")
    void testValid1BitConfig() {
        assertShardConfigValid(new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        )));
    }

    @Test
    @DisplayName("Test valid 2-bit sharding configuration")
    void testValid2BitConfig() {
        assertShardConfigValid(new ShardConfig(1, List.of(
            new ShardInfo(4, "http://shard-00.example.com"),
            new ShardInfo(5, "http://shard-01.example.com"),
            new ShardInfo(6, "http://shard-10.example.com"),
            new ShardInfo(7, "http://shard-11.example.com")
        )));
    }

    @Test
    @DisplayName("Test valid no-sharding configuration (single target)")
    void testValidNoShardConfig() {
        assertShardConfigValid(new ShardConfig(1, List.of(
            new ShardInfo(1, "http://single.example.com")
        )));
    }

    @Test
    @DisplayName("Test invalid configuration - missing 'left' shard")
    void testInvalidMissingLeftShard() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(5, "http://shard-01.example.com"),
            new ShardInfo(6, "http://shard-10.example.com"),
            new ShardInfo(7, "http://shard-11.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config, false)
        );

        assertEquals("Incomplete routing tree: missing left child for request IDs with binary suffix: 00", exception.getMessage());
    }

    @Test
    @DisplayName("Test invalid configuration - missing 'right' shard")
    void testInvalidMissingRightShard() {
        ShardConfig config = new ShardConfig(1, List.of(
                new ShardInfo(4, "http://shard-00.example.com"),
                new ShardInfo(5, "http://shard-01.example.com"),
                new ShardInfo(6, "http://shard-10.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ShardConfigValidator.validate(router, config, false)
        );

        assertEquals("Incomplete routing tree: missing right child for request IDs with binary suffix: 11", exception.getMessage());
    }

    @Test
    @DisplayName("Test invalid configuration - only one shard in 1-bit config")
    void testInvalidOneShard() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        var exception = assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config, false)
        );
        assertEquals("Incomplete routing tree: missing right child for request IDs with binary suffix: 1", exception.getMessage());
    }

    @Test
    @DisplayName("Test invalid configuration - missing URL in a shard configuration tree node")
    void testInvalidMissingUrl() {
        ShardConfig config = new ShardConfig(1, List.of(
                new ShardInfo(1, "http://shard0.example.com")
        ));

        DefaultShardRouter router = new DefaultShardRouter(config);
        router.getRootNode().setTargetUrl(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ShardConfigValidator.validate(router, config, false)
        );

        assertEquals("Leaf node has no target URL at depth 0 (path: root)", exception.getMessage());
    }

    @Test
    @DisplayName("Test valid mixed-depth configuration")
    void testValidMixedDepth() {
        // suffix "2" covers all ending in 0 (half the space)
        // suffix "5" covers ...01 (quarter of the space)
        // suffix "7" covers ...11 (quarter of the space)
        assertShardConfigValid(new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(5, "http://shard-01.example.com"),
            new ShardInfo(7, "http://shard-11.example.com")
        )));
    }

    @Test
    @DisplayName("Test empty configuration throws exception")
    void testEmptyConfig() {
        assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(null,
                    new ShardConfig(1, List.of()))
        );
    }

    @Test
    @DisplayName("Test null targets throws exception")
    void testNullTargets() {
        assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(null,
                    new ShardConfig(1, null))
        );
    }

    @Test
    @DisplayName("Test invalid suffix in configuration")
    void testInvalidSuffix() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            ShardInfo shardInfo = new ShardInfo(0, "http://invalid.example.com");
            new ShardSuffix(shardInfo);
        });
        assertEquals("Invalid suffix: 0 (must be at least 1)", exception.getMessage());
    }

    @Test
    @DisplayName("Test URL with query parameters is rejected")
    void testUrlWithQueryParameters() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(1, "http://example.com?param=value")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        var exception = assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config, false)
        );
        assertTrue(exception.getMessage().contains("must not contain query parameters"));
    }

    @Test
    @DisplayName("Test URL with fragment is rejected")
    void testUrlWithFragment() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(1, "http://example.com#anchor")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        var exception = assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config, false)
        );
        assertTrue(exception.getMessage().contains("must not contain fragment"));
    }

    @Test
    @DisplayName("Test URL without scheme is rejected")
    void testUrlWithoutScheme() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(1, "example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        var exception = assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config, false)
        );
        assertTrue(exception.getMessage().contains("must have a scheme"));
    }

    @Test
    @DisplayName("Test URL without host is rejected")
    void testUrlWithoutHost() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(1, "file:///just/a/path")
        ));

        ShardRouter router = new DefaultShardRouter(config);
        var exception = assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config, false)
        );
        assertTrue(exception.getMessage().contains("must have a host"));
    }

    @Test
    @DisplayName("Test URL with path and port is accepted")
    void testUrlWithPathAndPort() {
        assertShardConfigValid(new ShardConfig(1, List.of(
            new ShardInfo(1, "http://example.com:8080/api/v1/")
        )));
    }

    @Test
    @DisplayName("Test simple URLs are accepted")
    void testUrlWithSimplePath() {
        assertShardConfigValid(new ShardConfig(1, List.of(
                new ShardInfo(1, "http://example.com")
        )));
        assertShardConfigValid(new ShardConfig(1, List.of(
                new ShardInfo(1, "http://example.com/")
        )));
        assertShardConfigValid(new ShardConfig(1, List.of(
                new ShardInfo(1, "http://example.com:8080")
        )));
        assertShardConfigValid(new ShardConfig(1, List.of(
                new ShardInfo(1, "http://example.com:8080/")
        )));
    }

    private void assertShardConfigValid(ShardConfig config) {
        ShardRouter router = new DefaultShardRouter(config);
        assertDoesNotThrow(() -> ShardConfigValidator.validate(router, config, false));
    }
}
