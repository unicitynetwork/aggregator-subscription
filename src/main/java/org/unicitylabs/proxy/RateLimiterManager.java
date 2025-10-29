package org.unicitylabs.proxy;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
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
            logger.info("Attempted to rate limit unknown API key: {}", apiKey);
            return RateLimitResult.denied(0);
        }

        RateLimitEntry entry = buckets.compute(apiKey, (key, existingEntry) -> {
            if (existingEntry == null || !existingEntry.apiKeyInfo.equals(currentApiKeyInfo)) {
                Bucket newBucket = bucketFactory.apply(currentApiKeyInfo);
                return new RateLimitEntry(newBucket, currentApiKeyInfo);
            }
            return existingEntry;
        });

        Bucket bucket = entry.bucket;
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            // Record consumption for tracking
            entry.recordConsumption();

            if (logger.isTraceEnabled()) {
                logger.trace("Request allowed for API key {} (remaining: {})",
                    apiKey, probe.getRemainingTokens());
            }

            return RateLimitResult.allowed(probe.getRemainingTokens());
        } else {
            long waitTimeNanos = probe.getNanosToWaitForRefill();
            long waitTimeSeconds = Math.max(1, nanosToSecondsRoundUp(waitTimeNanos));
            if (logger.isDebugEnabled()) {
                logger.debug("Rate limit exceeded for API key {}", apiKey);
            }
            return RateLimitResult.denied(waitTimeSeconds);
        }
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

    private static class RateLimitEntry {
        public static final int MILLISECONDS_PER_DAY = 24 * 60 * 60 * 1000;

        private final Bucket bucket;
        private final CachedApiKeyManager.ApiKeyInfo apiKeyInfo;
        private final AtomicLong consumedPerSecond = new AtomicLong(0);
        private final AtomicLong consumedPerDay = new AtomicLong(0);
        private volatile long lastSecondReset = System.currentTimeMillis();
        private volatile long lastDayReset = System.currentTimeMillis();

        RateLimitEntry(Bucket bucket, CachedApiKeyManager.ApiKeyInfo apiKeyInfo) {
            this.bucket = bucket;
            this.apiKeyInfo = apiKeyInfo;
        }

        void recordConsumption() {
            long now = System.currentTimeMillis();

            // Reset per-second counter if more than 1 second has passed
            if (now - lastSecondReset >= 1000) {
                consumedPerSecond.set(1);
                lastSecondReset = now;
            } else {
                consumedPerSecond.incrementAndGet();
            }

            // Reset per-day counter if more than 24 hours have passed
            if (now - lastDayReset >= MILLISECONDS_PER_DAY) {
                consumedPerDay.set(1);
                lastDayReset = now;
            } else {
                consumedPerDay.incrementAndGet();
            }
        }

        long getConsumedPerSecond() {
            long now = System.currentTimeMillis();
            if (now - lastSecondReset >= 1000) {
                return 0;
            }
            return consumedPerSecond.get();
        }

        long getConsumedPerDay() {
            long now = System.currentTimeMillis();
            if (now - lastDayReset >= MILLISECONDS_PER_DAY) {
                return 0;
            }
            return consumedPerDay.get();
        }
    }

    public static class UtilizationInfo {
        private final String apiKey;
        private final long availableTokensPerSecond;
        private final long maxTokensPerSecond;
        private final long availableTokensPerDay;
        private final long maxTokensPerDay;
        private final long consumedPerSecond;
        private final long consumedPerDay;
        private final double utilizationPercentPerSecond;
        private final double utilizationPercentPerDay;

        public UtilizationInfo(String apiKey, long availablePerSec, long maxPerSec,
                              long availablePerDay, long maxPerDay) {
            this.apiKey = apiKey;
            this.availableTokensPerSecond = availablePerSec;
            this.maxTokensPerSecond = maxPerSec;
            this.availableTokensPerDay = availablePerDay;
            this.maxTokensPerDay = maxPerDay;
            this.consumedPerSecond = maxPerSec - availablePerSec;
            this.consumedPerDay = maxPerDay - availablePerDay;
            this.utilizationPercentPerSecond = maxPerSec > 0 ?
                (100.0 * consumedPerSecond / maxPerSec) : 0;
            this.utilizationPercentPerDay = maxPerDay > 0 ?
                (100.0 * consumedPerDay / maxPerDay) : 0;
        }

        // Getters
        public String getApiKey() { return apiKey; }
        public long getAvailableTokensPerSecond() { return availableTokensPerSecond; }
        public long getMaxTokensPerSecond() { return maxTokensPerSecond; }
        public long getAvailableTokensPerDay() { return availableTokensPerDay; }
        public long getMaxTokensPerDay() { return maxTokensPerDay; }
        public long getConsumedPerSecond() { return consumedPerSecond; }
        public long getConsumedPerDay() { return consumedPerDay; }
        public double getUtilizationPercentPerSecond() { return utilizationPercentPerSecond; }
        public double getUtilizationPercentPerDay() { return utilizationPercentPerDay; }
    }

    public UtilizationInfo getUtilization(String apiKey) {
        RateLimitEntry entry = buckets.get(apiKey);
        if (entry == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("No bucket entry found for API key: {}", apiKey);
            }
            return null;
        }

        CachedApiKeyManager.ApiKeyInfo apiKeyInfo = entry.apiKeyInfo;

        // Get consumption from tracking
        long consumedPerSec = entry.getConsumedPerSecond();
        long consumedPerDay = entry.getConsumedPerDay();

        long availablePerSecond = apiKeyInfo.requestsPerSecond() - consumedPerSec;
        long availablePerDay = apiKeyInfo.requestsPerDay() - consumedPerDay;

        if (logger.isDebugEnabled()) {
            logger.debug("Utilization for {}: consumed per sec={}/{}, per day={}/{}",
                apiKey, consumedPerSec, apiKeyInfo.requestsPerSecond(),
                consumedPerDay, apiKeyInfo.requestsPerDay());
        }

        return new UtilizationInfo(
            apiKey,
            availablePerSecond,
            apiKeyInfo.requestsPerSecond(),
            availablePerDay,
            apiKeyInfo.requestsPerDay()
        );
    }
}