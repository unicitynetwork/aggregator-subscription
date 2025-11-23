package org.unicitylabs.proxy;

import org.unicitylabs.proxy.repository.PricingPlanRepository;
import org.unicitylabs.proxy.shard.ShardConfig;
import org.unicitylabs.proxy.shard.ShardInfo;
import org.unicitylabs.proxy.testparameterization.AuthMode;
import org.unicitylabs.proxy.util.EnvironmentProvider;
import io.github.bucket4j.TimeMeter;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.Callback;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.unicitylabs.proxy.util.TestEnvironmentProvider;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.unicitylabs.proxy.testparameterization.AuthMode.AUTHORIZED;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.http.HttpHeader.AUTHORIZATION;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE;
import static org.eclipse.jetty.http.HttpStatus.*;

public abstract class AbstractIntegrationTest {
    protected static final byte[] SERVER_SECRET = {0, 1, 2, 3};

    protected static final PricingPlanRepository.PricingPlan PLAN_BASIC =
            new PricingPlanRepository.PricingPlan(1L, "basic", 5, 50000, new BigInteger("1000000"));

    protected static final PricingPlanRepository.PricingPlan PLAN_STANDARD =
            new PricingPlanRepository.PricingPlan(2L, "standard", 10, 100000, new BigInteger("5000000"));

    protected static final PricingPlanRepository.PricingPlan PLAN_PREMIUM =
            new PricingPlanRepository.PricingPlan(3L, "premium", 20, 500000, new BigInteger("10000000"));

    protected static final PricingPlanRepository.PricingPlan PLAN_ENTERPRISE =
            new PricingPlanRepository.PricingPlan(4L, "enterprise", 50, 1000000, new BigInteger("50000000"));

    protected static final PricingPlanRepository.PricingPlan PLAN_TEST_BASIC =
            new PricingPlanRepository.PricingPlan(5L, "test-basic", 5, 50000, BigInteger.valueOf(10000));

    protected static final PricingPlanRepository.PricingPlan PLAN_TEST_STANDARD =
            new PricingPlanRepository.PricingPlan(6L, "test-standard", 10, 100000, BigInteger.valueOf(20000));

    protected static final PricingPlanRepository.PricingPlan PLAN_TEST_PREMIUM =
            new PricingPlanRepository.PricingPlan(7L, "test-premium", 20, 500000, BigInteger.valueOf(30000));

    protected static final List<PricingPlanRepository.PricingPlan> ALL_PLANS = List.of(
            PLAN_BASIC, PLAN_STANDARD, PLAN_PREMIUM, PLAN_ENTERPRISE,
            PLAN_TEST_BASIC, PLAN_TEST_STANDARD, PLAN_TEST_PREMIUM
    );

    protected static final String SUBMIT_COMMITMENT_REQUEST = """
        {
            "jsonrpc": "2.0",
            "method": "submit_commitment",
            "params": {
                "requestId": "1234"
            },
            "id": 1
        }
        """;

    protected static final String GET_INCLUSION_PROOF_REQUEST = """
        {
            "jsonrpc": "2.0",
            "method": "get_inclusion_proof",
            "params": {
                "requestId": "1234"
            },
            "id": 1
        }
        """;

    private static final String JSON_RPC_TEMPLATE = """
        {
            "jsonrpc": "2.0",
            "method": "submit_commitment",
            "params": {
                "requestId": "1234",
                "data": "PLACEHOLDER"
            },
            "id": 1
        }
        """;

    private static final String REGULAR_CONTENT_TEMPLATE = "PLACEHOLDER";

    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

    protected final String defaultApiKey = "supersecret";

    protected Server mockServer;
    protected ProxyServer proxyServer;
    protected int proxyPort;
    protected HttpClient httpClient;

    protected TestTimeMeter testTimeMeter;

    protected RootTrustBase trustBase;

    protected volatile int mockResponseStatus;
    protected volatile String mockResponseBody;
    protected volatile boolean mockShouldReturnError;
    protected ProxyConfig config;

    @BeforeAll
    static void setUpDatabase() {
        TestDatabaseSetup.setupTestDatabase();
        TestPricingPlans.createTestPlans();
    }
    
    @AfterAll
    static void tearDownDatabase() {
        TestPricingPlans.deleteTestPlansAndTheirApiKeys();
        TestDatabaseSetup.teardownTestDatabase();
    }

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseSetup.resetDatabase();

        this.trustBase = UnicityObjectMapper.JSON.readValue(
                getClass().getResourceAsStream("/test-trust-base.json"),
                RootTrustBase.class
        );

        mockResponseStatus = OK_200;
        mockResponseBody = null;
        mockShouldReturnError = false;
        
        mockServer = createMockServer();
        mockServer.start();

        config = new ProxyConfig(new TestEnvironmentProvider());
        setUpConfigForTests(config);

        setUpNewProxyServer();
    }

    protected void setUpNewProxyServer() throws Exception {
        proxyServer = new ProxyServer(config, SERVER_SECRET, new TestEnvironmentProvider(), TestDatabaseSetup.getDatabaseConfig(), false);
        proxyServer.start();

        proxyPort = ((ServerConnector) proxyServer.getServer().getConnectors()[0]).getLocalPort();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> isServerReady(proxyPort));

        testTimeMeter = new TestTimeMeter();
        getRateLimiterManager().setBucketFactory(apiKeyInfo ->
                RateLimiterManager.createBucketWithTimeMeter(apiKeyInfo, testTimeMeter));
        CachedApiKeyManager.getInstance().setTimeMeter(testTimeMeter);

        // Set TestTimeMeter on PaymentService
        if (proxyServer != null && proxyServer.getPaymentHandler() != null) {
            proxyServer.getPaymentHandler().getPaymentService().setTimeMeter(testTimeMeter);
        }
    }

    protected void recreatePricingPlans() {
        PricingPlanRepository pricingPlanRepository = new PricingPlanRepository(TestDatabaseSetup.getDatabaseConfig());

        for (var plan : ALL_PLANS) {
            var existing = pricingPlanRepository.findById(plan.id());
            if (existing != null) {
                pricingPlanRepository.update(plan.id(), plan.name(),
                        plan.requestsPerSecond(), plan.requestsPerDay(), plan.price());
            } else {
                pricingPlanRepository.insertWithId(plan);
            }
        }

        pricingPlanRepository.updateSequenceToMax();
    }

    protected void setUpConfigForTests(ProxyConfig config) {
        int mockServerPort = ((ServerConnector) mockServer.getConnectors()[0]).getLocalPort();
        config.setPort(0); // Use random port

        setUpSingleShardAggregatorUrl("http://localhost:" + mockServerPort);

        // Use local test token types file instead of fetching from GitHub
        try {
            String testTokenTypesUrl = getClass().getResource("/test-token-types.json").toString();
            config.setTokenTypeIdsUrl(testTokenTypesUrl);
            System.out.println("Using local token types file: " + testTokenTypesUrl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load test token types file", e);
        }
    }

    protected void setUpSingleShardAggregatorUrl(String aggregatorUrl) {
        // Use suffix "1" (0 bits) to match all requests - no actual sharding in tests
        insertShardConfig(new ShardConfig(1, List.of(
            new ShardInfo(1, aggregatorUrl)
        )));
    }

    protected void insertShardConfig(ShardConfig shardConfig) {
        new org.unicitylabs.proxy.repository.ShardConfigRepository(TestDatabaseSetup.getDatabaseConfig()).saveConfig(shardConfig, "test");
    }

    @AfterEach
    void tearDown() throws Exception {
        TestDatabaseSetup.resetDatabase();

        if (proxyServer != null) {
            proxyServer.stop();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }
    
    protected void configureMockResponse(int status, String body) {
        this.mockResponseStatus = status;
        this.mockResponseBody = body;
    }
    
    protected void configureMockError(boolean shouldReturnError) {
        this.mockShouldReturnError = shouldReturnError;
        if (shouldReturnError) {
            this.mockResponseStatus = INTERNAL_SERVER_ERROR_500;
            this.mockResponseBody = "Internal Server Error";
        }
    }

    protected Server createMockServer() {
        return createMockServer("1");
    }

    protected Server createMockServer(String shardId) {
        Server server = new Server(0); // Random port
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                String path = Request.getPathInContext(request);
                String method = request.getMethod();

                // Add custom headers to all responses
                response.getHeaders().put("X-Mock-Server", "true");
                response.getHeaders().put("X-Shard-ID", shardId);
                response.getHeaders().put("X-Received-Path", path);

                // Check if we should return a configured error response
                if (mockShouldReturnError) {
                    response.setStatus(mockResponseStatus);
                    String errorBody = mockResponseBody != null ? mockResponseBody : "Error";
                    response.write(true, ByteBuffer.wrap(errorBody.getBytes()), callback);
                    return true;
                }
                
                // Check if we have a custom configured response
                if (mockResponseBody != null) {
                    response.setStatus(mockResponseStatus);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                    response.write(true, ByteBuffer.wrap(mockResponseBody.getBytes()), callback);
                    return true;
                }

                switch (path) {
                    case "/test" -> {
                        response.setStatus(OK_200);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                        String body = "{\"message\":\"Hello from mock server\",\"path\":\"" + path + "\"}";
                        response.write(true, ByteBuffer.wrap(body.getBytes()), callback);
                    }
                    case "/api/data" -> {
                        byte[] requestBody = getBodyBytes(request);
                        response.setStatus(CREATED_201);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                        String body = "{\"method\":\"" + method + "\",\"received\":" + requestBody.length + "}";
                        response.write(true, ByteBuffer.wrap(body.getBytes()), callback);
                    }
                    case "/headers" -> {
                        response.setStatus(OK_200);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                        StringBuilder headers = new StringBuilder("{");
                        StringBuilder responseHeaders = new StringBuilder(",\"responseHeaders\":{");
                        final boolean[] hasResponseHeaders = {false};
                        request.getHeaders().forEach(field -> {
                            if (field.getName().startsWith("X-")) {
                                if (headers.length() > 1) headers.append(",");
                                headers.append("\"").append(field.getName()).append("\":\"")
                                        .append(field.getValue()).append("\"");
                                // Also add to response headers for forwarding verification
                                response.getHeaders().put(field.getName(), field.getValue());
                                if (hasResponseHeaders[0]) responseHeaders.append(",");
                                responseHeaders.append("\"").append(field.getName()).append("\":\"")
                                        .append(field.getValue()).append("\"");
                                hasResponseHeaders[0] = true;
                            }
                        });
                        headers.append(responseHeaders).append("}}");
                        response.write(true, ByteBuffer.wrap(headers.toString().getBytes()), callback);
                    }
                    case "/not-found" -> {
                        response.setStatus(NOT_FOUND_404);
                        response.write(true, ByteBuffer.wrap("Not Found".getBytes()), callback);
                    }
                    case "/error" -> {
                        response.setStatus(INTERNAL_SERVER_ERROR_500);
                        response.write(true, ByteBuffer.wrap("Internal Server Error".getBytes()), callback);
                    }
                    case "/api/update" -> {
                        byte[] requestBody = getBodyBytes(request);
                        response.setStatus(OK_200);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                        String body = "{\"method\":\"" + method + "\",\"received\":" + requestBody.length + "}";
                        response.write(true, ByteBuffer.wrap(body.getBytes()), callback);
                    }
                    case "/api/delete/123" -> {
                        response.setStatus(NO_CONTENT_204);
                        callback.succeeded();
                    }
                    case "/search" -> {
                        String query = request.getHttpURI().getQuery();
                        response.setStatus(OK_200);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                        String body = "{\"query\":\"" + query + "\"}";
                        response.write(true, ByteBuffer.wrap(body.getBytes()), callback);
                    }
                    default -> {
                        response.setStatus(OK_200);
                        response.write(true, ByteBuffer.wrap("OK".getBytes()), callback);
                    }
                }

                return true;
            }
        });

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        return server;
    }

    private byte @NotNull [] getBodyBytes(Request request) throws IOException {
        ByteBuffer byteBuffer = Content.Source.asByteBuffer(request);
        byte[] requestBody = new byte[byteBuffer.remaining()];
        byteBuffer.get(requestBody);
        return requestBody;
    }

    private boolean isServerReady(int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/health-check"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    protected String getProxyUrl() {
        return "http://localhost:" + proxyPort;
    }
    
    protected HttpRequest.Builder getRequestBuilder(String urlPath, AuthMode authMode) {
        return switch (authMode) {
            case AUTHORIZED -> getAuthorizedRequestBuilder(urlPath);
            case UNAUTHORIZED -> getNotAuthorizedRequestBuilder(urlPath);
        };
    }

    protected HttpRequest.Builder getAuthorizedRequestBuilder(String urlPath) {
        return getAuthorizedRequestBuilder(urlPath, defaultApiKey);
    }
    
    protected HttpRequest.Builder getAuthorizedRequestBuilder(String urlPath, String apiKey) {
        return getNotAuthorizedRequestBuilder(urlPath)
                .header(AUTHORIZATION.asString(), "Bearer " + apiKey);
    }

    protected HttpRequest.Builder getNotAuthorizedRequestBuilder(String urlPath) {
        return HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + urlPath));
    }

    protected HttpResponse<String> performJsonRpcRequest(HttpRequest.Builder httpRequestBuilder, String httpBody) throws IOException, InterruptedException {
        HttpRequest request = httpRequestBuilder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(httpBody))
                .timeout(Duration.ofSeconds(5))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    protected RateLimiterManager getRateLimiterManager() {
        return proxyServer.getRateLimiterManager();
    }

    protected HttpResponse<String> sendRequestWithContent(int targetSize, AuthMode authMode) throws IOException, InterruptedException {
        if (authMode == AUTHORIZED) {
            String jsonBody = fillPlaceholderUpToTargetLength(targetSize, JSON_RPC_TEMPLATE, "PLACEHOLDER");

            return performJsonRpcRequest(
                    getRequestBuilder("/", authMode),
                    jsonBody);
        } else {
            String content = fillPlaceholderUpToTargetLength(targetSize, REGULAR_CONTENT_TEMPLATE, "PLACEHOLDER");

            HttpRequest request = getRequestBuilder("/test", authMode)
                    .header(CONTENT_TYPE.asString(), APPLICATION_OCTET_STREAM)
                    .POST(ofString(content))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private @NotNull String fillPlaceholderUpToTargetLength(int targetSize, String template, String placeholder) {
        int baseSize = template.length() - placeholder.length();
        int paddingSize = Math.max(0, targetSize - baseSize);
        return template.replace(placeholder, "A".repeat(paddingSize));
    }

    protected HttpResponse<String> sendRequestWithHeadersPrepared(HttpRequest.Builder requestBuilder, AuthMode authMode) throws IOException, InterruptedException {
        HttpRequest request;
        if (authMode == AUTHORIZED) {
            request = requestBuilder
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(SUBMIT_COMMITMENT_REQUEST))
                    .timeout(Duration.ofSeconds(5))
                    .build();
        } else {
            request = requestBuilder
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
        }
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static class TestTimeMeter implements TimeMeter {
        private final AtomicLong currentTime = new AtomicLong(System.currentTimeMillis());

        @Override
        public long currentTimeNanos() {
            return currentTime.get() * 1_000_000L;
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }

        public long getTime() {
            return currentTime.get();
        }

        public void addTime(long millis) {
            currentTime.addAndGet(millis);
        }

        public void setTime(long millis) {
            currentTime.set(millis);
        }
    }
}
