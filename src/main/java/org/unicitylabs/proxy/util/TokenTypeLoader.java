package org.unicitylabs.proxy.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.util.HexConverter;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class TokenTypeLoader {
    private static final String NON_FUNGIBLE = "non-fungible";

    private static final Logger logger = LoggerFactory.getLogger(TokenTypeLoader.class);
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final ObjectMapper objectMapper = UnicityObjectMapper.JSON;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenTypeEntry {
        private String network;
        private String assetKind;
        private String name;
        private String id;

        public String getNetwork() {
            return network;
        }

        public void setNetwork(String network) {
            this.network = network;
        }

        public String getAssetKind() {
            return assetKind;
        }

        public void setAssetKind(String assetKind) {
            this.assetKind = assetKind;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    /**
     * @param url The URL to the unicity-ids JSON file (HTTP/HTTPS or file://)
     * @param tokenTypeName The name of the token type to find (e.g., "unicity")
     * @return TokenType object with the ID from the configuration
     * @throws IOException if the file cannot be loaded or parsed
     * @throws IllegalArgumentException if the token type is not found
     */
    public static TokenType loadNonFungibleTokenType(String url, String tokenTypeName) throws IOException {
        logger.info("Loading token type '{}' from URL: {}", tokenTypeName, url);

        String jsonContent = loadJsonContent(url);
        List<TokenTypeEntry> entries = objectMapper.readValue(
            jsonContent,
            objectMapper.getTypeFactory().constructCollectionType(List.class, TokenTypeEntry.class)
        );

        // Find the entry with matching name and assetKind="non-fungible"
        TokenTypeEntry entry = entries.stream()
            .filter(e -> tokenTypeName.equals(e.getName()) && NON_FUNGIBLE.equals(e.getAssetKind()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                String.format("Token type '%s' with assetKind='non-fungible' not found in %s", tokenTypeName, url)
            ));

        logger.info("Found token type '{}' with ID: {}", tokenTypeName, entry.getId());
        return new TokenType(HexConverter.decode(entry.getId()));
    }

    /**
     * Load JSON content from a URL (HTTP/HTTPS) or file path.
     */
    private static String loadJsonContent(String urlString) throws IOException {
        URL url = new URL(urlString);

        if ("file".equalsIgnoreCase(url.getProtocol())) {
            return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(url.getPath())
            ));
        } else {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new IOException("Failed to fetch token type IDs: HTTP " + response.statusCode());
                }

                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", e);
            }
        }
    }
}
