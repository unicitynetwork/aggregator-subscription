package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShardRouterTest {

    @Test
    @DisplayName("Test routing with 1-bit sharding")
    void testRouting1Bit() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        // Request ID ending in 0 (even) should route to shard0
        String requestId0 = "0000000000000000000000000000000000000000000000000000000000000000";
        assertEquals("http://shard0.example.com", router.routeByRequestId(requestId0));

        // Request ID ending in F (odd) should route to shard1
        String requestIdF = "000000000000000000000000000000000000000000000000000000000000000F";
        assertEquals("http://shard1.example.com", router.routeByRequestId(requestIdF));
    }

    @Test
    @DisplayName("Test routing with 2-bit sharding")
    void testRouting2Bit() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(4, "http://shard-00.example.com"),
            new ShardInfo(5, "http://shard-01.example.com"),
            new ShardInfo(6, "http://shard-10.example.com"),
            new ShardInfo(7, "http://shard-11.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        // Test all 4 combinations
        assertEquals("http://shard-00.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("http://shard-01.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000001"));
        assertEquals("http://shard-10.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000002"));
        assertEquals("http://shard-11.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000003"));
    }

    @Test
    @DisplayName("Test no-sharding configuration (single target)")
    void testNoSharding() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(1, "http://single.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        // All requests should route to the same target
        assertEquals("http://single.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("http://single.example.com", router.routeByRequestId("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"));
        assertEquals("http://single.example.com", router.routeByRequestId("1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF"));
    }

    @Test
    @DisplayName("Test random target selection")
    void testRandomTarget() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        // Call random target multiple times - should return valid targets
        for (int i = 0; i < 10; i++) {
            String target = router.getRandomTarget();
            assertTrue(target.equals("http://shard0.example.com") || target.equals("http://shard1.example.com"));
        }
    }

    @Test
    @DisplayName("Test routing with null/empty request ID throws an error")
    void testNullRequestId() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertThrows(NullPointerException.class, () ->
                router.routeByRequestId(null));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                router.routeByRequestId(""));
        assertEquals("Invalid request ID format: ''", e.getMessage());
    }

    @Test
    @DisplayName("Test getAllTargets returns unique targets")
    void testGetAllTargets() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertEquals(2, router.getAllTargets().size());
        assertTrue(router.getAllTargets().contains("http://shard0.example.com"));
        assertTrue(router.getAllTargets().contains("http://shard1.example.com"));
    }

    @Test
    @DisplayName("Test routing with 0x prefix in request ID")
    void testRequestIdWith0xPrefix() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertEquals("http://shard0.example.com", router.routeByRequestId("0x0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("http://shard1.example.com", router.routeByRequestId("0x000000000000000000000000000000000000000000000000000000000000000F"));
    }

    @Test
    @DisplayName("Test routing by shard ID")
    void testRouteByShardId() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(3, "http://shard0.example.com"),
            new ShardInfo(2, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertEquals("http://shard0.example.com", router.routeByShardId(3).get());
        assertEquals("http://shard1.example.com", router.routeByShardId(2).get());
    }

    @Test
    @DisplayName("Test routing by non-existent shard ID returns null")
    void testRouteByInvalidShardId() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertTrue(router.routeByShardId(999).isEmpty());
        assertTrue(router.routeByShardId(0).isEmpty());
    }
}
