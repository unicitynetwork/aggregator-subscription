package com.unicity.proxy.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class PricingPlansRepository {
    private static final Logger logger = LoggerFactory.getLogger(PricingPlansRepository.class);
    
    private static final String INSERT_SQL = """
        INSERT INTO pricing_plans (name, requests_per_second, requests_per_day)
        VALUES (?, ?, ?)
        """;
    
    private static final String DELETE_SQL = "DELETE FROM pricing_plans WHERE id = ?";
    
    public int save(String name, int requestsPerSecond, int requestsPerDay) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            
            stmt.setString(1, name);
            stmt.setInt(2, requestsPerSecond);
            stmt.setInt(3, requestsPerDay);
            
            stmt.executeUpdate();
            
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1);
                } else {
                    throw new SQLException("Creating pricing plan failed, no ID obtained.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
    
    public boolean delete(int id) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
            
            stmt.setInt(1, id);
            int affected = stmt.executeUpdate();
            
            return affected > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}