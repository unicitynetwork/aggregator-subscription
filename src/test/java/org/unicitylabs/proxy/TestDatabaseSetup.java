package org.unicitylabs.proxy;

import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.unicitylabs.proxy.repository.DatabaseConfig;
import org.unicitylabs.proxy.repository.PaymentRepository;
import org.unicitylabs.proxy.util.EnvironmentProvider;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TestDatabaseSetup {
    private static PostgreSQLContainer<?> postgres;
    private static DatabaseConfig databaseConfig;
    private static final Set<String> keysToDeleteOnReset = ConcurrentHashMap.newKeySet();

    public static void setupTestDatabase() {
        if (postgres == null || !postgres.isRunning()) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                .withDatabaseName("aggregator_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(false); // Always fresh database for tests

            postgres.start();

            // Initialize database with test container connection details
            databaseConfig = new DatabaseConfig(EnvironmentProvider.SystemEnvironmentProvider.getInstance());
            databaseConfig.initialize(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
            );
        }
    }

    public static DatabaseConfig getDatabaseConfig() {
        if (databaseConfig == null) {
            throw new IllegalStateException("Database not initialized. Call setupTestDatabase() first.");
        }
        return databaseConfig;
    }

    public static void teardownTestDatabase() {
        if (databaseConfig != null) {
            databaseConfig.shutdown();
            databaseConfig = null;
        }
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
    }

    public static void resetDatabase() {
        if (CachedApiKeyManager.hasInstanceSet()) {
            CachedApiKeyManager.getInstance().invalidateCache();
        }
        PaymentRepository paymentRepository = new PaymentRepository(databaseConfig);
        ApiKeyRepository repository = new ApiKeyRepository(databaseConfig);
        for (String apiKey : keysToDeleteOnReset) {
            paymentRepository.deletePaymentSessionsByApiKey(apiKey);
            repository.delete(apiKey);
        }
        keysToDeleteOnReset.clear();
    }
    
    public static void markForDeletionDuringReset(String apiKey) {
        keysToDeleteOnReset.add(apiKey);
    }
}