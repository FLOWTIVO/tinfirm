package com.flowtivo.tinfirm.agent.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.agent.AgentReads;
import com.flowtivo.tinfirm.shared.api.AgentsApi;
import com.flowtivo.tinfirm.shared.api.SettingsApi;
import com.flowtivo.tinfirm.shared.api.model.Agent;
import com.flowtivo.tinfirm.shared.api.model.AgentOwnerRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentAcceptRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentHostsRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentPage;
import com.flowtivo.tinfirm.shared.api.model.AgentPauseRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentReadiness;
import com.flowtivo.tinfirm.shared.api.model.AgentRepositoriesRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsRequest;
import com.flowtivo.tinfirm.shared.api.model.MemoryNotes;
import com.flowtivo.tinfirm.shared.api.model.AgentSharesRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentSkillsRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentSystemsRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentUpdateRequest;

@RestController
class AgentsController implements AgentsApi, SettingsApi {

    private final AgentService agents;

    private final SettingsService settings;

    private final AgentReads reads;

    private final MemoryPages memory;

    AgentsController(AgentService agents, SettingsService settings, AgentReads reads, MemoryPages memory) {
        this.agents = agents;
        this.settings = settings;
        this.reads = reads;
        this.memory = memory;
    }

    @Override
    public ResponseEntity<AgentPage> listAgents(UUID companyId, Boolean usable, Integer limit,
            @Nullable String cursor) {
        return ResponseEntity.ok(reads.list(companyId, usable, limit, cursor));
    }

    @Override
    public ResponseEntity<Agent> createAgent(UUID companyId, AgentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agents.create(companyId, request));
    }

    @Override
    public ResponseEntity<Agent> getAgent(UUID companyId, UUID agentId) {
        return ResponseEntity.ok(reads.get(companyId, agentId));
    }

    @Override
    public ResponseEntity<Agent> updateAgent(UUID companyId, UUID agentId, AgentUpdateRequest request) {
        return ResponseEntity.ok(agents.update(companyId, agentId, request));
    }

    @Override
    public ResponseEntity<Void> deleteAgent(UUID companyId, UUID agentId) {
        agents.delete(companyId, agentId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Agent> replaceAgentSystems(UUID companyId, UUID agentId, AgentSystemsRequest request) {
        return ResponseEntity.ok(agents.replaceSystems(companyId, agentId, request.getSystems()));
    }

    @Override
    public ResponseEntity<Agent> replaceAgentRepositories(UUID companyId, UUID agentId,
            AgentRepositoriesRequest request) {
        return ResponseEntity.ok(agents.replaceRepositories(companyId, agentId, request.getRepositories()));
    }

    @Override
    public ResponseEntity<Agent> replaceAgentHosts(UUID companyId, UUID agentId, AgentHostsRequest request) {
        return ResponseEntity.ok(agents.replaceHosts(companyId, agentId, request.getHosts()));
    }

    @Override
    public ResponseEntity<Agent> replaceAgentShares(UUID companyId, UUID agentId, AgentSharesRequest request) {
        return ResponseEntity
            .ok(agents.replaceShares(companyId, agentId, request.getGroupIds(), request.getPrincipalIds()));
    }

    @Override
    public ResponseEntity<Agent> setAgentOwner(UUID companyId, UUID agentId, AgentOwnerRequest request) {
        return ResponseEntity.ok(agents.setOwner(companyId, agentId, request.getPrincipalId()));
    }

    @Override
    public ResponseEntity<Agent> pauseAgent(UUID companyId, UUID agentId, @Nullable AgentPauseRequest request) {
        boolean stopRunning = request != null && Boolean.TRUE.equals(request.getStopRunning());
        return ResponseEntity.ok(agents.pause(companyId, agentId, stopRunning));
    }

    @Override
    public ResponseEntity<Agent> resumeAgent(UUID companyId, UUID agentId) {
        return ResponseEntity.ok(agents.resume(companyId, agentId));
    }

    @Override
    public ResponseEntity<AgentSettings> acceptAgentVersion(UUID companyId, UUID agentId, AgentAcceptRequest request) {
        return ResponseEntity.ok(settings.accept(companyId, agentId, request.getVersion()));
    }

    @Override
    public ResponseEntity<AgentReadiness> getAgentReadiness(UUID companyId, UUID agentId) {
        return ResponseEntity.ok(reads.readiness(companyId, agentId));
    }

    @Override
    public ResponseEntity<AgentSettings> getMyAgentSettings(UUID companyId, UUID agentId) {
        return ResponseEntity.ok(settings.get(companyId, agentId));
    }

    @Override
    public ResponseEntity<AgentSettings> putMyAgentSettings(UUID companyId, UUID agentId,
            AgentSettingsRequest request) {
        return ResponseEntity.ok(settings.put(companyId, agentId, request));
    }

    @Override
    public ResponseEntity<AgentSettings> resetMyAgentSettings(UUID companyId, UUID agentId) {
        return ResponseEntity.ok(settings.reset(companyId, agentId));
    }

    @Override
    public ResponseEntity<Agent> replaceAgentSkills(UUID companyId, UUID agentId, AgentSkillsRequest request) {
        return ResponseEntity.ok(agents.replaceSkills(companyId, agentId, request.getSkills()));
    }

    @Override
    public ResponseEntity<Resource> downloadAgentSkill(UUID companyId, UUID agentId, UUID skillId) {
        AgentService.SkillContent download = agents.skillContent(companyId, agentId, skillId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .contentLength(download.content().length)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(download.fileName(), StandardCharsets.UTF_8).build()
                        .toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(new ByteArrayResource(download.content()));
    }

    @Override
    public ResponseEntity<MemoryNotes> getMyAgentMemory(UUID companyId, UUID agentId) {
        return ResponseEntity.ok(memory.get(companyId, agentId));
    }

    @Override
    public ResponseEntity<MemoryNotes> clearMyAgentMemory(UUID companyId, UUID agentId) {
        return ResponseEntity.ok(memory.clear(companyId, agentId));
    }

    @Override
    public ResponseEntity<MemoryNotes> deleteMyAgentMemoryNote(UUID companyId, UUID agentId, Integer noteNumber) {
        return ResponseEntity.ok(memory.delete(companyId, agentId, noteNumber));
    }
}
