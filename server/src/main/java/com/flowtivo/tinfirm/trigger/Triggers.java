package com.flowtivo.tinfirm.trigger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Triggers for the other parts of the module and for later packages (the package 4 contract, §3.1, §3.2). Runs in the
 * caller's transaction and tenant context: row-level security limits every lookup to the company of that context.
 * Who may see a trigger is the caller's check ({@code TriggerAudience}, T15).
 */
public interface Triggers {

    /** The trigger, live or deleted. */
    Optional<TriggerInfo> find(UUID companyId, UUID triggerId);

    /** Its account table, by system. */
    List<TriggerAccount> accounts(UUID companyId, UUID triggerId);

    /**
     * The live tracker triggers whose source row names the account: those a poll of that account serves (§5.4), in the
     * order of their names, active or not.
     */
    List<TriggerInfo> onSourceAccount(UUID companyId, UUID systemId, UUID accountId);

    /** The live paused triggers, the newest pause first (the kill switches' page, the package 6 contract, §5.4). */
    List<TriggerInfo> paused(UUID companyId);

    /** The live triggers whose source is the system or whose table has a row on it, in the order of their ids. */
    List<TriggerInfo> onSystem(UUID companyId, UUID systemId);

    /** The live triggers the principal authors, in the order of their ids. */
    List<TriggerInfo> ofAuthor(UUID companyId, UUID authorPrincipalId);
}
