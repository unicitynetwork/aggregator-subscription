package org.unicitylabs.proxy.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.util.HexConverter;

import java.io.IOException;
import java.util.List;

public class TokenTypeLoader {
    private static final String NON_FUNGIBLE = "non-fungible";

    private static final Logger logger = LoggerFactory.getLogger(TokenTypeLoader.class);
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

        String jsonContent = ResourceLoader.loadContent(url);
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
}
