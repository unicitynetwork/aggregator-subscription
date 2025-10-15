package org.unicitylabs.proxy.repository;

import org.unicitylabs.proxy.CachedApiKeyManager;
import org.unicitylabs.proxy.model.ApiKeyStatus;
import io.github.bucket4j.TimeMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.service.PaymentService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.unicitylabs.proxy.util.TimeUtils.currentTimeMillis;

public class ApiKeyRepository {
    private static final Logger logger = LoggerFactory.getLogger(ApiKeyRepository.class);

    private TimeMeter timeMeter = TimeMeter.SYSTEM_MILLISECONDS;
    
    private static final String FIND_BY_KEY_SQL = """
        SELECT ak.api_key, ak.status, ak.pricing_plan_id, ak.active_until, pp.requests_per_second, pp.requests_per_day
        FROM api_keys ak
        LEFT JOIN pricing_plans pp ON ak.pricing_plan_id = pp.id
        WHERE ak.api_key = ?
        """;
    
    private static final String INSERT_SQL = """
        INSERT INTO api_keys (api_key, pricing_plan_id, status, active_until)
        VALUES (?, ?, ?::api_key_status, ?)
        """;

    private static final String CREATE_WITH_DESCRIPTION_SQL = """
        INSERT INTO api_keys (api_key, description, pricing_plan_id, status, active_until)
        VALUES (?, ?, ?, 'active'::api_key_status, ?)
        """;

    private static final String UPDATE_PLAN_SQL = "UPDATE api_keys SET pricing_plan_id = ? WHERE api_key = ?";

    private static final String FIND_ALL_DETAILED_SQL = """
        SELECT ak.id, ak.api_key, ak.description, ak.status, ak.pricing_plan_id, ak.created_at
        FROM api_keys ak
        ORDER BY ak.created_at DESC
        """;

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM api_keys";

    private static final String COUNT_ACTIVE_SQL = "SELECT COUNT(*) FROM api_keys WHERE status = 'active'";

    private static final String UPDATE_STATUS_SQL = "UPDATE api_keys SET status = ?::api_key_status WHERE id = ?";

    private static final String UPDATE_PLAN_BY_ID_SQL = "UPDATE api_keys SET pricing_plan_id = ? WHERE id = ?";

    private static final String UPDATE_DESCRIPTION_SQL = "UPDATE api_keys SET description = ? WHERE id = ?";

    public static final String UPDATE_PRICING_PLAN_AND_SET_EXPIRY = """
            UPDATE api_keys
            SET pricing_plan_id = ?,
                active_until = ?
            WHERE api_key = ?
            """;

    private static final String DELETE_SQL = "DELETE FROM api_keys WHERE api_key = ?";

    private static final String DELETE_BY_PLAN_ID_SQL = "DELETE FROM api_keys WHERE pricing_plan_id = ?";

    public ApiKeyRepository() {
    }

    public void setTimeMeter(TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
    }

    public Optional<ApiKeyInfo> findByKeyIfNotRevokedAndHasPaid(String apiKey) {
        Optional<ApiKeyInfo> apiKeyInfo = findByKeyIfNotRevoked(apiKey);
        if (apiKeyInfo.isEmpty()) {
            return Optional.empty();
        }

        ApiKeyInfo info = apiKeyInfo.get();
        if (info.pricingPlanId() == null) {
            logger.debug("API key {} has no active pricing plan", apiKey);
            return Optional.empty();
        }

        if (info.activeUntil() != null) {
            long currentTime = currentTimeMillis(timeMeter);
            if (currentTime > info.activeUntil().getTime()) {
                logger.debug("API key {} has expired", apiKey);
                return Optional.empty();
            }
        }

        return apiKeyInfo;
    }

    public Optional<ApiKeyInfo> findByKeyIfNotRevoked(String apiKey) {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return findByKeyIfNotRevoked(conn, apiKey);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<ApiKeyInfo> findByKeyIfNotRevoked(Connection conn, String apiKey) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(FIND_BY_KEY_SQL)) {
            stmt.setString(1, apiKey);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ApiKeyStatus status = ApiKeyStatus.fromValue(rs.getString("status"));
                    if (status == ApiKeyStatus.REVOKED) {
                        logger.debug("API key {} is revoked", apiKey);
                        return Optional.empty();
                    }

                    Object planIdObj = rs.getObject("pricing_plan_id");
                    Long planId = planIdObj == null ? null : ((Number) planIdObj).longValue();
                    Timestamp activeUntil = rs.getTimestamp("active_until");

                    return Optional.of(new ApiKeyInfo(
                            rs.getString("api_key"),
                            rs.getInt("requests_per_second"),
                            rs.getInt("requests_per_day"),
                            planId,
                            activeUntil
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public void insert(String apiKey, long pricingPlanId, Instant activeUntil) {
        try (Connection conn = DatabaseConfig.getConnection()) {
            insert(conn, apiKey, pricingPlanId, activeUntil);
        } catch (SQLException e) {
            logger.error("Error saving API key: {}", apiKey, e);
            throw new RuntimeException("Failed to insert API key: " + apiKey, e);
        }
    }

    public void insert(Connection conn, String apiKey, long pricingPlanId, Instant activeUntil) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            stmt.setString(1, apiKey);
            stmt.setLong(2, pricingPlanId);
            stmt.setString(3, ApiKeyStatus.ACTIVE.getValue());
            stmt.setTimestamp(4, Timestamp.from(activeUntil));

            stmt.executeUpdate();
            logger.info("Saved API key: {} with pricing plan id: {} expiring at {}",
                apiKey, pricingPlanId, activeUntil);
            CachedApiKeyManager.getInstance().removeCacheEntry(apiKey);
        }
    }

    public void delete(String apiKey) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
            
            stmt.setString(1, apiKey);
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.info("Deleted API key: {}", apiKey);
                CachedApiKeyManager.getInstance().removeCacheEntry(apiKey);
            } else {
                throw new RuntimeException("Failed to delete API key: " + apiKey);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error deleting API key: " + apiKey, e);
        }
    }
    
    public void deleteByPricingPlanId(long planId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_PLAN_ID_SQL)) {
            
            stmt.setLong(1, planId);
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("Deleted {} API keys for pricing plan id: {}", affected, planId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error deleting API keys by pricing plan id: " + planId, e);
        }
    }

    public void updatePricingPlan(String apiKey, long newPricingPlanId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_PLAN_SQL)) {

            stmt.setLong(1, newPricingPlanId);
            stmt.setString(2, apiKey);

            int affected = stmt.executeUpdate();

            if (affected > 0) {
                logger.info("Updated pricing plan for API key: {} to plan_id: {}", apiKey, newPricingPlanId);
                CachedApiKeyManager.getInstance().removeCacheEntry(apiKey);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error updating pricing plan for API key: " + apiKey, e);
        }
    }

    /**
     * Update pricing plan and set expiry within an existing transaction
     */
    public void updatePricingPlanAndSetExpiry(Connection conn, String apiKey, long newPricingPlanId,
                                             Instant newExpiry) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(UPDATE_PRICING_PLAN_AND_SET_EXPIRY)) {
            stmt.setLong(1, newPricingPlanId);
            stmt.setTimestamp(2, Timestamp.from(newExpiry));
            stmt.setString(3, apiKey);

            int affected = stmt.executeUpdate();

            if (affected > 0) {
                logger.info("Updated pricing plan and set expiry for API key: {} to plan_id: {} with expiry: {}",
                           apiKey, newPricingPlanId, newExpiry);
                CachedApiKeyManager.getInstance().removeCacheEntry(apiKey);
            }
        }
    }

    public record ApiKeyInfo(String apiKey, int requestsPerSecond, int requestsPerDay, Long pricingPlanId, Timestamp activeUntil) {
    }

    public record ApiKeyDetail(Long id, String apiKey, String description, ApiKeyStatus status, Long pricingPlanId,
                               Timestamp createdAt) {
    }

    public List<ApiKeyDetail> findAll() {
        List<ApiKeyDetail> keys = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FIND_ALL_DETAILED_SQL);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Long pricingPlanId = rs.getObject("pricing_plan_id") != null
                    ? rs.getLong("pricing_plan_id")
                    : null;
                keys.add(new ApiKeyDetail(
                    rs.getLong("id"),
                    rs.getString("api_key"),
                    rs.getString("description"),
                    ApiKeyStatus.fromValue(rs.getString("status")),
                    pricingPlanId,
                    rs.getTimestamp("created_at")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding all API keys", e);
        }
        return keys;
    }

    public void create(String apiKey, String description, Long pricingPlanId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_WITH_DESCRIPTION_SQL)) {

            stmt.setString(1, apiKey);
            stmt.setString(2, description);
            stmt.setLong(3, pricingPlanId);
            stmt.setTimestamp(4, Timestamp.from(PaymentService.getExpiry(timeMeter)));

            stmt.executeUpdate();
            logger.info("Created API key: {} with description: {}", apiKey, description);
            CachedApiKeyManager.getInstance().removeCacheEntry(apiKey);
        } catch (SQLException e) {
            throw new RuntimeException("Error creating API key", e);
        }
    }

    public void updateStatus(Long id, ApiKeyStatus status) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_STATUS_SQL)) {

            stmt.setString(1, status.getValue());
            stmt.setLong(2, id);

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                logger.info("Updated status for API key id: {} to: {}", id, status);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error updating status for API key id: " + id, e);
        }
    }

    public void updatePricingPlan(Long id, Long pricingPlanId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_PLAN_BY_ID_SQL)) {

            stmt.setLong(1, pricingPlanId);
            stmt.setLong(2, id);

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                logger.info("Updated pricing plan for API key id: {} to plan_id: {}", id, pricingPlanId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error updating pricing plan for API key id: " + id, e);
        }
    }

    public long count() {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_SQL);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error counting API keys", e);
        }
        return 0;
    }

    public long countActive() {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_ACTIVE_SQL);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error counting active API keys", e);
        }
        return 0;
    }

    public void updateDescription(Long id, String description) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_DESCRIPTION_SQL)) {
            stmt.setString(1, description);
            stmt.setLong(2, id);
            int affected = stmt.executeUpdate();
            if (affected > 0) {
                logger.info("Updated description for API key id: {}", id);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error updating description for API key id: " + id, e);
        }
    }

    /**
     * Create an API key without a pricing plan.
     * The key will be inactive until a plan is purchased.
     */
    public void createWithoutPlan(String apiKey) {
        String sql = """
            INSERT INTO api_keys (api_key, description, pricing_plan_id, status)
            VALUES (?, '', NULL, 'active'::api_key_status)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, apiKey);

            stmt.executeUpdate();
            logger.info("Created API key without plan: {}", apiKey);
        } catch (SQLException e) {
            logger.error("Error creating API key without plan: {}", apiKey, e);
            throw new RuntimeException("Failed to create API key", e);
        }
    }

    public Optional<ApiKeyDetail> findByKey(String apiKey) {
        String sql = """
            SELECT id, api_key, description, status, pricing_plan_id, created_at
            FROM api_keys
            WHERE api_key = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, apiKey);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Long pricingPlanId = rs.getObject("pricing_plan_id") != null
                        ? rs.getLong("pricing_plan_id")
                        : null;
                    return Optional.of(new ApiKeyDetail(
                        rs.getLong("id"),
                        rs.getString("api_key"),
                        rs.getString("description"),
                        ApiKeyStatus.fromValue(rs.getString("status")),
                        pricingPlanId,
                        rs.getTimestamp("created_at")
                    ));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Error finding API key by key: " + apiKey, e);
        }
    }

    /**
     * Lock the API key row for update within an existing transaction.
     * Uses SELECT FOR UPDATE NOWAIT to fail fast if another transaction holds the lock.
     * <p>
     * This prevents concurrent payment processing for the same API key.
     */
    public void lockForUpdate(Connection conn, String apiKey) throws SQLException {
        String sql = """
            SELECT id, api_key, status
            FROM api_keys
            WHERE api_key = ?
            FOR UPDATE NOWAIT
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, apiKey);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("API key not found: " + apiKey);
                }
                logger.debug("Acquired lock for API key: {}", apiKey);
            }
        } catch (SQLException e) {
            // PostgreSQL error code 55P03 = lock_not_available
            if (e.getSQLState() != null && e.getSQLState().equals("55P03")) {
                logger.warn("Lock contention for API key: {}", apiKey);
                throw new LockConflictException(
                    "Another payment for this API key is currently being processed. Please wait and try again.",
                    e
                );
            }
            throw e;
        }
    }

    /**
     * Exception thrown when a row lock cannot be acquired due to another transaction holding it.
     */
    public static class LockConflictException extends RuntimeException {
        public LockConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}