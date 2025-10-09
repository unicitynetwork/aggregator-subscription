package org.unicitylabs.proxy.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PricingPlanRepository {
    private static final Logger logger = LoggerFactory.getLogger(PricingPlanRepository.class);

    private static final String FIND_ALL_SQL = """
        SELECT id, name, requests_per_second, requests_per_day, price
        FROM pricing_plans
        ORDER BY id
        """;

    private static final String CREATE_SQL = """
        INSERT INTO pricing_plans (name, requests_per_second, requests_per_day, price)
        VALUES (?, ?, ?, ?)
        """;

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM pricing_plans";

    private static final String FIND_BY_ID_SQL = """
        SELECT id, name, requests_per_second, requests_per_day, price
        FROM pricing_plans
        WHERE id = ?
        """;

    private static final String UPDATE_SQL = """
        UPDATE pricing_plans
        SET name = ?, requests_per_second = ?, requests_per_day = ?, price = ?
        WHERE id = ?
        """;

    private static final String DELETE_SQL = "DELETE FROM pricing_plans WHERE id = ?";

    private static final String COUNT_KEYS_USING_PLAN_SQL = """
        SELECT COUNT(*) FROM api_keys
        WHERE pricing_plan_id = ?
        """;

    private static final String COUNT_SESSIONS_USING_PLAN_SQL = """
        SELECT COUNT(*) FROM payment_sessions
        WHERE target_plan_id = ?
        """;

    private static final String INSERT_WITH_ID_SQL = """
        INSERT INTO pricing_plans (id, name, requests_per_second, requests_per_day, price)
        VALUES (?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SEQUENCE_SQL = "SELECT setval('pricing_plans_id_seq', (SELECT MAX(id) FROM pricing_plans))";

    public record PricingPlan(Long id, String name, int requestsPerSecond, int requestsPerDay, BigInteger price) {
    }

    public List<PricingPlan> findAll() {
        List<PricingPlan> plans = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FIND_ALL_SQL);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                plans.add(new PricingPlan(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getInt("requests_per_second"),
                    rs.getInt("requests_per_day"),
                    rs.getBigDecimal("price").toBigInteger()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding all pricing plans", e);
        }
        return plans;
    }

    public long create(String name, int requestsPerSecond, int requestsPerDay, BigInteger price) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_SQL, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, name);
            stmt.setInt(2, requestsPerSecond);
            stmt.setInt(3, requestsPerDay);
            stmt.setBigDecimal(4, new BigDecimal(price));

            stmt.executeUpdate();

            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                } else {
                    throw new SQLException("Creating pricing plan failed, no ID obtained.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
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
            throw new RuntimeException("Error counting pricing plans", e);
        }
        return 0;
    }

    public PricingPlan findById(Long id) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FIND_BY_ID_SQL)) {

            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new PricingPlan(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("requests_per_second"),
                        rs.getInt("requests_per_day"),
                        rs.getBigDecimal("price").toBigInteger()
                    );
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding pricing plan by id: " + id, e);
        }
        return null;
    }

    public void update(Long id, String name, int requestsPerSecond, int requestsPerDay, BigInteger price) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {

            stmt.setString(1, name);
            stmt.setInt(2, requestsPerSecond);
            stmt.setInt(3, requestsPerDay);
            stmt.setBigDecimal(4, new BigDecimal(price));
            stmt.setLong(5, id);

            stmt.executeUpdate();
            logger.info("Updated pricing plan {}: {} ({} req/s, {} req/day, {})",
                id, name, requestsPerSecond, requestsPerDay, price);
        } catch (SQLException e) {
            throw new RuntimeException("Error updating pricing plan", e);
        }
    }

    public int countKeysUsingPlan(long planId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_KEYS_USING_PLAN_SQL)) {

            stmt.setLong(1, planId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error counting API keys using plan: " + planId, e);
        }
        return 0;
    }

    public int countPaymentSessionsUsingPlan(long planId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_SESSIONS_USING_PLAN_SQL)) {

            stmt.setLong(1, planId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error counting payment sessions using plan: " + planId, e);
        }
        return 0;
    }

    public boolean delete(long id) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {

            stmt.setLong(1, id);
            int affected = stmt.executeUpdate();

            return affected > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void insertWithId(PricingPlan plan) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_WITH_ID_SQL)) {

            stmt.setLong(1, plan.id());
            stmt.setString(2, plan.name());
            stmt.setInt(3, plan.requestsPerSecond());
            stmt.setInt(4, plan.requestsPerDay());
            stmt.setBigDecimal(5, new BigDecimal(plan.price()));

            stmt.executeUpdate();
            logger.debug("Inserted pricing plan with ID {}: {}", plan.id(), plan.name());
        } catch (SQLException e) {
            throw new RuntimeException("Error inserting pricing plan with ID: " + plan.id(), e);
        }
    }

    public void updateSequenceToMax() {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SEQUENCE_SQL)) {

            stmt.executeQuery();
            logger.debug("Updated pricing_plans_id_seq to max ID");
        } catch (SQLException e) {
            throw new RuntimeException("Error updating pricing plans sequence", e);
        }
    }
}