package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.api.model.Run;
import com.flowtivo.tinfirm.shared.api.model.RunEvent;
import com.flowtivo.tinfirm.shared.api.model.RunEventType;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Builds the Runs API's DTOs; the names of the person and the payer are resolved at read time. */
@Component
class RunPresenter {

    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {
    };

    private final JsonMapper payloads = JsonMapper.builder().build();

    private final Principals principals;

    private final RunRepositoryStore repositories;

    private final RunMapper mapper;

    RunPresenter(Principals principals, RunRepositoryStore repositories, RunMapper mapper) {
        this.principals = principals;
        this.repositories = repositories;
        this.mapper = mapper;
    }

    Run run(RunStore.RunRow run) {
        return runs(List.of(run)).getFirst();
    }

    /** The runs, of one company, in the given order. */
    List<Run> runs(List<RunStore.RunRow> runs) {
        if (runs.isEmpty()) {
            return List.of();
        }
        Set<UUID> people = new HashSet<>();
        runs.forEach(run -> {
            people.add(run.actingPrincipalId());
            people.add(run.payerPrincipalId());
        });
        Map<UUID, String> names = principals.displayNames(runs.getFirst().companyId(), people);
        Map<UUID, List<RunRepositoryStore.RepositoryRow>> workspaces = repositories.ofRuns(runs.stream()
            .map(RunStore.RunRow::id)
            .toList());
        return runs.stream()
            .map(run -> mapper.toDto(details(run, names, workspaces.getOrDefault(run.id(), List.of()))))
            .toList();
    }

    RunEvent event(RunEventStore.EventRow event) {
        return new RunEvent(event.seq(), RunEventType.fromValue(event.type()), event.at().atOffset(ZoneOffset.UTC),
                payloads.readValue(event.payloadJson(), PAYLOAD));
    }

    private static RunViews.RunDetails details(RunStore.RunRow run, Map<UUID, String> names,
            List<RunRepositoryStore.RepositoryRow> workspace) {
        RunViews.UsageDetails usage = run.inputTokens() != null || run.outputTokens() != null
                ? new RunViews.UsageDetails(orZero(run.inputTokens()), orZero(run.outputTokens()),
                        run.cachedInputTokens(), run.cacheWriteTokens(), run.costUsd())
                : null;
        RunStore.Counts counts = run.counts();
        return new RunViews.RunDetails(run.id(), run.conversationId(), run.conversationKey(), run.agentId(),
                run.runtime().name(), run.turnReason().name(), run.turnNumber(), run.actingPrincipalId(),
                names.get(run.actingPrincipalId()), run.payerPrincipalId(), names.get(run.payerPrincipalId()),
                run.modelAccess(), run.subscriptionLabel(), run.runnerHostId(), run.runnerHostName(),
                run.runnerHostKind() != null ? run.runnerHostKind().name() : null, run.status().name(),
                run.failureClass(), run.failureMessage(), queueReason(run), run.createdAt(), run.startedAt(),
                run.finishedAt(), run.summary(), result(run), run.branch(), workspace.stream()
                    .map(repository -> new RunViews.RepositoryDetails(repository.repositoryId(),
                            repository.systemId(), repository.name(), repository.dir(), repository.readOnly(),
                            repository.readOnlyCause() != null ? repository.readOnlyCause().name() : null,
                            repository.pushed(), repository.headSha(), repository.commits()))
                    .toList(),
                usage,
                run.relay() != null ? new RunViews.RelayDetails(run.relay().requests(), run.relay().requestBytes(),
                        run.relay().responseBytes()) : null,
                counts != null ? new RunViews.CountsDetails(counts.toolCalls(), counts.messages(), counts.thoughts())
                        : null,
                run.resumed(), run.resumeOutcome() != null ? run.resumeOutcome().name() : null, run.lastEventSeq(),
                run.textsPurgedAt(), run.eventsPurgedAt());
    }

    /** The run's result, whose summary is the run's summary; null without {@code finish}. */
    private static RunViews.@Nullable ResultDetails result(RunStore.RunRow run) {
        RunStore.Result result = run.result();
        if (result == null) {
            return null;
        }
        return new RunViews.ResultDetails(result.outcome().name(), run.summary() != null ? run.summary() : "",
                result.nextSteps(), result.openQuestions());
    }

    /** Only a QUEUED run has one (the database enforces it); null before the dispatcher looked at the run. */
    private static RunViews.@Nullable QueueReasonDetails queueReason(RunStore.RunRow run) {
        QueueReason reason = run.queueReason();
        Instant since = run.queueReasonSince();
        if (reason == null || since == null) {
            return null;
        }
        return new RunViews.QueueReasonDetails(reason.code().name(), since, reason.text(), reason.runnerHostId(),
                reason.runnerHostName(), reason.credentialLabel(), reason.used(), reason.limit(), reason.lastSeenAt(),
                reason.retryAt(), reason.reason(), reason.rejectCode(), reason.message(),
                reason.runtime() != null ? reason.runtime().name() : null);
    }

    private static Long orZero(@Nullable Long value) {
        return value != null ? value : 0L;
    }
}
