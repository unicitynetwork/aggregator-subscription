package org.unicitylabs.proxy;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import org.unicitylabs.proxy.util.CorsUtils;
import org.unicitylabs.proxy.util.EnvironmentProvider;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class ProxyConfig {
    public static final String ADMIN_PASSWORD = "ADMIN_PASSWORD";
    public static final String GATEWAY_UPSTREAM_H2C = "GATEWAY_UPSTREAM_H2C";

    private final EnvironmentProvider environmentProvider;
    @Parameter(names = {"--port", "-p"}, description = "Proxy server port")
    private int port = 8080;

    @Parameter(names = {"--worker-threads"}, description = "Number of worker threads")
    private int workerThreads = 0;
    
    @Parameter(names = {"--connect-timeout"}, description = "Connection timeout in milliseconds")
    private int connectTimeout = 5000;
    
    @Parameter(names = {"--read-timeout"}, description = "Read timeout in milliseconds")
    private int readTimeout = 30000;

    @Parameter(names = {"--idle-timeout"}, description = "Idle timeout in milliseconds")
    private int idleTimeout = 30000;

    @Parameter(names = {"--enable-h2c"}, description = "Enable cleartext HTTP/2 on the inbound listener")
    private boolean h2cEnabled = false;

    @Parameter(names = {"--upstream-h2c"}, description = "Use cleartext HTTP/2 when proxying to http:// upstream aggregators")
    private boolean upstreamH2cEnabled = false;

    @Parameter(names = {"--upstream-h2c-max-connections-per-destination"}, description = "Maximum upstream h2c connections per destination")
    private int upstreamH2cMaxConnectionsPerDestination = 32;

    @Parameter(names = {"--upstream-h2c-max-queued-requests-per-destination"}, description = "Maximum upstream h2c queued requests per destination")
    private int upstreamH2cMaxQueuedRequestsPerDestination = 100000;

    @Parameter(names = {"--upstream-h2c-initial-session-recv-window"}, description = "Initial upstream h2c session receive window in bytes")
    private int upstreamH2cInitialSessionRecvWindow = 64 * 1024 * 1024;

    @Parameter(names = {"--upstream-h2c-initial-stream-recv-window"}, description = "Initial upstream h2c stream receive window in bytes")
    private int upstreamH2cInitialStreamRecvWindow = 16 * 1024 * 1024;

    @Parameter(names = {"--upstream-h2c-max-local-streams"}, description = "Maximum upstream h2c local streams")
    private int upstreamH2cMaxLocalStreams = 10000;

    @Parameter(
        names = {"--upstream-h2c-worker-threads"},
        description = "Number of upstream h2c client worker threads. -1 derives from --worker-threads; 0 uses virtual threads",
        validateWith = UpstreamH2cWorkerThreadsValidator.class
    )
    private int upstreamH2cWorkerThreads = -1;

    @Parameter(
        names = {"--upstream-response-max-buffer-bytes"},
        description = "Maximum upstream response bytes to buffer",
        validateWith = PositiveIntegerValidator.class
    )
    private int upstreamResponseMaxBufferBytes = 100 * 1024 * 1024;

    @Parameter(names = {"--admin-password"}, description = "Admin dashboard password")
    private String adminPassword = null;

    @Parameter(names = {"--protected-methods"}, description = "Comma-separated list of JSON-RPC methods requiring authentication and rate limiting")
    private String protectedMethods = "certification_request";

    @Parameter(names = {"--trust-base"}, description = "Path to trust base JSON file (defaults to built-in test-trust-base.json from the test network)")
    private String trustBasePath = null;

    @Parameter(names = {"--accepted-coin-id"}, description = "Coin ID accepted for payments (defaults to testnet coin ID)")
    private String acceptedCoinId = "455ad8720656b08e8dbd5bac1f3c73eeea5431565f6c1c3af742b1aa12d41d89";

    @Parameter(names = {"--minimum-payment-amount"}, description = "Minimum payment amount in smallest currency units (default: 1000)")
    private String minimumPaymentAmount = "1000";

    @Parameter(names = {"--token-type-ids-url"}, description = "URL to token type IDs JSON file (defaults to testnet unicity-ids)")
    private String tokenTypeIdsUrl = "https://raw.githubusercontent.com/unicitynetwork/unicity-ids/main/unicity-ids.testnet.json";

    @Parameter(names = {"--token-type-name"}, description = "Token type name to use from the IDs file (default: unicity)")
    private String tokenTypeName = "unicity";

    @Parameter(names = {"--help", "-h"}, help = true, description = "Show help")
    private boolean help = false;

    public ProxyConfig(EnvironmentProvider environmentProvider) {
        this.environmentProvider = environmentProvider;
    }

    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public boolean isVirtualThreads() {
        return getWorkerThreads() == 0;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public int getIdleTimeout() {
        return idleTimeout;
    }

    public boolean isH2cEnabled() {
        return h2cEnabled;
    }

    public boolean isUpstreamH2cEnabled() {
        return getBooleanEnvOrDefault(GATEWAY_UPSTREAM_H2C, upstreamH2cEnabled);
    }

    public int getUpstreamH2cMaxConnectionsPerDestination() {
        return upstreamH2cMaxConnectionsPerDestination;
    }

    public int getUpstreamH2cMaxQueuedRequestsPerDestination() {
        return upstreamH2cMaxQueuedRequestsPerDestination;
    }

    public int getUpstreamH2cInitialSessionRecvWindow() {
        return upstreamH2cInitialSessionRecvWindow;
    }

    public int getUpstreamH2cInitialStreamRecvWindow() {
        return upstreamH2cInitialStreamRecvWindow;
    }

    public int getUpstreamH2cMaxLocalStreams() {
        return upstreamH2cMaxLocalStreams;
    }

    public int getUpstreamH2cWorkerThreads() {
        if (upstreamH2cWorkerThreads >= 0) {
            return upstreamH2cWorkerThreads;
        }
        return isVirtualThreads() ? 0 : workerThreads;
    }

    public int getUpstreamResponseMaxBufferBytes() {
        return upstreamResponseMaxBufferBytes;
    }

    public String getAdminPassword() {
        // Check environment variable first, then command line parameter
        String envPassword = environmentProvider.getEnv(ADMIN_PASSWORD);
        if (envPassword != null && !envPassword.isBlank()) {
            return envPassword;
        }
        throw new IllegalArgumentException("Missing administrator password");
    }

    /**
     * The Access-Control-Allow-Headers list to advertise, from the {@code CORS_ALLOWED_HEADERS}
     * env var (read through the injected provider) with the proxy-required headers always
     * unioned in. See {@link CorsUtils#resolveAllowedHeaders(String)}.
     */
    public String getCorsAllowedHeaders() {
        return CorsUtils.resolveAllowedHeaders(environmentProvider.getEnv(CorsUtils.ENV_CORS_ALLOWED_HEADERS));
    }

    public boolean isHelp() {
        return help;
    }
    
    public Set<String> getProtectedMethods() {
        return Arrays.stream(protectedMethods.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
    }

    public String getTrustBasePath() {
        return trustBasePath;
    }

    public String getAcceptedCoinId() {
        return acceptedCoinId;
    }

    public java.math.BigInteger getMinimumPaymentAmount() {
        return new java.math.BigInteger(minimumPaymentAmount);
    }

    public String getTokenTypeIdsUrl() {
        return tokenTypeIdsUrl;
    }

    public String getTokenTypeName() {
        return tokenTypeName;
    }

    private boolean getBooleanEnvOrDefault(String key, boolean defaultValue) {
        String value = environmentProvider.getEnv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }

    public void setTokenTypeIdsUrl(String tokenTypeIdsUrl) {
        this.tokenTypeIdsUrl = tokenTypeIdsUrl;
    }

    public void setTokenTypeName(String tokenTypeName) {
        this.tokenTypeName = tokenTypeName;
    }

    void setUpstreamH2cEnabled(boolean upstreamH2cEnabled) {
        this.upstreamH2cEnabled = upstreamH2cEnabled;
    }

    void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    void setUpstreamH2cWorkerThreads(int upstreamH2cWorkerThreads) {
        this.upstreamH2cWorkerThreads = upstreamH2cWorkerThreads;
    }

    void setUpstreamResponseMaxBufferBytes(int upstreamResponseMaxBufferBytes) {
        this.upstreamResponseMaxBufferBytes = upstreamResponseMaxBufferBytes;
    }

    void setUpstreamH2cMaxConnectionsPerDestination(int upstreamH2cMaxConnectionsPerDestination) {
        this.upstreamH2cMaxConnectionsPerDestination = upstreamH2cMaxConnectionsPerDestination;
    }

    void setUpstreamH2cMaxQueuedRequestsPerDestination(int upstreamH2cMaxQueuedRequestsPerDestination) {
        this.upstreamH2cMaxQueuedRequestsPerDestination = upstreamH2cMaxQueuedRequestsPerDestination;
    }

    @Override
    public String toString() {
        return String.format(
            "ProxyConfig{port=%d, workerThreads=%d, " +
            "connectTimeout=%d, readTimeout=%d, idleTimeout=%d, h2cEnabled=%s, upstreamH2cEnabled=%s, " +
            "upstreamH2cMaxConnectionsPerDestination=%d, upstreamH2cMaxQueuedRequestsPerDestination=%d, " +
            "upstreamH2cInitialSessionRecvWindow=%d, upstreamH2cInitialStreamRecvWindow=%d, " +
            "upstreamH2cMaxLocalStreams=%d, upstreamH2cWorkerThreads=%d, upstreamResponseMaxBufferBytes=%d, " +
            "protectedMethods='%s'}",
            port, workerThreads,
            connectTimeout, readTimeout, idleTimeout, h2cEnabled, isUpstreamH2cEnabled(),
            upstreamH2cMaxConnectionsPerDestination, upstreamH2cMaxQueuedRequestsPerDestination,
            upstreamH2cInitialSessionRecvWindow, upstreamH2cInitialStreamRecvWindow,
            upstreamH2cMaxLocalStreams, getUpstreamH2cWorkerThreads(), getUpstreamResponseMaxBufferBytes(),
            protectedMethods
        );
    }

    public static final class UpstreamH2cWorkerThreadsValidator implements IParameterValidator {
        @Override
        public void validate(String name, String value) {
            int parsed = parseInteger(name, value);
            if (parsed < -1) {
                throw new ParameterException(name + " must be -1, 0, or positive");
            }
        }
    }

    public static final class PositiveIntegerValidator implements IParameterValidator {
        @Override
        public void validate(String name, String value) {
            int parsed = parseInteger(name, value);
            if (parsed <= 0) {
                throw new ParameterException(name + " must be positive");
            }
        }
    }

    private static int parseInteger(String name, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ParameterException(name + " must be an integer", e);
        }
    }
}
