package com.unicity.proxy;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;

public class ProxyServer {
    private static final Logger logger = LoggerFactory.getLogger(ProxyServer.class);
    
    private final ProxyConfig config;
    private final Server server;
    private final RateLimiterManager rateLimiterManager; 
    
    public ProxyServer(ProxyConfig config) {
        this.config = config;

        this.server = new Server(getQueuedThreadPool(config));

        ServerConnector connector = getServerConnector(config);
        server.addConnector(connector);

        // Create handler chain with admin handler first
        RequestHandler requestHandler = new RequestHandler(config);
        this.rateLimiterManager = requestHandler.getRateLimiterManager();
        
        AdminHandler adminHandler = new AdminHandler(
            config.getAdminPassword(),
            requestHandler.getApiKeyManager(),
            this.rateLimiterManager
        );

        // Create a combined handler that tries admin first, then proxy
        Handler.Abstract combinedHandler = new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                // Try admin handler first
                if (adminHandler.handle(request, response, callback)) {
                    return true;
                }
                // Fall back to proxy handler
                return requestHandler.handle(request, response, callback);
            }
        };

        server.setHandler(combinedHandler);

        logger.info("Proxy server configured on port {} targeting {}",
            config.getPort(), config.getTargetUrl());
        logger.info("Admin dashboard available at http://localhost:{}/admin", config.getPort());
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
        logger.info("Proxying all requests to: {}", config.getTargetUrl());
    }
    
    public void stop() throws Exception {
        logger.info("Stopping proxy server...");
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
}