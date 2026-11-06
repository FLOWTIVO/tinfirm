package com.flowtivo.tinfirm.conversation.internal;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.ServletException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.ServletRequestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerAuthentication;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * The files of a run's conversation for its runner host (ADR-0014, ADR-0021; the runner protocol v2:
 * {@code run.assign.attachments}, {@code save_artifact} and {@code run.finished.artifacts}). Not part of the browser's
 * API: it sits under {@code /api/runner/v1/**}, whose security chain has no session and no CSRF, and authenticates the
 * host's own bearer token ({@link RunnerAuthentication}). Everything is read in the host's company: another company's
 * run is not found.
 * <ul>
 * <li>{@code GET /api/runner/v1/runs/{runId}/attachments/{attachmentId}?epoch=}: a file of the run's conversation, for
 * a run assigned to this host with this epoch and active (assigned and awaiting {@code run.accepted}, or running: the
 * host downloads before its sandbox starts). Anything else is 404, so a host learns nothing of other runs.</li>
 * <li>{@code POST /api/runner/v1/runs/{runId}/attachments?epoch=&artifact=&source=}: a file of the agent, for a run
 * {@code RUNNING} on this host with this epoch ({@code RunReads.activeOnHost}), else 404. It becomes the agent's file
 * of the conversation, scanned like a person's ({@link AttachmentService#storeForAgent}); with {@code artifact=true}
 * its name may be a path relative to {@code /output}. A file the agent named with {@code save_artifact}
 * ({@code source=save_artifact}, or no source) is a {@code DOCUMENT} output at once; a file of {@code /output}
 * ({@code source=output}) becomes one when the turn ends, if the host lists it in {@code run.finished.artifacts};
 * {@code artifact=false} stores the file alone. The answer is 200 with {@code status} {@code STORED} or
 * {@code REJECTED} and a message for the agent; 413 for a file over the server's limit.</li>
 * </ul>
 */
@RestController
class RunnerFilesController {

    private static final Logger log = LoggerFactory.getLogger(RunnerFilesController.class);

    static final String DOWNLOAD_PATH = "/api/runner/v1/runs/{runId}/attachments/{attachmentId}";

    static final String UPLOAD_PATH = "/api/runner/v1/runs/{runId}/attachments";

    /** The {@code source} of a file the host left in {@code /output}. */
    static final String SOURCE_OUTPUT = "output";

    private final RunnerAuthentication authentication;

    private final SystemTransactions transactions;

    private final RunReads runs;

    private final AttachmentService attachments;

    private final OutputService outputs;

    RunnerFilesController(RunnerAuthentication authentication, SystemTransactions transactions, RunReads runs,
            AttachmentService attachments, OutputService outputs) {
        this.authentication = authentication;
        this.transactions = transactions;
        this.runs = runs;
        this.attachments = attachments;
        this.outputs = outputs;
    }

    /**
     * The answer to an upload, for the runner host.
     *
     * @param status     {@code STORED} or {@code REJECTED}
     * @param artifactId the {@code DOCUMENT} output the file became, when it became one at once
     */
    record UploadAnswer(String status, @Nullable UUID attachmentId, @Nullable UUID artifactId,
            @Nullable String fileName, @Nullable String code, String message) {
    }

    @GetMapping(path = DOWNLOAD_PATH, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<Resource> download(@PathVariable UUID runId, @PathVariable UUID attachmentId,
            @RequestParam long epoch,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable String authorization) {
        ConnectedHost host = host(authorization, runId);
        Optional<byte[]> content = transactions.inCompany(host.companyId(), () -> runs.find(host.companyId(), runId)
            .filter(run -> assignedTo(run, host, epoch))
            .flatMap(run -> attachments.content(run.conversationId(), attachmentId)));
        byte[] bytes = content.orElseThrow(() -> ApiException.notFound("Attachment not found"));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(bytes.length)
            .cacheControl(CacheControl.noStore())
            .body(new ByteArrayResource(bytes));
    }

    /**
     * The upload authenticates the host's token before it reads a parameter or a part: with
     * {@code spring.servlet.multipart.resolve-lazily} the body is parsed, and its parts written to disk, only when the
     * first parameter or part is read, so an anonymous request is refused 401 before any of it is (the security
     * review of 2026-10-27, L24). Hence the parameters are read here rather than bound.
     */
    @PostMapping(path = UPLOAD_PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UploadAnswer> upload(@PathVariable UUID runId, MultipartHttpServletRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable String authorization)
            throws IOException, ServletException {
        ConnectedHost host = host(authorization, runId);
        // Authenticated: from here on the body may be read.
        long epoch = ServletRequestUtils.getRequiredLongParameter(request, "epoch");
        MultipartFile file = request.getFile("file");
        if (file == null) {
            throw new MissingServletRequestPartException("file");
        }
        String note = request.getParameter("note");
        Boolean artifact = flag(request.getParameter("artifact"));
        String source = request.getParameter("source");
        byte[] content = file.getBytes();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        boolean asOutput = !Boolean.FALSE.equals(artifact);
        boolean fromOutputFolder = SOURCE_OUTPUT.equals(source);
        Optional<UploadAnswer> answer = transactions.inCompany(host.companyId(), () -> runs
            .activeOnHost(host.companyId(), runId, epoch, host.runnerHostId())
            .map(run -> {
                AttachmentService.AgentUploadResult stored = attachments.storeForAgent(run,
                        new AttachmentService.AgentUpload(fileName, file.getContentType(), content, note,
                                Boolean.TRUE.equals(artifact)));
                if (!stored.stored()) {
                    return new UploadAnswer("REJECTED", null, null, null, stored.code(), stored.message());
                }
                UUID attachmentId = stored.attachmentId();
                String name = stored.fileName();
                if (asOutput && !fromOutputFolder) {
                    UUID outputId = outputs.savedDocument(run, attachmentId, name);
                    log.info("Run {} saved the file {} as attachment {} and output {}", run.runId(), name,
                            attachmentId, outputId);
                    return new UploadAnswer("STORED", attachmentId, outputId, name, null, "Saved `" + name
                            + "` as an output of the conversation, where the person sees it; later turns find it in `"
                            + OutputPrompt.ATTACHMENTS_DIR + "`.");
                }
                log.info("Run {} stored the file {} as attachment {}", run.runId(), name, attachmentId);
                return new UploadAnswer("STORED", attachmentId, null, name, null, asOutput
                        ? "Stored `" + name + "`; it becomes an output of the conversation when the turn ends."
                        : "Stored `" + name + "` among the conversation's files.");
            }));
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(answer.orElseThrow(() -> ApiException.notFound("Run not found")));
    }

    /**
     * The host of the token, placed in the company of the run it asks about (a machine is present in several,
     * ADR-0092); a run of no company of the host's is unknown to it.
     */
    private ConnectedHost host(@Nullable String authorization, UUID runId) {
        ConnectedHost authenticated = authentication.authenticate(authorization)
            .orElseThrow(() -> ApiException.unauthorized("Invalid runner host token"));
        return authentication.forRun(authenticated, runId).orElseThrow(() -> ApiException.notFound("Run not found"));
    }

    /** An optional boolean parameter, as {@code @RequestParam Boolean} binds it: null when absent or empty. */
    private static @Nullable Boolean flag(@Nullable String value) throws ServletRequestBindingException {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Boolean.TRUE;
            case "false", "off", "no", "0" -> Boolean.FALSE;
            default -> throw new ServletRequestBindingException("The parameter artifact is not a boolean");
        };
    }

    /** The run is active, assigned to the host with this epoch: queued and awaiting {@code run.accepted}, or running. */
    private static boolean assignedTo(RunInfo run, ConnectedHost host, long epoch) {
        return (run.status() == RunStatus.QUEUED || run.status() == RunStatus.RUNNING) && run.epoch() == epoch
                && host.runnerHostId().equals(run.runnerHostId());
    }
}
