package com.unicity.proxy;

import com.unicity.proxy.repository.ApiKeyRepository;
import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.unicity.proxy.RequestHandler.HEADER_X_RATE_LIMIT_REMAINING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpHeader.WWW_AUTHENTICATE;
import static org.eclipse.jetty.http.HttpStatus.*;

class RateLimitingTest extends AbstractIntegrationTest {
    
    private static final String TEST_API_KEY = "test-rate-limit-key";
    private static final String PREMIUM_API_KEY = "premium-rate-limit-key";
    private static final String BASIC_API_KEY = "basic-rate-limit-key";
    
    private TestTimeMeter testTimeMeter;

    @BeforeEach
    @Override
    void setUp() throws Exception {
        super.setUp();
        
        ApiKeyRepository repository = new ApiKeyRepository();
        repository.save(TEST_API_KEY, TestPricingPlans.STANDARD_PLAN_ID);
        repository.save(PREMIUM_API_KEY, TestPricingPlans.PREMIUM_PLAN_ID);
        repository.save(BASIC_API_KEY, TestPricingPlans.BASIC_PLAN_ID);
        
        TestDatabaseSetup.markForDeletionDuringReset(TEST_API_KEY);
        TestDatabaseSetup.markForDeletionDuringReset(PREMIUM_API_KEY);
        TestDatabaseSetup.markForDeletionDuringReset(BASIC_API_KEY);
        
        testTimeMeter = new TestTimeMeter();
        getRateLimiterManager().setBucketFactory(apiKeyInfo ->
            RateLimiterManager.createBucketWithTimeMeter(apiKeyInfo, testTimeMeter));
    }
    
    @AfterEach
    @Override
    void tearDown() throws Exception {
        CachedApiKeyManager.getInstance().clearCache();
        super.tearDown();
    }
    
    private RateLimiterManager getRateLimiterManager() {
        return ((RequestHandler) proxyServer.getServer().getHandler()).getRateLimiterManager();
    }

    @Test
    void testPerSecondRateLimit() throws Exception {
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            responses.add(performSimpleGetRequest(getAuthorizedRequestBuilder("/test", BASIC_API_KEY)));
        }
        
        for (int i = 0; i < 5; i++) {
            assertThat(responses.get(i).statusCode()).isEqualTo(OK_200);
        }
        
        assertThat(responses.get(5).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        assertThat(responses.get(5).headers().firstValue("Retry-After")).isPresent();
        assertThat(responses.get(6).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
    }

    @Test
    void testRateLimitResetsAfterWait() throws Exception {
        for (int i = 0; i < 5; i++) {
            performSimpleGetRequest(getAuthorizedRequestBuilder("/test", BASIC_API_KEY));
        }

        HttpResponse<String> blockedResponse = performSimpleGetRequest(getAuthorizedRequestBuilder("/test", BASIC_API_KEY));
        assertThat(blockedResponse.statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        
        testTimeMeter.addTime(1100);

        HttpResponse<String> allowedResponse = performSimpleGetRequest(getAuthorizedRequestBuilder("/test", BASIC_API_KEY));
        assertThat(allowedResponse.statusCode()).isEqualTo(OK_200);
    }

    @Test
    void testDifferentApiKeysHaveSeparateLimits() throws Exception {
        for (int i = 0; i < 10; i++) {
            performSimpleGetRequest(getAuthorizedRequestBuilder("/test", TEST_API_KEY));
        }

        HttpResponse<String> blockedResponse = performSimpleGetRequest(getAuthorizedRequestBuilder("/test", TEST_API_KEY));
        assertThat(blockedResponse.statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);

        HttpResponse<String> allowedResponse = performSimpleGetRequest(getAuthorizedRequestBuilder("/test", PREMIUM_API_KEY));
        assertThat(allowedResponse.statusCode()).isEqualTo(OK_200);
    }

    @Test
    void testPremiumKeyHasHigherLimits() throws Exception {
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            responses.add(performSimpleGetRequest(getAuthorizedRequestBuilder("/test", PREMIUM_API_KEY)));
        }
        
        for (int i = 0; i < 20; i++) {
            assertThat(responses.get(i).statusCode()).isEqualTo(OK_200);
        }
        
        assertThat(responses.get(20).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        assertThat(responses.get(21).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
    }

    @Test
    void testRateLimitHeadersPresent() throws Exception {
        HttpResponse<String> response = performSimpleGetRequest(getAuthorizedRequestBuilder("/test", TEST_API_KEY));

        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.headers().firstValue(HEADER_X_RATE_LIMIT_REMAINING)).isPresent();
        
        long remaining = Long.parseLong(response.headers().firstValue(HEADER_X_RATE_LIMIT_REMAINING).get());
        assertThat(remaining).isLessThan(10);
    }

    @Test
    void testRetryAfterHeader() throws Exception {
        for (int i = 0; i < 5; i++) {
            performSimpleGetRequest(getAuthorizedRequestBuilder("/test", BASIC_API_KEY));
        }

        HttpResponse<String> blockedResponse = performSimpleGetRequest(getAuthorizedRequestBuilder("/test", BASIC_API_KEY));

        assertThat(blockedResponse.statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        assertThat(blockedResponse.headers().firstValue("Retry-After")).isPresent();
        
        long retryAfter = Long.parseLong(blockedResponse.headers().firstValue("Retry-After").get());
        assertThat(retryAfter).isGreaterThan(0).isLessThanOrEqualTo(2);
    }

    @Test
    void testConcurrentRequestsRespectRateLimit() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            HttpRequest request = getAuthorizedRequestBuilder("/test", TEST_API_KEY)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            futures.add(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> future : futures) {
            responses.add(future.get(10, TimeUnit.SECONDS));
        }
        
        long successCount = responses.stream().filter(r -> r.statusCode() == OK_200).count();
        long rateLimitedCount = responses.stream().filter(r -> r.statusCode() == TOO_MANY_REQUESTS_429).count();
        
        assertThat(successCount).isEqualTo(10);
        assertThat(rateLimitedCount).isEqualTo(5);
    }

    @Test
    void testInvalidApiKeyReturnsUnauthorized() throws Exception {
        HttpResponse<String> response = performSimpleGetRequest(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxyPort + "/test"))
                .header("Authorization", "Bearer invalid-key"));

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
            responses.add(performSimpleGetRequest(getAuthorizedRequestBuilder("/test", "day-limit-test")));
        }
        
        for (int i = 0; i < 3; i++) {
            assertThat(responses.get(i).statusCode()).isEqualTo(OK_200);
        }
        
        assertThat(responses.get(3).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        assertThat(responses.get(4).statusCode()).isEqualTo(TOO_MANY_REQUESTS_429);
        
        long retryAfter = Long.parseLong(responses.get(3).headers().firstValue("Retry-After").get());
        assertThat(retryAfter).isEqualTo(TimeUnit.HOURS.toSeconds(8));
    }
    
    private static class TestTimeMeter implements TimeMeter {
        private final AtomicLong currentTime = new AtomicLong(System.currentTimeMillis());
        
        @Override
        public long currentTimeNanos() {
            return currentTime.get() * 1_000_000L;
        }
        
        @Override
        public boolean isWallClockBased() {
            return true;
        }
        
        public void addTime(long millis) {
            currentTime.addAndGet(millis);
        }
        
        public void setTime(long millis) {
            currentTime.set(millis);
        }
    }

    private HttpResponse<String> performSimpleGetRequest(HttpRequest.Builder requestBuilder) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}