package org.unicitylabs.proxy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ResourceLoader {
    private static final Logger logger = LoggerFactory.getLogger(ResourceLoader.class);
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /**
     * Load text content from a URL (HTTP/HTTPS) or file path (file://).
     *
     * @param urlString The location to load from
     * @return The content as a string
     * @throws IOException if the content cannot be loaded
     */
    public static String loadContent(String urlString) throws IOException {
        URI uri = URI.create(urlString);

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return loadContentFromFile(urlString, uri);
        } else if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
            return loadContentFromUrl(urlString, uri);
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri.getScheme());
        }
    }

    private static String loadContentFromUrl(String urlString, URI uri) throws IOException {
        try {
            logger.debug("Loading content from URL: {}", urlString);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("Failed to fetch content: HTTP " + response.statusCode());
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private static String loadContentFromFile(String urlString, URI uri) throws IOException {
        try {
            logger.debug("Loading content from file: {}", urlString);
            return new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(uri)
            ));
        } catch (Exception e) {
            throw new IOException("Failed to read file: " + urlString, e);
        }
    }
}
