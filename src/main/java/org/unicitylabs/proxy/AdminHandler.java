package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.unicitylabs.proxy.model.ApiKeyStatus;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.unicitylabs.proxy.repository.PricingPlanRepository;
import org.unicitylabs.proxy.repository.ShardConfigRepository;
import org.unicitylabs.proxy.service.ApiKeyService;
import org.unicitylabs.proxy.shard.DefaultShardRouter;
import org.unicitylabs.proxy.shard.ShardConfig;
import org.unicitylabs.proxy.shard.ShardConfigValidator;
import org.unicitylabs.proxy.shard.ShardRouter;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AdminHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(AdminHandler.class);
    private static final ObjectMapper mapper = ObjectMapperUtils.createObjectMapper();

    private final String adminPassword;
    private final ApiKeyRepository apiKeyRepository;
    private final PricingPlanRepository pricingPlanRepository;
    private final ShardConfigRepository shardConfigRepository;
    private final CachedApiKeyManager apiKeyManager;
    private final RateLimiterManager rateLimiterManager;
    private final BigInteger minimumPaymentAmount;

    // Simple session management
    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private static final long SESSION_DURATION_HOURS = 24;

    private record SessionInfo(String token, Instant expiresAt) {
        boolean isExpired() {
                return Instant.now().isAfter(expiresAt);
            }
    }

    public AdminHandler(String adminPassword, CachedApiKeyManager apiKeyManager, RateLimiterManager rateLimiterManager, BigInteger minimumPaymentAmount) {
        this.adminPassword = adminPassword;
        this.apiKeyRepository = new ApiKeyRepository();
        this.pricingPlanRepository = new PricingPlanRepository();
        this.shardConfigRepository = new ShardConfigRepository();
        this.apiKeyManager = apiKeyManager;
        this.rateLimiterManager = rateLimiterManager;
        this.minimumPaymentAmount = minimumPaymentAmount;
        logger.info("Admin handler initialized");
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String path = request.getHttpURI().getPath();
        String method = request.getMethod();

        // Handle admin routes
        if (!path.startsWith("/admin")) {
            return false;
        }

        // Serve static admin dashboard
        if ("/admin".equals(path) || "/admin/".equals(path)) {
            serveAdminDashboard(response, callback);
            return true;
        }

        // Handle login
        if ("/admin/login".equals(path) && "POST".equals(method)) {
            handleLogin(request, response, callback);
            return true;
        }

        // Check authentication for API endpoints
        if (path.startsWith("/admin/api/")) {
            String token = extractToken(request);
            if (!isValidSession(token)) {
                sendUnauthorized(response, callback);
                return true;
            }

            // Clean expired sessions periodically
            cleanExpiredSessions();

            // Route to appropriate handler
            if ("/admin/api/keys".equals(path)) {
                if ("GET".equals(method)) {
                    handleGetApiKeys(response, callback);
                } else if ("POST".equals(method)) {
                    handleCreateApiKey(request, response, callback);
                }
            } else if (path.startsWith("/admin/api/keys/") && "PUT".equals(method)) {
                String keyId = path.substring("/admin/api/keys/".length());
                handleUpdateApiKey(keyId, request, response, callback);
            } else if ("/admin/api/plans".equals(path) && "GET".equals(method)) {
                handleGetPricingPlans(response, callback);
            } else if ("/admin/api/plans".equals(path) && "POST".equals(method)) {
                handleCreatePricingPlan(request, response, callback);
            } else if (path.startsWith("/admin/api/plans/") && "PUT".equals(method)) {
                String planId = path.substring("/admin/api/plans/".length());
                handleUpdatePricingPlan(planId, request, response, callback);
            } else if (path.startsWith("/admin/api/plans/") && "DELETE".equals(method)) {
                String planId = path.substring("/admin/api/plans/".length());
                handleDeletePricingPlan(planId, response, callback);
            } else if ("/admin/api/stats".equals(path) && "GET".equals(method)) {
                handleGetStats(response, callback);
            } else if ("/admin/api/utilization".equals(path) && "GET".equals(method)) {
                handleGetUtilization(response, callback);
            } else if ("/admin/api/shard-config".equals(path) && "GET".equals(method)) {
                handleGetShardConfig(response, callback);
            } else if ("/admin/api/shard-config".equals(path) && "POST".equals(method)) {
                handleUploadShardConfig(request, response, callback);
            } else {
                sendNotFound(response, callback);
            }
            return true;
        }

        return false;
    }

    private void serveAdminDashboard(Response response, Callback callback) {
        try {
            byte[] content;
            try (InputStream dashboardStream = getClass().getResourceAsStream("/admin/dashboard.html")) {
                if (dashboardStream == null) {
                    sendNotFound(response, callback);
                    return;
                }

                content = dashboardStream.readAllBytes();
            }
            response.setStatus(HttpStatus.OK_200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_HTML.asString());
            response.write(true, ByteBuffer.wrap(content), callback);
        } catch (IOException e) {
            logger.error("Failed to serve admin dashboard", e);
            sendServerError(response, callback);
        }
    }

    private void handleLogin(Request request, Response response, Callback callback) {
        try {
            String body = Content.Source.asString(request);
            ObjectNode loginRequest = (ObjectNode) mapper.readTree(body);
            String password = loginRequest.get("password").asText();

            if (adminPassword.equals(password)) {
                // Generate session token
                String token = generateSessionToken();
                SessionInfo session = new SessionInfo(token, Instant.now().plus(SESSION_DURATION_HOURS, ChronoUnit.HOURS));
                sessions.put(token, session);

                ObjectNode responseJson = mapper.createObjectNode();
                responseJson.put("token", token);
                responseJson.put("expiresAt", session.expiresAt.toString());

                sendJsonResponse(response, callback, responseJson.toString(), HttpStatus.OK_200);
            } else {
                sendUnauthorized(response, callback);
            }
        } catch (Exception e) {
            logger.error("Login failed", e);
            sendServerError(response, callback);
        }
    }

    private void handleGetApiKeys(Response response, Callback callback) {
        try {
            var keys = apiKeyRepository.findAll();
            ArrayNode keysArray = mapper.createArrayNode();

            for (var key : keys) {
                ObjectNode keyNode = mapper.createObjectNode();
                keyNode.put("id", key.id());
                keyNode.put("apiKey", key.apiKey());
                keyNode.put("description", key.description());
                keyNode.put("status", key.status().getValue());
                keyNode.put("pricingPlanId", key.pricingPlanId());
                keyNode.put("createdAt", key.createdAt().toString());
                if (key.activeUntil() != null) {
                    keyNode.put("activeUntil", key.activeUntil().toString());
                }

                // Add current rate limit info
                var apiKeyInfo = apiKeyManager.getApiKeyInfo(key.apiKey());
                if (apiKeyInfo != null) {
                    keyNode.put("requestsPerSecond", apiKeyInfo.requestsPerSecond());
                    keyNode.put("requestsPerDay", apiKeyInfo.requestsPerDay());
                }

                // Add plan name
                var plan = pricingPlanRepository.findById(key.pricingPlanId());
                if (plan != null) {
                    keyNode.put("planName", plan.name());
                }

                keysArray.add(keyNode);
            }

            sendJsonResponse(response, callback, keysArray.toString(), HttpStatus.OK_200);
        } catch (Exception e) {
            logger.error("Failed to get API keys", e);
            sendServerError(response, callback);
        }
    }

    private void handleCreateApiKey(Request request, Response response, Callback callback) {
        try {
            String body = Content.Source.asString(request);
            ObjectNode keyRequest = (ObjectNode) mapper.readTree(body);

            String description = keyRequest.has("description") ? keyRequest.get("description").asText() : "";
            Long planId = keyRequest.has("pricingPlanId") ? keyRequest.get("pricingPlanId").asLong() : 1L;

            // Generate new API key
            String newApiKey = ApiKeyService.generateApiKey();

            // Save to database
            apiKeyRepository.create(newApiKey, description, planId);

            // Clear cache to force reload
            apiKeyManager.removeCacheEntry(newApiKey);

            ObjectNode responseJson = mapper.createObjectNode();
            responseJson.put("apiKey", newApiKey);
            responseJson.put("message", "API key created successfully");

            sendJsonResponse(response, callback, responseJson.toString(), HttpStatus.CREATED_201);
        } catch (Exception e) {
            logger.error("Failed to create API key", e);
            sendServerError(response, callback);
        }
    }


    private void handleUpdateApiKey(String keyId, Request request, Response response, Callback callback) {
        try {
            Long id = Long.parseLong(keyId);
            String body = Content.Source.asString(request);
            ObjectNode updateRequest = (ObjectNode) mapper.readTree(body);

            if (updateRequest.has("status")) {
                String statusStr = updateRequest.get("status").asText();
                try {
                    ApiKeyStatus status = ApiKeyStatus.fromValue(statusStr);
                    apiKeyRepository.updateStatus(id, status);
                } catch (IllegalArgumentException e) {
                    logger.debug("Failed to update API key status", e);
                    ObjectNode error = mapper.createObjectNode();
                    error.put("error", "Invalid status value. Must be 'active' or 'revoked'");
                    sendJsonResponse(response, callback, error.toString(), HttpStatus.BAD_REQUEST_400);
                    return;
                }
            }

            if (updateRequest.has("pricingPlanId")) {
                Long planId = updateRequest.get("pricingPlanId").asLong();
                apiKeyRepository.updatePricingPlan(id, planId);
            }

            if (updateRequest.has("description")) {
                String description = updateRequest.get("description").asText();
                apiKeyRepository.updateDescription(id, description);
            }

            // Clear cache to force reload
            apiKeyManager.removeCacheEntry(keyId);

            ObjectNode responseJson = mapper.createObjectNode();
            responseJson.put("message", "API key updated successfully");

            sendJsonResponse(response, callback, responseJson.toString(), HttpStatus.OK_200);
        } catch (Exception e) {
            logger.error("Failed to update API key", e);
            sendServerError(response, callback);
        }
    }

    private void handleGetPricingPlans(Response response, Callback callback) {
        try {
            var plans = pricingPlanRepository.findAll();
            ArrayNode plansArray = mapper.createArrayNode();

            for (var plan : plans) {
                var planNode = createPricingPlanNode(plan, mapper);
                plansArray.add(planNode);
            }

            ObjectNode responseNode = mapper.createObjectNode();
            responseNode.set("plans", plansArray);
            responseNode.put("minimumPaymentAmount", minimumPaymentAmount.toString());

            sendJsonResponse(response, callback, responseNode.toString(), HttpStatus.OK_200);
        } catch (Exception e) {
            logger.error("Failed to get pricing plans", e);
            sendServerError(response, callback);
        }
    }

    static ObjectNode createPricingPlanNode(PricingPlanRepository.PricingPlan plan, ObjectMapper mapper) {
        ObjectNode planNode = mapper.createObjectNode();
        planNode.put("id", plan.id());
        planNode.put("name", plan.name());
        planNode.put("requestsPerSecond", plan.requestsPerSecond());
        planNode.put("requestsPerDay", plan.requestsPerDay());
        planNode.put("price", plan.price().toString());
        return planNode;
    }

    private void handleCreatePricingPlan(Request request, Response response, Callback callback) {
        try {
            PaymentPlan paymentPlan = getPaymentPlan(Content.Source.asString(request));

            pricingPlanRepository.create(paymentPlan.name(), paymentPlan.requestsPerSecond(), paymentPlan.requestsPerDay(), paymentPlan.price());

            ObjectNode responseJson = mapper.createObjectNode();
            responseJson.put("message", "Pricing plan created successfully");

            sendJsonResponse(response, callback, responseJson.toString(), HttpStatus.CREATED_201);
        } catch (Exception e) {
            logger.error("Failed to create pricing plan", e);
            sendServerError(response, callback);
        }
    }

    private void handleUpdatePricingPlan(String planId, Request request, Response response, Callback callback) {
        try {
            Long id = Long.parseLong(planId);
            PaymentPlan paymentPlan = getPaymentPlan(Content.Source.asString(request));

            pricingPlanRepository.update(id, paymentPlan.name(), paymentPlan.requestsPerSecond(), paymentPlan.requestsPerDay(), paymentPlan.price());

            ObjectNode responseJson = mapper.createObjectNode();
            responseJson.put("message", "Pricing plan updated successfully");

            sendJsonResponse(response, callback, responseJson.toString(), HttpStatus.OK_200);
        } catch (Exception e) {
            logger.error("Failed to update pricing plan", e);
            sendServerError(response, callback);
        }
    }

    private PaymentPlan getPaymentPlan(String body) throws IOException {
        ObjectNode planRequest = (ObjectNode) mapper.readTree(body);

        String name = planRequest.get("name").asText();
        int requestsPerSecond = planRequest.get("requestsPerSecond").asInt();
        int requestsPerDay = planRequest.get("requestsPerDay").asInt();
        BigInteger price = planRequest.has("price") ? new BigInteger(planRequest.get("price").asText()) : BigInteger.ZERO;
        return new PaymentPlan(name, requestsPerSecond, requestsPerDay, price);
    }

    private record PaymentPlan(String name, int requestsPerSecond, int requestsPerDay, BigInteger price) {
    }

    private void handleDeletePricingPlan(String planId, Response response, Callback callback) {
        try {
            long id = Long.parseLong(planId);

            int keysUsingPlan = pricingPlanRepository.countKeysUsingPlan(id);
            int sessionsUsingPlan = pricingPlanRepository.countPaymentSessionsUsingPlan(id);

            if (keysUsingPlan > 0 || sessionsUsingPlan > 0) {
                ObjectNode error = mapper.createObjectNode();
                error.put("error", "Cannot delete pricing plan");
                error.put("message", String.format(
                    "This pricing plan is currently in use by %d API key(s) and/or %d payment session(s). " +
                    "Please reassign or remove these associations before deleting the plan.",
                    keysUsingPlan, sessionsUsingPlan
                ));
                sendJsonResponse(response, callback, error.toString(), HttpStatus.CONFLICT_409);
                return;
            }

            // Note: There's a small race condition here - a key could be added after the check
            // but before deletion. The database foreign key constraint will catch this.
            boolean deleted = pricingPlanRepository.delete(id);

            if (deleted) {
                ObjectNode responseJson = mapper.createObjectNode();
                responseJson.put("message", "Pricing plan deleted successfully");
                sendJsonResponse(response, callback, responseJson.toString(), HttpStatus.OK_200);
            } else {
                ObjectNode error = mapper.createObjectNode();
                error.put("error", "Pricing plan not found");
                sendJsonResponse(response, callback, error.toString(), HttpStatus.NOT_FOUND_404);
            }
        } catch (NumberFormatException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("error", "Invalid plan ID");
            sendJsonResponse(response, callback, error.toString(), HttpStatus.BAD_REQUEST_400);
        } catch (Exception e) {
            logger.error("Failed to delete pricing plan", e);
            sendServerError(response, callback);
        }
    }

    private void handleGetStats(Response response, Callback callback) {
        try {
            ObjectNode stats = mapper.createObjectNode();
            stats.put("totalApiKeys", apiKeyRepository.count());
            stats.put("activeApiKeys", apiKeyRepository.countActive());
            stats.put("totalPricingPlans", pricingPlanRepository.count());

            sendJsonResponse(response, callback, stats.toString(), HttpStatus.OK_200);
        } catch (Exception e) {
            logger.error("Failed to get stats", e);
            sendServerError(response, callback);
        }
    }

    private void handleGetUtilization(Response response, Callback callback) {
        try {
            var keys = apiKeyRepository.findAll();
            ArrayNode utilizationArray = mapper.createArrayNode();

            for (var key : keys) {
                ObjectNode keyUtilization = mapper.createObjectNode();
                keyUtilization.put("id", key.id());
                keyUtilization.put("apiKey", key.apiKey());
                keyUtilization.put("description", key.description());
                keyUtilization.put("status", key.status().getValue());

                // Get utilization info from rate limiter
                var utilization = rateLimiterManager.getUtilization(key.apiKey());
                if (utilization != null) {
                    keyUtilization.put("consumedPerSecond", utilization.getConsumedPerSecond());
                    keyUtilization.put("maxPerSecond", utilization.getMaxTokensPerSecond());
                    keyUtilization.put("availablePerSecond", utilization.getAvailableTokensPerSecond());
                    keyUtilization.put("utilizationPercentPerSecond",
                        Math.round(utilization.getUtilizationPercentPerSecond() * 100) / 100.0);

                    keyUtilization.put("consumedPerDay", utilization.getConsumedPerDay());
                    keyUtilization.put("maxPerDay", utilization.getMaxTokensPerDay());
                    keyUtilization.put("availablePerDay", utilization.getAvailableTokensPerDay());
                    keyUtilization.put("utilizationPercentPerDay",
                        Math.round(utilization.getUtilizationPercentPerDay() * 100) / 100.0);
                } else {
                    // No utilization data yet (key hasn't been used)
                    var apiKeyInfo = apiKeyManager.getApiKeyInfo(key.apiKey());
                    if (apiKeyInfo != null) {
                        keyUtilization.put("consumedPerSecond", 0);
                        keyUtilization.put("maxPerSecond", apiKeyInfo.requestsPerSecond());
                        keyUtilization.put("availablePerSecond", apiKeyInfo.requestsPerSecond());
                        keyUtilization.put("utilizationPercentPerSecond", 0.0);

                        keyUtilization.put("consumedPerDay", 0);
                        keyUtilization.put("maxPerDay", apiKeyInfo.requestsPerDay());
                        keyUtilization.put("availablePerDay", apiKeyInfo.requestsPerDay());
                        keyUtilization.put("utilizationPercentPerDay", 0.0);
                    }
                }

                utilizationArray.add(keyUtilization);
            }

            sendJsonResponse(response, callback, utilizationArray.toString(), HttpStatus.OK_200);
        } catch (Exception e) {
            logger.error("Failed to get utilization", e);
            sendServerError(response, callback);
        }
    }

    private void handleGetShardConfig(Response response, Callback callback) {
        try {
            var configRecord = shardConfigRepository.getLatestConfig();

            ObjectNode responseJson = mapper.createObjectNode();
            responseJson.put("configJson", mapper.writeValueAsString(configRecord.config()));
            responseJson.put("createdAt", configRecord.createdAt().toString());
            responseJson.put("createdBy", configRecord.createdBy());

            sendJsonResponse(response, callback, responseJson.toString(), HttpStatus.OK_200);
        } catch (Exception e) {
            logger.error("Failed to get shard configuration", e);
            sendServerError(response, callback);
        }
    }

    private void handleUploadShardConfig(Request request, Response response, Callback callback) {
        try {
            String body = Content.Source.asString(request);
            ObjectNode uploadRequest = (ObjectNode) mapper.readTree(body);

            String configJson = uploadRequest.get("configJson").asText();

            // Parse the configuration
            ShardConfig shardConfig = mapper.readValue(configJson, ShardConfig.class);

            // Validate the configuration
            ShardRouter shardRouter = new DefaultShardRouter(shardConfig);
            try {
                ShardConfigValidator.validate(shardRouter, shardConfig);
            } catch (IllegalArgumentException e) {
                // Validation failed - return error without saving
                logger.warn("Shard configuration validation failed: {}", e.getMessage());
                ObjectNode error = mapper.createObjectNode();
                error.put("error", "Configuration validation failed: " + e.getMessage());
                sendJsonResponse(response, callback, error.toString(), HttpStatus.BAD_REQUEST_400);
                return;
            }

            // Save to database only if validation succeeds
            shardConfigRepository.saveConfig(shardConfig, "admin");

            ObjectNode responseJson = mapper.createObjectNode();
            responseJson.put("message", "Shard configuration uploaded successfully");
            responseJson.put("note", "Configuration will be applied within seconds");

            sendJsonResponse(response, callback, responseJson.toString(), HttpStatus.OK_200);
        } catch (Exception e) {
            if (e instanceof ValueInstantiationException instantiationException) {
                if (instantiationException.getCause() instanceof Exception causeException) {
                    e = causeException;
                }
            }
            logger.error("Failed to upload shard configuration", e);
            ObjectNode error = mapper.createObjectNode();
            error.put("error", "Invalid configuration: " + e.getMessage());
            sendJsonResponse(response, callback, error.toString(), HttpStatus.BAD_REQUEST_400);
        }
    }

    private String extractToken(Request request) {
        String authHeader = request.getHeaders().get(HttpHeader.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private boolean isValidSession(String token) {
        if (token == null) return false;
        SessionInfo session = sessions.get(token);
        return session != null && !session.isExpired();
    }

    private void cleanExpiredSessions() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private String generateSessionToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private void sendJsonResponse(Response response, Callback callback, String json, int status) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
        response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
    }

    private void sendUnauthorized(Response response, Callback callback) {
        response.setStatus(HttpStatus.UNAUTHORIZED_401);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
        response.write(true, ByteBuffer.wrap("{\"error\":\"Unauthorized\"}".getBytes()), callback);
    }

    private void sendNotFound(Response response, Callback callback) {
        response.setStatus(HttpStatus.NOT_FOUND_404);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
        response.write(true, ByteBuffer.wrap("{\"error\":\"Not found\"}".getBytes()), callback);
    }

    private void sendServerError(Response response, Callback callback) {
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
        response.write(true, ByteBuffer.wrap("{\"error\":\"Internal server error\"}".getBytes()), callback);
    }
}