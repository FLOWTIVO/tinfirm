package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;

/**
 * What part b calls to put a firing into a conversation (the package 4 contract, §5.6 step 4 and §5.7), implemented by
 * part a. In the firing's transaction (the subject's, or a schedule's), which holds the trigger's row
 * ({@link TriggerLocks}), after {@code TriggerGate.check} passed, the agent was found active, the rate and the chain
 * applied, and the firing was inserted into {@code trigger_firing} with its {@code request} (and a {@code STARTED}
 * one's {@code subject_summary}), {@code conversation_id} null for a start and the open conversation for a
 * continuation, {@code run_id} null: the turn that takes it sets it ({@code TriggerTurns.taken}).
 */
public interface FiringConversations {

    /**
     * {@code STARTED}: a new conversation of the trigger with its {@code FIRST} turn (§5.7), built from the trigger at
     * this moment: its agent, author and payer, its settings' runtime and model access ({@code SettingsOwner.Trigger}),
     * one {@code FIXED} binding per row of its table with an account, the workspace (the settings' default
     * repositories, else the agent's, leaving out those of systems without a row), the chain depth. The firing (when
     * named) gets the conversation and the first turn's run. Answers the conversation and its run.
     *
     * @param place     the issue or change request ({@code TRACKER_ISSUE}), or the schedule's due time ({@code SCHEDULE})
     * @param firingId  the {@code trigger_firing} row it starts for, or null
     * @param startedBy the user of a run now, else null (SYSTEM)
     */
    TriggerConversations.Started start(UUID companyId, UUID triggerId, Place place, int chainDepth,
            @Nullable UUID firingId, @Nullable UUID startedBy);

    /**
     * {@code CONTINUED}: the firing (already inserted with the conversation) waits for a turn of the trigger's open
     * conversation; an {@code EVENT} turn starts now when no run is active and no decision is open, else the next turn
     * takes it (and a run's end starts one for it). Answers the run started now, if any.
     */
    Optional<UUID> continueWith(UUID companyId, UUID conversationId);

    /** Where a firing's conversation is held. */
    sealed interface Place {

        /** An issue or change request: {@code TRACKER_ISSUE}. */
        record Subject(TriggerConversations.TrackerSubject subject) implements Place {
        }

        /** A schedule's firing: {@code SCHEDULE}, one conversation per firing. */
        record Schedule(Instant dueAt) implements Place {
        }

        /**
         * A package 5 place: a channel's chat thread ({@code CHAT_THREAD}) or a webhook's subject ({@code WEBHOOK}),
         * with its reference and title.
         */
        record Held(ConversationInfo.Place place, TriggerConversations.HeldPlace held) implements Place {
        }
    }
}
