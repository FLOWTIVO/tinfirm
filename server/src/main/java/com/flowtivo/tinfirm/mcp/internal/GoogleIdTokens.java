package com.flowtivo.tinfirm.mcp.internal;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.GmailCalls;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Google's ID tokens of a request to the Gmail add-on (ADR-0083, decision 2): their RS256 signature against Google's
 * keys (kept a day; read again when stale or for an unknown {@code kid}, one read at a time, at most once a minute,
 * whatever asks), the issuer
 * {@code https://accounts.google.com} (or without its scheme, as Google also writes it), and {@code exp} with five
 * minutes' skew. The deployment's token must have the endpoint's URL as its audience and the configured service
 * account, verified, as its email; the person's must have the configured OAuth client as its audience. What the person's
 * token names is the Google account: its subject, and its email to show.
 * <p>
 * What the tokens do not prove: they are valid for about an hour, so whoever captures a request in front of the server
 * can send it again until they expire. TLS is the protection; the person's tokens never leave Google and Tinfirm.
 */
@Component
class GoogleIdTokens {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokens.class);

    static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    static final Duration SKEW = Duration.ofMinutes(5);

    static final Duration KEYS_KEPT = Duration.ofDays(1);

    /** Google's keys are read at most this often, whatever asks: a stale set, an unknown {@code kid}, no set yet. */
    static final Duration READ_AGAIN = Duration.ofMinutes(1);

    /** The Google account a request is for: the subject never changes; the email is shown, never matched. */
    record GoogleAccount(String subject, @Nullable String email) {
    }

    /** A verification: the account, or why the request is refused (never a token). */
    record Verified(@Nullable GoogleAccount account, @Nullable String problem) {

        static Verified refused(String problem) {
            return new Verified(null, problem);
        }
    }

    private record Keys(JWKSet set, Instant at) {
    }

    private volatile @Nullable Keys keys;

    private volatile Instant refetched = Instant.EPOCH;

    /** One read of the keys at a time; never a monitor that every verification waits on. */
    private final ReentrantLock reading = new ReentrantLock();

    /** How long a verification without any key set waits for a read in flight. */
    static final Duration WAIT_FOR_READ = Duration.ofSeconds(25);

    private final GmailCalls calls;

    private final TimeSource time;

    GoogleIdTokens(GmailCalls calls, TimeSource time) {
        this.calls = calls;
        this.time = time;
    }

    /**
     * Verifies a request for the add-on of the configured project.
     *
     * @param systemToken the deployment's token: the {@code Authorization} header's bearer, else the event's
     *                    {@code systemIdToken}
     * @param userToken   the event's {@code userIdToken}
     * @param endpoint    the URL Google called, under the public URL: the deployment's token's audience
     */
    Verified verify(GmailAddOnProperties settings, @Nullable String systemToken, @Nullable String userToken,
            String endpoint) {
        if (systemToken == null || systemToken.isBlank()) {
            return Verified.refused("no ID token of the deployment");
        }
        if (userToken == null || userToken.isBlank()) {
            return Verified.refused("no ID token of the person");
        }
        JWTClaimsSet system;
        JWTClaimsSet user;
        try {
            system = claims(systemToken, "the deployment's token");
            user = claims(userToken, "the person's token");
        }
        catch (Refused ex) {
            return Verified.refused(ex.getMessage());
        }
        String audience = audience(system, endpoint);
        if (audience != null) {
            return Verified.refused("the deployment's token is for another URL (" + audience + ")");
        }
        Object email = system.getClaim("email");
        if (!(email instanceof String serviceAccount)
                || !serviceAccount.equalsIgnoreCase(Objects.requireNonNull(settings.serviceAccount()).strip())) {
            return Verified.refused("the deployment's token is another service account's ("
                    + (email instanceof String named ? named : "none") + ")");
        }
        Object verified = system.getClaim("email_verified");
        if (!Boolean.TRUE.equals(verified) && !"true".equals(verified)) {
            return Verified.refused("the deployment's token has no verified email");
        }
        String userAudience = audience(user, Objects.requireNonNull(settings.clientId()).strip());
        if (userAudience != null) {
            return Verified.refused("the person's token is for another OAuth client (" + userAudience + ")");
        }
        String subject = user.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            return Verified.refused("the person's token names no account");
        }
        Object userEmail = user.getClaim("email");
        return new Verified(new GoogleAccount(subject, userEmail instanceof String named && named.length() <= 320
                ? named : null), null);
    }

    private static final class Refused extends Exception {

        Refused(String message) {
            super(message, null, false, false);
        }
    }

    /** The claims of a token Google signed, current; {@link Refused} otherwise. */
    private JWTClaimsSet claims(String token, String which) throws Refused {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token.strip());
        }
        catch (ParseException ex) {
            throw new Refused(which + " is not a JWT");
        }
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new Refused(which + " is not RS256");
        }
        String kid = jwt.getHeader().getKeyID();
        Keys current = keys(kid);
        JWK key = current != null && kid != null ? current.set().getKeyByKeyId(kid) : null;
        if (!(key instanceof RSAKey rsa)) {
            throw new Refused(which + " is signed with an unknown key");
        }
        try {
            if (!jwt.verify(new RSASSAVerifier(rsa))) {
                throw new Refused(which + " has a bad signature");
            }
        }
        catch (JOSEException ex) {
            throw new Refused(which + " has a signature that cannot be checked");
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        }
        catch (ParseException ex) {
            throw new Refused(which + " has unreadable claims");
        }
        if (claims.getIssuer() == null || !ISSUERS.contains(claims.getIssuer())) {
            throw new Refused(which + " has another issuer");
        }
        Date expires = claims.getExpirationTime();
        if (expires == null || expires.toInstant().plus(SKEW).isBefore(time.now())) {
            throw new Refused(which + " expired");
        }
        return claims;
    }

    /** Null when the token is for the audience, else the audience it names (shown in the log, never a secret). */
    private static @Nullable String audience(JWTClaimsSet claims, String expected) {
        if (claims.getAudience() != null && claims.getAudience().contains(expected)) {
            return null;
        }
        return claims.getAudience() == null ? "none" : String.join(" ", claims.getAudience());
    }

    /**
     * The keys: kept a day; read again when stale or for an unknown {@code kid}, one read at a time and at most once a
     * minute, so that no request, whatever its token, makes Tinfirm call Google more often, even while Google cannot be
     * reached. A stale set is used while it cannot be read again: a token still needs a key Google published.
     */
    private @Nullable Keys keys(@Nullable String kid) {
        Keys current = keys;
        if (!due(current, kid, time.now())) {
            return current;
        }
        // The read runs outside any monitor (the security review of 2026-10-27, L9): a thread that holds a set
        // verifies with it while another reads; only one without any set waits for the read in flight.
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
            return keys;
        }
        try {
            current = keys;
            Instant now = time.now();
            if (due(current, kid, now) && !refetched.plus(READ_AGAIN).isAfter(now)) {
                refetched = now;
                CallResult<String> fetched = calls.googleKeys();
                if (fetched.failure() == null) {
                    try {
                        Keys parsed = new Keys(JWKSet.parse(Objects.requireNonNull(fetched.value())), now);
                        keys = parsed;
                        return parsed;
                    }
                    catch (ParseException ex) {
                        log.warn("Google's keys are not a JWK set");
                    }
                }
                else {
                    log.warn("Google's keys could not be read: {}", fetched.failureMessage());
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

    /** Forgets the keys, for the tests' own key sets. */
    void forget() {
        keys = null;
        refetched = Instant.EPOCH;
    }
}
