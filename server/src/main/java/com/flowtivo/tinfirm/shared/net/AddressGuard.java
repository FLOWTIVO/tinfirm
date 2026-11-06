package com.flowtivo.tinfirm.shared.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.jspecify.annotations.Nullable;

/**
 * The addresses an outbound request may go to (CONVENTIONS §14.4): never loopback, link-local, private
 * (RFC 1918, the shared address space 100.64/10, IPv6 unique local), unspecified (0.0.0.0/8 too), multicast or
 * special-use addresses unless the operator allows them. Special-use are the ranges no internet host is in, which a
 * private network may route (RFC 6890): the IETF protocol assignments 192.0.0.0/24, the documentation ranges
 * 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 and 2001:db8::/32, benchmarking 198.18.0.0/15, the reserved
 * 240.0.0.0/4, the discard-only 100::/64, the local-use NAT64 prefix 64:ff9b:1::/48 (RFC 8215) and the deprecated
 * IPv4-compatible IPv6 addresses, and Azure's wire server 168.63.129.16, a cloud's host endpoint as the link-local
 * metadata address is. An address of NAT64's well-known prefix 64:ff9b::/96 is checked as the IPv4 address it embeds
 * (RFC 6052), which is what a translator reaches; so are a 6to4 address (2002::/16, RFC 3056: the IPv4 address after
 * the prefix) and a Teredo address (2001::/32, RFC 4380: the client's IPv4 address, stored inverted in the last four
 * bytes), which a relay reaches. A host is resolved and every one of its addresses checked
 * before connecting, and the lookup the client connects with leaves out the same addresses
 * ({@link GuardedResolverProvider}); an IP literal is checked as it is.
 * <p>
 * Shared by every server-side request to a URL that someone outside the server chose: a system's base URL (the
 * system module's outbound client), a company's own model gateway (the model module) and an OAuth client's metadata
 * document (the identity module, ADR-0045).
 * <p>
 * Beside "public only" and "anything" there is {@link Reach#PRIVATE_NETWORK} (ADR-0066): the private ranges only, which
 * a company system on one of the operator's internal hosts resolves to. Never a public address, as such a host may be
 * called over http and its traffic must stay on the deployment's own network, and never loopback, link-local (a
 * cloud's metadata service), unspecified, multicast or special-use addresses, which no host of that network needs.
 */
public final class AddressGuard {

    /** Which addresses a host may resolve to. */
    public enum Reach {

        /** Only public addresses: the default for every outbound request. */
        PUBLIC,

        /**
         * Private addresses only (RFC 1918, the shared address space, IPv6 unique local), never public, loopback,
         * link-local, unspecified, multicast or special-use ones: a host the operator named as internal (ADR-0066).
         */
        PRIVATE_NETWORK,

        /** Any address: the operator allows private addresses for every company system. */
        ANY
    }

    private AddressGuard() {
    }

    /** Why the host may not be called, or null when every address it resolves to is allowed. */
    public static @Nullable String problem(String host, boolean allowPrivate) {
        return problem(host, allowPrivate ? Reach.ANY : Reach.PUBLIC);
    }

    /** Why the host may not be called within the reach, or null when every address it resolves to is allowed. */
    public static @Nullable String problem(String host, Reach reach) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        }
        catch (UnknownHostException ex) {
            return "the host " + host + " cannot be resolved";
        }
        if (reach == Reach.ANY) {
            return null;
        }
        for (InetAddress address : addresses) {
            String problem = problem(address, reach);
            if (problem != null) {
                return "the host " + host + " resolves to " + problem + " (" + address.getHostAddress() + ")";
            }
        }
        return null;
    }

    /** What is wrong with the address, or null for a public one. */
    public static @Nullable String problem(InetAddress address) {
        return problem(address, Reach.PUBLIC);
    }

    /** What is wrong with the address within the reach, or null when the reach allows it. */
    public static @Nullable String problem(InetAddress address, Reach reach) {
        if (reach == Reach.ANY) {
            return null;
        }
        if (address.isLoopbackAddress()) {
            return "a loopback address";
        }
        if (address.isLinkLocalAddress()) {
            return "a link-local address";
        }
        Inet4Address translated = embeddedIpv4(address);
        if (translated != null) {
            return problem(translated, reach);
        }
        if (azureWireServer(address)) {
            return "a cloud's host endpoint";
        }
        if (address.isAnyLocalAddress() || thisNetwork(address)) {
            return "an unspecified address";
        }
        if (address.isMulticastAddress()) {
            return "a multicast address";
        }
        if (specialUse(address)) {
            return "a special-use address";
        }
        boolean isPrivate = address.isSiteLocalAddress() || sharedAddressSpace(address) || uniqueLocal(address);
        if (reach == Reach.PUBLIC && isPrivate) {
            return "a private address";
        }
        if (reach == Reach.PRIVATE_NETWORK && !isPrivate) {
            return "a public address";
        }
        return null;
    }

    /** 100.64.0.0/10, the carrier-grade NAT range. */
    private static boolean sharedAddressSpace(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 0x40;
    }

    /** fc00::/7, the IPv6 unique local addresses. */
    private static boolean uniqueLocal(InetAddress address) {
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    }

    /** 0.0.0.0/8, "this network": no host is there; 0.0.0.0 itself is {@link InetAddress#isAnyLocalAddress()}. */
    private static boolean thisNetwork(InetAddress address) {
        return address instanceof Inet4Address && address.getAddress()[0] == 0;
    }

    /** The ranges no internet host is in, other than the private, loopback, link-local and multicast ones. */
    private static boolean specialUse(InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            int third = b[2] & 0xff;
            return first == 192 && second == 0 && (third == 0 || third == 2)   // 192.0.0.0/24, 192.0.2.0/24
                    || first == 198 && (second & 0xfe) == 18                    // 198.18.0.0/15
                    || first == 198 && second == 51 && third == 100             // 198.51.100.0/24
                    || first == 203 && second == 0 && third == 113              // 203.0.113.0/24
                    || first >= 240;                                            // 240.0.0.0/4
        }
        Inet6Address v6 = (Inet6Address) address;
        return startsWith(b, 0x00, 0x64, 0xff, 0x9b, 0x00, 0x01)                // 64:ff9b:1::/48
                || startsWith(b, 0x01, 0x00, 0, 0, 0, 0, 0, 0)                  // 100::/64
                || startsWith(b, 0x20, 0x01, 0x0d, 0xb8)                        // 2001:db8::/32
                || v6.isIPv4CompatibleAddress();                                // ::/96, deprecated
    }

    /** 168.63.129.16, Azure's wire server (its DHCP, DNS, health probes and the agent's host endpoint). */
    private static boolean azureWireServer(InetAddress address) {
        byte[] b = address.getAddress();
        return address instanceof Inet4Address && (b[0] & 0xff) == 168 && (b[1] & 0xff) == 63 && (b[2] & 0xff) == 129
                && (b[3] & 0xff) == 16;
    }

    /**
     * The IPv4 address an IPv6 address embeds for a translator or a relay to reach, or null for any other: NAT64's
     * well-known prefix 64:ff9b::/96 (RFC 6052, the last four bytes), 6to4's 2002::/16 (RFC 3056, the four bytes after
     * the prefix) and Teredo's 2001::/32 (RFC 4380, the client's address, the last four bytes inverted).
     */
    static @Nullable Inet4Address embeddedIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return null;
        }
        byte[] b = address.getAddress();
        if (startsWith(b, 0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0)) {
            return ipv4(b[12], b[13], b[14], b[15]);
        }
        if (startsWith(b, 0x20, 0x02)) {
            return ipv4(b[2], b[3], b[4], b[5]);
        }
        if (startsWith(b, 0x20, 0x01, 0x00, 0x00)) {
            return ipv4((byte) ~b[12], (byte) ~b[13], (byte) ~b[14], (byte) ~b[15]);
        }
        return null;
    }

    private static Inet4Address ipv4(byte a, byte b, byte c, byte d) {
        try {
            return (Inet4Address) InetAddress.getByAddress(new byte[] { a, b, c, d });
        }
        catch (UnknownHostException ex) {
            throw new IllegalStateException("four bytes are always an IPv4 address", ex);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
