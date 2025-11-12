package org.unicitylabs.proxy.shard;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.AbstractIntegrationTest;
import org.unicitylabs.proxy.ProxyConfig;

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
    @DisplayName("Test JSON-RPC request with requestId routes correctly")
    void testRequestIdRouting_shard0() throws Exception {
        HttpResponse<String> response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("4", getShardIdFromResponse(response));

       response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000001"));
        assertEquals("5", getShardIdFromResponse(response));

        response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000002"));
        assertEquals("6", getShardIdFromResponse(response));

        response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000003"));
        assertEquals("7", getShardIdFromResponse(response));

        response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000004"));
        assertEquals("4", getShardIdFromResponse(response));

        response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000005"));
        assertEquals("5", getShardIdFromResponse(response));

        response = postJson(inclusionProofRequest("1234238947A223094820398423048230482038420840923809EEEEEEE234234F"));
        assertEquals("7", getShardIdFromResponse(response));
    }

    @Test
    @DisplayName("Test JSON-RPC request with invalid requestId returns error messages")
    void testRequestIdRouting_invalidRequestId() throws Exception {
        HttpResponse<String> response = postJsonNoStatusCheck(inclusionProofRequest(""));
        assertEquals(400, response.statusCode());
        assertEquals("{\"error\":\"JSON-RPC requests must include either requestId or shardId\"}", response.body());

        response = postJsonNoStatusCheck(inclusionProofRequest("invalid"));
        assertEquals(400, response.statusCode());
        assertEquals("{\"error\":\"Invalid request ID format: 'invalid'\"}", response.body());
    }

    @Test
    @DisplayName("Test GET request uses random routing")
    void testGetRequestRandomRouting() throws Exception {
        HttpRequest request = getHttpRequest(HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/some/path"))
                .GET());

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertNotNull(getShardIdFromResponse(response), "Response should contain X-Shard-ID header");
    }

    @Test
    @DisplayName("Test shard config loading from database")
    void testShardConfigLoadingFromDatabase() {
        // Create and save a test shard config
        ShardConfig shardConfig = new ShardConfig(1, List.of(
            new ShardInfo(1, "http://localhost:9999")
        ));

        insertShardConfig(shardConfig);

        // Load config from database
        var shardConfigRepository = new org.unicitylabs.proxy.repository.ShardConfigRepository();
        var configRecord = shardConfigRepository.getLatestConfig();
        ShardConfig config = configRecord.config();

        assertNotNull(config);
        assertEquals(1, config.getVersion());
        assertEquals(1, config.getShards().size());
        assertEquals(1, config.getShards().get(0).id());
        assertEquals("http://localhost:9999", config.getShards().get(0).url());
    }

    @Test
    @DisplayName("Test incomplete shard config fails validation")
    void testIncompleteConfigValidation() {
        // Create incomplete config (only covers half the space)
        ShardConfig incompleteConfig = new ShardConfig(1, List.of(
            new ShardInfo(2, "http://localhost:9999")
        ));

        // Create router with incomplete config
        ShardRouter router = new DefaultShardRouter(incompleteConfig);

        // Validation should throw
        assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, incompleteConfig)
        );
    }

    private @NotNull HttpResponse<String> postJson(String jsonRpcRequest) throws IOException, InterruptedException {
        HttpResponse<String> response = postJsonNoStatusCheck(jsonRpcRequest);
        assertEquals(200, response.statusCode());
        return response;
    }

    private HttpResponse<String> postJsonNoStatusCheck(String jsonRpcRequest) throws IOException, InterruptedException {
        HttpRequest request = getHttpRequest(HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest)));

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response;
    }

    private @NotNull String inclusionProofRequest(String requestId) {
        return String.format("""
            {
                "jsonrpc": "2.0",
                "method": "get_inclusion_proof",
                "params": {
                    "requestId": "%s"
                },
                "id": 1
            }
            """, requestId);
    }

    private HttpRequest getHttpRequest(HttpRequest.Builder jsonRpcRequest) {
        return jsonRpcRequest
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    @DisplayName("Test JSON-RPC request without routing params returns 400")
    void testJsonRpcWithoutRoutingParamsReturns400() throws Exception {
        String jsonRpcRequest = """
            {
                "jsonrpc": "2.0",
                "method": "some_method",
                "params": {},
                "id": 1
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest))
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("JSON-RPC requests must include either requestId or shardId"));
    }

    @Test
    @DisplayName("Test JSON-RPC request with requestId succeeds")
    void testJsonRpcWithRequestIdSucceeds() throws Exception {
        String jsonRpcRequest = """
            {
                "jsonrpc": "2.0",
                "method": "get_inclusion_proof",
                "params": {
                    "requestId": "00000000000000000000000000000000000000000000000000000000000000F2"
                },
                "id": 1
            }
            """;

        HttpResponse<String> response = postJson(jsonRpcRequest);
        assertEquals(200, response.statusCode());
        assertEquals("6", getShardIdFromResponse(response));
    }

    @Test
    @DisplayName("Test JSON-RPC request with shardId succeeds")
    void testJsonRpcWithShardIdSucceeds() throws Exception {
        String jsonRpcRequest = """
            {
                "jsonrpc": "2.0",
                "method": "some_method",
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
    @DisplayName("Test JSON-RPC request with both requestId and shardId returns 400")
    void testJsonRpcWithBothParamsReturns400() throws Exception {
        String jsonRpcRequest = """
            {
                "jsonrpc": "2.0",
                "method": "some_method",
                "params": {
                    "requestId": "0000000000000000000000000000000000000000000000000000000000000000",
                    "shardId": "4"
                },
                "id": 1
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest))
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Cannot specify both requestId and shardId"));
    }

    @Test
    @DisplayName("Test non-JSON-RPC request with shard cookie routes correctly")
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
    @DisplayName("Test non-JSON-RPC request with requestId cookie routes correctly")
    void testNonJsonRpcWithRequestIdCookieRoutes() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/test"))
                .header("Cookie", "UNICITY_REQUEST_ID=00000000000000000000000000000000000000000000000000000000000000F1")
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("5", getShardIdFromResponse(response));
    }

    @Test
    @DisplayName("Test non-JSON-RPC request with both cookies returns 400")
    void testNonJsonRpcWithBothCookiesReturns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/test"))
                .header("Cookie", "UNICITY_SHARD_ID=0; UNICITY_REQUEST_ID=0000000000000000000000000000000000000000000000000000000000000000")
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Cannot specify both requestId and shardId"));
    }

    @Test
    @DisplayName("Test non-JSON-RPC request without cookies uses random routing")
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
}
