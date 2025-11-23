package org.unicitylabs.proxy.util;

public interface EnvironmentProvider {
    String getEnv(String key);

    class SystemEnvironmentProvider implements EnvironmentProvider {
        private static final SystemEnvironmentProvider INSTANCE = new SystemEnvironmentProvider();

        public static SystemEnvironmentProvider getInstance() {
            return INSTANCE;
        }

        @Override
        public String getEnv(String key) {
            return System.getenv(key);
        }
    }
}
