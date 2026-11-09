package com.flowtivo.tinfirm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The offline tool (ADR-0111, decision 4) against checkpoints in a directory and exports of the chain: an intact chain
 * passes; an event changed and the chain recomputed, the newest events cut, a checkpoint removed or forged, and a
 * checkpoint of a distrusted key after its compromise each fail it with a finding; after a rotation both keys verify.
 */
class AuditAnchorToolTest {

    private static final UUID INSTANCE = UUID.fromString("7f1d2c3b-4a59-4e6f-8a7b-9c0d1e2f3a4b");

    private static final UUID COMPANY = UUID.fromString("3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c");

    @TempDir
    private Path dir;

    private final KeyPair k2026 = AnchorKeys.generate();

    private final KeyPair k2027 = AnchorKeys.generate();

    private Path folder;

    private Path pub2026;

    private Path pub2027;

    private List<StoredAuditEvent> chain;

    @BeforeEach
    void checkpoints() throws IOException {
        folder = dir.resolve("checkpoints").resolve(INSTANCE.toString());
        pub2026 = Files.writeString(dir.resolve("k2026.pub"), AnchorKeys.publicPem(k2026.getPublic()));
        pub2027 = Files.writeString(dir.resolve("k2027.pub"), AnchorKeys.publicPem(k2027.getPublic()));
        chain = AnchorFixtures.chain(COMPANY, 6);
        // Checkpoint 1 by k2026 at seq 2, checkpoint 2 by k2027 (after the rotation) at seq 5.
        FileAnchorSink sink = new FileAnchorSink(dir.resolve("checkpoints"));
        AnchorKeys before = AnchorFixtures.keys("k2026", AnchorFixtures.keyMap("k2026",
                AnchorFixtures.signing(k2026)));
        AnchorKeys after = AnchorFixtures.keys("k2027", AnchorFixtures.keyMap("k2027", AnchorFixtures.signing(k2027),
                "k2026", AnchorFixtures.retired(k2026)));
        Checkpoint first = new Checkpoint(INSTANCE, 1, Instant.parse("2026-11-01T09:00:00Z"), Checkpoint.NO_PREVIOUS,
                "k2026", List.of(AnchorFixtures.head(COMPANY, chain.get(1))));
        Checkpoint second = new Checkpoint(INSTANCE, 2, Instant.parse("2026-11-01T10:00:00Z"), first.digest(),
                "k2027", List.of(AnchorFixtures.head(COMPANY, chain.get(4))));
        sink.write(INSTANCE, 1, first.signedDocument(before));
        sink.write(INSTANCE, 2, second.signedDocument(after));
    }

    private Path export(List<StoredAuditEvent> events) throws IOException {
        Path file = dir.resolve("export-" + UUID.randomUUID() + ".ndjson");
        Files.write(file, AnchorFixtures.export(COMPANY, events), StandardCharsets.UTF_8);
        return file;
    }

    private record Result(int status, String output) {
    }

    private Result run(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int status = AuditAnchorTool.run(args, new PrintStream(bytes, true, StandardCharsets.UTF_8), Map.of());
        return new Result(status, bytes.toString(StandardCharsets.UTF_8));
    }

    private Result verify(Path export, String... more) {
        List<String> args = new ArrayList<>(List.of("verify", "--checkpoints", folder.toString(), "--public-key",
                "k2026=" + pub2026, "--public-key", "k2027=" + pub2027, "--export", export.toString()));
        args.addAll(List.of(more));
        return run(args.toArray(String[]::new));
    }

    @Test
    void anIntactChainVerifiesAgainstCheckpointsOfBothKeysAfterTheRotation() throws IOException {
        Result result = verify(export(chain));

        assertThat(result.status()).as(result.output()).isZero();
        assertThat(result.output()).contains("checkpoints   1..2 read (keys k2026, k2027)")
            .contains("chain " + COMPANY)
            .contains("seq 1..6 OK, anchored to seq 5 (checkpoint 2)")
            .contains("result: OK");
    }

    @Test
    void anEventChangedAndTheChainRecomputedFailsAtTheCheckpointsSeq() throws IOException {
        // Whoever writes the database changes seq 2 and recomputes every hash after it: the chain verifies alone.
        List<StoredAuditEvent> rewritten = new ArrayList<>(chain.subList(0, 1));
        String previous = chain.getFirst().hash();
        for (StoredAuditEvent event : chain.subList(1, chain.size())) {
            StoredAuditEvent changed = AnchorFixtures.event(COMPANY, event.seq(), event.occurredAt(),
                    event.seq() == 2 ? "{\"n\":\"hidden\"}" : event.detailsJson(), previous);
            rewritten.add(changed);
            previous = changed.hash();
        }

        Result result = verify(export(rewritten));

        assertThat(result.status()).isEqualTo(1);
        assertThat(result.output()).doesNotContain("BROKEN at")
            .contains("MISMATCH at seq 2: the hash differs from checkpoint 1")
            .contains("MISMATCH at seq 5: the hash differs from checkpoint 2")
            .contains("result: FAILED");
    }

    @Test
    void anExportOfARangeIsReportedAsAPartOfTheChain() throws IOException {
        Result result = verify(export(chain.subList(2, 6)));

        assertThat(result.status()).as(result.output()).isZero();
        assertThat(result.output()).contains("seq 3..6 OK, anchored to seq 5 (checkpoint 2); a part of the chain");
        assertThat(verify(export(chain)).output()).doesNotContain("a part of the chain");
    }

    @Test
    void aChainCutBelowACheckpointIsShorter() throws IOException {
        Result result = verify(export(chain.subList(0, 4)));

        assertThat(result.status()).isEqualTo(1);
        assertThat(result.output()).contains("SHORTER: the last event is seq 4, checkpoint 2");
    }

    @Test
    void anEventChangedWithoutRecomputingIsBroken() throws IOException {
        List<StoredAuditEvent> changed = new ArrayList<>(chain);
        StoredAuditEvent third = chain.get(2);
        changed.set(2, new StoredAuditEvent(third.id(), COMPANY, 3, third.occurredAt(), "SYSTEM", null,
                "company.updated", "company", COMPANY.toString(), "{\"n\":\"x\"}", third.prevHash(), third.hash()));

        Result result = verify(export(changed));

        assertThat(result.status()).isEqualTo(1);
        assertThat(result.output()).contains("BROKEN at seq 3");
    }

    @Test
    void aMissingOrForgedCheckpointAndAnUntrustedKeyAreFindings() throws IOException {
        Files.delete(folder.resolve("000000000001.json"));
        Checkpoint third = new Checkpoint(INSTANCE, 3, Instant.parse("2026-11-01T11:00:00Z"), "f".repeat(64),
                "k2027", List.of(AnchorFixtures.head(COMPANY, chain.get(5))));
        // Signed by a key nobody trusts under k2027's id.
        Files.writeString(folder.resolve("000000000003.json"), third.signedDocument(AnchorFixtures.keys("k2027",
                AnchorFixtures.keyMap("k2027", AnchorFixtures.signing(AnchorKeys.generate())))));

        Result result = verify(export(chain));

        assertThat(result.status()).isEqualTo(1);
        assertThat(result.output()).contains("checkpoint 3        BAD SIGNATURE")
            .contains("checkpoints        STARTS AT 2: checkpoints 1..1 are not in this source");
        Result onlyNewKey = run("verify", "--checkpoints", folder.toString(), "--public-key", "k2026=" + pub2027,
                "--export", export(chain).toString());
        assertThat(onlyNewKey.output()).contains("checkpoint 2        BAD SIGNATURE");
    }

    @Test
    void aGapInTheNumbersIsMissing() throws IOException {
        Checkpoint fourth = new Checkpoint(INSTANCE, 4, Instant.parse("2026-11-01T12:00:00Z"), "e".repeat(64),
                "k2027", List.of(AnchorFixtures.head(COMPANY, chain.get(5))));
        new FileAnchorSink(dir.resolve("checkpoints")).write(INSTANCE, 4, fourth.signedDocument(
                AnchorFixtures.keys("k2027", AnchorFixtures.keyMap("k2027", AnchorFixtures.signing(k2027)))));

        Result result = verify(export(chain));

        assertThat(result.status()).isEqualTo(1);
        assertThat(result.output()).contains("checkpoint 3        MISSING (2 -> 4)");
    }

    @Test
    void aDistrustedKeysCheckpointsStoredAfterItsCompromiseFail() throws IOException {
        Result result = verify(export(chain), "--distrust", "k2027@2020-01-01T00:00:00Z");

        assertThat(result.status()).isEqualTo(1);
        assertThat(result.output()).contains("checkpoint 2        DISTRUSTED: signed with k2027");
        assertThat(verify(export(chain), "--distrust", "k2027@2999-01-01T00:00:00Z").status()).isZero();
    }

    @Test
    void checkpointsFromASyslogExportAreReadAndADuplicateCountsOnce() throws IOException {
        List<String> messages = new ArrayList<>();
        for (String name : List.of("000000000001.json", "000000000002.json", "000000000002.json")) {
            messages.add("Nov  7 10:00:00 collector <109>1 2026-11-07T10:00:00.000000Z host tinfirm - audit-checkpoint"
                    + " - " + Files.readString(folder.resolve(name)).strip());
        }
        Path syslog = Files.write(dir.resolve("collector.log"), messages);

        Result result = run("verify", "--checkpoints", "syslog:" + syslog, "--public-key", "k2026=" + pub2026,
                "--public-key", "k2027=" + pub2027, "--export", export(chain).toString());

        assertThat(result.status()).as(result.output()).isZero();
        assertThat(result.output()).contains("anchored to seq 5 (checkpoint 2)");
    }

    @Test
    void keygenWritesAPairTheServerAndTheToolRead() throws IOException {
        Result result = run("keygen", "k2028", dir.toString());

        assertThat(result.status()).as(result.output()).isZero();
        String privatePem = Files.readString(dir.resolve("k2028.key"));
        String publicPem = Files.readString(dir.resolve("k2028.pub"));
        AnchorKeys keys = AnchorFixtures.keys("k2028", AnchorFixtures.keyMap("k2028",
                new AuditAnchorProperties.Key(privatePem, publicPem)));
        assertThat(keys.activeId()).contains("k2028");
        assertThat(result.output()).contains("TINFIRM_AUDIT_ANCHOR_KEYS_K2028_PRIVATE");
        assertThat(run("keygen", "k2028", dir.toString()).status()).isEqualTo(2);
        assertThat(run("verify").status()).isEqualTo(2);
        assertThat(run("verify", "--checkpoints", folder.toString(), "--export", "x").output())
            .contains("--public-key");
    }
}
