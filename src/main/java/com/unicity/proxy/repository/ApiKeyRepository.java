package com.unicity.proxy.repository;

import com.unicity.proxy.CachedApiKeyManager;
import com.unicity.proxy.model.ApiKeyStatus;
import io.github.bucket4j.TimeMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ApiKeyRepository {
    private static final Logger logger = LoggerFactory.getLogger(ApiKeyRepository.class);
    private static final int PAYMENT_VALIDITY_DURATION_DAYS = 30;

    private TimeMeter timeMeter = TimeMeter.SYSTEM_MILLISECONDS;
    
    private static final String FIND_BY_KEY_SQL = """
        SELECT ak.api_key, ak.status, ak.pricing_plan_id, ak.active_until, pp.requests_per_second, pp.requests_per_day
        FROM api_keys ak
        LEFT JOIN pricing_plans pp ON ak.pricing_plan_id = pp.id
        WHERE ak.api_key = ?
        """;
    
    private static final String INSERT_SQL = """
        INSERT INTO api_keys (api_key, pricing_plan_id, status, active_until)
        VALUES (?, ?, ?::api_key_status, CURRENT_TIMESTAMP + INTERVAL '1 day' * ?)
        """;
    
    private static final String DELETE_SQL = "DELETE FROM api_keys WHERE api_key = ?";
    
    private static final String DELETE_BY_PLAN_ID_SQL = "DELETE FROM api_keys WHERE pricing_plan_id = ?";

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

    private static final String CREATE_WITH_DESCRIPTION_SQL = """
        INSERT INTO api_keys (api_key, description, pricing_plan_id, status, active_until)
        VALUES (?, ?, ?, 'active'::api_key_status, CURRENT_TIMESTAMP + INTERVAL '1 day' * ?)
        """;

    public static final String UPDATE_PRICING_PLAN_AND_EXTEND_EXPIRY = """
            UPDATE api_keys
            SET pricing_plan_id = ?,
                active_until = CASE
                    WHEN active_until IS NULL OR active_until < CURRENT_TIMESTAMP
                    THEN CURRENT_TIMESTAMP + INTERVAL '1 day' * ?
                    ELSE active_until + INTERVAL '1 day' * ?
                END
            WHERE api_key = ?
            """;

    public ApiKeyRepository() {
    }

    public void setTimeMeter(TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
    }

    public static int getPaymentValidityDurationDays() {
        return PAYMENT_VALIDITY_DURATION_DAYS;
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
            long currentTime = timeMeter.currentTimeNanos() / 1_000_000;
            if (currentTime > info.activeUntil().getTime()) {
                logger.debug("API key {} has expired", apiKey);
                return Optional.empty();
            }
        }

        return apiKeyInfo;
    }

    public Optional<ApiKeyInfo> findByKeyIfNotRevoked(String apiKey) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FIND_BY_KEY_SQL)) {

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
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public void insert(String apiKey, long pricingPlanId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            stmt.setString(1, apiKey);
            stmt.setLong(2, pricingPlanId);
            stmt.setString(3, ApiKeyStatus.ACTIVE.getValue());
            stmt.setLong(4, PAYMENT_VALIDITY_DURATION_DAYS);

            stmt.executeUpdate();
            logger.info("Saved API key: {} with pricing plan id: {}", apiKey, pricingPlanId);
            CachedApiKeyManager.getInstance().removeCacheEntry(apiKey);
        } catch (SQLException e) {
            logger.error("Error saving API key: " + apiKey, e);
            throw new RuntimeException("Failed to insert API key: " + apiKey, e);
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

    public void updatePricingPlanAndExtendExpiry(String apiKey, long newPricingPlanId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_PRICING_PLAN_AND_EXTEND_EXPIRY)) {

            long daysToAdd = PAYMENT_VALIDITY_DURATION_DAYS;
            stmt.setLong(1, newPricingPlanId);
            stmt.setLong(2, daysToAdd);
            stmt.setLong(3, daysToAdd);
            stmt.setString(4, apiKey);

            int affected = stmt.executeUpdate();

            if (affected > 0) {
                logger.info("Updated pricing plan and extended expiry for API key: {} to plan_id: {} for {} days",
                           apiKey, newPricingPlanId, daysToAdd);
                CachedApiKeyManager.getInstance().removeCacheEntry(apiKey);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error updating pricing plan and expiry for API key: " + apiKey, e);
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
            logger.error("Error creating API key without plan: " + apiKey, e);
            throw new RuntimeException("Failed to create API key", e);
        }
    }

    /**
     * Check if an API key exists (regardless of status or plan)
     */
    public boolean exists(String apiKey) {
        String sql = "SELECT 1 FROM api_keys WHERE api_key = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, apiKey);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error checking API key existence: " + apiKey, e);
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
}