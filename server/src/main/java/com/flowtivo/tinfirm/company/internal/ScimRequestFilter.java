package com.flowtivo.tinfirm.company.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import com.flowtivo.tinfirm.company.scim.ScimTokenStore;
import com.flowtivo.tinfirm.company.scim.ScimSettings;
import com.flowtivo.tinfirm.company.scim.ScimTokenFormat;
import com.flowtivo.tinfirm.company.scim.ScimException;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ScimAuthentication;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;

/**
 * Authenticates a request of the SCIM endpoint by its company's token (ADR-0087, decisions 1 and 3), in this order:
 * <ol>
 * <li>an {@code Origin} header is refused {@code 403}: the endpoint is for an identity provider's servers, never a
 * browser;</li>
 * <li>a client address with 20 refused tokens in the last minute is {@code 429}, before its token is looked at;</li>
 * <li>a missing, malformed, unknown, revoked or expired token, or one of a company whose SCIM the deployment no longer
 * allows, is {@code 401} with {@code WWW-Authenticate: Bearer error="invalid_token"}, without saying which, and is
 * audited {@code scim.refused} in the instance chain, at most once a minute per address and reason;</li>
 * <li>a company beyond its requests a minute is {@code 429} with {@code Retry-After};</li>
 * <li>a path the endpoint does not serve ({@code /Groups}, {@code /Bulk}, {@code /Me}) is {@code 404};</li>
 * <li>a body declared larger than {@value #MAX_BODY_BYTES} bytes (256 KiB) is {@code 413}, and one that is not
 * declared is read up to that size only (the security review of 2026-10-27, L10): a user is a few KiB.</li>
 * </ol>
 * Then the request runs as the token ({@link ScimAuthentication}) in the token's company's tenant context, and the
 * token's {@code last_used_at} is written, at most once a minute. The limits are in memory, per replica.
 */
final class ScimRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScimRequestFilter.class);

    private static final long MAX_TRACKED_KEYS = 100_000;

    /** The largest request body, in bytes. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    /** The paths served below {@code /scim/v2} (decision 1). */
    private static final Pattern SERVED = Pattern
        .compile("^/scim/v2/(Users(/[^/]+)?|ServiceProviderConfig|ResourceTypes(/[^/]+)?|Schemas(/[^/]+)?)/?$");

    private final ScimTokenStore tokens;

    private final ScimSettings settings;

    private final AuditLog auditLog;

    private final TransactionTemplate transactions;

    private final TimeSource time;

    private final ScimProperties limits;

    private final Cache<String, Bucket> refusalsByAddress;

    private final Cache<UUID, Bucket> requestsByCompany;

    /** The refusals audited in the last minute, by address and reason. */
    private final Cache<String, Boolean> auditedRefusals;

    ScimRequestFilter(ScimTokenStore tokens, ScimSettings settings, AuditLog auditLog, TransactionTemplate transactions,
            TimeSource time, ScimProperties limits) {
        this.tokens = tokens;
        this.settings = settings;
        this.auditLog = auditLog;
        this.transactions = transactions;
        this.time = time;
        this.limits = limits;
        this.refusalsByAddress = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_KEYS)
            .expireAfterAccess(Duration.ofMinutes(1))
            .build();
        this.requestsByCompany = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_KEYS)
            .expireAfterAccess(Duration.ofMinutes(1))
            .build();
        this.auditedRefusals = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_KEYS)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.ORIGIN) != null) {
            ScimErrors.write(response, new ScimException(403, null, "Browser requests are refused on this endpoint"));
            return;
        }
        String address = request.getRemoteAddr();
        EstimationProbe refusals = refusalBucket(address).estimateAbilityToConsume(1);
        if (!refusals.canBeConsumed()) {
            ScimErrors.write(response, ScimException.tooManyRequests(seconds(refusals.getNanosToWaitForRefill())));
            return;
        }
        String presented = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        Instant now = time.now();
        String refusal = null;
        ScimTokenStore.Resolved token = null;
        if (presented == null) {
            refusal = "missing";
        }
        else if (!ScimTokenFormat.isWellFormed(presented)) {
            refusal = "malformed";
        }
        else {
            Optional<ScimTokenStore.Resolved> found = tokens.resolve(ScimTokenFormat.hash(presented));
            if (found.isEmpty()) {
                refusal = "unknown";
            }
            else if (found.get().revokedAt() != null) {
                refusal = "revoked";
            }
            else if (!found.get().activeAt(now)) {
                refusal = "expired";
            }
            else if (!enabled(found.get().companyId())) {
                refusal = "not_enabled";
            }
            else {
                token = found.get();
            }
        }
        if (token == null) {
            refused(address, refusal);
            ScimErrors.write(response, new ScimException(401, null, "A valid SCIM token is required"));
            return;
        }
        ConsumptionProbe rate = requestsByCompany.get(token.companyId(), id -> bucket(limits.requestsPerMinute()))
            .tryConsumeAndReturnRemaining(1);
        if (!rate.isConsumed()) {
            ScimErrors.write(response, ScimException.tooManyRequests(seconds(rate.getNanosToWaitForRefill())));
            return;
        }
        if (!SERVED.matcher(request.getRequestURI().substring(request.getContextPath().length())).matches()) {
            ScimErrors.write(response, ScimException.notFound("Not served: this endpoint serves Users only"));
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            ScimErrors.write(response, new ScimException(413, null, "The request body is larger than "
                    + MAX_BODY_BYTES / 1024 + " KiB"));
            return;
        }
        UUID companyId = token.companyId();
        UUID tokenId = token.id();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ScimAuthentication(tokenId, companyId));
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(context);
        try (TenantContextHolder.Scope ignored = TenantContextHolder.open(TenantContext.forCompany(companyId))) {
            transactions.executeWithoutResult(status -> tokens.touch(companyId, tokenId, now));
            chain.doFilter(new Bounded(request), response);
        }
        finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /** Whether the deployment still allows the company's SCIM, read in its own tenant context. */
    private boolean enabled(UUID companyId) {
        try (TenantContextHolder.Scope ignored = TenantContextHolder.open(TenantContext.forCompany(companyId))) {
            return Boolean.TRUE.equals(transactions.execute(status -> settings.of(companyId).enabled()));
        }
    }

    private void refused(String address, String reason) {
        refusalBucket(address).tryConsume(1);
        String key = address + "|" + reason;
        if (auditedRefusals.asMap().putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        try (TenantContextHolder.Scope ignored = TenantContextHolder.open(TenantContext.EMPTY)) {
            transactions.executeWithoutResult(status -> auditLog.record(
                    AuditRecord.instance(AuditAction.SCIM_REFUSED, Actor.ANONYMOUS)
                        .detail("reason", reason)
                        .detail("clientAddress", address)
                        .build()));
        }
        catch (RuntimeException ex) {
            // A refusal is answered whatever its audit does; the next one within the minute is not audited either.
            log.warn("The audit of a refused SCIM token failed", ex);
        }
    }

    private Bucket refusalBucket(String address) {
        return refusalsByAddress.get(address, key -> bucket(limits.refusalsPerMinute()));
    }

    private static Bucket bucket(int perMinute) {
        return Bucket.builder()
            .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
            .build();
    }

    private static long seconds(long nanos) {
        return Math.max(1, Duration.ofNanos(nanos).toSeconds() + 1);
    }

    /** The request with its body read up to {@link #MAX_BODY_BYTES}, whatever its length says. */
    static final class Bounded extends HttpServletRequestWrapper {

        private @Nullable ServletInputStream bounded;

        Bounded(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (bounded == null) {
                bounded = new BoundedStream(super.getInputStream());
            }
            return bounded;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8));
        }
    }

    /**
     * A body read past {@link #MAX_BODY_BYTES}: the read fails with it, Spring wraps it as an unreadable message, and
     * {@link ScimExceptionHandler} answers {@code 413} when it finds it among the causes.
     */
    static final class BodyTooLarge extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLarge() {
            super("The request body is larger than " + MAX_BODY_BYTES / 1024 + " KiB");
        }
    }

    /** A body that fails past {@link #MAX_BODY_BYTES}. */
    private static final class BoundedStream extends ServletInputStream {

        private final ServletInputStream in;

        private long read;

        BoundedStream(ServletInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > MAX_BODY_BYTES) {
                throw new BodyTooLarge();
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }

    private static @Nullable String bearer(@Nullable String header) {
        if (header == null || header.length() < 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String value = header.substring(7).trim();
        return value.isEmpty() ? null : value;
    }
}
