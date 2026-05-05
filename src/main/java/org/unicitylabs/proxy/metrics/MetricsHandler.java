package org.unicitylabs.proxy.metrics;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class MetricsHandler extends Handler.Abstract {
    public static final String PATH = "/metrics";
    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final GatewayMetrics metrics;

    public MetricsHandler(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        if (!PATH.equals(request.getHttpURI().getPath())) {
            return false;
        }
        byte[] body = metrics.scrape().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpStatus.OK_200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, CONTENT_TYPE);
        response.write(true, ByteBuffer.wrap(body), callback);
        return true;
    }
}
