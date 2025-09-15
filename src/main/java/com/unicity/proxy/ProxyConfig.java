package com.unicity.proxy;

import com.beust.jcommander.Parameter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class ProxyConfig {
    @Parameter(names = {"--port", "-p"}, description = "Proxy server port")
    private int port = 8080;
    
    @Parameter(names = {"--target", "-t"}, description = "Target server URL")
    private String targetUrl = "http://localhost:3000";
    
    @Parameter(names = {"--threads"}, description = "Number of IO threads (0 for virtual threads)")
    private int ioThreads = 0;
    
    @Parameter(names = {"--worker-threads"}, description = "Number of worker threads")
    private int workerThreads = 0;
    
    @Parameter(names = {"--connect-timeout"}, description = "Connection timeout in milliseconds")
    private int connectTimeout = 5000;
    
    @Parameter(names = {"--read-timeout"}, description = "Read timeout in milliseconds")
    private int readTimeout = 30000;

    @Parameter(names = {"--idle-timeout"}, description = "Idle timeout in milliseconds")
    private int idleTimeout = 3000;

    @Parameter(names = {"--log-level"}, description = "Log level (TRACE, DEBUG, INFO, WARN, ERROR)")
    private String logLevel = "INFO";
    
    @Parameter(names = {"--protected-methods"}, description = "Comma-separated list of JSON-RPC methods requiring authentication and rate limiting")
    private String protectedMethods = "submit_commitment";
    
    @Parameter(names = {"--help", "-h"}, help = true, description = "Show help")
    private boolean help = false;

    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }

    public String getTargetUrl() {
        return targetUrl;
    }
    
    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public int getIoThreads() {
        return ioThreads;
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

    public String getLogLevel() {
        return logLevel;
    }

    public boolean isHelp() {
        return help;
    }
    
    public Set<String> getProtectedMethods() {
        return Arrays.stream(protectedMethods.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }
    
    @Override
    public String toString() {
        return String.format(
            "ProxyConfig{port=%d, targetUrl='%s', ioThreads=%d, workerThreads=%d, " +
            "connectTimeout=%d, readTimeout=%d, idleTimeout=%d, logLevel='%s', protectedMethods='%s'}",
            port, targetUrl, ioThreads, workerThreads, 
            connectTimeout, readTimeout, idleTimeout, logLevel, protectedMethods
        );
    }
}