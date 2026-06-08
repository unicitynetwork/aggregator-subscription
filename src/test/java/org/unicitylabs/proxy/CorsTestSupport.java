package org.unicitylabs.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;

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
        assertAllowsHeader(response, "Content-Type");
        assertAllowsHeader(response, "Authorization");
        // The proxy routes by the X-State-ID header, so browsers must be allowed
        // to send it on the preflight (regression: it was missing).
        assertAllowsHeader(response, "X-State-ID");
        assertThat(response.headers().firstValue("Access-Control-Max-Age"))
            .isPresent();
    }

    /**
     * Tests that a CORS preflight advertises a specific request header as allowed —
     * i.e. a browser asking permission for {@code requestedHeader} is granted it.
     *
     * @param path the path to test
     * @param origin the Origin header value to send
     * @param requestedHeader the header the client asks to send (Access-Control-Request-Headers)
     */
    public void assertCorsPreflightAllowsRequestHeader(String path, String origin, String requestedHeader)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", requestedHeader)
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(NO_CONTENT_204);
        assertCorsHeaders(response, origin);
        assertAllowsHeader(response, requestedHeader);
        // Negative control: the server advertises a FIXED list and does NOT echo
        // Access-Control-Request-Headers, so a header not in the configured list is
        // never advertised. Asserting this keeps the test from passing tautologically.
        assertDoesNotAllowHeader(response, "X-Definitely-Not-Allowed");
    }

    /**
     * Performs a CORS preflight OPTIONS and returns the response for custom assertions.
     */
    public HttpResponse<String> sendPreflight(String path, String origin) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Splits {@code Access-Control-Allow-Headers} into trimmed, lower-cased entries.
     * HTTP header field-names are case-insensitive, so callers compare on this list
     * rather than substring-matching the raw value (which false-positives, e.g.
     * "State-ID" inside "X-State-ID").
     */
    private static List<String> allowedHeaderEntries(HttpResponse<String> response) {
        return response.headers().firstValue("Access-Control-Allow-Headers")
            .map(value -> Arrays.stream(value.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .toList())
            .orElseThrow(() -> new AssertionError("Access-Control-Allow-Headers header is absent"));
    }

    /**
     * Asserts {@code Access-Control-Allow-Headers} advertises {@code header} as a distinct entry.
     */
    public void assertAllowsHeader(HttpResponse<String> response, String header) {
        assertThat(allowedHeaderEntries(response)).contains(header.toLowerCase());
    }

    /**
     * Asserts {@code Access-Control-Allow-Headers} does NOT advertise {@code header}.
     */
    public void assertDoesNotAllowHeader(HttpResponse<String> response, String header) {
        assertThat(allowedHeaderEntries(response)).doesNotContain(header.toLowerCase());
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
