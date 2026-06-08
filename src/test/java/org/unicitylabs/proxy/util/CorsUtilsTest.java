package org.unicitylabs.proxy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CorsUtils} allowed-headers resolution. Pure (no server,
 * no Docker) — exercises the env-override seam without mutating the environment.
 */
class CorsUtilsTest {

    @Test
    @DisplayName("Default allowed headers include X-State-ID and the standard set (issue #37)")
    void defaultIncludesStateIdHeader() {
        String headers = CorsUtils.resolveAllowedHeaders(null);
        assertThat(headers)
            .contains("X-State-ID")
            .contains("Content-Type")
            .contains("Authorization");
    }

    @Test
    @DisplayName("Blank / whitespace env value falls back to the default list")
    void blankEnvFallsBackToDefault() {
        String fromNull = CorsUtils.resolveAllowedHeaders(null);
        assertThat(CorsUtils.resolveAllowedHeaders("")).isEqualTo(fromNull);
        assertThat(CorsUtils.resolveAllowedHeaders("   ")).isEqualTo(fromNull);
        assertThat(fromNull).contains("X-State-ID");
    }

    @Test
    @DisplayName("CORS_ALLOWED_HEADERS env value overrides (and is trimmed)")
    void envValueOverridesDefault() {
        assertThat(CorsUtils.resolveAllowedHeaders("Content-Type, X-Custom-Header"))
            .isEqualTo("Content-Type, X-Custom-Header");
        // surrounding whitespace is trimmed
        assertThat(CorsUtils.resolveAllowedHeaders("  Content-Type, X-Foo  "))
            .isEqualTo("Content-Type, X-Foo");
    }

    @Test
    @DisplayName("Override is normalized: inner spaces + empty/consecutive commas collapsed")
    void envValueIsNormalized() {
        assertThat(CorsUtils.resolveAllowedHeaders("Content-Type,,  X-State-ID , "))
            .isEqualTo("Content-Type, X-State-ID");
        // a value with no real entries (only separators/whitespace) falls back to default
        assertThat(CorsUtils.resolveAllowedHeaders(" , , "))
            .isEqualTo(CorsUtils.resolveAllowedHeaders(null));
    }
}
