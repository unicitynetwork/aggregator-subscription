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
import org.unicitylabs.proxy.repository.ShardConfigRepository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ConfigHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(ConfigHandler.class);
    private static final ObjectMapper mapper = ObjectMapperUtils.createObjectMapper();

    private final ShardConfigRepository shardConfigRepository;

    public ConfigHandler(ShardConfigRepository shardConfigRepository) {
        this.shardConfigRepository = shardConfigRepository;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String path = request.getHttpURI().getPath();

        if (!path.startsWith("/config/")) {
            return false;
        }

        if ("/config/shards".equals(path)) {
            handleShardConfig(response, callback);
            return true;
        }

        return false;
    }

    private void handleShardConfig(Response response, Callback callback) {
        try {
            ShardConfigRepository.ShardConfigRecord configRecord = shardConfigRepository.getLatestConfig();
            String json = mapper.writeValueAsString(configRecord.config());
            sendJsonResponse(response, callback, json, HttpStatus.OK_200);
        } catch (Exception e) {
            logger.error("Failed to retrieve shard configuration", e);
            sendErrorResponse(response, callback, "Shard configuration not available");
        }
    }

    private void sendJsonResponse(Response response, Callback callback, String json, int status) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString());
        response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);
    }

    private void sendErrorResponse(Response response, Callback callback, String reason) {
        try {
            String json = mapper.writeValueAsString(Map.of("error", reason));
            sendJsonResponse(response, callback, json, HttpStatus.SERVICE_UNAVAILABLE_503);
        } catch (Exception e) {
            logger.error("Failed to serialize error response", e);
            callback.failed(e);
        }
    }
}
