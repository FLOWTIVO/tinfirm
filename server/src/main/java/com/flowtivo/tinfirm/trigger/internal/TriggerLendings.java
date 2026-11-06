package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.trigger.TriggerEvents;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSource;

/**
 * A trigger follows its service account's credential (ADR-0072). When an admin lends the service account another
 * credential on a system in place of the one it borrowed, in the lending's transaction, each live trigger whose row
 * acted through it names the new one with its identity ({@code trigger.accounts_changed}, {@code why}
 * {@code lending.replaced}), its poll follows after the commit ({@link PollKeeper}), and its author is told in their
 * inbox as of an admin's change on their behalf, unless they lent it themselves. Its open conversations bind the new
 * credential at their next check (the conversation module's bindings). A trigger keeps its row, and its gate pauses it
 * as for a withdrawn credential, when the new one is a chat system's bot, which acts for no trigger (C9), or when its
 * tracker's source row would name a credential without an identity (T2). A trigger open to anyone that follows the new
 * credential exposes it: whoever lent it is told the first time ({@link TriggerExposures}, D2).
 */
@Component
class TriggerLendings {

    private final TriggerStore store;

    private final TriggerService triggers;

    private final Accounts accounts;

    private final Principals principals;

    private final Systems systems;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final TriggerExposures exposures;

    TriggerLendings(TriggerStore store, TriggerService triggers, Accounts accounts, Principals principals,
            Systems systems, ApplicationEventPublisher events, TimeSource time, TriggerExposures exposures) {
        this.exposures = exposures;
        this.store = store;
        this.triggers = triggers;
        this.accounts = accounts;
        this.principals = principals;
        this.systems = systems;
        this.events = events;
        this.time = time;
    }

    @EventListener
    void lendingReplaced(AccountEvents.LendingReplaced event) {
        UUID companyId = event.companyId();
        List<UUID> triggerIds = store.liveWithLending(companyId, event.systemId(), event.servicePrincipalId(),
                event.replacedAccountId());
        // A chat system's bot acts for no trigger (C9): those that acted through the service account pause.
        if (triggerIds.isEmpty() || accounts.bot(companyId, event.accountId())) {
            return;
        }
        AccountInfo credential = accounts.find(companyId, event.accountId()).orElseThrow();
        String what = what(event, credential);
        Actor actor = CurrentActor.actor();
        UUID savedBy = actor.type() == ActorType.USER ? actor.id() : null;
        UUID admin = savedBy != null ? principals.ofMember(companyId, savedBy).map(PrincipalInfo::id).orElse(null)
                : null;
        Instant now = time.now();
        for (UUID triggerId : triggerIds) {
            TriggerInfo trigger = store.lock(companyId, triggerId).filter(TriggerInfo::live).orElse(null);
            if (trigger == null || (trigger.source() == TriggerSource.TRACKER
                    && event.systemId().equals(trigger.systemId()) && credential.externalId() == null)) {
                continue;
            }
            if (!store.followLending(companyId, triggerId, event.systemId(), event.servicePrincipalId(),
                    event.replacedAccountId(), credential.id(), credential.externalId(), savedBy, now)) {
                continue;
            }
            triggers.auditAccounts(trigger, "lending.replaced");
            // D2: the credential it follows is exposed to its open source now; whoever lent it is told once.
            exposures.record(trigger);
            if (!trigger.authorPrincipalId().equals(admin)) {
                store.adminChanged(companyId, triggerId, admin, what, now);
            }
            events.publishEvent(new TriggerEvents.TriggerAccountsChanged(companyId, triggerId));
        }
    }

    /** The author's note: "svc-dev now borrows the credential Second on Jira in place of First: …". */
    private String what(AccountEvents.LendingReplaced event, AccountInfo credential) {
        UUID companyId = event.companyId();
        String service = principals.find(companyId, event.servicePrincipalId()).map(PrincipalInfo::name)
            .orElse("Its service account");
        String system = systems.find(companyId, event.systemId()).map(SystemInfo::name).orElse("the system");
        @Nullable String replaced = accounts.find(companyId, event.replacedAccountId()).map(AccountInfo::name)
            .orElse(null);
        return service + " now borrows the credential " + credential.name() + " on " + system
                + (replaced != null ? " in place of " + replaced : "") + ", and the trigger acts with it";
    }
}
