package org.unicitylabs.proxy;

import com.beust.jcommander.JCommander;
import org.unicitylabs.proxy.repository.DatabaseConfig;
import org.unicitylabs.proxy.util.EnvironmentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.sdk.util.HexConverter;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static final String SERVER_SECRET = "SERVER_SECRET";

    public static final String DB_URL = "DB_URL";
    public static final String DB_USER = "DB_USER";
    public static final String DB_PASSWORD = "DB_PASSWORD";

    private final EnvironmentProvider environmentProvider;

    public Main() {
        this(EnvironmentProvider.SystemEnvironmentProvider.getInstance());
    }

    public Main(EnvironmentProvider environmentProvider) {
        this.environmentProvider = environmentProvider;
    }

    public static void main(String[] args) {
        Main main = new Main();
        main.run(args);
    }

    public void run(String[] args) {
        ProxyConfig config = new ProxyConfig(environmentProvider);
        JCommander commander = JCommander.newBuilder()
            .addObject(config)
            .programName("aggregator-subscription")
            .build();

        try {
            commander.parse(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            commander.usage();
            System.exit(1);
        }

        if (config.isHelp()) {
            commander.usage();
            return;
        }

        logger.info("Starting Aggregator Subscription Proxy");
        logger.info("Configuration: {}", config);

        DatabaseConfig databaseConfig = new DatabaseConfig(environmentProvider);
        initializeDatabase(databaseConfig);

        try {
            String serverSecret = environmentProvider.getEnv(SERVER_SECRET);
            if (serverSecret == null || serverSecret.isBlank()) {
               throw new RuntimeException(SERVER_SECRET + " environment variable not set");
            }
            ProxyServer server = new ProxyServer(config, HexConverter.decode(serverSecret), environmentProvider, databaseConfig);
            server.start();

            server.awaitTermination();
        } catch (Exception e) {
            logger.error("Fatal error", e);
            System.exit(1);
        } finally {
            shutdown(databaseConfig);
        }
    }

    private void initializeDatabase(DatabaseConfig databaseConfig) {
        String jdbcUrl = environmentProvider.getEnv(DB_URL);
        String dbUser = environmentProvider.getEnv(DB_USER);
        String dbPassword = environmentProvider.getEnv(DB_PASSWORD);

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:postgresql://localhost:5432/aggregator";
        }
        if (dbUser == null || dbUser.isBlank()) {
            dbUser = "aggregator";
        }
        if (dbPassword == null || dbPassword.isBlank()) {
            dbPassword = "aggregator";
        }

        logger.info("Connecting to database: {}", jdbcUrl);
        databaseConfig.initialize(jdbcUrl, dbUser, dbPassword);
    }

    private void shutdown(DatabaseConfig databaseConfig) {
        logger.info("Shutting down...");
        CachedApiKeyManager.getInstance().shutdown();
        databaseConfig.shutdown();
    }
}