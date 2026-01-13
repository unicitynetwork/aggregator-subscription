package org.unicitylabs.proxy;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.repository.ShardConfigRepository;
import org.unicitylabs.proxy.shard.*;
import org.unicitylabs.proxy.util.EnvironmentProvider;
import org.unicitylabs.proxy.util.ResourceLoader;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProxyServer {
    public static final String SHARD_CONFIG_URI = "SHARD_CONFIG_URI";

    public static final int SHARD_CONFIG_POLLING_INTERVAL_SECONDS = 2;

    private static final Logger logger = LoggerFactory.getLogger(ProxyServer.class);

    private final ProxyConfig config;
    private final Server server;
    private final RateLimiterManager rateLimiterManager;
    private final PaymentHandler paymentHandler;
    private final RequestHandler requestHandler;
    private final ShardConfigRepository shardConfigRepository;
    private final ScheduledExecutorService configPoller;
    private final boolean validateShardConnectivity;

    private volatile int lastConfigId;

    public ProxyServer(ProxyConfig config, byte[] serverSecret, EnvironmentProvider environmentProvider, org.unicitylabs.proxy.repository.DatabaseConfig databaseConfig) {
        this(config, serverSecret, environmentProvider, databaseConfig, true);
    }

    public ProxyServer(ProxyConfig config, byte[] serverSecret, EnvironmentProvider environmentProvider, org.unicitylabs.proxy.repository.DatabaseConfig databaseConfig, boolean validateShardConnectivity) {
        this.validateShardConnectivity = validateShardConnectivity;

        CachedApiKeyManager.initialize(databaseConfig);

        this.config = config;
        this.shardConfigRepository = new ShardConfigRepository(databaseConfig);
        this.configPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shard-config-poller");
            t.setDaemon(true);
            return t;
        });

        this.server = new Server(getQueuedThreadPool(config));

        ServerConnector connector = getServerConnector(config);
        server.addConnector(connector);

        ShardRouter shardRouter = loadInitialShardConfiguration(config, environmentProvider);

        this.requestHandler = new RequestHandler(config, shardRouter, databaseConfig);
        this.rateLimiterManager = requestHandler.getRateLimiterManager();

        AdminHandler adminHandler = new AdminHandler(
            config.getAdminPassword(),
            requestHandler.getApiKeyManager(),
            this.rateLimiterManager,
            config.getMinimumPaymentAmount(),
            databaseConfig,
            validateShardConnectivity
        );

        this.paymentHandler = new PaymentHandler(config, serverSecret, shardRouter, databaseConfig);

        // Create health check handler with dynamic access to the current shard router via supplier
        HealthCheckHandler healthCheckHandler = new HealthCheckHandler(databaseConfig, requestHandler::getShardRouter);

        // Create config handler for public config endpoints
        ConfigHandler configHandler = new ConfigHandler(shardConfigRepository);

        // Create a combined handler that tries handlers in order: health, config, payment, admin, then proxy
        Handler.Abstract combinedHandler = new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                // Try health check first (for /health endpoint)
                if (healthCheckHandler.handle(request, response, callback)) {
                    return true;
                }
                // Try config handler (for /config/* endpoints)
                if (configHandler.handle(request, response, callback)) {
                    return true;
                }
                // Try payment handler (for /api/payment/* endpoints)
                if (paymentHandler.handle(request, response, callback)) {
                    return true;
                }
                // Try admin handler next
                if (adminHandler.handle(request, response, callback)) {
                    return true;
                }
                // Fall back to proxy handler
                return requestHandler.handle(request, response, callback);
            }
        };

        server.setHandler(combinedHandler);

        if (shardRouter instanceof FailsafeShardRouter) {
            logger.warn("Proxy server configured on port {} with FAILSAFE ROUTER (no shard routing available)",
                config.getPort());
        } else {
            logger.info("Proxy server configured on port {} with {} shard targets",
                config.getPort(), shardRouter.getAllTargets().size());
        }
        logger.info("Admin dashboard available at http://localhost:{}/admin", config.getPort());

        // Start polling for config changes
        startConfigPolling();
    }

    private ShardRouter loadInitialShardConfiguration(ProxyConfig config, EnvironmentProvider environmentProvider) {
        String configUri = environmentProvider.getEnv(SHARD_CONFIG_URI);

        if (configUri != null && !configUri.trim().isBlank()) {
            return loadShardConfigFromUri(configUri.trim());
        } else {
            return loadShardConfigFromDatabase(config);
        }
    }

    private ShardRouter loadShardConfigFromDatabase(ProxyConfig config) {
        ShardRouter shardRouter;
        logger.info("Loading shard configuration from database");
        try {
            ShardConfigRepository.ShardConfigRecord configRecord = shardConfigRepository.getLatestConfig();
            ShardRouter tempRouter = new DefaultShardRouter(configRecord.config());
            ShardConfigValidator.validate(tempRouter, configRecord.config(), validateShardConnectivity);
            this.lastConfigId = configRecord.id();
            shardRouter = tempRouter;
            logger.info("Shard configuration loaded and validated successfully (id: {}, created at: {})",
                configRecord.id(), configRecord.createdAt());
        } catch (Exception e) {
            logger.error("Failed to load or validate shard configuration from database. " +
                "Application will start with failsafe router. " +
                "Admin UI is available to fix the configuration. Error: {}", e.getMessage(), e);
            // Use failsafe router to allow app to start and Admin UI to be accessible
            shardRouter = new FailsafeShardRouter();
            this.lastConfigId = -1;
            logger.warn("SHARD ROUTING IS UNAVAILABLE - Fix configuration via Admin UI at http://localhost:{}/admin",
                config.getPort());
        }
        return shardRouter;
    }

    private ShardRouter loadShardConfigFromUri(String configUri) {
        ShardRouter shardRouter;
        logger.info("Loading shard configuration from environment variable: {}", SHARD_CONFIG_URI);
        try {
            String jsonContent = ResourceLoader.loadContent(configUri);
            ShardConfig shardConfig = shardConfigRepository.parseShardConfig(jsonContent);
            ShardRouter tempRouter = new DefaultShardRouter(shardConfig);
            ShardConfigValidator.validate(tempRouter, shardConfig, validateShardConnectivity);

            // Save to database as new config
            int insertedId = shardConfigRepository.saveConfig(shardConfig, "environment");
            this.lastConfigId = insertedId;
            shardRouter = tempRouter;

            logger.info("Shard configuration loaded from {} and saved to database (id: {})", configUri, insertedId);
        } catch (Exception e) {
            logger.error("FATAL: Failed to load or validate shard configuration from environment variable {}. " +
                "Failing fast. Error: {}", SHARD_CONFIG_URI, e.getMessage(), e);
            throw new RuntimeException("Failed to load shard configuration from " + SHARD_CONFIG_URI, e);
        }
        return shardRouter;
    }

    private void startConfigPolling() {
        configPoller.scheduleAtFixedRate(() -> {
            try {
                ShardConfigRepository.ShardConfigRecord latestRecord = shardConfigRepository.getLatestConfig();

                if (latestRecord.id() > lastConfigId) {
                    logger.info("Detected new shard configuration (id: {}, created at: {}), reloading...",
                        latestRecord.id(), latestRecord.createdAt());

                    try {
                        ShardRouter newRouter = new DefaultShardRouter(latestRecord.config());
                        ShardConfigValidator.validate(newRouter, latestRecord.config(), validateShardConnectivity);

                        requestHandler.updateShardRouter(newRouter);
                        paymentHandler.updateShardRouter(newRouter);
                        lastConfigId = latestRecord.id();

                        logger.info("Shard configuration hot-reloaded successfully (id: {}) with {} targets",
                            latestRecord.id(), newRouter.getAllTargets().size());
                    } catch (Exception e) {
                        logger.error("Failed to load or validate new shard configuration (id: {}, created at: {}). " +
                            "Keeping current router. Error: {}", latestRecord.id(), latestRecord.createdAt(), e.getMessage());
                        // Don't update lastConfigId so we'll try again on next poll
                    }
                }
            } catch (Exception e) {
                logger.error("Error polling shard configuration from database", e);
            }
        }, SHARD_CONFIG_POLLING_INTERVAL_SECONDS, SHARD_CONFIG_POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);

        logger.info("Shard configuration polling started (interval: {} seconds)", SHARD_CONFIG_POLLING_INTERVAL_SECONDS);
    }

    private static QueuedThreadPool getQueuedThreadPool(ProxyConfig config) {
        QueuedThreadPool result = new QueuedThreadPool();

        if (config.isVirtualThreads()) {
            result.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
            result.setMaxThreads(200); // Jetty still needs some platform threads for I/O
            result.setMinThreads(10);
            logger.info("Using virtual threads for request handling");
        } else {
            // Use traditional thread pool - allocate 60% for server, 40% for client (in RequestHandler)
            int serverThreads = Math.max(2, (int)(config.getWorkerThreads() * 0.6));
            result.setMaxThreads(serverThreads);
            result.setMinThreads(Math.min(10, serverThreads));
            logger.info("Using traditional thread pool with {} threads for server (from {} total configured)",
                serverThreads, config.getWorkerThreads());
        }
        result.setName("jetty");
        return result;
    }

    private ServerConnector getServerConnector(ProxyConfig config) {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);

        HttpConnectionFactory httpFactory = new HttpConnectionFactory(httpConfig);

        ServerConnector connector = new ServerConnector(server, httpFactory);
        connector.setPort(config.getPort());
        connector.setHost("0.0.0.0");
        connector.setIdleTimeout(config.getIdleTimeout());
        return connector;
    }

    public void start() throws Exception {
        logger.info("Starting proxy server...");
        server.start();
        logger.info("Proxy server started successfully on port {}", config.getPort());
    }
    
    public void stop() throws Exception {
        logger.info("Stopping proxy server...");

        // Stop config polling
        configPoller.shutdown();
        try {
            if (!configPoller.awaitTermination(5, TimeUnit.SECONDS)) {
                configPoller.shutdownNow();
            }
        } catch (InterruptedException e) {
            configPoller.shutdownNow();
            Thread.currentThread().interrupt();
        }

        server.stop();
        logger.info("Proxy server stopped");
    }
    
    public void awaitTermination() throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received");
            try {
                stop();
            } catch (Exception e) {
                logger.error("Error during shutdown", e);
            }
        }));
        
        server.join();
    }

    Server getServer() {
        return server;
    }

    public RateLimiterManager getRateLimiterManager() {
        return rateLimiterManager;
    }

    public PaymentHandler getPaymentHandler() {
        return paymentHandler;
    }

    public ShardRouter getShardRouter() {
        return this.requestHandler.getShardRouter();
    }
}