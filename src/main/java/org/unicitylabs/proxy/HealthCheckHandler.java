package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.repository.DatabaseConfig;
import org.unicitylabs.proxy.shard.ShardRouter;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class HealthCheckHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckHandler.class);
    private static final ObjectMapper mapper = ObjectMapperUtils.createObjectMapper();
    static final int AGGREGATOR_CHECK_TIMEOUT_SECONDS = 5;

    // Dedicated executor for I/O-bound health check operations to avoid blocking the common ForkJoinPool
    private static final ExecutorService HEALTH_CHECK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "health-check-worker");
        t.setDaemon(true);
        return t;
    });

    private final DatabaseConfig databaseConfig;
    private final Supplier<ShardRouter> shardRouterSupplier;
    private final AggregatorHealthChecker aggregatorHealthChecker;

    /**
     * Functional interface for checking aggregator health.
     * Returns the block height if healthy, throws exception if unreachable.
     */
    @FunctionalInterface
    public interface AggregatorHealthChecker {
        long checkHealth(String url) throws Exception;
    }

    public HealthCheckHandler(DatabaseConfig databaseConfig, Supplier<ShardRouter> shardRouterSupplier) {
        this(databaseConfig, shardRouterSupplier, HealthCheckHandler::defaultAggregatorCheck);
    }

    public HealthCheckHandler(DatabaseConfig databaseConfig, Supplier<ShardRouter> shardRouterSupplier,
                              AggregatorHealthChecker aggregatorHealthChecker) {
        this.databaseConfig = databaseConfig;
        this.shardRouterSupplier = shardRouterSupplier;
        this.aggregatorHealthChecker = aggregatorHealthChecker;
    }

    private static long defaultAggregatorCheck(String url) throws Exception {
        // Note: JsonRpcAggregatorClient from the SDK does not implement AutoCloseable
        // and does not hold persistent resources that require cleanup
        JsonRpcAggregatorClient client = new JsonRpcAggregatorClient(url);
        return client.getBlockHeight().get();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String path = request.getHttpURI().getPath();

        if (!"/health".equals(path)) {
            return false;
        }

        Map<String, Object> components = new LinkedHashMap<>();
        boolean allHealthy = true;

        // Check 1: Database connectivity
        String dbStatus = checkDatabase();
        components.put("database", dbStatus);
        if (!"ok".equals(dbStatus)) {
            allHealthy = false;
        }

        // Check 2: Aggregator connectivity
        Map<String, String> aggregatorStatuses = checkAggregators();
        components.put("aggregators", aggregatorStatuses);
        for (String status : aggregatorStatuses.values()) {
            if (!"ok".equals(status)) {
                allHealthy = false;
                break;
            }
        }

        if (allHealthy) {
            sendHealthy(response, callback, components);
        } else {
            sendUnhealthy(response, callback, components);
        }
        return true;
    }

    private String checkDatabase() {
        try {
            if (!databaseConfig.isInitialized()) {
                return "not initialized";
            }
            try (Connection conn = databaseConfig.getConnection()) {
                if (!conn.isValid(2)) {
                    return "connection invalid";
                }
            }
            return "ok";
        } catch (Exception e) {
            logger.error("Database health check failed", e);
            return "error: " + e.getMessage();
        }
    }

    private Map<String, String> checkAggregators() {
        Map<String, String> statuses = new LinkedHashMap<>();
        ShardRouter router = shardRouterSupplier.get();

        if (router == null) {
            statuses.put("_error", "no router configured");
            return statuses;
        }

        List<String> targets = router.getAllTargets();
        if (targets.isEmpty()) {
            statuses.put("_error", "no aggregator targets configured");
            return statuses;
        }

        // Check all aggregators in parallel with timeout using dedicated I/O executor.
        // Note: This blocks the request thread for up to AGGREGATOR_CHECK_TIMEOUT_SECONDS
        // when aggregators are slow or unreachable.
        List<CompletableFuture<Map.Entry<String, String>>> futures = targets.stream()
            .map(url -> CompletableFuture.supplyAsync(() -> checkSingleAggregator(url), HEALTH_CHECK_EXECUTOR)
                .orTimeout(AGGREGATOR_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    // Distinguish between timeout and other failures
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof TimeoutException) {
                        return Map.entry(url, "timeout");
                    }
                    return Map.entry(url, "error: " + cause.getMessage());
                }))
            .toList();

        for (CompletableFuture<Map.Entry<String, String>> future : futures) {
            Map.Entry<String, String> entry = future.join();
            statuses.put(entry.getKey(), entry.getValue());
        }

        return statuses;
    }

    private Map.Entry<String, String> checkSingleAggregator(String url) {
        try {
            long blockHeight = aggregatorHealthChecker.checkHealth(url);
            logger.debug("Aggregator {} is healthy (block height: {})", url, blockHeight);
            return Map.entry(url, "ok");
        } catch (Exception e) {
            logger.warn("Aggregator {} health check failed: {}", url, e.getMessage());
            return Map.entry(url, "unreachable: " + e.getMessage());
        }
    }

    private void sendHealthy(Response response, Callback callback, Map<String, Object> components) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "healthy");
            result.putAll(components);
            String json = mapper.writeValueAsString(result);
            response.setStatus(HttpStatus.OK_200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
            response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
        } catch (Exception e) {
            logger.error("Failed to serialize health response", e);
            callback.failed(e);
        }
    }

    private void sendUnhealthy(Response response, Callback callback, Map<String, Object> components) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "unhealthy");
            result.putAll(components);
            String json = mapper.writeValueAsString(result);
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
            response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
        } catch (Exception e) {
            logger.error("Failed to serialize unhealthy response", e);
            callback.failed(e);
        }
    }
}
