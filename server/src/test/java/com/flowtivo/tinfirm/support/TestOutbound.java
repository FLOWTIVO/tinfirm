package com.flowtivo.tinfirm.support;

import java.util.Set;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.flowtivo.tinfirm.system.internal.http.PersonalStubHosts;

/**
 * TEST FIXTURE ONLY: the integration tests serve personal MCP servers from stubs on {@code localhost}, which a personal
 * server never reaches in production, nor through {@code tinfirm.connection.allowed-private-hosts}. This bean names the
 * stubs' host; every other private address stays refused for personal servers (an IP literal such as
 * {@code 127.0.0.1}), so the tests see the rule. The server refuses to start with it outside the {@code test} profile.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestOutbound {

    @Bean
    PersonalStubHosts personalStubHosts() {
        return new PersonalStubHosts(Set.of("localhost"));
    }
}
