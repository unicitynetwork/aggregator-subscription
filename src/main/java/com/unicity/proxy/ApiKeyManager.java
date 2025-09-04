package com.unicity.proxy;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ApiKeyManager {
    // TODO: A hardcoded list of keys for testing. This will be replaced with a database.
    private static final Map<String, ApiKeyInfo> DEFAULT_API_KEYS = Map.of(
        "supersecret", new ApiKeyInfo("supersecret", 10, 100_000),
        "test-key-123", new ApiKeyInfo("test-key-123", 10, 100_000),
        "premium-key-abc", new ApiKeyInfo("premium-key-abc", 20, 500_000),
        "basic-key-xyz", new ApiKeyInfo("basic-key-xyz", 5, 50_000),
        "dev-key-456", new ApiKeyInfo("dev-key-456", 10, 100_000)
    );
    
    private static Map<String, ApiKeyInfo> apiKeys = new ConcurrentHashMap<>(DEFAULT_API_KEYS);
    
    public static boolean isValidApiKey(String apiKey) {
        return apiKey != null && apiKeys.containsKey(apiKey);
    }
    
    public static ApiKeyInfo getApiKeyInfo(String apiKey) {
        return apiKeys.get(apiKey);
    }
    
    public static Set<String> getAllApiKeys() {
        return apiKeys.keySet();
    }
    
    public static void setApiKeyForTesting(String apiKey, ApiKeyInfo info) {
        apiKeys.put(apiKey, info);
    }
    
    public static void removeApiKeyForTesting(String apiKey) {
        apiKeys.remove(apiKey);
    }
    
    public static void resetToDefaults() {
        apiKeys.clear();
        apiKeys.putAll(DEFAULT_API_KEYS);
    }
    
    public static class ApiKeyInfo {
        private final String apiKey;
        private final int requestsPerSecond;
        private final int requestsPerDay;
        
        public ApiKeyInfo(String apiKey, int requestsPerSecond, int requestsPerDay) {
            this.apiKey = apiKey;
            this.requestsPerSecond = requestsPerSecond;
            this.requestsPerDay = requestsPerDay;
        }
        
        public String getApiKey() {
            return apiKey;
        }
        
        public int getRequestsPerSecond() {
            return requestsPerSecond;
        }
        
        public int getRequestsPerDay() {
            return requestsPerDay;
        }
    }
}