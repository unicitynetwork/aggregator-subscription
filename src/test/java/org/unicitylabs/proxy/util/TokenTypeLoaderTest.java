package org.unicitylabs.proxy.util;

import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.util.HexConverter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TokenTypeLoaderTest {
    private static final String DEFAULT_TESTNET_URL =
        "https://raw.githubusercontent.com/unicitynetwork/unicity-ids/main/unicity-ids.testnet.json";

    private static final String EXPECTED_UNICITY_TOKEN_TYPE_ID =
        "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509";

    @Test
    void testLoadNonFungibleTokenTypeFromTestnetUrl() throws Exception {
        TokenType tokenType = TokenTypeLoader.loadNonFungibleTokenType(DEFAULT_TESTNET_URL, "unicity");

        String actualId = HexConverter.encode(tokenType.getBytes());
        assertThat(actualId).isEqualTo(EXPECTED_UNICITY_TOKEN_TYPE_ID);
    }

    @Test
    void testLoadNonFungibleTokenTypeWithInvalidName() {
        // Try to load a non-existent token type
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            TokenTypeLoader.loadNonFungibleTokenType(DEFAULT_TESTNET_URL, "nonexistent-token");
        });

        assertThat(exception.getMessage())
            .contains("not found")
            .contains("nonexistent-token");
    }

    @Test
    void testLoadNonFungibleTokenTypeFromInvalidUrl() {
        Exception exception = assertThrows(Exception.class, () -> {
            TokenTypeLoader.loadNonFungibleTokenType("https://invalid-url-that-does-not-exist.com/file.json", "unicity");
        });

        assertThat(exception).isNotNull();
    }

    @Test
    void testLoadNonFungibleTokenTypeFromLocalTestFile() throws Exception {
        TokenType tokenType = TokenTypeLoader.loadNonFungibleTokenType(
                getClass().getResource("/test-token-types.json").toString(),
                "unicity");

        String actualId = HexConverter.encode(tokenType.getBytes());
        assertThat(actualId).isEqualTo(EXPECTED_UNICITY_TOKEN_TYPE_ID);
    }
}
