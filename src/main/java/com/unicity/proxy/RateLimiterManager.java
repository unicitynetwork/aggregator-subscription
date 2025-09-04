package com.unicity.proxy;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public class RateLimiterManager {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiterManager.class);
    
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private Function<ApiKeyManager.ApiKeyInfo, Bucket> bucketFactory;
    
    public RateLimiterManager() {
        this.bucketFactory = this::createDefaultBucket;
        logger.info("RateLimiterManager initialized");
    }
    
    public void setBucketFactory(Function<ApiKeyManager.ApiKeyInfo, Bucket> bucketFactory) {
        this.bucketFactory = bucketFactory;
        buckets.clear();
    }
    
    public RateLimitResult tryConsume(String apiKey) {
        ApiKeyManager.ApiKeyInfo apiKeyInfo = ApiKeyManager.getApiKeyInfo(apiKey);
        if (apiKeyInfo == null) {
            logger.warn("Attempted to rate limit unknown API key: {}", apiKey);
            return RateLimitResult.denied(0);
        }
        
        Bucket bucket = buckets.computeIfAbsent(apiKey, k -> bucketFactory.apply(apiKeyInfo));
        
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long waitTimeNanos = probe.getNanosToWaitForRefill();
            long waitTimeSeconds = Math.max(1, nanosToSecondsRoundUp(waitTimeNanos));
            logger.debug("Rate limit exceeded for API key {}", apiKey);
            return RateLimitResult.denied(waitTimeSeconds);
        }
        
        logger.trace("Request allowed for API key {} (remaining: {})", 
            apiKey, probe.getRemainingTokens());
        
        return RateLimitResult.allowed(probe.getRemainingTokens());
    }

    private static long nanosToSecondsRoundUp(long nanoseconds) {
        return 1L + (nanoseconds - 1L) / 1_000_000_000L;
    }

    private Bucket createDefaultBucket(ApiKeyManager.ApiKeyInfo apiKeyInfo) {
        return createBucketWithTimeMeter(apiKeyInfo, TimeMeter.SYSTEM_MILLISECONDS);
    }
    
    public static Bucket createBucketWithTimeMeter(ApiKeyManager.ApiKeyInfo apiKeyInfo, TimeMeter timeMeter) {
        Bucket bucket = Bucket.builder()
            .addLimit(limit -> limit
                .capacity(apiKeyInfo.getRequestsPerSecond())
                .refillGreedy(apiKeyInfo.getRequestsPerSecond(), Duration.ofSeconds(1))
            )
            .addLimit(limit -> limit
                .capacity(apiKeyInfo.getRequestsPerDay())
                .refillGreedy(apiKeyInfo.getRequestsPerDay(), Duration.ofDays(1))
            )
            .withCustomTimePrecision(timeMeter)
            .build();
        
        logger.debug("Created rate limiter for API key {} with limits: {}/sec, {}/day",
            apiKeyInfo.getApiKey(), apiKeyInfo.getRequestsPerSecond(), apiKeyInfo.getRequestsPerDay());
        
        return bucket;
    }
    
    public void resetBucketForApiKey(String apiKey) {
        buckets.remove(apiKey);
        logger.info("Reset rate limiter bucket for API key: {}", apiKey);
    }
    
    public void resetAllBuckets() {
        buckets.clear();
        logger.info("Reset all rate limiter buckets");
    }
    
    public static class RateLimitResult {
        private final boolean allowed;
        private final long retryAfterSeconds;
        private final long remainingTokens;
        
        private RateLimitResult(boolean allowed, long retryAfterSeconds, long remainingTokens) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
            this.remainingTokens = remainingTokens;
        }
        
        public static RateLimitResult allowed(long remainingTokens) {
            return new RateLimitResult(true, 0, remainingTokens);
        }
        
        public static RateLimitResult denied(long retryAfterSeconds) {
            return new RateLimitResult(false, retryAfterSeconds, 0);
        }
        
        public boolean isAllowed() {
            return allowed;
        }
        
        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
        
        public long getRemainingTokens() {
            return remainingTokens;
        }
    }
}