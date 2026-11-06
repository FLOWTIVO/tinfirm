package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;


/**
 * What part 1 reads of {@code decision} (V005): the open decisions of a conversation for what it waits on, the
 * questions a turn raised (ADR-0036), and the cancellation of the open ones when part 2 provides no
 * {@link OpenDecisions}. Part 2 writes decisions and shows them.
 */
@Repository
class DecisionReads {

    private final JdbcClient jdbc;

    DecisionReads(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An open decision: its kind and since when. */
    /**
     * An open decision, as what the conversation waits on names it.
     *
     * @param subjectType     an approval's subject's type ({@code RESULT}: a result's review); null for a question
     * @param approverGroupId the group whose members decide it (a trigger's team); null for one person's
     */
    record Open(UUID id, String kind, Instant createdAt, @Nullable String subjectType, @Nullable UUID approverGroupId) {

        /** What the conversation waits on, in its {@code waitingReason}: QUESTION, APPROVAL, or REVIEW for a result. */
        String waitingReason() {
            return "RESULT".equals(subjectType) ? "REVIEW" : kind;
        }
    }

    /** The conversation's open decisions, oldest first. */
    List<Open> open(UUID companyId, UUID conversationId) {
        return jdbc.sql("""
                SELECT id, kind, created_at, subject ->> 'type' AS subject_type, approver_group_id FROM decision
                 WHERE company_id = ? AND conversation_id = ? AND status = 'OPEN' ORDER BY created_at, id""")
            .params(companyId, conversationId)
            .query((rs, rowNum) -> new Open(rs.getObject("id", UUID.class), rs.getString("kind"),
                    ConversationStore.instant(rs, "created_at"), rs.getString("subject_type"),
                    rs.getObject("approver_group_id", UUID.class)))
            .list();
    }

    /** Whether the run raised a question that is still open: the turn ended on its own questions (ADR-0036). */
    boolean openQuestionsOfRun(UUID companyId, UUID runId) {
        return jdbc.sql("""
                SELECT count(*) FROM decision
                 WHERE company_id = ? AND run_id = ? AND kind = 'QUESTION' AND status = 'OPEN'""")
            .params(companyId, runId)
            .query(Long.class)
            .single() > 0;
    }

    /**
     * Whether a decision of the conversation was decided and no turn told it yet ({@code reported_run_id} null, V009):
     * one decided while a run was active, whose {@code DECIDED} turn could not start then (I11). An approved call that
     * has not run yet does not count: its result starts that turn once it is there ({@link ApprovedCalls}).
     */
    boolean decidedUntold(UUID companyId, UUID conversationId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM decision
                                WHERE company_id = ? AND conversation_id = ? AND status = 'DECIDED'
                                  AND reported_run_id IS NULL
                                  AND NOT coalesce(outcome = 'APPROVED' AND subject ->> 'type' = 'TOOL_CALL'
                                                   AND result IS NULL, false))""")
            .params(companyId, conversationId)
            .query(Boolean.class)
            .single();
    }

    /** Cancels the conversation's open decisions with the cause; the default of {@link OpenDecisions}. */
    int cancelOpen(UUID companyId, UUID conversationId, String cause) {
        return jdbc.sql("""
                UPDATE decision SET status = 'CANCELLED', cancel_cause = ?
                 WHERE company_id = ? AND conversation_id = ? AND status = 'OPEN'""")
            .params(cause, companyId, conversationId)
            .update();
    }
}
