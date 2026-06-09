package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the get_block_height response parsing used by the h2c connectivity probe.
 * Pure (no server/Docker) — exercises the accept/reject branches that the testcontainers
 * integration test does not (non-2xx, JSON-RPC error, missing result, malformed body).
 */
@DisplayName("ShardConfigValidator.parseBlockHeightResponse")
class ShardConfigValidatorParseTest {

    @Test
    @DisplayName("accepts a valid get_block_height result (blockNumber as string or number)")
    void acceptsValidResult() throws Exception {
        assertThat(ShardConfigValidator.parseBlockHeightResponse(200,
            "{\"jsonrpc\":\"2.0\",\"result\":{\"blockNumber\":\"42\"},\"id\":1}")).isEqualTo(42L);
        assertThat(ShardConfigValidator.parseBlockHeightResponse(200,
            "{\"jsonrpc\":\"2.0\",\"result\":{\"blockNumber\":7},\"id\":1}")).isEqualTo(7L);
        // a bare numeric result is also accepted
        assertThat(ShardConfigValidator.parseBlockHeightResponse(200,
            "{\"jsonrpc\":\"2.0\",\"result\":99,\"id\":1}")).isEqualTo(99L);
    }

    @Test
    @DisplayName("present-but-unparseable height still passes (best-effort, returns -1)")
    void unparseableHeightDoesNotFail() throws Exception {
        assertThat(ShardConfigValidator.parseBlockHeightResponse(200,
            "{\"jsonrpc\":\"2.0\",\"result\":{\"blockNumber\":\"not-a-number\"},\"id\":1}")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("rejects non-2xx status")
    void rejectsNon2xx() {
        assertThatThrownBy(() -> ShardConfigValidator.parseBlockHeightResponse(503, "{}"))
            .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("rejects a JSON-RPC error response")
    void rejectsJsonRpcError() {
        assertThatThrownBy(() -> ShardConfigValidator.parseBlockHeightResponse(200,
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,\"message\":\"method not found\"},\"id\":1}"))
            .hasMessageContaining("JSON-RPC error");
    }

    @Test
    @DisplayName("rejects a response with no result")
    void rejectsMissingResult() {
        assertThatThrownBy(() -> ShardConfigValidator.parseBlockHeightResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1}"))
            .hasMessageContaining("no result");
        assertThatThrownBy(() -> ShardConfigValidator.parseBlockHeightResponse(200,
            "{\"jsonrpc\":\"2.0\",\"result\":null,\"id\":1}"))
            .hasMessageContaining("no result");
    }

    @Test
    @DisplayName("rejects a malformed (non-JSON) body")
    void rejectsMalformedBody() {
        assertThatThrownBy(() -> ShardConfigValidator.parseBlockHeightResponse(200, "not json at all"))
            .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("a normal aggregator answer does not throw")
    void aggregatorAnswerOk() {
        assertThatCode(() -> ShardConfigValidator.parseBlockHeightResponse(200,
            "{\"jsonrpc\":\"2.0\",\"result\":{\"blockNumber\":\"123456\"},\"id\":1}"))
            .doesNotThrowAnyException();
    }
}
