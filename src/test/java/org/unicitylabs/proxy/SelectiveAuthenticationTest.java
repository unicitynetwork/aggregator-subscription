package org.unicitylabs.proxy;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpStatus.*;

class SelectiveAuthenticationTest extends AbstractIntegrationTest {
    @Test
    void testProtectedMethodRequiresAuthentication() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
                getNotAuthorizedRequestBuilder("/"),
                CERTIFICATION_REQUEST_JSON);
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED_401);
    }

    @Test
    void testProtectedMethodWorksWithAuthentication() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/"),
                CERTIFICATION_REQUEST_JSON);
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }
    
    @Test
    void testUnprotectedMethodWorksWithoutAuthentication() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
                getNotAuthorizedRequestBuilder("/"),
                GET_INCLUSION_PROOF_V2_JSON);
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }
    
    @Test
    void testUnprotectedMethodAlsoWorksWithAuthentication() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/"),
                GET_INCLUSION_PROOF_V2_JSON);
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }
    
    @Test
    void testNonJsonRpcRequestsWorkWithoutAuth() throws Exception {
        HttpRequest request = getNotAuthorizedRequestBuilder("/test")
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }
}