package org.unicitylabs.proxy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Gateway-wide metrics registry. Holds the Prometheus registry, JVM/system
 * binders, and the gateway-specific counters/timers used by the request path.
 */
public final class GatewayMetrics {

    public enum Outcome {
        SUCCESS("success"),
        UNAUTHORIZED("unauthorized"),
        RATE_LIMITED("rate_limited"),
        BAD_REQUEST("bad_request"),
        ROUTING_ERROR("routing_error"),
        UPSTREAM_ERROR("upstream_error");

        private final String label;

        Outcome(String label) { this.label = label; }

        public String label() { return label; }
    }

    private static final String METHOD_NONE = "none";
    private static final String METHOD_OTHER = "other";

    /**
     * Methods we expect to see; anything outside this set is collapsed to
     * "other" to keep label cardinality bounded.
     */
    private static final Set<String> KNOWN_METHODS = Set.of(
        "submit_commitment",
        "get_inclusion_proof",
        "get_inclusion_proof.v2",
        "get_no_deletion_proof",
        "get_block_height",
        "get_block",
        "get_block_commitments",
        "certification_request"
    );

    private final PrometheusMeterRegistry registry;

    public GatewayMetrics() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
        new FileDescriptorMetrics().bindTo(registry);
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    public String scrape() {
        return registry.scrape();
    }

    public void recordRequest(Outcome outcome, String jsonRpcMethod, int httpStatus, long durationNanos) {
        String method = normalizeMethod(jsonRpcMethod);
        String statusClass = statusClass(httpStatus);
        Counter.builder("gateway_requests_total")
            .description("Total HTTP requests processed by the gateway")
            .tag("outcome", outcome.label())
            .tag("jsonrpc_method", method)
            .tag("status_class", statusClass)
            .register(registry)
            .increment();

        Timer.builder("gateway_request_duration_seconds")
            .description("End-to-end request latency, including upstream call")
            .tag("outcome", outcome.label())
            .tag("jsonrpc_method", method)
            .publishPercentileHistogram()
            .register(registry)
            .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void recordUpstream(String shardTarget, boolean success) {
        Counter.builder("gateway_upstream_requests_total")
            .description("Requests forwarded to upstream aggregator shards")
            .tag("shard", shardTarget == null ? "unknown" : shardTarget)
            .tag("outcome", success ? "success" : "error")
            .register(registry)
            .increment();
    }

    public <T> void registerRateLimitBucketGauge(T source, ToDoubleFunction<T> sizeFn) {
        io.micrometer.core.instrument.Gauge.builder("gateway_ratelimit_buckets", source, sizeFn)
            .description("Number of active per-API-key rate-limit buckets")
            .register(registry);
    }

    private static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return METHOD_NONE;
        }
        return KNOWN_METHODS.contains(method) ? method : METHOD_OTHER;
    }

    private static String statusClass(int status) {
        if (status >= 500) return "5xx";
        if (status >= 400) return "4xx";
        if (status >= 300) return "3xx";
        if (status >= 200) return "2xx";
        return "other";
    }
}
