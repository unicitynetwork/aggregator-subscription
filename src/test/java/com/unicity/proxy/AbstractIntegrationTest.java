package com.unicity.proxy;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.http.HttpHeader.AUTHORIZATION;
import static org.eclipse.jetty.http.HttpStatus.*;

public abstract class AbstractIntegrationTest {
    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

    protected final String defaultApiKey = "supersecret";

    protected Server mockServer;
    protected ProxyServer proxyServer;
    protected int proxyPort;
    protected HttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
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

    protected HttpRequest.Builder getAuthorizedRequestBuilder(String urlPath) {
        return getAuthorizedRequestBuilder(urlPath, defaultApiKey);
    }
    
    protected HttpRequest.Builder getAuthorizedRequestBuilder(String urlPath, String apiKey) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxyPort + urlPath))
                .header(AUTHORIZATION.asString(), "Bearer " + apiKey);
    }
}
