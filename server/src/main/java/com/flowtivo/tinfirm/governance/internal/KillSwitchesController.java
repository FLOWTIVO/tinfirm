package com.flowtivo.tinfirm.governance.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.GovernanceApi;
import com.flowtivo.tinfirm.shared.api.model.Departure;
import com.flowtivo.tinfirm.shared.api.model.DepartureSummaryList;
import com.flowtivo.tinfirm.shared.api.model.DepartureCloseRequest;
import com.flowtivo.tinfirm.shared.api.model.DeparturePreview;
import com.flowtivo.tinfirm.shared.api.model.KillSwitches;
import com.flowtivo.tinfirm.shared.api.model.MemberSuspension;
import com.flowtivo.tinfirm.shared.api.model.RunsLiftResult;
import com.flowtivo.tinfirm.shared.api.model.RunsStopRequest;
import com.flowtivo.tinfirm.shared.api.model.RunsStopResult;
import com.flowtivo.tinfirm.shared.api.model.SuspendRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemPauseRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemPauseResult;
import com.flowtivo.tinfirm.shared.api.model.SystemResumeRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemResumeResult;

@RestController
class KillSwitchesController implements GovernanceApi {

    private final KillSwitchesService switches;

    private final DeparturesService departures;

    KillSwitchesController(KillSwitchesService switches, DeparturesService departures) {
        this.switches = switches;
        this.departures = departures;
    }

    @Override
    public ResponseEntity<KillSwitches> getKillSwitches(UUID companyId) {
        return ResponseEntity.ok(switches.page(companyId));
    }

    @Override
    public ResponseEntity<RunsStopResult> stopRuns(UUID companyId, RunsStopRequest request) {
        return ResponseEntity.ok(switches.stop(companyId, request.getReason()));
    }

    @Override
    public ResponseEntity<RunsLiftResult> liftRunsStop(UUID companyId) {
        return ResponseEntity.ok(switches.lift(companyId));
    }

    @Override
    public ResponseEntity<SystemPauseResult> pauseSystem(UUID companyId, UUID systemId, SystemPauseRequest request) {
        return ResponseEntity.ok(switches.pause(companyId, systemId, request.getReason(),
                Boolean.TRUE.equals(request.getStopRunning())));
    }

    @Override
    public ResponseEntity<SystemResumeResult> resumeSystem(UUID companyId, UUID systemId,
            @Nullable SystemResumeRequest request) {
        boolean resumeTriggers = request == null || !Boolean.FALSE.equals(request.getResumeTriggers());
        return ResponseEntity.ok(switches.resume(companyId, systemId, resumeTriggers));
    }

    @Override
    public ResponseEntity<MemberSuspension> suspendMember(UUID companyId, UUID userId, SuspendRequest request) {
        return ResponseEntity.ok(switches.suspend(companyId, userId, request.getReason()));
    }

    @Override
    public ResponseEntity<MemberSuspension> resumeMember(UUID companyId, UUID userId) {
        return ResponseEntity.ok(switches.resumeMember(companyId, userId));
    }

    @Override
    public ResponseEntity<DeparturePreview> previewDeparture(UUID companyId, UUID userId) {
        return ResponseEntity.ok(departures.preview(companyId, userId));
    }

    @Override
    public ResponseEntity<DepartureSummaryList> listOpenDepartures(UUID companyId) {
        return ResponseEntity.ok(departures.open(companyId));
    }

    @Override
    public ResponseEntity<Departure> getDeparture(UUID companyId, UUID principalId) {
        return ResponseEntity.ok(departures.checklist(companyId, principalId));
    }

    @Override
    public ResponseEntity<Departure> closeDeparture(UUID companyId, UUID principalId,
            @Nullable DepartureCloseRequest request) {
        return ResponseEntity.ok(departures.close(companyId, principalId,
                request != null && Boolean.TRUE.equals(request.getForce())));
    }
}
