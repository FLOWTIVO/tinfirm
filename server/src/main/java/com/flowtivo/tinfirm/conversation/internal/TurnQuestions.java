package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.runner.RunnerMessages;

/**
 * The questions a turn ends on (the package 3 contract, §5.8; ADR-0028, ADR-0036): a {@code BLOCKED} result's open
 * questions become questions of the conversation's person ({@link TurnEndHook}), at most as many as the conversation
 * may hold open. Their card is the turn's own message, which part 1 posted with the result and which lists them
 * (ADR-0036: they are asks of that one message), so none gets a card of its own.
 */
@Component
class TurnQuestions implements TurnEndHook {

    private final DecisionStore store;

    private final DecisionOps decisions;

    TurnQuestions(DecisionStore store, DecisionOps decisions) {
        this.store = store;
        this.decisions = decisions;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void turnEnded(ConversationInfo conversation, TurnEnded.TurnEnd end) {
        if (!conversation.open() || end.result() == null
                || end.result().outcome() != RunnerMessages.ResultOutcome.BLOCKED) {
            return;
        }
        List<String> questions = end.result().openQuestions();
        if (questions.isEmpty()) {
            return;
        }
        store.lockConversation(conversation.id());
        int room = DecisionOps.MAX_OPEN - store.countOpen(conversation.id(), DecisionStore.Kind.QUESTION);
        for (String question : questions) {
            String text = question != null ? question.strip() : "";
            if (text.isEmpty() || room <= 0) {
                continue;
            }
            decisions.question(conversation, end.runId(), null, DecisionOps.cut(text,
                    DecisionOps.MAX_QUESTION_LENGTH), List.of(), false);
            room--;
        }
    }
}
