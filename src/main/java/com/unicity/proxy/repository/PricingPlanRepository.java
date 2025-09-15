package com.unicity.proxy.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        INSERT INTO pricing_plans (name, requests_per_second, requests_per_day)
        VALUES (?, ?, ?)
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

    public static class PricingPlan {
        private final Long id;
        private final String name;
        private final int requestsPerSecond;
        private final int requestsPerDay;
        private final double price;

        public PricingPlan(Long id, String name, int requestsPerSecond, int requestsPerDay, double price) {
            this.id = id;
            this.name = name;
            this.requestsPerSecond = requestsPerSecond;
            this.requestsPerDay = requestsPerDay;
            this.price = price;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public int getRequestsPerSecond() { return requestsPerSecond; }
        public int getRequestsPerDay() { return requestsPerDay; }
        public double getPrice() { return price; }
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
                    rs.getDouble("price")
                ));
            }
        } catch (SQLException e) {
            logger.error("Error finding all pricing plans", e);
        }
        return plans;
    }

    public void create(String name, int requestsPerSecond, int requestsPerDay, double price) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_SQL)) {

            stmt.setString(1, name);
            stmt.setInt(2, requestsPerSecond);
            stmt.setInt(3, requestsPerDay);

            stmt.executeUpdate();
            logger.info("Created pricing plan: {} ({} req/s, {} req/day)",
                name, requestsPerSecond, requestsPerDay);
        } catch (SQLException e) {
            logger.error("Error creating pricing plan", e);
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
            logger.error("Error counting pricing plans", e);
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
                        rs.getDouble("price")
                    );
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding pricing plan by id: " + id, e);
        }
        return null;
    }

    public void update(Long id, String name, int requestsPerSecond, int requestsPerDay, double price) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {

            stmt.setString(1, name);
            stmt.setInt(2, requestsPerSecond);
            stmt.setInt(3, requestsPerDay);
            stmt.setDouble(4, price);
            stmt.setLong(5, id);

            stmt.executeUpdate();
            logger.info("Updated pricing plan {}: {} ({} req/s, {} req/day, ${:.2f})",
                id, name, requestsPerSecond, requestsPerDay, price);
        } catch (SQLException e) {
            logger.error("Error updating pricing plan", e);
        }
    }

    public void delete(Long id) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {

            stmt.setLong(1, id);
            stmt.executeUpdate();
            logger.info("Deleted pricing plan with id: {}", id);
        } catch (SQLException e) {
            logger.error("Error deleting pricing plan", e);
        }
    }
}