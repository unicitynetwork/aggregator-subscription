package com.unicitylabs.proxy;

import com.unicitylabs.proxy.testparameterization.AuthMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.unicitylabs.proxy.testparameterization.AuthMode.AUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpStatus.*;

@ParameterizedClass
@EnumSource(AuthMode.class)
class DoSProtectionTest extends AbstractIntegrationTest {
    @Parameter
    AuthMode authMode;
    
    @Test
    @DisplayName("Should reject request with content length exceeding limit")
    void testRejectLargeContentLength() throws Exception {
        int oversizedLength = RequestHandler.MAX_PAYLOAD_SIZE_BYTES + 1;
        HttpResponse<String> response = sendRequestWithContent(oversizedLength, authMode);
        
        assertThat(response.statusCode()).isEqualTo(BAD_REQUEST_400);
        assertThat(response.body()).contains("Request body too large");
    }

    @Test
    @DisplayName("Should accept request with Content-Length within limit")
    void testAcceptNormalContentLength() throws Exception {
        int normalSize = 100; // Small size that's well within limits
        HttpResponse<String> response = sendRequestWithContent(normalSize, authMode);
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }

    @Test
    @DisplayName("Should handle request at maximum allowed size")
    void testMaxAllowedSize() throws Exception {
        HttpResponse<String> response = sendRequestWithContent(RequestHandler.MAX_PAYLOAD_SIZE_BYTES, authMode);
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }

    @Test
    @DisplayName("Should reject request with too many headers")
    void testRejectTooManyHeaders() throws Exception {
        HttpRequest.Builder requestBuilder = getRequestBuilder("/test", authMode);
        
        for (int i = 0; i < RequestHandler.MAX_HEADER_COUNT; i++) {
            requestBuilder.header("X-Custom-Header-" + i, "value");
        }
        
        HttpResponse<String> response = sendRequestWithHeadersPrepared(requestBuilder, authMode);
        
        assertThat(response.statusCode()).isEqualTo(BAD_REQUEST_400);
        assertThat(response.body()).contains("Too many headers");
    }
    
    @Test
    @DisplayName("Should reject request with header name too long")
    void testRejectLongHeaderName() throws Exception {
        String longHeaderName = "X-" + "A".repeat(9000); // Exceeds Jetty's 8KB limit
        
        HttpRequest.Builder requestBuilder = getRequestBuilder("/test", authMode)
                .header(longHeaderName, "value");
        
        HttpResponse<String> response = sendRequestWithHeadersPrepared(requestBuilder, authMode);
        
        assertThat(response.statusCode()).isEqualTo(REQUEST_HEADER_FIELDS_TOO_LARGE_431);
    }
    
    @Test
    @DisplayName("Should reject request with header value too long")
    void testRejectLongHeaderValue() throws Exception {
        String longHeaderValue = "A".repeat(9000); // Exceeds 8192 byte limit
        
        HttpRequest.Builder requestBuilder = getRequestBuilder("/test", authMode)
                .header("X-Custom-Header", longHeaderValue);
        
        HttpResponse<String> response = sendRequestWithHeadersPrepared(requestBuilder, authMode);
        
        assertThat(response.statusCode()).isEqualTo(REQUEST_HEADER_FIELDS_TOO_LARGE_431);
    }
    
    @Test
    @DisplayName("Should accept request with maximum allowed headers")
    void testMaxAllowedHeaders() throws Exception {
        HttpRequest.Builder requestBuilder = getRequestBuilder("/test", authMode);
        
        // Account for extra headers in authorized mode (auth headers, Content-Type for JSON-RPC)
        int maxHeaders = authMode == AUTHORIZED 
                ? RequestHandler.MAX_HEADER_COUNT - 15
                : RequestHandler.MAX_HEADER_COUNT - 10;
        
        for (int i = 0; i < maxHeaders; i++) {
            requestBuilder.header("X-Custom-Header-" + i, "value");
        }
        
        HttpResponse<String> response = sendRequestWithHeadersPrepared(requestBuilder, authMode);
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }

}