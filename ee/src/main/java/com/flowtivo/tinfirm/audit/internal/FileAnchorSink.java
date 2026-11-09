package com.flowtivo.tinfirm.audit.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checkpoints as files: {@code <dir>/<instance>/<number, 12 digits>.json}, each created once and never overwritten
 * (ADR-0111, decision 3). Its protection is the operator's: a volume that the database's hosts and administrators
 * cannot write, a WORM or append-only mount, or a directory a job ships elsewhere.
 */
final class FileAnchorSink implements AnchorSink {

    private static final Pattern NAME = Pattern.compile("(\\d{12})\\.json");

    private final Path dir;

    FileAnchorSink(Path dir) {
        this.dir = dir;
    }

    @Override
    public String location() {
        return dir.toAbsolutePath().toString();
    }

    @Override
    public void checkAtStart() {
        try {
            Files.createDirectories(dir);
        }
        catch (IOException ex) {
            throw new IllegalStateException("The audit anchor's directory " + dir + " cannot be created: "
                    + ex.getMessage(), ex);
        }
        if (!Files.isDirectory(dir) || !Files.isWritable(dir)) {
            throw new IllegalStateException("The audit anchor's directory " + dir + " is not a directory the server"
                    + " can write");
        }
    }

    @Override
    public String write(UUID instance, long number, String document) throws IOException {
        Path folder = dir.resolve(instance.toString());
        Files.createDirectories(folder);
        Path target = folder.resolve(AnchorSink.name(number));
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString(), null, "a checkpoint is never overwritten");
        }
        // Written beside its name and moved into place, so that a reader never sees half a checkpoint; the move
        // fails rather than replace a checkpoint that appeared meanwhile.
        Path temporary = folder.resolve("." + AnchorSink.name(number) + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, document + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            Files.createLink(target, temporary);
        }
        catch (UnsupportedOperationException ex) {
            // A file system without hard links: move without replacing.
            Files.move(temporary, target);
            return target.toString();
        }
        finally {
            Files.deleteIfExists(temporary);
        }
        return target.toString();
    }

    @Override
    public boolean readable() {
        return true;
    }

    @Override
    public List<Long> numbers(UUID instance, long after) throws IOException {
        Path folder = dir.resolve(instance.toString());
        List<Long> numbers = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return numbers;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                Matcher matcher = NAME.matcher(file.getFileName().toString());
                if (matcher.matches()) {
                    long number = Long.parseLong(matcher.group(1));
                    if (number > after) {
                        numbers.add(number);
                    }
                }
            }
        }
        numbers.sort(Long::compare);
        return numbers;
    }

    @Override
    public Optional<Stored> read(UUID instance, long number) throws IOException {
        Path file = dir.resolve(instance.toString()).resolve(AnchorSink.name(number));
        try {
            return Optional.of(new Stored(Files.readString(file, StandardCharsets.UTF_8),
                    Files.getLastModifiedTime(file).toInstant(), 1));
        }
        catch (NoSuchFileException ex) {
            return Optional.empty();
        }
    }
}
