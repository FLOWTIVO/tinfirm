package com.flowtivo.tinfirm.run.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Where the session bundles' objects are (ADR-0097): ciphertext only, each named by a random UUID. The port of the
 * store; {@link FileSystemBundleObjects} and {@link S3BundleObjects} adapt it.
 */
interface BundleObjects {

    /** Writes an object, replacing one of the same name. */
    void put(UUID name, byte[] content) throws IOException;

    /**
     * Writes an object as its content streams (ADR-0101): what {@link Upload#stream()} is given goes to the store as
     * it comes, and becomes the object only at {@link Upload#commit()}. Closed without a commit, it leaves no object;
     * what a crash leaves is {@link #abandoned}'s.
     */
    Upload upload(UUID name) throws IOException;

    /**
     * Removes what uploads that never committed left before {@code before}: a file system's temporary files, an S3
     * bucket's incomplete multipart uploads. Answers how many it removed.
     */
    int abandoned(Instant before) throws IOException;

    /** An object being written ({@link #upload}). */
    interface Upload extends AutoCloseable {

        /** The object's content: written to the store as it comes. */
        OutputStream stream();

        /** Makes what was written the object. */
        void commit() throws IOException;

        /** Discards what was written, unless it was committed. */
        @Override
        void close() throws IOException;
    }

    /** The object's content, or empty when there is none. The caller closes the stream. */
    Optional<InputStream> open(UUID name) throws IOException;

    /** Deletes an object; one that is not there is no error. */
    void delete(UUID name) throws IOException;

    /** A page of the objects, in name order, after the given name. */
    List<StoredObject> list(@Nullable UUID after, int limit) throws IOException;

    /** An object in the store, and when it was written. */
    record StoredObject(UUID name, Instant modifiedAt) {
    }

    /** No store: the server keeps no bundles. */
    BundleObjects NONE = new BundleObjects() {

        @Override
        public void put(UUID name, byte[] content) throws IOException {
            throw new IOException("No session bundle store is configured");
        }

        @Override
        public Upload upload(UUID name) throws IOException {
            throw new IOException("No session bundle store is configured");
        }

        @Override
        public int abandoned(Instant before) {
            return 0;
        }

        @Override
        public Optional<InputStream> open(UUID name) {
            return Optional.empty();
        }

        @Override
        public void delete(UUID name) {
        }

        @Override
        public List<StoredObject> list(@Nullable UUID after, int limit) {
            return List.of();
        }
    };
}
