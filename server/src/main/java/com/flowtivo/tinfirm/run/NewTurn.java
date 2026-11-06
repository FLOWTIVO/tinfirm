package com.flowtivo.tinfirm.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.runner.RuntimeKind;

/**
 * A turn of a conversation, as the conversation module starts it ({@link Turns#start}).
 *
 * @param conversationId    the conversation: {@code workItem.id} and {@code job.id} on the wire, so each conversation
 *                          has its own session home on a host, and the host's one run per work item is one per
 *                          conversation (I12)
 * @param conversationKey   the conversation's key, 1 to 93 characters: the branch {@code agent/{key}} and
 *                          {@code workItem.key}
 * @param runtime           the conversation's runtime, fixed at its start (open point 3)
 * @param number            the turn's number in its conversation, from 1
 * @param actingPrincipalId the person the run acts for; a trigger's conversation's author
 * @param payerPrincipalId  who pays (I10): the person, as the conversation fixed it; a trigger's conversation's payer
 * @param modelAccess       the payer's budget, or a subscription on the payer's personal host, as the conversation
 *                          fixed it
 * @param createdBy         the user whose act started the turn, or null for the system
 * @param triggerId         the trigger of a trigger's conversation (the package 4 contract, §3.7): its runs act for
 *                          the conversation's author and are paid by the conversation's payer; null for a personal
 *                          conversation, whose runs act for and are paid by its person
 * @param scheduled         an automatic retry's (ADR-0073): the run waits {@code RETRY_SCHEDULED} until its time; null
 *                          for a turn that may run at once
 */
public record NewTurn(UUID companyId, UUID conversationId, String conversationKey, UUID agentId, RuntimeKind runtime,
        TurnReason reason, int number, UUID actingPrincipalId, UUID payerPrincipalId, ModelAccessChoice modelAccess,
        @Nullable UUID createdBy, @Nullable UUID triggerId, @Nullable Scheduled scheduled) {

    /** The longest key: the branch {@code agent/{key}} fits a branch name of 100 characters. */
    public static final int MAX_KEY_LENGTH = 93;

    public NewTurn {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(conversationKey, "conversationKey");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actingPrincipalId, "actingPrincipalId");
        Objects.requireNonNull(payerPrincipalId, "payerPrincipalId");
        Objects.requireNonNull(modelAccess, "modelAccess");
        if (conversationKey.isBlank() || conversationKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("A conversation key has 1 to " + MAX_KEY_LENGTH + " characters");
        }
        if (number < 1) {
            throw new IllegalArgumentException("A turn's number starts at 1");
        }
        if (triggerId == null && !payerPrincipalId.equals(actingPrincipalId)) {
            throw new IllegalArgumentException("A personal conversation's turn is paid by its person");
        }
        if (scheduled != null && (reason != TurnReason.RETRY || createdBy != null)) {
            throw new IllegalArgumentException("Only the system's retry of a turn waits until a time");
        }
    }

    /** A turn that may run at once. */
    public NewTurn(UUID companyId, UUID conversationId, String conversationKey, UUID agentId, RuntimeKind runtime,
            TurnReason reason, int number, UUID actingPrincipalId, UUID payerPrincipalId,
            ModelAccessChoice modelAccess, @Nullable UUID createdBy, @Nullable UUID triggerId) {
        this(companyId, conversationId, conversationKey, agentId, runtime, reason, number, actingPrincipalId,
                payerPrincipalId, modelAccess, createdBy, triggerId, null);
    }

    /** A turn of a personal conversation. */
    public NewTurn(UUID companyId, UUID conversationId, String conversationKey, UUID agentId, RuntimeKind runtime,
            TurnReason reason, int number, UUID actingPrincipalId, UUID payerPrincipalId,
            ModelAccessChoice modelAccess, @Nullable UUID createdBy) {
        this(companyId, conversationId, conversationKey, agentId, runtime, reason, number, actingPrincipalId,
                payerPrincipalId, modelAccess, createdBy, null);
    }

    /**
     * When an automatic retry may run, and which of how many in a row it is (ADR-0073).
     *
     * @param at       the earliest time it is dispatched
     * @param attempt  its number in the row, from 1
     * @param attempts how many there are at most
     */
    public record Scheduled(Instant at, int attempt, int attempts) {

        public Scheduled {
            Objects.requireNonNull(at, "at");
            if (attempt < 1 || attempt > attempts) {
                throw new IllegalArgumentException("An automatic retry is one of 1 to " + attempts);
            }
        }
    }
}
