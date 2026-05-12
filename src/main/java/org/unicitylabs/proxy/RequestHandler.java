package org.unicitylabs.proxy;

import org.unicitylabs.proxy.metrics.GatewayMetrics;
import org.unicitylabs.proxy.metrics.GatewayMetrics.Outcome;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.unicitylabs.proxy.util.CorsUtils;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.util.HexConverter;

import static org.apache.commons.io.FileUtils.ONE_MB;
import static org.eclipse.jetty.http.HttpHeader.*;
import static org.eclipse.jetty.http.HttpMethod.*;
import static org.eclipse.jetty.http.MimeTypes.Type.TEXT_PLAIN;

record RoutingParams(String stateId, String shardId) {
    boolean hasStateId() {
        return stateId != null && !stateId.isBlank();
    }

    boolean hasShardId() {
        return shardId != null && !shardId.isBlank();
    }

    boolean hasBoth() {
        return hasStateId() && hasShardId();
    }
}

public class RequestHandler extends Handler.Abstract {
    private static final String CERTIFICATION_REQUEST = "certification_request";
    private static final String GET_INCLUSION_PROOF_V2 = "get_inclusion_proof.v2";
    // TODO(bft-shard): replace these with Java SDK-exported v2 CBOR metadata
    // once available. Until then, aggregator-go/pkg/api/cbor_tags.go is the
    // source of truth for the certification_request wire tag/version.
    private static final long CERTIFICATION_REQUEST_CBOR_TAG = 39030L;
    private static final int CERTIFICATION_REQUEST_VERSION = 1;

    /**
     * JSON-RPC methods whose semantics require shard-bound routing in bft-shard
     * mode. For any method in this set, the gateway rejects requests lacking a
     * {@code stateId}.
     */
    private static final Set<String> SHARD_BOUND_V2_METHODS =
        Set.of(CERTIFICATION_REQUEST, GET_INCLUSION_PROOF_V2);

    public static final int MAX_PAYLOAD_SIZE_BYTES = 10 * (int) ONE_MB;
    public static final int MAX_HEADER_COUNT = 200;

    private static final String COOKIE_SHARD_ID = "UNICITY_SHARD_ID";
    private static final String COOKIE_STATE_ID = "UNICITY_STATE_ID";

    private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);

    private static final Pattern BEARER_RX = Pattern.compile(
            "^\\s*[Bb]earer[ \\t]+([A-Za-z0-9\\-._~+/]+=*)\\s*$");
    private static final String BEARER_AUTHORIZATION = "Bearer";
    static final String HEADER_X_API_KEY = "X-API-Key";
    static final String HEADER_X_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    static final String HEADER_X_STATE_ID = "X-State-ID";

    private final HttpClient httpClient;
    private final org.eclipse.jetty.client.HttpClient upstreamH2cClient;
    private volatile ShardRouter shardRouter;
    private final Duration readTimeout;
    private final boolean useVirtualThreads;
    private final boolean upstreamH2cEnabled;
    private final CachedApiKeyManager apiKeyManager;
    private final RateLimiterManager rateLimiterManager;
    private final WebUIHandler webUIHandler;
    private final Set<String> protectedMethods;
    private final ObjectMapper objectMapper = ObjectMapperUtils.createObjectMapper();
    private final GatewayMetrics metrics;

    /**
     * Test-only convenience constructor: spins up a throwaway
     * {@link GatewayMetrics} (with its own JVM/system binders). Production
     * code must use the 4-arg constructor with a shared {@code GatewayMetrics}
     * to avoid duplicate metric registrations.
     */
    RequestHandler(ProxyConfig config, ShardRouter shardRouter, org.unicitylabs.proxy.repository.DatabaseConfig databaseConfig) {
        this(config, shardRouter, databaseConfig, new GatewayMetrics());
    }

    public RequestHandler(ProxyConfig config, ShardRouter shardRouter, org.unicitylabs.proxy.repository.DatabaseConfig databaseConfig, GatewayMetrics metrics) {
        this.metrics = metrics;
        this.shardRouter = shardRouter;
        this.readTimeout = Duration.ofMillis(config.getReadTimeout());
        this.useVirtualThreads = config.isVirtualThreads();
        this.upstreamH2cEnabled = config.isUpstreamH2cEnabled();
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
        this.upstreamH2cClient = upstreamH2cEnabled ? buildUpstreamH2cClient(config) : null;
        if (upstreamH2cEnabled) {
            logger.info(
                "Using Jetty h2c client for http:// upstream aggregators (maxConnectionsPerDestination={}, maxQueuedRequestsPerDestination={})",
                upstreamH2cClient.getMaxConnectionsPerDestination(),
                upstreamH2cClient.getMaxRequestsQueuedPerDestination()
            );
        }

        logger.info("Request handler initialized with {} shard targets", shardRouter.getAllTargets().size());
    }

    private org.eclipse.jetty.client.HttpClient buildUpstreamH2cClient(ProxyConfig config) {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.setUseALPN(false);
        http2Client.setConnectTimeout(config.getConnectTimeout());
        http2Client.setIdleTimeout(config.getIdleTimeout());
        http2Client.setStreamIdleTimeout(config.getReadTimeout());
        http2Client.setInitialSessionRecvWindow(config.getUpstreamH2cInitialSessionRecvWindow());
        http2Client.setInitialStreamRecvWindow(config.getUpstreamH2cInitialStreamRecvWindow());
        http2Client.setMaxLocalStreams(config.getUpstreamH2cMaxLocalStreams());

        org.eclipse.jetty.client.HttpClient client = new org.eclipse.jetty.client.HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        client.setConnectTimeout(config.getConnectTimeout());
        client.setIdleTimeout(config.getIdleTimeout());
        client.setFollowRedirects(false);
        client.setMaxConnectionsPerDestination(config.getUpstreamH2cMaxConnectionsPerDestination());
        client.setMaxRequestsQueuedPerDestination(config.getUpstreamH2cMaxQueuedRequestsPerDestination());
        try {
            client.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start upstream h2c client", e);
        }
        return client;
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

        RequestMetricsRecorder recorder = new RequestMetricsRecorder(metrics, response);
        Callback metricsCallback = recorder.wrap(callback);

        // Handle CORS preflight OPTIONS requests
        if (OPTIONS.asString().equals(method)) {
            response.setStatus(HttpStatus.NO_CONTENT_204);
            metricsCallback.succeeded();
            return true;
        }

        // Handle web UI routes
        if ("/index.html".equals(path) || "/generate".equals(path)) {
            return webUIHandler.handle(request, response, metricsCallback);
        }

        byte[] requestBody;
        if (hasBody(request.getMethod())) {
            try {
                requestBody = readBodyWithLimit(request);
            } catch (IOException e) {
                // Client transport failed mid-body. Without this catch, the
                // exception bubbles out of handle(), Jetty's default error
                // path runs, and our wrapped callback never fires — so the
                // request is missing from gateway_requests_total.
                logger.warn("Failed to read request body: {}", e.getMessage());
                recorder.setOutcome(Outcome.BAD_REQUEST);
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, TEXT_PLAIN.asString());
                response.write(true, ByteBuffer.wrap("Failed to read request body".getBytes()), metricsCallback);
                return true;
            }
        } else {
            requestBody = null;
        }

        String jsonRpcMethod = extractJsonRpcMethodFromBody(method, requestBody);
        recorder.setJsonRpcMethod(jsonRpcMethod);

        if (requiresAuthentication(request.getMethod(), jsonRpcMethod)) {
            if (!performAuthenticationAndRateLimiting(request, response, metricsCallback, method, path, recorder)) {
                return true;
            }
        }

        if (!performValidationOnRequestSizeLimits(request, response, metricsCallback, recorder)) {
            return true;
        }

        if (useVirtualThreads) {
            Thread.startVirtualThread(() -> handleRequestAsync(request, requestBody, jsonRpcMethod, response, metricsCallback, recorder));
        } else {
            handleRequestAsync(request, requestBody, jsonRpcMethod, response, metricsCallback, recorder);
        }

        return true;
    }

    private String extractJsonRpcMethodFromBody(String method, byte[] requestBody) {
        if (POST.asString().equals(method) && requestBody != null) {
            try (JsonParser parser = objectMapper.getFactory().createParser(requestBody)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return null;
                }

                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String fieldName = parser.currentName();
                    JsonToken valueToken = parser.nextToken();

                    if ("method".equals(fieldName)) {
                        return valueToken == JsonToken.VALUE_STRING ? parser.getText() : null;
                    }

                    parser.skipChildren();
                }
            } catch (IOException e) {
                // Non-JSON or invalid JSON content will be treated as a non-JSON-RPC request
                // unless a method field was seen before the malformed part.
                logger.debug("Could not extract JSON-RPC method from request body", e);
            }
        }
        return null;
    }

    private boolean performValidationOnRequestSizeLimits(Request request, Response response, Callback callback, RequestMetricsRecorder recorder) {
        String validationError = validateRequestSizeLimits(request);
        if (validationError != null) {
            logger.warn("Request validation failed: {}", validationError);
            recorder.setOutcome(Outcome.BAD_REQUEST);
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, TEXT_PLAIN.asString());
            response.write(true, ByteBuffer.wrap(validationError.getBytes()), callback);
            return false;
        }
        return true;
    }

    private boolean performAuthenticationAndRateLimiting(Request request, Response response, Callback callback, String method, String path, RequestMetricsRecorder recorder) {
        String apiKey = extractApiKey(request);
        if (apiKey == null || !apiKeyManager.isValidApiKey(apiKey)) {
            logger.warn("Authentication failed for request: {} {} - API key: {}", method, path,
                    apiKey != null ? "invalid" : "missing");
            recorder.setOutcome(Outcome.UNAUTHORIZED);
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
            recorder.setOutcome(Outcome.RATE_LIMITED);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS_429);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, TEXT_PLAIN.asString());
            response.getHeaders().put(RETRY_AFTER, String.valueOf(rateLimitResult.retryAfterSeconds()));
            response.write(true, ByteBuffer.wrap("Too Many Requests".getBytes()), callback);
            return false;
        }

        response.getHeaders().put(HEADER_X_RATE_LIMIT_REMAINING, String.valueOf(rateLimitResult.remainingTokens()));
        return true;
    }

    private boolean requiresAuthentication(String method, String jsonRpcMethod) {
        return POST.asString().equals(method)
            && jsonRpcMethod != null
            && protectedMethods.contains(jsonRpcMethod);
    }

    private void handleRequestAsync(Request request, byte[] cachedBody, String jsonRpcMethod, Response response, Callback callback, RequestMetricsRecorder recorder) {
        try {
            proxyRequest(request, cachedBody, jsonRpcMethod, response, callback, recorder);
        } catch (Exception e) {
            logger.error("Error handling request", e);
            recorder.setOutcome(Outcome.UPSTREAM_ERROR);
            response.setStatus(HttpStatus.BAD_GATEWAY_502);
            response.write(true, ByteBuffer.wrap("Bad Gateway".getBytes()), callback);
        }
    }

    private void proxyRequest(Request request, byte[] cachedBody, String jsonRpcMethod, Response response, Callback callback, RequestMetricsRecorder recorder) {
        try {
            String method = request.getMethod();
            String path = Request.getPathInContext(request);
            String queryString = request.getHttpURI().getQuery();
            String fullPath = queryString != null ? path + "?" + queryString : path;

            // Determine target URL based on routing parameters
            String targetUrl;
            try {
                targetUrl = determineTargetUrl(request, cachedBody, jsonRpcMethod);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid routing parameters: {}", e.getMessage());
                recorder.setOutcome(Outcome.ROUTING_ERROR);
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                String errorBody = routingErrorBody(e.getMessage());
                response.write(true, ByteBuffer.wrap(errorBody.getBytes()), callback);
                return;
            }

            String targetUri = joinUrlPath(targetUrl, fullPath);

            if (logger.isDebugEnabled()) {
                logger.debug("Proxying {} {} to {}", method, fullPath, targetUri);
                request.getHeaders().forEach(field ->
                    logger.debug("Request header: {}: {}", field.getName(), field.getValue()));
            }

            if (shouldUseUpstreamH2c(targetUri)) {
                proxyRequestWithUpstreamH2c(request, cachedBody, method, targetUrl, targetUri, jsonRpcMethod, response, callback, recorder);
                return;
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUri))
                .timeout(readTimeout);
            forwardAllNonRestrictedHeaders(request, requestBuilder);
            forwardBody(cachedBody, method, requestBuilder);
            HttpRequest httpRequest = requestBuilder.build();

            long upstreamStartNanos = System.nanoTime();
            metrics.incrementActiveUpstreamRequests();
            try {
                httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .whenComplete((httpResponse, throwable) -> {
                        long upstreamElapsedNanos = System.nanoTime() - upstreamStartNanos;
                        metrics.decrementActiveUpstreamRequests();
                        if (throwable != null) {
                            logger.error("Failed to proxy request to {}: {}", targetUri, throwable.getMessage());
                            metrics.recordUpstream(targetUrl, false);
                            metrics.recordUpstreamDuration(targetUrl, jsonRpcMethod, false, upstreamElapsedNanos);
                            recorder.setOutcome(Outcome.UPSTREAM_ERROR);
                            handleError(response, callback, throwable);
                        } else {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Received response from {}: status {}", targetUri, httpResponse.statusCode());
                            }
                            boolean upstreamSuccess = httpResponse.statusCode() < 500;
                            metrics.recordUpstream(targetUrl, upstreamSuccess);
                            metrics.recordUpstreamDuration(targetUrl, jsonRpcMethod, upstreamSuccess, upstreamElapsedNanos);
                            forwardResponse(response, callback, httpResponse);
                        }
                    });
            } catch (Exception e) {
                metrics.decrementActiveUpstreamRequests();
                metrics.recordUpstream(targetUrl, false);
                metrics.recordUpstreamDuration(targetUrl, jsonRpcMethod, false, System.nanoTime() - upstreamStartNanos);
                throw e;
            }

        } catch (Exception e) {
            recorder.setOutcome(Outcome.UPSTREAM_ERROR);
            handleError(response, callback, e);
        }
    }

    private boolean shouldUseUpstreamH2c(String targetUri) {
        return upstreamH2cEnabled
            && upstreamH2cClient != null
            && targetUri.regionMatches(true, 0, "http://", 0, "http://".length());
    }

    private void proxyRequestWithUpstreamH2c(
        Request request,
        byte[] cachedBody,
        String method,
        String targetUrl,
        String targetUri,
        String jsonRpcMethod,
        Response response,
        Callback callback,
        RequestMetricsRecorder recorder
    ) {
        org.eclipse.jetty.client.Request upstreamRequest = upstreamH2cClient.newRequest(URI.create(targetUri))
            .method(method)
            .version(HttpVersion.HTTP_2)
            .timeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .idleTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .followRedirects(false);
        forwardAllNonRestrictedHeaders(request, upstreamRequest);
        forwardBody(cachedBody, method, upstreamRequest);

        long upstreamStartNanos = System.nanoTime();
        metrics.incrementActiveUpstreamRequests();
        try {
            upstreamRequest.send(new BufferingResponseListener(MAX_PAYLOAD_SIZE_BYTES) {
                @Override
                public void onComplete(Result result) {
                    long upstreamElapsedNanos = System.nanoTime() - upstreamStartNanos;
                    metrics.decrementActiveUpstreamRequests();
                    if (result.isFailed()) {
                        Throwable failure = result.getFailure();
                        logger.error("Failed to proxy h2c request to {}: {}", targetUri, failure.getMessage());
                        metrics.recordUpstream(targetUrl, false);
                        metrics.recordUpstreamDuration(targetUrl, jsonRpcMethod, false, upstreamElapsedNanos);
                        recorder.setOutcome(Outcome.UPSTREAM_ERROR);
                        handleError(response, callback, failure);
                        return;
                    }

                    org.eclipse.jetty.client.Response upstreamResponse = result.getResponse();
                    if (logger.isDebugEnabled()) {
                        logger.debug("Received h2c response from {}: status {}", targetUri, upstreamResponse.getStatus());
                    }
                    boolean upstreamSuccess = upstreamResponse.getStatus() < 500;
                    metrics.recordUpstream(targetUrl, upstreamSuccess);
                    metrics.recordUpstreamDuration(targetUrl, jsonRpcMethod, upstreamSuccess, upstreamElapsedNanos);
                    forwardResponse(response, callback, upstreamResponse, getContent());
                }
            });
        } catch (Exception e) {
            metrics.decrementActiveUpstreamRequests();
            metrics.recordUpstream(targetUrl, false);
            metrics.recordUpstreamDuration(targetUrl, jsonRpcMethod, false, System.nanoTime() - upstreamStartNanos);
            throw e;
        }
    }

    private String routingErrorBody(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            logger.debug("Could not serialize routing error response", e);
            return "{\"error\":\"Invalid routing parameters\"}";
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

    private void forwardBody(byte[] requestBody, String method, org.eclipse.jetty.client.Request requestBuilder) {
        if (requestBody != null) {
            if (logger.isTraceEnabled()) {
                String bodyStr = new String(requestBody, 0, Math.min(requestBody.length, 1000));
                logger.trace("Request body (first 1000 chars): {}", bodyStr);
            }

            requestBuilder.body(new BytesRequestContent(requestBody));
        } else if (requiresRequestBody(method)) {
            requestBuilder.body(new BytesRequestContent(new byte[0]));
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

    private void forwardAllNonRestrictedHeaders(Request request, org.eclipse.jetty.client.Request requestBuilder) {
        Set<String> connectionTokens = parseConnectionTokens(request.getHeaders().getField(CONNECTION.asString()));
        requestBuilder.headers(headers -> {
            request.getHeaders().forEach(field -> {
                String name = field.getName();
                if (!isRestrictedHeader(name) && !connectionTokens.contains(name.toLowerCase(Locale.ROOT))) {
                    headers.add(name, field.getValue());
                }
            });
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

    private void forwardResponse(Response response, Callback callback, org.eclipse.jetty.client.Response upstreamResponse, byte[] body) {
        try {
            response.setStatus(upstreamResponse.getStatus());

            for (var field : upstreamResponse.getHeaders()) {
                String name = field.getName();
                if (!name.equalsIgnoreCase(CONNECTION.asString()) &&
                    !name.equalsIgnoreCase(TRANSFER_ENCODING.asString()) &&
                    !CorsUtils.isCorsHeader(name)) {
                    response.getHeaders().add(name, field.getValue());
                }
            }

            if (body.length > 0) {
                response.write(true, ByteBuffer.wrap(body), callback);
            } else {
                callback.succeeded();
            }

            if (logger.isDebugEnabled()) {
                logger.debug("H2C response forwarded: {} {}", upstreamResponse.getStatus(), upstreamResponse.getRequest().getURI());
            }
        } catch (Exception e) {
            logger.error("Error forwarding h2c response", e);
            handleError(response, callback, e);
        }
    }

    private boolean requiresRequestBody(String method) {
        return POST.asString().equals(method)
            || PUT.asString().equals(method)
            || "PATCH".equalsIgnoreCase(method);
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

    public void stopUpstreamH2cClient() {
        if (upstreamH2cClient == null) {
            return;
        }
        try {
            upstreamH2cClient.stop();
        } catch (Exception e) {
            logger.warn("Failed to stop upstream h2c client", e);
        }
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

    private JsonNode parseRequestBodyAsJsonForRouting(byte[] requestBody) {
        try {
            return objectMapper.readTree(requestBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON-RPC request body", e);
        }
    }

    private RoutingParams extractRoutingParams(JsonNode root, String method) {
        String stateId = null;
        String shardId = null;

        try {
            if (CERTIFICATION_REQUEST.equals(method) && root.has("params")) {
                stateId = extractCertificationRequestStateId(root.get("params").asText());
            } else if (root.path("params").has("stateId")) {
                stateId = root.path("params").path("stateId").asText(null);
            }

            if (root.path("params").has("shardId")) {
                shardId = root.path("params").path("shardId").asText(null);
            }
        } catch (IllegalArgumentException e) {
            if (CERTIFICATION_REQUEST.equals(method)) {
                throw e;
            }
            logger.debug("Could not extract routing params from request body", e);
        } catch (Exception e) {
            if (CERTIFICATION_REQUEST.equals(method)) {
                String message = e.getMessage();
                if (message == null || message.isBlank()) {
                    message = "invalid certification_request params";
                }
                throw new IllegalArgumentException(message, e);
            }
            logger.debug("Could not extract routing params from request body", e);
        }

        return new RoutingParams(stateId, shardId);
    }

    private String extractCertificationRequestStateId(String paramsHex) {
        // certification_request params are a tagged/versioned CBOR value.
        var tagged = CborDeserializer.readTag(HexConverter.decode(paramsHex));
        if (tagged.getTag() != CERTIFICATION_REQUEST_CBOR_TAG) {
            throw new IllegalArgumentException("unexpected certification_request CBOR tag: " + tagged.getTag());
        }

        List<byte[]> data = CborDeserializer.readArray(tagged.getData());
        if (data.size() < 2) {
            throw new IllegalArgumentException("certification_request params missing stateId field");
        }

        int version = CborDeserializer.readUnsignedInteger(data.getFirst()).asInt();
        if (version != CERTIFICATION_REQUEST_VERSION) {
            throw new IllegalArgumentException("unsupported certification_request version: " + version);
        }

        try {
            return HexConverter.encode(CborDeserializer.readByteString(data.get(1)));
        } catch (Exception e) {
            throw new IllegalArgumentException("certification_request stateId must be a byte string", e);
        }
    }

    private RoutingParams extractRoutingParamsFromHeaders(Request request) {
        String stateId = null;

        var stateIdField = request.getHeaders().getField(HEADER_X_STATE_ID);
        if (stateIdField != null && stateIdField.getValue() != null) {
            stateId = stateIdField.getValue().trim();
        }

        return new RoutingParams(stateId, null);
    }

    private RoutingParams extractRoutingParamsFromCookies(Request request) {
        String stateId = null;
        String shardId = null;

        List<HttpCookie> cookies = Request.getCookies(request);
        if (cookies != null) {
            for (HttpCookie cookie : cookies) {
                if (COOKIE_STATE_ID.equals(cookie.getName())) {
                    stateId = cookie.getValue();
                } else if (COOKIE_SHARD_ID.equals(cookie.getName())) {
                    shardId = cookie.getValue();
                }
            }
        }

        return new RoutingParams(stateId, shardId);
    }

    private String routeWithParams(RoutingParams params, boolean isJsonRpc, String rpcMethod) throws IllegalArgumentException {
        if (params.hasBoth()) {
            throw new IllegalArgumentException("Cannot specify both stateId and shardId");
        }

        // Shard-bound v2 methods (certification_request, get_inclusion_proof.v2)
        // always route by stateId — shard ownership is derived from it, so a
        // shardId override would let the caller bypass that derivation.
        boolean shardBoundV2 = isJsonRpc && rpcMethod != null && SHARD_BOUND_V2_METHODS.contains(rpcMethod);
        if (shardBoundV2 && !params.hasStateId()) {
            throw new IllegalArgumentException(
                "JSON-RPC method '" + rpcMethod + "' requires stateId");
        }

        if (params.hasStateId()) {
            String target = shardRouter.routeByStateId(params.stateId());
            if (logger.isTraceEnabled()) {
                logger.trace("Routing request by stateId {} to {}", params.stateId(), target);
            }
            return target;
        }

        if (params.hasShardId()) {
            Optional<String> target = shardRouter.routeByShardId(params.shardId());
            if (target.isEmpty()) {
                throw new IllegalArgumentException("Shard ID not found: " + params.shardId());
            }
            if (logger.isTraceEnabled()) {
                logger.trace("Routing request with shard ID {} to {}", params.shardId(), target.get());
            }
            return target.get();
        }

        if (isJsonRpc) {
            throw new IllegalArgumentException(
                "JSON-RPC requests must include either stateId or shardId");
        }
        return shardRouter.getRandomTarget();
    }

    private String determineTargetUrl(Request request, byte[] requestBody, String jsonRpcMethod) throws IllegalArgumentException {
        boolean isJsonRpc = jsonRpcMethod != null;

        RoutingParams headerParams = extractRoutingParamsFromHeaders(request);
        if (headerParams.hasStateId()) {
            return routeWithParams(headerParams, isJsonRpc, jsonRpcMethod);
        }

        if (isJsonRpc) {
            JsonNode root = parseRequestBodyAsJsonForRouting(requestBody);
            return routeWithParams(extractRoutingParams(root, jsonRpcMethod), true, jsonRpcMethod);
        }

        return routeWithParams(extractRoutingParamsFromCookies(request), false, null);
    }

    ShardRouter getShardRouter() {
        return shardRouter;
    }

    static String joinUrlPath(String baseUrl, String path) {
        String cleanBase = baseUrl.replaceAll("/+$", "");
        String cleanPath = path.replaceAll("^/+", "");
        return URI.create(cleanBase + "/" + cleanPath).toString();
    }

    /**
     * Per-request metrics state. Mutated as the request flows through the
     * handler; recorded once when the response callback completes.
     */
    static final class RequestMetricsRecorder {
        private final GatewayMetrics metrics;
        private final Response response;
        private final long startNanos = System.nanoTime();
        private final java.util.concurrent.atomic.AtomicBoolean recorded = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile String jsonRpcMethod;
        private volatile Outcome outcome = Outcome.SUCCESS;

        RequestMetricsRecorder(GatewayMetrics metrics, Response response) {
            this.metrics = metrics;
            this.response = response;
            this.metrics.incrementActiveRequests();
        }

        void setJsonRpcMethod(String method) { this.jsonRpcMethod = method; }
        void setOutcome(Outcome outcome) { this.outcome = outcome; }

        Callback wrap(Callback delegate) {
            return new Callback() {
                @Override
                public void succeeded() {
                    recordOnce();
                    delegate.succeeded();
                }

                @Override
                public void failed(Throwable x) {
                    recordOnce();
                    delegate.failed(x);
                }
            };
        }

        private void recordOnce() {
            if (recorded.compareAndSet(false, true)) {
                long elapsed = System.nanoTime() - startNanos;
                try {
                    metrics.recordRequest(outcome, jsonRpcMethod, response.getStatus(), elapsed);
                } finally {
                    metrics.decrementActiveRequests();
                }
            }
        }
    }
}
