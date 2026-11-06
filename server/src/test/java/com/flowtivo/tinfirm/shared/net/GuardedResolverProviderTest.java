package com.flowtivo.tinfirm.shared.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The JVM's resolver that guards the lookups of connection hosts (CONVENTIONS §14.4): while a request to a host is in
 * flight, its lookups lose the addresses no connection may reach, so a DNS answer that changed since the guard
 * checked it (DNS rebinding) cannot reach them; every other lookup is the built-in resolver's.
 */
class GuardedResolverProviderTest {

    private static final LookupPolicy POLICY = LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6);

    /** A built-in resolver that answers from a table and records what it was asked. */
    private static final class FakeResolver implements InetAddressResolver {

        private final Map<String, List<String>> answers;

        final List<String> asked = new ArrayList<>();

        FakeResolver(Map<String, List<String>> answers) {
            this.answers = answers;
        }

        @Override
        public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
            asked.add(host);
            List<String> addresses = answers.get(host);
            if (addresses == null) {
                throw new UnknownHostException(host);
            }
            List<InetAddress> resolved = new ArrayList<>();
            for (String address : addresses) {
                resolved.add(InetAddress.getByAddress(host, InetAddress.getByName(address).getAddress()));
            }
            return resolved.stream();
        }

        @Override
        public String lookupByAddress(byte[] addr) {
            return "reverse.example";
        }
    }

    private static List<String> addresses(Stream<InetAddress> resolved) {
        return resolved.map(InetAddress::getHostAddress).toList();
    }

    @Test
    void aRegisteredHostThatRebindsToAPrivateAddressIsRefused() throws UnknownHostException {
        FakeResolver builtin = new FakeResolver(Map.of("jira.example.com", List.of("10.0.0.7"),
                "gitlab.example.com", List.of("127.0.0.1", "::1", "169.254.169.254", "100.64.1.1", "fd00::5",
                        "0.0.0.0", "224.0.0.1")));
        GuardedHosts hosts = new GuardedHosts();
        InetAddressResolver resolver = new GuardedResolverProvider.GuardedResolver(builtin, hosts);

        try (GuardedHosts.Registration jira = hosts.open("jira.example.com");
                GuardedHosts.Registration gitlab = hosts.open("GitLab.Example.COM.")) {
            assertThatThrownBy(() -> resolver.lookupByName("jira.example.com", POLICY))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("no address an outbound request may go to");
            assertThatThrownBy(() -> resolver.lookupByName("gitlab.example.com", POLICY))
                .isInstanceOf(UnknownHostException.class);
            assertThatThrownBy(() -> resolver.lookupByName("JIRA.example.com.", POLICY))
                .isInstanceOf(UnknownHostException.class);
        }

        // Once the requests are done, the host is the built-in resolver's again.
        assertThat(addresses(resolver.lookupByName("jira.example.com", POLICY))).containsExactly("10.0.0.7");
        assertThat(hosts.guarded("jira.example.com")).isFalse();
    }

    @Test
    void aRegisteredHostKeepsItsPublicAddressesOnly() throws UnknownHostException {
        FakeResolver builtin = new FakeResolver(Map.of("github.example.com",
                List.of("192.168.1.20", "140.82.112.3", "fe80::1", "2606:50c0:8000::153")));
        GuardedHosts hosts = new GuardedHosts();
        InetAddressResolver resolver = new GuardedResolverProvider.GuardedResolver(builtin, hosts);

        try (GuardedHosts.Registration first = hosts.open("github.example.com")) {
            GuardedHosts.Registration second = hosts.open("github.example.com");
            second.close();
            second.close();
            // Another request to the host is still in flight: its lookups stay guarded.
            assertThat(addresses(resolver.lookupByName("github.example.com", POLICY)))
                .containsExactly("140.82.112.3", "2606:50c0:8000:0:0:0:0:153");
        }
        assertThat(addresses(resolver.lookupByName("github.example.com", POLICY))).hasSize(4);
    }

    @Test
    void anInternalHostKeepsOnlyItsPrivateAddresses() throws UnknownHostException {
        FakeResolver builtin = new FakeResolver(Map.of("mcp-server", List.of("172.18.0.5", "127.0.0.1", "::1",
                "169.254.169.254", "fe80::1", "0.0.0.0", "224.0.0.1", "fd00::5", "100.64.1.1", "140.82.112.3"),
                "rebound", List.of("127.0.0.1", "169.254.169.254")));
        GuardedHosts hosts = new GuardedHosts();
        InetAddressResolver resolver = new GuardedResolverProvider.GuardedResolver(builtin, hosts);

        try (GuardedHosts.Registration search = hosts.open("mcp-server", AddressGuard.Reach.PRIVATE_NETWORK);
                GuardedHosts.Registration rebound = hosts.open("rebound", AddressGuard.Reach.PRIVATE_NETWORK)) {
            assertThat(hosts.reaches("Mcp-Server.")).containsExactly(AddressGuard.Reach.PRIVATE_NETWORK);
            // Never a public address either: an internal host may be called over http.
            assertThat(addresses(resolver.lookupByName("mcp-server", POLICY)))
                .containsExactly("172.18.0.5", "fd00:0:0:0:0:0:0:5", "100.64.1.1");
            // A name that rebinds to the host's own loopback or the cloud's metadata service reaches neither.
            assertThatThrownBy(() -> resolver.lookupByName("rebound", POLICY))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("no address an outbound request may go to");
        }
        assertThat(hosts.reaches("mcp-server")).isEmpty();
        // No registration at all for a request that may reach any address: its lookups are not filtered.
        try (GuardedHosts.Registration any = hosts.open("mcp-server", AddressGuard.Reach.ANY)) {
            assertThat(hosts.guarded("mcp-server")).isFalse();
            assertThat(addresses(resolver.lookupByName("mcp-server", POLICY))).hasSize(10);
        }
    }

    @Test
    void requestsOfBothReachesToOneHostLeaveItsLookupsNoAddress() throws UnknownHostException {
        FakeResolver builtin = new FakeResolver(Map.of("mcp-server", List.of("172.18.0.5", "140.82.112.3")));
        GuardedHosts hosts = new GuardedHosts();
        InetAddressResolver resolver = new GuardedResolverProvider.GuardedResolver(builtin, hosts);

        try (GuardedHosts.Registration internal = hosts.open("mcp-server", AddressGuard.Reach.PRIVATE_NETWORK)) {
            try (GuardedHosts.Registration strict = hosts.open("mcp-server")) {
                // Each lookup keeps only what every request in flight may reach: the two reaches share no address.
                assertThat(hosts.reaches("mcp-server")).containsExactlyInAnyOrder(AddressGuard.Reach.PUBLIC,
                        AddressGuard.Reach.PRIVATE_NETWORK);
                assertThatThrownBy(() -> resolver.lookupByName("mcp-server", POLICY))
                    .isInstanceOf(UnknownHostException.class);
            }
            assertThat(addresses(resolver.lookupByName("mcp-server", POLICY))).containsExactly("172.18.0.5");
        }
        try (GuardedHosts.Registration strict = hosts.open("mcp-server")) {
            assertThat(addresses(resolver.lookupByName("mcp-server", POLICY))).containsExactly("140.82.112.3");
        }
    }

    @Test
    void otherLookupsAreTheBuiltInResolversUntouched() throws UnknownHostException {
        FakeResolver builtin = new FakeResolver(Map.of("db.internal", List.of("10.1.2.3"), "localhost",
                List.of("127.0.0.1"), "jira.example.com", List.of("10.0.0.7")));
        GuardedHosts hosts = new GuardedHosts();
        InetAddressResolver resolver = new GuardedResolverProvider.GuardedResolver(builtin, hosts);

        try (GuardedHosts.Registration jira = hosts.open("jira.example.com")) {
            // The database host and anything else that is no connection's host in flight resolve as they always do.
            assertThat(addresses(resolver.lookupByName("db.internal", POLICY))).containsExactly("10.1.2.3");
            assertThat(addresses(resolver.lookupByName("localhost", POLICY))).containsExactly("127.0.0.1");
            assertThat(resolver.lookupByAddress(new byte[] { 10, 0, 0, 7 })).isEqualTo("reverse.example");
            assertThatThrownBy(() -> resolver.lookupByName("unknown.example", POLICY))
                .isInstanceOf(UnknownHostException.class)
                .hasMessage("unknown.example");
        }
        assertThat(builtin.asked).containsExactly("db.internal", "localhost", "unknown.example");
    }

    @Test
    void theProviderIsTheResolverOfThisJvm() throws UnknownHostException {
        // The service file registers it; the JDK installs it with the first lookup that needs a resolver.
        InetAddress.getAllByName("localhost");

        assertThat(GuardedResolverProvider.installed()).isTrue();
        assertThat(new GuardedResolverProvider().name()).isEqualTo("tinfirm-outbound-guard");
    }
}
