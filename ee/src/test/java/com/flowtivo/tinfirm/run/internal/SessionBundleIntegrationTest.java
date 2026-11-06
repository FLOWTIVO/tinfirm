package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.NewTurn;
import com.flowtivo.tinfirm.run.RunPurge;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;

import tools.jackson.databind.JsonNode;

/**
 * Session bundles end to end (ADR-0097), with the filesystem store and fake company hosts that list
 * {@code session.bundle}: a run's host uploads the session folders, the next turn's {@code run.assign} names the
 * bundle of its {@code resumeSessionId}, and its host downloads exactly that; a bundle with a vault secret is refused;
 * a session reported without its bundle leaves none behind; a purge of the conversation's content takes its bundles;
 * the sweeper deletes due bundles and orphaned objects, and leaves the bundle of a conversation that resumed since
 * its lookup. Under an Enterprise key: without one, the server keeps none
 * (ADR-0100).
 */
@ExtendWith(EnterpriseLicence.class)
class SessionBundleIntegrationTest extends RunTestBench {

    private static final Path BUNDLES;

    static {
        try {
            BUNDLES = Files.createTempDirectory("tinfirm-session-bundles");
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @DynamicPropertySource
    static void bundleStore(DynamicPropertyRegistry registry) {
        registry.add("tinfirm.session-bundles.store", () -> "filesystem");
        registry.add("tinfirm.session-bundles.directory", BUNDLES::toString);
    }

    /**
     * The store is one directory for the class, and a test leaves its bundles behind: what is there goes before the
     * next test, so that a count of objects is the test's own, whatever the order the tests run in.
     */
    @BeforeEach
    void emptyTheStore() throws IOException {
        try (Stream<Path> files = Files.list(BUNDLES)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    SessionBundleCleanup cleanup;

    @Autowired
    RunPurge runPurge;

    @Autowired
    SessionBundleStore store;

    @Autowired
    SessionBundleKeys keys;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void aCompanyHostStoresItsRunsBundleAndTheNextTurnsHostRestoresIt() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        try (FakeRunner runner = bundleHost(host)) {
            Turn first = start(bench);
            JsonNode assign = expectAssign(runner, first.runId());
            // Upload only: there is nothing to restore yet.
            assertThat(assign.get("sessionBundle").isObject()).isTrue();
            assertThat(assign.get("sessionBundle").has("sha256")).isFalse();
            long epoch = assign.get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            byte[] bundle = gzip("the session of turn one " + UUID.randomUUID());
            JsonNode stored = upload(host.token(), first.runId(), epoch, "session-1", bundle);
            assertThat(stored.get("status").asString()).as("%s", stored).isEqualTo("STORED");
            assertThat(stored.get("sha256").asString()).isEqualTo(SessionBundlesImpl.sha256(bundle));
            // The store holds ciphertext only.
            List<Path> objects = objects();
            assertThat(objects).hasSize(1);
            assertThat(contains(Files.readAllBytes(objects.getFirst()), bundle)).isFalse();
            runner.send(finished(first, epoch, "session-1", "STORED"));
            awaitRun(bench, first.runId(), "SUCCEEDED");

            UUID secondRun = next(bench, first, 2);
            JsonNode second = expectAssign(runner, secondRun);
            assertThat(second.get("resumeSessionId").asString()).isEqualTo("session-1");
            assertThat(second.get("sessionBundle").get("sha256").asString())
                .isEqualTo(SessionBundlesImpl.sha256(bundle));
            assertThat(second.get("sessionBundle").get("sizeBytes").asLong()).isEqualTo(bundle.length);
            long secondEpoch = second.get("epoch").asLong();
            HttpResponse<byte[]> download = download(host.token(), secondRun, secondEpoch);
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo(bundle);
            assertThat(download.headers().firstValue("X-Content-SHA256")).hasValue(SessionBundlesImpl.sha256(bundle));
            // Only that run's host, with that epoch.
            assertThat(download(host.token(), secondRun, secondEpoch + 1).statusCode()).isEqualTo(404);
            assertThat(download(host.token(), first.runId(), epoch).statusCode()).isEqualTo(404);
            RunnerHostRef other = registerRunnerHost(bench.owner(), bench.companyId(), unique("other"), "COMPANY");
            assertThat(download(other.token(), secondRun, secondEpoch).statusCode()).isEqualTo(404);
            assertThat(download("not-a-token", secondRun, secondEpoch).statusCode()).isEqualTo(401);
            // Nor a host of another company.
            Bench elsewhere = bench(false);
            RunnerHostRef stranger = registerRunnerHost(elsewhere.owner(), elsewhere.companyId(), unique("stranger"),
                    "COMPANY");
            assertThat(download(stranger.token(), secondRun, secondEpoch).statusCode()).isEqualTo(404);
            runner.assertFramesValid();
        }
    }

    @Test
    void aRefusedBundleOrASessionWithoutItsBundleLeavesNoneBehind() throws Exception {
        Bench bench = bench(true);
        String secret = "deploy-" + UUID.randomUUID();
        ApiResponse created = bench.owner().post(bench.path("/secrets"), Map.of("name", "DEPLOY_KEY", "value", secret));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        try (FakeRunner runner = bundleHost(host)) {
            Turn first = start(bench);
            long epoch = expectAssign(runner, first.runId()).get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            assertThat(upload(host.token(), first.runId(), epoch, "session-1", gzip("turn one")).get("status")
                .asString()).isEqualTo("STORED");
            runner.send(finished(first, epoch, "session-1", "STORED"));
            awaitRun(bench, first.runId(), "SUCCEEDED");
            assertThat(bundleRows(bench)).isEqualTo(1);

            // The agent left the company's secret in its session: refused, and the earlier bundle goes too.
            UUID secondRun = next(bench, first, 2);
            long secondEpoch = expectAssign(runner, secondRun).get("epoch").asLong();
            runner.accepted(secondRun, secondEpoch);
            awaitRun(bench, secondRun, "RUNNING");
            JsonNode refused = upload(host.token(), secondRun, secondEpoch, "session-1",
                    gzip("echo " + secret + " > deploy.sh"));
            assertThat(refused.get("status").asString()).isEqualTo("REFUSED");
            assertThat(refused.get("message").asString()).contains("DEPLOY_KEY").doesNotContain(secret);
            assertThat(bundleRows(bench)).isZero();
            await().atMost(WAIT).until(() -> objects().isEmpty());
            runner.send(finished(new Turn(first.conversationId(), first.key(), secondRun), secondEpoch, "session-1",
                    "REFUSED"));
            awaitRun(bench, secondRun, "SUCCEEDED");

            // Stored again, then a turn that reports the session without its bundle drops it.
            UUID thirdRun = next(bench, first, 3);
            JsonNode third = expectAssign(runner, thirdRun);
            assertThat(third.get("sessionBundle").has("sha256")).isFalse();
            long thirdEpoch = third.get("epoch").asLong();
            runner.accepted(thirdRun, thirdEpoch);
            awaitRun(bench, thirdRun, "RUNNING");
            assertThat(upload(host.token(), thirdRun, thirdEpoch, "session-1", gzip("turn three")).get("status")
                .asString()).isEqualTo("STORED");
            runner.send(finished(new Turn(first.conversationId(), first.key(), thirdRun), thirdEpoch, "session-1",
                    "STORED"));
            awaitRun(bench, thirdRun, "SUCCEEDED");
            assertThat(bundleRows(bench)).isEqualTo(1);
            UUID fourthRun = next(bench, first, 4);
            long fourthEpoch = expectAssign(runner, fourthRun).get("epoch").asLong();
            runner.accepted(fourthRun, fourthEpoch);
            awaitRun(bench, fourthRun, "RUNNING");
            runner.send(finished(new Turn(first.conversationId(), first.key(), fourthRun), fourthEpoch, "session-1",
                    "TOO_LARGE"));
            awaitRun(bench, fourthRun, "SUCCEEDED");
            assertThat(bundleRows(bench)).isZero();
            await().atMost(WAIT).until(() -> objects().isEmpty());
        }
    }

    /**
     * L24: a host stores one bundle at a time, and only its own uploads wait for it. Four uploads of one host that
     * send their body slowly hold up neither another host's upload nor its scan.
     */
    @Test
    void aSlowHostHoldsUpOnlyItsOwnUploads() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef slow = registerRunnerHost(bench.owner(), bench.companyId(), unique("slow"), "COMPANY");
        RunnerHostRef quick = registerRunnerHost(bench.owner(), bench.companyId(), unique("quick"), "COMPANY");
        List<Socket> stalled = new ArrayList<>();
        try (FakeRunner slowRunner = bundleHost(slow)) {
            for (int i = 0; i < 4; i++) {
                Turn turn = start(bench);
                long epoch = expectAssign(slowRunner, turn.runId()).get("epoch").asLong();
                slowRunner.accepted(turn.runId(), epoch);
                awaitRun(bench, turn.runId(), "RUNNING");
                stalled.add(stalledUpload(slow.token(), turn.runId(), epoch));
            }
            try (FakeRunner quickRunner = bundleHost(quick)) {
                Turn turn = start(bench);
                long epoch = expectAssign(quickRunner, turn.runId()).get("epoch").asLong();
                quickRunner.accepted(turn.runId(), epoch);
                awaitRun(bench, turn.runId(), "RUNNING");
                byte[] bundle = gzip("the quick host's session " + UUID.randomUUID());
                HttpRequest request = HttpRequest
                    .newBuilder(URI.create("http://localhost:" + port + "/api/runner/v1/runs/" + turn.runId()
                            + "/session-bundle?epoch=" + epoch + "&sessionId=session-q"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + quick.token())
                    .header("Content-Type", "application/gzip")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bundle))
                    .build();
                HttpResponse<String> stored = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(stored.statusCode()).as(stored.body()).isEqualTo(200);
                assertThat(JSON.readTree(stored.body()).get("status").asString()).isEqualTo("STORED");
            }
        }
        finally {
            for (Socket socket : stalled) {
                socket.close();
            }
        }
    }

    /** I8: a member's machine is placed in the run's company as its presence, which finds no bundle: 404, not 500. */
    @Test
    void aPersonalHostsTokenFindsNoBundle() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        RunnerHostRef personal = registerRunnerHost(bench.owner(), bench.companyId(), unique("mine"), "PERSONAL");
        try (FakeRunner runner = bundleHost(host)) {
            Turn first = start(bench);
            long epoch = expectAssign(runner, first.runId()).get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            assertThat(upload(host.token(), first.runId(), epoch, "session-1", gzip("turn one")).get("status")
                .asString()).isEqualTo("STORED");

            assertThat(download(personal.token(), first.runId(), epoch).statusCode()).isEqualTo(404);
            assertThat(send(personal.token(), first.runId(), epoch, "session-1", gzip("mine")).statusCode())
                .isEqualTo(404);
            assertThat(download(personal.token(), UUID.randomUUID(), epoch).statusCode()).isEqualTo(404);
            assertThat(send(personal.token(), UUID.randomUUID(), epoch, "session-1", gzip("mine")).statusCode())
                .isEqualTo(404);
            assertThat(bundleRows(bench)).isEqualTo(1);
            runner.assertFramesValid();
        }
    }

    /** An upload that says 1 MiB and sends 16 bytes of it, left open: its request waits for the rest. */
    private Socket stalledUpload(String token, UUID runId, long epoch) throws IOException {
        // Plain HTTP to the test server on the loopback, as the test's client speaks it: nothing crosses a network.
        Socket socket = new Socket("localhost", port); // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        OutputStream out = socket.getOutputStream();
        out.write(("POST /api/runner/v1/runs/" + runId + "/session-bundle?epoch=" + epoch + "&sessionId=session-s "
                + "HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + token + "\r\nContent-Type: "
                + "application/gzip\r\nContent-Length: 1048576\r\n\r\n0123456789abcdef")
            .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return socket;
    }

    /** A host's {@code STORED} is not trusted without its upload: the earlier bundle goes all the same. */
    @Test
    void aStoredReportWithoutItsUploadLeavesNoEarlierBundle() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        try (FakeRunner runner = bundleHost(host)) {
            Turn first = start(bench);
            long epoch = expectAssign(runner, first.runId()).get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            assertThat(upload(host.token(), first.runId(), epoch, "session-1", gzip("turn one")).get("status")
                .asString()).isEqualTo("STORED");
            runner.send(finished(first, epoch, "session-1", "STORED"));
            awaitRun(bench, first.runId(), "SUCCEEDED");
            assertThat(bundleRows(bench)).isEqualTo(1);

            UUID secondRun = next(bench, first, 2);
            long secondEpoch = expectAssign(runner, secondRun).get("epoch").asLong();
            runner.accepted(secondRun, secondEpoch);
            awaitRun(bench, secondRun, "RUNNING");
            runner.send(finished(new Turn(first.conversationId(), first.key(), secondRun), secondEpoch, "session-1",
                    "STORED"));
            awaitRun(bench, secondRun, "SUCCEEDED");
            assertThat(bundleRows(bench)).isZero();
            await().atMost(WAIT).until(() -> objects().isEmpty());
        }
    }

    /** A host gets only the bundle its {@code run.assign} named: of the session the run resumes. */
    @Test
    void aBundleOfAnotherSessionIsNotServed() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        Turn first;
        try (FakeRunner runner = bundleHost(host)) {
            first = start(bench);
            long epoch = expectAssign(runner, first.runId()).get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            assertThat(upload(host.token(), first.runId(), epoch, "session-1", gzip("turn one")).get("status")
                .asString()).isEqualTo("STORED");
            // The run ends on another session than the one it stored.
            runner.send(finished(first, epoch, "session-2", "STORED"));
            awaitRun(bench, first.runId(), "SUCCEEDED");
            assertThat(bundleRows(bench)).isEqualTo(1);

            UUID secondRun = next(bench, first, 2);
            JsonNode second = expectAssign(runner, secondRun);
            assertThat(second.get("resumeSessionId").asString()).isEqualTo("session-2");
            assertThat(second.get("sessionBundle").has("sha256")).isFalse();
            assertThat(download(host.token(), secondRun, second.get("epoch").asLong()).statusCode()).isEqualTo(404);
            runner.assertFramesValid();
        }
        // The other tests count the store's objects.
        transactions.runInCompany(bench.companyId(), () -> cleanup.purged(first.conversationId()));
        await().atMost(WAIT).until(() -> objects().isEmpty());
    }

    /**
     * ADR-0101: an upload streams through the scan and into the store a chunk at a time, so a bundle of several chunks
     * (and of a full last one) comes back whole, and the store keeps no partial object.
     */
    @Test
    void aBundleOfSeveralChunksStreamsInAndComesBackWhole() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        Turn first;
        try (FakeRunner runner = bundleHost(host)) {
            first = start(bench);
            long epoch = expectAssign(runner, first.runId()).get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            // Incompressible content, so that the gzip spans several of the cipher's chunks.
            byte[] noise = new byte[3 * BundleCipher.CHUNK_BYTES + 12_345];
            new java.security.SecureRandom().nextBytes(noise);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(noise);
            }
            byte[] bundle = out.toByteArray();
            JsonNode stored = upload(host.token(), first.runId(), epoch, "session-1", bundle);
            assertThat(stored.get("status").asString()).as("%s", stored).isEqualTo("STORED");
            assertThat(stored.get("sha256").asString()).isEqualTo(SessionBundlesImpl.sha256(bundle));
            try (Stream<Path> files = Files.list(BUNDLES)) {
                assertThat(files.filter(f -> f.getFileName().toString().endsWith(".tmp"))).isEmpty();
            }
            runner.send(finished(first, epoch, "session-1", "STORED"));
            awaitRun(bench, first.runId(), "SUCCEEDED");

            UUID secondRun = next(bench, first, 2);
            JsonNode second = expectAssign(runner, secondRun);
            assertThat(second.get("sessionBundle").get("sizeBytes").asLong()).isEqualTo(bundle.length);
            HttpResponse<byte[]> download = download(host.token(), secondRun, second.get("epoch").asLong());
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo(bundle);
        }
        purge(bench, first);
    }

    /**
     * ADR-0101: the vault's rewrap covers a bundle's data key: it is wrapped again, in the SYSTEM context, and the
     * bundle still opens. The rewrap here is asked to move every key off {@code test-k2}, so that it rewraps keys that
     * are under the active {@code test-k1} already.
     */
    @Test
    void theVaultsRewrapCoversTheBundlesDataKeys() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        Turn first;
        try (FakeRunner runner = bundleHost(host)) {
            first = start(bench);
            long epoch = expectAssign(runner, first.runId()).get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            byte[] bundle = gzip("the session to rewrap " + UUID.randomUUID());
            assertThat(upload(host.token(), first.runId(), epoch, "session-1", bundle).get("status").asString())
                .isEqualTo("STORED");
            runner.send(finished(first, epoch, "session-1", "STORED"));
            awaitRun(bench, first.runId(), "SUCCEEDED");
            Object before = query(bench.companyId(), "SELECT encode(wrapped_key, 'hex') FROM session_bundle");
            assertThat(query(bench.companyId(), "SELECT key_id FROM session_bundle")).isEqualTo("test-k1");

            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            List<String> otherThanK2 = SystemContext.call(() -> tx.execute(status -> keys.keyIdsOtherThan("test-k2")));
            assertThat(otherThanK2).contains("test-k1");
            Integer rewrapped = SystemContext.call(() -> tx.execute(status -> keys.rewrapBatch("test-k2", 1000)));
            assertThat(rewrapped).isPositive();
            assertThat(query(bench.companyId(), "SELECT encode(wrapped_key, 'hex') FROM session_bundle"))
                .isNotEqualTo(before);
            assertThat(query(bench.companyId(), "SELECT key_id FROM session_bundle")).isEqualTo("test-k1");
            // Nothing to do when every key is under the active one.
            List<String> otherThanK1 = SystemContext.call(() -> tx.execute(status -> keys.keyIdsOtherThan("test-k1")));
            assertThat(otherThanK1).isEmpty();

            UUID secondRun = next(bench, first, 2);
            JsonNode second = expectAssign(runner, secondRun);
            HttpResponse<byte[]> download = download(host.token(), secondRun, second.get("epoch").asLong());
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo(bundle);
        }
        purge(bench, first);
    }

    /** Purges the conversation's content, and waits until its bundle's object is gone: other tests keep theirs. */
    private void purge(Bench bench, Turn turn) {
        UUID object = (UUID) query(bench.companyId(), "SELECT object_name FROM session_bundle WHERE conversation_id = ?",
                turn.conversationId());
        transactions.runInCompany(bench.companyId(), () -> cleanup.purged(turn.conversationId()));
        await().atMost(WAIT).until(() -> !Files.exists(BUNDLES.resolve(object.toString())));
    }

    /** ADR-0100: without elastic runners in the licence, no run gets a bundle and the host's endpoints say why. */
    @Test
    void withoutElasticRunnersTheServerKeepsNoBundles() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        TestLicences.clear();
        try (FakeRunner runner = bundleHost(host)) {
            Turn first = start(bench);
            JsonNode assign = expectAssign(runner, first.runId());
            assertThat(assign.has("sessionBundle")).isFalse();
            long epoch = assign.get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            HttpResponse<String> refused = send(host.token(), first.runId(), epoch, "session-1", gzip("turn one"));
            assertThat(refused.statusCode()).isEqualTo(403);
            assertThat(refused.body()).contains("EDITION_REQUIRED", "ELASTIC_RUNNERS");
            assertThat(download(host.token(), first.runId(), epoch).statusCode()).isEqualTo(403);
            assertThat(download("not-a-token", first.runId(), epoch).statusCode()).isEqualTo(401);
            assertThat(bundleRows(bench)).isZero();
            runner.assertFramesValid();
        }
        finally {
            LocalDate today = TestLicences.today();
            TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.plusYears(1));
        }
    }

    @Test
    void aHostWithoutTheFeatureGetsNoSessionBundle() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 2)) {
            Turn first = start(bench);
            JsonNode assign = expectAssign(runner, first.runId());
            assertThat(assign.has("sessionBundle")).isFalse();
            runner.assertFramesValid();
        }
    }

    @Test
    void theSweeperDeletesTheDueBundlesAndTheOrphanedObjects() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        Turn first;
        try (FakeRunner runner = bundleHost(host)) {
            first = start(bench);
            long epoch = expectAssign(runner, first.runId()).get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            upload(host.token(), first.runId(), epoch, "session-1", gzip("turn one"));
            runner.send(finished(first, epoch, "session-1", "STORED"));
            awaitRun(bench, first.runId(), "SUCCEEDED");
        }
        Path due = BUNDLES.resolve(query(bench.companyId(), "SELECT object_name FROM session_bundle WHERE "
                + "conversation_id = ?", first.conversationId()).toString());
        assertThat(due).exists();
        // An object of an upload that never got its row, old enough; and one of an upload in flight.
        Path orphan = BUNDLES.resolve(UUID.randomUUID().toString());
        Files.write(orphan, new byte[] { 1 });
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        Path inFlight = BUNDLES.resolve(UUID.randomUUID().toString());
        Files.write(inFlight, new byte[] { 2 });
        // The conversation took no turn for longer than the retention.
        update(bench.companyId(), "UPDATE conversation SET last_turn_at = now() - interval '40 days' WHERE id = ?",
                first.conversationId());

        assertThat(cleanup.sweep()).isGreaterThanOrEqualTo(2);
        assertThat(bundleRows(bench)).isZero();
        assertThat(due).doesNotExist();
        assertThat(orphan).doesNotExist();
        assertThat(inFlight).exists();
        Files.delete(inFlight);
    }

    @Test
    void theSweeperLeavesTheBundleOfAConversationThatResumedSinceItsLookup() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        Turn first;
        try (FakeRunner runner = bundleHost(host)) {
            first = start(bench);
            long epoch = expectAssign(runner, first.runId()).get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            upload(host.token(), first.runId(), epoch, "session-1", gzip("turn one"));
            runner.send(finished(first, epoch, "session-1", "STORED"));
            awaitRun(bench, first.runId(), "SUCCEEDED");
        }
        UUID conversation = first.conversationId();
        UUID objectName = (UUID) query(bench.companyId(),
                "SELECT object_name FROM session_bundle WHERE conversation_id = ?", conversation);
        Path object = BUNDLES.resolve(objectName.toString());
        assertThat(object).exists();
        update(bench.companyId(), "UPDATE conversation SET last_turn_at = now() - interval '40 days' WHERE id = ?",
                conversation);
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        List<SessionBundleStore.Due> due = transactions.unscoped(() -> store.due(cutoff, 200));
        assertThat(due).contains(new SessionBundleStore.Due(bench.companyId(), conversation, bench.agentId(),
                objectName));

        // Between the lookup and the delete the conversation took a turn: its bundle stays, row and object.
        update(bench.companyId(), "UPDATE conversation SET last_turn_at = now() WHERE id = ?", conversation);
        assertThat(transactions.inCompany(bench.companyId(),
                () -> store.deleteDue(conversation, bench.agentId(), objectName, cutoff))).isEmpty();
        assertThat(bundleRows(bench)).isEqualTo(1);
        assertThat(object).exists();

        // The turn's run replaced the bundle with a new object (SessionBundleRows.replace: delete, then insert) and
        // the conversation fell due again: the lookup's stale object name deletes nothing, and the new object stays.
        UUID replacement = UUID.randomUUID();
        Path replaced = BUNDLES.resolve(replacement.toString());
        Files.write(replaced, new byte[] { 3 });
        update(bench.companyId(), """
                WITH old AS (DELETE FROM session_bundle WHERE conversation_id = ? AND agent_id = ? RETURNING *)
                INSERT INTO session_bundle (company_id, conversation_id, agent_id, runtime, session_id, object_name,
                                            size_bytes, sha256, key_id, wrapped_key, key_nonce, run_id, created_at)
                SELECT company_id, conversation_id, agent_id, runtime, session_id, ?, size_bytes, sha256, key_id,
                       wrapped_key, key_nonce, run_id, created_at
                  FROM old""", conversation, bench.agentId(), replacement);
        update(bench.companyId(), "UPDATE conversation SET last_turn_at = now() - interval '40 days' WHERE id = ?",
                conversation);
        assertThat(transactions.inCompany(bench.companyId(),
                () -> store.deleteDue(conversation, bench.agentId(), objectName, cutoff))).isEmpty();
        assertThat(bundleRows(bench)).isEqualTo(1);
        assertThat(query(bench.companyId(), "SELECT object_name FROM session_bundle WHERE conversation_id = ?",
                conversation)).isEqualTo(replacement);
        assertThat(replaced).exists();

        // An ended conversation's bundle goes whatever its last turn: the sweeper's lookup and delete agree.
        update(bench.companyId(), "UPDATE conversation SET last_turn_at = now() WHERE id = ?", conversation);
        assertThat(bench.owner().post(bench.path("/conversations/" + conversation + "/end"), null).status())
            .isEqualTo(200);
        assertThat(cleanup.sweep()).isGreaterThanOrEqualTo(1);
        assertThat(bundleRows(bench)).isZero();
        assertThat(replaced).doesNotExist();
    }

    @Test
    void aPurgeOfTheConversationsContentDeletesItsBundles() throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        Turn first;
        try (FakeRunner runner = bundleHost(host)) {
            first = start(bench);
            long epoch = expectAssign(runner, first.runId()).get("epoch").asLong();
            runner.accepted(first.runId(), epoch);
            awaitRun(bench, first.runId(), "RUNNING");
            upload(host.token(), first.runId(), epoch, "session-1", gzip("turn one"));
            runner.send(finished(first, epoch, "session-1", "STORED"));
            awaitRun(bench, first.runId(), "SUCCEEDED");
        }
        Path object = BUNDLES.resolve(query(bench.companyId(), "SELECT object_name FROM session_bundle WHERE "
                + "conversation_id = ?", first.conversationId()).toString());
        assertThat(object).exists();

        // Retention's purge of the conversation's content, or its erasure: the session holds the same words.
        transactions.runInCompany(bench.companyId(),
                () -> runPurge.purgeTextsOf(first.conversationId(), Instant.now()));

        assertThat(bundleRows(bench)).isZero();
        assertThat(object).doesNotExist();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** A company host whose {@code hello} lists {@code session.bundle}. */
    private FakeRunner bundleHost(RunnerHostRef host) {
        FakeRunner runner = FakeRunner.connect(port, host.token());
        Map<String, Object> hello = FakeRunner.helloV2(List.of(), List.of(), GATEWAY_VERSION, 4);
        hello.put("features", List.of("session.bundle"));
        runner.send(hello);
        runner.expect("welcome");
        awaitConfigured(host.id());
        return runner;
    }

    /** The conversation's next turn, as a message of its person starts it: its run. */
    private UUID next(Bench bench, Turn first, int number) {
        return transactions.inCompany(bench.companyId(), () -> turns.start(new NewTurn(bench.companyId(),
                first.conversationId(), first.key(), bench.agentId(), RuntimeKind.CLAUDE_CODE, TurnReason.MESSAGE,
                number, bench.personPrincipalId(), bench.personPrincipalId(), ModelAccessChoice.BUDGET, null)))
            .orElseThrow(() -> new AssertionError("The turn did not start"));
    }

    private static Map<String, Object> finished(Turn turn, long epoch, String sessionId, String bundle) {
        Map<String, Object> finished = new HashMap<>(RunTestBench.finished(turn.runId(), epoch, "SUCCEEDED", 0,
                "done", sessionId));
        finished.put("sessionBundle", bundle);
        return finished;
    }

    private long bundleRows(Bench bench) {
        return ((Number) query(bench.companyId(), "SELECT count(*) FROM session_bundle")).longValue();
    }

    private static List<Path> objects() throws IOException {
        try (Stream<Path> files = Files.list(BUNDLES)) {
            return files.filter(file -> file.getFileName().toString().length() == 36).toList();
        }
    }

    private JsonNode upload(String token, UUID runId, long epoch, String sessionId, byte[] bundle) throws Exception {
        HttpResponse<String> response = send(token, runId, epoch, sessionId, bundle);
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String token, UUID runId, long epoch, String sessionId, byte[] bundle)
            throws Exception {
        HttpRequest request = HttpRequest
            .newBuilder(URI.create("http://localhost:" + port + "/api/runner/v1/runs/" + runId
                    + "/session-bundle?epoch=" + epoch + "&sessionId=" + sessionId))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/gzip")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bundle))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> download(String token, UUID runId, long epoch) throws Exception {
        HttpRequest request = HttpRequest
            .newBuilder(URI.create("http://localhost:" + port + "/api/runner/v1/runs/" + runId
                    + "/session-bundle?epoch=" + epoch))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
