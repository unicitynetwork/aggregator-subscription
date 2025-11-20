package org.unicitylabs.proxy.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.util.EnvironmentProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    private final EnvironmentProvider environmentProvider;
    private HikariDataSource dataSource;

    public DatabaseConfig(EnvironmentProvider environmentProvider) {
        this.environmentProvider = environmentProvider;
    }

    public void initialize(String jdbcUrl, String username, String password) {
        if (dataSource != null) {
            dataSource.close();
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");

        // Connection pool sizing - configurable via environment variables
        config.setMaximumPoolSize(getEnvAsInt("HIKARI_MAX_POOL_SIZE", 50));
        config.setMinimumIdle(getEnvAsInt("HIKARI_MIN_IDLE", 10));

        // Timeout settings
        config.setConnectionTimeout(getEnvAsInt("HIKARI_CONNECTION_TIMEOUT", 30000));
        config.setIdleTimeout(getEnvAsInt("HIKARI_IDLE_TIMEOUT", 600000));
        config.setMaxLifetime(getEnvAsInt("HIKARI_MAX_LIFETIME", 1800000));
        config.setValidationTimeout(getEnvAsInt("HIKARI_VALIDATION_TIMEOUT", 5000));

        // Connection leak detection (0 = disabled)
        int leakDetectionThreshold = getEnvAsInt("HIKARI_LEAK_DETECTION_THRESHOLD", 60000);
        if (leakDetectionThreshold > 0) {
            config.setLeakDetectionThreshold(leakDetectionThreshold);
        }

        // Transaction control
        config.setAutoCommit(true);

        dataSource = new HikariDataSource(config);

        runMigrations();

        logger.info("Database connection pool initialized (max: {}, minIdle: {}, leak detection: {}ms)",
            config.getMaximumPoolSize(), config.getMinimumIdle(),
            leakDetectionThreshold > 0 ? leakDetectionThreshold : "disabled");
    }

    /**
     * Read an integer environment variable with a default value.
     */
    private int getEnvAsInt(String key, int defaultValue) {
        String value = environmentProvider.getEnv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for {}: '{}', using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private void runMigrations() {
        try {
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();

            var result = flyway.migrate();
            logger.info("Database migrations completed. Applied {} migrations", result.migrationsExecuted);
        } catch (Exception e) {
            logger.error("Failed to run database migrations", e);
            throw new RuntimeException("Database migration failed", e);
        }
    }

    public DataSource getDataSource() {
        if (dataSource == null) {
            throw new IllegalStateException("Database not initialized. Call initialize() first.");
        }
        return dataSource;
    }

    public Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Database connection pool closed");
        }
    }

    public boolean isInitialized() {
        return dataSource != null && !dataSource.isClosed();
    }
}