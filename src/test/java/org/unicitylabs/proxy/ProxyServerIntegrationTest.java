package org.unicitylabs.proxy;

import org.unicitylabs.proxy.testparameterization.AuthMode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.unicitylabs.proxy.testparameterization.AuthMode.AUTHORIZED;
import static org.unicitylabs.proxy.testparameterization.AuthMode.UNAUTHORIZED;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE;
import static org.eclipse.jetty.http.HttpHeader.WWW_AUTHENTICATE;
import static org.eclipse.jetty.http.HttpStatus.*;
import static org.eclipse.jetty.http.MimeTypes.Type.APPLICATION_JSON;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The tests start a real mock server and a real proxy server to test end-to-end functionality over HTTP.
 */
@ParameterizedClass
@EnumSource(AuthMode.class)
class ProxyServerIntegrationTest extends AbstractIntegrationTest {
    @Parameter
    AuthMode authMode;

    @Test
    @DisplayName("Should proxy GET requests correctly")
    void testGetRequest() throws Exception {
        assumeTrue(authMode == UNAUTHORIZED, "GET request proxying is tested for non-JSON-RPC requests only");
        
        HttpRequest request = getRequestBuilder("/test", authMode)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).isEqualTo("{\"message\":\"Hello from mock server\",\"path\":\"/test\"}");
        assertThat(response.headers().firstValue("X-Mock-Server")).isPresent()
            .hasValue("true");
    }

    @Test
    @DisplayName("Should proxy POST requests with body")
    void testPostRequest() throws Exception {
        HttpRequest request = getRequestBuilder("/api/data", authMode)
            .header(CONTENT_TYPE.asString(), APPLICATION_JSON.asString())
            .POST(ofString("{\"name\":\"test\",\"value\":123}"))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(CREATED_201);
        assertThat(response.body()).contains("\"received\":" + "{\"name\":\"test\",\"value\":123}".length());
        assertThat(response.body()).contains("\"method\":\"POST\"");
    }

    @Test
    @DisplayName("Should forward custom headers")
    void testHeaderForwarding() throws Exception {
        HttpRequest.Builder requestBuilder = getRequestBuilder("/headers", authMode)
            .header("X-Custom-Header", "test-value")
            .header("X-Another-Header", "another-value");
        
        HttpResponse<String> response = sendRequestWithHeadersPrepared(requestBuilder, authMode);
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        // Both auth modes should see the headers forwarded (mock now returns headers in both cases)
        assertThat(response.body()).contains("\"X-Custom-Header\":\"test-value\"");
        assertThat(response.body()).contains("\"X-Another-Header\":\"another-value\"");
    }
    
    @Test
    @DisplayName("Should handle 404 responses")
    void testNotFoundResponse() throws Exception {
        // Configure mock to return 404
        configureMockResponse(NOT_FOUND_404, "Not Found");
        
        HttpResponse<String> response;
        if (authMode == AUTHORIZED) {
            // For authorized mode, use JSON-RPC which will get the error response
            response = performJsonRpcRequest(
                    getRequestBuilder("/", authMode),
                    GET_INCLUSION_PROOF_REQUEST);
        } else {
            // For unauthorized mode, use regular GET
            HttpRequest request = getRequestBuilder("/test", authMode)
                .GET()
                .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
        
        assertThat(response.statusCode()).isEqualTo(NOT_FOUND_404);
        assertThat(response.body()).contains("Not Found");
    }
    
    @Test
    @DisplayName("Should handle server errors")
    void testServerError() throws Exception {
        // Configure mock to return 500 error
        configureMockError(true);
        
        HttpResponse<String> response;
        if (authMode == AUTHORIZED) {
            // For authorized mode, use JSON-RPC which will get the error response
            response = performJsonRpcRequest(
                    getRequestBuilder("/", authMode),
                    GET_INCLUSION_PROOF_REQUEST);
        } else {
            // For unauthorized mode, use regular GET
            HttpRequest request = getRequestBuilder("/test", authMode)
                .GET()
                .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
        
        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR_500);
        assertThat(response.body()).contains("Internal Server Error");
    }
    
    @Test
    @DisplayName("Should handle PUT requests")
    void testPutRequest() throws Exception {
        // PUT requests only make sense for non-JSON-RPC
        assumeTrue(authMode == UNAUTHORIZED, "PUT method is tested for non-JSON-RPC requests only");
        
        HttpRequest request = getRequestBuilder("/api/update", authMode)
            .header(CONTENT_TYPE.asString(), APPLICATION_JSON.asString())
            .PUT(ofString("{\"updated\":true}"))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("\"method\":\"PUT\"");
        assertThat(response.body()).contains("\"received\":" + "{\"updated\":true}".length());
    }
    
    @Test
    @DisplayName("Should handle DELETE requests")
    void testDeleteRequest() throws Exception {
        // DELETE requests only make sense for non-JSON-RPC
        assumeTrue(authMode == UNAUTHORIZED, "DELETE method is tested for non-JSON-RPC requests only");
        
        HttpRequest request = getRequestBuilder("/api/delete/123", authMode)
            .DELETE()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(NO_CONTENT_204);
        assertThat(response.body()).isEmpty();
    }
    
    @Test
    @DisplayName("Should handle query parameters")
    void testQueryParameters() throws Exception {
        // Query parameters only make sense for non-JSON-RPC
        assumeTrue(authMode == UNAUTHORIZED, "Query parameters are tested for non-JSON-RPC requests only");
        
        HttpRequest request = getRequestBuilder("/search?q=test&limit=10", authMode)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("\"query\":\"q=test&limit=10\"");
    }
    
    @Test
    @DisplayName("Should reject protected JSON-RPC requests without authentication")
    void testProtectedJsonRpcRequiresAuth() throws Exception {
        // This test is specifically about unauthorized access
        assumeTrue(authMode == UNAUTHORIZED, "Testing unauthorized access to protected methods");
        
        HttpResponse<String> response = performJsonRpcRequest(
                getRequestBuilder("/", authMode),
                SUBMIT_COMMITMENT_REQUEST);
        
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED_401);
        assertThat(response.body()).isEqualTo("Unauthorized");
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE.asString())).isPresent()
            .hasValue("Bearer");
    }
    
    @Test
    @DisplayName("Should accept protected JSON-RPC requests with valid Bearer token")
    void testBearerTokenAuth() throws Exception {
        HttpResponse<String> response;
        assumeTrue(authMode == AUTHORIZED);
        response = performJsonRpcRequest(
                getRequestBuilder("/", authMode),
                SUBMIT_COMMITMENT_REQUEST);
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }
    
    @Test
    @DisplayName("Should accept protected JSON-RPC requests with valid X-API-Key header")
    void testApiKeyHeaderAuth() throws Exception {
        // Test X-API-Key authentication for both modes
        HttpRequest request;
        if (authMode == AUTHORIZED) {
            // For authorized mode, the auth is already included via Bearer token,
            // but we can also test X-API-Key as an alternative
            request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/"))
                .header(RequestHandler.HEADER_X_API_KEY, defaultApiKey)
                .header(CONTENT_TYPE.asString(), APPLICATION_JSON.asString())
                .POST(ofString(SUBMIT_COMMITMENT_REQUEST))
                .build();
        } else {
            // For unauthorized mode, add X-API-Key header
            request = getRequestBuilder("/", authMode)
                .header(RequestHandler.HEADER_X_API_KEY, defaultApiKey)
                .header(CONTENT_TYPE.asString(), APPLICATION_JSON.asString())
                .POST(ofString(GET_INCLUSION_PROOF_REQUEST))
                .build();
        }
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }

    @Test
    @DisplayName("Should reject protected JSON-RPC requests with invalid Bearer token")
    void testInvalidBearerToken() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/")
            .header("Authorization", "Bearer wrongtoken")
            .header(CONTENT_TYPE.asString(), APPLICATION_JSON.asString())
            .POST(ofString(SUBMIT_COMMITMENT_REQUEST))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED_401);
        assertThat(response.body()).isEqualTo("Unauthorized");
    }
    
    @Test
    @DisplayName("Should allow non-JSON-RPC requests without authentication")
    void testNonJsonRpcRequestsAllowed() throws Exception {
        // This test is specifically about non-JSON-RPC requests without auth
        assumeTrue(authMode == UNAUTHORIZED, "Testing non-JSON-RPC requests without authentication");
        
        HttpRequest request = getRequestBuilder("/test", authMode)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("Hello from mock server");
    }
    
    @Test
    @DisplayName("Should allow unprotected JSON-RPC methods without authentication")
    void testUnprotectedJsonRpcAllowed() throws Exception {
        // Unprotected methods should work regardless of auth mode
        HttpResponse<String> response = performJsonRpcRequest(
                getRequestBuilder("/", authMode),
                GET_INCLUSION_PROOF_REQUEST);
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }
    
    @Test
    @DisplayName("Should not forward authentication headers to target server for authenticated JSON-RPC")
    void testAuthHeadersNotForwardedForJsonRpc() throws Exception {
        // This test verifies auth header filtering behavior
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/headers"))
            .header("Authorization", "Bearer " + defaultApiKey)
            .header(RequestHandler.HEADER_X_API_KEY, defaultApiKey)
            .header("X-Custom-Header", "should-be-forwarded")
            .header(CONTENT_TYPE.asString(), APPLICATION_JSON.asString())
            .POST(ofString(SUBMIT_COMMITMENT_REQUEST))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("\"X-Custom-Header\":\"should-be-forwarded\"");
        assertThat(response.body()).doesNotContain("Authorization");
        assertThat(response.body()).doesNotContain(RequestHandler.HEADER_X_API_KEY);
    }
    
    @Test
    @DisplayName("Should forward custom headers bidirectionally for authenticated requests")
    void testBidirectionalHeaderForwardingWithAuth() throws Exception {
        assumeTrue(authMode == AUTHORIZED, "Testing header forwarding for authenticated requests");
        
        HttpRequest request = getRequestBuilder("/headers", authMode)
            .header("X-Request-Id", "test-123")
            .header("X-Custom-Header", "custom-value")
            .header(CONTENT_TYPE.asString(), APPLICATION_JSON.asString())
            .POST(ofString(SUBMIT_COMMITMENT_REQUEST))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("\"X-Request-Id\":\"test-123\"");
        assertThat(response.body()).contains("\"X-Custom-Header\":\"custom-value\"");
        
        assertThat(response.headers().firstValue("X-Mock-Server")).isPresent()
            .hasValue("true");
        
        assertThat(response.headers().firstValue(RequestHandler.HEADER_X_RATE_LIMIT_REMAINING)).isPresent();
    }
    
    @Test
    @DisplayName("Should forward custom headers bidirectionally for non-authenticated requests")
    void testBidirectionalHeaderForwardingWithoutAuth() throws Exception {
        assumeTrue(authMode == UNAUTHORIZED, "Testing header forwarding for non-authenticated requests");
        
        HttpRequest request = getRequestBuilder("/test", authMode)
            .header("X-Request-Id", "test-456")
            .header("X-Trace-Id", "trace-789")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        
        assertThat(response.headers().firstValue("X-Mock-Server")).isPresent()
            .hasValue("true");
        
        assertThat(response.headers().firstValue(RequestHandler.HEADER_X_RATE_LIMIT_REMAINING)).isEmpty();
    }
}