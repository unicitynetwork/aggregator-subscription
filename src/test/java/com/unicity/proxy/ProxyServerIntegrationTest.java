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
        HttpRequest request = getAuthorizedRequestBuilder("/test")
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
        HttpRequest request = getAuthorizedRequestBuilder("/api/data")
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
        HttpRequest request = getAuthorizedRequestBuilder("/headers")
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
        HttpRequest request = getAuthorizedRequestBuilder("/not-found")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(NOT_FOUND_404);
        assertThat(response.body()).isEqualTo("Not Found");
    }
    
    @Test
    @DisplayName("Should handle server errors")
    void testServerError() throws Exception {
        HttpRequest request = getAuthorizedRequestBuilder("/error")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR_500);
        assertThat(response.body()).isEqualTo("Internal Server Error");
    }
    
    @Test
    @DisplayName("Should handle PUT requests")
    void testPutRequest() throws Exception {
        HttpRequest request = getAuthorizedRequestBuilder("/api/update")
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
        HttpRequest request = getAuthorizedRequestBuilder("/api/delete/123")
            .DELETE()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(NO_CONTENT_204);
        assertThat(response.body()).isEmpty();
    }
    
    @Test
    @DisplayName("Should handle query parameters")
    void testQueryParameters() throws Exception {
        HttpRequest request = getAuthorizedRequestBuilder("/search?q=test&limit=10")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("\"query\":\"q=test&limit=10\"");
    }
    
    @Test
    @DisplayName("Should reject requests without authentication")
    void testAuthenticationRequired() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + proxyPort + "/test"))
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED_401);
        assertThat(response.body()).isEqualTo("Unauthorized");
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE.asString())).isPresent()
            .hasValue("Bearer");
    }
    
    @Test
    @DisplayName("Should accept requests with valid Bearer token")
    void testBearerTokenAuth() throws Exception {
        HttpRequest request = getAuthorizedRequestBuilder("/test")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("Hello from mock server");
    }
    
    @Test
    @DisplayName("Should accept requests with valid X-API-Key header")
    void testApiKeyHeaderAuth() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + proxyPort + "/test"))
            .header(RequestHandler.HEADER_X_API_KEY, defaultApiKey)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).contains("Hello from mock server");
    }

    @Test
    @DisplayName("Should reject requests with invalid Bearer token")
    void testInvalidBearerToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + proxyPort + "/test"))
            .header("Authorization", "Bearer wrongtoken")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED_401);
        assertThat(response.body()).isEqualTo("Unauthorized");
    }
    
    @Test
    @DisplayName("Should not forward authentication headers to target server")
    void testAuthHeadersNotForwarded() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + proxyPort + "/headers"))
            .header("Authorization", "Bearer " + defaultApiKey)
            .header(RequestHandler.HEADER_X_API_KEY, defaultApiKey)
            .header("X-Custom-Header", "should-be-forwarded")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);

        assertThat(response.body()).contains("\"X-Custom-Header\":\"should-be-forwarded\"");

        assertThat(response.body()).doesNotContain("Authorization");
        assertThat(response.body()).doesNotContain(RequestHandler.HEADER_X_API_KEY);
    }
}