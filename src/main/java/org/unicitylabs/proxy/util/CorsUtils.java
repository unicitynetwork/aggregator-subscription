package org.unicitylabs.proxy.util;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

import java.util.LinkedHashMap;

/**
 * Utility class for handling CORS (Cross-Origin Resource Sharing) headers.
 */
public final class CorsUtils {
    // CORS header names
    public static final String CORS_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    public static final String CORS_ALLOW_METHODS = "Access-Control-Allow-Methods";
    public static final String CORS_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    public static final String CORS_MAX_AGE = "Access-Control-Max-Age";
    public static final String CORS_EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    public static final String CORS_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";

    // Env var that overrides the CORS allowed-headers list WITHOUT a code change /
    // rebuild. Resolve it via ProxyConfig.getCorsAllowedHeaders() (which reads through
    // the injected EnvironmentProvider, like the rest of the codebase) and pass the
    // result into addCorsHeaders — CorsUtils deliberately does NOT read System.getenv
    // itself, so the value flows through the DI seam and is integration-testable.
    public static final String ENV_CORS_ALLOWED_HEADERS = "CORS_ALLOWED_HEADERS";

    private static final String DEFAULT_ALLOWED_METHODS = "GET, POST, PUT, DELETE, OPTIONS";
    private static final String DEFAULT_MAX_AGE = "3600";

    // Default advertised list. Includes the headers the proxy itself depends on —
    // X-State-ID (shard routing, RequestHandler.HEADER_X_STATE_ID), Content-Type
    // (application/json POSTs), and Authorization / X-API-Key (auth for protected
    // methods). CORS_ALLOWED_HEADERS fully replaces this list (full-replace is
    // intentional — the operator owns it); the README documents which headers must
    // be kept when overriding.
    private static final String DEFAULT_ALLOWED_HEADERS =
        "Content-Type, Authorization, X-API-Key, X-Requested-With, Accept, Origin, X-State-ID";

    private CorsUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Adds CORS headers to the response, advertising the built-in default allowed-headers list.
     */
    public static void addCorsHeaders(Request request, Response response) {
        addCorsHeaders(request, response, null, DEFAULT_ALLOWED_HEADERS);
    }

    /**
     * Adds CORS headers to the response with extra exposed headers, advertising the default list.
     */
    public static void addCorsHeaders(Request request, Response response, String exposeHeaders) {
        addCorsHeaders(request, response, exposeHeaders, DEFAULT_ALLOWED_HEADERS);
    }

    /**
     * Adds CORS headers to the response based on the request's Origin header.
     *
     * @param request the incoming request
     * @param response the response to add headers to
     * @param exposeHeaders additional headers to expose to the client (comma-separated), or null
     * @param allowedHeaders the Access-Control-Allow-Headers value to advertise (typically
     *                       {@code ProxyConfig.getCorsAllowedHeaders()}); falls back to the
     *                       built-in default when null/blank
     */
    public static void addCorsHeaders(Request request, Response response, String exposeHeaders, String allowedHeaders) {
        // Get the Origin header from the request
        var originField = request.getHeaders().getField("Origin");
        String origin = originField != null ? originField.getValue() : "*";

        // Allow the requesting origin (or * if no Origin header)
        response.getHeaders().put(CORS_ALLOW_ORIGIN, origin);

        // Allow common HTTP methods
        response.getHeaders().put(CORS_ALLOW_METHODS, DEFAULT_ALLOWED_METHODS);

        // Advertise the allowed request headers (incl. the proxy-required ones)
        response.getHeaders().put(CORS_ALLOW_HEADERS,
            (allowedHeaders == null || allowedHeaders.isBlank()) ? DEFAULT_ALLOWED_HEADERS : allowedHeaders);

        // Cache preflight response for 1 hour (3600 seconds)
        response.getHeaders().put(CORS_MAX_AGE, DEFAULT_MAX_AGE);

        // Expose headers to JavaScript if specified
        if (exposeHeaders != null && !exposeHeaders.isEmpty()) {
            response.getHeaders().put(CORS_EXPOSE_HEADERS, exposeHeaders);
        }
    }

    /**
     * Resolves the effective Access-Control-Allow-Headers value from a raw
     * {@code CORS_ALLOWED_HEADERS} env value. Pure / side-effect-free (no env access) so it
     * is unit-testable. Full-replace semantics: a non-blank override fully determines the
     * advertised list (the operator owns it). Behaviour:
     * <ul>
     *   <li>null / blank / only-separators -&gt; the built-in default list;</li>
     *   <li>otherwise the override, normalized per element (each entry trimmed; empty and
     *       case-insensitively duplicate names dropped, first-seen casing kept).</li>
     * </ul>
     *
     * @param envValue the raw {@code CORS_ALLOWED_HEADERS} value (may be null/blank)
     * @return the allowed-headers list to advertise
     */
    public static String resolveAllowedHeaders(String envValue) {
        if (envValue == null || envValue.isBlank()) {
            return DEFAULT_ALLOWED_HEADERS;
        }
        // case-insensitive dedup; first-seen casing + insertion order preserved
        LinkedHashMap<String, String> byLower = new LinkedHashMap<>();
        for (String h : envValue.split(",")) {
            String t = h.trim();
            if (!t.isEmpty()) {
                byLower.putIfAbsent(t.toLowerCase(), t);
            }
        }
        return byLower.isEmpty() ? DEFAULT_ALLOWED_HEADERS : String.join(", ", byLower.values());
    }

    /**
     * Checks if the given header name is a CORS header.
     *
     * @param name the header name to check
     * @return true if it's a CORS header, false otherwise
     */
    public static boolean isCorsHeader(String name) {
        return name.equalsIgnoreCase(CORS_ALLOW_ORIGIN) ||
               name.equalsIgnoreCase(CORS_ALLOW_METHODS) ||
               name.equalsIgnoreCase(CORS_ALLOW_HEADERS) ||
               name.equalsIgnoreCase(CORS_MAX_AGE) ||
               name.equalsIgnoreCase(CORS_EXPOSE_HEADERS) ||
               name.equalsIgnoreCase(CORS_ALLOW_CREDENTIALS);
    }
}
