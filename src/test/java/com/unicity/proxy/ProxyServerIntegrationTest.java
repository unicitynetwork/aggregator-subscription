package com.unicity.proxy;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE;
import static org.eclipse.jetty.http.HttpHeader.WWW_AUTHENTICATE;
import static org.eclipse.jetty.http.HttpStatus.*;
import static org.eclipse.jetty.http.MimeTypes.Type.APPLICATION_JSON;

/**
 * The tests start a real mock server and a real proxy server to test end-to-end functionality over HTTP.
 */
class ProxyServerIntegrationTest extends AbstractIntegrationTest {
    @Test
    @DisplayName("Should proxy GET requests correctly")
    void testGetRequest() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/test")
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
        HttpRequest request = getNotAuthorizedRequestBuilder("/api/data")
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
        HttpRequest request = getNotAuthorizedRequestBuilder("/headers")
            .header("X-Custom-Header", "test-value")
            .header("X-Another-Header", "another-value")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("\"X-Custom-Header\":\"test-value\"");
        assertThat(response.body()).contains("\"X-Another-Header\":\"another-value\"");
    }
    
    @Test
    @DisplayName("Should handle 404 responses")
    void testNotFoundResponse() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/not-found")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(NOT_FOUND_404);
        assertThat(response.body()).isEqualTo("Not Found");
    }
    
    @Test
    @DisplayName("Should handle server errors")
    void testServerError() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/error")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR_500);
        assertThat(response.body()).isEqualTo("Internal Server Error");
    }
    
    @Test
    @DisplayName("Should handle PUT requests")
    void testPutRequest() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/api/update")
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
        HttpRequest request = getNotAuthorizedRequestBuilder("/api/delete/123")
            .DELETE()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(NO_CONTENT_204);
        assertThat(response.body()).isEmpty();
    }
    
    @Test
    @DisplayName("Should handle query parameters")
    void testQueryParameters() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/search?q=test&limit=10")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("\"query\":\"q=test&limit=10\"");
    }
    
    @Test
    @DisplayName("Should reject protected JSON-RPC requests without authentication")
    void testProtectedJsonRpcRequiresAuth() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
                getNotAuthorizedRequestBuilder("/"),
                SUBMIT_COMMITMENT_REQUEST);
        
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED_401);
        assertThat(response.body()).isEqualTo("Unauthorized");
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE.asString())).isPresent()
            .hasValue("Bearer");
    }
    
    @Test
    @DisplayName("Should accept protected JSON-RPC requests with valid Bearer token")
    void testBearerTokenAuth() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/"),
                SUBMIT_COMMITMENT_REQUEST);
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }
    
    @Test
    @DisplayName("Should accept protected JSON-RPC requests with valid X-API-Key header")
    void testApiKeyHeaderAuth() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/")
            .header(RequestHandler.HEADER_X_API_KEY, defaultApiKey)
            .header(CONTENT_TYPE.asString(), APPLICATION_JSON.asString())
            .POST(ofString(SUBMIT_COMMITMENT_REQUEST))
            .build();
        
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
        HttpRequest request = getNotAuthorizedRequestBuilder("/test")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("Hello from mock server");
    }
    
    @Test
    @DisplayName("Should allow unprotected JSON-RPC methods without authentication")
    void testUnprotectedJsonRpcAllowed() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
                getNotAuthorizedRequestBuilder("/"),
                GET_INCLUSION_PROOF_REQUEST);
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }
    
    @Test
    @DisplayName("Should not forward authentication headers to target server for authenticated JSON-RPC")
    void testAuthHeadersNotForwardedForJsonRpc() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/headers")
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
        HttpRequest request = getAuthorizedRequestBuilder("/headers")
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
        HttpRequest request = getNotAuthorizedRequestBuilder("/test")
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