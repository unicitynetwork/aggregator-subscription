package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the shard-connectivity probe against a REAL HAProxy whose
 * gateway-facing frontend is configured with {@code bind ... proto h2} — i.e. cleartext
 * HTTP/2 (h2c) prior-knowledge, exactly the testnet2 change this fix enables.
 *
 * <p>A unit test cannot catch this: the failure mode is genuine h2c interop with HAProxy's
 * prior-knowledge listener (a plain HTTP/1.1 client gets "unexpected end of stream"). The
 * two cases below pin both directions:
 * <ul>
 *   <li>{@code upstreamH2c=true}  → the probe speaks h2c and succeeds (the fix);</li>
 *   <li>{@code upstreamH2c=false} → the HTTP/1.1 SDK probe fails (reproduces the production
 *       startup crash that motivated this change).</li>
 * </ul>
 *
 * <p>HAProxy returns a canned {@code get_block_height} JSON-RPC response on its h2c frontend,
 * so the test isolates the gateway↔HAProxy h2c hop (the part that changed) without needing a
 * full aggregator/BFT backend.
 */
@DisplayName("ShardConfigValidator connectivity probe vs a real HAProxy 'proto h2' frontend")
class ShardConfigValidatorH2cIntegrationTest {

    private static final String HAPROXY_CFG = String.join("\n",
        "global",
        "    log stdout format raw local0 info",
        "defaults",
        "    mode http",
        "    timeout connect 5s",
        "    timeout client 30s",
        "    timeout server 30s",
        "frontend shard_frontend",
        "    bind *:3000 proto h2",
        "    http-request return status 200 content-type \"application/json\" file /etc/haproxy/resp.json",
        "");

    // A valid get_block_height JSON-RPC response — what an aggregator would answer.
    private static final String BLOCK_HEIGHT_RESPONSE =
        "{\"jsonrpc\":\"2.0\",\"result\":{\"blockNumber\":\"42\"},\"id\":1}";

    private static GenericContainer<?> haproxy;

    @BeforeAll
    static void startHaproxy() {
        haproxy = new GenericContainer<>("haproxy:2.9-alpine")
            .withCopyToContainer(Transferable.of(HAPROXY_CFG), "/usr/local/etc/haproxy/haproxy.cfg")
            .withCopyToContainer(Transferable.of(BLOCK_HEIGHT_RESPONSE), "/etc/haproxy/resp.json")
            .withExposedPorts(3000)
            .waitingFor(Wait.forListeningPort());
        haproxy.start();
    }

    @AfterAll
    static void stopHaproxy() {
        if (haproxy != null) {
            haproxy.stop();
        }
    }

    private String shardUrl() {
        return "http://" + haproxy.getHost() + ":" + haproxy.getMappedPort(3000);
    }

    // Two prefix-free, covering bft shards both pointing at the h2c HAProxy frontend.
    private ShardConfig bftConfig() {
        return new ShardConfig(1, ShardingMode.BFT_SHARD, null,
            List.of(new BftShardInfo("0", shardUrl()), new BftShardInfo("1", shardUrl())));
    }

    @Test
    @DisplayName("upstreamH2c=true: probe speaks h2c and validates against the 'proto h2' frontend")
    void h2cProbeSucceedsAgainstProtoH2Frontend() {
        ShardConfig config = bftConfig();
        ShardRouter router = ShardRouterFactory.create(config);
        assertThatCode(() -> ShardConfigValidator.validate(router, config, true, true))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("upstreamH2c=false: HTTP/1.1 probe fails against the 'proto h2' frontend (the bug)")
    void h1ProbeFailsAgainstProtoH2Frontend() {
        ShardConfig config = bftConfig();
        ShardRouter router = ShardRouterFactory.create(config);
        assertThatThrownBy(() -> ShardConfigValidator.validate(router, config, true, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not reachable or not a valid aggregator");
    }
}
