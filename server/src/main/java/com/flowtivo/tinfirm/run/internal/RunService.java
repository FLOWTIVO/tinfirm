package com.flowtivo.tinfirm.run.internal;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.run.RunVisibility;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.Run;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Runs over HTTP (the package 3 contract, §6): get, and cancel. Runs are started by their conversations only, as
 * turns. A run follows its conversation ({@link com.flowtivo.tinfirm.run.RunVisibility}): a run the caller may not see
 * is {@code 404}, as another company's (I17).
 */
@Service
class RunService {

    private final RunStore runs;

    private final RunLocks locks;

    private final TurnStarter turns;

    private final CompanyAccessGuard companyAccess;

    private final RunPorts ports;

    private final RunPresenter presenter;

    RunService(RunStore runs, RunLocks locks, TurnStarter turns, CompanyAccessGuard companyAccess, RunPorts ports,
            RunPresenter presenter) {
        this.runs = runs;
        this.locks = locks;
        this.turns = turns;
        this.companyAccess = companyAccess;
        this.ports = ports;
        this.presenter = presenter;
    }

    @Transactional(readOnly = true)
    public Run get(UUID companyId, UUID runId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        return presenter.run(visible(access, runId, RunVisibility.Read.RUN));
    }

    /**
     * Idempotent. A QUEUED run is CANCELLED at once, and its turn with it; a RUNNING run gets a durable cancel command
     * and {@code run.cancel}, and ends when its host reports {@code run.finished}. Its person and OWNER, of a run they
     * see ({@code 403} for one they see but may not cancel).
     */
    @Transactional
    public Run cancel(UUID companyId, UUID runId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        // The run it answers is a read of it: on record before the locks below (CONVENTIONS §10).
        RunStore.RunRow found = visible(access, runId, RunVisibility.Read.RUN);
        if (!ports.visibility().mayCancel(access, CurrentActor.requireUser().id(), found.info())) {
            throw ApiException.forbidden("Only the conversation's person and OWNER cancel its runs");
        }
        // The conversation first, then the run (CONVENTIONS §10): a queued run's end tells its conversation.
        RunStore.RunRow run = locks.lock(runId).orElseThrow(RunService::notFound);
        turns.cancel(run);
        return presenter.run(runs.find(runId).orElseThrow(RunService::notFound));
    }

    /**
     * The run, when the caller may see it, for a read that shows it ({@link RunVisibility#read}: OWNER's read of
     * another person's run is audited); 404 otherwise. Before the caller's transaction locks anything.
     */
    RunStore.RunRow visible(CompanyAccess access, UUID runId, RunVisibility.Read what) {
        UUID userId = CurrentActor.requireUser().id();
        return runs.find(runId)
            .filter(run -> ports.visibility().read(access, userId, run.info(), what))
            .orElseThrow(RunService::notFound);
    }

    /**
     * The run, when the caller still may see it, for a stream's poll ({@link RunEventService}): checked again without
     * recording a read, since the stream's read was recorded when it opened; 404 otherwise.
     */
    RunStore.RunRow stillVisible(CompanyAccess access, UUID runId) {
        UUID userId = CurrentActor.requireUser().id();
        return runs.find(runId)
            .filter(run -> ports.visibility().maySee(access, userId, run.info()))
            .orElseThrow(RunService::notFound);
    }

    static ApiException notFound() {
        return ApiException.notFound("Run not found");
    }
}
