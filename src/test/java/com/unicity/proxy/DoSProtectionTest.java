package com.unicity.proxy;

import com.unicity.proxy.testparameterization.AuthMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE;
import static org.eclipse.jetty.http.HttpStatus.*;

@ParameterizedClass
@EnumSource(AuthMode.class)
class DoSProtectionTest extends AbstractIntegrationTest {
    @Parameter
    AuthMode authMode;

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

        assertThat(response.statusCode()).isEqualTo(BAD_REQUEST_400);
        assertThat(response.body()).contains("Request body too large");
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

        assertThat(response.statusCode()).isEqualTo(OK_200);
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

        assertThat(response.statusCode()).isEqualTo(OK_200);
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
        
        assertThat(response.statusCode()).isEqualTo(BAD_REQUEST_400);
        assertThat(response.body()).contains("Too many headers");
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
        assertThat(response.statusCode()).isEqualTo(REQUEST_HEADER_FIELDS_TOO_LARGE_431);
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
        assertThat(response.statusCode()).isEqualTo(REQUEST_HEADER_FIELDS_TOO_LARGE_431);
    }
    
    @Test
    @DisplayName("Should accept request with maximum allowed headers")
    void testMaxAllowedHeaders() throws Exception {
        HttpRequest.Builder requestBuilder = getAuthorizedRequestBuilder("/test");
        
        for (int i = 0; i < RequestHandler.MAX_HEADER_COUNT - 10; i++) {
            requestBuilder.header("X-Custom-Header-" + i, "value");
        }
        
        HttpRequest request = requestBuilder
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }

    private static void fillWithSomething(byte[] largePayload) {
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }
    }
}