package com.flowtivo.tinfirm.mcp.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.flowtivo.tinfirm.conversation.StagedFiles;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.BearerGrant;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * {@code POST /api/companies/{companyId}/mcp/files} (ADR-0084): a file the person's client holds for a start, one
 * {@code file} part of {@code multipart/form-data}, behind the endpoint's bearer chain, so with a personal access token
 * or an OAuth grant and never the session; the Outlook add-in's task pane uploads an email's attachments here, then
 * names them in {@code start_conversation}'s {@code fileIds}. As the endpoint's requests: the credential's rate limit
 * (429 {@code RATE_LIMITED} with {@code Retry-After}), the scope {@code tinfirm:write} (403 {@code INSUFFICIENT_SCOPE}),
 * and a request with an {@code Origin}, the add-in's, refused without {@code OUTLOOK_ADDIN} for an
 * active member (403 {@code EDITION_REQUIRED}). Then {@link StagedFiles#stage}'s rules and problems; 201 with the held
 * file. Logged at INFO with the company, the user, the grant, the size and the outcome; never the file or its name.
 */
@RestController
class McpFilesController {

    private static final Logger log = LoggerFactory.getLogger(McpFilesController.class);

    static final String PATH = "/api/companies/{companyId}/mcp/files";

    private final StagedFiles files;

    private final McpRateLimiter limiter;

    private final Licensing licensing;

    private final McpMembers members;

    McpFilesController(StagedFiles files, McpRateLimiter limiter, Licensing licensing, McpMembers members) {
        this.files = files;
        this.limiter = limiter;
        this.licensing = licensing;
        this.members = members;
    }

    @PostMapping(path = PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> upload(@PathVariable UUID companyId, @RequestParam("file") MultipartFile file,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) @Nullable String origin) {
        Optional<BearerGrant> grant = BearerGrantHolder.current();
        UUID credential = grant.map(BearerGrant::id)
            .orElseGet(() -> CurrentActor.requireUser().id());
        Optional<Duration> wait = limiter.admit(credential);
        if (wait.isPresent()) {
            throw ApiException.rateLimited(wait.get(), "Too many requests from this token");
        }
        if (grant.isEmpty() || !grant.get().allows(ApiScope.WRITE)) {
            throw ApiException.forbidden(ProblemCode.INSUFFICIENT_SCOPE, "Uploading a file needs the scope "
                    + ApiScope.WRITE.oauthScope() + ", which this token does not have. Sign in again and allow it.");
        }
        if (origin != null && !licensing.has(Feature.OUTLOOK_ADDIN) && members.test(companyId)) {
            // Only Tinfirm's own page sends an Origin here, the Outlook add-in's task pane (ADR-0074, ADR-0077).
            throw Licensing.editionRequired(Feature.OUTLOOK_ADDIN);
        }
        String outcome = "failed";
        try {
            byte[] content;
            try {
                content = file.getBytes();
            }
            catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            StagedFiles.StagedFile staged = files.stage(companyId, file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "", file.getContentType(), content);
            outcome = "held";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", staged.id());
            body.put("fileName", staged.fileName());
            body.put("contentType", staged.contentType());
            body.put("sizeBytes", staged.sizeBytes());
            body.put("expiresAt", staged.expiresAt().atOffset(ZoneOffset.UTC).toString());
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }
        catch (ApiException ex) {
            outcome = ex.code() != null ? ex.code().name() : ex.status().name();
            throw ex;
        }
        finally {
            log.atInfo()
                .addKeyValue("mcp.companyId", companyId)
                .addKeyValue("mcp.grantKind", grant.map(BearerGrant::kind).orElse(null))
                .addKeyValue("mcp.grantId", grant.map(BearerGrant::id).orElse(null))
                .addKeyValue("mcp.sizeBytes", file.getSize())
                .addKeyValue("mcp.outcome", outcome)
                .log("MCP file upload in company {} ({} bytes): {}", companyId, file.getSize(), outcome);
        }
    }
}
