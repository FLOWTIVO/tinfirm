package com.flowtivo.tinfirm.shared.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The JVM's resolver (JEP 418), registered in {@code META-INF/services/java.net.spi.InetAddressResolverProvider},
 * which guards the lookups that {@code java.net.http.HttpClient} makes when it connects (CONVENTIONS §14.4). A guarded
 * request (to a connection, or for an OAuth client's metadata document) checks every address of its host before it is
 * sent ({@link AddressGuard}), but
 * the client resolves the host again to connect, so a DNS answer that changes in between (DNS rebinding) could
 * reach a private address. While a request to a host is in flight ({@link GuardedHosts}), this resolver answers
 * the lookups of that host with the built-in resolver's addresses minus loopback, link-local, private, shared,
 * unique-local, unspecified and multicast ones (minus all but the private ones for one of the operator's internal hosts,
 * ADR-0066; minus every address that one of the requests in flight may not reach), and with {@link UnknownHostException} when none is left; so the address the client connects to is one
 * that passed the guard. Every other lookup, and every reverse lookup, is the built-in resolver's, untouched. IP
 * literals never reach a resolver: the guard checks them directly.
 *
 * <p>The JDK loads the provider with the thread context class loader of the first lookup of the JVM, which is the
 * application's class loader both in the image (the classes on the class path) and in the executable jar (the
 * Spring Boot launcher sets it before the first lookup). The connection module's outbound client refuses to start when
 * it finds the provider not installed and private addresses are not allowed, and the metadata document fetch refuses
 * to send.
 */
public final class GuardedResolverProvider extends InetAddressResolverProvider {

    private static volatile boolean installed;

    /** Whether the JVM's resolver is this provider's, so connect-time lookups are guarded. */
    public static boolean installed() {
        return installed;
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        installed = true;
        return new GuardedResolver(configuration.builtinResolver(), GuardedHosts.GLOBAL);
    }

    @Override
    public String name() {
        return "tinfirm-outbound-guard";
    }

    /** The built-in resolver, with the addresses of the hosts of requests in flight filtered. */
    static final class GuardedResolver implements InetAddressResolver {

        private final InetAddressResolver delegate;

        private final GuardedHosts hosts;

        GuardedResolver(InetAddressResolver delegate, GuardedHosts hosts) {
            this.delegate = delegate;
            this.hosts = hosts;
        }

        @Override
        public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
            Set<AddressGuard.Reach> reaches = hosts.reaches(host);
            if (reaches.isEmpty()) {
                return delegate.lookupByName(host, lookupPolicy);
            }
            List<InetAddress> allowed = delegate.lookupByName(host, lookupPolicy)
                .filter(address -> reaches.stream().allMatch(reach -> AddressGuard.problem(address, reach) == null))
                .toList();
            if (allowed.isEmpty()) {
                throw new UnknownHostException(host + ": no address an outbound request may go to");
            }
            return allowed.stream();
        }

        @Override
        public String lookupByAddress(byte[] addr) throws UnknownHostException {
            return delegate.lookupByAddress(addr);
        }
    }
}
