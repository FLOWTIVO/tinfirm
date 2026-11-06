package com.flowtivo.tinfirm.shared.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which addresses each reach of the guard allows (CONVENTIONS §14.4, ADR-0066). The addresses are IP literals, which
 * {@link InetAddress#getByName} parses without a lookup, so the test needs no DNS.
 */
class AddressGuardTest {

    private static InetAddress address(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // Public addresses, and the neighbours of the special-use ranges.
            "140.82.112.3, null",
            "1.1.1.1, null",
            "2606:4700:4700::1111, null",
            "192.0.1.1, null",
            "192.0.3.1, null",
            "198.17.255.255, null",
            "198.20.0.0, null",
            "198.51.99.255, null",
            "203.0.112.1, null",
            "239.255.255.255, a multicast address",
            // The loopback, link-local, unspecified and private ranges.
            "127.0.0.1, a loopback address",
            "::1, a loopback address",
            "169.254.169.254, a link-local address",
            "fe80::1, a link-local address",
            "0.0.0.0, an unspecified address",
            "::, an unspecified address",
            "10.1.2.3, a private address",
            "172.16.0.1, a private address",
            "192.168.1.1, a private address",
            "100.64.0.1, a private address",
            "fd00::1, a private address",
            // 0.0.0.0/8 is unspecified as a whole.
            "0.1.2.3, an unspecified address",
            // The special-use ranges no internet host is in (RFC 6890).
            "192.0.0.8, a special-use address",
            "192.0.2.10, a special-use address",
            "198.18.0.1, a special-use address",
            "198.19.255.254, a special-use address",
            "198.51.100.7, a special-use address",
            "203.0.113.200, a special-use address",
            "240.0.0.1, a special-use address",
            "255.255.255.255, a special-use address",
            "::ffff:198.18.0.1, a special-use address",
            "64:ff9b:1::a00:1, a special-use address",
            "100::1, a special-use address",
            "2001:db8::1, a special-use address",
            "::10.0.0.1, a special-use address",
            // NAT64's well-known prefix: the embedded IPv4 address decides (RFC 6052).
            "64:ff9b::8c52:7003, null",
            "64:ff9b::a9fe:a9fe, a link-local address",
            "64:ff9b::a00:1, a private address",
            "64:ff9b::7f00:1, a loopback address",
            "64:ff9b::c612:1, a special-use address",
            // Azure's wire server, and its neighbour.
            "168.63.129.16, a cloud's host endpoint",
            "168.63.129.17, null",
            "::ffff:168.63.129.16, a cloud's host endpoint",
            // 6to4 (RFC 3056): the IPv4 address after 2002::/16 decides.
            "2002:8c52:7003::1, null",
            "2002:a00:1::1, a private address",
            "2002:7f00:1::, a loopback address",
            "2002:a9fe:a9fe::1, a link-local address",
            "2002:c612:1::1, a special-use address",
            "2002:a83f:8110::1, a cloud's host endpoint",
            // Teredo (RFC 4380): the client's address, inverted in the last four bytes, decides.
            "2001:0:4136:e378:8000:63bf:73ad:8ffc, null",
            "2001:0:4136:e378:8000:63bf:f5ff:fffe, a private address",
            "2001:0:4136:e378:8000:63bf:80ff:fffe, a loopback address",
            "2001:0:4136:e378:8000:63bf:5601:5601, a link-local address",
    })
    void publicReachAllowsPublicAddressesOnly(String literal, @Nullable String problem) throws UnknownHostException {
        assertThat(AddressGuard.problem(address(literal), AddressGuard.Reach.PUBLIC)).isEqualTo(problem);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "10.1.2.3, null",
            "100.64.0.1, null",
            "fd00::1, null",
            "64:ff9b::a00:1, null",
            "140.82.112.3, a public address",
            "64:ff9b::8c52:7003, a public address",
            "169.254.169.254, a link-local address",
            "0.1.2.3, an unspecified address",
            "198.18.0.1, a special-use address",
            "240.0.0.1, a special-use address",
            "2001:db8::1, a special-use address",
            "2002:a00:1::1, null",
            "2001:0:4136:e378:8000:63bf:f5ff:fffe, null",
            "2002:8c52:7003::1, a public address",
            "168.63.129.16, a cloud's host endpoint",
    })
    void privateNetworkReachAllowsPrivateAddressesOnly(String literal, @Nullable String problem)
            throws UnknownHostException {
        assertThat(AddressGuard.problem(address(literal), AddressGuard.Reach.PRIVATE_NETWORK)).isEqualTo(problem);
    }

    @Test
    void anyReachAllowsEveryAddress() throws UnknownHostException {
        for (String literal : new String[] { "127.0.0.1", "10.1.2.3", "198.18.0.1", "64:ff9b::a9fe:a9fe" }) {
            assertThat(AddressGuard.problem(address(literal), AddressGuard.Reach.ANY)).isNull();
        }
    }

    @Test
    void aHostNamesTheAddressItResolvesTo() {
        assertThat(AddressGuard.problem("198.18.0.1", false))
            .isEqualTo("the host 198.18.0.1 resolves to a special-use address (198.18.0.1)");
        assertThat(AddressGuard.problem("198.18.0.1", true)).isNull();
    }
}
