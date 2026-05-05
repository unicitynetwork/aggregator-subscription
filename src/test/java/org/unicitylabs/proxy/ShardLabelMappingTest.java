package org.unicitylabs.proxy;

import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.shard.BftShardInfo;
import org.unicitylabs.proxy.shard.ShardConfig;
import org.unicitylabs.proxy.shard.ShardInfo;
import org.unicitylabs.proxy.shard.ShardingMode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@link ProxyServer#buildShardLabels(ShardConfig)}. Each Prometheus
 * upstream label originates from this map; a regression here silently turns
 * upstream metrics into {@code shard="unknown"} in production.
 */
class ShardLabelMappingTest {

    @Test
    void appShardLabelsKeyByShardId() {
        ShardConfig config = new ShardConfig(1, List.of(
            new ShardInfo(0, "http://shard-a:3000"),
            new ShardInfo(1, "http://shard-b:3000")
        ));

        Map<String, String> labels = ProxyServer.buildShardLabels(config);

        assertThat(labels).containsExactlyInAnyOrderEntriesOf(Map.of(
            "http://shard-a:3000", "shard-0",
            "http://shard-b:3000", "shard-1"
        ));
    }

    @Test
    void bftShardLabelsKeyByPrefix() {
        ShardConfig config = new ShardConfig(1, ShardingMode.BFT_SHARD, List.of(),
            List.of(
                new BftShardInfo("0", "http://bft-zero:3000"),
                new BftShardInfo("10", "http://bft-ten:3000"),
                new BftShardInfo("11", "http://bft-eleven:3000")
            ));

        Map<String, String> labels = ProxyServer.buildShardLabels(config);

        assertThat(labels).containsExactlyInAnyOrderEntriesOf(Map.of(
            "http://bft-zero:3000", "shard-0",
            "http://bft-ten:3000", "shard-10",
            "http://bft-eleven:3000", "shard-11"
        ));
    }

    @Test
    void nullConfigYieldsEmptyMap() {
        assertThat(ProxyServer.buildShardLabels(null)).isEmpty();
    }

    @Test
    void duplicateUrlAcrossShardsKeepsFirstLabel() {
        // Same URL appearing in both lists should not produce two labels.
        ShardConfig config = new ShardConfig(1, ShardingMode.BFT_SHARD,
            List.of(new ShardInfo(7, "http://dup:3000")),
            List.of(new BftShardInfo("01", "http://dup:3000")));

        Map<String, String> labels = ProxyServer.buildShardLabels(config);

        // App-shard list is processed first, so its label wins.
        assertThat(labels).containsExactlyInAnyOrderEntriesOf(Map.of(
            "http://dup:3000", "shard-7"
        ));
    }
}
