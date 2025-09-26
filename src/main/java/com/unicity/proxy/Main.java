package com.unicity.proxy;

import com.beust.jcommander.JCommander;
import com.unicity.proxy.repository.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.sdk.util.HexConverter;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static final String SERVER_SECRET = "SERVER_SECRET";

    public static void main(String[] args) {
        ProxyConfig config = new ProxyConfig();
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
        
        initializeDatabase(config);
        
        try {
            String serverSecret = System.getenv(SERVER_SECRET);
            if (serverSecret == null || serverSecret.isBlank()) {
               throw new RuntimeException(SERVER_SECRET + " environment variable not set");
            }
            ProxyServer server = new ProxyServer(config, HexConverter.decode(serverSecret));
            server.start();
            
            server.awaitTermination();
        } catch (Exception e) {
            logger.error("Fatal error", e);
            System.exit(1);
        } finally {
            shutdown();
        }
    }

    private static void initializeDatabase(ProxyConfig config) {
        String jdbcUrl = System.getenv("DB_URL");
        String dbUser = System.getenv("DB_USER");
        String dbPassword = System.getenv("DB_PASSWORD");
        
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            jdbcUrl = "jdbc:postgresql://localhost:5432/aggregator";
        }
        if (dbUser == null || dbUser.isEmpty()) {
            dbUser = "aggregator";
        }
        if (dbPassword == null || dbPassword.isEmpty()) {
            dbPassword = "aggregator";
        }
        
        logger.info("Connecting to database: {}", jdbcUrl);
        DatabaseConfig.initialize(jdbcUrl, dbUser, dbPassword);
    }
    
    private static void shutdown() {
        logger.info("Shutting down...");
        CachedApiKeyManager.getInstance().shutdown();
        DatabaseConfig.shutdown();
    }
}