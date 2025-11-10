package org.unicitylabs.proxy.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.shard.ShardConfig;

import java.sql.*;

public class ShardConfigRepository {
    private static final Logger logger = LoggerFactory.getLogger(ShardConfigRepository.class);
    private static final ObjectMapper objectMapper = ObjectMapperUtils.createObjectMapper();

    private static final String GET_LATEST_SQL = """
        SELECT config_json, created_at, created_by
        FROM shard_config
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """;

    private static final String INSERT_SQL = """
        INSERT INTO shard_config (config_json, created_by)
        VALUES (?::jsonb, ?)
        """;

    public record ShardConfigRecord(ShardConfig config, Timestamp createdAt, String createdBy) {
    }

    public ShardConfigRecord getLatestConfig() {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_LATEST_SQL);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                String configJson = rs.getString("config_json");
                ShardConfig config = objectMapper.readValue(configJson, ShardConfig.class);

                return new ShardConfigRecord(
                    config,
                    rs.getTimestamp("created_at"),
                    rs.getString("created_by")
                );
            } else {
                throw new RuntimeException("No shard configuration found in database");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error loading shard configuration from database", e);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing shard configuration JSON", e);
        }
    }

    public void saveConfig(ShardConfig config, String createdBy) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            String configJson = objectMapper.writeValueAsString(config);
            stmt.setString(1, configJson);
            stmt.setString(2, createdBy);

            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new RuntimeException("Failed to insert shard configuration");
            }

            logger.info("Saved new shard configuration by: {}", createdBy);
        } catch (SQLException e) {
            throw new RuntimeException("Error saving shard configuration to database", e);
        } catch (Exception e) {
            throw new RuntimeException("Error serializing shard configuration to JSON", e);
        }
    }
}
