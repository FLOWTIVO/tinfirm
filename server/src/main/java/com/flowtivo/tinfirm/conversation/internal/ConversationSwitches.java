package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.CompanyRunsDependents;
import com.flowtivo.tinfirm.conversation.PersonConversations;
import com.flowtivo.tinfirm.run.RunCancellations;
import com.flowtivo.tinfirm.system.SystemSwitchDependents;

/**
 * The conversation module's part of the kill switches and of a member's departure (the package 6 contract, §5.4,
 * §5.5): a system paused with {@code stopRunning} cancels the running turns of the open conversations bound to it
 * (its bindings keep their state: for the conversations the system is one without an account); the company's stop
 * refuses every parked call that has not started (the security review of 2026-10-27, M6: {@link ParkedCalls#stopped});
 * and a person's conversations by counts.
 */
@Component
class ConversationSwitches implements SystemSwitchDependents, CompanyRunsDependents, PersonConversations {

    private final JdbcClient jdbc;

    private final RunCancellations cancellations;

    private final ObjectProvider<ParkedCalls> parked;

    ConversationSwitches(JdbcClient jdbc, RunCancellations cancellations, ObjectProvider<ParkedCalls> parked) {
        this.jdbc = jdbc;
        this.cancellations = cancellations;
        this.parked = parked;
    }

    /**
     * The company's runs are being stopped (M6), in the stop's transaction: the parked calls that have not started are
     * refused {@code COMPANY_STOPPED}, so that nothing an agent asked for before the stop runs after it is lifted.
     * Answers no runs: the run module cancels them ({@code RunSwitches}).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> runsStopping(UUID companyId) {
        parked.getObject().stopped(companyId);
        return List.of();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Pausing systemPausing(UUID companyId, UUID systemId, boolean stopRunning) {
        if (!stopRunning) {
            return Pausing.NONE;
        }
        List<UUID> conversations = jdbc.sql("""
                SELECT DISTINCT b.conversation_id
                  FROM conversation_binding b
                  JOIN conversation c ON c.id = b.conversation_id AND c.company_id = b.company_id
                 WHERE b.company_id = ? AND b.system_id = ? AND b.state <> 'GONE' AND c.state = 'OPEN'""")
            .params(companyId, systemId)
            .query(UUID.class)
            .list();
        return new Pausing(cancellations.cancelRunningOfConversations(companyId, conversations), 0);
    }

    @Override
    @Transactional(readOnly = true)
    public int openOf(UUID companyId, UUID personPrincipalId) {
        return count(companyId, personPrincipalId, "OPEN");
    }

    @Override
    @Transactional(readOnly = true)
    public int endedOf(UUID companyId, UUID personPrincipalId) {
        return count(companyId, personPrincipalId, "ENDED");
    }

    private int count(UUID companyId, UUID personPrincipalId, String state) {
        return jdbc.sql("SELECT count(*) FROM conversation WHERE company_id = ? AND person_principal_id = ?"
                + " AND state = ?")
            .params(companyId, personPrincipalId, state)
            .query(Integer.class)
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenChangeRequest> openChangeRequestsOf(UUID companyId, UUID holderPrincipalId) {
        return jdbc.sql("""
                SELECT r.conversation_id, r.url, r.state
                  FROM run_change_request r
                  JOIN conversation_binding b ON b.id = r.binding_id AND b.company_id = r.company_id
                 WHERE r.company_id = ? AND b.holder_principal_id = ? AND b.how = 'OWN'
                   AND r.state IN ('OPEN', 'DRAFT')
                 ORDER BY r.created_at DESC, r.output_id
                 LIMIT 100""")
            .params(companyId, holderPrincipalId)
            .query((rs, row) -> new OpenChangeRequest(rs.getObject("conversation_id", UUID.class),
                    rs.getString("url"), rs.getString("state")))
            .list();
    }
}
