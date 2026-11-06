package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.TestConversations;

import tools.jackson.databind.JsonNode;

/**
 * A conversation's files (the package 3 contract, §3.13, §5.10, §6, §7; ADR-0014): its person adds, lists, downloads
 * and deletes them; OWNER reads them but does not act ({@code CONVERSATION_BOUND}); ADMIN and any other member get
 * 404; the limits per conversation (the test profile's: 64 KB a file, 5 files, 160 KB); the scan at upload; an ended
 * conversation takes no file.
 */
class AttachmentIntegrationTest extends OutputTestBench {

    private static final byte[] TEXT = "Line 1\nŽluťoučký kůň\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void thePersonAddsListsDownloadsAndDeletesFilesOwnerReadsThemAndNobodyElseSeesThem() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        String path = files(bench, conversation);
        ApiResponse uploaded = bench.person().upload(path, new ApiClient.Multipart("C:\\Users\\me\\návrh v2.txt",
                "text/plain; charset=utf-8", TEXT, Map.of("note", " the draft ")));
        assertThat(uploaded.status()).as("upload: %s", uploaded).isEqualTo(201);
        JsonNode attachment = uploaded.json();
        String id = attachment.get("id").asString();
        assertThat(attachment.get("conversationId").asString()).isEqualTo(conversation.id().toString());
        assertThat(attachment.get("fileName").asString()).isEqualTo("návrh v2.txt");
        assertThat(attachment.get("contentType").asString()).isEqualTo("text/plain");
        assertThat(attachment.get("sizeBytes").asLong()).isEqualTo(TEXT.length);
        assertThat(attachment.get("sha256").asString()).isEqualTo(AttachmentService.sha256(TEXT));
        assertThat(attachment.get("note").asString()).isEqualTo("the draft");
        assertThat(attachment.get("uploadedBy").asString()).isEqualTo("User " + bench.company().email("MEMBER"));
        assertThat(attachment.get("uploadedByUserId").asString())
            .isEqualTo(bench.company().userId("MEMBER").toString());
        assertThat(attachment.has("outputId")).isFalse();
        assertThat(attachment.has("agentId")).isFalse();

        // Its person and OWNER read the files.
        for (ApiClient reader : List.of(bench.person(), bench.owner())) {
            assertThat(reader.get(path).json()).hasSize(1);
            HttpResponse<byte[]> download = reader.download(path + "/" + id);
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo(TEXT);
            assertThat(download.headers().firstValue("Content-Type")).hasValue("text/plain");
            assertThat(download.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;")
                .contains("filename*=UTF-8''n%C3%A1vrh%20v2.txt");
            assertThat(download.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        }
        // ADMIN, who is not OWNER, and any other member do not see a personal conversation (I17): 404 everywhere.
        ApiClient admin = loginAs(bench.company().email("ADMIN"));
        assertThat(admin.get(path).status()).isEqualTo(404);
        assertThat(admin.download(path + "/" + id).statusCode()).isEqualTo(404);
        assertThat(admin.upload(path, new ApiClient.Multipart("admin.txt", "text/plain", TEXT)).status())
            .isEqualTo(404);
        assertThat(admin.delete(path + "/" + id).status()).isEqualTo(404);
        // OWNER sees it, but only its person adds and deletes its files (I15).
        ApiResponse ownerUpload = bench.owner().upload(path, new ApiClient.Multipart("owner.txt", "text/plain",
                TEXT));
        assertThat(ownerUpload.status()).isEqualTo(409);
        assertThat(ownerUpload.problemCode()).isEqualTo("CONVERSATION_BOUND");
        assertThat(bench.owner().delete(path + "/" + id).problemCode()).isEqualTo("CONVERSATION_BOUND");
        // Names: the last segment of what the browser sent; control characters, "." and ".." are refused.
        assertThat(bench.person().upload(path, new ApiClient.Multipart("bad\u0007name.txt", "text/plain", TEXT))
            .status()).isEqualTo(400);
        assertThat(bench.person().upload(path, new ApiClient.Multipart("..", "text/plain", TEXT)).status())
            .isEqualTo(400);
        ApiResponse duplicate = bench.person().upload(path, new ApiClient.Multipart("návrh v2.txt", "text/plain",
                "other".getBytes(StandardCharsets.UTF_8)));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.problemCode()).isEqualTo("DUPLICATE_ATTACHMENT_NAME");
        // An unknown file, or one of another conversation, is not found.
        Conversation other = conversation(bench);
        assertThat(bench.person().download(files(bench, other) + "/" + id).statusCode()).isEqualTo(404);
        assertThat(bench.person().delete(files(bench, other) + "/" + id).status()).isEqualTo(404);

        assertThat(bench.person().delete(path + "/" + id).status()).isEqualTo(204);
        assertThat(bench.person().delete(path + "/" + id).status()).isEqualTo(404);
        assertThat(bench.owner().download(path + "/" + id).statusCode()).isEqualTo(404);
        List<JsonNode> events = auditEvents(bench.owner(), bench.company()).stream()
            .filter(event -> event.get("action").asString().startsWith("attachment."))
            .toList();
        assertThat(events).extracting(event -> event.get("action").asString())
            .containsExactly("attachment.deleted", "attachment.uploaded");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.get("actor").get("type").asString()).isEqualTo("USER");
            assertThat(event.get("details").get("conversationId").asString())
                .isEqualTo(conversation.id().toString());
        });
        assertThat(events.get(1).get("details").get("scanned").asBoolean()).isTrue();
        assertThat(events.get(1).get("details").get("sizeBytes").asLong()).isEqualTo(TEXT.length);
        assertThat(events.toString()).doesNotContain("návrh").doesNotContain("n\\u00E1vrh");
    }

    @Test
    void theLimitsPerFileCountAndSizeHold() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        String path = files(bench, conversation);
        byte[] tooLarge = new byte[64 * 1024 + 1];
        ApiResponse large = bench.person().upload(path, new ApiClient.Multipart("large.bin",
                "application/octet-stream", tooLarge));
        assertThat(large.status()).isEqualTo(413);
        assertThat(large.problemCode()).isEqualTo("ATTACHMENT_TOO_LARGE");
        for (int i = 1; i <= 5; i++) {
            assertThat(bench.person().upload(path, new ApiClient.Multipart("file-" + i + ".txt", "text/plain", TEXT))
                .status()).isEqualTo(201);
        }
        ApiResponse sixth = bench.person().upload(path, new ApiClient.Multipart("file-6.txt", "text/plain", TEXT));
        assertThat(sixth.status()).isEqualTo(409);
        assertThat(sixth.problemCode()).isEqualTo("TOO_MANY_ATTACHMENTS");
        assertThat(bench.person().get(path).json()).hasSize(5);

        // 160 KB in all: two files of 60 KB fit, a third does not.
        Conversation full = conversation(bench);
        String fullPath = files(bench, full);
        byte[] sixty = new byte[60 * 1024];
        Arrays.fill(sixty, (byte) 'a');
        assertThat(bench.person().upload(fullPath, new ApiClient.Multipart("a.txt", "text/plain", sixty)).status())
            .isEqualTo(201);
        assertThat(bench.person().upload(fullPath, new ApiClient.Multipart("b.txt", "text/plain", sixty)).status())
            .isEqualTo(201);
        ApiResponse third = bench.person().upload(fullPath, new ApiClient.Multipart("c.txt", "text/plain", sixty));
        assertThat(third.status()).isEqualTo(409);
        assertThat(third.problemCode()).isEqualTo("TOO_MANY_ATTACHMENTS");
    }

    @Test
    void theScanRefusesVaultValuesAlwaysAndCredentialFormatsUnlessAnOwnerOrAdminPersonAcknowledges()
            throws IOException {
        Bench bench = bench();
        createSecret(bench.owner(), bench.companyId(), "JIRA_TOKEN", "test-jira-token-value-of-the-vault");
        Conversation conversation = conversation(bench);
        String path = files(bench, conversation);
        byte[] withToken = "export JIRA_TOKEN=test-jira-token-value-of-the-vault\n".getBytes(StandardCharsets.UTF_8);
        ApiResponse vaultHit = bench.person().upload(path, new ApiClient.Multipart(".env", "text/plain", withToken,
                Map.of("acknowledgeCredentialMatch", "true")));
        assertThat(vaultHit.status()).isEqualTo(422);
        assertThat(vaultHit.problemCode()).isEqualTo("CREDENTIAL_FOUND");
        assertThat(vaultHit.json().get("credentialMatches").get(0).get("kind").asString()).isEqualTo("VAULT_SECRET");
        assertThat(vaultHit.json().get("credentialMatches").get(0).get("name").asString()).isEqualTo("JIRA_TOKEN");
        assertThat(vaultHit.body()).doesNotContain("test-jira-token-value-of-the-vault");
        // A known format that is not in the vault: the MEMBER person may not store it.
        byte[] withFormat = ("key: ghp_" + "Ab1".repeat(12) + "\n").getBytes(StandardCharsets.UTF_8);
        ApiResponse formatHit = bench.person().upload(path, new ApiClient.Multipart("example.yaml", "text/yaml",
                withFormat));
        assertThat(formatHit.status()).isEqualTo(422);
        assertThat(formatHit.json().get("credentialMatches").get(0).get("kind").asString()).isEqualTo("FORMAT");
        assertThat(bench.person().upload(path, new ApiClient.Multipart("example.yaml", "text/yaml", withFormat,
                Map.of("acknowledgeCredentialMatch", "true"))).status()).isEqualTo(403);
        // OWNER, in a conversation of their own, acknowledges it.
        Conversation owners = conversation(bench, bench.agentId(),
                memberPrincipal(bench.company(), bench.company().userId("OWNER")));
        ApiResponse acknowledged = bench.owner().upload(files(bench, owners), new ApiClient.Multipart("example.yaml",
                "text/yaml", withFormat, Map.of("acknowledgeCredentialMatch", "true")));
        assertThat(acknowledged.status()).as("acknowledged: %s", acknowledged).isEqualTo(201);
        JsonNode event = auditEvents(bench.owner(), bench.company(), "attachment.uploaded").getFirst();
        assertThat(event.get("details").get("acknowledgedCredentialFormats").get(0).asString())
            .isEqualTo("GitHub token");
        // An encrypted zip cannot be scanned.
        byte[] encrypted = zip("secret.txt", "hidden".getBytes(StandardCharsets.UTF_8));
        encrypted[6] |= 1;
        ApiResponse unscannable = bench.person().upload(path, new ApiClient.Multipart("locked.zip", "application/zip",
                encrypted));
        assertThat(unscannable.status()).isEqualTo(422);
        assertThat(unscannable.problemCode()).isEqualTo("ATTACHMENT_NOT_SCANNABLE");
        assertThat(bench.person().get(path).json()).isEmpty();
    }

    @Test
    void anEndedConversationTakesNoFileButKeepsItsOwn() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        String path = files(bench, conversation);
        String id = bench.person().upload(path, new ApiClient.Multipart("kept.txt", "text/plain", TEXT)).json()
            .get("id").asString();
        TestConversations.end(bench.companyId(), conversation.id());
        ApiResponse late = bench.person().upload(path, new ApiClient.Multipart("late.txt", "text/plain", TEXT));
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.problemCode()).isEqualTo("CONVERSATION_ENDED");
        assertThat(bench.person().get(path).json()).hasSize(1);
        assertThat(bench.person().download(path + "/" + id).statusCode()).isEqualTo(200);
        assertThat(bench.person().delete(path + "/" + id).status()).isEqualTo(204);
    }

    private static byte[] zip(String name, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
