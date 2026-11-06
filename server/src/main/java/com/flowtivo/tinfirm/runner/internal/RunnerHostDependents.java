package com.flowtivo.tinfirm.runner.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.principal.PrincipalDependents;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * A member's personal runner hosts follow their memberships (ADR-0092). A member who joins a company brings their
 * machines along: each gets a presence there, audited {@code runner_host.registered} with the cause
 * {@code principal.joined}, in the joining transaction. A member who leaves takes their presences along (the platform
 * redesign, Part 8; the package 2 contract, M4): each is removed in the leaving transaction, after the person's
 * conversations ended, audited as {@code runner_host.removed} with the cause {@code principal.left}; the machine, its
 * token and its connection stay for the person's other companies. A service identity owns no host.
 */
@Component
class RunnerHostDependents implements PrincipalDependents {

    private final RunnerHostStore store;

    private final RunnerHostService hosts;

    private final TimeSource time;

    RunnerHostDependents(RunnerHostStore store, RunnerHostService hosts, TimeSource time) {
        this.store = store;
        this.hosts = hosts;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void principalDeleting(UUID companyId, UUID principalId) {
        // Only a member's principal owns a runner host (runner_host_owner_fk).
    }

    /** The person's machines are present in the company from now on, under their new principal. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberJoined(UUID companyId, UUID principalId, UUID userId) {
        for (RunnerHostStore.Machine machine : store.machinesOf(userId)) {
            hosts.addPresence(companyId, principalId, machine, "principal.joined", time.now());
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberLeaving(UUID companyId, UUID principalId, UUID userId) {
        // The presences go once the person's conversations are locked and ended (memberLeft).
    }

    /**
     * After {@code PrincipalLeft}, which locked the person's conversations: the dispatcher locks a conversation, its
     * run and then the hosts that may take it, and a presence's {@code DELETE} writes the conversations and runs that
     * name it (their foreign keys' {@code ON DELETE SET NULL}), so the hosts come after the conversations (CONVENTIONS
     * §10).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberLeft(UUID companyId, UUID principalId, UUID userId) {
        for (RunnerHostStore.HostRow host : store.lockPersonalHosts(companyId, principalId)) {
            hosts.removePresence(companyId, host, "principal.left");
        }
    }
}
