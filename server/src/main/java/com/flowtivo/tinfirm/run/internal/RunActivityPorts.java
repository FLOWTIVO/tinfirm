package com.flowtivo.tinfirm.run.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.model.RunModelTokens;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.QueueReasonCode;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.RunTokens;
import com.flowtivo.tinfirm.runner.RunnerActivity;
import com.flowtivo.tinfirm.shared.api.model.Run;

/**
 * The run module's reads ({@link RunReads}) and the ports of lower modules it implements: the runner module's
 * {@link RunnerActivity} (the slots runs hold on each host, and whether a host may be removed) and the model module's
 * {@link RunModelTokens} (the relay accepts a run's one-run token only while the run is RUNNING on the token's host
 * with the token's epoch, M7). Each query runs in the caller's transaction and tenant context.
 */
@Component
class RunActivityPorts implements RunnerActivity, RunModelTokens, RunReads {

    private final RunStore runs;

    private final RunRepositoryStore repositories;

    private final RunPresenter presenter;

    RunActivityPorts(RunStore runs, RunRepositoryStore repositories, RunPresenter presenter) {
        this.runs = runs;
        this.repositories = repositories;
        this.presenter = presenter;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> activeRunsByHost(Collection<UUID> runnerHostIds) {
        return runs.occupyingByHosts(runnerHostIds);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveRuns(UUID runnerHostId) {
        return runs.hasActiveForHost(runnerHostId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveRunsOnMachine(UUID personalHostId) {
        return runs.hasActiveForMachine(personalHostId);
    }

    /**
     * One indexed read per relayed request: the run is RUNNING, with the epoch, on the host. A run that ended, was
     * taken back or assigned anew (a new epoch, perhaps another host), and an unknown run, answer false.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean active(UUID companyId, UUID runId, long epoch, UUID runnerHostId) {
        return runs.runningOn(runId, epoch, runnerHostId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunInfo> find(UUID companyId, UUID runId) {
        return runs.find(runId).map(RunStore.RunRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> queueReasonText(UUID companyId, UUID runId) {
        return runs.find(runId)
            .filter(run -> run.status() == RunStatus.QUEUED)
            .map(RunStore.RunRow::queueReason)
            .map(QueueReason::text);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunInfo> activeOf(UUID companyId, UUID conversationId) {
        return runs.activeOfConversation(conversationId).map(RunStore.RunRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunInfo> ofConversation(UUID companyId, UUID conversationId) {
        return runs.ofConversation(conversationId).stream().map(RunStore.RunRow::info).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Run> views(UUID companyId, UUID conversationId) {
        return presenter.runs(runs.ofConversation(conversationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Push> lastPush(UUID companyId, UUID conversationId, UUID repositoryId,
            @Nullable UUID exceptRunId) {
        return repositories.lastPush(conversationId, repositoryId, exceptRunId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActiveRun> activeOnHost(UUID companyId, UUID runId, long epoch, UUID runnerHostId) {
        return runs.find(runId)
            .filter(run -> run.status() == RunStatus.RUNNING && run.epoch() == epoch
                    && runnerHostId.equals(run.runnerHostId()))
            .map(RunStore.RunRow::active);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunTokens> tokens(UUID companyId, UUID runId) {
        return runs.tokens(runId);
    }

    @Override
    @Transactional(readOnly = true)
    public int countQueued(UUID companyId, UUID payerPrincipalId, QueueReasonCode reason) {
        return runs.countQueued(payerPrincipalId, reason.name());
    }
}
