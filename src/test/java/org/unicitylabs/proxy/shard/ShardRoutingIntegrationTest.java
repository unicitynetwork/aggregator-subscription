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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.*;

public class ShardRoutingIntegrationTest extends AbstractIntegrationTest {
    private final Map<Integer, Integer> shardsToMockServerPorts = new LinkedHashMap<>();

    @Override
    protected void setUpConfigForTests(ProxyConfig config) {
        super.setUpConfigForTests(config);

        asList(0, 1).forEach(shard -> {
            Server mockServer = createMockServer(String.valueOf(shard));
            try {
                mockServer.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            shardsToMockServerPorts.put(shard, ((ServerConnector) mockServer.getConnectors()[0]).getLocalPort());
        });

        try {
            String shardConfigJson = String.format(
                    """
                            {
                              "version": 1,
                              "targets": {
                                "2": "http://localhost:%d",
                                "3": "http://localhost:%d"
                              }
                            }""",
                    shardsToMockServerPorts.get(0),
                    shardsToMockServerPorts.get(1)
            );
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test-shard-config-", ".json");
            java.nio.file.Files.writeString(tempFile, shardConfigJson);
            tempFile.toFile().deleteOnExit();
            config.setShardConfigUrl("file://" + tempFile.toAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test shard config", e);
        }
    }

    private String getShardIdFromResponse(HttpResponse<String> response) {
        return response.headers().firstValue("X-Shard-ID").orElse(null);
    }

    @Test
    @DisplayName("Test JSON-RPC request with requestId routes correctly")
    void testRequestIdRouting_shard0() throws Exception {
        HttpResponse<String> response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("0", getShardIdFromResponse(response));

       response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000001"));
        assertEquals("1", getShardIdFromResponse(response));

        response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000002"));
        assertEquals("0", getShardIdFromResponse(response));

        response = postJson(inclusionProofRequest("0000000000000000000000000000000000000000000000000000000000000003"));
        assertEquals("1", getShardIdFromResponse(response));

        response = postJson(inclusionProofRequest("1234238947A223094820398423048230482038420840923809EEEEEEE234234F"));
        assertEquals("1", getShardIdFromResponse(response));
    }

    @Test
    @DisplayName("Test request without requestId uses random routing")
    void testRandomRouting() throws Exception {
        String jsonRpcRequest = """
            {
                "jsonrpc": "2.0",
                "method": "some_method",
                "params": {},
                "id": 1
            }
            """;

        HttpResponse<String> response = postJson(jsonRpcRequest);
        assertNotNull(getShardIdFromResponse(response), "Response should contain X-Shard-ID header");
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
    @DisplayName("Test shard config loading from file")
    void testShardConfigLoadingFromFile() throws Exception {
        // Create a test shard config file
        String shardConfigJson = """
            {
                "version": 1,
                "targets": {
                    "1": "http://localhost:9999"
                }
            }
            """;

        Path tempFile = Files.createTempFile("test-shard-", ".json");
        Files.writeString(tempFile, shardConfigJson);
        tempFile.toFile().deleteOnExit();

        // Load config
        ShardConfig config = ShardConfigLoader.load("file://" + tempFile.toAbsolutePath());

        assertNotNull(config);
        assertEquals(1, config.getVersion());
        assertEquals(1, config.getTargets().size());
        assertTrue(config.getTargets().containsKey("1"));
        assertEquals("http://localhost:9999", config.getTargets().get("1"));
    }

    @Test
    @DisplayName("Test invalid shard config file throws exception")
    void testInvalidShardConfigThrows() {
        assertThrows(Exception.class, () ->
            ShardConfigLoader.load("file:///nonexistent/path/config.json")
        );
    }

    @Test
    @DisplayName("Test incomplete shard config fails validation")
    void testIncompleteConfigValidation() throws Exception {
        // Create incomplete config (only covers half the space)
        String incompleteConfigJson = """
            {
                "version": 1,
                "targets": {
                    "2": "http://localhost:9999"
                }
            }
            """;

        Path tempFile = Files.createTempFile("test-incomplete-shard-", ".json");
        Files.writeString(tempFile, incompleteConfigJson);
        tempFile.toFile().deleteOnExit();

        // Load config and create router
        ShardConfig config = ShardConfigLoader.load("file://" + tempFile.toAbsolutePath());
        ShardRouter router = new ShardRouter(config);

        // Validation should throw
        assertThrows(IllegalArgumentException.class, () ->
            ShardConfigValidator.validate(router, config)
        );
    }

    @Test
    @DisplayName("Test complete 2-bit shard config passes validation")
    void testComplete2BitConfig() throws Exception {
        String completeConfigJson = """
            {
                "version": 1,
                "targets": {
                    "4": "http://shard-00.example.com",
                    "5": "http://shard-01.example.com",
                    "6": "http://shard-10.example.com",
                    "7": "http://shard-11.example.com"
                }
            }
            """;

        Path tempFile = Files.createTempFile("test-complete-shard-", ".json");
        Files.writeString(tempFile, completeConfigJson);
        tempFile.toFile().deleteOnExit();

        ShardConfig config = ShardConfigLoader.load("file://" + tempFile.toAbsolutePath());

        assertNotNull(config);
        assertEquals(4, config.getTargets().size());
    }

    private @NotNull HttpResponse<String> postJson(String jsonRpcRequest) throws IOException, InterruptedException {
        HttpRequest request = getHttpRequest(HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest)));

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        return response;
    }

    private @NotNull String inclusionProofRequest(String requestId) {
        String jsonRpcRequest = String.format("""
            {
                "jsonrpc": "2.0",
                "method": "get_inclusion_proof",
                "params": {
                    "requestId": "%s"
                },
                "id": 1
            }
            """, requestId);
        return jsonRpcRequest;
    }

    private HttpRequest getHttpRequest(HttpRequest.Builder jsonRpcRequest) {
        return jsonRpcRequest
                .timeout(Duration.ofSeconds(5))
                .build();
    }
}
