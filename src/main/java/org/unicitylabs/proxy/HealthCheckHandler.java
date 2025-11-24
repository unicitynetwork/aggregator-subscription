package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.repository.DatabaseConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;

public class HealthCheckHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckHandler.class);
    private static final ObjectMapper mapper = ObjectMapperUtils.createObjectMapper();

    private final DatabaseConfig databaseConfig;

    public HealthCheckHandler(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String path = request.getHttpURI().getPath();

        if (!"/health".equals(path)) {
            return false;
        }

        try {
            if (!databaseConfig.isInitialized()) {
                sendUnhealthy(response, callback, "Database not initialized");
                return true;
            }

            // Check 2: Verify we can obtain a connection from the pool
            try (Connection conn = databaseConfig.getConnection()) {
                // Check 3: Verify connection is valid
                if (!conn.isValid(2)) {
                    sendUnhealthy(response, callback, "Database connection invalid");
                    return true;
                }
            }

            // All checks passed - service is healthy
            sendHealthy(response, callback);
            return true;

        } catch (Exception e) {
            logger.error("Health check failed", e);
            sendUnhealthy(response, callback, "Health check error: " + e.getMessage());
            return true;
        }
    }

    private void sendHealthy(Response response, Callback callback) {
        try {
            String json = mapper.writeValueAsString(Map.of("status", "healthy", "database", "ok"));
            response.setStatus(HttpStatus.OK_200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
            response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
        } catch (Exception e) {
            logger.error("Failed to serialize health response", e);
            callback.failed(e);
        }
    }

    private void sendUnhealthy(Response response, Callback callback, String reason) {
        try {
            String json = mapper.writeValueAsString(Map.of("status", "unhealthy", "reason", reason));
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
            response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
        } catch (Exception e) {
            logger.error("Failed to serialize unhealthy response", e);
            callback.failed(e);
        }
    }
}
