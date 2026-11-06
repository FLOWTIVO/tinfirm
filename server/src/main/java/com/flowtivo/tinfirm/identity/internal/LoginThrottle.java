package com.flowtivo.tinfirm.identity.internal;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.web.ApiException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;

/**
 * Login throttling per client IP and per email (token buckets, kept in memory per replica as CONVENTIONS §5 states).
 * <ul>
 * <li>Per IP, every attempt counts (password spraying across accounts). An IPv6 client counts by its /64, the prefix
 * one subscriber or host usually holds, so that the addresses of one network are one client.</li>
 * <li>Per email, failed attempts count (guessing one account's password), so a user's own successful logins never
 * lock them out. The email's attempt is taken before the password is verified and given back when it is right
 * ({@link #refund}), so that parallel attempts against one email cannot all pass while the first is being hashed.</li>
 * </ul>
 * The client IP is {@code request.getRemoteAddr()}, which Tomcat's RemoteIpValve derives from
 * {@code X-Forwarded-For} only when the request comes from a trusted proxy.
 */
@Component
class LoginThrottle {

    private static final long MAX_TRACKED_KEYS = 100_000;

    /** An IPv6 literal: hex digits and colons, an embedded IPv4 address's dots at most. */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final Cache<String, Bucket> byEmail;

    private final Cache<String, Bucket> byIp;

    /**
     * Failed SAML responses per client IP, apart from {@link #byIp}: the ACS takes cross-site posts, so a page that
     * posts junk from a visitor's browser must not use up that address's password logins.
     */
    private final Cache<String, Bucket> samlByIp;

    private final Function<String, Bucket> newEmailBucket;

    private final Function<String, Bucket> newIpBucket;

    LoginThrottle(AuthProperties properties) {
        AuthProperties.Limit email = properties.loginThrottle().perEmail();
        AuthProperties.Limit ip = properties.loginThrottle().perIp();
        this.byEmail = cache(email.period());
        this.byIp = cache(ip.period());
        this.samlByIp = cache(ip.period());
        this.newEmailBucket = key -> bucket(email);
        this.newIpBucket = key -> bucket(ip);
    }

    /**
     * Admits one login attempt, or throws 429 {@code RATE_LIMITED}: consumes one attempt of the client IP and one of
     * the email. A right password gives the email's back ({@link #refund}); an attempt that was never checked does too.
     */
    void admit(String normalizedEmail, String clientIp) {
        ConsumptionProbe ip = byIp.get(ipKey(clientIp), newIpBucket).tryConsumeAndReturnRemaining(1);
        if (!ip.isConsumed()) {
            throw ApiException.rateLimited(Duration.ofNanos(ip.getNanosToWaitForRefill()));
        }
        ConsumptionProbe email = byEmail.get(normalizedEmail, newEmailBucket).tryConsumeAndReturnRemaining(1);
        if (!email.isConsumed()) {
            throw ApiException.rateLimited(Duration.ofNanos(email.getNanosToWaitForRefill()));
        }
    }

    /** Gives back the email's attempt that {@link #admit} took: the password was right, or it was never checked. */
    void refund(String normalizedEmail) {
        byEmail.get(normalizedEmail, newEmailBucket).addTokens(1);
    }

    /**
     * The key of a client address: an IPv4 address as it is, an IPv6 address by its /64 ({@code 2001:db8:1:2::/64}),
     * an IPv4-mapped IPv6 address as its IPv4 address. Anything that is not an IP literal is kept as it is; nothing is
     * ever resolved.
     */
    static String ipKey(String clientIp) {
        if (clientIp == null || clientIp.indexOf(':') < 0) {
            return clientIp;
        }
        String literal = clientIp.startsWith("[") && clientIp.endsWith("]")
                ? clientIp.substring(1, clientIp.length() - 1) : clientIp;
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (!IPV6.matcher(literal).matches()) {
            return clientIp;
        }
        try {
            // A literal of hex digits, colons and dots is parsed, never looked up.
            InetAddress address = InetAddress.getByName(literal);
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
            byte[] bytes = address.getAddress();
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                prefix.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff))).append(':');
            }
            return prefix.append(":/64").toString();
        }
        catch (UnknownHostException | RuntimeException ex) {
            return clientIp;
        }
    }

    /**
     * Whether the client IP has SAML attempts left, without consuming one: a SAML response is refused with 429 before
     * it is parsed when its address's failed SAML sign-ins used them up (ADR-0086, decision 13); empty when it may go on, else the
     * time to wait.
     */
    java.util.Optional<Duration> samlBlocked(String clientIp) {
        EstimationProbe ip = samlByIp.get(ipKey(clientIp), newIpBucket).estimateAbilityToConsume(1);
        return ip.canBeConsumed() ? java.util.Optional.empty()
                : java.util.Optional.of(Duration.ofNanos(ip.getNanosToWaitForRefill()));
    }

    /** Counts a failed SAML sign-in against the client IP, in SAML's own bucket. */
    void recordSamlFailure(String clientIp) {
        samlByIp.get(ipKey(clientIp), newIpBucket).tryConsume(1);
    }

    private static Bucket bucket(AuthProperties.Limit limit) {
        return Bucket.builder()
            .addLimit(bandwidth -> bandwidth.capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()))
            .build();
    }

    private static Cache<String, Bucket> cache(Duration period) {
        // A bucket untouched for a full period is full again, so dropping it loses nothing.
        return Caffeine.newBuilder().maximumSize(MAX_TRACKED_KEYS).expireAfterAccess(period).build();
    }
}
