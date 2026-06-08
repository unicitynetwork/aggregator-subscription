package org.unicitylabs.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.util.CorsUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the load-bearing invariant of issue #37: the CORS default allowed-headers list
 * must advertise the exact header names the proxy reads for routing/auth. Those names
 * live as independent string literals in different packages (RequestHandler vs CorsUtils),
 * so a rename on one side would otherwise silently diverge and re-break browser clients.
 * This test sits in RequestHandler's package so it can reference the package-private
 * constants directly; it fails CI the moment the two sides drift. Pure — no server/Docker.
 */
class CorsHeaderContractTest {

    @Test
    @DisplayName("Default CORS allowed-headers advertise the proxy's routing/auth headers")
    void defaultAdvertisesRoutingAndAuthHeaders() {
        List<String> entries = Arrays.stream(CorsUtils.resolveAllowedHeaders(null).split(","))
            .map(String::trim)
            .map(String::toLowerCase)
            .toList();
        assertThat(entries)
            .as("CORS default must advertise the X-State-ID shard-routing header")
            .contains(RequestHandler.HEADER_X_STATE_ID.toLowerCase());
        assertThat(entries)
            .as("CORS default must advertise the X-API-Key auth header")
            .contains(RequestHandler.HEADER_X_API_KEY.toLowerCase());
    }
}
