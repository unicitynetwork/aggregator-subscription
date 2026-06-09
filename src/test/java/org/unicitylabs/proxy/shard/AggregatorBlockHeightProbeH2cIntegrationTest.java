package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.unicitylabs.proxy.HealthCheckHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link AggregatorBlockHeightProbe} against a REAL HAProxy whose frontend is
 * {@code bind ... proto h2} (cleartext HTTP/2 prior-knowledge) — the testnet2 cutover config.
 *
 * <p>This is the single shared probe used by BOTH {@code ShardConfigValidator} (startup/admin) and
 * {@code HealthCheckHandler} (periodic /health). The /health path is what regressed in production
 * (issue #40 fallout: an HTTP/1.1 health probe against the proto-h2 frontend marked every shard
 * unhealthy → 503 → ALB dropped the gateway). The cases below pin all of it:
 * <ul>
 *   <li>{@code upstreamH2c=true} → the probe speaks h2c and succeeds (the fix);</li>
 *   <li>{@code upstreamH2c=false} → an HTTP/1.1 probe fails (reproduces the regression);</li>
 *   <li>a {@code HealthCheckHandler.AggregatorHealthChecker} backed by the h2c probe (exactly how
 *       ProxyServer wires it) reports the shard healthy over h2c.</li>
 * </ul>
 */
@DisplayName("AggregatorBlockHeightProbe vs a real HAProxy 'proto h2' frontend")
class AggregatorBlockHeightProbeH2cIntegrationTest {

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

    @Test
    @DisplayName("upstreamH2c=true: probe speaks h2c and returns the block height")
    void h2cProbeSucceeds() throws Exception {
        try (AggregatorBlockHeightProbe probe = new AggregatorBlockHeightProbe(true)) {
            assertThat(probe.blockHeight(shardUrl())).isEqualTo(42L);
        }
    }

    @Test
    @DisplayName("upstreamH2c=false: HTTP/1.1 probe fails against the 'proto h2' frontend")
    void h1ProbeFails() {
        try (AggregatorBlockHeightProbe probe = new AggregatorBlockHeightProbe(false)) {
            assertThatThrownBy(() -> probe.blockHeight(shardUrl())).isInstanceOf(Exception.class);
        }
    }

    @Test
    @DisplayName("the HealthCheckHandler checker (h2c probe) reports the shard healthy over h2c")
    void healthCheckerWorksOverH2c() throws Exception {
        // Exactly how ProxyServer wires the periodic /health worker: probe::blockHeight.
        try (AggregatorBlockHeightProbe probe = new AggregatorBlockHeightProbe(true)) {
            HealthCheckHandler.AggregatorHealthChecker checker = probe::blockHeight;
            assertThat(checker.checkHealth(shardUrl())).isEqualTo(42L);
        }
    }
}
