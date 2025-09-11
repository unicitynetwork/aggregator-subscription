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
}