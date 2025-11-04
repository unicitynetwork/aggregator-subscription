package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.model.ObjectMapperUtils;

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
