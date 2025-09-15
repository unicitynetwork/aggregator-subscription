package com.unicity.proxy;

import com.unicity.proxy.testparameterization.AuthMode;
import io.github.bucket4j.TimeMeter;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.http.HttpHeader.AUTHORIZATION;
import static org.eclipse.jetty.http.HttpStatus.*;

public abstract class AbstractIntegrationTest {

    protected static final String SUBMIT_COMMITMENT_REQUEST = """
        {
            "jsonrpc": "2.0",
            "method": "submit_commitment",
            "params": {
                "requestId": "test123"
            },
            "id": 1
        }
        """;

    protected static final String GET_INCLUSION_PROOF_REQUEST = """
        {
            "jsonrpc": "2.0",
            "method": "get_inclusion_proof",
            "params": {
                "requestId": "test123"
            },
            "id": 1
        }
        """;

    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

    protected final String defaultApiKey = "supersecret";

    protected Server mockServer;
    protected ProxyServer proxyServer;
    protected int proxyPort;
    protected HttpClient httpClient;

    protected TestTimeMeter testTimeMeter;

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
        mockServer = createMockServer();
        mockServer.start();
        int mockServerPort = ((ServerConnector) mockServer.getConnectors()[0]).getLocalPort();

        ProxyConfig config = new ProxyConfig();
        config.setPort(0); // Use random port
        config.setTargetUrl("http://localhost:" + mockServerPort);

        proxyServer = new ProxyServer(config);
        proxyServer.start();

        proxyPort = ((ServerConnector) proxyServer.getServer().getConnectors()[0]).getLocalPort();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> isServerReady(proxyPort));

        testTimeMeter = new TestTimeMeter();
        getRateLimiterManager().setTimeMeter(testTimeMeter);
        getRateLimiterManager().setBucketFactory(apiKeyInfo ->
                RateLimiterManager.createBucketWithTimeMeter(apiKeyInfo, testTimeMeter));
        CachedApiKeyManager.getInstance().setTimeMeter(testTimeMeter);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (proxyServer != null) {
            proxyServer.stop();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    private Server createMockServer() {
        Server server = new Server(0); // Random port
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                String path = Request.getPathInContext(request);
                String method = request.getMethod();

                // Add custom header to all responses
                response.getHeaders().put("X-Mock-Server", "true");

                switch (path) {
                    case "/test" -> {
                        response.setStatus(OK_200);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                        String body = "{\"message\":\"Hello from mock server\",\"path\":\"" + path + "\"}";
                        response.write(true, ByteBuffer.wrap(body.getBytes()), callback);
                    }
                    case "/api/data" -> {
                        byte[] requestBody = Content.Source.asByteBuffer(request).array();
                        response.setStatus(CREATED_201);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                        String body = "{\"method\":\"" + method + "\",\"received\":" + requestBody.length + "}";
                        response.write(true, ByteBuffer.wrap(body.getBytes()), callback);
                    }
                    case "/headers" -> {
                        response.setStatus(OK_200);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                        StringBuilder headers = new StringBuilder("{");
                        request.getHeaders().forEach(field -> {
                            if (field.getName().startsWith("X-")) {
                                if (headers.length() > 1) headers.append(",");
                                headers.append("\"").append(field.getName()).append("\":\"")
                                        .append(field.getValue()).append("\"");
                            }
                        });
                        headers.append("}");
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
                        byte[] requestBody = Content.Source.asByteBuffer(request).array();
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
                .uri(URI.create("http://localhost:" + proxyPort + urlPath));
    }

    protected HttpResponse<String> performJsonRpcRequest(HttpRequest.Builder httpRequestBuilder, String httpBody) throws IOException, InterruptedException {
        HttpRequest request = httpRequestBuilder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(httpBody))
                .timeout(Duration.ofSeconds(5))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private RateLimiterManager getRateLimiterManager() {
        return ((RequestHandler) proxyServer.getServer().getHandler()).getRateLimiterManager();
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

        public void addTime(long millis) {
            currentTime.addAndGet(millis);
        }

        public void setTime(long millis) {
            currentTime.set(millis);
        }
    }
}
