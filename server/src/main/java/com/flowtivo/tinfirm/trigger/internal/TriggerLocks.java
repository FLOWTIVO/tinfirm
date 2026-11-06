package com.flowtivo.tinfirm.trigger.internal;

import java.util.Optional;
import java.util.UUID;

import com.flowtivo.tinfirm.trigger.TriggerInfo;

/**
 * The trigger module's side of the global lock order (CONVENTIONS §10, extended by package 4), for parts a, b and c:
 * <ol>
 * <li>a poll's rows ({@code trigger_poll}, then the subject's {@code trigger_subject}), when the transaction is a
 * poll's;</li>
 * <li>a trigger's row, {@code FOR NO KEY UPDATE} (so that the foreign keys' checks of a new conversation, firing or run
 * that names it never wait on it); several triggers in the order of their ids;</li>
 * <li>then its conversations in the order of their ids ({@code TriggerConversations} and {@code Turns} lock them), then
 * their runs, bindings, decisions and outbox rows;</li>
 * <li>the company's audit chain head last: the transaction's audit records lock it when it commits.</li>
 * </ol>
 * Implemented by part a.
 */
public interface TriggerLocks {

    /** The trigger, locked {@code FOR NO KEY UPDATE} in the caller's transaction; empty in another company. */
    Optional<TriggerInfo> lock(UUID companyId, UUID triggerId);
}
