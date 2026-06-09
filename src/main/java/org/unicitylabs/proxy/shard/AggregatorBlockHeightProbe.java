package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;

import java.util.concurrent.TimeUnit;

/**
 * Single source of truth for "is this shard aggregator reachable and a valid aggregator?", by
 * calling {@code get_block_height} over the SAME transport the proxy uses for real traffic:
 *
 * <ul>
 *   <li>cleartext HTTP/2 (h2c, prior-knowledge) for {@code http://} upstreams when {@code upstreamH2c}
 *       is set — mirroring {@code RequestHandler.shouldUseUpstreamH2c}. An HAProxy
 *       {@code bind ... proto h2} frontend speaks h2c prior-knowledge only and rejects an HTTP/1.1
 *       request with "unexpected end of stream";</li>
 *   <li>the HTTP/1.1 SDK client otherwise — including every {@code https://} upstream, which the
 *       runtime reaches over TLS, not cleartext h2c.</li>
 * </ul>
 *
 * <p>Shared by {@code ShardConfigValidator} (startup / admin upload / hot-reload — one probe per
 * validation pass) and {@code HealthCheckHandler} (periodic {@code /health} — one long-lived probe),
 * so the get_block_height check exists in exactly one place. Thread-safe: the optional Jetty h2c
 * client is created once and reused for concurrent requests (the health worker probes shards in
 * parallel). {@link AutoCloseable}: {@link #close()} stops the h2c client — a caller holding a
 * long-lived probe must close it on shutdown; a per-pass caller should use try-with-resources.
 */
public final class AggregatorBlockHeightProbe implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(AggregatorBlockHeightProbe.class);
    private static final ObjectMapper MAPPER = ObjectMapperUtils.createObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final long REQUEST_TIMEOUT_SECONDS = 10;
    private static final String GET_BLOCK_HEIGHT_REQUEST =
        "{\"jsonrpc\":\"2.0\",\"method\":\"get_block_height\",\"params\":{},\"id\":1}";

    private final boolean upstreamH2c;
    // Lazily started on the first h2c probe (double-checked locking over a volatile field):
    // constructing a probe never starts a Jetty client / thread pool, so it can't leak one if the
    // surrounding construction later fails, and a probe that never hits an http:// upstream
    // allocates nothing. Started at most once, then reused for the health worker's parallel probes.
    private volatile HttpClient h2cClient;

    public AggregatorBlockHeightProbe(boolean upstreamH2c) {
        this.upstreamH2c = upstreamH2c;
    }

    /**
     * Probes {@code get_block_height} on the given shard URL.
     *
     * @return the reported block height (best-effort — {@code -1} if the result shape is unexpected
     *         but the response is otherwise a valid, error-free aggregator answer)
     * @throws Exception if the upstream is unreachable or does not answer get_block_height like a
     *         valid aggregator (HTTP error, JSON-RPC error, missing result, protocol mismatch, ...)
     */
    public long blockHeight(String url) throws Exception {
        if (useH2c(url)) {
            ContentResponse response = h2cClient().newRequest(url)
                .method(HttpMethod.POST)
                .headers(h -> h.put(HttpHeader.CONTENT_TYPE, "application/json"))
                .body(new StringRequestContent("application/json", GET_BLOCK_HEIGHT_REQUEST))
                .timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .send();
            return parseBlockHeightResponse(response.getStatus(), response.getContentAsString());
        }
        return new JsonRpcAggregatorClient(url).getBlockHeight().get();
    }

    private boolean useH2c(String url) {
        return upstreamH2c && url != null && url.regionMatches(true, 0, "http://", 0, "http://".length());
    }

    /** Lazily starts the shared h2c client on first use (double-checked locking over the volatile field). */
    private HttpClient h2cClient() {
        HttpClient client = h2cClient;
        if (client == null) {
            synchronized (this) {
                client = h2cClient;
                if (client == null) {
                    client = startH2cClient();
                    h2cClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Parses a get_block_height JSON-RPC response. Throws if it is not a successful aggregator
     * answer (non-2xx, JSON-RPC error, or missing result). The returned height is best-effort
     * (informational): an unexpected result shape yields {@code -1} rather than failing, since a
     * present, error-free result already proves a valid aggregator answered. Package-private so the
     * accept/reject branches are unit-testable without a live server.
     */
    static long parseBlockHeightResponse(int status, String body) throws Exception {
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("get_block_height returned HTTP " + status);
        }
        JsonNode root = MAPPER.readTree(body);
        if (root.hasNonNull("error")) {
            throw new IllegalStateException("get_block_height returned JSON-RPC error: " + root.get("error"));
        }
        JsonNode result = root.get("result");
        if (result == null || result.isNull()) {
            throw new IllegalStateException("get_block_height response has no result");
        }
        JsonNode height = result.has("blockNumber") ? result.get("blockNumber") : result;
        try {
            return height.isNumber() ? height.asLong() : Long.parseLong(height.asText().trim());
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static HttpClient startH2cClient() {
        HTTP2Client h2Client = new HTTP2Client();
        h2Client.setUseALPN(false); // cleartext h2c prior-knowledge (no ALPN/TLS, no h1 upgrade)
        HttpClient client = new HttpClient(new HttpClientTransportOverHTTP2(h2Client));
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        try {
            client.start();
        } catch (Exception e) {
            try {
                client.stop();
            } catch (Exception ignored) {
                // best effort — start() already failed
            }
            throw new IllegalStateException("Failed to start h2c aggregator-probe client", e);
        }
        return client;
    }

    @Override
    public void close() {
        HttpClient client = h2cClient;
        if (client != null) {
            try {
                client.stop();
            } catch (Exception e) {
                logger.warn("Failed to stop h2c aggregator-probe client", e);
            }
        }
    }
}
