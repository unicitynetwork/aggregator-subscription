package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BftShardRouterTest {

    private static final String ZERO_32 =
        "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String ONE_PREFIX_32 =
        "8000000000000000000000000000000000000000000000000000000000000000";
    private static final String SEVEN_F_32 =
        "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    @Test
    @DisplayName("routes keys with MSB=0 to shard '0' and MSB=1 to shard '1'")
    void twoShardOneBit() {
        BftShardRouter r = twoShardRouter();

        assertEquals("http://shard0.example.com", r.routeByStateId(ZERO_32));
        assertEquals("http://shard0.example.com", r.routeByStateId(SEVEN_F_32));
        assertEquals("http://shard1.example.com", r.routeByStateId(ONE_PREFIX_32));
    }

    @Test
    @DisplayName("tolerates 0x prefix and mixed case")
    void tolerates0xAndMixedCase() {
        BftShardRouter r = twoShardRouter();
        assertEquals("http://shard0.example.com",
            r.routeByStateId("0x" + ZERO_32));
        assertEquals("http://shard1.example.com",
            r.routeByStateId("0x" + ONE_PREFIX_32.toUpperCase()));
    }

    @Test
    @DisplayName("rejects non-32-byte stateIds")
    void rejectsWrongLength() {
        BftShardRouter r = twoShardRouter();
        assertThrows(IllegalArgumentException.class, () -> r.routeByStateId("00"));
        assertThrows(IllegalArgumentException.class, () -> r.routeByStateId(""));
        assertThrows(IllegalArgumentException.class, () -> r.routeByStateId(null));
        // 34-byte input is rejected by the generic length check
        assertThrows(IllegalArgumentException.class,
            () -> r.routeByStateId("0000" + ZERO_32));
    }

    @Test
    @DisplayName("rejects malformed hex")
    void rejectsBadHex() {
        BftShardRouter r = twoShardRouter();
        assertThrows(IllegalArgumentException.class,
            () -> r.routeByStateId("gg" + "0".repeat(62)));
    }

    @Test
    @DisplayName("three-bit prefix 101 routes precisely")
    void threeBitPrefix() {
        ShardConfig config = new ShardConfig(1, ShardingMode.BFT_SHARD, null, List.of(
            new BftShardInfo("0", "http://half0.example.com"),
            new BftShardInfo("10", "http://quarter-10.example.com"),
            new BftShardInfo("11", "http://quarter-11.example.com")
        ));
        BftShardRouter r = new BftShardRouter(config);

        // key starting 1010_... -> prefix "10"
        assertEquals("http://quarter-10.example.com",
            r.routeByStateId("a0" + "0".repeat(62)));
        // key starting 1100_... -> prefix "11"
        assertEquals("http://quarter-11.example.com",
            r.routeByStateId("c0" + "0".repeat(62)));
        // key starting 0xxx_... -> prefix "0"
        assertEquals("http://half0.example.com",
            r.routeByStateId("7f" + "f".repeat(62)));
    }

    @Test
    @DisplayName("routeByShardId matches configured bitstring prefix")
    void routeByShardIdBitstring() {
        BftShardRouter r = twoShardRouter();
        assertEquals("http://shard0.example.com", r.routeByShardId("0").get());
        assertEquals("http://shard1.example.com", r.routeByShardId("1").get());
    }

    @Test
    @DisplayName("routeByShardId with unknown bitstring returns empty")
    void routeByShardIdUnknownReturnsEmpty() {
        BftShardRouter r = twoShardRouter();
        assertTrue(r.routeByShardId("101").isEmpty());
    }

    @Test
    @DisplayName("routeByShardId with non-bitstring label throws")
    void routeByShardIdNonBinaryThrows() {
        BftShardRouter r = twoShardRouter();
        assertThrows(IllegalArgumentException.class, () -> r.routeByShardId("abc"));
        assertThrows(IllegalArgumentException.class, () -> r.routeByShardId("2"));
    }

    @Test
    @DisplayName("getMode returns BFT_SHARD")
    void mode() {
        assertEquals(ShardingMode.BFT_SHARD, twoShardRouter().getMode());
    }

    @Test
    @DisplayName("getAllTargets returns unique targets")
    void allTargets() {
        BftShardRouter r = twoShardRouter();
        assertEquals(2, r.getAllTargets().size());
        assertTrue(r.getAllTargets().contains("http://shard0.example.com"));
        assertTrue(r.getAllTargets().contains("http://shard1.example.com"));
    }

    @Test
    @DisplayName("constructor rejects ShardConfig with wrong mode")
    void wrongModeRejected() {
        ShardConfig appShard = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://a"), new ShardInfo(3, "http://b")));
        assertThrows(IllegalArgumentException.class, () -> new BftShardRouter(appShard));
    }

    @Test
    @DisplayName("constructor rejects empty bftShards")
    void emptyBftShardsRejected() {
        ShardConfig cfg = new ShardConfig(1, ShardingMode.BFT_SHARD, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> new BftShardRouter(cfg));
    }

    private static BftShardRouter twoShardRouter() {
        ShardConfig config = new ShardConfig(1, ShardingMode.BFT_SHARD, null, List.of(
            new BftShardInfo("0", "http://shard0.example.com"),
            new BftShardInfo("1", "http://shard1.example.com")
        ));
        return new BftShardRouter(config);
    }
}
