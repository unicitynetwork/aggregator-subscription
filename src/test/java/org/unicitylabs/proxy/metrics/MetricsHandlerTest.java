package org.unicitylabs.proxy.metrics;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class MetricsHandlerTest {

    private Server server;
    private int port;
    private GatewayMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        metrics = new GatewayMetrics();
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new MetricsHandler(metrics));
        server.start();
        port = connector.getLocalPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
    }

    @Test
    void scrapesPrometheusExposition() throws Exception {
        // Record a few synthetic samples
        metrics.recordRequest(GatewayMetrics.Outcome.SUCCESS, "submit_commitment", 200, 1_000_000L);
        metrics.recordRequest(GatewayMetrics.Outcome.UNAUTHORIZED, "submit_commitment", 401, 500_000L);
        metrics.recordRequest(GatewayMetrics.Outcome.RATE_LIMITED, "submit_commitment", 429, 500_000L);
        metrics.recordRequest(GatewayMetrics.Outcome.SUCCESS, "wat", 200, 500_000L); // unknown method -> "other"
        metrics.recordUpstream("http://shard-1", true);

        String body = scrape();

        // Exposition format header
        assertThat(body).contains("# HELP gateway_requests_total");
        assertThat(body).contains("# TYPE gateway_requests_total counter");

        // Outcome labels are present
        assertThat(body).contains("outcome=\"success\"");
        assertThat(body).contains("outcome=\"unauthorized\"");
        assertThat(body).contains("outcome=\"rate_limited\"");

        // Method labels: known method preserved, unknown collapsed to "other"
        assertThat(body).contains("jsonrpc_method=\"submit_commitment\"");
        assertThat(body).contains("jsonrpc_method=\"other\"");

        // Status class labels
        assertThat(body).contains("status_class=\"2xx\"");
        assertThat(body).contains("status_class=\"4xx\"");

        // Latency histogram exposed
        assertThat(body).contains("gateway_request_duration_seconds");

        // Upstream counter exposed
        assertThat(body).contains("gateway_upstream_requests_total");

        // JVM binders present
        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("jvm_threads_live_threads");
        assertThat(body).contains("process_uptime_seconds");
    }

    @Test
    void unknownPathReturnsNoResponse() throws Exception {
        // /metrics handler returns false for non-/metrics paths;
        // with no fallback handler installed, Jetty returns 404.
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/not-metrics")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
    }

    private String scrape() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("content-type")).isPresent()
            .get().asString().startsWith("text/plain");
        return resp.body();
    }
}
