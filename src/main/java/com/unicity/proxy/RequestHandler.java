package com.unicity.proxy;

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
import java.io.UncheckedIOException;
import java.net.URI;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.IOException;

import static org.apache.commons.io.FileUtils.ONE_MB;
import static org.eclipse.jetty.http.HttpHeader.*;
import static org.eclipse.jetty.http.HttpMethod.*;
import static org.eclipse.jetty.http.MimeTypes.Type.TEXT_PLAIN;

public class RequestHandler extends Handler.Abstract {
    public static final int MAX_PAYLOAD_SIZE_BYTES = 10 * (int) ONE_MB;
    public static final int MAX_HEADER_COUNT = 200;

    private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);

    private static final Pattern BEARER_RX = Pattern.compile(
            "^\\s*[Bb]earer[ \\t]+([A-Za-z0-9\\-._~+/]+=*)\\s*$");
    private static final String BEARER_AUTHORIZATION = "Bearer";
    static final String HEADER_X_API_KEY = "X-API-Key";
    static final String HEADER_X_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";

    private final HttpClient httpClient;
    private final String targetUrl;
    private final Duration readTimeout;
    private final boolean useVirtualThreads;
    private final CachedApiKeyManager apiKeyManager;
    private final RateLimiterManager rateLimiterManager;
    private final WebUIHandler webUIHandler;
    
    public RequestHandler(ProxyConfig config) {
        this.targetUrl = config.getTargetUrl();
        this.readTimeout = Duration.ofMillis(config.getReadTimeout());
        this.useVirtualThreads = config.isVirtualThreads();
        this.apiKeyManager = CachedApiKeyManager.getInstance();
        this.rateLimiterManager = new RateLimiterManager(apiKeyManager);
        this.webUIHandler = new WebUIHandler();
        
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
        
        logger.info("Request handler initialized with target: {}", targetUrl);
    }
    
    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String path = request.getHttpURI().getPath();
        String method = request.getMethod();

        // Log incoming request
        if (logger.isDebugEnabled()) {
            logger.debug("Incoming request: {} {} from {}", method, path, request.getConnectionMetaData().getRemoteSocketAddress());
        }

        // Handle web UI routes
        if ("/index.html".equals(path) || "/generate".equals(path)) {
            return webUIHandler.handle(request, response, callback);
        }

        String apiKey = extractApiKey(request);
        if (apiKey == null || !apiKeyManager.isValidApiKey(apiKey)) {
            logger.warn("Authentication failed for request: {} {} - API key: {}", method, path,
                apiKey != null ? "invalid" : "missing");
            response.setStatus(HttpStatus.UNAUTHORIZED_401);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, TEXT_PLAIN.asString());
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, BEARER_AUTHORIZATION);
            response.write(true, ByteBuffer.wrap("Unauthorized".getBytes()), callback);
            return true;
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
            return true;
        }
        
        response.getHeaders().put(HEADER_X_RATE_LIMIT_REMAINING, String.valueOf(rateLimitResult.remainingTokens()));
        
        String validationError = validateRequestSizeLimits(request);
        if (validationError != null) {
            logger.warn("Request validation failed: {}", validationError);
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, TEXT_PLAIN.asString());
            response.write(true, ByteBuffer.wrap(validationError.getBytes()), callback);
            return true;
        }
        
        if (useVirtualThreads) {
            Thread.startVirtualThread(() -> handleRequestAsync(request, response, callback));
        } else {
            handleRequestAsync(request, response, callback);
        }
        
        return true;
    }
    
    private void handleRequestAsync(Request request, Response response, Callback callback) {
        try {
            proxyRequest(request, response, callback);
        } catch (Exception e) {
            logger.error("Error handling request", e);
            response.setStatus(HttpStatus.BAD_GATEWAY_502);
            response.write(true, ByteBuffer.wrap("Bad Gateway".getBytes()), callback);
        }
    }
    
    private void proxyRequest(Request request, Response response, Callback callback) {
        try {
            String method = request.getMethod();
            String path = Request.getPathInContext(request);
            String queryString = request.getHttpURI().getQuery();
            String fullPath = queryString != null ? path + "?" + queryString : path;
            String targetUri = targetUrl + fullPath;

            if (logger.isDebugEnabled()) {
                logger.debug("Proxying {} {} to {}", method, fullPath, targetUri);
                request.getHeaders().forEach(field ->
                    logger.debug("Request header: {}: {}", field.getName(), field.getValue()));
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUri))
                .timeout(readTimeout);
            forwardAllNonRestrictedHeaders(request, requestBuilder);
            forwardBody(request, method, requestBuilder);
            HttpRequest httpRequest = requestBuilder.build();

            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
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

    private void forwardBody(Request request, String method, HttpRequest.Builder requestBuilder) {
        if (hasBody(method)) {
            // For debugging: optionally log request body (only in TRACE mode to avoid performance impact)
            if (logger.isTraceEnabled()) {
                try {
                    byte[] bodyBytes = IOUtils.toByteArray(Content.Source.asInputStream(request));
                    String bodyStr = new String(bodyBytes, 0, Math.min(bodyBytes.length, 1000));
                    logger.trace("Request body (first 1000 chars): {}", bodyStr);
                    // Use the captured bytes
                    requestBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
                    return;
                } catch (IOException e) {
                    logger.warn("Failed to log request body", e);
                }
            }

            HttpRequest.BodyPublisher streamingBodyPublisher = HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    InputStream requestInputStream = Content.Source.asInputStream(request);
                    return BoundedInputStream.builder()
                            .setInputStream(requestInputStream)
                            .setMaxCount(MAX_PAYLOAD_SIZE_BYTES)
                            .get();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to get InputStream from request", e);
                }
            });
            requestBuilder.method(method, streamingBodyPublisher);
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

    private void forwardResponse(Response response, Callback callback, HttpResponse<InputStream> httpResponse) {
        try {
            response.setStatus(httpResponse.statusCode());
            
            httpResponse.headers().map().forEach((name, values) -> {
                if (!name.equalsIgnoreCase(CONNECTION.asString()) &&
                    !name.equalsIgnoreCase(TRANSFER_ENCODING.asString())) {
                    for (String value : values) {
                        response.getHeaders().add(name, value);
                    }
                }
            });

            try (InputStream responseBodyStream = httpResponse.body()) {
                IOUtils.copy(responseBodyStream, Content.Sink.asOutputStream(response));
                callback.succeeded();
            } catch (IOException e) {
                logger.error("Error streaming response body to client", e);
                callback.failed(e);
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
    
    private static Set<String> parseConnectionTokens(HttpField connectionField) {
        String connectionHeader = connectionField != null ? connectionField.getValue() : null;
        if (connectionHeader == null || connectionHeader .isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(connectionHeader.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
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
}