package com.flowtivo.tinfirm.system.internal.http;

import java.util.Set;

/**
 * TEST ONLY: the host names that a personal MCP server may resolve to private addresses at, so that the integration
 * tests serve personal servers from stubs on {@code localhost} ({@code support.TestOutbound}). No property and no
 * variable makes one: only a bean does, which only the tests define, and {@link OutboundHttp} refuses to start with one
 * outside the {@code test} profile. In production a personal server never reaches a private address (the security
 * review of the redesign, row 12), whatever {@code tinfirm.connection.*} says.
 */
public record PersonalStubHosts(Set<String> hosts) {

    public PersonalStubHosts {
        hosts = Set.copyOf(hosts);
    }
}
