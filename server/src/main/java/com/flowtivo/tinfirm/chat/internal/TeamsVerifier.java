package com.flowtivo.tinfirm.chat.internal;

import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.databind.JsonNode;

/**
 * The Bot Framework's token of an activity (the package 5 contract, §5.2, C1, C2, threat model 11.1 and 11.9): the
 * bearer JWT's RS256 signature against the keys the Bot Framework's OpenID metadata names (kept a day; read again
 * when stale or for an unknown {@code kid}, one read at a time, at most once a minute, whatever asks),
 * {@code iss} {@code https://api.botframework.com}, {@code aud} the
 * system's app id, {@code exp} and {@code nbf} with five minutes' skew, the key's endorsements naming {@code msteams},
 * the {@code serviceurl} claim (so named, lower case) equal to the activity's {@code serviceUrl}, whose origin is the
 * system's base URL, and the activity's tenant ({@code channelData.tenant.id}) the system's.
 * <p>
 * What the token does not prove: it authenticates the connector, not the activity, and is valid for about an hour, so
 * whoever captures one in front of the server can post any activity until it expires while its {@code serviceurl}
 * matches. The dedupe and these checks are the mitigations; the TLS endpoint is inside the trust boundary (§5.2).
 */
@Component
class TeamsVerifier {

    private static final Logger log = LoggerFactory.getLogger(TeamsVerifier.class);

    static final String ISSUER = "https://api.botframework.com";

    static final Duration SKEW = Duration.ofMinutes(5);

    static final Duration KEYS_KEPT = Duration.ofDays(1);

    /** The keys are read at most this often, whatever asks: a stale set, an unknown {@code kid}, no set yet. */
    static final Duration READ_AGAIN = Duration.ofMinutes(1);

    private record Keys(JWKSet set, Map<String, List<String>> endorsements, Instant at) {
    }

    private volatile @Nullable Keys keys;

    private volatile Instant refetched = Instant.EPOCH;

    /** One read of the keys at a time; never a monitor that every verification waits on. */
    private final ReentrantLock reading = new ReentrantLock();

    /** How long a verification without any key set waits for a read in flight. */
    static final Duration WAIT_FOR_READ = Duration.ofSeconds(25);

    private final ChatCalls calls;

    private final TimeSource time;

    TeamsVerifier(ChatCalls calls, TimeSource time) {
        this.calls = calls;
        this.time = time;
    }

    /** Why the token does not verify the activity for the system, or null when it does. */
    @Nullable String problem(ChatSystemInfo system, @Nullable String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "no bearer token";
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(authorization.substring(7).strip());
        }
        catch (ParseException ex) {
            return "not a JWT";
        }
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            return "not RS256";
        }
        String kid = jwt.getHeader().getKeyID();
        Keys current = keys(system, kid);
        JWK key = current != null && kid != null ? current.set().getKeyByKeyId(kid) : null;
        if (key == null || !(key instanceof RSAKey rsa)) {
            return "an unknown key";
        }
        if (!current.endorsements().getOrDefault(kid, List.of()).contains("msteams")) {
            return "a key not endorsed for msteams";
        }
        try {
            if (!jwt.verify(new RSASSAVerifier(rsa))) {
                return "a bad signature";
            }
        }
        catch (JOSEException ex) {
            return "a signature that cannot be checked";
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        }
        catch (ParseException ex) {
            return "unreadable claims";
        }
        Instant now = time.now();
        if (!ISSUER.equals(claims.getIssuer())) {
            return "another issuer";
        }
        if (claims.getAudience() == null || system.appId() == null
                || claims.getAudience().stream().noneMatch(audience -> audience.equalsIgnoreCase(system.appId()))) {
            return "another audience";
        }
        Date expires = claims.getExpirationTime();
        if (expires == null || expires.toInstant().plus(SKEW).isBefore(now)) {
            return "expired";
        }
        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null && notBefore.toInstant().minus(SKEW).isAfter(now)) {
            return "not valid yet";
        }
        return null;
    }

    /**
     * The activity's own checks, once the token verified: its {@code serviceUrl} is the token's {@code serviceurl}
     * claim and on the system's origin, and its tenant is the system's (C2). Why not, or null.
     */
    @Nullable String activityProblem(ChatSystemInfo system, String authorization, JsonNode activity) {
        String serviceUrl = SlackEvents.text(activity, "serviceUrl");
        Object claimed;
        try {
            claimed = SignedJWT.parse(authorization.substring(7).strip()).getJWTClaimsSet().getClaim("serviceurl");
        }
        catch (ParseException ex) {
            return "unreadable claims";
        }
        if (serviceUrl == null || !(claimed instanceof String claim) || !same(claim, serviceUrl)) {
            return "a serviceurl claim that is not the activity's";
        }
        String origin = origin(serviceUrl);
        if (origin == null || !origin.equals(origin(system.system().baseUrl()))) {
            return "a serviceUrl outside the system's origin";
        }
        String tenant = SlackEvents.text(activity.path("channelData").path("tenant"), "id");
        if (tenant == null || system.tenantId() == null || !tenant.equalsIgnoreCase(system.tenantId())) {
            return "another tenant";
        }
        return null;
    }

    /** The origin of a URL ({@code scheme://host[:port]}), or null when it has none. */
    static @Nullable String origin(@Nullable String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url.strip());
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            int standard = "http".equals(scheme) ? 80 : 443;
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (port == -1 || port == standard ? "" : ":" + port);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean same(String a, String b) {
        return trim(a).equalsIgnoreCase(trim(b));
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The keys: kept a day; read again when stale or for an unknown {@code kid}, one read at a time and at most once a
     * minute, so that no activity, whatever its token, makes Tinfirm call the Bot Framework more often, even while it
     * cannot be reached. A stale set is used while it cannot be read again: a token still needs a key it published.
     * <p>
     * The read runs outside any monitor (the security review of 2026-10-27, L9): while one thread reads, another that
     * holds a set verifies with it at once rather than waiting on the Bot Framework; only a thread with no set at all
     * waits for the read in flight, at most the time a read may take.
     */
    private @Nullable Keys keys(ChatSystemInfo system, @Nullable String kid) {
        Keys current = keys;
        if (!due(current, kid, time.now())) {
            return current;
        }
        boolean locked;
        try {
            locked = current == null ? reading.tryLock(WAIT_FOR_READ.toMillis(), TimeUnit.MILLISECONDS)
                    : reading.tryLock();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return keys;
        }
        if (!locked) {
            // Another thread reads them: this one goes on with what it holds.
            return keys;
        }
        try {
            current = keys;
            Instant now = time.now();
            if (due(current, kid, now) && !refetched.plus(READ_AGAIN).isAfter(now)) {
                refetched = now;
                CallResult<String> fetched = calls.teamsKeys(system.system());
                if (fetched.failure() == null) {
                    Keys parsed = parse(Objects.requireNonNull(fetched.value()), now);
                    if (parsed != null) {
                        keys = parsed;
                        return parsed;
                    }
                }
                else {
                    log.warn("The Bot Framework's keys could not be read: {}", fetched.failureMessage());
                }
            }
            return keys;
        }
        finally {
            reading.unlock();
        }
    }

    /** Whether the keys are to be read again: none yet, stale, or without the {@code kid}. */
    private static boolean due(@Nullable Keys current, @Nullable String kid, Instant now) {
        return current == null || current.at().plus(KEYS_KEPT).isBefore(now)
                || kid != null && current.set().getKeyByKeyId(kid) == null;
    }

    private static @Nullable Keys parse(String json, Instant now) {
        try {
            JWKSet set = JWKSet.parse(json);
            Map<String, List<String>> endorsements = new HashMap<>();
            for (JsonNode key : ChatStore.tree(json).path("keys")) {
                String kid = SlackEvents.text(key, "kid");
                if (kid != null) {
                    endorsements.put(kid, ChatStore.list(ChatStore.JSON.convertValue(key.path("endorsements"),
                            List.class)));
                }
            }
            return new Keys(set, endorsements, now);
        }
        catch (ParseException | RuntimeException ex) {
            log.warn("The Bot Framework's keys are not a JWK set ({})", ex.getClass().getSimpleName());
            return null;
        }
    }

    /** Forgets the keys, for the tests' own key sets. */
    void forget() {
        keys = null;
        refetched = Instant.EPOCH;
    }
}
