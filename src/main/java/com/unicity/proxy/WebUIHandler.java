package com.unicity.proxy;

import com.unicity.proxy.repository.ApiKeyRepository;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpHeader;

import java.nio.ByteBuffer;
import java.util.UUID;

public class WebUIHandler extends Handler.Abstract {

    private final ApiKeyRepository repository = new ApiKeyRepository();
    
    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String path = request.getHttpURI().getPath();
        
        if ("/".equals(path) || "/index.html".equals(path)) {
            handleHomePage(response, callback, null);
            return true;
        } else if ("/generate".equals(path) && "POST".equals(request.getMethod())) {
            handleGenerateKey(response, callback);
            return true;
        }
        
        return false;
    }
    
    private void handleHomePage(Response response, Callback callback, String generatedKey) {
        String html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>API Key Generator</title>
                <style>
                    body {
                        background-color: #001a33;
                        color: white;
                        font-family: Arial, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                        margin: 0;
                    }
                    .container {
                        text-align: center;
                        padding: 40px;
                        background-color: rgba(0, 40, 80, 0.5);
                        border-radius: 10px;
                    }
                    button {
                        background-color: #4CAF50;
                        color: white;
                        padding: 15px 32px;
                        text-align: center;
                        text-decoration: none;
                        display: inline-block;
                        font-size: 18px;
                        margin: 20px 2px;
                        cursor: pointer;
                        border: none;
                        border-radius: 5px;
                        transition: background-color 0.3s;
                    }
                    button:hover {
                        background-color: #45a049;
                    }
                    .api-key {
                        margin-top: 30px;
                        padding: 20px;
                        background-color: rgba(0, 0, 0, 0.3);
                        border-radius: 5px;
                        font-family: monospace;
                        font-size: 16px;
                        word-break: break-all;
                    }
                    .success {
                        color: #4CAF50;
                        margin-bottom: 10px;
                        font-weight: bold;
                    }
                    h1 {
                        margin-bottom: 30px;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>API Key Generator</h1>
                    <form action="/generate" method="POST">
                        <button type="submit">Generate New API Key</button>
                    </form>
            """ + (generatedKey != null ? """
                    <div class="api-key">
                        <div class="success">Success! Your new API key:</div>
                        """ + generatedKey + """
                        <div style="margin-top: 15px; font-size: 14px; color: #ffaa00;">
                            Note: This key requires payment activation before use.
                        </div>
                    </div>
            """ : "") + """
                </div>
            </body>
            </html>
            """;
        
        response.setStatus(HttpStatus.OK_200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html; charset=UTF-8");
        response.write(true, ByteBuffer.wrap(html.getBytes()), callback);
    }
    
    private void handleGenerateKey(Response response, Callback callback) {
        String newApiKey = "key_" + UUID.randomUUID().toString();
        
        try {
            repository.createWithoutPlan(newApiKey);
            CachedApiKeyManager.getInstance().removeCacheEntry(newApiKey);
            handleHomePage(response, callback, newApiKey);
        } catch (Exception e) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            response.write(true, ByteBuffer.wrap("Error generating API key".getBytes()), callback);
        }
    }
}