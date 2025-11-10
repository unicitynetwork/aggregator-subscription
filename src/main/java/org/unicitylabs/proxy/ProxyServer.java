package org.unicitylabs.proxy;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.repository.ShardConfigRepository;
import org.unicitylabs.proxy.shard.DefaultShardRouter;
import org.unicitylabs.proxy.shard.FailsafeShardRouter;
import org.unicitylabs.proxy.shard.ShardConfigValidator;
import org.unicitylabs.proxy.shard.ShardRouter;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProxyServer {
    private static final Logger logger = LoggerFactory.getLogger(ProxyServer.class);

    public static final int SHARD_CONFIG_POLLING_INTERVAL = 2;

    private final ProxyConfig config;
    private final Server server;
    private final RateLimiterManager rateLimiterManager;
    private final PaymentHandler paymentHandler;
    private final RequestHandler requestHandler;
    private final ShardConfigRepository shardConfigRepository;
    private final ScheduledExecutorService configPoller;

    private volatile Timestamp lastConfigTimestamp; 
    
    public ProxyServer(ProxyConfig config, byte[] serverSecret) {
        this.config = config;
        this.shardConfigRepository = new ShardConfigRepository();
        this.configPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shard-config-poller");
            t.setDaemon(true);
            return t;
        });

        this.server = new Server(getQueuedThreadPool(config));

        ServerConnector connector = getServerConnector(config);
        server.addConnector(connector);

        // Load initial shard configuration from database
        logger.info("Loading shard configuration from database");
        ShardRouter shardRouter;
        try {
            ShardConfigRepository.ShardConfigRecord configRecord = shardConfigRepository.getLatestConfig();
            ShardRouter tempRouter = new DefaultShardRouter(configRecord.config());
            ShardConfigValidator.validate(tempRouter, configRecord.config());
            this.lastConfigTimestamp = configRecord.createdAt();
            shardRouter = tempRouter;
            logger.info("Shard configuration loaded and validated successfully (created at: {})", lastConfigTimestamp);
        } catch (Exception e) {
            logger.error("Failed to load or validate shard configuration from database. " +
                "Application will start with failsafe router. " +
                "Admin UI is available to fix the configuration. Error: {}", e.getMessage(), e);
            // Use failsafe router to allow app to start and Admin UI to be accessible
            shardRouter = new FailsafeShardRouter();
            // Set timestamp to epoch so first valid config will be picked up
            this.lastConfigTimestamp = new Timestamp(0);
            logger.warn("SHARD ROUTING IS UNAVAILABLE - Fix configuration via Admin UI at http://localhost:{}/admin",
                config.getPort());
        }

        this.requestHandler = new RequestHandler(config, shardRouter);
        this.rateLimiterManager = requestHandler.getRateLimiterManager();

        AdminHandler adminHandler = new AdminHandler(
            config.getAdminPassword(),
            requestHandler.getApiKeyManager(),
            this.rateLimiterManager,
            config.getMinimumPaymentAmount()
        );

        this.paymentHandler = new PaymentHandler(config, serverSecret, shardRouter);

        Handler.Abstract combinedHandler = new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                // Try payment handler first (for /api/payment/* endpoints)
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

    private void startConfigPolling() {
        configPoller.scheduleAtFixedRate(() -> {
            try {
                ShardConfigRepository.ShardConfigRecord latestRecord = shardConfigRepository.getLatestConfig();

                if (latestRecord.createdAt().after(lastConfigTimestamp)) {
                    logger.info("Detected new shard configuration (created at: {}), reloading...",
                        latestRecord.createdAt());

                    try {
                        ShardRouter newRouter = new DefaultShardRouter(latestRecord.config());
                        ShardConfigValidator.validate(newRouter, latestRecord.config());

                        requestHandler.updateShardRouter(newRouter);
                        paymentHandler.updateShardRouter(newRouter);
                        lastConfigTimestamp = latestRecord.createdAt();

                        logger.info("Shard configuration hot-reloaded successfully with {} targets",
                            newRouter.getAllTargets().size());
                    } catch (Exception e) {
                        logger.error("Failed to load or validate new shard configuration (created at: {}). " +
                            "Keeping current router. Error: {}", latestRecord.createdAt(), e.getMessage());
                        // Don't update lastConfigTimestamp so we'll try again on next poll
                    }
                }
            } catch (Exception e) {
                logger.error("Error polling shard configuration from database", e);
            }
        }, SHARD_CONFIG_POLLING_INTERVAL, SHARD_CONFIG_POLLING_INTERVAL, TimeUnit.SECONDS);

        logger.info("Shard configuration polling started (interval: {} seconds)", SHARD_CONFIG_POLLING_INTERVAL);
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
}