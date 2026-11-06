package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.TriggerAudience;
import com.flowtivo.tinfirm.conversation.TriggerTurns;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.Triggers;

/**
 * The trigger module's answers to other modules and to its parts: {@link Triggers}, {@link TriggerLocks}, and the
 * conversation module's ports {@link TriggerAudience} (who sees and acts on a trigger's conversations, the package 4
 * contract, §5.1, §5.13, T12, T15) and {@link TriggerTurns} (the firings a turn takes, §5.6, §5.12). In the caller's
 * transaction and tenant context.
 */
@Component
class TriggerPortsImpl implements Triggers, TriggerLocks, TriggerAudience, TriggerTurns {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_INSTANT;

    private final TriggerStore store;

    private final Principals principals;

    private final GroupMembership groups;

    private final JdbcClient jdbc;

    TriggerPortsImpl(TriggerStore store, Principals principals, GroupMembership groups, JdbcClient jdbc) {
        this.store = store;
        this.principals = principals;
        this.groups = groups;
        this.jdbc = jdbc;
    }

    // ---- Triggers, TriggerLocks --------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<TriggerInfo> find(UUID companyId, UUID triggerId) {
        return store.find(companyId, triggerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TriggerAccount> accounts(UUID companyId, UUID triggerId) {
        return store.accounts(companyId, triggerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TriggerInfo> paused(UUID companyId) {
        return store.livePaused(companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TriggerInfo> onSystem(UUID companyId, UUID systemId) {
        return store.liveOnSystem(companyId, systemId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TriggerInfo> ofAuthor(UUID companyId, UUID authorPrincipalId) {
        return store.liveOfAuthorInfo(companyId, authorPrincipalId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TriggerInfo> onSourceAccount(UUID companyId, UUID systemId, UUID accountId) {
        return jdbc.sql("""
                SELECT t.id FROM trigger t JOIN trigger_account a ON a.trigger_id = t.id AND a.system_id = t.system_id
                 WHERE t.company_id = ? AND t.system_id = ? AND a.account_id = ? AND t.deleted_at IS NULL
                   AND t.source = 'TRACKER'
                 ORDER BY t.name, t.id""")
            .params(companyId, systemId, accountId)
            .query(UUID.class)
            .list()
            .stream()
            .map(id -> store.find(companyId, id).orElseThrow())
            .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TriggerInfo> lock(UUID companyId, UUID triggerId) {
        return store.lock(companyId, triggerId);
    }

    // ---- TriggerAudience ---------------------------------------------------------------------------------------------

    @Override
    public Optional<TriggerView> trigger(UUID companyId, UUID triggerId) {
        return store.find(companyId, triggerId).map(trigger -> new TriggerView(trigger.id(), trigger.companyId(),
                trigger.name(), trigger.agentId(), trigger.authorPrincipalId(), trigger.teamGroupId(),
                trigger.instruction(), trigger.pausedReason() != null ? trigger.pausedReason().name() : null,
                !trigger.live()));
    }

    @Override
    public boolean sees(CompanyAccess access, UUID userId, UUID triggerId) {
        Optional<TriggerInfo> trigger = store.find(access.companyId(), triggerId);
        if (trigger.isEmpty()) {
            return false;
        }
        return admin(access) || acts(access.companyId(), userId, trigger.get());
    }

    @Override
    public Seen seenBy(CompanyAccess access, UUID userId) {
        if (admin(access)) {
            return new Seen(true, Set.of());
        }
        UUID principalId = principals.ofMember(access.companyId(), userId).map(PrincipalInfo::id).orElse(null);
        if (principalId == null) {
            return Seen.NONE;
        }
        return new Seen(false, store.seenBy(access.companyId(), principalId, groups.groupsOf(access.companyId(),
                userId)));
    }

    @Override
    public boolean acts(UUID companyId, UUID userId, UUID triggerId) {
        return store.find(companyId, triggerId).map(trigger -> acts(companyId, userId, trigger)).orElse(false);
    }

    private boolean acts(UUID companyId, UUID userId, TriggerInfo trigger) {
        UUID principalId = principals.ofMember(companyId, userId).map(PrincipalInfo::id).orElse(null);
        if (principalId == null) {
            return false;
        }
        return trigger.authorPrincipalId().equals(principalId)
                || trigger.teamGroupId() != null && groups.groupsOf(companyId, userId).contains(trigger.teamGroupId());
    }

    static boolean admin(CompanyAccess access) {
        return access.viaInstanceAdmin() || access.role() == CompanyRole.OWNER || access.role() == CompanyRole.ADMIN;
    }

    // ---- TriggerTurns ------------------------------------------------------------------------------------------------

    @Override
    public boolean waiting(UUID companyId, UUID conversationId) {
        return store.firingsWaiting(companyId, conversationId);
    }

    @Override
    public void taken(UUID companyId, UUID conversationId, UUID runId) {
        store.takeFirings(companyId, conversationId, runId);
    }

    @Override
    public Optional<String> request(UUID companyId, UUID runId) {
        List<TriggerStore.Taken> taken = store.taken(companyId, runId);
        if (taken.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder request = new StringBuilder();
        for (TriggerStore.Taken firing : taken) {
            if (!request.isEmpty()) {
                request.append("\n\n");
            }
            request.append(firing.request() != null && !firing.request().isBlank() ? firing.request().strip()
                    : describe(companyId, firing));
        }
        return Optional.of(request.toString());
    }

    @Override
    public Optional<String> subject(UUID companyId, UUID conversationId, UUID runId) {
        return store.taken(companyId, runId).stream()
            .map(TriggerStore.Taken::subjectSummary)
            .filter(summary -> summary != null && !summary.isBlank())
            .findFirst();
    }

    /** A firing in words when part b recorded no request for it. */
    private String describe(UUID companyId, TriggerStore.Taken firing) {
        String by = firing.manualBy() != null ? " (run now by " + userName(companyId, firing.manualBy()) + ")" : "";
        return switch (firing.event()) {
            case SCHEDULE -> "The schedule fired" + (firing.dueAt() != null ? " for "
                    + TIME.format(firing.dueAt().truncatedTo(ChronoUnit.SECONDS)) : "") + by + ".";
            default -> {
                String what = firing.event().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
                yield "The trigger fired on " + (firing.subjectKey() != null ? firing.subjectKey() : "its subject")
                        + ": " + what + (firing.value() != null ? " " + firing.value() : "")
                        + (firing.changedAt() != null ? " at " + TIME.format(firing.changedAt()
                            .truncatedTo(ChronoUnit.SECONDS)) : "") + by + ".";
            }
        };
    }

    private String userName(UUID companyId, UUID userId) {
        return principals.ofMember(companyId, userId).map(PrincipalInfo::name).filter(name -> name != null)
            .orElse("a member");
    }

    /** When the instant is, for texts. */
    static String at(@Nullable Instant instant) {
        return instant == null ? "" : TIME.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }
}
