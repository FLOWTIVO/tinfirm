package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.RunnerFileRequests;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * The runner host's file endpoints (the runner protocol v2; ADR-0014, ADR-0021): a host downloads the files of its
 * active run's conversation and uploads the agent's files; a {@code save_artifact} file is a {@code DOCUMENT} output at
 * once, a file of {@code /output} when its turn ends ({@link OutputTurnEnd}); everything else is 404, another company's
 * run included.
 */
@ExtendWith(EnterpriseLicence.class)
class RunnerFilesIntegrationTest extends OutputTestBench {

    private static final byte[] REPORT = "# Report\n\nAll good.\n".getBytes(StandardCharsets.UTF_8);

    @Autowired
    OutputTurnEnd turnEnd;

    @Autowired
    OutputService outputs;

    @Autowired
    ServletContext servletContext;

    @Test
    void aHostDownloadsTheFilesOfItsActiveRunOnly() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        RunnerHostRef host = host(bench);
        RunnerHostRef other = host(bench);
        UUID runId = running(bench, conversation, host, 3, 1);
        byte[] content = "the brief".getBytes(StandardCharsets.UTF_8);
        UUID attachmentId = UUID.fromString(bench.person().upload(files(bench, conversation),
                new ApiClient.Multipart("brief.txt", "text/plain", content)).json().get("id").asString());

        HttpResponse<byte[]> download = hostDownload(host.token(), runId, attachmentId, 3);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(content);
        assertThat(download.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        // Another epoch, another host, no token, an unknown file, a file of another conversation.
        assertThat(hostDownload(host.token(), runId, attachmentId, 2).statusCode()).isEqualTo(404);
        assertThat(hostDownload(other.token(), runId, attachmentId, 3).statusCode()).isEqualTo(404);
        assertThat(hostDownload(null, runId, attachmentId, 3).statusCode()).isEqualTo(401);
        assertThat(hostDownload("tfr_not-a-token", runId, attachmentId, 3).statusCode()).isEqualTo(401);
        assertThat(hostDownload(host.token(), runId, UUID.randomUUID(), 3).statusCode()).isEqualTo(404);
        Conversation second = conversation(bench);
        UUID secondFile = UUID.fromString(bench.person().upload(files(bench, second),
                new ApiClient.Multipart("other.txt", "text/plain", content)).json().get("id").asString());
        assertThat(hostDownload(host.token(), runId, secondFile, 3).statusCode()).isEqualTo(404);
        // The run ended: nothing more.
        TestRuns.ended(bench.companyId(), runId);
        assertThat(hostDownload(host.token(), runId, attachmentId, 3).statusCode()).isEqualTo(404);
    }

    @Test
    void anAnonymousUploadIsRefusedBeforeItsBodyIsRead() throws IOException {
        // L24: the multipart is parsed lazily, after the token was checked. The request says 20 MiB and sends the
        // part's head and 16 KiB of it: the 401 comes without the rest, and no part was written to the disk.
        File temp = (File) servletContext.getAttribute(ServletContext.TEMPDIR);
        Set<String> before = uploads(temp);
        for (String authorization : new String[] { null, "Bearer tfr_not-a-token" }) {
            // Plain HTTP to the test server on the loopback, as the test's client speaks it: nothing crosses a network.
            try (Socket socket = new Socket("localhost", port)) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
                socket.setSoTimeout(10_000);
                String boundary = "tinfirm-" + UUID.randomUUID();
                OutputStream out = socket.getOutputStream();
                out.write(("POST /api/runner/v1/runs/" + UUID.randomUUID() + "/attachments?epoch=1&artifact=true "
                        + "HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=" + boundary
                        + "\r\nContent-Length: " + (20 * 1024 * 1024) + "\r\n"
                        + (authorization != null ? "Authorization: " + authorization + "\r\n" : "") + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.bin\""
                        + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(new byte[16 * 1024]);
                out.flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.US_ASCII));
                assertThat(in.readLine()).as("the status line, without the rest of the body")
                    .startsWith("HTTP/1.1 401");
                assertThat(uploads(temp)).as("no part on the disk").isEqualTo(before);
            }
        }
        assertThat(uploads(temp)).isEqualTo(before);
    }

    /** The files a multipart parse leaves in the container's temporary directory while its request lasts. */
    private static Set<String> uploads(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("upload_"));
        return files == null ? Set.of() : Arrays.stream(files).map(File::getName).collect(Collectors.toSet());
    }

    @Test
    void aSavedFileIsADocumentAtOnceAndAFileOfOutputBecomesOneWhenItsTurnEnds() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        RunnerHostRef host = host(bench);
        UUID runId = running(bench, conversation, host, 1, 1);

        // save_artifact with a file: an attachment of the agent and a DOCUMENT output.
        HttpResponse<String> saved = hostUpload(host.token(), runId, 1, "&artifact=true&source=save_artifact",
                "report.md", "text/markdown", REPORT);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        JsonNode answer = json(saved);
        assertThat(answer.get("status").asString()).isEqualTo("STORED");
        assertThat(answer.get("fileName").asString()).isEqualTo("report.md");
        assertThat(answer.get("message").asString()).startsWith("Saved `report.md` as an output of the conversation");
        UUID reportId = UUID.fromString(answer.get("attachmentId").asString());
        UUID reportOutput = UUID.fromString(answer.get("artifactId").asString());

        // A file of /output keeps its path, and is no output yet.
        HttpResponse<String> left = hostUpload(host.token(), runId, 1, "&artifact=true&source=output",
                "reports/q3 summary.md", "text/markdown", REPORT);
        JsonNode leftAnswer = json(left);
        assertThat(leftAnswer.get("status").asString()).as(left.body()).isEqualTo("STORED");
        assertThat(leftAnswer.get("fileName").asString()).isEqualTo("reports/q3 summary.md");
        assertThat(leftAnswer.has("artifactId")).isFalse();
        UUID q3Id = UUID.fromString(leftAnswer.get("attachmentId").asString());
        // A file that holds a credential, a path that leaves the folder, a name taken: refused, for the agent.
        createSecret(bench.owner(), bench.companyId(), "DEPLOY_KEY", "test-deploy-key-value-of-the-vault");
        JsonNode leaked = json(hostUpload(host.token(), runId, 1, "&artifact=true&source=output", "notes.txt",
                "text/plain", "key test-deploy-key-value-of-the-vault".getBytes(StandardCharsets.UTF_8)));
        assertThat(leaked.get("status").asString()).isEqualTo("REJECTED");
        assertThat(leaked.get("code").asString()).isEqualTo("CREDENTIAL_FOUND");
        assertThat(leaked.toString()).doesNotContain("test-deploy-key-value-of-the-vault");
        JsonNode escaping = json(hostUpload(host.token(), runId, 1, "&artifact=true&source=output", "../up.md",
                "text/markdown", REPORT));
        assertThat(escaping.get("code").asString()).isEqualTo("INVALID_REQUEST");
        JsonNode taken = json(hostUpload(host.token(), runId, 1, "&artifact=true&source=save_artifact", "report.md",
                "text/markdown", REPORT));
        assertThat(taken.get("code").asString()).isEqualTo("DUPLICATE_ATTACHMENT_NAME");
        // Another epoch, another company's host: not found.
        assertThat(hostUpload(host.token(), runId, 2, "&artifact=true", "x.md", "text/markdown", REPORT)
            .statusCode()).isEqualTo(404);
        Bench elsewhere = bench();
        RunnerHostRef foreign = host(elsewhere);
        assertThat(hostUpload(foreign.token(), runId, 1, "&artifact=true", "x.md", "text/markdown", REPORT)
            .statusCode()).isEqualTo(404);
        assertThat(hostDownload(foreign.token(), runId, reportId, 1).statusCode()).isEqualTo(404);

        // The files are the conversation's, the agent's, and the document is an output it keeps.
        String path = files(bench, conversation);
        List<JsonNode> listed = list(bench.person().get(path).json());
        assertThat(listed).extracting(file -> file.get("fileName").asString())
            .containsExactly("report.md", "reports/q3 summary.md");
        assertThat(listed.get(0).get("agentId").asString()).isEqualTo(bench.agentId().toString());
        assertThat(listed.get(0).get("runId").asString()).isEqualTo(runId.toString());
        assertThat(listed.get(0).get("uploadedBy").asString()).isEqualTo(bench.agentName());
        assertThat(listed.get(0).get("outputId").asString()).isEqualTo(reportOutput.toString());
        assertThat(listed.get(1).has("outputId")).isFalse();
        ApiResponse kept = bench.person().delete(path + "/" + reportId);
        assertThat(kept.status()).isEqualTo(409);
        assertThat(kept.problemCode()).isEqualTo("ATTACHMENT_IS_OUTPUT");

        // The turn ends: the host lists the file of /output, and one that is not its own, which is skipped.
        UUID personsFile = UUID.fromString(bench.person().upload(path, new ApiClient.Multipart("person.txt",
                "text/plain", REPORT)).json().get("id").asString());
        TestRuns.ended(bench.companyId(), runId);
        TurnEnded.TurnEnd end = end(bench, conversation, runId, List.of(
                new TurnEnded.OutputFile(q3Id, "reports/q3 summary.md", REPORT.length),
                new TurnEnded.OutputFile(personsFile, "person.txt", REPORT.length),
                new TurnEnded.OutputFile(reportId, "report.md", REPORT.length)));
        recordEnd(bench, conversation, end);
        recordEnd(bench, conversation, end);
        List<ConversationOutputs.OutputInfo> produced = inCompany(bench,
                () -> outputs.outputs(bench.companyId(), conversation.id()));
        assertThat(produced).extracting(ConversationOutputs.OutputInfo::kind, ConversationOutputs.OutputInfo::title,
                ConversationOutputs.OutputInfo::attachmentId)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(ConversationOutputs.Kind.DOCUMENT, "report.md",
                    reportId),
                    org.assertj.core.groups.Tuple.tuple(ConversationOutputs.Kind.DOCUMENT, "reports/q3 summary.md",
                            q3Id));
        assertThat(produced).allSatisfy(output -> assertThat(output.runId()).isEqualTo(runId));
        assertThat(bench.person().delete(path + "/" + q3Id).problemCode()).isEqualTo("ATTACHMENT_IS_OUTPUT");
        assertThat(bench.person().delete(path + "/" + personsFile).status()).isEqualTo(204);

        List<JsonNode> created = auditEvents(bench.owner(), bench.company(), "output.created");
        assertThat(created).hasSize(2).allSatisfy(event -> {
            assertThat(event.get("actor").get("type").asString()).isEqualTo("AGENT");
            assertThat(event.get("actor").get("id").asString()).isEqualTo(bench.agentId().toString());
            assertThat(event.get("details").get("kind").asString()).isEqualTo("DOCUMENT");
            assertThat(event.get("details").get("runId").asString()).isEqualTo(runId.toString());
        });
        assertThat(created).extracting(event -> event.get("details").get("attachmentId").asString())
            .containsExactlyInAnyOrder(reportId.toString(), q3Id.toString());
        List<JsonNode> uploads = auditEvents(bench.owner(), bench.company(), "attachment.uploaded").stream()
            .filter(event -> event.get("actor").get("type").asString().equals("AGENT"))
            .toList();
        assertThat(uploads).hasSize(2)
            .allSatisfy(event -> assertThat(event.get("details").get("runId").asString()).isEqualTo(runId.toString()));
        assertThat(bench.owner().get(bench.path("/audit-events/verification")).json().get("valid").asBoolean())
            .isTrue();
    }

    @Test
    void aPathOfOutputHasUpTo1024CharactersAsTheProtocolAllows() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        RunnerHostRef host = host(bench);
        UUID runId = running(bench, conversation, host, 1, 1);
        // runner protocol v2's ArtifactResult.name: 1024 characters. In ASCII, and in distinct ideographs of three
        // bytes each in UTF-8, so that the name takes 3 KB, more than a B-tree entry holds (V010 keys it on MD5).
        String ascii = "a".repeat(255) + "/" + "b".repeat(255) + "/" + "c".repeat(255) + "/" + "d".repeat(254) + "/e";
        String wide = ideographs(0, 255) + "/" + ideographs(255, 255) + "/" + ideographs(510, 255) + "/"
                + ideographs(765, 250) + "/" + ideographs(1015, 5);
        assertThat(ascii).hasSize(1024);
        assertThat(wide.codePointCount(0, wide.length())).isEqualTo(1024);
        for (String path : List.of(ascii, wide)) {
            // As the runner host sends the name: its part header takes 2 KB for the first path, 10 KB for the second.
            HttpResponse<String> stored = RunnerFileRequests.uploadAsTheRunnerHost(port, host.token(), runId, 1,
                    "&artifact=true&source=output", path, "text/markdown", REPORT);
            assertThat(stored.statusCode()).as(stored.body()).isEqualTo(200);
            JsonNode answer = json(stored);
            assertThat(answer.get("status").asString()).as(stored.body()).isEqualTo("STORED");
            assertThat(answer.get("fileName").asString()).isEqualTo(path);
            // The same path again is the conversation's already; one character more is refused, for the agent.
            assertThat(json(RunnerFileRequests.uploadAsTheRunnerHost(port, host.token(), runId, 1,
                    "&artifact=true&source=output", path, "text/markdown", REPORT)).get("code").asString())
                .isEqualTo("DUPLICATE_ATTACHMENT_NAME");
            JsonNode longer = json(RunnerFileRequests.uploadAsTheRunnerHost(port, host.token(), runId, 1,
                    "&artifact=true&source=output", path + "x", "text/markdown", REPORT));
            assertThat(longer.get("status").asString()).isEqualTo("REJECTED");
            assertThat(longer.get("code").asString()).isEqualTo("INVALID_REQUEST");
            assertThat(longer.get("message").asString()).contains("at most 1024 characters");
        }
        assertThat(list(bench.person().get(files(bench, conversation)).json()))
            .extracting(file -> file.get("fileName").asString())
            .containsExactly(ascii, wide);
    }

    /** Distinct CJK ideographs, from U+4E00 on. */
    private static String ideographs(int from, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.appendCodePoint(0x4E00 + from + i);
        }
        return text.toString();
    }

    private void recordEnd(Bench bench, Conversation conversation, TurnEnded.TurnEnd end) {
        transactions.runInCompany(bench.companyId(),
                () -> turnEnd.turnEnded(conversationInfo(bench, conversation), end));
    }

    static TurnEnded.TurnEnd end(Bench bench, Conversation conversation, UUID runId,
            List<TurnEnded.OutputFile> files) {
        return new TurnEnded.TurnEnd(bench.companyId(), runId, conversation.id(), bench.agentId(), TurnReason.FIRST,
                1, bench.personPrincipalId(), bench.personPrincipalId(), ModelAccessChoice.BUDGET, RunStatus.SUCCEEDED,
                null, null, "Done.", null, null, List.of(), files, null);
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new java.util.ArrayList<>();
        array.forEach(items::add);
        return items;
    }
}
