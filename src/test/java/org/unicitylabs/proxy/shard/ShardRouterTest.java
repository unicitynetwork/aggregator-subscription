package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultShardRouter} (app-shard mode) with v2 state-ID
 * routing. Bit layout: bit {@code d} = bit {@code d%8} of byte {@code d/8}
 * (aggregator {@code MatchesShardPrefix} convention).
 *
 * <p>Test state IDs are 32-byte hex strings. Routing is determined by the
 * low-order bits of byte 0.
 */
public class ShardRouterTest {

    private static final String ZEROS_31 = "0".repeat(62); // 31 zero bytes

    @Test
    @DisplayName("1-bit sharding: byte 0 bit 0 picks the shard")
    void testRouting1Bit() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),   // bitmask 10 → bit0=0
            new ShardInfo(3, "http://shard1.example.com")    // bitmask 11 → bit0=1
        ));

        ShardRouter router = new DefaultShardRouter(config);

        // byte 0 = 0x00 → bit 0 = 0
        assertEquals("http://shard0.example.com", router.routeByStateId("00" + ZEROS_31));
        // byte 0 = 0x01 → bit 0 = 1
        assertEquals("http://shard1.example.com", router.routeByStateId("01" + ZEROS_31));
    }

    @Test
    @DisplayName("2-bit sharding: bits 0 and 1 of byte 0 pick the shard")
    void testRouting2Bit() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(4, "http://shard-00.example.com"), // bitmask 100 → bits[0,1]=[0,0]
            new ShardInfo(5, "http://shard-01.example.com"), // bitmask 101 → bits[0,1]=[1,0]
            new ShardInfo(6, "http://shard-10.example.com"), // bitmask 110 → bits[0,1]=[0,1]
            new ShardInfo(7, "http://shard-11.example.com")  // bitmask 111 → bits[0,1]=[1,1]
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertEquals("http://shard-00.example.com", router.routeByStateId("00" + ZEROS_31));
        assertEquals("http://shard-01.example.com", router.routeByStateId("01" + ZEROS_31));
        assertEquals("http://shard-10.example.com", router.routeByStateId("02" + ZEROS_31));
        assertEquals("http://shard-11.example.com", router.routeByStateId("03" + ZEROS_31));
    }

    @Test
    @DisplayName("No-sharding configuration (single target) routes everything to one URL")
    void testNoSharding() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(1, "http://single.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertEquals("http://single.example.com", router.routeByStateId("00" + ZEROS_31));
        assertEquals("http://single.example.com", router.routeByStateId("ff" + "f".repeat(62)));
        assertEquals("http://single.example.com", router.routeByStateId("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"));
    }

    @Test
    @DisplayName("Random target selection returns one of the configured targets")
    void testRandomTarget() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        for (int i = 0; i < 10; i++) {
            String target = router.getRandomTarget();
            assertTrue(target.equals("http://shard0.example.com") || target.equals("http://shard1.example.com"));
        }
    }

    @Test
    @DisplayName("Null and empty stateId throw")
    void testNullStateId() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertThrows(IllegalArgumentException.class, () -> router.routeByStateId(null));
        assertThrows(IllegalArgumentException.class, () -> router.routeByStateId(""));
    }

    @Test
    @DisplayName("Non-32-byte stateId rejected")
    void testWrongLength() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(1, "http://single.example.com")
        ));
        ShardRouter router = new DefaultShardRouter(config);

        // 31 bytes
        assertThrows(IllegalArgumentException.class, () -> router.routeByStateId("00".repeat(31)));
        // 33 bytes
        assertThrows(IllegalArgumentException.class, () -> router.routeByStateId("00".repeat(33)));
    }

    @Test
    @DisplayName("getAllTargets returns unique targets")
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
    @DisplayName("0x prefix on stateId is tolerated")
    void testStateIdWith0xPrefix() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertEquals("http://shard0.example.com", router.routeByStateId("0x00" + ZEROS_31));
        assertEquals("http://shard1.example.com", router.routeByStateId("0x01" + ZEROS_31));
    }

    @Test
    @DisplayName("routeByShardId parses integer label")
    void testRouteByShardId() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(3, "http://shard0.example.com"),
            new ShardInfo(2, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertEquals("http://shard0.example.com", router.routeByShardId("3").get());
        assertEquals("http://shard1.example.com", router.routeByShardId("2").get());
    }

    @Test
    @DisplayName("routeByShardId with unknown integer returns empty")
    void testRouteByInvalidShardId() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard0.example.com"),
            new ShardInfo(3, "http://shard1.example.com")
        ));

        ShardRouter router = new DefaultShardRouter(config);

        assertTrue(router.routeByShardId("999").isEmpty());
        assertTrue(router.routeByShardId("0").isEmpty());
    }

    @Test
    @DisplayName("routeByShardId with non-integer label throws")
    void testRouteByNonIntegerShardId() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(1, "http://single.example.com")
        ));
        ShardRouter router = new DefaultShardRouter(config);

        assertThrows(IllegalArgumentException.class, () -> router.routeByShardId("abc"));
    }
}
