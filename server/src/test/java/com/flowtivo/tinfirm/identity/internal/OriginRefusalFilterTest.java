package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

/** The origin a browser sends for Tinfirm's own pages, which the MCP endpoint lets through (ADR-0074). */
class OriginRefusalFilterTest {

    @Test
    void theOriginOfThePublicUrlIsItsSchemeHostAndAnOtherThanDefaultPort() {
        assertThat(OriginRefusalFilter.origin(URI.create("https://tinfirm.example.com"))).isEqualTo(
                "https://tinfirm.example.com");
        assertThat(OriginRefusalFilter.origin(URI.create("https://Tinfirm.Example.com:443/"))).isEqualTo(
                "https://tinfirm.example.com");
        assertThat(OriginRefusalFilter.origin(URI.create("http://localhost:8080"))).isEqualTo(
                "http://localhost:8080");
        assertThat(OriginRefusalFilter.origin(URI.create("http://localhost:80/base"))).isEqualTo("http://localhost");
        assertThat(OriginRefusalFilter.origin(URI.create("https://tinfirm.example.com:8443/tinfirm"))).isEqualTo(
                "https://tinfirm.example.com:8443");
    }
}
