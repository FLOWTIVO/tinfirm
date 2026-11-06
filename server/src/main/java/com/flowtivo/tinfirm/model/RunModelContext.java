package com.flowtivo.tinfirm.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.flowtivo.tinfirm.runner.RuntimeKind;

/**
 * What the model-access rules need to know about a run (the package 2 contract, §5): package 3 builds it from the
 * conversation and its turn before it dispatches.
 *
 * @param payingPrincipalId       the principal whose model access pays (M2): the person, the trigger's author or a
 *                                service identity
 * @param access                  the model access the run named: the principal's budget, or a subscription on their
 *                                personal host
 * @param runtime                 the agent's runtime: a host must serve it
 * @param workspaceAccountHolders the principals whose accounts the run's workspaces use (each repository's git
 *                                account's holder), or a credential's id for a system's credential a grant lends, which
 *                                is no one's (ADR-0072); empty for a run without a workspace. A personal host takes the
 *                                run only when every one of them is its owner (M4)
 */
public record RunModelContext(UUID companyId, UUID payingPrincipalId, ModelAccessChoice access, RuntimeKind runtime,
        Set<UUID> workspaceAccountHolders) {

    public RunModelContext {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(payingPrincipalId, "payingPrincipalId");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(runtime, "runtime");
        workspaceAccountHolders = Set.copyOf(workspaceAccountHolders);
    }

    /**
     * Whether every workspace account of the run is the principal's own (or the run has no workspace): the
     * personal-host rule, {@link Hosts#personalHostMayRun}.
     */
    public boolean workspaceAllOf(UUID principalId) {
        return Hosts.personalHostMayRun(principalId, workspaceAccountHolders);
    }
}
