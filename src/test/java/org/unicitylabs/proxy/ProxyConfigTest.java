package org.unicitylabs.proxy;

import org.junit.jupiter.api.Test;
import org.unicitylabs.proxy.util.TestEnvironmentProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.unicitylabs.proxy.ProxyConfig.GATEWAY_UPSTREAM_H2C;

class ProxyConfigTest {

    @Test
    void upstreamH2cUsesCliFlagWhenEnvironmentIsUnset() {
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        config.setUpstreamH2cEnabled(true);

        assertThat(config.isUpstreamH2cEnabled()).isTrue();
    }

    @Test
    void upstreamH2cEnvironmentOverridesCliFlag() {
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider(Map.of(
            GATEWAY_UPSTREAM_H2C, "false"
        )));
        config.setUpstreamH2cEnabled(true);

        assertThat(config.isUpstreamH2cEnabled()).isFalse();
    }

    @Test
    void upstreamH2cRejectsInvalidEnvironmentValue() {
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider(Map.of(
            GATEWAY_UPSTREAM_H2C, "maybe"
        )));

        assertThrows(IllegalArgumentException.class, config::isUpstreamH2cEnabled);
    }

    @Test
    void upstreamH2cWorkerThreadsDefaultToVirtualThreadsWhenServerUsesVirtualThreads() {
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());

        assertThat(config.getUpstreamH2cWorkerThreads()).isZero();
    }

    @Test
    void upstreamH2cWorkerThreadsDefaultToServerWorkerThreadsWhenFixed() {
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        config.setWorkerThreads(64);

        assertThat(config.getUpstreamH2cWorkerThreads()).isEqualTo(64);
        assertThat(config.toString()).contains("upstreamH2cWorkerThreads=64");
    }

    @Test
    void upstreamH2cWorkerThreadsCanBeOverridden() {
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        config.setWorkerThreads(64);
        config.setUpstreamH2cWorkerThreads(16);

        assertThat(config.getUpstreamH2cWorkerThreads()).isEqualTo(16);
    }

    @Test
    void upstreamH2cWorkerThreadsRejectInvalidValue() {
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        config.setUpstreamH2cWorkerThreads(-2);

        assertThrows(IllegalArgumentException.class, config::getUpstreamH2cWorkerThreads);
    }

    @Test
    void upstreamResponseMaxBufferDefaultsAboveRequestPayloadLimit() {
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());

        assertThat(config.getUpstreamResponseMaxBufferBytes()).isGreaterThan(RequestHandler.MAX_PAYLOAD_SIZE_BYTES);
        assertThat(config.toString()).contains("upstreamResponseMaxBufferBytes=");
    }

    @Test
    void upstreamResponseMaxBufferRejectsInvalidValue() {
        ProxyConfig config = new ProxyConfig(new TestEnvironmentProvider());
        config.setUpstreamResponseMaxBufferBytes(0);

        assertThrows(IllegalArgumentException.class, config::getUpstreamResponseMaxBufferBytes);
    }
}
