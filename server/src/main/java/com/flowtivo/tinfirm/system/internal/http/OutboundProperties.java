package com.flowtivo.tinfirm.system.internal.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.flowtivo.tinfirm.shared.net.GuardedHosts;

/**
 * Settings of the outbound client of calls to systems (CONVENTIONS §4; the variables keep their names from the
 * connections they were made for).
 *
 * @param allowHttp             accept http base URLs ({@code TINFIRM_CONNECTION_ALLOW_HTTP}); off by default, because
 *                              a token would travel in clear text
 * @param allowPrivateAddresses accept company systems that point at loopback, link-local and private addresses
 *                              ({@code TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES}); off by default, against requests
 *                              forged into the server's own network. Never for a personal MCP server, which any
 *                              member registers (the security review of the redesign, row 12)
 * @param timeout               connect and request timeout of one call to a system ({@code TINFIRM_CONNECTION_TIMEOUT})
 * @param toolTimeout           how long one tool call or change request may take in all, its requests together
 *                              ({@code TINFIRM_RUN_TOOL_TIMEOUT}); at most {@code PT60S}, since a runner host waits 5 s
 *                              longer for the answer of a tool call
 * @param allowedPrivateHosts   TEST ONLY, never in production ({@code tinfirm.connection.allowed-private-hosts}, no
 *                              documented variable): host names that may resolve to private addresses for a company
 *                              system, never for a personal server, as {@code tinfirm.oauth.cimd.allowed-private-hosts}
 *                              is for client metadata documents. A test serves a system from a stub on
 *                              {@code localhost} and still sees the guard refuse every other private address (an IP
 *                              literal such as {@code 127.0.0.1}). Spring's relaxed binding would also take it from
 *                              {@code TINFIRM_CONNECTION_ALLOWEDPRIVATEHOSTS}, so {@link OutboundHttp} refuses to start
 *                              with it set outside the {@code test} profile
 * @param internalHosts         the operator's internal hosts ({@code TINFIRM_CONNECTION_INTERNAL_HOSTS}, ADR-0066):
 *                              host names (or IPv4 literals) of services on the deployment's own private network, such
 *                              as an MCP server, that a company system may name. Such a host may resolve to private
 *                              addresses only, never to public, loopback, link-local, unspecified or multicast ones,
 *                              and may be called over http, its traffic staying on that network. Never for a personal
 *                              MCP server, and nothing else changes: every other host keeps the guard
 */
@Validated
@ConfigurationProperties("tinfirm.connection")
public record OutboundProperties(@DefaultValue("false") boolean allowHttp,
        @DefaultValue("false") boolean allowPrivateAddresses, @NotNull @DefaultValue("PT20S") Duration timeout,
        @NotNull @DefaultValue("PT60S") Duration toolTimeout, @DefaultValue Set<String> allowedPrivateHosts,
        @DefaultValue Set<String> internalHosts) {

    /** The longest {@link #toolTimeout}. */
    public static final Duration MAX_TOOL_TIMEOUT = Duration.ofSeconds(60);

    /** An internal host: a DNS name or an IPv4 literal, without a scheme, a port, a path or a wildcard. */
    private static final Pattern INTERNAL_HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252})");

    public OutboundProperties {
        if (toolTimeout.isNegative() || toolTimeout.isZero() || toolTimeout.compareTo(MAX_TOOL_TIMEOUT) > 0) {
            throw new IllegalArgumentException("The tool timeout (TINFIRM_RUN_TOOL_TIMEOUT) is more than zero and at "
                    + "most PT60S, not " + toolTimeout);
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String entry : internalHosts) {
            String host = entry.strip();
            if (host.isEmpty()) {
                continue;
            }
            if (!INTERNAL_HOST.matcher(host).matches()) {
                throw new IllegalArgumentException("TINFIRM_CONNECTION_INTERNAL_HOSTS names host names only, separated "
                        + "by commas, without a scheme, a port, a path or a wildcard (e.g. mcp-server,"
                        + "mcp-server.default.svc.cluster.local), not " + host);
            }
            hosts.add(GuardedHosts.normalize(host));
        }
        internalHosts = Set.copyOf(hosts);
    }

    /**
     * Whether the URL's host is one of the operator's internal hosts ({@link #internalHosts}), as the outbound client
     * decides it for a company system; false for a URL without a host or one that does not parse.
     */
    public boolean internalHost(@Nullable String url) {
        if (url == null) {
            return false;
        }
        try {
            String host = new URI(url).getHost();
            return host != null && internalHosts.contains(GuardedHosts.normalize(host));
        }
        catch (URISyntaxException ex) {
            return false;
        }
    }
}
