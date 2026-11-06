package com.flowtivo.tinfirm.trigger.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.TriggersApi;
import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsRequest;
import com.flowtivo.tinfirm.shared.api.model.MemoryNotes;
import com.flowtivo.tinfirm.shared.api.model.CompanySettings;
import com.flowtivo.tinfirm.shared.api.model.Trigger;
import com.flowtivo.tinfirm.shared.api.model.TriggerAcceptRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerAccountsRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerChoices;
import com.flowtivo.tinfirm.shared.api.model.TriggerCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerFiring;
import com.flowtivo.tinfirm.shared.api.model.TriggerFiringPage;
import com.flowtivo.tinfirm.shared.api.model.TriggerPage;
import com.flowtivo.tinfirm.shared.api.model.TriggerPauseRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerPollList;
import com.flowtivo.tinfirm.shared.api.model.TriggerPreview;
import com.flowtivo.tinfirm.shared.api.model.TriggerPreviewRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerReassignRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerRunRequest;
import com.flowtivo.tinfirm.shared.api.model.TriggerStatus;
import com.flowtivo.tinfirm.shared.api.model.TriggerSubjectList;
import com.flowtivo.tinfirm.shared.api.model.TriggerUpdateRequest;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * The Triggers API (the package 4 contract, §6), over {@link TriggerService}. A save of a channel trigger first asks
 * the chat platform, outside any transaction, which of its channels the author is in (package 5, §5.7).
 */
@RestController
class TriggersController implements TriggersApi {

    private final TriggerService triggers;

    private final TriggerMemory memory;

    TriggersController(TriggerService triggers, TriggerMemory memory) {
        this.triggers = triggers;
        this.memory = memory;
    }

    @Override
    public ResponseEntity<TriggerPage> listTriggers(UUID companyId, @Nullable UUID systemId, @Nullable UUID agentId,
            @Nullable UUID authorId, @Nullable UUID teamGroupId, @Nullable TriggerStatus status,
            @Nullable UUID accountHolderPrincipalId, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(triggers.list(companyId, systemId, agentId, authorId, teamGroupId,
                status != null ? com.flowtivo.tinfirm.trigger.TriggerStatus.valueOf(status.getValue()) : null,
                accountHolderPrincipalId, limit, cursor));
    }

    @Override
    public ResponseEntity<Trigger> createTrigger(UUID companyId, TriggerCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(triggers.create(companyId, request,
                triggers.joined(companyId, null, null, request.getChannels(), request.getSource().getValue(),
                        request.getSystemId())));
    }

    @Override
    public ResponseEntity<TriggerChoices> getTriggerChoices(UUID companyId, @Nullable UUID agentId,
            @Nullable UUID systemId, @Nullable UUID forPrincipalId, @Nullable UUID triggerId) {
        return ResponseEntity.ok(triggers.choices(companyId, agentId, systemId, forPrincipalId, triggerId));
    }

    @Override
    public ResponseEntity<TriggerPreview> previewTrigger(UUID companyId, TriggerPreviewRequest request) {
        var draft = request.getDraft();
        return ResponseEntity.ok(triggers.preview(companyId, request, draft == null ? null
                : triggers.joined(companyId, request.getTriggerId(), null, draft.getChannels(),
                        draft.getSource().getValue(), draft.getSystemId())));
    }

    @Override
    public ResponseEntity<Trigger> getTrigger(UUID companyId, UUID triggerId) {
        return ResponseEntity.ok(triggers.get(companyId, triggerId));
    }

    @Override
    public ResponseEntity<Trigger> updateTrigger(UUID companyId, UUID triggerId, TriggerUpdateRequest request) {
        return ResponseEntity.ok(triggers.update(companyId, triggerId, request, request.getChannels() == null ? null
                : triggers.joined(companyId, triggerId, null, request.getChannels(), null, null)));
    }

    @Override
    public ResponseEntity<Void> deleteTrigger(UUID companyId, UUID triggerId) {
        triggers.delete(companyId, triggerId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Trigger> replaceTriggerAccounts(UUID companyId, UUID triggerId,
            TriggerAccountsRequest request) {
        return ResponseEntity.ok(triggers.replaceAccounts(companyId, triggerId, request));
    }

    @Override
    public ResponseEntity<AgentSettings> getTriggerSettings(UUID companyId, UUID triggerId) {
        return ResponseEntity.ok(triggers.settings(companyId, triggerId));
    }

    @Override
    public ResponseEntity<AgentSettings> saveTriggerSettings(UUID companyId, UUID triggerId,
            AgentSettingsRequest request) {
        return ResponseEntity.ok(triggers.saveSettings(companyId, triggerId, request));
    }

    @Override
    public ResponseEntity<MemoryNotes> getTriggerMemory(UUID companyId, UUID triggerId) {
        return ResponseEntity.ok(memory.get(companyId, triggerId));
    }

    @Override
    public ResponseEntity<MemoryNotes> clearTriggerMemory(UUID companyId, UUID triggerId) {
        return ResponseEntity.ok(memory.clear(companyId, triggerId));
    }

    @Override
    public ResponseEntity<MemoryNotes> deleteTriggerMemoryNote(UUID companyId, UUID triggerId, Integer noteNumber) {
        return ResponseEntity.ok(memory.delete(companyId, triggerId, noteNumber));
    }

    @Override
    public ResponseEntity<MemoryNotes> acceptTriggerMemoryNote(UUID companyId, UUID triggerId, Integer noteNumber) {
        return ResponseEntity.ok(memory.accept(companyId, triggerId, noteNumber));
    }

    @Override
    public ResponseEntity<Trigger> acceptTriggerWidening(UUID companyId, UUID triggerId,
            TriggerAcceptRequest request) {
        if (request.getVersion() == null) {
            throw ApiException.validation("version is the agent's version to accept");
        }
        return ResponseEntity.ok(triggers.accept(companyId, triggerId, request.getVersion(),
                Boolean.TRUE.equals(request.getAcknowledgeOpenEvents())));
    }

    @Override
    public ResponseEntity<Trigger> pauseTrigger(UUID companyId, UUID triggerId,
            @Nullable TriggerPauseRequest request) {
        return ResponseEntity.ok(triggers.pause(companyId, triggerId,
                request != null && Boolean.TRUE.equals(request.getStopRunning())));
    }

    @Override
    public ResponseEntity<Trigger> resumeTrigger(UUID companyId, UUID triggerId) {
        return ResponseEntity.ok(triggers.resume(companyId, triggerId));
    }

    @Override
    public ResponseEntity<TriggerFiring> runTrigger(UUID companyId, UUID triggerId,
            @Nullable TriggerRunRequest request) {
        return ResponseEntity.ok(triggers.run(companyId, triggerId, request != null ? request.getSubjectKey() : null));
    }

    @Override
    public ResponseEntity<TriggerSubjectList> listTriggerSubjects(UUID companyId, UUID triggerId,
            @Nullable String query) {
        return ResponseEntity.ok(triggers.subjects(companyId, triggerId, query));
    }

    @Override
    public ResponseEntity<Trigger> reassignTrigger(UUID companyId, UUID triggerId, TriggerReassignRequest request) {
        return ResponseEntity.ok(triggers.reassign(companyId, triggerId, request,
                triggers.joined(companyId, triggerId, request.getAuthorPrincipalId(), null, null, null)));
    }

    @Override
    public ResponseEntity<TriggerFiringPage> listTriggerFirings(UUID companyId, UUID triggerId, Integer limit,
            @Nullable String cursor) {
        return ResponseEntity.ok(triggers.firings(companyId, triggerId, limit, cursor));
    }

    @Override
    public ResponseEntity<TriggerPollList> listSystemPolls(UUID companyId, UUID systemId) {
        return ResponseEntity.ok(triggers.polls(companyId, systemId));
    }

    @Override
    public ResponseEntity<CompanySettings> getCompanySettings(UUID companyId) {
        return ResponseEntity.ok(triggers.companySettings(companyId));
    }

    @Override
    public ResponseEntity<CompanySettings> updateCompanySettings(UUID companyId, CompanySettings settings) {
        return ResponseEntity.ok(triggers.saveCompanySettings(companyId, settings));
    }
}
