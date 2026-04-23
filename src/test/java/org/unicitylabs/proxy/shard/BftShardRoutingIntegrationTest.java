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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ShardingMode#BFT_SHARD} routing.
 *
 * <p>Two mock backends are configured behind the gateway: one for shard
 * prefix {@code "0"} (MSB=0) and one for shard prefix {@code "1"} (MSB=1).
 */
public class BftShardRoutingIntegrationTest extends AbstractIntegrationTest {
    private static final String CERTIFICATION_REQUEST_PARAMS_HEX_WRONG_TAG =
        CERTIFICATION_REQUEST_PARAMS_HEX.replaceFirst("^d99876", "d99877");
    private static final String CERTIFICATION_REQUEST_PARAMS_HEX_WRONG_VERSION =
        CERTIFICATION_REQUEST_PARAMS_HEX.replaceFirst("^d998768401", "d998768402");

    private final Map<String, Integer> prefixToPort = new LinkedHashMap<>();

    @Override
    protected void setUpConfigForTests(ProxyConfig config) {
        super.setUpConfigForTests(config);

        for (String prefix : List.of("0", "1")) {
            Server mockServer = createMockServer("bft-" + prefix);
            try {
                mockServer.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            prefixToPort.put(prefix,
                ((ServerConnector) mockServer.getConnectors()[0]).getLocalPort());
        }

        ShardConfig shardConfig = new ShardConfig(1, ShardingMode.BFT_SHARD, null, List.of(
            new BftShardInfo("0", "http://localhost:" + prefixToPort.get("0")),
            new BftShardInfo("1", "http://localhost:" + prefixToPort.get("1"))
        ));
        insertShardConfig(shardConfig);
    }

    private String shardLabel(HttpResponse<String> response) {
        return response.headers().firstValue("X-Shard-ID").orElse(null);
    }

    @Test
    @DisplayName("stateId with MSB=0 routes to shard '0'")
    void stateIdRoutesZero() throws Exception {
        HttpResponse<String> response = postJson(getInclusionProofV2Request(
            "0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("bft-0", shardLabel(response));

        response = postJson(getInclusionProofV2Request(
            "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
        assertEquals("bft-0", shardLabel(response));
    }

    @Test
    @DisplayName("stateId with MSB=1 routes to shard '1'")
    void stateIdRoutesOne() throws Exception {
        HttpResponse<String> response = postJson(getInclusionProofV2Request(
            "8000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("bft-1", shardLabel(response));

        response = postJson(getInclusionProofV2Request(
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
        assertEquals("bft-1", shardLabel(response));
    }

    @Test
    @DisplayName("real certification_request routes by embedded stateId")
    void certificationRequestRoutesByEmbeddedStateId() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
            getAuthorizedRequestBuilder("/"),
            CERTIFICATION_REQUEST_JSON);
        assertEquals(200, response.statusCode(),
            "expected 200, got " + response.statusCode() + " body: " + response.body());
        assertEquals("bft-1", shardLabel(response));
    }

    @Test
    @DisplayName("certification_request with wrong CBOR tag returns 400")
    void certificationRequestWrongTagRejected() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
            getAuthorizedRequestBuilder("/"),
            certificationRequestJson(CERTIFICATION_REQUEST_PARAMS_HEX_WRONG_TAG));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("requires stateId"), "got: " + response.body());
    }

    @Test
    @DisplayName("certification_request with wrong version returns 400")
    void certificationRequestWrongVersionRejected() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
            getAuthorizedRequestBuilder("/"),
            certificationRequestJson(CERTIFICATION_REQUEST_PARAMS_HEX_WRONG_VERSION));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("requires stateId"), "got: " + response.body());
    }

    @Test
    @DisplayName("certification_request with too-short CBOR array returns 400")
    void certificationRequestTooShortArrayRejected() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
            getAuthorizedRequestBuilder("/"),
            certificationRequestJson("d998768101"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("requires stateId"), "got: " + response.body());
    }

    @Test
    @DisplayName("certification_request with non-byte-string stateId returns 400")
    void certificationRequestNonByteStringStateIdRejected() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
            getAuthorizedRequestBuilder("/"),
            certificationRequestJson("d99876820100"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("requires stateId"), "got: " + response.body());
    }

    @Test
    @DisplayName("bft-shard accepts shardId as a bitstring label")
    void shardIdBitstringRoutes() throws Exception {
        HttpResponse<String> response = postJson(
            jsonRpc("get_block_height", "{\"shardId\":\"0\"}"));
        assertEquals("bft-0", shardLabel(response));

        response = postJson(jsonRpc("get_block_height", "{\"shardId\":\"1\"}"));
        assertEquals("bft-1", shardLabel(response));
    }

    @Test
    @DisplayName("bft-shard rejects non-bitstring shardId")
    void shardIdIntegerRejected() throws Exception {
        HttpResponse<String> response = postJsonNoStatusCheck(
            jsonRpc("get_block_height", "{\"shardId\":\"2\"}"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("bft-shard mode"),
            "expected bft-shard bitstring error, got: " + response.body());
    }

    @Test
    @DisplayName("shard-bound v2 method without stateId returns 400")
    void missingStateIdForV2MethodReturns400() throws Exception {
        HttpResponse<String> response = postJsonNoStatusCheck(
            jsonRpc("get_inclusion_proof.v2", "{}"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("requires stateId"),
            "got: " + response.body());
    }

    @Test
    @DisplayName("shard-bound v2 method with only shardId (no stateId) returns 400")
    void shardBoundV2WithOnlyShardIdReturns400() throws Exception {
        HttpResponse<String> response = postJsonNoStatusCheck(
            jsonRpc("get_inclusion_proof.v2", "{\"shardId\":\"0\"}"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("requires stateId"),
            "shard-bound v2 methods must reject shardId-only routing; got: " + response.body());
    }


    @Test
    @DisplayName("non-shard-bound JSON-RPC without routing params returns 400")
    void missingRoutingParamsReturns400() throws Exception {
        HttpResponse<String> response = postJsonNoStatusCheck(
            jsonRpc("get_block_height", "{}"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("must include either stateId or shardId"),
            "got: " + response.body());
    }

    @Test
    @DisplayName("malformed stateId (wrong length) returns 400")
    void wrongLengthStateIdReturns400() throws Exception {
        HttpResponse<String> response = postJsonNoStatusCheck(
            getInclusionProofV2Request("00"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Invalid state ID length"),
            "got: " + response.body());
    }

    @Test
    @DisplayName("34-byte stateId rejected as invalid length")
    void wrongLengthStateIdRejected() throws Exception {
        HttpResponse<String> response = postJsonNoStatusCheck(
            getInclusionProofV2Request("0000" + "00".repeat(32)));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Invalid state ID length"),
            "got: " + response.body());
    }

    @Test
    @DisplayName("non-JSON-RPC request uses random routing in bft-shard mode")
    void nonJsonRpcRandomRouting() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/test"))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String shard = shardLabel(response);
        assertTrue(shard != null && (shard.equals("bft-0") || shard.equals("bft-1")),
            "expected random routing to one of the bft shards, got: " + shard);
    }

    private @NotNull HttpResponse<String> postJson(String jsonRpcRequest) throws IOException, InterruptedException {
        HttpResponse<String> response = postJsonNoStatusCheck(jsonRpcRequest);
        assertEquals(200, response.statusCode(),
            "expected 200, got " + response.statusCode() + " body: " + response.body());
        return response;
    }

    private HttpResponse<String> postJsonNoStatusCheck(String jsonRpcRequest) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest))
            .timeout(Duration.ofSeconds(5))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String getInclusionProofV2Request(String stateIdHex) {
        return String.format("""
            {
                "jsonrpc": "2.0",
                "method": "get_inclusion_proof.v2",
                "params": {
                    "stateId": "%s"
                },
                "id": 1
            }
            """, stateIdHex);
    }

    private static String jsonRpc(String method, String paramsJson) {
        return String.format("""
            {
                "jsonrpc": "2.0",
                "method": "%s",
                "params": %s,
                "id": 1
            }
            """, method, paramsJson);
    }
}
