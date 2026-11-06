package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.ToolEffect;

/**
 * The audit of a conversation's tools (the package 3 contract, §8), by the agent: {@code tool.called} for each call
 * that reached its system (never its arguments or result), {@code tool.denied} once per run and tool for a call the
 * agent, the settings or the binding refused ({@code conversation_tool_denial}, V009), so that a prompt-injected agent
 * calling a denied tool again and again is one row of the hash chain; each attempt still reaches the agent as the
 * call's answer. In the caller's transaction.
 */
@Component
class ToolAudit {

    static final String RUN = "run";

    private final AuditLog auditLog;

    private final JdbcClient jdbc;

    private final TimeSource time;

    ToolAudit(AuditLog auditLog, JdbcClient jdbc, TimeSource time) {
        this.auditLog = auditLog;
        this.jdbc = jdbc;
        this.time = time;
    }

    /**
     * A call that ran on its system, or would have (a refused credential, a failure inside Tinfirm).
     *
     * @param mode       {@code ALLOW}; {@code ASK} for an approved call
     * @param decisionId the approval of an approved call; null otherwise
     * @param bindingId  the binding of a parked call that ran once it was bound; null otherwise
     * @param code       the code of a call that did not answer with a result (a refusal of the system module, or
     *                   {@code RECONNECT}); the system's failure for a result with {@code isError}
     */
    void called(Call call, String mode, @Nullable UUID decisionId, @Nullable UUID bindingId, boolean ok,
            @Nullable String code) {
        AuditRecord.Builder record = AuditRecord.company(call.companyId(), AuditAction.TOOL_CALLED,
                Actor.agent(call.agentId()));
        if (call.runId() != null) {
            record.target(RUN, call.runId());
        }
        else if (decisionId != null) {
            record.target(DecisionOps.TARGET, decisionId);
        }
        auditLog.record(record.detail("conversationId", call.conversationId().toString())
            .detail("runId", call.runId() != null ? call.runId().toString() : null)
            .detail("systemId", call.systemId().toString())
            .detail("tool", call.tool())
            .detail("effect", call.effect().name())
            .detail("mode", mode)
            .detail("accountId", call.accountId() != null ? call.accountId().toString() : null)
            .detail("holderPrincipalId", call.holderPrincipalId() != null ? call.holderPrincipalId().toString() : null)
            .detail("grantId", call.grantId() != null ? call.grantId().toString() : null)
            .detail("decisionId", decisionId != null ? decisionId.toString() : null)
            .detail("bindingId", bindingId != null ? bindingId.toString() : null)
            .detail("ok", ok)
            .detail("code", code)
            .build());
    }

    /**
     * A call refused at a step of §5.4: audited the first time the run calls the tool so, and only then.
     *
     * @return whether it was audited now
     */
    boolean denied(UUID companyId, UUID conversationId, UUID runId, UUID agentId, UUID systemId, String tool,
            ToolRules.Step step) {
        int inserted = jdbc.sql("""
                INSERT INTO conversation_tool_denial (company_id, run_id, system_id, tool, step, created_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""")
            .params(companyId, runId, systemId, tool, step.name(), DecisionStore.ts(time.now()))
            .update();
        if (inserted == 0) {
            return false;
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.TOOL_DENIED, Actor.agent(agentId))
            .target(RUN, runId)
            .detail("conversationId", conversationId.toString())
            .detail("systemId", systemId.toString())
            .detail("tool", tool)
            .detail("step", step.name())
            .build());
        return true;
    }

    /**
     * What {@code tool.called} records of a call: whose conversation and run, which tool, and the account it acted
     * with.
     *
     * @param runId the run that called; null for none (an approved call whose run is unknown)
     */
    record Call(UUID companyId, UUID conversationId, @Nullable UUID runId, UUID agentId, UUID systemId, String tool,
            ToolEffect effect, @Nullable UUID accountId, @Nullable UUID holderPrincipalId, @Nullable UUID grantId) {
    }
}
