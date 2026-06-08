package org.unicitylabs.proxy.util;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

import java.util.Arrays;
import java.util.stream.Collectors;

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
    // rebuild. When set (non-blank) its value REPLACES DEFAULT_ALLOWED_HEADERS; when
    // unset the built-in default (which already includes X-State-ID) is used.
    public static final String ENV_CORS_ALLOWED_HEADERS = "CORS_ALLOWED_HEADERS";

    // Default CORS values.
    // X-State-ID MUST be allowed: the proxy reads it (RequestHandler.HEADER_X_STATE_ID)
    // to route a request to the owning shard without parsing the JSON-RPC body. It is a
    // non-simple request header, so a browser only sends it once the CORS preflight
    // advertises it here — otherwise the preflight fails with "Request header field
    // x-state-id is not allowed by Access-Control-Allow-Headers".
    private static final String DEFAULT_ALLOWED_METHODS = "GET, POST, PUT, DELETE, OPTIONS";
    private static final String DEFAULT_ALLOWED_HEADERS = "Content-Type, Authorization, X-API-Key, X-Requested-With, Accept, Origin, X-State-ID";
    private static final String DEFAULT_MAX_AGE = "3600";

    // Resolved once at class load — process environment is static for the lifetime
    // of the JVM, so there is no benefit to re-reading it on every request. Env
    // access goes through EnvironmentProvider to match the rest of the codebase.
    private static final String ALLOWED_HEADERS = resolveAllowedHeaders(
        EnvironmentProvider.SystemEnvironmentProvider.getInstance().getEnv(ENV_CORS_ALLOWED_HEADERS));

    private CorsUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Adds CORS headers to the response based on the request's Origin header.
     *
     * @param request the incoming request
     * @param response the response to add headers to
     */
    public static void addCorsHeaders(Request request, Response response) {
        addCorsHeaders(request, response, null);
    }

    /**
     * Adds CORS headers to the response based on the request's Origin header.
     *
     * @param request the incoming request
     * @param response the response to add headers to
     * @param exposeHeaders additional headers to expose to the client (comma-separated), or null
     */
    public static void addCorsHeaders(Request request, Response response, String exposeHeaders) {
        // Get the Origin header from the request
        var originField = request.getHeaders().getField("Origin");
        String origin = originField != null ? originField.getValue() : "*";

        // Allow the requesting origin (or * if no Origin header)
        response.getHeaders().put(CORS_ALLOW_ORIGIN, origin);

        // Allow common HTTP methods
        response.getHeaders().put(CORS_ALLOW_METHODS, DEFAULT_ALLOWED_METHODS);

        // Allow common headers including auth + the X-State-ID routing header
        // (overridable via the CORS_ALLOWED_HEADERS env var)
        response.getHeaders().put(CORS_ALLOW_HEADERS, ALLOWED_HEADERS);

        // Cache preflight response for 1 hour (3600 seconds)
        response.getHeaders().put(CORS_MAX_AGE, DEFAULT_MAX_AGE);

        // Expose headers to JavaScript if specified
        if (exposeHeaders != null && !exposeHeaders.isEmpty()) {
            response.getHeaders().put(CORS_EXPOSE_HEADERS, exposeHeaders);
        }
    }

    /**
     * Resolves the effective CORS allowed-headers list: the {@code CORS_ALLOWED_HEADERS}
     * env value when set (non-blank), otherwise the built-in default (which includes
     * {@code X-State-ID}). Package-private and pure so it can be unit-tested without
     * mutating the process environment.
     *
     * @param envValue the raw {@code CORS_ALLOWED_HEADERS} value (may be null/blank)
     * @return the allowed-headers list to advertise in {@code Access-Control-Allow-Headers}
     */
    static String resolveAllowedHeaders(String envValue) {
        if (envValue == null || envValue.isBlank()) {
            return DEFAULT_ALLOWED_HEADERS;
        }
        // Normalize per-element: trim each header and drop empties, so stray inner
        // spaces or consecutive/trailing commas (e.g. "Content-Type,, X-State-ID ,")
        // can't produce a malformed Access-Control-Allow-Headers value.
        String resolved = Arrays.stream(envValue.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining(", "));
        return resolved.isEmpty() ? DEFAULT_ALLOWED_HEADERS : resolved;
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
