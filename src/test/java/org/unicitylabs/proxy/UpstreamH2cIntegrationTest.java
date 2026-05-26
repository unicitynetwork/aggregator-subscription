package org.unicitylabs.proxy;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpStatus.CREATED_201;

class UpstreamH2cIntegrationTest extends AbstractIntegrationTest {

    @Override
    protected void setUpConfigForTests(ProxyConfig config) {
        try {
            mockServer.stop();
            mockServer = createH2cOnlyMockServer();
            mockServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start h2c mock server", e);
        }

        int mockServerPort = ((ServerConnector) mockServer.getConnectors()[0]).getLocalPort();
        config.setPort(0);
        config.setUpstreamH2cEnabled(true);
        config.setUpstreamH2cMaxConnectionsPerDestination(2);
        config.setUpstreamH2cMaxQueuedRequestsPerDestination(100);
        setUpSingleShardAggregatorUrl("http://localhost:" + mockServerPort);

        String testTokenTypesUrl = getClass().getResource("/test-token-types.json").toString();
        config.setTokenTypeIdsUrl(testTokenTypesUrl);
    }

    @Test
    void proxiesRequestsToH2cUpstream() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/api/data")
            .header(HttpHeader.CONTENT_TYPE.asString(), "application/json")
            .POST(ofString("{\"name\":\"h2c\"}"))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(CREATED_201);
        assertThat(response.headers().firstValue("X-H2C-Upstream")).hasValue("true");
        assertThat(response.body()).contains("\"method\":\"POST\"");
    }

    private Server createH2cOnlyMockServer() {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        ServerConnector connector = new ServerConnector(server, new HTTP2CServerConnectionFactory(httpConfig));
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                ByteBuffer bodyBuffer = Content.Source.asByteBuffer(request);
                int bodyLength = bodyBuffer.remaining();

                response.setStatus(CREATED_201);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                response.getHeaders().put("X-H2C-Upstream", "true");
                String body = "{\"method\":\"" + request.getMethod() + "\",\"received\":" + bodyLength + "}";
                response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
                return true;
            }
        });
        return server;
    }
}
