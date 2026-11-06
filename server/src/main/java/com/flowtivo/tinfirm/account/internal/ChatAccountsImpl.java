package com.flowtivo.tinfirm.account.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.ChatAccounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * {@link ChatAccounts} (the package 5 contract, §3.2, §5.4; ADR-0072): the bot's credential through
 * {@link AccountService}'s own save and removal, lent to its service account through {@link Credentials}, and the
 * members' {@code LINK} accounts, which nothing else writes (C4).
 */
@Component
class ChatAccountsImpl implements ChatAccounts {

    private static final String IDENTITY_INDEX = "account_identity_uq";

    private static final String PRINCIPAL_INDEX = "account_principal_uq";

    private final AccountStore store;

    private final AccountService accounts;

    private final Credentials credentials;

    private final Principals principals;

    private final Systems systems;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    ChatAccountsImpl(AccountStore store, AccountService accounts, Credentials credentials, Principals principals,
            Systems systems, AuditLog auditLog, ApplicationEventPublisher events, TimeSource time) {
        this.store = store;
        this.accounts = accounts;
        this.credentials = credentials;
        this.principals = principals;
        this.systems = systems;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountInfo storeBot(UUID companyId, UUID systemId, @Nullable UUID botAccountId, UUID serviceIdentityId,
            String token, String externalId, @Nullable String login) {
        PrincipalInfo service = principals.find(companyId, serviceIdentityId)
            .filter(PrincipalInfo::service)
            .orElseThrow(() -> ApiException.notFound("Service account not found"));
        if (!service.active()) {
            throw ApiException.conflict(ProblemCode.PRINCIPAL_SUSPENDED, "The service account is suspended");
        }
        SystemInfo system = systems.find(companyId, systemId)
            .filter(found -> found.kind().chat())
            .orElseThrow(() -> ApiException.notFound("System not found"));
        UUID actingUserId = CurrentActor.requireUser().id();
        AccountService.Holder target = botAccountId != null && store.find(companyId, botAccountId).isPresent()
                ? new AccountService.Holder(system, null, botAccountId, null, actingUserId)
                : new AccountService.Holder(system, null, null, botName(companyId, systemId), actingUserId);
        SystemCalls.Identity identity = new SystemCalls.Identity(externalId, login, null);
        if (accounts.identityInUse(target, identity)) {
            throw ApiException.conflict(ProblemCode.IDENTITY_IN_USE,
                    "A member's own account is this identity on the system");
        }
        AccountStore.AccountRow bot = accounts.save(target, AccountInfo.Kind.TOKEN, token, identity, null, null);
        credentials.lendTo(companyId, systemId, bot.id(), service.id(), GrantInfo.Access.WRITE,
                store.lending(companyId, service.id(), systemId, true));
        return bot.info();
    }

    /** A free name for a new bot credential: {@code Bot}, else {@code Bot 2} and on. */
    private String botName(UUID companyId, UUID systemId) {
        String name = "Bot";
        for (int i = 2; store.credentialNameTaken(companyId, systemId, name, null); i++) {
            name = "Bot " + i;
        }
        return name;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeBot(UUID companyId, UUID accountId, String cause) {
        store.lock(companyId, accountId).ifPresent(account -> accounts.remove(account, null, cause, false));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> botHolder(UUID companyId, UUID accountId) {
        return store.lendingsOf(companyId, accountId).stream()
            .map(AccountStore.LendingRow::servicePrincipalId)
            .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountInfo link(UUID companyId, UUID systemId, UUID memberPrincipalId, String externalId,
            @Nullable String login, How how, String chatUserId) {
        PrincipalInfo member = principals.find(companyId, memberPrincipalId)
            .filter(PrincipalInfo::member)
            .filter(PrincipalInfo::active)
            .orElseThrow(() -> ApiException.notFound("Member not found"));
        if (store.withIdentity(companyId, systemId, externalId).isPresent()) {
            throw ApiException.conflict(ProblemCode.IDENTITY_IN_USE, "This chat user is linked to a member already");
        }
        if (store.of(companyId, systemId, member.id(), false).isPresent()) {
            throw ApiException.conflict(ProblemCode.ALREADY_LINKED, "You are linked to another chat user of this "
                    + "system already: unlink it first");
        }
        Instant now = time.now();
        Actor actor = CurrentActor.actor();
        AccountStore.AccountRow row = new AccountStore.AccountRow(Ids.newId(), companyId, systemId, member.id(),
                member.kind(), null, true, AccountInfo.Kind.LINK, null, cut(externalId), cut(login), null, null,
                null, AccountInfo.Status.OK, null, now, actor.type() == ActorType.USER ? actor.id() : null, null);
        try {
            store.insert(row, actor.type() == ActorType.USER ? actor.id() : null);
        }
        catch (DataIntegrityViolationException ex) {
            if (Constraints.violated(ex, IDENTITY_INDEX)) {
                throw ApiException.conflict(ProblemCode.IDENTITY_IN_USE, "This chat user is linked to a member "
                        + "already");
            }
            if (Constraints.violated(ex, PRINCIPAL_INDEX)) {
                throw ApiException.conflict(ProblemCode.ALREADY_LINKED, "You are linked on this system already");
            }
            throw ex;
        }
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.ACCOUNT_CONNECTED, actor)
            .target(AccountService.TARGET, row.id())
            .detail("systemId", systemId.toString())
            .detail("principalId", member.id().toString())
            .detail("kind", AccountInfo.Kind.LINK.name())
            .detail("how", how.name())
            .detail("chatUserId", chatUserId);
        if (member.userId() != null) {
            audit.detail("userId", member.userId().toString());
        }
        auditLog.record(audit.build());
        events.publishEvent(new AccountEvents.AccountConnected(companyId, row.id(), systemId, member.id()));
        return row.info();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountInfo> ofIdentity(UUID companyId, UUID systemId, String externalId) {
        return store.withIdentity(companyId, systemId, externalId).map(AccountStore.AccountRow::info);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeByAdmin(UUID companyId, UUID accountId) {
        store.lock(companyId, accountId)
            .ifPresent(account -> accounts.removeAndRevokeAfterCommit(account, accounts.memberOf(account), "admin"));
    }

    private static @Nullable String cut(@Nullable String value) {
        return value == null || value.length() <= 255 ? value : value.substring(0, 255);
    }
}
