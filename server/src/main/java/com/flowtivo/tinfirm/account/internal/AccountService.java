package com.flowtivo.tinfirm.account.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.Account;
import com.flowtivo.tinfirm.shared.api.model.SignInStart;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.ClientCredentials;
import com.flowtivo.tinfirm.system.OAuthTokens;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.vault.RevealedSecret;

/**
 * Connecting and disconnecting accounts (the package 1 contract, §3.6, §3.8, §6, I1 to I4; ADR-0072): a member manages
 * their own accounts only; OWNER and ADMIN manage a system's credentials ({@link Credentials}, which connects them
 * here). A token is checked with the system's "who am I" and refused when the system refuses it; a sign-in goes
 * through the system's app with PKCE and a state bound to the person's session, and comes back through the one
 * callback ({@link SignInCallback}). One account per member per system (I1); an identity on a system is one member's
 * own account's, or the system's credentials', never both (I2). Every change is audited (a member's own account with
 * the member's user id, a credential with its name, never a credential's value) and announced ({@link AccountEvents}).
 * <p>
 * A call to the system (its "who am I", the code exchange, the revocation of a disconnected grant) runs outside any
 * transaction: a short transaction before it authorises the caller and reads what the call needs, another after it
 * checks the holder and the system again and stores the outcome, where the database keeps I1 and I2 against a
 * concurrent connect.
 */
@Service
class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    static final String TARGET = "account";

    /** How long a sign-in may take (contract §3.8). */
    static final Duration SIGN_IN_LIFETIME = Duration.ofMinutes(10);

    static final int MAX_RETURN_TO = 1000;

    static final int MAX_CREDENTIAL_NAME = 100;

    private static final String IDENTITY_INDEX = "account_identity_uq";

    static final String NAME_INDEX = "account_name_uq";

    private final AccountStore store;

    private final SignInStore signIns;

    private final AccountSecrets secrets;

    private final Grants grants;

    private final Principals principals;

    private final Systems systems;

    private final SystemCalls calls;

    private final CompanyAccessGuard guard;

    private final RefusalAudit refusals;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final AccountMapper mapper;

    private final AccountRevocations revocations;

    private final SignInVerifiers verifiers;

    private final TransactionTemplate transactions;

    private final TimeSource time;

    private final ChatSystems chatSystems;

    AccountService(AccountStore store, SignInStore signIns, AccountSecrets secrets, Grants grants,
            Principals principals, Systems systems, SystemCalls calls, CompanyAccessGuard guard, RefusalAudit refusals,
            AuditLog auditLog, ApplicationEventPublisher events, AccountMapper mapper, AccountRevocations revocations,
            SignInVerifiers verifiers, PlatformTransactionManager transactionManager, TimeSource time,
            ChatSystems chatSystems) {
        this.store = store;
        this.signIns = signIns;
        this.secrets = secrets;
        this.grants = grants;
        this.principals = principals;
        this.systems = systems;
        this.calls = calls;
        this.guard = guard;
        this.refusals = refusals;
        this.auditLog = auditLog;
        this.events = events;
        this.mapper = mapper;
        this.revocations = revocations;
        this.verifiers = verifiers;
        this.transactions = new TransactionTemplate(transactionManager);
        this.time = time;
        this.chatSystems = chatSystems;
    }

    /**
     * Whose account a request is about, and who asks: a member's own account, or one of a system's credentials.
     *
     * @param principal      the member whose own account it is; null for a credential
     * @param credentialId   the credential, when it exists; null for a member's own account and a new credential
     * @param credentialName a new credential's name; null otherwise
     * @param actingUserId   the member, or the admin who manages the system's credentials
     */
    record Holder(SystemInfo system, @Nullable PrincipalInfo principal, @Nullable UUID credentialId,
            @Nullable String credentialName, UUID actingUserId) {

        UUID companyId() {
            return system.companyId();
        }

        /** A credential the system holds (ADR-0072), rather than a member's own account. */
        boolean credential() {
            return principal == null;
        }
    }

    /** The caller's own account on a system, as a request names it. */
    record Target(UUID companyId, UUID systemId) {
    }

    // ---- Whose account ---------------------------------------------------------------------------------------------

    /** The caller's own account on a system they see (any member); in the caller's transaction. */
    Holder mine(UUID companyId, UUID systemId) {
        guard.require(companyId, CompanyPermission.VIEW_SYSTEMS);
        UUID userId = CurrentActor.requireUser().id();
        PrincipalInfo principal = principals.ofMember(companyId, userId)
            .orElseThrow(() -> ApiException.notFound("Only members hold accounts"));
        SystemInfo system = systems.find(companyId, systemId).orElseThrow(AccountService::systemNotFound);
        if (system.personal() && !system.ownedBy(principal.id())) {
            throw systemNotFound();
        }
        return new Holder(system, principal, null, null, userId);
    }

    /**
     * A company system's credential (OWNER, ADMIN; ADR-0072): the one named, or a new one by its name, free on the
     * system. A personal server holds its owner's own account only ({@code 400 PERSONAL_SYSTEM}). In the caller's
     * transaction.
     */
    Holder credential(UUID companyId, UUID systemId, @Nullable UUID credentialId, @Nullable String newName) {
        Holder system = credentialSystem(companyId, systemId);
        if (credentialId != null) {
            AccountStore.AccountRow credential = store.find(companyId, credentialId)
                .filter(row -> row.credential() && row.systemId().equals(systemId))
                .orElseThrow(AccountService::credentialNotFound);
            return new Holder(system.system(), null, credential.id(), null, system.actingUserId());
        }
        String name = credentialName(Objects.requireNonNull(newName));
        if (store.credentialNameTaken(companyId, systemId, name, null)) {
            throw duplicateCredentialName(system.system(), name);
        }
        return new Holder(system.system(), null, null, name, system.actingUserId());
    }

    /** A company system whose credentials the caller manages (OWNER, ADMIN), as a holder that names none yet. */
    Holder credentialSystem(UUID companyId, UUID systemId) {
        guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
        UUID userId = CurrentActor.requireUser().id();
        SystemInfo system = systems.find(companyId, systemId).orElseThrow(AccountService::systemNotFound);
        if (system.personal()) {
            boolean own = principals.ofMember(companyId, userId).map(PrincipalInfo::id)
                .filter(system::ownedBy)
                .isPresent();
            if (!own) {
                throw systemNotFound();
            }
            throw ApiException.badRequest(ProblemCode.PERSONAL_SYSTEM, "A personal MCP server holds its owner's own "
                    + "account only");
        }
        return new Holder(system, null, null, null, userId);
    }

    /**
     * The holder again, in the transaction after a call to the system: its member, its system and its credential as
     * they are now (404 when one is gone); the caller was authorised before the call. The system's row is locked
     * shared ({@code Systems.lockShared}; before the account's row the transaction then writes, CONVENTIONS §10) and
     * must have the kind and the base URL the call went to: an MCP server moved to another endpoint meanwhile
     * (ADR-0079) takes nothing checked at the one before ({@code 409 SYSTEM_MOVED}), since the move removed every
     * account on it and this one would stay behind it.
     */
    private Holder again(Holder holder) {
        PrincipalInfo principal = null;
        if (holder.principal() != null) {
            principal = principals.find(holder.companyId(), holder.principal().id())
                .orElseThrow(() -> ApiException.notFound("The principal is gone"));
        }
        SystemInfo system = systems.lockShared(holder.companyId(), holder.system().id())
            .orElseThrow(AccountService::systemNotFound);
        if (system.kind() != holder.system().kind() || !Objects.equals(system.baseUrl(), holder.system().baseUrl())) {
            throw ApiException.conflict(ProblemCode.SYSTEM_MOVED, "The endpoint of " + system.name() + " changed "
                    + "while the account was checked at the one before: nothing is stored; connect it again");
        }
        if (holder.credentialId() != null && store.find(holder.companyId(), holder.credentialId()).isEmpty()) {
            throw credentialNotFound();
        }
        return new Holder(system, principal, holder.credentialId(), holder.credentialName(), holder.actingUserId());
    }

    /**
     * A call the system module did not make ({@code INVALID}: the system gone, or moved to another endpoint since the
     * holder was read, ADR-0079) is told apart from the system's refusal before one is audited: the holder is read
     * again, which answers 404 or {@code 409 SYSTEM_MOVED} then; any other failure is the system's.
     */
    private void requireStillThere(Holder holder, CallResult.Failure failure) {
        if (failure.kind() == CallResult.Kind.INVALID) {
            inTransaction(() -> again(holder));
        }
    }

    /**
     * Whether an account may be connected now: an active member, a system not paused, a usable personal server, a kind
     * that takes accounts here. On a chat or webhook system none does, but on Slack a sign-in through its app's user
     * scopes, for {@code history} (the package 5 contract, §2.2, open point 13): a member's own, or a credential; never
     * the system's bot ({@code 409 BOT_ACCOUNT_IN_USE}), which only {@code PUT /systems/{id}/bot} sets. A token pasted
     * for one stays refused.
     *
     * @param signIn an OAuth sign-in, rather than a token
     */
    void requireConnectable(Holder holder, boolean signIn) {
        if (holder.principal() != null && !holder.principal().active()) {
            throw ApiException.conflict(ProblemCode.PRINCIPAL_SUSPENDED, "The principal is suspended");
        }
        if (holder.system().paused()) {
            throw ApiException.conflict(ProblemCode.SYSTEM_PAUSED, holder.system().name() + " is paused by an admin");
        }
        if (holder.system().personal() && !systems.personalSystemsOn(holder.companyId())) {
            throw ApiException.conflict(ProblemCode.PERSONAL_SYSTEMS_OFF,
                    "The company switched personal MCP servers off");
        }
        requireNotBot(holder);
        SystemKind kind = holder.system().kind();
        if (kind.chat() || kind == SystemKind.WEBHOOK) {
            if (signIn && kind == SystemKind.SLACK) {
                return;
            }
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "Accounts on a " + kind.label()
                    + " system are its bot, set on the system's page, and its members' links, made from the chat"
                    + (kind == SystemKind.SLACK ? "; a Slack account for history is connected by signing in to Slack"
                            : ""));
        }
    }

    /**
     * C9 (the package 5 contract, §3.2): a chat system's bot is a credential that changes and goes only through
     * {@code PUT} and {@code DELETE /systems/{id}/bot}, never among the system's credentials.
     */
    void requireNotBot(Holder holder) {
        if (holder.credentialId() != null && chatSystems.botOf(holder.companyId(), holder.credentialId()).isPresent()) {
            throw ApiException.conflict(ProblemCode.BOT_ACCOUNT_IN_USE, "This credential is the system's bot: it is "
                    + "changed and removed with the bot, on the system's page");
        }
    }

    // ---- A token ---------------------------------------------------------------------------------------------------

    /** Stores a token as the caller's own account on the system ({@link #storeToken}). */
    public Account storeMyToken(Target target, String token) {
        AccountStore.AccountRow row = storeToken(() -> mine(target.companyId(), target.systemId()), token);
        return mapper.toDto(AccountMapper.AccountDetails.of(row));
    }

    /**
     * Stores a token as the holder's account, after the system's "who am I" accepted it; a refusal is audited in a
     * transaction of its own and answered 400 {@code ACCOUNT_REFUSED}. The "who am I" runs between the transaction
     * that authorises the caller ({@code authorise}, which names the holder) and the one that stores the account.
     */
    AccountStore.AccountRow storeToken(Supplier<Holder> authorise, String token) {
        Holder holder = inTransaction(() -> {
            Holder found = authorise.get();
            requireConnectable(found, false);
            return found;
        });
        String value = token.strip();
        if (value.isEmpty() || value.length() > 4000) {
            throw ApiException.validation("token must be 1 to 4000 characters");
        }
        CallResult<SystemCalls.Identity> identity = calls.whoAmI(holder.system(),
                new SystemCalls.Credential(value, false));
        if (identity.failure() != null) {
            requireStillThere(holder, identity.failure());
            // The system's words, without the token should it have quoted it.
            String reason = Objects.requireNonNull(identity.failureMessage()).replace(value, "***");
            refused(holder, reason);
            throw ApiException.badRequest(ProblemCode.ACCOUNT_REFUSED, "The system refused the token: " + reason);
        }
        SystemCalls.Identity who = Objects.requireNonNull(identity.value());
        return inTransaction(() -> {
            Holder current = again(holder);
            requireConnectable(current, false);
            requireIdentityFree(current, who);
            return save(current, AccountInfo.Kind.TOKEN, value, who, null, null);
        });
    }

    // ---- A client's own credentials -------------------------------------------------------------------------------

    /**
     * Stores an OAuth client's own credentials as the credential's ({@code CLIENT}): an Atlassian service account's
     * OAuth 2.0 credential on Jira Cloud. The client mints an access token first ({@code client_credentials}), and the
     * site names who it is ({@link SystemCalls#clientIdentity}); a refusal of either is audited in a transaction of its
     * own and answered 400 {@code ACCOUNT_REFUSED}. A member's own account, or a system of another kind, takes none
     * ({@code 400 SYSTEM_KIND_INVALID}). The calls run between the transaction that authorises the caller and the one
     * that stores the credential, as for a token.
     */
    AccountStore.AccountRow storeClient(Supplier<Holder> authorise, String clientId, String clientSecret) {
        Holder holder = inTransaction(() -> {
            Holder found = authorise.get();
            requireConnectable(found, false);
            requireClientCredentials(found);
            return found;
        });
        String id = clientId.strip();
        String secret = clientSecret.strip();
        if (id.isEmpty() || id.length() > ClientCredentials.MAX_CLIENT_ID) {
            throw ApiException.invalid("clientId must be 1 to " + ClientCredentials.MAX_CLIENT_ID + " characters",
                    "clientId");
        }
        if (secret.isEmpty() || secret.length() > ClientCredentials.MAX_CLIENT_SECRET) {
            throw ApiException.invalid("clientSecret must be 1 to " + ClientCredentials.MAX_CLIENT_SECRET
                    + " characters", "clientSecret");
        }
        ClientCredentials client = new ClientCredentials(id, secret, null);
        CallResult<OAuthTokens> minted = calls.clientToken(holder.system(), client);
        if (minted.failure() != null) {
            requireStillThere(holder, minted.failure());
            String reason = Objects.requireNonNull(minted.failureMessage()).replace(secret, "***");
            refused(holder, reason);
            throw ApiException.badRequest(ProblemCode.ACCOUNT_REFUSED, "The system refused the client credentials: "
                    + reason);
        }
        OAuthTokens tokens = Objects.requireNonNull(minted.value());
        CallResult<SystemCalls.Identity> identity = calls.clientIdentity(holder.system(), tokens.accessToken());
        if (identity.failure() != null) {
            requireStillThere(holder, identity.failure());
            String reason = Objects.requireNonNull(identity.failureMessage()).replace(secret, "***")
                .replace(tokens.accessToken(), "***");
            refused(holder, reason);
            throw ApiException.badRequest(ProblemCode.ACCOUNT_REFUSED, "The system refused the client's token: "
                    + reason);
        }
        SystemCalls.Identity who = Objects.requireNonNull(identity.value());
        return inTransaction(() -> {
            Holder current = again(holder);
            requireConnectable(current, false);
            requireClientCredentials(current);
            requireIdentityFree(current, who);
            return save(current, AccountInfo.Kind.CLIENT, client.minted(tokens).json(), who, tokens.scope(),
                    tokens.expiresAt());
        });
    }

    /** Client credentials: a system's credential, on a kind that takes them ({@link SystemKind#clientCredentials}). */
    private static void requireClientCredentials(Holder holder) {
        if (!holder.credential()) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "Client credentials are a credential of "
                    + "the system's, never a member's own account");
        }
        if (!holder.system().kind().clientCredentials()) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "A " + holder.system().kind().label()
                    + " system takes no client credentials: only Jira Cloud does, for an Atlassian service account");
        }
    }

    /** I2: the identity is another member's own account, or (for a member) a credential of the system. */
    private void requireIdentityFree(Holder holder, SystemCalls.Identity identity) {
        Optional<AccountStore.AccountRow> other = identityHolder(holder, identity);
        if (other.isPresent()) {
            throw identityInUse(holder, identity, other.get());
        }
    }

    boolean identityInUse(Holder holder, SystemCalls.Identity identity) {
        return identityHolder(holder, identity).isPresent();
    }

    /**
     * The account of this identity on the system that the holder may not share it with (I2), if any: for a member,
     * another member's own account or a credential; for a credential, a member's own account (credentials may share
     * one: two tokens of one bot user).
     */
    private Optional<AccountStore.AccountRow> identityHolder(Holder holder, SystemCalls.Identity identity) {
        String externalId = identity.externalId();
        if (externalId == null) {
            return Optional.empty();
        }
        return store.withIdentity(holder.companyId(), holder.system().id(), externalId)
            .filter(other -> holder.credential() ? !other.credential()
                    : !Objects.equals(other.principalId(), Objects.requireNonNull(holder.principal()).id()));
    }

    /**
     * Stores the credential as the holder's account: a new one ({@code account.connected}), or the one it holds,
     * signed in or stored again ({@code account.reconnected}, {@code OK} again).
     */
    AccountStore.AccountRow save(Holder holder, AccountInfo.Kind kind, String secretValue,
            SystemCalls.Identity identity, @Nullable String scopes, @Nullable Instant expiresAt) {
        UUID companyId = holder.companyId();
        PrincipalInfo principal = holder.principal();
        Optional<AccountStore.AccountRow> existing;
        if (principal != null) {
            existing = store.of(companyId, holder.system().id(), principal.id(), true);
        }
        else if (holder.credentialId() != null) {
            existing = Optional.of(store.lock(companyId, holder.credentialId())
                .orElseThrow(AccountService::credentialNotFound));
        }
        else {
            existing = Optional.empty();
        }
        String name = principal != null ? null
                : existing.map(AccountStore.AccountRow::name).orElse(holder.credentialName());
        String label = name != null ? "`" + name + "` of the system " + holder.system().handle()
                : holder.system().handle();
        UUID secretId = secrets.store(companyId, principal, existing.map(AccountStore.AccountRow::secretId)
            .orElse(null), label, secretValue);
        Instant now = time.now();
        AccountStore.AccountRow row = new AccountStore.AccountRow(
                existing.map(AccountStore.AccountRow::id).orElseGet(Ids::newId), companyId, holder.system().id(),
                principal != null ? principal.id() : null, principal != null ? principal.kind() : null, name,
                existing.map(AccountStore.AccountRow::enabled).orElse(true), kind, secretId,
                cut(identity.externalId(), 255), cut(identity.login(), 255), identity.apiBaseUrl(), cut(scopes, 2000),
                expiresAt, AccountInfo.Status.OK, null, now, holder.actingUserId(),
                existing.map(AccountStore.AccountRow::lastUsedAt).orElse(null));
        try {
            if (existing.isPresent()) {
                store.reconnect(row, holder.actingUserId());
            }
            else {
                store.insert(row, holder.actingUserId());
            }
        }
        catch (DataIntegrityViolationException ex) {
            if (Constraints.violated(ex, IDENTITY_INDEX)) {
                throw identityInUse();
            }
            if (Constraints.violated(ex, NAME_INDEX)) {
                throw duplicateCredentialName(holder.system(), Objects.requireNonNull(name));
            }
            throw ex;
        }
        AuditRecord.Builder audit = holderDetails(AuditRecord
            .company(companyId, existing.isPresent() ? AuditAction.ACCOUNT_RECONNECTED : AuditAction.ACCOUNT_CONNECTED,
                    CurrentActor.actor())
            .target(TARGET, row.id())
            .detail("systemId", row.systemId().toString()), row)
            .detail("kind", kind.name());
        if (principal != null && principal.userId() != null) {
            audit.detail("userId", principal.userId().toString());
        }
        auditLog.record(audit.build());
        events.publishEvent(existing.isPresent()
                ? new AccountEvents.AccountReconnected(companyId, row.id(), row.systemId(), row.principalId())
                : new AccountEvents.AccountConnected(companyId, row.id(), row.systemId(), row.principalId()));
        return row;
    }

    /** Whose the account is, for its audit: {@code principalId} for a member's own, {@code credential} its name. */
    static AuditRecord.Builder holderDetails(AuditRecord.Builder audit, AccountStore.AccountRow account) {
        if (account.principalId() != null) {
            return audit.detail("principalId", account.principalId().toString());
        }
        return audit.detail("credential", Objects.requireNonNull(account.name()));
    }

    // ---- A sign-in -------------------------------------------------------------------------------------------------

    /** Starts a sign-in for the caller's own account ({@link #startSignIn}). */
    @Transactional
    public SignInStart startMySignIn(Target target, String returnTo, String sessionId) {
        return startSignIn(mine(target.companyId(), target.systemId()), returnTo, sessionId);
    }

    /**
     * Starts a sign-in with the system's app for the holder, in the caller's transaction: a state bound to the
     * person's session, kept hashed for ten minutes, the challenge of the PKCE verifier derived from it
     * ({@link SignInVerifiers}; the verifier is never stored), and the authorization URL to send the browser to.
     */
    SignInStart startSignIn(Holder holder, String returnTo, String sessionId) {
        requireConnectable(holder, true);
        String path = returnTo(returnTo);
        if (!calls.oauthAvailable(holder.system())) {
            throw ApiException.conflict(ProblemCode.OAUTH_NOT_AVAILABLE, "The system has no app to sign in with");
        }
        String state = SignInSecrets.random();
        String url = calls.authorizationUrl(holder.system(), state, SignInSecrets.challenge(verifiers.of(state)))
            .orElseThrow(() -> ApiException.conflict(ProblemCode.OAUTH_NOT_AVAILABLE,
                    "The system has no app to sign in with"));
        Instant now = time.now();
        signIns.insert(new SignInStore.Request(SignInSecrets.hash(state), holder.companyId(), holder.system().id(),
                holder.principal() != null ? holder.principal().id() : null, holder.credentialId(),
                holder.credentialName(), holder.actingUserId(), SignInSecrets.hash(sessionId), path, now,
                now.plus(SIGN_IN_LIFETIME)));
        return new SignInStart(url);
    }

    /**
     * Completes a consumed sign-in in its company: the caller may still connect the account (a 403 or 404 otherwise,
     * which the callback answers as {@code forbidden}), the code is exchanged, the account read and stored. Answers the
     * outcome the callback returns with: {@code connected}, {@code refused}, {@code exchange_failed},
     * {@code site_not_granted}, {@code identity_in_use}, {@code identity_shared} (a member's sign-in as the user of
     * one of the system's credentials), {@code already_linked} (a member's Slack sign-in as another Slack user than
     * their link), {@code name_taken} (a new credential's name, taken meanwhile), {@code system_moved} (an MCP server
     * moved to another endpoint meanwhile, ADR-0079: nothing is stored). The exchange and the "who am I" run between
     * the transaction that authorises the caller and the one that stores the account; the sign-in was consumed before
     * both, in a transaction of its own ({@link SignInCallback}).
     *
     * @param codeVerifier the sign-in's PKCE verifier, derived again from its state ({@link SignInVerifiers})
     */
    public String completeSignIn(SignInStore.Request request, String codeVerifier, String code) {
        Holder holder = inTransaction(() -> {
            UUID companyId = request.companyId();
            guard.require(companyId, CompanyPermission.VIEW_SYSTEMS);
            Holder found;
            if (request.principalId() != null) {
                found = mine(companyId, request.systemId());
                if (!Objects.requireNonNull(found.principal()).id().equals(request.principalId())) {
                    throw ApiException.forbidden("The sign-in is not the caller's");
                }
            }
            else if (request.credentialId() != null) {
                found = credential(companyId, request.systemId(), request.credentialId(), null);
            }
            else {
                // The name was free when the sign-in started; save tells when it was taken meanwhile.
                Holder system = credentialSystem(companyId, request.systemId());
                found = new Holder(system.system(), null, null,
                        credentialName(Objects.requireNonNull(request.credentialName())), system.actingUserId());
            }
            if (!found.actingUserId().equals(request.userId())) {
                throw ApiException.forbidden("The sign-in is not the caller's");
            }
            requireConnectable(found, true);
            return found;
        });
        try {
            return complete(holder, codeVerifier, code);
        }
        catch (ApiException ex) {
            // I2 kept by the database against a concurrent connect of the same identity; a name taken meanwhile; the
            // server moved meanwhile (ADR-0079).
            if (ex.code() == ProblemCode.IDENTITY_IN_USE) {
                return "identity_in_use";
            }
            if (ex.code() == ProblemCode.DUPLICATE_CREDENTIAL_NAME) {
                return "name_taken";
            }
            if (ex.code() == ProblemCode.SYSTEM_MOVED) {
                return "system_moved";
            }
            throw ex;
        }
    }

    /** {@link #completeSignIn} after its first transaction: the exchange, the "who am I" and the store. */
    private String complete(Holder holder, String codeVerifier, String code) {
        CallResult<OAuthTokens> exchanged = calls.exchange(holder.system(), code, codeVerifier);
        if (exchanged.failure() != null) {
            if (exchanged.failure().kind() == CallResult.Kind.UNAUTHORIZED) {
                refused(holder, Objects.requireNonNull(exchanged.failureMessage()));
                return "refused";
            }
            requireStillThere(holder, exchanged.failure());
            log.info("The code exchange at the system {} failed: {}", holder.system().id(),
                    exchanged.failureMessage());
            return "exchange_failed";
        }
        OAuthTokens tokens = Objects.requireNonNull(exchanged.value());
        CallResult<SystemCalls.Identity> identity = calls.whoAmI(holder.system(),
                new SystemCalls.Credential(tokens.accessToken(), true));
        if (identity.failure() != null) {
            if (holder.system().kind() == SystemKind.JIRA_CLOUD
                    && identity.failure().kind() == CallResult.Kind.NOT_FOUND) {
                return "site_not_granted";
            }
            requireStillThere(holder, identity.failure());
            refused(holder, Objects.requireNonNull(identity.failureMessage()).replace(tokens.accessToken(), "***"));
            return "refused";
        }
        SystemCalls.Identity who = Objects.requireNonNull(identity.value());
        return inTransaction(() -> {
            Holder current = again(holder);
            requireConnectable(current, true);
            Optional<AccountStore.AccountRow> other = identityHolder(current, who);
            if (other.isPresent()) {
                // A member signed in as the user of a shared credential: the page says whom to ask.
                return !current.credential() && other.get().credential() ? "identity_shared" : "identity_in_use";
            }
            if (linkedElsewhere(current, who)) {
                return "already_linked";
            }
            save(current, AccountInfo.Kind.OAUTH, tokens.json(), who, tokens.scope(), tokens.expiresAt());
            return "connected";
        });
    }

    /**
     * A member's Slack account is also their link to that chat user (the package 5 contract, §5.4, open point 13): a
     * sign-in replaces their {@code LINK}, or their account, of the same Slack user only; as another Slack user it is
     * {@code already_linked}, since a member has one identity per system (I1). A credential has no link.
     */
    private boolean linkedElsewhere(Holder holder, SystemCalls.Identity identity) {
        if (holder.system().kind() != SystemKind.SLACK || holder.principal() == null) {
            return false;
        }
        return store.of(holder.companyId(), holder.system().id(), holder.principal().id(), true)
            .filter(own -> !Objects.equals(own.externalId(), identity.externalId()))
            .isPresent();
    }

    /** {@code account.refused} for a token or a sign-in the system refused, in a transaction of its own. */
    private void refused(Holder holder, String reason) {
        String credential = holder.credentialName();
        if (holder.credential() && credential == null && holder.credentialId() != null) {
            // After the system's answer, outside any transaction: read in one, in the company's tenant context.
            credential = transactions.execute(status -> store.find(holder.companyId(), holder.credentialId())
                .map(AccountStore.AccountRow::name)
                .orElse(null));
        }
        refusals.refused(holder.companyId(), holder.system().id(),
                holder.principal() != null ? holder.principal().id() : null, credential, reason);
    }

    /**
     * A path of this site to return to: it starts with one {@code /}, has no scheme, host, backslash, fragment,
     * whitespace or control character, and is at most 1000 characters; anything else is 400, never a redirect away.
     */
    static String returnTo(String requested) {
        String path = requested.strip();
        boolean bad = path.isEmpty() || path.length() > MAX_RETURN_TO || !path.startsWith("/")
                || path.startsWith("//") || path.contains("\\") || path.contains("#")
                || path.chars().anyMatch(c -> c <= 0x20 || c == 0x7f);
        if (!bad) {
            try {
                URI uri = new URI(path);
                bad = uri.getScheme() != null || uri.getRawAuthority() != null;
            }
            catch (URISyntaxException ex) {
                bad = true;
            }
        }
        if (bad) {
            throw ApiException.validation("returnTo must be a path of this site, such as /c/{companyId}/accounts");
        }
        return path;
    }

    /** A credential's name: 1 to 100 characters, stripped, without control characters. */
    static String credentialName(String requested) {
        String name = requested.strip();
        if (name.isEmpty() || name.length() > MAX_CREDENTIAL_NAME || name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.invalid("name must be 1 to " + MAX_CREDENTIAL_NAME + " characters", "name");
        }
        return name;
    }

    // ---- Disconnecting ---------------------------------------------------------------------------------------------

    /** What the transaction before a disconnect read: the account, its member (none for a credential), its grant. */
    private record Disconnecting(AccountStore.AccountRow account, @Nullable PrincipalInfo member, GrantRead grant) {
    }

    /**
     * The OAuth grant of an account that goes, read before it goes.
     *
     * @param revocation what to revoke it with; null for nothing to revoke
     * @param unreadable whether its secret could not be revealed (a master key dropped before its rewrap, data
     *                   tampered with): the account goes unrevoked, audited with the reason {@code SECRET_UNREADABLE}
     */
    private record GrantRead(AccountRevocations.@Nullable Revocation revocation, boolean unreadable) {

        static final GrantRead NONE = new GrantRead(null, false);

        static final GrantRead UNREADABLE = new GrantRead(null, true);
    }

    /** The audit's reason for an account that went unrevoked because its grant could not be read. */
    static final String SECRET_UNREADABLE = "SECRET_UNREADABLE";

    /** Disconnects the caller's own account on the system ({@link #disconnect(Supplier)}). */
    public void disconnectMine(Target target) {
        disconnect(() -> {
            Holder holder = mine(target.companyId(), target.systemId());
            return store.of(holder.companyId(), holder.system().id(), Objects.requireNonNull(holder.principal()).id(),
                    false).orElseThrow(() -> ApiException.notFound("No account on this system"));
        });
    }

    /**
     * Disconnects the account the transaction before names ({@code authorise}); 404 when there is none. An OAuth grant
     * is revoked at the system where it offers revocation, between the transaction that reads the grant and the one
     * that removes the account (a failure does not stop the removal, nor does a grant that cannot be read).
     */
    void disconnect(Supplier<AccountStore.AccountRow> authorise) {
        Disconnecting before = inTransaction(() -> {
            AccountStore.AccountRow account = authorise.get();
            PrincipalInfo member = memberOf(account);
            return new Disconnecting(account, member, grantOf(account, member));
        });
        AccountRevocations.Revocation revocation = before.grant().revocation();
        boolean revoked = revocation != null && revocations.now(revocation);
        inTransaction(() -> {
            AccountStore.AccountRow account = store.lock(before.account().companyId(), before.account().id())
                .orElseThrow(() -> ApiException.notFound("No account on this system"));
            remove(account, before.member(), null, revoked, before.grant().unreadable() ? SECRET_UNREADABLE : null);
            return account;
        });
    }

    /** The member whose own account it is; null for a credential. */
    @Nullable PrincipalInfo memberOf(AccountStore.AccountRow account) {
        return account.principalId() != null
                ? principals.find(account.companyId(), account.principalId()).orElseThrow() : null;
    }

    /**
     * Removes the account, already locked, in the transaction of what removes it: its grants and lendings first, then
     * the account and its secret. Its OAuth grant is revoked at the system after the commit, in the background, best
     * effort; a grant that cannot be read goes unrevoked.
     *
     * @param cause what removed it, as nobody disconnected it ({@code principal.left}, {@code admin})
     */
    void removeAndRevokeAfterCommit(AccountStore.AccountRow account, @Nullable PrincipalInfo member, String cause) {
        GrantRead grant = grantOf(account, member);
        if (grant.unreadable()) {
            remove(account, member, cause, false, SECRET_UNREADABLE);
            return;
        }
        remove(account, member, cause, null, null);
        AccountRevocations.Revocation revocation = grant.revocation();
        if (revocation != null) {
            revocations.afterCommit(revocation);
        }
    }

    /**
     * Removes the account, already locked: its grants and lendings first, then the account and its secret.
     *
     * @param cause   what removed it, when nobody disconnected it ({@code system.deleted}, {@code principal.left},
     *                {@code system.bot_removed})
     * @param revoked whether the system confirmed revoking its OAuth grant; null when that is asked after the commit
     *                ({@code revocation} {@code after_commit} in the audit)
     */
    void remove(AccountStore.AccountRow account, @Nullable PrincipalInfo member, @Nullable String cause,
            @Nullable Boolean revoked) {
        remove(account, member, cause, revoked, null);
    }

    /**
     * As {@link #remove(AccountStore.AccountRow, PrincipalInfo, String, Boolean)}, with why its grant was not revoked
     * ({@code reason} in the audit, beside {@code revoked} false), when that is not the system's answer.
     */
    private void remove(AccountStore.AccountRow account, @Nullable PrincipalInfo member, @Nullable String cause,
            @Nullable Boolean revoked, @Nullable String notRevokedReason) {
        UUID companyId = account.companyId();
        String removal = cause != null ? cause : "account.disconnected";
        grants.removeAllOf(account, removal);
        for (AccountStore.LendingRow lending : store.lockLendingsOf(companyId, account.id())) {
            store.withdraw(companyId, lending.servicePrincipalId(), lending.systemId());
            auditLog.record(AuditRecord.company(companyId, AuditAction.CREDENTIAL_WITHDRAWN, CurrentActor.actor())
                .target(TARGET, account.id())
                .detail("accountId", account.id().toString())
                .detail("systemId", account.systemId().toString())
                .detail("serviceAccountId", lending.servicePrincipalId().toString())
                .detail("access", lending.access().name())
                .detail("cause", removal)
                .build());
        }
        store.delete(companyId, account.id());
        secrets.delete(companyId, member, account.secretId());
        AuditRecord.Builder audit = holderDetails(AuditRecord
            .company(companyId, AuditAction.ACCOUNT_DISCONNECTED, CurrentActor.actor())
            .target(TARGET, account.id())
            .detail("systemId", account.systemId().toString()), account);
        if (revoked != null) {
            audit.detail("revoked", revoked);
        }
        else {
            audit.detail("revocation", "after_commit");
        }
        if (notRevokedReason != null) {
            audit.detail("reason", notRevokedReason);
        }
        if (member != null && member.userId() != null) {
            audit.detail("userId", member.userId().toString());
        }
        if (cause != null) {
            audit.detail("cause", cause);
        }
        auditLog.record(audit.build());
        events.publishEvent(new AccountEvents.AccountDisconnected(companyId, account.id(), account.systemId(),
                account.principalId()));
    }

    /**
     * The OAuth grant of the account to revoke, read in the current transaction (its secret revealed, audited as
     * {@code secret.accessed} with the purpose {@code account-revoke:{accountId}}); nothing to revoke for a token, or a
     * system without an app. A secret that cannot be revealed (the vault cannot decrypt it: its master key was dropped
     * before the rewrap, its data tampered with) is nothing to revoke with: logged, and the account goes all the same,
     * since a grant nobody can read is no use to keep. The vault leaves the transaction usable after such a failure.
     */
    private GrantRead grantOf(AccountStore.AccountRow account, @Nullable PrincipalInfo member) {
        if (account.kind() != AccountInfo.Kind.OAUTH || account.secretId() == null) {
            return GrantRead.NONE;
        }
        SystemInfo system = systems.find(account.companyId(), account.systemId()).orElse(null);
        if (system == null || !system.oauthApp()) {
            return GrantRead.NONE;
        }
        OAuthTokens tokens;
        try (RevealedSecret secret = secrets.reveal(account.companyId(), member,
                Objects.requireNonNull(account.secretId()), "account-revoke:" + account.id())) {
            tokens = OAuthTokens.fromJson(secret.asString());
        }
        catch (RuntimeException ex) {
            // The vault's message names the failure, never key material or a value.
            log.warn("The grant of the account {} could not be read to revoke it, and goes unrevoked: {}",
                    account.id(), ex.toString());
            return GrantRead.UNREADABLE;
        }
        return tokens != null ? new GrantRead(new AccountRevocations.Revocation(account.companyId(), account.id(),
                system, tokens), false) : GrantRead.NONE;
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    private static @Nullable String cut(@Nullable String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static ApiException systemNotFound() {
        return ApiException.notFound("System not found");
    }

    static ApiException credentialNotFound() {
        return ApiException.notFound("Credential not found");
    }

    static ApiException duplicateCredentialName(SystemInfo system, String name) {
        return ApiException.conflict(ProblemCode.DUPLICATE_CREDENTIAL_NAME, system.name() + " has a credential named "
                + name + " already");
    }

    private static ApiException identityInUse() {
        return ApiException.conflict(ProblemCode.IDENTITY_IN_USE,
                "Another account of this identity is on the system");
    }

    /**
     * I2, saying who holds the identity: a credential by its name, which every member may see once it is lent to them,
     * so that a member whose own user is a shared credential learns whom to ask; a member never by name, only as
     * {@code MEMBER}.
     */
    private ApiException identityInUse(Holder holder, SystemCalls.Identity identity, AccountStore.AccountRow other) {
        String system = holder.system().name();
        String user = identity.login() != null
                ? "The " + holder.system().kind().label() + " user " + identity.login()
                : "This " + holder.system().kind().label() + " user";
        if (other.credential()) {
            String name = Objects.requireNonNull(other.name());
            return ApiException.conflict(ProblemCode.IDENTITY_IN_USE, user + " is the credential " + name + " of "
                    + system + ", which groups and service accounts use as a shared account. Ask an owner or admin "
                    + "to store " + name + " with another user, such as a machine user; then connect yours",
                    Map.of("details", Map.of("holder", "CREDENTIAL", "credentialName", name)));
        }
        String detail = holder.credential()
                ? user + " is a member's own account on " + system
                        + ": add the credential with a user of its own, such as a machine user"
                : user + " is another member's own account on " + system;
        return ApiException.conflict(ProblemCode.IDENTITY_IN_USE, detail,
                Map.of("details", Map.of("holder", "MEMBER")));
    }
}
