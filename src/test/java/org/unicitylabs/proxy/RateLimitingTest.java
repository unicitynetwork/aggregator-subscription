package org.unicitylabs.proxy;

import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.unicitylabs.proxy.RequestHandler.HEADER_X_RATE_LIMIT_REMAINING;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpHeader.WWW_AUTHENTICATE;
import static org.eclipse.jetty.http.HttpStatus.*;

class RateLimitingTest extends AbstractIntegrationTest {
    
    private static final String TEST_API_KEY = "test-rate-limit-key";
    private static final String PREMIUM_API_KEY = "premium-rate-limit-key";
    private static final String BASIC_API_KEY = "basic-rate-limit-key";
    
    @BeforeEach
    @Override
    void setUp() throws Exception {
        super.setUp();
        
        ApiKeyRepository repository = new ApiKeyRepository();
        repository.insert(TEST_API_KEY, TestPricingPlans.STANDARD_PLAN_ID);
        repository.insert(PREMIUM_API_KEY, TestPricingPlans.PREMIUM_PLAN_ID);
        repository.insert(BASIC_API_KEY, TestPricingPlans.BASIC_PLAN_ID);
        
        TestDatabaseSetup.markForDeletionDuringReset(TEST_API_KEY);
        TestDatabaseSetup.markForDeletionDuringReset(PREMIUM_API_KEY);
        TestDatabaseSetup.markForDeletionDuringReset(BASIC_API_KEY);
    }
    
    @AfterEach
    @Override
    void tearDown() throws Exception {
        CachedApiKeyManager.getInstance().invalidateCache();
        super.tearDown();
    }
    
    @Test
    void testPerSecondRateLimit() throws Exception {
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            responses.add(performJsonRpcRequest(
                    getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                    SUBMIT_COMMITMENT_REQUEST));
        }
        
        for (int i = 0; i < 5; i++) {
            assertThat(responses.get(i).statusCode()).isEqualTo(OK_200);
        }
        
        assertThat(responses.get(5).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        assertThat(responses.get(6).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
    }

    @Test
    void testRateLimitResetsAfterWait() throws Exception {
        for (int i = 0; i < 5; i++) {
            performJsonRpcRequest(
                    getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                    SUBMIT_COMMITMENT_REQUEST);
        }

        HttpResponse<String> blockedResponse = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                SUBMIT_COMMITMENT_REQUEST);
        assertThat(blockedResponse.statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);

        testTimeMeter.addTime(1100);

        HttpResponse<String> response = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                SUBMIT_COMMITMENT_REQUEST);
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }

    @Test
    void testDifferentApiKeysHaveSeparateLimits() throws Exception {
        for (int i = 0; i < 10; i++) {
            performJsonRpcRequest(
                    getAuthorizedRequestBuilder("/", TEST_API_KEY),
                    SUBMIT_COMMITMENT_REQUEST);
        }

        HttpResponse<String> response = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                SUBMIT_COMMITMENT_REQUEST);
        assertThat(response.statusCode()).isEqualTo(OK_200);
    }

    @Test
    void testPremiumKeyHasHigherLimits() throws Exception {
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            responses.add(performJsonRpcRequest(
                    getAuthorizedRequestBuilder("/", PREMIUM_API_KEY),
                    SUBMIT_COMMITMENT_REQUEST));
        }
        
        for (int i = 0; i < 20; i++) {
            assertThat(responses.get(i).statusCode())
                    .as("Request %d should succeed", i)
                    .isEqualTo(OK_200);
        }
        
        for (int i = 20; i < 25; i++) {
            assertThat(responses.get(i).statusCode())
                    .as("Request %d should be rate limited", i)
                    .isEqualTo(TOO_MANY_REQUESTS_429);
        }
    }

    @Test
    void testRateLimitHeadersPresent() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                SUBMIT_COMMITMENT_REQUEST);
        
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.headers().firstValue(HEADER_X_RATE_LIMIT_REMAINING)).isPresent();
        
        String remainingTokens = response.headers().firstValue(HEADER_X_RATE_LIMIT_REMAINING).get();
        assertThat(Integer.parseInt(remainingTokens)).isEqualTo(4);
    }

    @Test
    void testRetryAfterHeader() throws Exception {
        for (int i = 0; i < 5; i++) {
            performJsonRpcRequest(
                    getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                    SUBMIT_COMMITMENT_REQUEST);
        }

        HttpResponse<String> blockedResponse = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                SUBMIT_COMMITMENT_REQUEST);
        assertThat(blockedResponse.statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        assertThat(blockedResponse.headers().firstValue("Retry-After")).isPresent();
        
        long retryAfter = Long.parseLong(blockedResponse.headers().firstValue("Retry-After").get());
        assertThat(retryAfter).isGreaterThan(0).isLessThanOrEqualTo(2);
    }

    @Test
    void testConcurrentRequestsRespectRateLimit() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        
        for (int i = 0; i < 15; i++) {
            HttpRequest request = getAuthorizedRequestBuilder("/", TEST_API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(SUBMIT_COMMITMENT_REQUEST))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            futures.add(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> future : futures) {
            responses.add(future.get(10, SECONDS));
        }
        
        long successCount = responses.stream().filter(r -> r.statusCode() == OK_200).count();
        long rateLimitedCount = responses.stream().filter(r -> r.statusCode() == TOO_MANY_REQUESTS_429).count();
        
        assertThat(successCount).isEqualTo(10);
        assertThat(rateLimitedCount).isEqualTo(5);
    }

    @Test
    void testInvalidApiKeyReturnsUnauthorized() throws Exception {
        HttpResponse<String> response = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/", "invalid-key"),
                SUBMIT_COMMITMENT_REQUEST);

        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED_401);
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE.asString())).isPresent();
        assertThat(response.headers().firstValue(HEADER_X_RATE_LIMIT_REMAINING)).isEmpty();
    }

    @Test
    void testDayLimitEnforced() throws Exception {
        CachedApiKeyManager.getInstance().setApiKeyForTesting("day-limit-test",
                new CachedApiKeyManager.ApiKeyInfo("day-limit-test", 1000, 3));

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(performJsonRpcRequest(
                    getAuthorizedRequestBuilder("/", "day-limit-test"),
                    SUBMIT_COMMITMENT_REQUEST));
        }
        
        for (int i = 0; i < 3; i++) {
            assertThat(responses.get(i).statusCode()).isEqualTo(OK_200);
        }
        
        assertThat(responses.get(3).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        assertThat(responses.get(4).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        
        long retryAfter = Long.parseLong(responses.get(3).headers().firstValue("Retry-After").get());
        assertThat(retryAfter).isEqualTo(TimeUnit.HOURS.toSeconds(8));
    }

    @Test
    void testRateLimitsUpdateAfterCacheExpiry() throws Exception {
        // Use the basic key (5 requests/sec) and hit the rate limit
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> response = performJsonRpcRequest(
                    getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                    SUBMIT_COMMITMENT_REQUEST);
            assertThat(response.statusCode()).isEqualTo(OK_200);
        }
        
        HttpResponse<String> blockedResponse = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                SUBMIT_COMMITMENT_REQUEST);
        assertThat(blockedResponse.statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);

        // Update the key's plan in the database to Premium (20 requests/sec)
        new ApiKeyRepository().updatePricingPlan(BASIC_API_KEY, TestPricingPlans.PREMIUM_PLAN_ID);
        // Advance time by more than the cache TTL (1 minute) to force a refresh
        testTimeMeter.addTime(SECONDS.toMillis(61));

        // Confirm the new limit is in place by sending more requests
        for (int i = 0; i < 20; i++) {
            HttpResponse<String> response = performJsonRpcRequest(
                    getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                    SUBMIT_COMMITMENT_REQUEST);
            assertThat(response.statusCode()).isEqualTo(OK_200);
        }
        
        HttpResponse<String> finalBlockedResponse = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/", BASIC_API_KEY),
                SUBMIT_COMMITMENT_REQUEST);
        assertThat(finalBlockedResponse.statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
    }
}