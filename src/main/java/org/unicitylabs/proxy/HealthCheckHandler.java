package org.unicitylabs.proxy;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.repository.DatabaseConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;

public class HealthCheckHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckHandler.class);

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String path = request.getHttpURI().getPath();

        if (!"/health".equals(path)) {
            return false;
        }

        try {
            if (!DatabaseConfig.isInitialized()) {
                sendUnhealthy(response, callback, "Database not initialized");
                return true;
            }

            // Check 2: Verify we can obtain a connection from the pool
            try (Connection conn = DatabaseConfig.getConnection()) {
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
        String json = "{\"status\":\"healthy\",\"database\":\"ok\"}";
        response.setStatus(HttpStatus.OK_200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
        response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
    }

    private void sendUnhealthy(Response response, Callback callback, String reason) {
        String json = String.format("{\"status\":\"unhealthy\",\"reason\":\"%s\"}",
            reason.replace("\"", "\\\""));
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
        response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
    }
}
