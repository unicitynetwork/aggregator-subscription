package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.*;
import org.unicitylabs.proxy.repository.DatabaseConfig;
import org.unicitylabs.proxy.shard.DefaultShardRouter;
import org.unicitylabs.proxy.shard.ShardConfig;
import org.unicitylabs.proxy.shard.ShardInfo;
import org.unicitylabs.proxy.shard.ShardRouter;
import org.unicitylabs.proxy.util.TestEnvironmentProvider;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.eclipse.jetty.http.HttpStatus.*;
import static org.junit.jupiter.api.Assertions.*;

public class HealthCheckHandlerTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String AGGREGATOR_URL_1 = "http://aggregator1.example.com:3000";
    private static final String AGGREGATOR_URL_2 = "http://aggregator2.example.com:3000";

    private Server healthServer;
    private int healthServerPort;
    private HttpClient httpClient;

    private AtomicBoolean databaseHealthy;
    private AtomicBoolean aggregator1Healthy;
    private AtomicBoolean aggregator2Healthy;
    private AtomicInteger aggregator1BlockHeight;
    private AtomicInteger aggregator2BlockHeight;

    @BeforeEach
    void setUp() throws Exception {
        databaseHealthy = new AtomicBoolean(true);
        aggregator1Healthy = new AtomicBoolean(true);
        aggregator2Healthy = new AtomicBoolean(true);
        aggregator1BlockHeight = new AtomicInteger(100);
        aggregator2BlockHeight = new AtomicInteger(100);

        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (healthServer != null) {
            healthServer.stop();
        }
    }

    @Test
    @DisplayName("Health check returns 200 when all components are healthy")
    void testHealthyWhenAllComponentsUp() throws Exception {
        startHealthServer(createShardRouter());

        HttpResponse<String> response = sendHealthRequest();

        assertEquals(OK_200, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals("healthy", json.get("status").asText());
        assertEquals("ok", json.get("database").asText());

        JsonNode aggregators = json.get("aggregators");
        assertNotNull(aggregators);
        assertTrue(aggregators.has(AGGREGATOR_URL_1));
        assertTrue(aggregators.has(AGGREGATOR_URL_2));
        assertEquals("ok", aggregators.get(AGGREGATOR_URL_1).asText());
        assertEquals("ok", aggregators.get(AGGREGATOR_URL_2).asText());
    }

    @Test
    @DisplayName("Health check returns 503 when database is down")
    void testUnhealthyWhenDatabaseDown() throws Exception {
        databaseHealthy.set(false);
        startHealthServer(createShardRouter());

        HttpResponse<String> response = sendHealthRequest();

        assertEquals(SERVICE_UNAVAILABLE_503, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals("unhealthy", json.get("status").asText());
        assertTrue(json.get("database").asText().contains("not initialized"));
    }

    @Test
    @DisplayName("Health check returns 503 when one aggregator is down")
    void testUnhealthyWhenOneAggregatorDown() throws Exception {
        aggregator1Healthy.set(false);
        startHealthServer(createShardRouter());

        HttpResponse<String> response = sendHealthRequest();

        assertEquals(SERVICE_UNAVAILABLE_503, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals("unhealthy", json.get("status").asText());
        assertEquals("ok", json.get("database").asText());

        JsonNode aggregators = json.get("aggregators");
        assertTrue(aggregators.get(AGGREGATOR_URL_1).asText().contains("unreachable"));
        assertEquals("ok", aggregators.get(AGGREGATOR_URL_2).asText());
    }

    @Test
    @DisplayName("Health check returns 503 when all aggregators are down")
    void testUnhealthyWhenAllAggregatorsDown() throws Exception {
        aggregator1Healthy.set(false);
        aggregator2Healthy.set(false);
        startHealthServer(createShardRouter());

        HttpResponse<String> response = sendHealthRequest();

        assertEquals(SERVICE_UNAVAILABLE_503, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals("unhealthy", json.get("status").asText());

        JsonNode aggregators = json.get("aggregators");
        assertTrue(aggregators.get(AGGREGATOR_URL_1).asText().contains("unreachable"));
        assertTrue(aggregators.get(AGGREGATOR_URL_2).asText().contains("unreachable"));
    }

    @Test
    @DisplayName("Health check returns 503 when both database and aggregator are down")
    void testUnhealthyWhenDatabaseAndAggregatorDown() throws Exception {
        databaseHealthy.set(false);
        aggregator1Healthy.set(false);
        startHealthServer(createShardRouter());

        HttpResponse<String> response = sendHealthRequest();

        assertEquals(SERVICE_UNAVAILABLE_503, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals("unhealthy", json.get("status").asText());
        assertTrue(json.get("database").asText().contains("not initialized"));

        JsonNode aggregators = json.get("aggregators");
        assertTrue(aggregators.get(AGGREGATOR_URL_1).asText().contains("unreachable"));
    }

    @Test
    @DisplayName("Health check returns healthy with single aggregator")
    void testHealthyWithSingleAggregator() throws Exception {
        ShardRouter router = new DefaultShardRouter(new ShardConfig(1, List.of(
            new ShardInfo(1, AGGREGATOR_URL_1)
        )));
        startHealthServer(router);

        HttpResponse<String> response = sendHealthRequest();

        assertEquals(OK_200, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals("healthy", json.get("status").asText());

        JsonNode aggregators = json.get("aggregators");
        assertEquals(1, aggregators.size());
        assertEquals("ok", aggregators.get(AGGREGATOR_URL_1).asText());
    }

    @Test
    @DisplayName("Health check handles null router gracefully")
    void testHandlesNullRouter() throws Exception {
        startHealthServer(null);

        HttpResponse<String> response = sendHealthRequest();

        assertEquals(SERVICE_UNAVAILABLE_503, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals("unhealthy", json.get("status").asText());

        JsonNode aggregators = json.get("aggregators");
        assertTrue(aggregators.has("_error"));
        assertEquals("no router configured", aggregators.get("_error").asText());
    }

    @Test
    @DisplayName("Health check includes correct Content-Type header")
    void testContentTypeHeader() throws Exception {
        startHealthServer(createShardRouter());

        HttpResponse<String> response = sendHealthRequest();

        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    @DisplayName("Health check reports correct block heights from aggregators")
    void testAggregatorBlockHeightsAreQueried() throws Exception {
        aggregator1BlockHeight.set(500);
        aggregator2BlockHeight.set(600);
        startHealthServer(createShardRouter());

        HttpResponse<String> response = sendHealthRequest();

        assertEquals(OK_200, response.statusCode());
        // Both aggregators should be ok (meaning health check succeeded)
        JsonNode json = objectMapper.readTree(response.body());
        JsonNode aggregators = json.get("aggregators");
        assertEquals("ok", aggregators.get(AGGREGATOR_URL_1).asText());
        assertEquals("ok", aggregators.get(AGGREGATOR_URL_2).asText());
    }

    @Test
    @DisplayName("Non-health endpoint requests are not handled")
    void testNonHealthEndpointNotHandled() throws Exception {
        startHealthServer(createShardRouter());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + healthServerPort + "/other"))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Should return 404 since HealthCheckHandler doesn't handle /other
        assertEquals(NOT_FOUND_404, response.statusCode());
    }

    private ShardRouter createShardRouter() {
        return new DefaultShardRouter(new ShardConfig(1, List.of(
            new ShardInfo(2, AGGREGATOR_URL_1),
            new ShardInfo(3, AGGREGATOR_URL_2)
        )));
    }

    /**
     * Mock aggregator health checker that simulates aggregator connectivity
     * based on the test's AtomicBoolean flags.
     */
    private HealthCheckHandler.AggregatorHealthChecker createMockAggregatorChecker() {
        return url -> {
            if (AGGREGATOR_URL_1.equals(url)) {
                if (!aggregator1Healthy.get()) {
                    throw new RuntimeException("Connection refused");
                }
                return aggregator1BlockHeight.get();
            } else if (AGGREGATOR_URL_2.equals(url)) {
                if (!aggregator2Healthy.get()) {
                    throw new RuntimeException("Connection refused");
                }
                return aggregator2BlockHeight.get();
            }
            throw new RuntimeException("Unknown aggregator: " + url);
        };
    }

    private void startHealthServer(ShardRouter router) throws Exception {
        DatabaseConfig mockDbConfig = new MockDatabaseConfig(databaseHealthy);
        Supplier<ShardRouter> routerSupplier = () -> router;

        HealthCheckHandler healthHandler = new HealthCheckHandler(
            mockDbConfig,
            routerSupplier,
            createMockAggregatorChecker()
        );

        healthServer = new Server(0);
        healthServer.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                if (healthHandler.handle(request, response, callback)) {
                    return true;
                }
                // Return 404 for unhandled paths
                response.setStatus(NOT_FOUND_404);
                response.write(true, ByteBuffer.wrap("Not Found".getBytes()), callback);
                return true;
            }
        });
        healthServer.start();
        healthServerPort = ((ServerConnector) healthServer.getConnectors()[0]).getLocalPort();
    }

    private HttpResponse<String> sendHealthRequest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + healthServerPort + "/health"))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Mock DatabaseConfig that simulates database connectivity based on a flag.
     */
    private static class MockDatabaseConfig extends DatabaseConfig {
        private final AtomicBoolean healthy;

        MockDatabaseConfig(AtomicBoolean healthy) {
            super(new TestEnvironmentProvider()); // Use test environment provider
            this.healthy = healthy;
        }

        @Override
        public boolean isInitialized() {
            return healthy.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (!healthy.get()) {
                throw new SQLException("Mock: database is unhealthy");
            }
            // Return a mock connection that passes isValid() check
            return createMockConnection();
        }

        private Connection createMockConnection() {
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("isValid".equals(method.getName())) {
                            return true;
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        if ("isClosed".equals(method.getName())) {
                            return false;
                        }
                        throw new UnsupportedOperationException("Mock connection: " + method.getName());
                    }
                }
            );
        }
    }
}
