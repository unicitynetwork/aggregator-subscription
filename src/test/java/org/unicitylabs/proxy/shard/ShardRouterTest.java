package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ShardRouterTest {

    @Test
    @DisplayName("Test routing with 1-bit sharding")
    void testRouting1Bit() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "2", "http://shard0.example.com",
            "3", "http://shard1.example.com"
        ));

        ShardRouter router = new ShardRouter(config);

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
        ShardConfig config = new ShardConfig(1, Map.of(
            "4", "http://shard-00.example.com",
            "5", "http://shard-01.example.com",
            "6", "http://shard-10.example.com",
            "7", "http://shard-11.example.com"
        ));

        ShardRouter router = new ShardRouter(config);

        // Test all 4 combinations
        assertEquals("http://shard-00.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("http://shard-01.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000001"));
        assertEquals("http://shard-10.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000002"));
        assertEquals("http://shard-11.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000003"));
    }

    @Test
    @DisplayName("Test no-sharding configuration (single target)")
    void testNoSharding() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "1", "http://single.example.com"
        ));

        ShardRouter router = new ShardRouter(config);

        // All requests should route to the same target
        assertEquals("http://single.example.com", router.routeByRequestId("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("http://single.example.com", router.routeByRequestId("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"));
        assertEquals("http://single.example.com", router.routeByRequestId("1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF"));
    }

    @Test
    @DisplayName("Test random target selection")
    void testRandomTarget() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "2", "http://shard0.example.com",
            "3", "http://shard1.example.com"
        ));

        ShardRouter router = new ShardRouter(config);

        // Call random target multiple times - should return valid targets
        for (int i = 0; i < 10; i++) {
            String target = router.getRandomTarget();
            assertTrue(target.equals("http://shard0.example.com") || target.equals("http://shard1.example.com"));
        }
    }

    @Test
    @DisplayName("Test routing with null/empty request ID returns random target")
    void testNullRequestId() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "2", "http://shard0.example.com",
            "3", "http://shard1.example.com"
        ));

        ShardRouter router = new ShardRouter(config);

        String targetNull = router.routeByRequestId(null);
        assertTrue(targetNull.equals("http://shard0.example.com") || targetNull.equals("http://shard1.example.com"));

        String targetEmpty = router.routeByRequestId("");
        assertTrue(targetEmpty.equals("http://shard0.example.com") || targetEmpty.equals("http://shard1.example.com"));
    }

    @Test
    @DisplayName("Test getAllTargets returns unique targets")
    void testGetAllTargets() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "2", "http://shard0.example.com",
            "3", "http://shard1.example.com"
        ));

        ShardRouter router = new ShardRouter(config);

        assertEquals(2, router.getAllTargets().size());
        assertTrue(router.getAllTargets().contains("http://shard0.example.com"));
        assertTrue(router.getAllTargets().contains("http://shard1.example.com"));
    }

    @Test
    @DisplayName("Test routing with 0x prefix in request ID")
    void testRequestIdWith0xPrefix() {
        ShardConfig config = new ShardConfig(1, Map.of(
            "2", "http://shard0.example.com",
            "3", "http://shard1.example.com"
        ));

        ShardRouter router = new ShardRouter(config);

        assertEquals("http://shard0.example.com", router.routeByRequestId("0x0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("http://shard1.example.com", router.routeByRequestId("0x000000000000000000000000000000000000000000000000000000000000000F"));
    }
}
