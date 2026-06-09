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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ShardConfigValidator {
    private static final Logger logger = LoggerFactory.getLogger(ShardConfigValidator.class);
    private static final ObjectMapper MAPPER = ObjectMapperUtils.createObjectMapper();

    /**
     * Validates shard configuration with connectivity checks enabled.
     */
    public static void validate(ShardRouter router, ShardConfig config) {
        validate(router, config, true, false);
    }

    /**
     * Validates shard configuration (upstream assumed HTTP/1.1).
     */
    public static void validate(ShardRouter router, ShardConfig config, boolean validateConnectivity) {
        validate(router, config, validateConnectivity, false);
    }

    /**
     * Validates shard configuration.
     * @param router the shard router
     * @param config the shard configuration
     * @param validateConnectivity whether to validate connectivity by calling get_block_height on each shard
     * @param upstreamH2c whether upstream connections use cleartext HTTP/2 (h2c). When true the
     *                    connectivity probe MUST also use h2c: the upstream (e.g. an HAProxy
     *                    'bind ... proto h2' frontend) speaks h2c prior-knowledge only and rejects
     *                    an HTTP/1.1 probe. Mirror {@code ProxyConfig.isUpstreamH2cEnabled()}.
     */
    public static void validate(ShardRouter router, ShardConfig config, boolean validateConnectivity, boolean upstreamH2c) {
        if (config == null) {
            throw new IllegalArgumentException("Shard configuration is null");
        }

        ShardingMode mode = config.getMode();

        if (router instanceof FailsafeShardRouter) {
            return;
        }

        if (mode == ShardingMode.BFT_SHARD) {
            validateBftShardConfig(config, validateConnectivity, upstreamH2c);
            if (!(router instanceof BftShardRouter)) {
                throw new IllegalArgumentException(
                    "bft-shard config requires BftShardRouter, got: " + router.getClass());
            }
        } else {
            validateAppShardConfig(config, validateConnectivity, upstreamH2c);
            if (router instanceof DefaultShardRouter defaultRouter) {
                validateTreeCompleteness(defaultRouter.getRootNode());
            } else {
                throw new IllegalArgumentException(
                    "app-shard config requires DefaultShardRouter, got: " + router.getClass());
            }
        }
    }

    private static void validateAppShardConfig(ShardConfig config, boolean validateConnectivity, boolean upstreamH2c) {
        if (config.getShards() == null || config.getShards().isEmpty()) {
            throw new IllegalArgumentException("Shard configuration has no shards");
        }
        if (config.getBftShards() != null && !config.getBftShards().isEmpty()) {
            throw new IllegalArgumentException(
                "app-shard mode must not populate 'bftShards'; set mode to 'bft-shard' or remove the entries");
        }
        validateUniqueShardIds(config.getShards(), validateConnectivity, upstreamH2c);
    }

    private static void validateBftShardConfig(ShardConfig config, boolean validateConnectivity, boolean upstreamH2c) {
        List<BftShardInfo> bftShards = config.getBftShards();
        if (bftShards == null || bftShards.isEmpty()) {
            throw new IllegalArgumentException("bft-shard configuration has no bftShards entries");
        }
        if (config.getShards() != null && !config.getShards().isEmpty()) {
            throw new IllegalArgumentException(
                "bft-shard mode must not populate 'shards'; set mode to 'app-shard' or remove the entries");
        }

        Set<String> seenPrefixes = new HashSet<>();
        for (BftShardInfo shard : bftShards) {
            if (!seenPrefixes.add(shard.prefix())) {
                throw new IllegalArgumentException("Duplicate bft shard prefix: '" + shard.prefix() + "'");
            }
            validateUrl(shard.url(), "bft shard '" + shard.prefix() + "'");
        }

        validatePrefixFreeAndCovering(bftShards);

        if (validateConnectivity) {
            for (BftShardInfo shard : bftShards) {
                validateShardConnectivity(shard.url(), "bft shard '" + shard.prefix() + "'", upstreamH2c);
            }
        }
    }

    /**
     * Enforces that the prefix set is both prefix-free (no prefix is a prefix
     * of another) and covering (every possible bitstring has exactly one
     * matching prefix).
     *
     * <p>Checked by inserting each prefix into a binary tree and then verifying
     * that every internal node has two children and every leaf has zero.
     */
    private static void validatePrefixFreeAndCovering(List<BftShardInfo> shards) {
        PrefixNode root = new PrefixNode();
        for (BftShardInfo shard : shards) {
            insertPrefix(root, shard.prefix());
        }
        validateCoverage(root, "");
    }

    private static void insertPrefix(PrefixNode root, String prefix) {
        PrefixNode current = root;
        for (int i = 0; i < prefix.length(); i++) {
            if (current.terminal) {
                throw new IllegalArgumentException(
                    "bft shard prefixes are not prefix-free: '" + prefix + "' extends an existing shard");
            }
            char bit = prefix.charAt(i);
            if (bit == '0') {
                if (current.zero == null) current.zero = new PrefixNode();
                current = current.zero;
            } else {
                if (current.one == null) current.one = new PrefixNode();
                current = current.one;
            }
        }
        if (current.terminal) {
            throw new IllegalArgumentException(
                "Duplicate bft shard prefix: '" + prefix + "'");
        }
        if (current.zero != null || current.one != null) {
            throw new IllegalArgumentException(
                "bft shard prefixes are not prefix-free: '" + prefix + "' is a prefix of an existing shard");
        }
        current.terminal = true;
    }

    private static void validateCoverage(PrefixNode node, String path) {
        if (node.terminal) {
            return;
        }
        if (node.zero == null) {
            throw new IllegalArgumentException(
                "bft shard set is not covering: no shard matches stateIds with prefix '" + path + "0'");
        }
        if (node.one == null) {
            throw new IllegalArgumentException(
                "bft shard set is not covering: no shard matches stateIds with prefix '" + path + "1'");
        }
        validateCoverage(node.zero, path + "0");
        validateCoverage(node.one, path + "1");
    }

    private static final class PrefixNode {
        PrefixNode zero;
        PrefixNode one;
        boolean terminal;
    }

    private static void validateUniqueShardIds(List<ShardInfo> shards, boolean validateConnectivity, boolean upstreamH2c) {
        if (shards == null) {
            return;
        }

        Set<Integer> seenIds = new HashSet<>();
        for (ShardInfo shard : shards) {
            if (!seenIds.add(shard.id())) {
                throw new IllegalArgumentException("Duplicate shard ID: " + shard.id());
            }

            validateUrl(shard.url(), "Shard " + shard.id());
        }

        if (validateConnectivity) {
            for (ShardInfo shard : shards) {
                validateShardConnectivity(shard.url(), "Shard " + shard.id(), upstreamH2c);
            }
        }
    }

    private static void validateUrl(String url, String shardLabel) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(shardLabel + " has empty or null URL");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(shardLabel + " has malformed URL: " + url + " - " + e.getMessage());
        }

        if (uri.getQuery() != null) {
            throw new IllegalArgumentException(shardLabel + " URL must not contain query parameters: " + url);
        }

        if (uri.getFragment() != null) {
            throw new IllegalArgumentException(shardLabel + " URL must not contain fragment: " + url);
        }

        if (uri.getScheme() == null) {
            throw new IllegalArgumentException(shardLabel + " URL must have a scheme (http or https): " + url);
        }

        if (uri.getHost() == null) {
            throw new IllegalArgumentException(shardLabel + " URL must have a host: " + url);
        }
    }

    private static void validateShardConnectivity(String url, String shardLabel, boolean upstreamH2c) {
        // Mirror RequestHandler.shouldUseUpstreamH2c: cleartext h2c applies ONLY to http:// upstreams.
        // For https:// the runtime proxies over normal TLS, so the probe must use the (TLS-capable)
        // SDK client too — probing an https:// endpoint with a cleartext-only h2c client would
        // spuriously fail. (testnet2 shards are http://, so this resolves to plain upstreamH2c there.)
        boolean useH2c = upstreamH2c && url.regionMatches(true, 0, "http://", 0, "http://".length());
        logger.debug("Validating connectivity to {} at {} (h2c={})", shardLabel, url, useH2c);
        try {
            if (useH2c) {
                // The SDK's JsonRpcAggregatorClient is HTTP/1.1-only (it builds its own okhttp
                // client and exposes no way to inject one), so it CANNOT probe an h2c-only
                // upstream — e.g. an HAProxy 'bind ... proto h2' frontend, which speaks cleartext
                // HTTP/2 prior-knowledge and rejects HTTP/1.1 with "unexpected end of stream".
                // Probe over the same h2c transport the proxy uses for real traffic instead.
                long blockHeight = probeBlockHeightH2c(url);
                logger.debug("{} at {} is reachable over h2c (block height: {})", shardLabel, url, blockHeight);
            } else {
                JsonRpcAggregatorClient client = new JsonRpcAggregatorClient(url);
                long blockHeight = client.getBlockHeight().get();
                logger.debug("{} at {} is reachable (block height: {})", shardLabel, url, blockHeight);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("%s at %s is not reachable or not a valid aggregator: %s",
                    shardLabel, url, e.getMessage()),
                e
            );
        }
    }

    /**
     * Probes get_block_height over cleartext HTTP/2 (h2c, prior-knowledge) — the same transport
     * the proxy uses for upstream traffic when --upstream-h2c is enabled (Jetty HTTP2Client with
     * ALPN disabled). Returns the reported block height; throws if the upstream is unreachable
     * over h2c or does not answer get_block_height like an aggregator.
     */
    private static long probeBlockHeightH2c(String url) throws Exception {
        HTTP2Client h2Client = new HTTP2Client();
        h2Client.setUseALPN(false); // cleartext h2c prior-knowledge (no ALPN/TLS, no h1 upgrade)
        HttpClient client = new HttpClient(new HttpClientTransportOverHTTP2(h2Client));
        client.setConnectTimeout(5000);
        try {
            client.start(); // inside try so a start() failure is still torn down by finally
            String requestBody = "{\"jsonrpc\":\"2.0\",\"method\":\"get_block_height\",\"params\":{},\"id\":1}";
            ContentResponse response = client.newRequest(url)
                .method(HttpMethod.POST)
                .headers(h -> h.put(HttpHeader.CONTENT_TYPE, "application/json"))
                .body(new StringRequestContent("application/json", requestBody))
                .timeout(10, TimeUnit.SECONDS)
                .send();
            return parseBlockHeightResponse(response.getStatus(), response.getContentAsString());
        } finally {
            client.stop();
        }
    }

    /**
     * Parses a get_block_height JSON-RPC response. Throws if it is not a successful aggregator
     * answer (non-2xx, JSON-RPC error, or missing result) — those mean "not reachable / not a valid
     * aggregator". The returned height is best-effort (debug log only): an unexpected result shape
     * yields -1 rather than failing, since a present, error-free result already proves a valid
     * aggregator answered. Package-private so the rejection branches are unit-testable without a server.
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

    private static void validateTreeCompleteness(ShardTreeNode node) {
        validateTreeCompleteness(node, "", 0);
    }

    private static void validateTreeCompleteness(ShardTreeNode node, String pathBits, int depth) {
        if (node == null) {
            throw new IllegalArgumentException(
                String.format("Routing tree has null node at depth %d (path: %s)",
                    depth, pathBits.isBlank() ? "root" : pathBits)
            );
        }

        if (node.isLeaf()) {
            if (node.getTargetUrl() == null || node.getTargetUrl().isBlank()) {
                throw new IllegalArgumentException(
                    String.format("Leaf node has no target URL at depth %d (path: %s)",
                        depth, pathBits.isBlank() ? "root" : pathBits)
                );
            }
        } else {
            if (node.getLeft() == null) {
                throw new IllegalArgumentException(
                    String.format("Incomplete routing tree: missing left child for request IDs with binary suffix: 0%s", pathBits)
                );
            }
            if (node.getRight() == null) {
                throw new IllegalArgumentException(
                    String.format("Incomplete routing tree: missing right child for request IDs with binary suffix: 1%s", pathBits)
                );
            }

            validateTreeCompleteness(node.getLeft(), "0" + pathBits, depth + 1);
            validateTreeCompleteness(node.getRight(), "1" + pathBits, depth + 1);
        }
    }
}
