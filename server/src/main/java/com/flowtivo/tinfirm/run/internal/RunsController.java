package com.flowtivo.tinfirm.run.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.RunsApi;
import com.flowtivo.tinfirm.shared.api.model.Run;
import com.flowtivo.tinfirm.shared.api.model.RunEventPage;
import com.flowtivo.tinfirm.shared.api.model.RunEventType;
import com.flowtivo.tinfirm.shared.web.EventStreamSupport;

@RestController
class RunsController implements RunsApi {

    private final RunService runs;

    private final RunEventService events;

    RunsController(RunService runs, RunEventService events) {
        this.runs = runs;
        this.events = events;
    }

    @Override
    public ResponseEntity<Run> getRun(UUID companyId, UUID runId) {
        return ResponseEntity.ok(runs.get(companyId, runId));
    }

    @Override
    public ResponseEntity<Run> cancelRun(UUID companyId, UUID runId) {
        return ResponseEntity.ok(runs.cancel(companyId, runId));
    }

    @Override
    public ResponseEntity<RunEventPage> listRunEvents(UUID companyId, UUID runId, Long afterSeq,
            @Nullable List<RunEventType> types, Integer limit) {
        return ResponseEntity.ok(events.list(companyId, runId, afterSeq, types == null ? List.of() : types, limit));
    }

    /**
     * {@code text/event-stream}; replays after the later of {@code afterSeq} and a reconnecting client's
     * {@code Last-Event-ID}.
     */
    @Override
    public ResponseEntity<String> streamRunEvents(UUID companyId, UUID runId, Long afterSeq,
            @Nullable Long lastEventId) {
        long after = Math.max(afterSeq, lastEventId == null ? 0 : lastEventId);
        return EventStreamSupport.response(events.stream(companyId, runId, after));
    }
}
