package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.shard.ShardConfig;
import org.unicitylabs.proxy.shard.ShardInfo;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jetty.http.HttpStatus.OK_200;
import static org.junit.jupiter.api.Assertions.*;

public class ConfigHandlerIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper objectMapper = ObjectMapperUtils.createObjectMapper();

    @Test
    @DisplayName("GET /config/shards returns current shard configuration")
    void testGetShardConfigReturnsCurrentConfig() throws Exception {
        insertShardConfig(new ShardConfig(1, List.of(
            new ShardInfo(2, "http://shard2.example.com:8080"),
            new ShardInfo(3, "http://shard3.example.com:9090")
        )));

        HttpResponse<String> response = performGetRequest("/config/shards");

        assertEquals(OK_200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));

        assertEquals(
            """
            {
              "version" : 1,
              "shards" : [ {
                "id" : 2,
                "url" : "http://shard2.example.com:8080"
              }, {
                "id" : 3,
                "url" : "http://shard3.example.com:9090"
              } ]
            }""",
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(objectMapper.readTree(response.body())));
    }

    @Test
    @DisplayName("Should handle CORS preflight OPTIONS request for /config/shards")
    void testCorsPreflightRequest() throws Exception {
        CorsTestSupport corsTestSupport = new CorsTestSupport(httpClient, getProxyUrl());
        corsTestSupport.assertCorsPreflightRequest("/config/shards", "https://example.com");
    }

    @Test
    @DisplayName("Should include CORS headers in /config/shards response")
    void testCorsHeadersInRegularResponse() throws Exception {
        insertShardConfig(new ShardConfig(1, List.of(
            new ShardInfo(1, "http://shard1.example.com:8080")
        )));

        CorsTestSupport corsTestSupport = new CorsTestSupport(httpClient, getProxyUrl());
        HttpResponse<String> response = corsTestSupport.assertCorsHeadersInGetResponse("/config/shards", "https://unicitynetwork.github.io");

        assertThat(response.statusCode()).isEqualTo(OK_200);
    }
}
