package com.flowtivo.tinfirm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The index of a sink's verified checkpoints (ADR-0111, decisions 2 and 5), which the writer and the daily check
 * decide by instead of the database: it follows the tail, keeps every head of every chain, stops at a checkpoint that
 * does not verify, is missing or does not follow, and marks a checkpoint that contradicts an earlier one for a chain.
 */
class SinkIndexTest {

    private static final UUID INSTANCE = UUID.fromString("7f1d2c3b-4a59-4e6f-8a7b-9c0d1e2f3a4b");

    private static final UUID COMPANY = UUID.fromString("3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c");

    private static final UUID OTHER = UUID.fromString("9c41d2e3-1f2a-4b3c-8d4e-5f6a7b8c9d0e");

    @TempDir
    private Path dir;

    private final KeyPair key = AnchorKeys.generate();

    private AnchorKeys keys;

    private FileAnchorSink sink;

    private Checkpoint last;

    @BeforeEach
    void sink() {
        keys = AnchorFixtures.keys("k1", AnchorFixtures.keyMap("k1", AnchorFixtures.signing(key)));
        sink = new FileAnchorSink(dir);
        last = null;
    }

    private Checkpoint write(Checkpoint.Head... heads) throws IOException {
        long number = last == null ? 1 : last.number() + 1;
        Checkpoint checkpoint = new Checkpoint(INSTANCE, number, Instant.parse("2026-11-01T09:00:00Z")
            .plusSeconds(number * 3600), last == null ? Checkpoint.NO_PREVIOUS : last.digest(), "k1", List.of(heads));
        sink.write(INSTANCE, number, checkpoint.signedDocument(keys));
        last = checkpoint;
        return checkpoint;
    }

    private static Checkpoint.Head head(UUID companyId, long seq, char hash) {
        return new Checkpoint.Head(companyId, seq, String.valueOf(hash).repeat(64));
    }

    @Test
    void itFollowsTheTailAndKeepsEveryHeadOfEveryChain() throws IOException {
        write(head(COMPANY, 5, 'a'), head(OTHER, 2, 'b'));
        write(head(COMPANY, 9, 'c'));
        SinkIndex index = new SinkIndex(INSTANCE);

        index.refresh(sink, keys);

        assertThat(index.broken()).isNull();
        assertThat(index.lastNumber()).isEqualTo(2);
        assertThat(index.lastDigest()).isEqualTo(last.digest());
        assertThat(index.anchors(COMPANY)).extracting(SinkIndex.Anchor::checkpoint, SinkIndex.Anchor::seq)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 5L), org.assertj.core.groups.Tuple.tuple(2L, 9L));
        assertThat(index.latest(COMPANY, null).orElseThrow().seq()).isEqualTo(9);
        assertThat(index.latest(COMPANY, 8L).orElseThrow().seq()).isEqualTo(5);
        assertThat(index.latest(OTHER, null).orElseThrow().checkpoint()).isEqualTo(1);
        assertThat(index.latestOfEach()).containsOnlyKeys(COMPANY, OTHER);
        assertThat(index.conflict(COMPANY)).isEmpty();

        write(head(OTHER, 3, 'd'));
        index.refresh(sink, keys);
        assertThat(index.lastNumber()).isEqualTo(3);
        assertThat(index.latest(OTHER, null).orElseThrow().seq()).isEqualTo(3);
    }

    @Test
    void aLaterCheckpointThatRegressesOrForksAChainIsItsConflict() throws IOException {
        write(head(COMPANY, 5, 'a'), head(OTHER, 7, 'b'));
        write(head(COMPANY, 4, 'c'), head(OTHER, 7, 'e'));
        SinkIndex index = new SinkIndex(INSTANCE);

        index.refresh(sink, keys);

        assertThat(index.broken()).isNull();
        assertThat(index.conflict(COMPANY).orElseThrow().checkpoint()).isEqualTo(2);
        assertThat(index.conflict(COMPANY).orElseThrow().reason()).contains("names seq 4 after checkpoint 1");
        assertThat(index.conflict(OTHER).orElseThrow().reason()).contains("another hash for seq 7");
    }

    @Test
    void itStopsAtACheckpointThatIsMissingForgedOrDoesNotFollow() throws IOException {
        write(head(COMPANY, 5, 'a'));
        write(head(COMPANY, 6, 'b'));
        write(head(COMPANY, 7, 'c'));
        Path second = dir.resolve(INSTANCE.toString()).resolve(AnchorSink.name(2));
        Path third = dir.resolve(INSTANCE.toString()).resolve(AnchorSink.name(3));

        // Forged: another key under the id k1.
        String forged = new Checkpoint(INSTANCE, 3, Instant.parse("2026-11-01T12:00:00Z"), last.prev(), "k1",
                List.of(head(COMPANY, 7, 'f'))).signedDocument(AnchorFixtures.keys("k1",
                        AnchorFixtures.keyMap("k1", AnchorFixtures.signing(AnchorKeys.generate()))));
        Files.delete(third);
        Files.writeString(third, forged);
        SinkIndex index = new SinkIndex(INSTANCE);
        index.refresh(sink, keys);
        assertThat(index.broken()).contains("checkpoint 3 does not verify");
        assertThat(index.lastNumber()).isEqualTo(2);

        // Missing: the second checkpoint gone.
        Files.delete(second);
        SinkIndex fresh = new SinkIndex(INSTANCE);
        fresh.refresh(sink, keys);
        assertThat(fresh.broken()).contains("checkpoint 2 is missing");
        assertThat(fresh.lastNumber()).isEqualTo(1);

        // Not following: a checkpoint 2 that names another prev.
        Checkpoint stray = new Checkpoint(INSTANCE, 2, Instant.parse("2026-11-01T11:00:00Z"), "e".repeat(64), "k1",
                List.of(head(COMPANY, 6, 'b')));
        sink.write(INSTANCE, 2, stray.signedDocument(keys));
        fresh.refresh(sink, keys);
        assertThat(fresh.broken()).contains("does not name checkpoint 1's digest");
        assertThat(fresh.lastNumber()).isEqualTo(1);
    }

    @Test
    void aCheckpointTheWriterWroteIsIndexedWithoutReadingItBack() throws IOException {
        write(head(COMPANY, 5, 'a'));
        SinkIndex index = new SinkIndex(INSTANCE);
        index.refresh(sink, keys);
        Checkpoint next = new Checkpoint(INSTANCE, 2, Instant.parse("2026-11-01T11:00:00Z"), last.digest(), "k1",
                List.of(head(COMPANY, 8, 'b')));

        index.written(next);

        assertThat(index.lastNumber()).isEqualTo(2);
        assertThat(index.latest(COMPANY, null).orElseThrow().seq()).isEqualTo(8);
        assertThat(index.broken()).isNull();
    }
}
