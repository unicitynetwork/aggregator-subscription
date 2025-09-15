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

    private final ConcurrentMap<String, RateLimitEntry> buckets = new ConcurrentHashMap<>();
    private final CachedApiKeyManager apiKeyManager;
    private Function<CachedApiKeyManager.ApiKeyInfo, Bucket> bucketFactory;

    public RateLimiterManager(CachedApiKeyManager apiKeyManager) {
        this.apiKeyManager = apiKeyManager;
        this.bucketFactory = this::createDefaultBucket;
        logger.info("RateLimiterManager initialized");
    }
    
    public void setBucketFactory(Function<CachedApiKeyManager.ApiKeyInfo, Bucket> bucketFactory) {
        this.bucketFactory = bucketFactory;
        buckets.clear();
    }

    public RateLimitResult tryConsume(String apiKey) {
        CachedApiKeyManager.ApiKeyInfo currentApiKeyInfo = apiKeyManager.getApiKeyInfo(apiKey);
        if (currentApiKeyInfo == null) {
            logger.warn("Attempted to rate limit unknown API key: {}", apiKey);
            return RateLimitResult.denied(0);
        }

        RateLimitEntry entry = buckets.compute(apiKey, (key, existingEntry) -> {
            if (existingEntry == null || !existingEntry.apiKeyInfo().equals(currentApiKeyInfo)) {
                Bucket newBucket = bucketFactory.apply(currentApiKeyInfo);
                return new RateLimitEntry(newBucket, currentApiKeyInfo);
            }
            return existingEntry;
        });

        Bucket bucket = entry.bucket();
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long waitTimeNanos = probe.getNanosToWaitForRefill();
            long waitTimeSeconds = Math.max(1, nanosToSecondsRoundUp(waitTimeNanos));
            if (logger.isDebugEnabled()) {
                logger.debug("Rate limit exceeded for API key {}", apiKey);
            }
            return RateLimitResult.denied(waitTimeSeconds);
        }
        
        if (logger.isTraceEnabled()) {
            logger.trace("Request allowed for API key {} (remaining: {})",
                apiKey, probe.getRemainingTokens());
        }
        
        return RateLimitResult.allowed(probe.getRemainingTokens());
    }

    private static long nanosToSecondsRoundUp(long nanoseconds) {
        return 1L + (nanoseconds - 1L) / 1_000_000_000L;
    }

    private Bucket createDefaultBucket(CachedApiKeyManager.ApiKeyInfo apiKeyInfo) {
        return createBucketWithTimeMeter(apiKeyInfo, TimeMeter.SYSTEM_MILLISECONDS);
    }
    
    public static Bucket createBucketWithTimeMeter(CachedApiKeyManager.ApiKeyInfo apiKeyInfo, TimeMeter timeMeter) {
        Bucket bucket = Bucket.builder()
            .addLimit(limit -> limit
                .capacity(apiKeyInfo.requestsPerSecond())
                .refillGreedy(apiKeyInfo.requestsPerSecond(), Duration.ofSeconds(1))
            )
            .addLimit(limit -> limit
                .capacity(apiKeyInfo.requestsPerDay())
                .refillGreedy(apiKeyInfo.requestsPerDay(), Duration.ofDays(1))
            )
            .withCustomTimePrecision(timeMeter)
            .build();
        
        if (logger.isDebugEnabled()) {
            logger.debug("Created rate limiter for API key {} with limits: {}/sec, {}/day",
                apiKeyInfo.apiKey(), apiKeyInfo.requestsPerSecond(), apiKeyInfo.requestsPerDay());
        }
        
        return bucket;
    }

    public record RateLimitResult(boolean allowed, long retryAfterSeconds, long remainingTokens) {
        public static RateLimitResult allowed(long remainingTokens) {
            return new RateLimitResult(true, 0, remainingTokens);
       }

        public static RateLimitResult denied(long retryAfterSeconds) {
            return new RateLimitResult(false, retryAfterSeconds, 0);
        }
    }

    private record RateLimitEntry(Bucket bucket, CachedApiKeyManager.ApiKeyInfo apiKeyInfo) {}
}