package org.unicitylabs.proxy.shard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.model.ObjectMapperUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Loads shard configuration from file:// or https:// URLs.
 */
public class ShardConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ShardConfigLoader.class);
    private static final ObjectMapper objectMapper = ObjectMapperUtils.createObjectMapper();

    public static ShardConfig load(String urlString) throws IOException {
        logger.info("Loading shard configuration from: {}", urlString);

        URI uri = URI.create(urlString);
        String scheme = uri.getScheme();

        InputStream inputStream;

        if ("file".equalsIgnoreCase(scheme)) {
            Path path = Path.of(uri);
            logger.debug("Loading from file: {}", path);
            inputStream = Files.newInputStream(path);
        } else if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            logger.debug("Loading from URL: {}", urlString);
            inputStream = fetchFromUrl(urlString);
        } else {
            throw new IllegalArgumentException("Unsupported URL scheme: " + scheme + " (expected file://, http://, or https://)");
        }

        try {
            ShardConfig config = objectMapper.readValue(inputStream, ShardConfig.class);
            logger.info("Loaded shard config version {} with {} targets",
                config.getVersion(), config.getTargets().size());

            return config;
        } catch (IOException e) {
            throw new IOException("Failed to parse shard configuration from " + urlString, e);
        } finally {
            inputStream.close();
        }
    }

    private static InputStream fetchFromUrl(String url) throws IOException {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("Failed to fetch shard config from " + url +
                    ": HTTP " + response.statusCode());
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching shard config from " + url, e);
        }
    }
}
