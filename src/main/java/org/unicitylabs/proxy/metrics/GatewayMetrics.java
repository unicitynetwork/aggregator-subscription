package org.unicitylabs.proxy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

/**
 * Gateway-wide metrics registry. Holds the Prometheus registry, JVM/system
 * binders, and the gateway-specific counters/timers used by the request path.
 *
 * <p>Hot-path instruments ({@link #recordRequest} / {@link #recordUpstream})
 * are cached in {@link ConcurrentHashMap}s keyed by tag tuples to avoid
 * builder/register overhead on every request. Histogram configuration for
 * the request-duration timer is applied globally via a {@link MeterFilter}
 * registered at construction time.
 */
public final class GatewayMetrics implements AutoCloseable {

    public enum Outcome {
        SUCCESS("success"),
        UNAUTHORIZED("unauthorized"),
        RATE_LIMITED("rate_limited"),
        BAD_REQUEST("bad_request"),
        ROUTING_ERROR("routing_error"),
        UPSTREAM_ERROR("upstream_error"),
        INTERNAL_ERROR("internal_error");

        private final String label;

        Outcome(String label) { this.label = label; }

        public String label() { return label; }
    }

    private static final String METHOD_NONE = "none";
    private static final String METHOD_OTHER = "other";
    private static final String SHARD_UNKNOWN = "unknown";

    static final String REQUESTS_METRIC = "gateway_requests_total";
    static final String DURATION_METRIC = "gateway_request_duration_seconds";
    static final String UPSTREAM_METRIC = "gateway_upstream_requests_total";
    static final String UPSTREAM_DURATION_METRIC = "gateway_upstream_request_duration_seconds";
    static final String INFLIGHT_REQUESTS_METRIC = "gateway_requests_in_flight";
    static final String INFLIGHT_UPSTREAM_METRIC = "gateway_upstream_requests_in_flight";

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
    private final JvmGcMetrics jvmGcMetrics;
    private final ConcurrentHashMap<RequestKey, Counter> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TimerKey, Timer> requestTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UpstreamKey, Counter> upstreamCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UpstreamTimerKey, Timer> upstreamTimers = new ConcurrentHashMap<>();
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicInteger activeUpstreamRequests = new AtomicInteger();

    /** URL → stable shard label (e.g. "shard-0"); replaced atomically on shard-config reload. */
    private volatile Map<String, String> shardLabels = Collections.emptyMap();

    public GatewayMetrics() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Configure the request-duration histogram once, globally — avoids
        // re-evaluating distribution config on every recordRequest() call.
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (DURATION_METRIC.equals(id.getName()) || UPSTREAM_DURATION_METRIC.equals(id.getName())) {
                    return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .build()
                        .merge(config);
                }
                return config;
            }
        });

        new JvmMemoryMetrics().bindTo(registry);
        // JvmGcMetrics installs a GC notification listener and must be
        // closed when the registry is torn down — otherwise repeated
        // ProxyServer construction (tests, embedded use, in-JVM rolling
        // restarts) accumulates listeners.
        this.jvmGcMetrics = new JvmGcMetrics();
        jvmGcMetrics.bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
        new FileDescriptorMetrics().bindTo(registry);

        Gauge.builder(INFLIGHT_REQUESTS_METRIC, activeRequests, AtomicInteger::get)
            .description("HTTP requests currently being handled by the gateway")
            .register(registry);
        Gauge.builder(INFLIGHT_UPSTREAM_METRIC, activeUpstreamRequests, AtomicInteger::get)
            .description("Requests currently waiting on an upstream aggregator shard")
            .register(registry);
    }

    @Override
    public void close() {
        jvmGcMetrics.close();
        registry.close();
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    public String scrape() {
        return registry.scrape();
    }

    /**
     * Replace the URL-to-shard-label mapping. Called by the proxy at startup
     * and on shard-config hot-reload. Labels not present in the map are
     * reported as {@code "unknown"} on {@link #recordUpstream}.
     */
    public void setShardLabels(Map<String, String> labels) {
        this.shardLabels = labels == null ? Collections.emptyMap() : Map.copyOf(labels);
    }

    public void recordRequest(Outcome outcome, String jsonRpcMethod, int httpStatus, long durationNanos) {
        String method = normalizeMethod(jsonRpcMethod);
        String statusClass = statusClass(httpStatus);
        RequestKey rk = new RequestKey(outcome, method, statusClass);
        requestCounters.computeIfAbsent(rk, this::newRequestCounter).increment();

        TimerKey tk = new TimerKey(outcome, method);
        requestTimers.computeIfAbsent(tk, this::newRequestTimer)
            .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void recordUpstream(String shardTarget, boolean success) {
        String label = resolveShardLabel(shardTarget);
        UpstreamKey key = new UpstreamKey(label, success);
        upstreamCounters.computeIfAbsent(key, this::newUpstreamCounter).increment();
    }

    public void recordUpstreamDuration(String shardTarget, String jsonRpcMethod, boolean success, long durationNanos) {
        String label = resolveShardLabel(shardTarget);
        String method = normalizeMethod(jsonRpcMethod);
        UpstreamTimerKey key = new UpstreamTimerKey(label, success, method);
        upstreamTimers.computeIfAbsent(key, this::newUpstreamTimer)
            .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void incrementActiveRequests() {
        activeRequests.incrementAndGet();
    }

    public void decrementActiveRequests() {
        activeRequests.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    public void incrementActiveUpstreamRequests() {
        activeUpstreamRequests.incrementAndGet();
    }

    public void decrementActiveUpstreamRequests() {
        activeUpstreamRequests.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    public <T> void registerRateLimitBucketGauge(T source, ToDoubleFunction<T> sizeFn) {
        io.micrometer.core.instrument.Gauge.builder("gateway_ratelimit_buckets", source, sizeFn)
            .description("Per-API-key rate-limit buckets currently held in memory. " +
                "Buckets are created on first use and not evicted, so the value " +
                "approximates the count of distinct API keys seen since startup.")
            .register(registry);
    }

    private Counter newRequestCounter(RequestKey k) {
        return Counter.builder(REQUESTS_METRIC)
            .description("Total HTTP requests processed by the gateway")
            .tag("outcome", k.outcome.label())
            .tag("jsonrpc_method", k.method)
            .tag("status_class", k.statusClass)
            .register(registry);
    }

    private Timer newRequestTimer(TimerKey k) {
        return Timer.builder(DURATION_METRIC)
            .description("End-to-end request latency, including upstream call")
            .tag("outcome", k.outcome.label())
            .tag("jsonrpc_method", k.method)
            .register(registry);
    }

    private Counter newUpstreamCounter(UpstreamKey k) {
        return Counter.builder(UPSTREAM_METRIC)
            .description("Requests forwarded to upstream aggregator shards")
            .tag("shard", k.shardLabel)
            .tag("outcome", k.success ? "success" : "error")
            .register(registry);
    }

    private Timer newUpstreamTimer(UpstreamTimerKey k) {
        return Timer.builder(UPSTREAM_DURATION_METRIC)
            .description("Latency waiting for an upstream aggregator shard response")
            .tag("shard", k.shardLabel)
            .tag("outcome", k.success ? "success" : "error")
            .tag("jsonrpc_method", k.method)
            .register(registry);
    }

    private String resolveShardLabel(String shardTarget) {
        if (shardTarget == null) return SHARD_UNKNOWN;
        String label = shardLabels.get(shardTarget);
        return label != null ? label : SHARD_UNKNOWN;
    }

    private static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return METHOD_NONE;
        }
        return KNOWN_METHODS.contains(method) ? method : METHOD_OTHER;
    }

    /**
     * Maps the response status to its standard class label. The proxy
     * disables redirect following but forwards 3xx upstream responses
     * unchanged, so 3xx is exposed as its own class for visibility — folding
     * it into 2xx would hide redirects from dashboards/alerts. The full
     * label set is {@code {2xx, 3xx, 4xx, 5xx, other}}.
     */
    private static String statusClass(int status) {
        if (status >= 500) return "5xx";
        if (status >= 400) return "4xx";
        if (status >= 300) return "3xx";
        if (status >= 200) return "2xx";
        return "other";
    }

    private record RequestKey(Outcome outcome, String method, String statusClass) {}
    private record TimerKey(Outcome outcome, String method) {}
    private record UpstreamKey(String shardLabel, boolean success) {}
    private record UpstreamTimerKey(String shardLabel, boolean success, String method) {}
}
