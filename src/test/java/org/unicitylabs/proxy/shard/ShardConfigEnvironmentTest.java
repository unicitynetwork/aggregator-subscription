package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.unicitylabs.proxy.AbstractIntegrationTest;
import org.unicitylabs.proxy.ProxyConfig;
import org.unicitylabs.proxy.ProxyServer;
import org.unicitylabs.proxy.TestDatabaseSetup;
import org.unicitylabs.proxy.repository.ShardConfigRepository;
import org.unicitylabs.proxy.util.EnvironmentProvider;
import org.unicitylabs.proxy.util.TestEnvironmentProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShardConfigEnvironmentTest extends AbstractIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Load shard config from file via environment variable")
    void testShardConfigFromFile() throws Exception {
        // Create a test config file
        Path configFile = tempDir.resolve("shard-config.json");
        String configJson = """
            {
              "version": 1,
              "shards": [
                {"id": 2, "url": "http://localhost:9001"},
                {"id": 3, "url": "http://localhost:9002"}
              ]
            }
            """;
        Files.writeString(configFile, configJson);

        // Set environment variable using test provider
        TestEnvironmentProvider envProvider = new TestEnvironmentProvider();
        envProvider.setEnv(ProxyServer.SHARD_CONFIG_URI, "file://" + configFile.toAbsolutePath());

        // Create and start proxy server
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        setUpConfigForTests(config);
        byte[] serverSecret = new byte[32];

        ProxyServer server = new ProxyServer(config, serverSecret, envProvider, TestDatabaseSetup.getDatabaseConfig(), false);

        // Verify config was saved to database
        ShardConfigRepository repo = new ShardConfigRepository(TestDatabaseSetup.getDatabaseConfig());
        ShardConfigRepository.ShardConfigRecord savedConfig = repo.getLatestConfig();

        assertNotNull(savedConfig);
        assertEquals(1, savedConfig.config().getVersion());
        assertEquals(2, savedConfig.config().getShards().size());
        assertEquals(2, savedConfig.config().getShards().get(0).id());
        assertEquals("http://localhost:9001", savedConfig.config().getShards().get(0).url());
        assertEquals("environment", savedConfig.createdBy());

        server.stop();
    }

    @Test
    @DisplayName("Environment variable with invalid JSON fails fast")
    void testInvalidJsonFailsFast() throws Exception {
        Path configFile = tempDir.resolve("invalid.json");
        Files.writeString(configFile, "{ this is not valid json }");

        TestEnvironmentProvider envProvider = new TestEnvironmentProvider();
        envProvider.setEnv(ProxyServer.SHARD_CONFIG_URI, "file://" + configFile.toAbsolutePath());

        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        setUpConfigForTests(config);
        byte[] serverSecret = new byte[32];

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            new ProxyServer(config, serverSecret, envProvider, TestDatabaseSetup.getDatabaseConfig(), false);
        });

        assertTrue(exception.getMessage().contains("Failed to load shard configuration from " + ProxyServer.SHARD_CONFIG_URI));
    }

    @Test
    @DisplayName("Environment variable with invalid shard config fails fast")
    void testInvalidShardConfigFailsFast() throws Exception {
        // Valid JSON but invalid shard configuration (duplicate IDs)
        Path configFile = tempDir.resolve("invalid-shard.json");
        String configJson = """
            {
              "version": 1,
              "shards": [
                {"id": 1, "url": "http://localhost:9001"},
                {"id": 1, "url": "http://localhost:9002"}
              ]
            }
            """;
        Files.writeString(configFile, configJson);

        TestEnvironmentProvider envProvider = new TestEnvironmentProvider();
        envProvider.setEnv(ProxyServer.SHARD_CONFIG_URI, "file://" + configFile.toAbsolutePath());

        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        setUpConfigForTests(config);
        byte[] serverSecret = new byte[32];

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            new ProxyServer(config, serverSecret, envProvider, TestDatabaseSetup.getDatabaseConfig(), false);
        });

        assertTrue(exception.getMessage().contains("Failed to load shard configuration from " + ProxyServer.SHARD_CONFIG_URI));
    }

    @Test
    @DisplayName("Environment variable with non-existent file fails fast")
    void testNonExistentFileFailsFast() {
        TestEnvironmentProvider envProvider = new TestEnvironmentProvider();
        envProvider.setEnv(ProxyServer.SHARD_CONFIG_URI, "file:///does/not/exist.json");

        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        setUpConfigForTests(config);
        byte[] serverSecret = new byte[32];

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            new ProxyServer(config, serverSecret, envProvider, TestDatabaseSetup.getDatabaseConfig(), false);
        });

        assertTrue(exception.getMessage().contains("Failed to load shard configuration from " + ProxyServer.SHARD_CONFIG_URI));
    }

    @Test
    @DisplayName("Environment variable not set falls back to database")
    void testNoEnvironmentVariableFallsBackToDatabase() throws Exception {
        // Create environment provider without the SHARD_CONFIG_URI set
        TestEnvironmentProvider envProvider = new TestEnvironmentProvider();
        envProvider.removeEnv(ProxyServer.SHARD_CONFIG_URI);

        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        setUpConfigForTests(config);

        insertShardConfig(new ShardConfig(1, List.of(
            new ShardInfo(1, "http://localhost:8001")
        )));

        byte[] serverSecret = new byte[32];

        ProxyServer server = new ProxyServer(config, serverSecret, envProvider, TestDatabaseSetup.getDatabaseConfig(), false);

        assertEquals("http://localhost:8001", server.getShardRouterForTesting().routeByShardId(1).get());

        server.stop();
    }

    @Test
    @DisplayName("Empty environment variable falls back to database")
    void testEmptyEnvironmentVariableFallsBackToDatabase() throws Exception {
        // Set empty environment variable
        TestEnvironmentProvider envProvider = new TestEnvironmentProvider();
        envProvider.setEnv(ProxyServer.SHARD_CONFIG_URI, "");

        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        setUpConfigForTests(config);

        insertShardConfig(new ShardConfig(1, List.of(
            new ShardInfo(1, "http://localhost:8123")
        )));
        byte[] serverSecret = new byte[32];

        ProxyServer server = new ProxyServer(config, serverSecret, envProvider, TestDatabaseSetup.getDatabaseConfig(), false);

        assertEquals("http://localhost:8123", server.getShardRouterForTesting().routeByShardId(1).get());

        server.stop();
    }

    @Test
    @DisplayName("Environment variable overrides existing database config")
    void testEnvironmentVariableOverridesDatabase() throws Exception {
        // Insert old config into database
        ShardConfig oldConfig = new ShardConfig(1, List.of(
            new ShardInfo(1, "http://localhost:8001")
        ));
        insertShardConfig(oldConfig);

        // Create new config file
        Path configFile = tempDir.resolve("new-config.json");
        String configJson = """
            {
              "version": 1,
              "shards": [
                {"id": 2, "url": "http://localhost:9020"},
                {"id": 3, "url": "http://localhost:9021"}
              ]
            }
            """;
        Files.writeString(configFile, configJson);

        TestEnvironmentProvider envProvider = new TestEnvironmentProvider();
        envProvider.setEnv(ProxyServer.SHARD_CONFIG_URI, "file://" + configFile.toAbsolutePath());

        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        setUpConfigForTests(config);
        byte[] serverSecret = new byte[32];

        ProxyServer server = new ProxyServer(config, serverSecret, envProvider, TestDatabaseSetup.getDatabaseConfig(), false);

        // Verify new config was saved as latest
        ShardConfigRepository repo = new ShardConfigRepository(TestDatabaseSetup.getDatabaseConfig());
        ShardConfigRepository.ShardConfigRecord latestConfig = repo.getLatestConfig();

        assertEquals(2, latestConfig.config().getShards().size());
        assertEquals("http://localhost:9020", latestConfig.config().getShards().get(0).url());
        assertEquals("environment", latestConfig.createdBy());

        server.stop();
    }
}
