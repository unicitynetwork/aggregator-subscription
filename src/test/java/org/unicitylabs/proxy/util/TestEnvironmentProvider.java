package org.unicitylabs.proxy.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Test implementation of EnvironmentProvider that uses an in-memory map.
 * Initializes with system environment variables and allows overriding for tests.
 */
public class TestEnvironmentProvider implements EnvironmentProvider {
    private final Map<String, String> environment;

    /**
     * Create a test environment provider initialized with system environment variables.
     */
    public TestEnvironmentProvider() {
        this.environment = new HashMap<>();
    }

    /**
     * Create a test environment provider with a custom environment map.
     */
    public TestEnvironmentProvider(Map<String, String> environment) {
        this.environment = new HashMap<>(environment);
    }

    @Override
    public String getEnv(String key) {
        return environment.get(key);
    }

    /**
     * Set an environment variable for testing.
     */
    public void setEnv(String key, String value) {
        environment.put(key, value);
    }

    /**
     * Remove an environment variable for testing.
     */
    public void removeEnv(String key) {
        environment.remove(key);
    }

    /**
     * Clear all environment variables.
     */
    public void clearAll() {
        environment.clear();
    }
}
