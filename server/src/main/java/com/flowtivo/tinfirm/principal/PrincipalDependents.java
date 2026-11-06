package com.flowtivo.tinfirm.principal;

import java.util.UUID;

/**
 * Port: what other modules hang from a principal (the package 1 contract, §3.3, §3.6 and I8), implemented by each of
 * them: the account module (accounts, the secrets a member owns) and the system module (personal MCP servers); the
 * principal module does not depend on them. Every implementation is called in the transaction that deletes the service
 * identity or ends the membership, in the company's tenant context, as the actor of that change, before the principal
 * changes, so that each module removes, audits and announces its own rows.
 */
public interface PrincipalDependents {

    /**
     * The service identity is about to be deleted: its accounts go, with the company secrets they manage and the
     * grants of them.
     */
    void principalDeleting(UUID companyId, UUID principalId);

    /**
     * The member is about to leave the company (I8): their accounts, the secrets they own in the company and their
     * personal systems go.
     */
    void memberLeaving(UUID companyId, UUID principalId, UUID userId);

    /**
     * The member left: called in the same transaction after {@link PrincipalEvents.PrincipalLeft} was announced, so
     * after the conversation module locked and ended the person's conversations, for rows that come after the
     * conversations in the lock order (CONVENTIONS §10): the runner module's personal hosts, which the dispatcher locks
     * after a conversation and its run, and whose deletion writes the conversations and runs that name them.
     */
    default void memberLeft(UUID companyId, UUID principalId, UUID userId) {
    }

    /**
     * The member joined the company and has a principal there: called in the same transaction after
     * {@link PrincipalEvents.PrincipalCreated} was announced, in the company's tenant context. The runner module gives
     * each personal runner host of the user a presence in the company (ADR-0092).
     */
    default void memberJoined(UUID companyId, UUID principalId, UUID userId) {
    }
}
