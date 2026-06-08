package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.repository.ApiKeyRepository;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test: reproduce the production symptom where PUT /admin/api/keys/{id}
 * with an {@code activeUntil} field returns 200 OK but the database row appears unchanged.
 *
 * <p>The test inserts a key with a known activeUntil, hits the admin PUT endpoint to
 * extend it by 1 year, then reads the row back via:
 * <ol>
 *   <li>{@link ApiKeyRepository#findByKey(String)} (the same path the admin GET uses)</li>
 *   <li>raw JDBC against {@code api_keys.active_until} (so we can see the bytes on disk)</li>
 * </ol>
 *
 * <p>If (1) shows the old value but (2) shows the new value, the bug is in the read path
 * (cache, projection, etc). If both show the old value, the UPDATE itself isn't landing —
 * either the WHERE didn't match or something is reverting it.
 */
public class AdminUpdateActiveUntilTest extends AbstractIntegrationTest {

    private static final ObjectMapper objectMapper = ObjectMapperUtils.createObjectMapper();

    @Test
    @DisplayName("PUT /admin/api/keys/{id} with activeUntil persists the new value")
    void putUpdatesActiveUntilInDatabase() throws Exception {
        // 1) Insert a key with a known activeUntil (set by ApiKeyRepository.create()
        //    using getExpiryStartingFrom(timeMeter) — the default 30-day expiry).
        ApiKeyRepository repo = new ApiKeyRepository(TestDatabaseSetup.getDatabaseConfig());
        String apiKey = "sk_test_update_active_until_" + System.nanoTime();
        repo.create(apiKey, "diagnostic key", PLAN_STANDARD.id());
        TestDatabaseSetup.markForDeletionDuringReset(apiKey);

        Optional<ApiKeyRepository.ApiKeyDetail> beforeDetail = repo.findByKey(apiKey);
        assertTrue(beforeDetail.isPresent(), "newly created key should be findable");
        Long id = beforeDetail.get().id();
        Timestamp beforeActiveUntil = beforeDetail.get().activeUntil();
        System.out.println("[BEFORE PUT] id=" + id
            + " activeUntil(repo)=" + beforeActiveUntil
            + " activeUntil(rawSql)=" + readActiveUntilRaw(id));

        // 2) PUT /admin/api/keys/{id} with the new activeUntil — 1 year out.
        Instant newActiveUntil = beforeActiveUntil.toInstant().plusSeconds(365L * 24 * 3600);
        String body = objectMapper.writeValueAsString(
            Map.of("activeUntil", newActiveUntil.toString()));

        String token = loginAsAdmin();
        HttpRequest put = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/admin/api/keys/" + id))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method("PUT", HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> putResp = httpClient.send(put, HttpResponse.BodyHandlers.ofString());
        System.out.println("[PUT] status=" + putResp.statusCode() + " body=" + putResp.body());
        assertEquals(200, putResp.statusCode(), "PUT should succeed");

        // 3) Read back via repo (admin GET path) AND via raw SQL.
        Optional<ApiKeyRepository.ApiKeyDetail> afterDetail = repo.findByKey(apiKey);
        assertTrue(afterDetail.isPresent());
        Timestamp afterRepo = afterDetail.get().activeUntil();
        Timestamp afterRaw = readActiveUntilRaw(id);
        System.out.println("[AFTER PUT]  id=" + id
            + " activeUntil(repo)=" + afterRepo
            + " activeUntil(rawSql)=" + afterRaw
            + " expected(Instant)=" + newActiveUntil);

        // 4) Assert: both repo and raw SQL should now show the new value.
        assertNotNull(afterRaw, "raw SQL active_until should be present after the PUT");
        assertNotNull(afterRepo, "repo.findByKey active_until should be present after the PUT");
        assertNotEquals(beforeActiveUntil, afterRaw,
            "raw SQL read should NOT match the pre-PUT value — if it does, the UPDATE didn't land");
        assertEquals(newActiveUntil.toEpochMilli(), afterRaw.toInstant().toEpochMilli(),
            "raw SQL active_until should equal the value we sent");
        assertEquals(newActiveUntil.toEpochMilli(), afterRepo.toInstant().toEpochMilli(),
            "repo.findByKey active_until should equal the value we sent");
    }

    /**
     * Read api_keys.active_until directly via JDBC, bypassing the repository layer entirely.
     */
    private Timestamp readActiveUntilRaw(Long id) throws Exception {
        try (Connection conn = TestDatabaseSetup.getDatabaseConfig().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT active_until FROM api_keys WHERE id = ?")) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "no api_keys row found for id=" + id);
                return rs.getTimestamp("active_until");
            }
        }
    }

    private String loginAsAdmin() throws Exception {
        String loginPayload = objectMapper.writeValueAsString(
            Map.of("password", config.getAdminPassword()));
        HttpRequest login = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/admin/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(loginPayload))
            .timeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> resp = httpClient.send(login, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "admin login should succeed");
        return objectMapper.readTree(resp.body()).get("token").asText();
    }
}
