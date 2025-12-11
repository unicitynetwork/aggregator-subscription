package org.unicitylabs.proxy;

import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.shard.ShardRouter;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import org.eclipse.jetty.http.HttpCookie;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.unicitylabs.proxy.util.CorsUtils;

import static org.apache.commons.io.FileUtils.ONE_MB;
import static org.eclipse.jetty.http.HttpHeader.*;
import static org.eclipse.jetty.http.HttpMethod.*;
import static org.eclipse.jetty.http.MimeTypes.Type.TEXT_PLAIN;

record RoutingParams(String requestId, String shardId) {
    boolean hasBoth() {
        return hasRequestId() && hasShardId();
    }

    boolean hasAny() {
        return hasRequestId() || hasShardId();
    }

    boolean hasRequestId() {
        return requestId != null && !requestId.isBlank();
    }

    boolean hasShardId() {
        return shardId != null && !shardId.isBlank();
    }
}

public class RequestHandler extends Handler.Abstract {
    public static final int MAX_PAYLOAD_SIZE_BYTES = 10 * (int) ONE_MB;
    public static final int MAX_HEADER_COUNT = 200;

    private static final String COOKIE_SHARD_ID = "UNICITY_SHARD_ID";
    private static final String COOKIE_REQUEST_ID = "UNICITY_REQUEST_ID";

    private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);

    private static final Pattern BEARER_RX = Pattern.compile(
            "^\\s*[Bb]earer[ \\t]+([A-Za-z0-9\\-._~+/]+=*)\\s*$");
    private static final String BEARER_AUTHORIZATION = "Bearer";
    static final String HEADER_X_API_KEY = "X-API-Key";
    static final String HEADER_X_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";

    private final HttpClient httpClient;
    private volatile ShardRouter shardRouter;
    private final Duration readTimeout;
    private final boolean useVirtualThreads;
    private final CachedApiKeyManager apiKeyManager;
    private final RateLimiterManager rateLimiterManager;
    private final WebUIHandler webUIHandler;
    private final Set<String> protectedMethods;
    private final ObjectMapper objectMapper = ObjectMapperUtils.createObjectMapper();

    public RequestHandler(ProxyConfig config, ShardRouter shardRouter, org.unicitylabs.proxy.repository.DatabaseConfig databaseConfig) {
        this.shardRouter = shardRouter;
        this.readTimeout = Duration.ofMillis(config.getReadTimeout());
        this.useVirtualThreads = config.isVirtualThreads();
        this.apiKeyManager = CachedApiKeyManager.getInstance();
        this.rateLimiterManager = new RateLimiterManager(apiKeyManager);
        this.webUIHandler = new WebUIHandler(databaseConfig);
        this.protectedMethods = config.getProtectedMethods();

        var httpClientBuilder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.getConnectTimeout()))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER);
        
        if (useVirtualThreads) {
            httpClientBuilder.executor(Executors.newVirtualThreadPerTaskExecutor());
            logger.info("Using virtual threads for HTTP client");
        } else {
            // Use a fixed thread pool for HTTP client - allocate 40% of total threads
            // Server gets 60%, client gets 40% to stay within configured total
            int clientThreads = Math.max(2, (int)(config.getWorkerThreads() * 0.4));
            httpClientBuilder.executor(Executors.newFixedThreadPool(clientThreads));
            logger.info("Using fixed thread pool with {} threads for HTTP client (from {} total configured)", 
                clientThreads, config.getWorkerThreads());
        }
        
        this.httpClient = httpClientBuilder.build();

        logger.info("Request handler initialized with {} shard targets", shardRouter.getAllTargets().size());
    }
    
    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String path = request.getHttpURI().getPath();
        String method = request.getMethod();

        // Log incoming request
        if (logger.isDebugEnabled()) {
            logger.debug("Incoming request: {} {} from {}", method, path, request.getConnectionMetaData().getRemoteSocketAddress());
        }

        // Add CORS headers to all responses
        CorsUtils.addCorsHeaders(request, response, HEADER_X_RATE_LIMIT_REMAINING);

        // Handle CORS preflight OPTIONS requests
        if ("OPTIONS".equals(method)) {
            response.setStatus(HttpStatus.NO_CONTENT_204);
            callback.succeeded();
            return true;
        }

        // Handle web UI routes
        if ("/index.html".equals(path) || "/generate".equals(path)) {
            return webUIHandler.handle(request, response, callback);
        }
        
        byte[] requestBody;
        if (hasBody(request.getMethod())) {
            requestBody = readBodyWithLimit(request);
        } else {
            requestBody = null;
        }

        JsonNode root = attemptParsingRequestBodyAsJson(method, requestBody);

        if (requiresAuthentication(request.getMethod(), root)) {
            if (!performAuthenticationAndRateLimiting(request, response, callback, method, path)) {
                return true;
            }
        }

        if (!performValidationOnRequestSizeLimits(request, response, callback)) {
            return true;
        }

        if (useVirtualThreads) {
            JsonNode finalRoot = root;
            Thread.startVirtualThread(() -> handleRequestAsync(request, requestBody, finalRoot, response, callback));
        } else {
            handleRequestAsync(request, requestBody, root, response, callback);
        }
        
        return true;
    }

    private JsonNode attemptParsingRequestBodyAsJson(String method, byte[] requestBody) {
        JsonNode root = null;
        if ("POST".equals(method) && requestBody != null) {
            try {
               root = objectMapper.readTree(requestBody);
            } catch (IOException e) {
              // Non-JSON or invalid JSON content, will be treated as non-JSON-RPC request
              logger.debug("Could not extract JSON method from request body", e);
            }
        }
        return root;
    }

    private boolean performValidationOnRequestSizeLimits(Request request, Response response, Callback callback) {
        String validationError = validateRequestSizeLimits(request);
        if (validationError != null) {
            logger.warn("Request validation failed: {}", validationError);
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, TEXT_PLAIN.asString());
            response.write(true, ByteBuffer.wrap(validationError.getBytes()), callback);
            return false;
        }
        return true;
    }

    private boolean performAuthenticationAndRateLimiting(Request request, Response response, Callback callback, String method, String path) {
        String apiKey = extractApiKey(request);
        if (apiKey == null || !apiKeyManager.isValidApiKey(apiKey)) {
            logger.warn("Authentication failed for request: {} {} - API key: {}", method, path,
                    apiKey != null ? "invalid" : "missing");
            response.setStatus(HttpStatus.UNAUTHORIZED_401);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, TEXT_PLAIN.asString());
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, BEARER_AUTHORIZATION);
            response.write(true, ByteBuffer.wrap("Unauthorized".getBytes()), callback);
            return false;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Request authenticated with API key ending in: ...{}",
                    apiKey.length() > 4 ? apiKey.substring(apiKey.length() - 4) : "***");
        }

        RateLimiterManager.RateLimitResult rateLimitResult = rateLimiterManager.tryConsume(apiKey);
        if (!rateLimitResult.allowed()) {
            logger.info("Rate limit exceeded for API key: {}", apiKey);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS_429);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, TEXT_PLAIN.asString());
            response.getHeaders().put(RETRY_AFTER, String.valueOf(rateLimitResult.retryAfterSeconds()));
            response.write(true, ByteBuffer.wrap("Too Many Requests".getBytes()), callback);
            return false;
        }

        response.getHeaders().put(HEADER_X_RATE_LIMIT_REMAINING, String.valueOf(rateLimitResult.remainingTokens()));
        return true;
    }

    private boolean requiresAuthentication(String method, JsonNode root) {
        boolean requiresAuth = false;
        if ("POST".equals(method) && root != null) {
            String jsonRpcMethod = extractJsonRpcMethodFromBody(root);
            if (jsonRpcMethod != null && protectedMethods.contains(jsonRpcMethod)) {
                requiresAuth = true;
            }
        }
        return requiresAuth;
    }

    private void handleRequestAsync(Request request, byte[] cachedBody, JsonNode root, Response response, Callback callback) {
        try {
            proxyRequest(request, cachedBody, root, response, callback);
        } catch (Exception e) {
            logger.error("Error handling request", e);
            response.setStatus(HttpStatus.BAD_GATEWAY_502);
            response.write(true, ByteBuffer.wrap("Bad Gateway".getBytes()), callback);
        }
    }
    
    private void proxyRequest(Request request, byte[] cachedBody, JsonNode root, Response response, Callback callback) {
        try {
            String method = request.getMethod();
            String path = Request.getPathInContext(request);
            String queryString = request.getHttpURI().getQuery();
            String fullPath = queryString != null ? path + "?" + queryString : path;

            // Determine target URL based on routing parameters
            String targetUrl;
            try {
                targetUrl = determineTargetUrl(request, root);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid routing parameters: {}", e.getMessage());
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                String errorBody = String.format("{\"error\":\"%s\"}", e.getMessage());
                response.write(true, ByteBuffer.wrap(errorBody.getBytes()), callback);
                return;
            }

            String targetUri = joinUrlPath(targetUrl, fullPath);

            if (logger.isDebugEnabled()) {
                logger.debug("Proxying {} {} to {}", method, fullPath, targetUri);
                request.getHeaders().forEach(field ->
                    logger.debug("Request header: {}: {}", field.getName(), field.getValue()));
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUri))
                .timeout(readTimeout);
            forwardAllNonRestrictedHeaders(request, requestBuilder);
            forwardBody(cachedBody, method, requestBuilder);
            HttpRequest httpRequest = requestBuilder.build();

            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((httpResponse, throwable) -> {
                    if (throwable != null) {
                        logger.error("Failed to proxy request to {}: {}", targetUri, throwable.getMessage());
                        handleError(response, callback, throwable);
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Received response from {}: status {}", targetUri, httpResponse.statusCode());
                        }
                        forwardResponse(response, callback, httpResponse);
                    }
                });
            
        } catch (Exception e) {
            handleError(response, callback, e);
        }
    }

    private void forwardBody(byte[] requestBody, String method, HttpRequest.Builder requestBuilder) {
        if (requestBody != null) {
            if (logger.isTraceEnabled()) {
                String bodyStr = new String(requestBody, 0, Math.min(requestBody.length, 1000));
                logger.trace("Request body (first 1000 chars): {}", bodyStr);
            }

            requestBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(requestBody));
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        }
    }

    private void forwardAllNonRestrictedHeaders(Request request, HttpRequest.Builder requestBuilder) {
        Set<String> connectionTokens = parseConnectionTokens(request.getHeaders().getField(CONNECTION.asString()));
        request.getHeaders().forEach(field -> {
            String name = field.getName();
            if (!isRestrictedHeader(name) && !connectionTokens.contains(name.toLowerCase(Locale.ROOT))) {
                requestBuilder.header(name, field.getValue());
            }
        });
    }

    private void forwardResponse(Response response, Callback callback, HttpResponse<byte[]> httpResponse) {
        try {
            response.setStatus(httpResponse.statusCode());

            httpResponse.headers().map().forEach((name, values) -> {
                // Skip hop-by-hop headers and CORS headers (proxy adds its own CORS headers)
                if (!name.equalsIgnoreCase(CONNECTION.asString()) &&
                    !name.equalsIgnoreCase(TRANSFER_ENCODING.asString()) &&
                    !CorsUtils.isCorsHeader(name)) {
                    for (String value : values) {
                        response.getHeaders().add(name, value);
                    }
                }
            });

            byte[] body = httpResponse.body();
            if (body != null && body.length > 0) {
                response.write(true, ByteBuffer.wrap(body), callback);
            } else {
                callback.succeeded();
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Response forwarded: {} {}", httpResponse.statusCode(), httpResponse.uri());
            }

        } catch (Exception e) {
            logger.error("Error forwarding response", e);
            handleError(response, callback, e);
        }
    }

    private void handleError(Response response, Callback callback, Throwable throwable) {
        logger.error("Error processing request", throwable);
        
        try {
            response.setStatus(HttpStatus.BAD_GATEWAY_502);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, TEXT_PLAIN.asString());
            response.write(true, ByteBuffer.wrap(("Bad Gateway").getBytes()), callback);
        } catch (Exception e) {
            logger.error("Error writing error response", e);
            callback.failed(e);
        }
    }
    
    private boolean hasBody(String method) {
        return POST.asString().equals(method) || PUT.asString().equals(method) ||
               PATCH.asString().equals(method) || DELETE.asString().equals(method);
    }

    private boolean isRestrictedHeader(String name) {
        return name.equalsIgnoreCase(HOST.asString()) ||
               name.equalsIgnoreCase(CONNECTION.asString()) ||
               name.equalsIgnoreCase(CONTENT_LENGTH.asString()) ||
               name.equalsIgnoreCase(EXPECT.asString()) ||
               name.equalsIgnoreCase(UPGRADE.asString()) ||
               name.equalsIgnoreCase(TE.asString()) ||
               name.equalsIgnoreCase(TRANSFER_ENCODING.asString()) ||
               name.equalsIgnoreCase(KEEP_ALIVE.asString()) ||
               name.equalsIgnoreCase(PROXY_CONNECTION.asString()) ||
               name.equalsIgnoreCase(TRAILER.asString()) ||
               name.equalsIgnoreCase(PROXY_AUTHENTICATE.asString()) ||
               name.equalsIgnoreCase(PROXY_AUTHORIZATION.asString()) ||
               name.equalsIgnoreCase(AUTHORIZATION.asString()) ||
               name.equalsIgnoreCase(HEADER_X_API_KEY);
    }
    
    public RateLimiterManager getRateLimiterManager() {
        return rateLimiterManager;
    }

    public CachedApiKeyManager getApiKeyManager() {
        return apiKeyManager;
    }

    public void updateShardRouter(ShardRouter newRouter) {
        this.shardRouter = newRouter;
        logger.info("RequestHandler updated with new shard router ({} targets)", newRouter.getAllTargets().size());
    }

    private static Set<String> parseConnectionTokens(HttpField connectionField) {
        String connectionHeader = connectionField != null ? connectionField.getValue() : null;
        if (connectionHeader == null || connectionHeader.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(connectionHeader.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    private String extractApiKey(Request request) {
        var authField = request.getHeaders().getField(AUTHORIZATION.asString());
        if (authField != null) {
            String authHeader = authField.getValue();
            if (authHeader != null) {
                var m = BEARER_RX.matcher(authHeader);
                if (m.matches()) {
                    return m.group(1);
                }
            }
        }
        
        var apiKeyField = request.getHeaders().getField(HEADER_X_API_KEY);
        if (apiKeyField != null) {
            return apiKeyField.getValue().trim();
        }
        
        return null;
    }

    private String validateRequestSizeLimits(Request request) {
        var contentLengthField = request.getHeaders().getField(CONTENT_LENGTH.asString());
        if (contentLengthField != null) {
            try {
                long contentLength = Long.parseLong(contentLengthField.getValue());
                if (contentLength > MAX_PAYLOAD_SIZE_BYTES) {
                    return "Request body too large: " + contentLength + " bytes (max: " + MAX_PAYLOAD_SIZE_BYTES + ")";
                }
            } catch (NumberFormatException e) {
                return "Invalid Content-Length header";
            }
        }
        
        if (request.getHeaders().size() > MAX_HEADER_COUNT) {
            return "Too many headers: " + request.getHeaders().size() + " (max: " + MAX_HEADER_COUNT + ")";
        }

        return null; // No validation errors
    }

    private byte[] readBodyWithLimit(Request request) throws IOException {
        InputStream requestInputStream = Content.Source.asInputStream(request);
        try (BoundedInputStream boundedStream = BoundedInputStream.builder()
                .setInputStream(requestInputStream)
                .setMaxCount(MAX_PAYLOAD_SIZE_BYTES)
                .get())
        {
            return IOUtils.toByteArray(boundedStream);
        }
    }

    private String extractJsonRpcMethodFromBody(JsonNode root) {
        try {
            if (root.has("method")) {
                return root.get("method").asText();
            }
        } catch (Exception e) {
            logger.debug("Could not extract JSON-RPC method from request body", e);
        }
        return null;
    }

    private RoutingParams extractRoutingParams(JsonNode root) {
        String requestId = null;
        String shardId = null;

        try {
            if (root.has("params")) {
                JsonNode params = root.get("params");

                if (params.has("requestId")) {
                    String value = params.get("requestId").asText();
                    if (value != null && !value.isBlank()) {
                        requestId = value;
                    }
                }

                if (params.has("shardId")) {
                    String value = params.get("shardId").asText();
                    if (value != null && !value.isBlank()) {
                        shardId = value;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract routing params from request body", e);
        }

        return new RoutingParams(requestId, shardId);
    }

    private boolean isJsonRpcRequest(JsonNode root) {
        return root != null && root.has("method");
    }

    private RoutingParams extractRoutingParamsFromCookies(Request request) {
        String requestId = null;
        String shardId = null;

        List<HttpCookie> cookies = Request.getCookies(request);
        if (cookies != null) {
            for (HttpCookie cookie : cookies) {
                if (COOKIE_REQUEST_ID.equals(cookie.getName())) {
                    requestId = cookie.getValue();
                } else if (COOKIE_SHARD_ID.equals(cookie.getName())) {
                    shardId = cookie.getValue();
                }
            }
        }

        return new RoutingParams(requestId, shardId);
    }

    private String routeWithParams(RoutingParams params, boolean isJsonRpc) throws IllegalArgumentException {
        // Check for conflicting parameters
        if (params.hasBoth()) {
            throw new IllegalArgumentException("Cannot specify both requestId and shardId");
        }

        // Route by shard ID if present
        if (params.hasShardId()) {
            int shardId;
            try {
                shardId = Integer.parseInt(params.shardId());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid Shard ID format: " + params.shardId(), e);
            }

            Optional<String> target = shardRouter.routeByShardId(shardId);
            if (target.isEmpty()) {
                throw new IllegalArgumentException("Shard ID not found: " + params.shardId());
            }
            if (logger.isTraceEnabled()) {
                logger.trace("Routing request with shard ID {} to {}", params.shardId(), target.get());
            }
            return target.get();
        }

        // Route by request ID if present
        if (params.hasRequestId()) {
            String target = shardRouter.routeByRequestId(params.requestId());
            if (logger.isTraceEnabled()) {
                logger.trace("Routing request with ID {} to {}", params.requestId(), target);
            }
            return target;
        }

        // No routing params
        if (isJsonRpc) {
            throw new IllegalArgumentException("JSON-RPC requests must include either requestId or shardId");
        } else {
            // Non-JSON-RPC requests use random routing
            return shardRouter.getRandomTarget();
        }
    }

    private String determineTargetUrl(Request request, JsonNode root) throws IllegalArgumentException {
        boolean isJsonRpc = isJsonRpcRequest(root);

        RoutingParams params = isJsonRpc
            ? extractRoutingParams(root)
            : extractRoutingParamsFromCookies(request);

        return routeWithParams(params, isJsonRpc);
    }

    ShardRouter getShardRouterForTesting() {
        return shardRouter;
    }

    static String joinUrlPath(String baseUrl, String path) {
        String cleanBase = baseUrl.replaceAll("/+$", "");
        String cleanPath = path.replaceAll("^/+", "");
        return URI.create(cleanBase + "/" + cleanPath).toString();
    }
}