package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.repository.ShardConfigRepository;
import org.unicitylabs.proxy.shard.ShardConfig;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class AdminIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper objectMapper = ObjectMapperUtils.createObjectMapper();

    @Test
    @DisplayName("Test GET admin plans endpoint includes minimumPaymentAmount")
    void testGetAdminPlansIncludesMinimumPayment() throws Exception {
        String token = loginAsAdmin();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/admin/api/plans"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        // Verify response includes minimumPaymentAmount field
        var jsonNode = objectMapper.readTree(response.body());
        assertTrue(jsonNode.has("minimumPaymentAmount"), "Response should include minimumPaymentAmount");
        assertEquals(config.getMinimumPaymentAmount().toString(), jsonNode.get("minimumPaymentAmount").asText(),
            "Minimum payment amount should be set");

        // Verify plans array is not empty and contains expected plans
        assertTrue(jsonNode.has("plans"), "Response should include plans array");
        var plans = jsonNode.get("plans");
        assertTrue(plans.isArray(), "Plans should be an array");
        assertFalse(plans.isEmpty(), "Plans array should not be empty");
    }

    @Test
    @DisplayName("Test upload shard config with invalid URL returns error")
    void testUploadShardConfigWithInvalidUrl() throws Exception {
        String token = loginAsAdmin();

        String invalidShardConfig = """
            {
                "version": 1,
                "shards": [
                    {
                        "id": 1,
                        "url": "not a valid url at all"
                    }
                ]
            }
            """;

        HttpResponse<String> response = uploadShardConfig(token, invalidShardConfig);

        assertEquals(400, response.statusCode());
        assertEquals("{\"error\":\"Invalid configuration: Shard URL is malformed: 'not a valid url at all'\"}", response.body());
    }

    @Test
    @DisplayName("Test upload shard config with valid URL succeeds")
    void testUploadShardConfigWithValidUrl() throws Exception {
        String token = loginAsAdmin();

        String validShardConfig = """
            {
                "version": 1,
                "shards": [
                    {
                        "id": 1,
                        "url": "http://test123.example.com:8080"
                    }
                ]
            }
            """;

        HttpResponse<String> response = uploadShardConfig(token, validShardConfig);

        assertEquals(200, response.statusCode());
        var jsonNode = objectMapper.readTree(response.body());
        assertTrue(jsonNode.has("message"), "Response should include message");
        assertEquals("Shard configuration uploaded successfully", jsonNode.get("message").asText());

        var config = new ShardConfigRepository().getLatestConfig();
        assertEquals("http://test123.example.com:8080", config.config().getShards().get(0).url());
    }

    private HttpResponse<String> uploadShardConfig(String token, String shardConfigJson) throws Exception {
        String uploadPayload = objectMapper.writeValueAsString(
            new java.util.HashMap<String, String>() {{
                put("configJson", shardConfigJson);
            }}
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/admin/api/shard-config"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(uploadPayload))
            .timeout(Duration.ofSeconds(5))
            .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String loginAsAdmin() throws Exception {
        String loginPayload = objectMapper.writeValueAsString(
            new java.util.HashMap<String, String>() {{
                put("password", config.getAdminPassword());
            }}
        );

        HttpRequest loginRequest = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/admin/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(loginPayload))
            .timeout(Duration.ofSeconds(5))
            .build();

        HttpResponse<String> loginResponse = httpClient.send(loginRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResponse.statusCode(), "Login should succeed");

        var loginJson = objectMapper.readTree(loginResponse.body());
        assertTrue(loginJson.has("token"), "Login response should include token");
        return loginJson.get("token").asText();
    }
}
