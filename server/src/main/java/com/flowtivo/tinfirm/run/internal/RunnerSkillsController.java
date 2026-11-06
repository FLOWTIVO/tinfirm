package com.flowtivo.tinfirm.run.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerAuthentication;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.skill.Skills;

/**
 * The skills of a run for its runner host (ADR-0064; the skills contract, §7.2; the runner protocol v2:
 * {@code run.assign.skills}). Not part of the browser's API: it sits under {@code /api/runner/v1/**}, whose security
 * chain has no session and no CSRF, and authenticates the host's own bearer token ({@link RunnerAuthentication}).
 * Everything is read in the host's company: another company's run is not found.
 * <p>
 * {@code GET /api/runner/v1/runs/{runId}/skills/{skillId}/versions/{version}?epoch=}: the canonical zip of a skill
 * the run was assigned at that version ({@code run_skill}), for a run assigned to this host with this epoch and active
 * (assigned and awaiting {@code run.accepted}, or running: the host downloads before its sandbox starts). Anything
 * else is 404, so a host learns nothing of other runs or other versions.
 */
@RestController
class RunnerSkillsController {

    static final String DOWNLOAD_PATH = "/api/runner/v1/runs/{runId}/skills/{skillId}/versions/{version}";

    private final RunnerAuthentication authentication;

    private final SystemTransactions transactions;

    private final RunReads runs;

    private final RunSkillStore assigned;

    private final Skills skills;

    RunnerSkillsController(RunnerAuthentication authentication, SystemTransactions transactions, RunReads runs,
            RunSkillStore assigned, Skills skills) {
        this.authentication = authentication;
        this.transactions = transactions;
        this.runs = runs;
        this.assigned = assigned;
        this.skills = skills;
    }

    @GetMapping(path = DOWNLOAD_PATH, produces = "application/zip")
    ResponseEntity<Resource> download(@PathVariable UUID runId, @PathVariable UUID skillId, @PathVariable int version,
            @RequestParam long epoch,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable String authorization) {
        ConnectedHost authenticated = authentication.authenticate(authorization)
            .orElseThrow(() -> ApiException.unauthorized("Invalid runner host token"));
        // Placed in the run's company: a machine is present in several (ADR-0092).
        ConnectedHost host = authentication.forRun(authenticated, runId)
            .orElseThrow(() -> ApiException.notFound("Skill not found"));
        Optional<byte[]> content = transactions.inCompany(host.companyId(), () -> runs.find(host.companyId(), runId)
            .filter(run -> assignedTo(run, host, epoch))
            .filter(run -> assigned.assigned(host.companyId(), runId, skillId, version))
            .flatMap(run -> skills.content(host.companyId(), skillId, version)));
        byte[] bytes = content.orElseThrow(() -> ApiException.notFound("Skill not found"));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .contentLength(bytes.length)
            .cacheControl(CacheControl.noStore())
            .body(new ByteArrayResource(bytes));
    }

    /**
     * The run is active, assigned to the host with this epoch: queued and awaiting {@code run.accepted}, or running.
     */
    private static boolean assignedTo(RunInfo run, ConnectedHost host, long epoch) {
        return (run.status() == RunStatus.QUEUED || run.status() == RunStatus.RUNNING) && run.epoch() == epoch
                && host.runnerHostId().equals(run.runnerHostId());
    }
}
