package com.flowtivo.tinfirm.identity.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.identity.UserMembership;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.BearerGrant;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Personal access tokens (ADR-0045): a user creates, lists, renews and revokes their own; the bearer chain of the MCP
 * endpoint resolves a presented token to its user. Creating, renewing and revoking are audited in the instance chain
 * with the user as actor and the token as target. The audit details hold the scope and the expiry, never the token
 * and never its name (a user's free text may be personal data, which stays out of the hashed chain, CONVENTIONS §8).
 * <p>
 * A token outlives nobody's intent (the security review of 2026-10-27, D9 and L34; the owner's decision Q6; CONVENTIONS
 * §5): it expires {@link #LIFETIME} after its creation or its last renewal; it reaches only the companies it names
 * (every company of the user when it names none, as tokens made before); it is created only with the current password
 * of a local user, or from a session that signed in less than {@link #RECENT_SIGN_IN} ago for a user without one; and
 * it is revoked with the user's OAuth grants on a password change or reset, and when the user leaves a company it
 * names ({@link BearerRevocations}).
 */
@Service
class ApiTokenService {

    /** Active (neither revoked nor expired) tokens a user may hold. */
    static final int MAX_ACTIVE_TOKENS = 25;

    /** The most tokens a listing returns, newest first; expired tokens stay listed until they are revoked. */
    static final int MAX_LISTED_TOKENS = 200;

    /** {@code last_used_at} is written at most this often per token, so a request is not a write. */
    static final Duration LAST_USED_GRANULARITY = Duration.ofMinutes(1);

    /** A token's life from its creation or renewal, and the latest expiry a creation may ask for. */
    static final Duration LIFETIME = Duration.ofDays(90);

    /** How recently a user without a password must have signed in to create a token. */
    static final Duration RECENT_SIGN_IN = Duration.ofMinutes(10);

    /** The most companies one token names. */
    static final int MAX_COMPANIES = 100;

    /** Why a token was revoked by something else than its user's own revoke ({@code reason} in the audit). */
    enum RevokeReason {
        PASSWORD_CHANGED, PASSWORD_RESET, LEFT_COMPANY
    }

    private final ApiTokenRepository tokens;

    private final UserRepository users;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final PasswordHasher hasher;

    private final MembershipDirectory memberships;

    ApiTokenService(ApiTokenRepository tokens, UserRepository users, AuditLog auditLog, TimeSource time,
            PasswordHasher hasher, MembershipDirectory memberships) {
        this.tokens = tokens;
        this.users = users;
        this.auditLog = auditLog;
        this.time = time;
        this.hasher = hasher;
        this.memberships = memberships;
    }

    /**
     * What a creation asks for: the name, scope and expiry, the companies (null for every company of the user), and
     * the proof that the person is there: a local user's current password, or the creation time of the session of a
     * user without a password.
     */
    record CreateRequest(String name, ApiScope scope, @Nullable Instant expiresAt, @Nullable List<UUID> companyIds,
            @Nullable String currentPassword, @Nullable Instant sessionCreatedAt) {

        @Override
        public String toString() {
            return "CreateRequest[scope=" + scope + ", expiresAt=" + expiresAt + ", companyIds=" + companyIds
                    + ", currentPassword=***]";
        }
    }

    /** A new token and the token itself, which exists only in this result. */
    record Created(ApiTokenEntity token, String secret) {
    }

    /**
     * Creates a token for the calling user: 400 {@code WRONG_PASSWORD} without a local user's right current password,
     * 403 {@code RECENT_SIGN_IN_REQUIRED} for a user without a password whose session signed in more than 10 minutes
     * ago, 400 for a company the user is not a member of, 409 {@code TOKEN_LIMIT} with 25 active tokens.
     */
    @Transactional
    public Created create(CreateRequest request) {
        AuthenticatedUser user = CurrentActor.requireUser();
        String trimmed = request.name().strip();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw ApiException.validation("The name must have 1 to 100 characters");
        }
        Instant now = time.now();
        Instant latest = now.plus(LIFETIME);
        Instant expiry = (request.expiresAt() != null ? request.expiresAt() : latest).truncatedTo(ChronoUnit.MICROS);
        if (!expiry.isAfter(now)) {
            throw ApiException.validation("The expiry must be in the future");
        }
        if (expiry.isAfter(latest)) {
            throw ApiException.validation("A token expires at most " + LIFETIME.toDays() + " days from now; renew it "
                    + "before then");
        }
        Set<UUID> companies = companies(user.id(), request.companyIds());
        // Serializes concurrent creates of one user, so the cap holds.
        users.lockById(user.id()).orElseThrow(() -> ApiException.unauthorized("Authentication required"));
        UserEntity account = users.findById(user.id())
            .orElseThrow(() -> ApiException.unauthorized("Authentication required"));
        requirePresence(account, request, now);
        if (tokens.countActive(user.id(), now) >= MAX_ACTIVE_TOKENS) {
            throw ApiException.conflict(ProblemCode.TOKEN_LIMIT,
                    "You have " + MAX_ACTIVE_TOKENS + " active tokens. Revoke one before you create another.");
        }
        String secret = ApiTokens.generate();
        ApiTokenEntity token = tokens.save(new ApiTokenEntity(Ids.newId(), user.id(), trimmed, request.scope(),
                ApiTokens.hash(secret), now, expiry, companies));
        auditLog.record(AuditRecord.instance(AuditAction.TOKEN_CREATED, Actor.user(user.id()))
            .target("api_token", token.getId())
            .detail("scope", request.scope().name())
            .detail("expiresAt", expiry.toString())
            .detail("companyIds", token.getCompanyIds() != null
                    ? token.getCompanyIds().stream().map(UUID::toString).toList() : null)
            .build());
        return new Created(token, secret);
    }

    /**
     * The companies a creation names, each one the user is a member of; null for none named (every company of the
     * user). An empty list, more than {@value #MAX_COMPANIES}, or a company the user is not a member of is 400.
     */
    private @Nullable Set<UUID> companies(UUID userId, @Nullable List<UUID> requested) {
        if (requested == null) {
            return null;
        }
        Set<UUID> named = new LinkedHashSet<>(requested);
        if (named.isEmpty() || named.size() > MAX_COMPANIES || named.contains(null)) {
            throw ApiException.validation("A token names 1 to " + MAX_COMPANIES + " companies, or none for all");
        }
        Set<UUID> mine = memberships.membershipsOf(userId).stream().map(UserMembership::companyId)
            .collect(Collectors.toSet());
        if (!mine.containsAll(named)) {
            throw ApiException.validation("A token names only companies you are a member of");
        }
        return named;
    }

    /**
     * The person is there: a local user gives their current password; a user without one (an identity provider's)
     * signed in less than {@link #RECENT_SIGN_IN} ago, by the session's creation time.
     */
    private void requirePresence(UserEntity account, CreateRequest request, Instant now) {
        if (account.isLocal()) {
            String password = request.currentPassword();
            if (password == null || password.isEmpty() || !hasher.matches(password, account.getPasswordHash())) {
                throw ApiException.badRequest(ProblemCode.WRONG_PASSWORD,
                        "Give your current password to create a token");
            }
            return;
        }
        Instant signedIn = request.sessionCreatedAt();
        if (signedIn == null || signedIn.isBefore(now.minus(RECENT_SIGN_IN))) {
            throw ApiException.forbidden(ProblemCode.RECENT_SIGN_IN_REQUIRED, "Sign in again to create a token: "
                    + "your sign-in must be less than " + RECENT_SIGN_IN.toMinutes() + " minutes old");
        }
    }

    /**
     * Renews one of the calling user's tokens: its expiry becomes {@link #LIFETIME} from now. 404 for another user's,
     * a revoked or an unknown token; 409 {@code TOKEN_EXPIRED} for an expired one.
     */
    @Transactional
    public ApiTokenEntity renewOwn(UUID tokenId) {
        AuthenticatedUser user = CurrentActor.requireUser();
        ApiTokenEntity token = tokens.findByIdAndUserId(tokenId, user.id())
            .filter(found -> !found.isRevoked())
            .orElseThrow(() -> ApiException.notFound("Token not found"));
        Instant now = time.now();
        if (token.isExpired(now)) {
            throw ApiException.conflict(ProblemCode.TOKEN_EXPIRED, "The token expired; create a new one");
        }
        Instant expiry = now.plus(LIFETIME).truncatedTo(ChronoUnit.MICROS);
        token.renew(expiry);
        auditLog.record(AuditRecord.instance(AuditAction.TOKEN_RENEWED, Actor.user(user.id()))
            .target("api_token", token.getId())
            .detail("scope", token.getScope().name())
            .detail("expiresAt", expiry.toString())
            .build());
        return token;
    }

    /** The calling user's tokens that are not revoked, newest first. */
    @Transactional(readOnly = true)
    public List<ApiTokenEntity> listOwn() {
        AuthenticatedUser user = CurrentActor.requireUser();
        return tokens.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDescIdDesc(user.id(),
                Limit.of(MAX_LISTED_TOKENS));
    }

    /** Revokes one of the calling user's tokens; idempotent. Another user's token is 404, as an unknown one. */
    @Transactional
    public void revokeOwn(UUID tokenId) {
        AuthenticatedUser user = CurrentActor.requireUser();
        ApiTokenEntity token = tokens.findByIdAndUserId(tokenId, user.id())
            .orElseThrow(() -> ApiException.notFound("Token not found"));
        if (token.isRevoked()) {
            return;
        }
        token.revoke(time.now());
        auditLog.record(AuditRecord.instance(AuditAction.TOKEN_REVOKED, Actor.user(user.id()))
            .target("api_token", token.getId())
            .detail("scope", token.getScope().name())
            .build());
    }

    /**
     * Revokes the user's tokens that are not revoked yet: all of them, or, with a company, those that name it. By the
     * change that revokes them, audited each as {@code token.revoked} with the reason; in its transaction.
     */
    @Transactional
    public int revokeAll(UUID userId, @Nullable UUID companyId, RevokeReason reason) {
        Instant now = time.now();
        int revoked = 0;
        for (ApiTokenEntity token : tokens.findByUserIdAndRevokedAtIsNull(userId)) {
            if (companyId != null && !token.names(companyId)) {
                continue;
            }
            token.revoke(now);
            revoked++;
            auditLog.record(AuditRecord.instance(AuditAction.TOKEN_REVOKED, CurrentActor.actor())
                .target("api_token", token.getId())
                .detail("scope", token.getScope().name())
                .detail("reason", reason.name())
                .detail("companyId", companyId != null ? companyId.toString() : null)
                .build());
        }
        return revoked;
    }

    /**
     * The active user and the grant of a presented token on the endpoint of {@code companyId}; empty when the token is
     * malformed, unknown, revoked or expired, does not reach that company, or its user is deactivated. Records the use
     * at most once a minute per token.
     */
    @Transactional
    public Optional<ResolvedBearer> resolve(String presented, @Nullable UUID companyId) {
        if (!ApiTokens.isWellFormed(presented)) {
            return Optional.empty();
        }
        Instant now = time.now();
        Optional<ApiTokenEntity> found = tokens.findByTokenHash(ApiTokens.hash(presented))
            .filter(token -> token.isActive(now))
            .filter(token -> companyId != null ? token.reaches(companyId) : token.getCompanyIds() == null);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiTokenEntity token = found.get();
        Optional<AuthenticatedUser> user = users.findById(token.getUserId())
            .filter(UserEntity::isActive)
            .map(UserEntity::toAuthenticatedUser);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        Instant lastUsed = token.getLastUsedAt();
        Instant staleBefore = now.minus(LAST_USED_GRANULARITY);
        if (lastUsed == null || lastUsed.isBefore(staleBefore)) {
            tokens.touch(token.getId(), now, staleBefore);
        }
        return Optional.of(new ResolvedBearer(user.get(),
                new BearerGrant(BearerGrant.Kind.PERSONAL_ACCESS_TOKEN, token.getId(), token.getScope())));
    }
}
