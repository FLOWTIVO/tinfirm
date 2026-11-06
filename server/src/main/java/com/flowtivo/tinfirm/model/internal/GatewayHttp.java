package com.flowtivo.tinfirm.model.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.net.AddressGuard;
import com.flowtivo.tinfirm.shared.net.GuardedHosts;
import com.flowtivo.tinfirm.shared.net.GuardedResolverProvider;
import com.flowtivo.tinfirm.shared.net.OutboundProxy;
import com.flowtivo.tinfirm.shared.net.OutboundUrls;

/**
 * The server's HTTP client to companies' model gateways, for their admin API and the model requests the relay
 * forwards. The deployment's bundled gateway is the operator's address and is reached as configured. A company's own
 * gateway ({@code EXTERNAL}, or {@code PROVIDED} by its model URL, ADR-0094) is an address an admin entered: it is held
 * to the outbound guard of the company's systems (CONVENTIONS §14.4), with the operator's choices for them
 * ({@code TINFIRM_CONNECTION_ALLOW_HTTP}, {@code TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES}): https unless http is
 * allowed, no loopback, link-local or private address unless allowed, checked when it is entered and before every
 * request, and guarded against a changed DNS answer while a request is in flight. Redirects are never followed.
 */
@Component
class GatewayHttp {

    private final HttpClient http;

    private final boolean allowHttp;

    private final boolean allowPrivateAddresses;

    /** TEST ONLY ({@code tinfirm.connection.allowed-private-hosts}): hosts that may resolve to private addresses. */
    private final Set<String> allowedPrivateHosts;

    /**
     * The JVM's proxy settings never apply; an explicit {@code tinfirm.outbound.proxy} carries a company's own
     * gateway's requests, never the bundled one's, which is on the deployment's own network ({@link OutboundProxy}).
     */
    GatewayHttp(ModelProperties properties, @Value("${tinfirm.connection.allow-http:false}") boolean allowHttp,
            @Value("${tinfirm.connection.allow-private-addresses:false}") boolean allowPrivateAddresses,
            @Value("${tinfirm.connection.allowed-private-hosts:}") Set<String> allowedPrivateHosts,
            Environment environment) {
        Set<String> bundled = new HashSet<>();
        addHost(bundled, properties.gatewayAdminUrl());
        addHost(bundled, properties.bundledModelUrl());
        this.http = HttpClient.newBuilder()
            .connectTimeout(properties.adminTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(OutboundProxy.selector(environment, bundled))
            .build();
        this.allowHttp = allowHttp;
        this.allowPrivateAddresses = allowPrivateAddresses;
        this.allowedPrivateHosts = allowedPrivateHosts.stream()
            .filter(host -> !host.isBlank())
            .map(GuardedHosts::normalize)
            .collect(Collectors.toUnmodifiableSet());
    }

    HttpClient client() {
        return http;
    }

    /** Why the URL an admin entered may not be a company's gateway, or null when it may. Resolves the host. */
    @Nullable String problem(String url) {
        return OutboundUrls.problem(url, new OutboundUrls.Policy(allowHttp, allowPrivate(host(url)),
                "the operator can allow http with TINFIRM_CONNECTION_ALLOW_HTTP"));
    }

    /**
     * Checks the gateway's address before a request and guards it while the request is in flight; close the
     * registration when the answer was read.
     *
     * @throws LiteLlmException when the address may not be reached
     */
    GuardedHosts.Registration guard(Gateway gateway) {
        if (gateway.bundled()) {
            return GuardedHosts.Registration.NONE;
        }
        String host = host(gateway.baseUrl());
        if (host == null) {
            throw LiteLlmException.refused("The gateway's URL has no host");
        }
        boolean allowPrivate = allowPrivate(host);
        String problem = AddressGuard.problem(host, allowPrivate);
        if (problem != null) {
            throw LiteLlmException.refused("The gateway may not be reached: " + problem);
        }
        if (allowPrivate) {
            return GuardedHosts.Registration.NONE;
        }
        if (!GuardedResolverProvider.installed()) {
            throw LiteLlmException.refused("The connect-time address guard is not installed");
        }
        return GuardedHosts.GLOBAL.open(host);
    }

    private boolean allowPrivate(@Nullable String host) {
        return allowPrivateAddresses || host != null && allowedPrivateHosts.contains(GuardedHosts.normalize(host));
    }

    private static void addHost(Set<String> hosts, @Nullable String url) {
        String host = url != null && !url.isBlank() ? host(url.strip()) : null;
        if (host != null) {
            hosts.add(host);
        }
    }

    private static @Nullable String host(String url) {
        try {
            return new URI(url).getHost();
        }
        catch (URISyntaxException ex) {
            return null;
        }
    }
}
