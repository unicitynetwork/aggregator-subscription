package com.unicity.proxy;

import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.*;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE;
import static org.junit.jupiter.api.Assertions.*;

class DoSProtectionTest extends AbstractIntegrationTest {
    @Test
    @DisplayName("Should reject request with content length exceeding limit")
    void testRejectLargeContentLength() throws Exception {
        byte[] largePayload = new byte[RequestHandler.MAX_PAYLOAD_SIZE_BYTES + 1];
        fillWithSomething(largePayload);

        HttpRequest request = getAuthorizedRequestBuilder("/test")
                .header(CONTENT_TYPE.asString(), APPLICATION_OCTET_STREAM)
                .POST(ofByteArray(largePayload))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpStatus.BAD_REQUEST_400, response.statusCode());
        assertTrue(response.body().contains("Request body too large"));
    }

    @Test
    @DisplayName("Should accept request with Content-Length within limit")
    void testAcceptNormalContentLength() throws Exception {
        String payload = "This is a normal sized request body";

        HttpRequest request = getAuthorizedRequestBuilder("/test")
                .header(CONTENT_TYPE.asString(), APPLICATION_OCTET_STREAM)
                .POST(ofString(payload))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpStatus.OK_200, response.statusCode());
    }

    @Test
    @DisplayName("Should handle request at maximum allowed size")
    void testMaxAllowedSize() throws Exception {
        int size = RequestHandler.MAX_PAYLOAD_SIZE_BYTES;
        byte[] largePayload = new byte[size];
        fillWithSomething(largePayload);

        HttpRequest request = getAuthorizedRequestBuilder("/test")
                .header(CONTENT_TYPE.asString(), APPLICATION_OCTET_STREAM)
                .POST(ofByteArray(largePayload))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpStatus.OK_200, response.statusCode());
    }

    @Test
    @DisplayName("Should reject request with too many headers")
    void testRejectTooManyHeaders() throws Exception {
        HttpRequest.Builder requestBuilder = getAuthorizedRequestBuilder("/test");
        
        for (int i = 0; i < RequestHandler.MAX_HEADER_COUNT; i++) {
            requestBuilder.header("X-Custom-Header-" + i, "value");
        }
        
        HttpRequest request = requestBuilder
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(HttpStatus.BAD_REQUEST_400, response.statusCode());
        assertTrue(response.body().contains("Too many headers"));
    }
    
    @Test
    @DisplayName("Should reject request with header name too long")
    void testRejectLongHeaderName() throws Exception {
        String longHeaderName = "X-" + "A".repeat(9000); // Exceeds Jetty's 8KB limit
        
        HttpRequest request = getAuthorizedRequestBuilder("/test")
                .header(longHeaderName, "value")
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431, response.statusCode());
    }
    
    @Test
    @DisplayName("Should reject request with header value too long")
    void testRejectLongHeaderValue() throws Exception {
        String longHeaderValue = "A".repeat(9000); // Exceeds 8192 byte limit
        
        HttpRequest request = getAuthorizedRequestBuilder("/test")
                .header("X-Custom-Header", longHeaderValue)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431, response.statusCode());
    }
    
    @Test
    @DisplayName("Should accept request with maximum allowed headers")
    void testMaxAllowedHeaders() throws Exception {
        HttpRequest.Builder requestBuilder = getAuthorizedRequestBuilder("/test");
        
        for (int i = 0; i < RequestHandler.MAX_HEADER_COUNT - 10 ; i++) {
            requestBuilder.header("X-Custom-Header-" + i, "value");
        }
        
        HttpRequest request = requestBuilder
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(HttpStatus.OK_200, response.statusCode());
    }

    private static void fillWithSomething(byte[] largePayload) {
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }
    }
}