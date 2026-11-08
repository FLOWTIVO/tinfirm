package com.flowtivo.tinfirm.audit.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What a readable sink's checkpoints say, verified (ADR-0111, decisions 2 and 5): the tail the next checkpoint
 * follows, and every head each chain was anchored at. Built from the sink alone, never from the database, which is
 * what the anchor must not trust: the writer decides from it which heads moved and what the next number and
 * {@code prev} are, and the daily check compares every anchored head of a chain with it.
 * <p>
 * The index reads the checkpoints in order, once each, and keeps reading from where it stopped. It stops at the first
 * checkpoint that does not verify, misses its number, names another instance or does not follow the one before
 * ({@link #broken}): nothing is chained to a checkpoint the server cannot trust, and the writer refuses until it is
 * understood. Between checkpoints, a chain's head may only move forward: a later checkpoint that names a lower
 * {@code seq} for a chain, or the same {@code seq} with another hash, contradicts an earlier one, and is the chain's
 * {@link #conflict}.
 */
final class SinkIndex {

    /** A head as a checkpoint names it, with the checkpoint's time and key. */
    record Anchor(long checkpoint, long seq, String hash, Instant issuedAt, String keyId) {
    }

    /** The first checkpoint whose head for a chain contradicts an earlier one's. */
    record Conflict(long checkpoint, long seq, String reason) {
    }

    private final UUID instance;

    private long lastNumber;

    private String lastDigest = Checkpoint.NO_PREVIOUS;

    private final Map<String, List<Anchor>> byChain = new HashMap<>();

    private final Map<String, Conflict> conflicts = new HashMap<>();

    private @Nullable String broken;

    SinkIndex(UUID instance) {
        this.instance = instance;
    }

    UUID instance() {
        return instance;
    }

    /** The latest checkpoint indexed; 0 before the first. */
    synchronized long lastNumber() {
        return lastNumber;
    }

    /** The latest checkpoint's digest, which the next one names as {@code prev}. */
    synchronized String lastDigest() {
        return lastDigest;
    }

    /** Why the sink's checkpoints stopped verifying, at the first that did not; null while they all do. */
    synchronized @Nullable String broken() {
        return broken;
    }

    /** Every head the sink's checkpoints name for the chain, oldest first. */
    synchronized List<Anchor> anchors(@Nullable UUID companyId) {
        return List.copyOf(byChain.getOrDefault(key(companyId), List.of()));
    }

    /** The latest head the sink names for the chain, at or before {@code atOrBeforeSeq} when given. */
    synchronized Optional<Anchor> latest(@Nullable UUID companyId, @Nullable Long atOrBeforeSeq) {
        List<Anchor> anchors = byChain.getOrDefault(key(companyId), List.of());
        for (int i = anchors.size() - 1; i >= 0; i--) {
            if (atOrBeforeSeq == null || anchors.get(i).seq() <= atOrBeforeSeq) {
                return Optional.of(anchors.get(i));
            }
        }
        return Optional.empty();
    }

    /** The latest head the sink names for every chain, by chain (the instance's under null). */
    synchronized Map<@Nullable UUID, Anchor> latestOfEach() {
        Map<@Nullable UUID, Anchor> latest = new HashMap<>();
        byChain.forEach((key, anchors) -> latest.put(key.isEmpty() ? null : UUID.fromString(key), anchors.getLast()));
        return latest;
    }

    synchronized Optional<Conflict> conflict(@Nullable UUID companyId) {
        return Optional.ofNullable(conflicts.get(key(companyId)));
    }

    /**
     * Reads the checkpoints the sink holds after the last one indexed, in order, verifying each; stops at the first
     * that is missing, does not verify or does not follow. A checkpoint that stopped the index is read again at the
     * next refresh, so that a sink put right is followed again.
     */
    synchronized void refresh(AnchorSink sink, AnchorKeys keys) throws IOException {
        broken = null;
        for (long number : sink.numbers(instance, lastNumber)) {
            if (number != lastNumber + 1) {
                broken = "checkpoint " + (lastNumber + 1) + " is missing from the sink, which holds " + number;
                return;
            }
            Optional<AnchorSink.Stored> stored = sink.read(instance, number);
            if (stored.isEmpty()) {
                broken = "checkpoint " + number + " vanished from the sink";
                return;
            }
            if (stored.get().versions() > 1) {
                broken = "checkpoint " + number + " has " + stored.get().versions() + " versions in the sink";
                return;
            }
            Checkpoint.Signed signed;
            try {
                signed = Checkpoint.parse(stored.get().document(), keys);
            }
            catch (IllegalArgumentException ex) {
                broken = "checkpoint " + number + " cannot be read: " + ex.getMessage();
                return;
            }
            String why = add(number, signed);
            if (why != null) {
                broken = why;
                return;
            }
        }
    }

    /** Adds a checkpoint the writer just wrote, signed by itself. */
    synchronized void written(Checkpoint checkpoint) {
        String why = add(checkpoint.number(), new Checkpoint.Signed(checkpoint, true, null));
        if (why != null) {
            broken = why;
        }
    }

    /** Indexes one checkpoint; why it may not be, or null. */
    private @Nullable String add(long number, Checkpoint.Signed signed) {
        Checkpoint checkpoint = signed.checkpoint();
        if (!signed.verified()) {
            return "checkpoint " + number + " does not verify: " + signed.reason();
        }
        if (checkpoint.number() != number || !checkpoint.instance().equals(instance)) {
            return "checkpoint " + number + " names another number or instance than its place in the sink";
        }
        if (number != lastNumber + 1) {
            return "checkpoint " + number + " does not follow checkpoint " + lastNumber;
        }
        if (!checkpoint.prev().equals(lastDigest)) {
            return "checkpoint " + number + " does not name checkpoint " + lastNumber + "'s digest as its prev";
        }
        for (Checkpoint.Head head : checkpoint.chains()) {
            String key = key(head.companyId());
            List<Anchor> anchors = byChain.computeIfAbsent(key, k -> new ArrayList<>());
            if (!anchors.isEmpty() && !conflicts.containsKey(key)) {
                Anchor before = anchors.getLast();
                if (head.seq() < before.seq()) {
                    conflicts.put(key, new Conflict(number, head.seq(), "names seq " + head.seq() + " after checkpoint "
                            + before.checkpoint() + " named seq " + before.seq()));
                }
                else if (head.seq() == before.seq() && !head.hash().equals(before.hash())) {
                    conflicts.put(key, new Conflict(number, head.seq(), "names another hash for seq " + head.seq()
                            + " than checkpoint " + before.checkpoint()));
                }
            }
            anchors.add(new Anchor(number, head.seq(), head.hash(), checkpoint.issuedAt(), checkpoint.keyId()));
        }
        lastNumber = number;
        lastDigest = checkpoint.digest();
        return null;
    }

    private static String key(@Nullable UUID companyId) {
        return companyId == null ? "" : companyId.toString();
    }
}
