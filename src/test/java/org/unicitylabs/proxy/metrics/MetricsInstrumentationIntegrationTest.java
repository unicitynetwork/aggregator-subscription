package org.unicitylabs.proxy.metrics;

import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.AbstractIntegrationTest;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpStatus.BAD_GATEWAY_502;
import static org.eclipse.jetty.http.HttpStatus.BAD_REQUEST_400;
import static org.eclipse.jetty.http.HttpStatus.OK_200;
import static org.eclipse.jetty.http.HttpStatus.TOO_MANY_REQUESTS_429;
import static org.eclipse.jetty.http.HttpStatus.UNAUTHORIZED_401;

/**
 * Wires {@link org.unicitylabs.proxy.RequestHandler} against a full
 * {@code ProxyServer} + mock upstream and asserts that scraping {@code /metrics}
 * after each terminal branch (success / unauthorized / rate-limited / routing
 * error) reports the expected counter increments. Catches regressions in the
 * callback wrapping or per-branch outcome assignment that the unit-level
 * {@link MetricsHandlerTest} cannot cover.
 */
class MetricsInstrumentationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void successfulRequestIncrementsSuccessCounter() throws Exception {
        // Authorized + valid stateId → upstream OK
        HttpResponse<String> response = performJsonRpcRequest(
            getAuthorizedRequestBuilder("/"), CERTIFICATION_REQUEST_JSON);
        assertThat(response.statusCode()).isEqualTo(OK_200);

        String scrape = scrapeMetrics();
        assertThat(counter(scrape, "gateway_requests_total",
            "outcome=\"success\"", "jsonrpc_method=\"certification_request\"", "status_class=\"2xx\""))
            .isGreaterThanOrEqualTo(1);
        assertThat(counter(scrape, "gateway_upstream_requests_total",
            "outcome=\"success\""))
            .isGreaterThanOrEqualTo(1);
        // Latency timer also recorded
        assertThat(counter(scrape, "gateway_request_duration_seconds_count",
            "outcome=\"success\"", "jsonrpc_method=\"certification_request\""))
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    void unauthorizedRequestIncrementsUnauthorizedCounter() throws Exception {
        // No Authorization header → 401 on a protected method
        HttpResponse<String> response = performJsonRpcRequest(
            getNotAuthorizedRequestBuilder("/"), CERTIFICATION_REQUEST_JSON);
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED_401);

        String scrape = scrapeMetrics();
        assertThat(counter(scrape, "gateway_requests_total",
            "outcome=\"unauthorized\"", "jsonrpc_method=\"certification_request\"", "status_class=\"4xx\""))
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    void rateLimitedRequestIncrementsRateLimitedCounter() throws Exception {
        // Standard plan = 10 req/s (see PLAN_STANDARD); fire 12 to guarantee a 429
        int over = 12;
        boolean got429 = false;
        for (int i = 0; i < over; i++) {
            HttpResponse<String> response = performJsonRpcRequest(
                getAuthorizedRequestBuilder("/"), CERTIFICATION_REQUEST_JSON);
            if (response.statusCode() == TOO_MANY_REQUESTS_429) {
                got429 = true;
            }
        }
        assertThat(got429).as("expected at least one 429 within %d requests", over).isTrue();

        String scrape = scrapeMetrics();
        assertThat(counter(scrape, "gateway_requests_total",
            "outcome=\"rate_limited\"", "jsonrpc_method=\"certification_request\"", "status_class=\"4xx\""))
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    void routingErrorIncrementsRoutingErrorCounter() throws Exception {
        // get_inclusion_proof.v2 is shard-bound and requires stateId. Sending
        // it with empty params triggers the routing-error branch in
        // determineTargetUrl deterministically (no body parsing needed).
        String body = """
            {"jsonrpc":"2.0","method":"get_inclusion_proof.v2","params":{},"id":1}""";
        HttpResponse<String> response = performJsonRpcRequest(
            getNotAuthorizedRequestBuilder("/"), body);
        assertThat(response.statusCode()).isEqualTo(BAD_REQUEST_400);

        String scrape = scrapeMetrics();
        long routingErrors = counter(scrape, "gateway_requests_total",
            "outcome=\"routing_error\"",
            "jsonrpc_method=\"get_inclusion_proof.v2\"",
            "status_class=\"4xx\"");
        assertThat(routingErrors).isGreaterThanOrEqualTo(1);
    }

    @Test
    void oversizedHeadersIncrementBadRequestCounter() throws Exception {
        // Send >MAX_HEADER_COUNT (200) headers; validateRequestSizeLimits
        // returns the bad_request branch.
        HttpRequest.Builder b = getAuthorizedRequestBuilder("/");
        for (int i = 0; i < 250; i++) {
            b = b.header("X-Stuff-" + i, "v" + i);
        }
        HttpRequest req = b
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(CERTIFICATION_REQUEST_JSON))
            .timeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(BAD_REQUEST_400);

        String scrape = scrapeMetrics();
        long badRequests = counter(scrape, "gateway_requests_total",
            "outcome=\"bad_request\"",
            "jsonrpc_method=\"certification_request\"",
            "status_class=\"4xx\"");
        assertThat(badRequests).isGreaterThanOrEqualTo(1);
    }

    @Test
    void unreachableUpstreamIncrementsUpstreamErrorCounter() throws Exception {
        // Stop the mock so the proxy's upstream call fails with a connection
        // error → handleError → outcome=upstream_error.
        mockServer.stop();

        HttpResponse<String> response = performJsonRpcRequest(
            getAuthorizedRequestBuilder("/"), CERTIFICATION_REQUEST_JSON);
        assertThat(response.statusCode()).isEqualTo(BAD_GATEWAY_502);

        String scrape = scrapeMetrics();
        long upstreamErrors = counter(scrape, "gateway_requests_total",
            "outcome=\"upstream_error\"",
            "jsonrpc_method=\"certification_request\"",
            "status_class=\"5xx\"");
        assertThat(upstreamErrors).isGreaterThanOrEqualTo(1);

        // The dedicated upstream counter should also reflect the failure.
        long upstreamFailures = counter(scrape, "gateway_upstream_requests_total",
            "outcome=\"error\"");
        assertThat(upstreamFailures).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rateLimitBucketGaugeReflectsAuthorizedTraffic() throws Exception {
        // Before any authorized request, no bucket has been allocated.
        long before = gauge(scrapeMetrics(), "gateway_ratelimit_buckets");

        // One authorized request → bucket created for the default test API key.
        HttpResponse<String> response = performJsonRpcRequest(
            getAuthorizedRequestBuilder("/"), CERTIFICATION_REQUEST_JSON);
        assertThat(response.statusCode()).isEqualTo(OK_200);

        long after = gauge(scrapeMetrics(), "gateway_ratelimit_buckets");
        assertThat(after).as("gateway_ratelimit_buckets should grow after a new key allocates a bucket")
            .isGreaterThan(before);
    }

    @Test
    void successCounterIncrementsRateLimitRemainingHeaderPresent() throws Exception {
        // Sanity check that instrumentation does not break the existing
        // X-RateLimit-Remaining header path.
        HttpResponse<String> response = performJsonRpcRequest(
            getAuthorizedRequestBuilder("/"), CERTIFICATION_REQUEST_JSON);
        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.headers().firstValue("X-RateLimit-Remaining"))
            .isPresent();
    }

    private String scrapeMetrics() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(java.net.URI.create(getProxyUrl() + "/metrics")).GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return resp.body();
    }

    /**
     * Returns the (single) value of an unlabeled gauge. Returns -1 if absent
     * so the caller can distinguish "not present" from "present and zero".
     */
    private static long gauge(String scrape, String metric) {
        Pattern line = Pattern.compile("^" + Pattern.quote(metric) + "(?:\\{[^}]*\\})?\\s+([0-9eE.+-]+)\\s*$",
            Pattern.MULTILINE);
        Matcher m = line.matcher(scrape);
        if (m.find()) {
            try {
                return (long) Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Sums all values of {@code metric{... matchers all present ...}} samples
     * in a Prometheus text-format scrape. Returns 0 if no sample matches.
     */
    private static long counter(String scrape, String metric, String... mustContain) {
        Pattern line = Pattern.compile("^" + Pattern.quote(metric) + "(?:\\{([^}]*)\\})?\\s+([0-9eE.+-]+)\\s*$",
            Pattern.MULTILINE);
        Matcher m = line.matcher(scrape);
        long sum = 0;
        while (m.find()) {
            String labels = m.group(1) == null ? "" : m.group(1);
            boolean ok = true;
            for (String needle : mustContain) {
                if (!labels.contains(needle)) { ok = false; break; }
            }
            if (ok) {
                try {
                    sum += (long) Double.parseDouble(m.group(2));
                } catch (NumberFormatException ignored) { /* skip */ }
            }
        }
        return sum;
    }
}
