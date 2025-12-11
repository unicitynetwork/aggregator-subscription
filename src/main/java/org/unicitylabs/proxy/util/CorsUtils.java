package org.unicitylabs.proxy.util;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

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

    // Default CORS values
    private static final String DEFAULT_ALLOWED_METHODS = "GET, POST, PUT, DELETE, OPTIONS";
    private static final String DEFAULT_ALLOWED_HEADERS = "Content-Type, Authorization, X-API-Key, X-Requested-With, Accept, Origin";
    private static final String DEFAULT_MAX_AGE = "3600";

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

        // Allow common headers including auth headers
        response.getHeaders().put(CORS_ALLOW_HEADERS, DEFAULT_ALLOWED_HEADERS);

        // Cache preflight response for 1 hour (3600 seconds)
        response.getHeaders().put(CORS_MAX_AGE, DEFAULT_MAX_AGE);

        // Expose headers to JavaScript if specified
        if (exposeHeaders != null && !exposeHeaders.isEmpty()) {
            response.getHeaders().put(CORS_EXPOSE_HEADERS, exposeHeaders);
        }
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
