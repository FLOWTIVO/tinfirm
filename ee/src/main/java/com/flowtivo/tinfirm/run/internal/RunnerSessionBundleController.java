package com.flowtivo.tinfirm.run.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerAuthentication;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * The session bundle of a run's conversation for its runner host (ADR-0097; the runner protocol v2, the feature
 * {@code session.bundle}). Not part of the browser's API: it sits under {@code /api/runner/v1/**}, whose security chain
 * has no session and no CSRF, and authenticates the host's own bearer token ({@link RunnerAuthentication}).
 * Everything is read in the host's company, and only for a company host: another company's run, a personal host's
 * and another host's are not found. Part of elastic runners (ADR-0100): without {@code ELASTIC_RUNNERS} in the licence
 * in force, a valid token gets {@code 403 EDITION_REQUIRED}.
 * <ul>
 * <li>{@code GET /api/runner/v1/runs/{runId}/session-bundle?epoch=}: the bundle of the run's conversation and agent,
 * of its runtime, decrypted, for a run assigned to this host with this epoch and active (the host restores it before
 * its sandbox starts); {@code X-Content-SHA256} names its SHA-256, which the host checks. Anything else is 404.</li>
 * <li>{@code POST /api/runner/v1/runs/{runId}/session-bundle?epoch=&sessionId=} with the gzip-compressed tar as the
 * body: the run's bundle of the session {@code sessionId}, for a run RUNNING on this host with this epoch, else 404.
 * The answer is 200 with {@code status} {@code STORED} or {@code REFUSED} and a message for the run's log; 413 for a
 * body that says it is larger than {@value RunnerMessages#MAX_SESSION_BUNDLE_BYTES} bytes.</li>
 * </ul>
 */
@RestController
class RunnerSessionBundleController {

    static final String PATH = "/api/runner/v1/runs/{runId}/session-bundle";

    static final String SHA256_HEADER = "X-Content-SHA256";

    private final RunnerAuthentication authentication;

    private final SessionBundlesImpl bundles;

    private final Licensing licensing;

    RunnerSessionBundleController(RunnerAuthentication authentication, SessionBundlesImpl bundles,
            Licensing licensing) {
        this.authentication = authentication;
        this.bundles = bundles;
        this.licensing = licensing;
    }

    @GetMapping(path = PATH, produces = "application/gzip")
    ResponseEntity<Resource> download(@PathVariable UUID runId, @RequestParam long epoch,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable String authorization)
            throws IOException {
        ConnectedHost host = host(authorization, runId);
        SessionBundlesImpl.Download bundle = bundles.download(host, runId, epoch)
            .orElseThrow(() -> ApiException.notFound("Session bundle not found"));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/gzip"))
            .contentLength(bundle.sizeBytes())
            .header(SHA256_HEADER, bundle.sha256())
            .cacheControl(CacheControl.noStore())
            .body(new InputStreamResource(bundle.content()));
    }

    @PostMapping(path = PATH, consumes = { "application/gzip", MediaType.APPLICATION_OCTET_STREAM_VALUE },
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionBundlesImpl.UploadAnswer> upload(@PathVariable UUID runId, @RequestParam long epoch,
            @RequestParam String sessionId, InputStream body,
            @RequestHeader(name = HttpHeaders.CONTENT_LENGTH, required = false) @Nullable Long length,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable String authorization)
            throws IOException {
        ConnectedHost host = host(authorization, runId);
        if (length != null && length > RunnerMessages.MAX_SESSION_BUNDLE_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, null, "A session bundle is at most "
                    + RunnerMessages.MAX_SESSION_BUNDLE_BYTES + " bytes");
        }
        SessionBundlesImpl.UploadAnswer answer = bundles.upload(host, runId, epoch, sessionId, body)
            .orElseThrow(() -> ApiException.notFound("Run not found"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(answer);
    }

    /**
     * The host of the token, while the licence in force holds elastic runners, placed in the company of the run it
     * asks about: a run of no company of the host's is not found. A member's machine is placed as its presence there,
     * whose runs are never a company host's, so it finds no bundle (404, the security review of 2026-10-27, I8).
     */
    private ConnectedHost host(@Nullable String authorization, UUID runId) {
        ConnectedHost host = authentication.authenticate(authorization)
            .orElseThrow(() -> ApiException.unauthorized("Invalid runner host token"));
        licensing.require(Feature.ELASTIC_RUNNERS);
        return authentication.forRun(host, runId).orElseThrow(() -> ApiException.notFound("Run not found"));
    }
}
