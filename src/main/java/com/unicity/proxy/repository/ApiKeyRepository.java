package com.unicity.proxy.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ApiKeyRepository {
    private static final Logger logger = LoggerFactory.getLogger(ApiKeyRepository.class);
    
    private static final String FIND_BY_KEY_SQL = """
        SELECT ak.api_key, ak.status, pp.requests_per_second, pp.requests_per_day
        FROM api_keys ak
        JOIN pricing_plans pp ON ak.pricing_plan_id = pp.id
        WHERE ak.api_key = ?
        """;
    
    private static final String FIND_ALL_SQL = """
        SELECT ak.api_key, ak.status, pp.requests_per_second, pp.requests_per_day
        FROM api_keys ak
        JOIN pricing_plans pp ON ak.pricing_plan_id = pp.id
        """;
    
    private static final String INSERT_SQL = """
        INSERT INTO api_keys (api_key, pricing_plan_id, status)
        VALUES (?, ?, ?::api_key_status)
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

    private static final String DELETE_BY_ID_SQL = "DELETE FROM api_keys WHERE id = ?";

    private static final String CREATE_WITH_DESCRIPTION_SQL = """
        INSERT INTO api_keys (api_key, description, pricing_plan_id, status)
        VALUES (?, ?, ?, 'active'::api_key_status)
        """;

    public Optional<ApiKeyInfo> findByKeyIfActive(String apiKey) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FIND_BY_KEY_SQL)) {
            
            stmt.setString(1, apiKey);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String status = rs.getString("status");
                    if ("revoked".equals(status)) {
                        logger.debug("API key {} is revoked", apiKey);
                        return Optional.empty();
                    }
                    
                    return Optional.of(new ApiKeyInfo(
                        rs.getString("api_key"),
                        rs.getInt("requests_per_second"),
                        rs.getInt("requests_per_day")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding API key: " + apiKey, e);
        }
        return Optional.empty();
    }
    
    public void save(String apiKey, int pricingPlanId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            
            stmt.setString(1, apiKey);
            stmt.setInt(2, pricingPlanId);
            stmt.setString(3, "active");
            
            stmt.executeUpdate();
            logger.info("Saved API key: {} with pricing plan id: {}", apiKey, pricingPlanId);
        } catch (SQLException e) {
            logger.error("Error saving API key: " + apiKey, e);
        }
    }
    
    public void delete(String apiKey) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
            
            stmt.setString(1, apiKey);
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.info("Deleted API key: {}", apiKey);
            }
        } catch (SQLException e) {
            logger.error("Error deleting API key: {}", apiKey, e);
        }
    }
    
    public void deleteByPricingPlanId(int planId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_PLAN_ID_SQL)) {
            
            stmt.setInt(1, planId);
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("Deleted {} API keys for pricing plan id: {}", affected, planId);
            }
        } catch (SQLException e) {
            logger.error("Error deleting API keys for pricing plan id: " + planId, e);
        }
    }

    public void updatePricingPlan(String apiKey, int newPricingPlanId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_PLAN_SQL)) {

            stmt.setInt(1, newPricingPlanId);
            stmt.setString(2, apiKey);

            int affected = stmt.executeUpdate();

            if (affected > 0) {
                logger.info("Updated pricing plan for API key: {} to plan_id: {}", apiKey, newPricingPlanId);
            }
        } catch (SQLException e) {
            logger.error("Error updating pricing plan for API key: {}", apiKey, e);
        }
    }

    public record ApiKeyInfo(String apiKey, int requestsPerSecond, int requestsPerDay) {
    }

    public static class ApiKeyDetail {
        private final Long id;
        private final String apiKey;
        private final String description;
        private final String status;
        private final Long pricingPlanId;
        private final java.sql.Timestamp createdAt;

        public ApiKeyDetail(Long id, String apiKey, String description, String status,
                          Long pricingPlanId, java.sql.Timestamp createdAt) {
            this.id = id;
            this.apiKey = apiKey;
            this.description = description;
            this.status = status;
            this.pricingPlanId = pricingPlanId;
            this.createdAt = createdAt;
        }

        public Long getId() { return id; }
        public String getApiKey() { return apiKey; }
        public String getDescription() { return description; }
        public String getStatus() { return status; }
        public Long getPricingPlanId() { return pricingPlanId; }
        public java.sql.Timestamp getCreatedAt() { return createdAt; }
    }

    public List<ApiKeyDetail> findAll() {
        List<ApiKeyDetail> keys = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FIND_ALL_DETAILED_SQL);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                keys.add(new ApiKeyDetail(
                    rs.getLong("id"),
                    rs.getString("api_key"),
                    rs.getString("description"),
                    rs.getString("status"),
                    rs.getLong("pricing_plan_id"),
                    rs.getTimestamp("created_at")
                ));
            }
        } catch (SQLException e) {
            logger.error("Error finding all API keys", e);
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
        } catch (SQLException e) {
            logger.error("Error creating API key", e);
        }
    }

    public void delete(Long id) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_ID_SQL)) {

            stmt.setLong(1, id);
            int affected = stmt.executeUpdate();

            if (affected > 0) {
                logger.info("Deleted API key with id: {}", id);
            }
        } catch (SQLException e) {
            logger.error("Error deleting API key with id: {}", id, e);
        }
    }

    public void updateStatus(Long id, String status) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_STATUS_SQL)) {

            stmt.setString(1, status);
            stmt.setLong(2, id);

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                logger.info("Updated status for API key id: {} to: {}", id, status);
            }
        } catch (SQLException e) {
            logger.error("Error updating status for API key id: {}", id, e);
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
            logger.error("Error updating pricing plan for API key id: {}", id, e);
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
            logger.error("Error counting API keys", e);
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
            logger.error("Error counting active API keys", e);
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
            logger.error("Error updating description for API key id: {}", id, e);
        }
    }
}