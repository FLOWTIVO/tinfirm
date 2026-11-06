package com.flowtivo.tinfirm.trigger.internal;

import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.trigger.TriggerEvents;

/**
 * The new author of a reassigned trigger is told (the security review of 2026-10-27, H2; the owner's decision Q2). In
 * the reassignment's transaction, the trigger keeps the admin's note on it ({@code admin_changed_*}, which the
 * reassignment cleared of the former author's), and {@link TriggerInboxImpl} answers the new author's inbox with the
 * {@code TRIGGER} item {@code TRIGGER_REASSIGNED_TO_YOU} while it is paused {@code TO_ACCEPT}: they accept its accounts,
 * its payer and its open events (the trigger's accept), which resumes it, and only they do.
 */
@Component
class TriggerReassignments {

    /** The note the new author reads. */
    static final String NOTE = "reassigned it to you: accept its accounts, its payer and its open events to resume it";

    private final TriggerStore store;

    private final Principals principals;

    private final TimeSource time;

    TriggerReassignments(TriggerStore store, Principals principals, TimeSource time) {
        this.store = store;
        this.principals = principals;
        this.time = time;
    }

    @EventListener
    void reassigned(TriggerEvents.TriggerReassigned event) {
        Actor actor = CurrentActor.actor();
        UUID admin = actor.type() == ActorType.USER
                ? principals.ofMember(event.companyId(), actor.id()).map(PrincipalInfo::id).orElse(null) : null;
        store.adminChanged(event.companyId(), event.triggerId(), admin, NOTE, time.now());
    }
}
