package com.flowtivo.tinfirm.run.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * The bundles' objects as files of a directory (Compose, the tests): {@code <directory>/<uuid>}, written to a
 * temporary file first and moved into place, so that a reader never sees half an object.
 */
final class FileSystemBundleObjects implements BundleObjects {

    private static final String TEMPORARY_SUFFIX = ".tmp";

    private final Path directory;

    FileSystemBundleObjects(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory;
    }

    private Path path(UUID name) {
        return directory.resolve(name.toString());
    }

    @Override
    public void put(UUID name, byte[] content) throws IOException {
        try (Upload upload = upload(name)) {
            upload.stream().write(content);
            upload.commit();
        }
    }

    @Override
    public Upload upload(UUID name) throws IOException {
        Path temporary = directory.resolve(name + "." + UUID.randomUUID() + TEMPORARY_SUFFIX);
        try {
            // Private from its creation, never with the umask's permissions.
            Files.createFile(temporary,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        catch (UnsupportedOperationException ex) {
            // A file system without POSIX permissions: the directory's own guard the files.
            Files.createFile(temporary);
        }
        OutputStream out;
        try {
            out = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        catch (IOException ex) {
            Files.deleteIfExists(temporary);
            throw ex;
        }
        return new Upload() {

            private boolean committed;

            @Override
            public OutputStream stream() {
                return out;
            }

            @Override
            public void commit() throws IOException {
                out.close();
                try {
                    Files.move(temporary, path(name), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                }
                catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, path(name), StandardCopyOption.REPLACE_EXISTING);
                }
                committed = true;
            }

            @Override
            public void close() throws IOException {
                if (!committed) {
                    out.close();
                    Files.deleteIfExists(temporary);
                }
            }
        };
    }

    @Override
    public int abandoned(Instant before) throws IOException {
        int removed = 0;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(TEMPORARY_SUFFIX)).toList()) {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(before) && Files.deleteIfExists(file)) {
                        removed++;
                    }
                }
                catch (NoSuchFileException ex) {
                    // Committed or removed meanwhile.
                }
            }
        }
        return removed;
    }

    @Override
    public Optional<InputStream> open(UUID name) throws IOException {
        try {
            return Optional.of(Files.newInputStream(path(name)));
        }
        catch (NoSuchFileException ex) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(UUID name) throws IOException {
        Files.deleteIfExists(path(name));
    }

    @Override
    public List<StoredObject> list(@Nullable UUID after, int limit) throws IOException {
        List<StoredObject> objects = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                UUID name = parse(file.getFileName().toString());
                if (name == null || (after != null && name.toString().compareTo(after.toString()) <= 0)) {
                    continue;
                }
                try {
                    objects.add(new StoredObject(name, Files.getLastModifiedTime(file).toInstant()));
                }
                catch (NoSuchFileException ex) {
                    // Deleted meanwhile.
                    continue;
                }
                if (objects.size() >= limit) {
                    break;
                }
            }
        }
        return objects;
    }

    /** The UUID a file is named by, or null for any other file (a temporary one). */
    private static @Nullable UUID parse(String fileName) {
        if (fileName.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(fileName);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
