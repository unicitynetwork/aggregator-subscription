package org.unicitylabs.proxy.shard;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.AbstractIntegrationTest;
import org.unicitylabs.proxy.ProxyConfig;
import org.unicitylabs.proxy.TestDatabaseSetup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.*;

public class ShardRoutingIntegrationTest extends AbstractIntegrationTest {
    private static final String ZEROS_31_BYTES = "00".repeat(31);

    private final Map<Integer, Integer> shardsToMockServerPorts = new LinkedHashMap<>();

    @Override
    protected void setUpConfigForTests(ProxyConfig config) {
        super.setUpConfigForTests(config);

        asList(4, 5, 6, 7).forEach(shard -> {
            Server mockServer = createMockServer(String.valueOf(shard));
            try {
                mockServer.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            shardsToMockServerPorts.put(shard, ((ServerConnector) mockServer.getConnectors()[0]).getLocalPort());
        });

        ShardConfig shardConfig = new ShardConfig(1, List.of(
            new ShardInfo(4, "http://localhost:" + shardsToMockServerPorts.get(4)),
            new ShardInfo(5, "http://localhost:" + shardsToMockServerPorts.get(5)),
            new ShardInfo(6, "http://localhost:" + shardsToMockServerPorts.get(6)),
            new ShardInfo(7, "http://localhost:" + shardsToMockServerPorts.get(7))
        ));
        insertShardConfig(shardConfig);
    }

    private String getShardIdFromResponse(HttpResponse<String> response) {
        return response.headers().firstValue("X-Shard-ID").orElse(null);
    }

    @Test
    @DisplayName("stateId routes by bits 0,1 of byte 0 (v2 layout)")
    void testStateIdRouting() throws Exception {
        // bits 0,1 of byte 0 pick the shard (aggregator MatchesShardPrefix convention):
        //   0x00 → bits 0,0 → shard 4
        //   0x01 → bits 1,0 → shard 5
        //   0x02 → bits 0,1 → shard 6
        //   0x03 → bits 1,1 → shard 7
        assertEquals("4", getShardIdFromResponse(postJson(inclusionProofV2Request("00" + ZEROS_31_BYTES))));
        assertEquals("5", getShardIdFromResponse(postJson(inclusionProofV2Request("01" + ZEROS_31_BYTES))));
        assertEquals("6", getShardIdFromResponse(postJson(inclusionProofV2Request("02" + ZEROS_31_BYTES))));
        assertEquals("7", getShardIdFromResponse(postJson(inclusionProofV2Request("03" + ZEROS_31_BYTES))));
        // byte 0 = 0x04 → bits 0,0 → shard 4 (bits 2+ irrelevant at depth 2)
        assertEquals("4", getShardIdFromResponse(postJson(inclusionProofV2Request("04" + ZEROS_31_BYTES))));
        // byte 0 = 0x05 → bits 1,0 → shard 5
        assertEquals("5", getShardIdFromResponse(postJson(inclusionProofV2Request("05" + ZEROS_31_BYTES))));
    }

    @Test
    @DisplayName("Invalid stateId returns a clear routing error")
    void testStateIdRouting_invalidStateId() throws Exception {
        HttpResponse<String> response = postJsonNoStatusCheck(inclusionProofV2Request(""));
        assertEquals(400, response.statusCode());
        assertEquals("{\"error\":\"JSON-RPC method 'get_inclusion_proof.v2' requires stateId\"}", response.body());

        response = postJsonNoStatusCheck(inclusionProofV2Request("invalid"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Invalid state ID"),
            "expected invalid-state-ID error, got: " + response.body());
    }

    @Test
    @DisplayName("GET request uses random routing")
    void testGetRequestRandomRouting() throws Exception {
        HttpRequest request = getHttpRequest(HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/some/path"))
                .GET());

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertNotNull(getShardIdFromResponse(response), "Response should contain X-Shard-ID header");
    }

    @Test
    @DisplayName("Shard config loads from database")
    void testShardConfigLoadingFromDatabase() {
        ShardConfig shardConfig = new ShardConfig(1, List.of(
            new ShardInfo(1, "http://localhost:9999")
        ));

        insertShardConfig(shardConfig);

        var shardConfigRepository = new org.unicitylabs.proxy.repository.ShardConfigRepository(TestDatabaseSetup.getDatabaseConfig());
        var configRecord = shardConfigRepository.getLatestConfig();
        ShardConfig config = configRecord.config();

        assertNotNull(config);
        assertEquals(1, config.getVersion());
        assertEquals(1, config.getShards().size());
        assertEquals(1, config.getShards().get(0).id());
        assertEquals("http://localhost:9999", config.getShards().get(0).url());
    }

    @Test
    @DisplayName("Incomplete shard config fails validation")
    void testIncompleteConfigValidation() {
        ShardConfig incompleteConfig = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://localhost:9999")
        ));

        ShardRouter router = new DefaultShardRouter(incompleteConfig);

        assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, incompleteConfig, false)
        );
    }

    private @NotNull HttpResponse<String> postJson(String jsonRpcRequest) throws IOException, InterruptedException {
        HttpResponse<String> response = postJsonNoStatusCheck(jsonRpcRequest);
        assertEquals(200, response.statusCode(),
            "expected 200, got " + response.statusCode() + " body: " + response.body());
        return response;
    }

    private HttpResponse<String> postJsonNoStatusCheck(String jsonRpcRequest) throws IOException, InterruptedException {
        HttpRequest request = getHttpRequest(HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest)));

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private @NotNull String inclusionProofV2Request(String stateId) {
        return String.format("""
            {
                "jsonrpc": "2.0",
                "method": "get_inclusion_proof.v2",
                "params": {
                    "stateId": "%s"
                },
                "id": 1
            }
            """, stateId);
    }

    private HttpRequest getHttpRequest(HttpRequest.Builder jsonRpcRequest) {
        return jsonRpcRequest
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    @DisplayName("JSON-RPC without routing params returns 400")
    void testJsonRpcWithoutRoutingParamsReturns400() throws Exception {
        String jsonRpcRequest = """
            {
                "jsonrpc": "2.0",
                "method": "get_block_height",
                "params": {},
                "id": 1
            }
            """;

        HttpResponse<String> response = postJsonNoStatusCheck(jsonRpcRequest);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("JSON-RPC requests must include either stateId or shardId"),
            "got: " + response.body());
    }

    @Test
    @DisplayName("JSON-RPC with stateId succeeds")
    void testJsonRpcWithStateIdSucceeds() throws Exception {
        // byte 0 = 0x02 → bits 0,1 = 0,1 → shard 6
        String jsonRpcRequest = inclusionProofV2Request("02" + ZEROS_31_BYTES);
        HttpResponse<String> response = postJson(jsonRpcRequest);
        assertEquals(200, response.statusCode());
        assertEquals("6", getShardIdFromResponse(response));
    }

    @Test
    @DisplayName("JSON-RPC with shardId succeeds even for non-shard-bound methods")
    void testJsonRpcWithShardIdSucceeds() throws Exception {
        String jsonRpcRequest = """
            {
                "jsonrpc": "2.0",
                "method": "get_block_height",
                "params": {
                    "shardId": "6"
                },
                "id": 1
            }
            """;

        HttpResponse<String> response = postJson(jsonRpcRequest);
        assertEquals(200, response.statusCode());
        assertEquals("6", getShardIdFromResponse(response));
    }

    @Test
    @DisplayName("JSON-RPC with both stateId and shardId returns 400")
    void testJsonRpcWithBothParamsReturns400() throws Exception {
        String jsonRpcRequest = String.format("""
            {
                "jsonrpc": "2.0",
                "method": "get_inclusion_proof.v2",
                "params": {
                    "stateId": "%s",
                    "shardId": "4"
                },
                "id": 1
            }
            """, "00" + ZEROS_31_BYTES);

        HttpResponse<String> response = postJsonNoStatusCheck(jsonRpcRequest);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Cannot specify both stateId and shardId"),
            "got: " + response.body());
    }

    @Test
    @DisplayName("Non-JSON-RPC request with shard cookie routes correctly")
    void testNonJsonRpcWithShardCookieRoutes() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/test"))
                .header("Cookie", "UNICITY_SHARD_ID=4")
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("4", getShardIdFromResponse(response));
    }

    @Test
    @DisplayName("Non-JSON-RPC request with stateId cookie routes correctly")
    void testNonJsonRpcWithStateIdCookieRoutes() throws Exception {
        // byte 0 = 0x02 → bits 0,1 = 0,1 → shard 6
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/test"))
                .header("Cookie", "UNICITY_STATE_ID=02" + ZEROS_31_BYTES)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("6", getShardIdFromResponse(response));
    }

    @Test
    @DisplayName("Non-JSON-RPC request with both cookies returns 400")
    void testNonJsonRpcWithBothCookiesReturns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/test"))
                .header("Cookie", "UNICITY_SHARD_ID=4; UNICITY_STATE_ID=00" + ZEROS_31_BYTES)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Cannot specify both stateId and shardId"),
            "got: " + response.body());
    }

    @Test
    @DisplayName("Non-JSON-RPC request without cookies uses random routing")
    void testNonJsonRpcWithoutCookiesUsesRandom() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/test"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertNotNull(getShardIdFromResponse(response), "Response should contain X-Shard-ID header");

        String shardId = getShardIdFromResponse(response);
        assertTrue(asList("4", "5", "6", "7").contains(shardId));
    }

    @Test
    @DisplayName("Shard URL with trailing slash and subpath joins correctly without double slashes")
    void testShardUrlWithTrailingSlashAndSubpath() throws Exception {
        Server mockServerWithSubpath = createMockServer("1");
        mockServerWithSubpath.start();

        try {
            int mockPort = ((ServerConnector) mockServerWithSubpath.getConnectors()[0]).getLocalPort();

            insertShardConfig(new ShardConfig(1, List.of(
                new ShardInfo(1, "http://localhost:" + mockPort + "/api/v1/")
            )));

            setUpNewProxyServer();

            HttpResponse<String> response = postJson("""
                {
                    "jsonrpc": "2.0",
                    "method": "get_block_height",
                    "params": {
                        "shardId": "1"
                    },
                    "id": 1
                }
                """);

            assertEquals(200, response.statusCode());
            assertEquals("/api/v1/", response.headers().firstValue("X-Received-Path").orElse(null),
                "Path should be correctly joined without double slashes");
        } finally {
            mockServerWithSubpath.stop();
        }
    }
}
