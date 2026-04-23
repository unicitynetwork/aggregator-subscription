package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShardConfigValidatorBftTest {

    @Test
    @DisplayName("valid two-shard 1-bit split")
    void validOneBitSplit() {
        assertValid(config(List.of(
            new BftShardInfo("0", "http://shard0.example.com"),
            new BftShardInfo("1", "http://shard1.example.com")
        )));
    }

    @Test
    @DisplayName("valid three-shard mixed-depth split")
    void validMixedDepth() {
        assertValid(config(List.of(
            new BftShardInfo("0", "http://half0.example.com"),
            new BftShardInfo("10", "http://q10.example.com"),
            new BftShardInfo("11", "http://q11.example.com")
        )));
    }

    @Test
    @DisplayName("empty bftShards rejected at router construction")
    void emptyRejected() {
        ShardConfig cfg = new ShardConfig(1, ShardingMode.BFT_SHARD, null, List.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ShardRouterFactory.create(cfg));
        assertTrue(e.getMessage().contains("no bftShards"));
    }

    @Test
    @DisplayName("single shard with empty prefix rejected at BftShardInfo construction")
    void singleEmptyPrefixRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new BftShardInfo("", "http://x.example.com"));
    }

    @Test
    @DisplayName("duplicate prefix rejected")
    void duplicatePrefixRejected() {
        ShardConfig cfg = config(List.of(
            new BftShardInfo("0", "http://a.example.com"),
            new BftShardInfo("0", "http://b.example.com"),
            new BftShardInfo("1", "http://c.example.com")
        ));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> validate(cfg));
        assertTrue(e.getMessage().contains("Duplicate"));
    }

    @Test
    @DisplayName("prefix that is a prefix of another is rejected")
    void notPrefixFreeRejected() {
        ShardConfig cfg = config(List.of(
            new BftShardInfo("0", "http://a.example.com"),
            new BftShardInfo("01", "http://b.example.com"),
            new BftShardInfo("1", "http://c.example.com")
        ));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> validate(cfg));
        assertTrue(e.getMessage().contains("prefix-free"));
    }

    @Test
    @DisplayName("prefix-free but not covering is rejected")
    void notCoveringRejected() {
        // "00" and "01" cover leading "0", but nothing covers leading "1"
        ShardConfig cfg = config(List.of(
            new BftShardInfo("00", "http://a.example.com"),
            new BftShardInfo("01", "http://b.example.com")
        ));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> validate(cfg));
        assertTrue(e.getMessage().contains("not covering"));
    }

    @Test
    @DisplayName("missing one side of a split is rejected")
    void missingSiblingRejected() {
        // "10" and "11" don't cover the "0" subspace
        ShardConfig cfg = config(List.of(
            new BftShardInfo("10", "http://a.example.com"),
            new BftShardInfo("11", "http://b.example.com")
        ));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> validate(cfg));
        assertTrue(e.getMessage().contains("not covering"));
    }

    @Test
    @DisplayName("mixing app-shard 'shards' with bft mode is rejected")
    void mixingShardsRejected() {
        ShardConfig cfg = new ShardConfig(1, ShardingMode.BFT_SHARD,
            List.of(new ShardInfo(2, "http://a.example.com"), new ShardInfo(3, "http://b.example.com")),
            List.of(new BftShardInfo("0", "http://x.example.com"),
                    new BftShardInfo("1", "http://y.example.com")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> validate(cfg));
        assertTrue(e.getMessage().contains("must not populate 'shards'"));
    }

    @Test
    @DisplayName("mixing bft 'bftShards' with app-shard mode is rejected")
    void mixingBftShardsInAppModeRejected() {
        ShardConfig cfg = new ShardConfig(1, ShardingMode.APP_SHARD,
            List.of(new ShardInfo(2, "http://a.example.com"), new ShardInfo(3, "http://b.example.com")),
            List.of(new BftShardInfo("0", "http://x.example.com")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> validate(cfg));
        assertTrue(e.getMessage().contains("must not populate 'bftShards'"));
    }

    @Test
    @DisplayName("non-binary prefix rejected at construction")
    void nonBinaryPrefixRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new BftShardInfo("0a1", "http://x.example.com"));
    }

    @Test
    @DisplayName("malformed URL rejected")
    void malformedUrlRejected() {
        ShardConfig cfg = config(List.of(
            new BftShardInfo("0", "http://ok.example.com"),
            new BftShardInfo("1", "http://bad.example.com?q=1")
        ));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> validate(cfg));
        assertTrue(e.getMessage().contains("query parameters"));
    }

    @Test
    @DisplayName("validator rejects BftShardRouter built from app-shard config")
    void routerMismatchRejected() {
        // Simulate a callsite that built the wrong router for the config.
        ShardConfig cfg = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://a.example.com"),
            new ShardInfo(3, "http://b.example.com")));
        BftShardRouter wrongRouter = new BftShardRouter(
            new ShardConfig(1, ShardingMode.BFT_SHARD, null,
                List.of(new BftShardInfo("0", "http://a.example.com"),
                        new BftShardInfo("1", "http://b.example.com"))));
        assertThrows(IllegalArgumentException.class,
            () -> ShardConfigValidator.validate(wrongRouter, cfg, false));
    }

    private static ShardConfig config(List<BftShardInfo> shards) {
        return new ShardConfig(1, ShardingMode.BFT_SHARD, null, shards);
    }

    private static void assertValid(ShardConfig cfg) {
        BftShardRouter router = new BftShardRouter(cfg);
        assertDoesNotThrow(() -> ShardConfigValidator.validate(router, cfg, false));
    }

    private static void validate(ShardConfig cfg) {
        ShardRouter router = ShardRouterFactory.create(cfg);
        ShardConfigValidator.validate(router, cfg, false);
    }
}
