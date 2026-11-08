package com.flowtivo.tinfirm.audit.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Where the checkpoints go (ADR-0111, decision 3): a place the database's writer cannot change. Each checkpoint is one
 * document, under the instance's id and its number. The server writes; the server and the offline tool read back
 * where the sink can be read.
 */
interface AnchorSink extends AutoCloseable {

    /** The sink's place for people: the bucket and prefix, the directory, the collector; never a credential. */
    String location();

    /**
     * Checks at the start what can be checked: the bucket's object lock, a directory that can be written. A failure
     * that a later write may not see again (a store that cannot be reached) is not one.
     *
     * @throws IllegalStateException when the sink must not be written to
     */
    void checkAtStart();

    /**
     * Writes a checkpoint's signed document, never over an existing one.
     *
     * @return where it went, for the record
     */
    String write(UUID instance, long number, String document) throws IOException;

    /** Whether checkpoints can be read back: not from a syslog collector. */
    boolean readable();

    /** The checkpoints' numbers in the sink, ascending, after the given one. */
    List<Long> numbers(UUID instance, long after) throws IOException;

    /** A checkpoint's document, with the time the sink stored it where it knows; empty when it holds none. */
    Optional<Stored> read(UUID instance, long number) throws IOException;

    @Override
    default void close() {
    }

    /**
     * A document as the sink keeps it.
     *
     * @param storedAt when the sink stored it (an object's creation, a file's modification), which an attacker who
     *                 holds the signing key cannot set back on a locked object
     * @param versions how many versions the sink keeps of it: more than one is an overwrite
     */
    record Stored(String document, @Nullable Instant storedAt, int versions) {
    }

    /** A checkpoint's name in a sink: its number in 12 digits, so that names sort as numbers. */
    static String name(long number) {
        return String.format("%012d.json", number);
    }
}
