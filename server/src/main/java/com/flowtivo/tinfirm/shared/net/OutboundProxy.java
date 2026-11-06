package com.flowtivo.tinfirm.shared.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;

/**
 * The proxy of the server's guarded outbound clients (the systems' {@code OutboundHttp}, the model gateway's
 * {@code GatewayHttp}, the identity module's client metadata fetch): none, unless the operator names one in
 * {@code tinfirm.outbound.proxy} ({@code TINFIRM_OUTBOUND_PROXY}, an {@code http://host:port} URL). The JVM's own proxy
 * settings ({@code https.proxyHost}, {@code java.net.useSystemProxies}) never apply to these clients: a proxy resolves
 * the target's name itself, so the connect-time guard ({@link GuardedResolverProvider}) would no longer see the lookup,
 * and a DNS answer that changed since the check could reach a private address. With an explicit proxy the guard still
 * resolves and checks every host before each request, and the operator's proxy must refuse private addresses itself
 * (the operations guide, {@code systems.md}).
 */
public final class OutboundProxy {

    /** The property that names the explicit proxy. */
    public static final String PROPERTY = "tinfirm.outbound.proxy";

    private OutboundProxy() {
    }

    /** The proxy the environment names, or {@link HttpClient.Builder#NO_PROXY}. */
    public static ProxySelector selector(Environment environment) {
        return selector(environment.getProperty(PROPERTY), Set.of());
    }

    /**
     * The proxy the environment names, but for the hosts named here, reached directly (a service of the deployment's
     * own network); {@link HttpClient.Builder#NO_PROXY} without one.
     */
    public static ProxySelector selector(Environment environment, Set<String> directHosts) {
        return selector(environment.getProperty(PROPERTY), directHosts);
    }

    /**
     * The proxy of a configured value, or {@link HttpClient.Builder#NO_PROXY} for a blank one.
     *
     * @throws IllegalStateException for a value that is not an {@code http://host:port} URL
     */
    public static ProxySelector selector(@Nullable String configured, Set<String> directHosts) {
        if (configured == null || configured.isBlank()) {
            return HttpClient.Builder.NO_PROXY;
        }
        InetSocketAddress address = address(configured.strip());
        Set<String> direct = directHosts.stream()
            .filter(host -> host != null && !host.isBlank())
            .map(GuardedHosts::normalize)
            .collect(Collectors.toUnmodifiableSet());
        Proxy proxy = new Proxy(Proxy.Type.HTTP, address);
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String host = uri.getHost();
                return host != null && direct.contains(GuardedHosts.normalize(host)) ? List.of(Proxy.NO_PROXY)
                        : List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException ex) {
                // One proxy, no other to fall back to: the request fails as any unreachable host's does.
            }
        };
    }

    private static InetSocketAddress address(String configured) {
        URI uri;
        try {
            uri = new URI(configured);
        }
        catch (URISyntaxException ex) {
            throw invalid();
        }
        if (!"http".equals(uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : null)
                || uri.getHost() == null || uri.getPort() < 1 || uri.getRawUserInfo() != null
                || uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid();
        }
        return new InetSocketAddress(uri.getHost(), uri.getPort());
    }

    /** The value is not echoed: it may hold credentials. */
    private static IllegalStateException invalid() {
        return new IllegalStateException("TINFIRM_OUTBOUND_PROXY (tinfirm.outbound.proxy) names the proxy of the "
                + "server's outbound requests as http://host:port, without credentials, a path or a query");
    }
}
