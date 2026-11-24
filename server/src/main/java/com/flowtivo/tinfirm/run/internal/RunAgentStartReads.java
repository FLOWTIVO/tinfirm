package com.flowtivo.tinfirm.run.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.run.RunAgentStart;

/**
 * {@link RunAgentStart} from the run's stored events and the relay's counts of its requests. The relay notes a
 * request as admitted, in a transaction of its own, before it forwards it; a request whose answer has not ended counts
 * as one too. A run that does not go through the relay (a subscription on a personal host) has only its events, and
 * they prove nothing: a host keeps a run's events in memory while its connection is down, so the agent may have
 * worked on the subscription without one of them stored. Such a run may always have started.
 */
@Component
class RunAgentStartReads implements RunAgentStart {

    private final RunStore runs;

    private final RunEventStore events;

    private final ModelRelay relay;

    RunAgentStartReads(RunStore runs, RunEventStore events, ModelRelay relay) {
        this.runs = runs;
        this.events = events;
        this.relay = relay;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean mayHaveStarted(UUID companyId, UUID runId) {
        RunStore.RunRow run = runs.find(runId).orElse(null);
        if (run == null || !run.budget() || events.pastPreparation(runId)) {
            return true;
        }
        return relay.usage(companyId, runId).requests() > 0 || relay.answersUnderWay(companyId, runId) > 0;
    }
}
