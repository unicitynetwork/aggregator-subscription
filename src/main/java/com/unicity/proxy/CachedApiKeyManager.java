package com.unicity.proxy;

import com.unicity.proxy.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MINUTES;

public class CachedApiKeyManager {
    private static final Logger logger = LoggerFactory.getLogger(CachedApiKeyManager.class);
    private static final long CACHE_TTL_MS = MINUTES.toMillis(1);
    private static final long CLEANUP_INTERVAL_MS = MINUTES.toMillis(5);
    
    private final ApiKeyRepository repository;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;
    
    private static volatile CachedApiKeyManager instance;
    
    private CachedApiKeyManager() {
        this.repository = new ApiKeyRepository();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "api-key-cache-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        
        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredEntries,
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
        logger.info("CachedApiKeyManager initialized with {}ms TTL and {}ms cleanup interval", 
            CACHE_TTL_MS, CLEANUP_INTERVAL_MS);
    }
    
    public static CachedApiKeyManager getInstance() {
        if (instance == null) {
            synchronized (CachedApiKeyManager.class) {
                if (instance == null) {
                    instance = new CachedApiKeyManager();
                }
            }
        }
        return instance;
    }
    
    public boolean isValidApiKey(String apiKey) {
        ApiKeyInfo info = getApiKeyInfo(apiKey);
        return info != null;
    }
    
    public ApiKeyInfo getApiKeyInfo(String apiKey) {
        CacheEntry entry = cache.get(apiKey);
        if (entry != null && !entry.isExpired(System.currentTimeMillis())) {
            logger.debug("Cache hit for API key: {}", apiKey);
            return entry.info;
        }
        
        logger.debug("Cache miss for API key: {}, fetching from database", apiKey);
        Optional<ApiKeyRepository.ApiKeyInfo> repoInfo = repository.findByKeyIfActive(apiKey);
        
        if (repoInfo.isPresent()) {
            ApiKeyRepository.ApiKeyInfo dbInfo = repoInfo.get();
            ApiKeyInfo info = new ApiKeyInfo(
                dbInfo.apiKey(),
                dbInfo.requestsPerSecond(),
                dbInfo.requestsPerDay()
            );
            cache.put(apiKey, new CacheEntry(info));
            return info;
        }
        
        cache.put(apiKey, new CacheEntry(null));
        return null;
    }
    
    public void setApiKeyForTesting(String apiKey, ApiKeyInfo info) {
        cache.put(apiKey, new CacheEntry(info));
    }
    
    public void clearCache() {
        cache.clear();
    }
    
    public void removeCacheEntry(String apiKey) {
        cache.remove(apiKey);
        logger.debug("Removed cache entry for API key: {}", apiKey);
    }
    
    private void cleanupExpiredEntries() {
        int removed = 0;
        long currentTimeMillis = System.currentTimeMillis();

        for (Map.Entry<String, CacheEntry> e : cache.entrySet()) {
            CacheEntry v = e.getValue();
            if (v.isExpired(currentTimeMillis) && cache.remove(e.getKey(), v)) {
                removed++;
            }
        }

        if (removed > 0) {
            logger.debug("Cleaned up {} expired cache entries", removed);
        }
    }
    
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    private static class CacheEntry {
        final ApiKeyInfo info;
        final long timestamp;
        
        CacheEntry(ApiKeyInfo info) {
            this.info = info;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired(long currentTimeMillis) {
            return currentTimeMillis - timestamp > CACHE_TTL_MS;
        }
    }

    public record ApiKeyInfo(String apiKey, int requestsPerSecond, int requestsPerDay) {
    }
}