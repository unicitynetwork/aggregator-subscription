package org.unicitylabs.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpStatus.NO_CONTENT_204;

/**
 * Support class for testing CORS functionality across different handlers.
 * Provides reusable methods for CORS preflight and regular response testing.
 */
public class CorsTestSupport {

    private final HttpClient httpClient;
    private final String baseUrl;

    public CorsTestSupport(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    /**
     * Tests that a CORS preflight OPTIONS request returns proper headers.
     *
     * @param path the path to test
     * @param origin the Origin header value to send
     */
    public void assertCorsPreflightRequest(String path, String origin) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "Content-Type, Authorization")
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(NO_CONTENT_204);
        assertCorsHeaders(response, origin);
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods"))
            .isPresent()
            .hasValueSatisfying(value -> assertThat(value).contains("POST"));
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers"))
            .isPresent()
            .hasValueSatisfying(value -> {
                assertThat(value).contains("Content-Type");
                assertThat(value).contains("Authorization");
            });
        assertThat(response.headers().firstValue("Access-Control-Max-Age"))
            .isPresent();
    }

    /**
     * Tests that a regular GET request includes proper CORS headers.
     *
     * @param path the path to test
     * @param origin the Origin header value to send
     * @return the HTTP response for additional assertions
     */
    public HttpResponse<String> assertCorsHeadersInGetResponse(String path, String origin) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Origin", origin)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertCorsHeaders(response, origin);
        return response;
    }

    /**
     * Asserts that the response contains the expected CORS headers.
     *
     * @param response the HTTP response to check
     * @param expectedOrigin the expected value of Access-Control-Allow-Origin
     */
    public void assertCorsHeaders(HttpResponse<String> response, String expectedOrigin) {
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
            .isPresent()
            .hasValue(expectedOrigin);
    }
}
