package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.RawHttp;

import tools.jackson.databind.json.JsonMapper;

/**
 * An upload over the multipart limit with the production limits ({@code TINFIRM_ATTACHMENT_MAX_FILE_SIZE} 25 MB,
 * {@code TINFIRM_ATTACHMENT_MAX_REQUEST_SIZE} 26 MB; the test profile lowers only the conversation's own limit), sent
 * as a client sends a file, over plain HTTP: up to {@code TINFIRM_ATTACHMENT_MAX_DRAIN_SIZE} (64 MB) it is read to its
 * end and thrown away for an authenticated caller, and the client gets {@code 413 ATTACHMENT_TOO_LARGE}; beyond it,
 * and for an anonymous request on any chain, the connection closes while the client still sends. Nothing of it reaches the disk.
 */
class UploadOverTheLimitIntegrationTest extends OutputTestBench {

    private static final long MIB = 1024 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    ServletContext servletContext;

    @Test
    void aConversationsFileOverTheLimitIsAnswered413() {
        Bench bench = bench();
        String path = files(bench, conversation(bench));
        Set<String> before = uploads();
        for (long size : new long[] { 26 * MIB, 30 * MIB, 63 * MIB }) {
            RawHttp.Upload upload = RawHttp.upload(port, path, session(bench.person()), "big.bin", size);
            assertTooLarge(upload, size / MIB + " MiB");
        }
        assertThat(uploads()).as("no part on the disk").isEqualTo(before);
    }

    @Test
    void theOtherUploadsOverTheLimitAreAnswered413TheSameWay() {
        Bench bench = bench();
        // A skill's upload.
        assertTooLarge(RawHttp.upload(port, bench.path("/skills"), session(bench.person()), "skill.zip", 30 * MIB),
                "skill");
        // The MCP endpoint's files, with a personal access token.
        ApiResponse token = bench.person().post("/api/me/tokens", Map.of("currentPassword", PASSWORD, "name",
                "files", "scope", "WRITE"));
        assertThat(token.status()).as("%s", token).isEqualTo(201);
        assertTooLarge(RawHttp.upload(port, bench.path("/mcp/files"), Map.of("Authorization", "Bearer "
                + token.json().get("token").asString()), "mail.pdf", 30 * MIB), "MCP files");
        // A runner host's upload of /output, for a run it holds.
        RunnerHostRef host = host(bench);
        UUID runId = running(bench, conversation(bench), host, 1, 1);
        assertTooLarge(RawHttp.upload(port, "/api/runner/v1/runs/" + runId + "/attachments?epoch=1&artifact=true",
                Map.of("Authorization", "Bearer " + host.token()), "report.pdf", 30 * MIB), "runner host");
    }

    @Test
    void beyondTheBoundAndForAnAnonymousRequestTheBodyIsNotRead() {
        Bench bench = bench();
        RawHttp.Upload beyond = RawHttp.upload(port, files(bench, conversation(bench)), session(bench.person()),
                "huge.bin", 65 * MIB);
        assertThat(beyond.sentAll()).as("65 MiB: the connection closes while the client sends").isFalse();
        // Anonymous: refused 401 by the token check before the multipart is parsed (L24), and Tomcat reads at most
        // its 2 MB of the rest, as before.
        RawHttp.Upload anonymous = RawHttp.upload(port, "/api/runner/v1/runs/" + UUID.randomUUID()
                + "/attachments?epoch=1&artifact=true", Map.of(), "big.bin", 30 * MIB);
        assertThat(anonymous.sentAll()).as("anonymous: the body is not read to its end").isFalse();
        // Anonymous on the session chain, without a session or a CSRF header: whatever refuses it, the body over the
        // limit is not read to its end either (no drain without an authenticated caller).
        RawHttp.Upload session = RawHttp.upload(port, files(bench, conversation(bench)), Map.of(), "big.bin",
                30 * MIB);
        assertThat(session.sentAll()).as("anonymous on the session chain: %s", session.answer()).isFalse();
    }

    private static void assertTooLarge(RawHttp.Upload upload, String what) {
        assertThat(upload.sentAll()).as("%s: the server read the whole body: %s", what, upload.answer()).isTrue();
        assertThat(upload.answer()).as("%s: an answer arrived", what).isNotNull();
        assertThat(upload.answer().status()).as("%s: %s", what, upload.answer()).isEqualTo(413);
        assertThat(upload.answer().header("Content-Type")).startsWith("application/problem+json");
        assertThat(JSON.readTree(upload.answer().body()).get("code").asString()).as(what)
            .isEqualTo("ATTACHMENT_TOO_LARGE");
    }

    /** The session cookie and the CSRF token of a signed-in client, as the web app sends them. */
    private static Map<String, String> session(ApiClient client) {
        String xsrf = client.cookie("XSRF-TOKEN").orElseThrow().getValue();
        return Map.of("Cookie", "TINFIRM_SESSION=" + client.cookie("TINFIRM_SESSION").orElseThrow().getValue()
                + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf);
    }

    /** The files a multipart parse leaves in the container's temporary directory while its request lasts. */
    private Set<String> uploads() {
        File[] files = ((File) servletContext.getAttribute(ServletContext.TEMPDIR))
            .listFiles((dir, name) -> name.startsWith("upload_"));
        return files == null ? Set.of() : Arrays.stream(files).map(File::getName).collect(Collectors.toSet());
    }
}
