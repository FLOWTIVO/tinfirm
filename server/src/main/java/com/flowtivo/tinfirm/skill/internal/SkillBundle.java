package com.flowtivo.tinfirm.skill.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * A skill's folder as an upload gives it (the skills contract, §2 and §4.2 steps 1, 2 and 4): read from a zip or from
 * the text of a {@code SKILL.md}, every path and limit checked against what is actually read (never what the zip
 * claims), the front matter of its {@code SKILL.md} checked, and written again as the canonical zip whose SHA-256 is
 * the version's. A pure function of its input: no database, no clock.
 */
final class SkillBundle {

    static final String SKILL_MD = "SKILL.md";

    static final int MAX_FILES = 200;

    static final int MAX_BYTES = 5 * 1024 * 1024;

    static final int MAX_UPLOAD = 5 * 1024 * 1024;

    /** The canonical zip's limit: {@code run.assign.skills[].sizeBytes}'s maximum in the runner protocol. */
    static final int MAX_STORED = 5 * 1024 * 1024;

    static final int MAX_SKILL_MD_CHARS = 100_000;

    static final int MAX_PATH_BYTES = 1024;

    static final int MAX_SEGMENT_BYTES = 255;

    static final int MAX_SEGMENTS = 8;

    static final int MAX_NAME = 64;

    static final int MAX_DESCRIPTION = 1024;

    static final int MAX_TEXT = 500;

    static final int MAX_METADATA = 50;

    static final Pattern NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** The front matter's keys of the Agent Skills specification (§2); any other is refused. */
    static final Set<String> KEYS = Set.of("name", "description", "license", "compatibility", "metadata",
            "allowed-tools");

    /** The time every entry of the canonical zip carries. */
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    /** The folders and files an archiver adds that are not the skill's: macOS's resource forks and Finder's state. */
    private static final String MACOS_FORKS = "__MACOSX/";

    private static final String FINDER_STATE = ".DS_Store";

    private SkillBundle() {
    }

    /** A file of the skill: its path relative to the folder, {@code /}-separated, and its bytes. */
    record File(String path, byte[] content) {
    }

    /**
     * The skill as stored.
     *
     * @param name        the front matter's {@code name}
     * @param description the front matter's {@code description}, trimmed
     * @param files       by path
     * @param zip         the canonical zip
     * @param sha256      the SHA-256 of {@code zip}, lower-case hex
     * @param skillMd     the text of its {@code SKILL.md}
     */
    record Parsed(String name, String description, List<FileEntry> files, byte[] zip, String sha256,
            String skillMd) {

        Parsed {
            files = List.copyOf(files);
        }
    }

    /** A file as the version lists it ({@code skill_version.files}): its path, size and SHA-256. */
    record FileEntry(String path, long sizeBytes, String sha256) {
    }

    /** Why an upload is refused: §9's {@code SKILL_INVALID} with a rule, or {@code SKILL_TOO_LARGE} with a limit. */
    static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** {@code SKILL_INVALID}'s rules. */
        enum Rule {
            NOT_A_ZIP, PATH, NO_SKILL_MD, FRONT_MATTER, NAME, DESCRIPTION, KEY
        }

        /** {@code SKILL_TOO_LARGE}'s limits. */
        enum Limit {
            FILES, BYTES, UPLOAD, STORED
        }

        private final @Nullable Rule rule;

        private final @Nullable Limit limit;

        private final @Nullable String path;

        private Refused(@Nullable Rule rule, @Nullable Limit limit, @Nullable String path, String message) {
            super(message);
            this.rule = rule;
            this.limit = limit;
            this.path = path;
        }

        static Refused invalid(Rule rule, @Nullable String path, String message) {
            return new Refused(rule, null, path, message);
        }

        static Refused tooLarge(Limit limit, String message) {
            return new Refused(null, limit, null, message);
        }

        @Nullable Rule rule() {
            return rule;
        }

        @Nullable Limit limit() {
            return limit;
        }

        @Nullable String path() {
            return path;
        }
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    /** A skill from a zip of its folder. */
    static Parsed fromZip(byte[] upload) {
        if (upload.length > MAX_UPLOAD) {
            throw Refused.tooLarge(Refused.Limit.UPLOAD, "A skill's zip may have at most " + mib(MAX_UPLOAD));
        }
        return of(readZip(upload));
    }

    /** A skill of one file, from the text of its {@code SKILL.md}. */
    static Parsed fromSkillMd(String text) {
        if (text.length() > MAX_SKILL_MD_CHARS) {
            throw Refused.tooLarge(Refused.Limit.BYTES, "A SKILL.md may have at most " + MAX_SKILL_MD_CHARS
                    + " characters");
        }
        return of(List.of(new File(SKILL_MD, text.getBytes(StandardCharsets.UTF_8))));
    }

    private static Parsed of(List<File> read) {
        List<File> sorted = new ArrayList<>(read);
        sorted.sort(Comparator.comparing(File::path));
        List<File> files = stripTopFolder(sorted);
        checkPaths(files);
        File skillMd = files.stream().filter(file -> file.path().equals(SKILL_MD)).findFirst()
            .orElseThrow(() -> Refused.invalid(Refused.Rule.NO_SKILL_MD, null,
                    "A skill needs a SKILL.md at the root of its folder"));
        String text = utf8(skillMd.content());
        FrontMatter front = frontMatter(text);
        byte[] zip = canonicalZip(files);
        if (zip.length > MAX_STORED) {
            // Deflate adds a little to data that does not compress: files just under the limits can pass it here.
            throw Refused.tooLarge(Refused.Limit.STORED, "Written as the server stores it, the skill's zip has "
                    + "more than " + mib(MAX_STORED) + ", which a run's host does not take: make its files a little "
                    + "smaller");
        }
        List<FileEntry> entries = files.stream()
            .map(file -> new FileEntry(file.path(), file.content().length, sha256(file.content())))
            .toList();
        return new Parsed(front.name(), front.description(), entries, zip, sha256(zip), text);
    }

    /**
     * The files of the zip, in its order. Directory entries, macOS's resource forks and Finder's state are skipped.
     * Every limit counts what is read: a zip that claims small sizes and inflates to more is refused when it passes
     * the limit. A skipped entry's bytes count too: the stream inflates an entry whether or not it is kept, so a
     * payload under {@code __MACOSX/}, in a {@code .DS_Store} or on a directory entry is drained through the same
     * budget as the files, and refused past it. Only the file count leaves the skipped entries out.
     */
    static List<File> readZip(byte[] upload) {
        List<File> files = new ArrayList<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(upload), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            boolean any = false;
            while ((entry = zip.getNextEntry()) != null) {
                any = true;
                String name = entry.getName();
                if (entry.isDirectory() || skipped(name)) {
                    total += drain(zip, MAX_BYTES - total, null);
                    continue;
                }
                if (files.size() == MAX_FILES) {
                    throw Refused.tooLarge(Refused.Limit.FILES, "A skill may have at most " + MAX_FILES + " files");
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                total += drain(zip, MAX_BYTES - total, content);
                files.add(new File(name, content.toByteArray()));
            }
            if (!any) {
                throw Refused.invalid(Refused.Rule.NOT_A_ZIP, null, "The file is not a zip, or the zip is empty");
            }
        }
        catch (ZipException | IllegalArgumentException ex) {
            // Encrypted, damaged, or names that are not UTF-8.
            throw Refused.invalid(Refused.Rule.NOT_A_ZIP, null, "The zip cannot be read: it is encrypted or damaged");
        }
        catch (IOException ex) {
            throw Refused.invalid(Refused.Rule.NOT_A_ZIP, null, "The zip cannot be read: it is damaged");
        }
        if (files.isEmpty()) {
            throw Refused.invalid(Refused.Rule.NO_SKILL_MD, null, "The zip holds no files");
        }
        return files;
    }

    private static boolean skipped(String name) {
        String last = name.substring(name.lastIndexOf('/') + 1);
        return name.startsWith(MACOS_FORKS) || name.contains("/" + MACOS_FORKS) || last.equals(FINDER_STATE);
    }

    /**
     * Reads the entry to its end and answers how many bytes it inflated to, at most {@code room}; one more is
     * {@code SKILL_TOO_LARGE}. The bytes go to {@code sink}, or nowhere when it is null (a skipped entry, whose
     * payload is counted and discarded).
     */
    private static long drain(InputStream in, long room, @Nullable ByteArrayOutputStream sink) throws IOException {
        byte[] buffer = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (n > room - read) {
                throw Refused.tooLarge(Refused.Limit.BYTES, "A skill may have at most " + mib(MAX_BYTES)
                        + " of files in all");
            }
            if (sink != null) {
                sink.write(buffer, 0, n);
            }
            read += n;
        }
        return read;
    }

    /**
     * When every file lies under one top-level folder, that folder goes: a zip of the skill's folder itself. The folder
     * is the first segment of every path as uploaded, so it passes a segment's rules before it goes ({@code ..},
     * {@code .}, an empty one, control characters, {@code \} or {@code :} refuse the upload as {@link #checkPaths}
     * does, with the path as uploaded): stripped unchecked, {@code ../SKILL.md} would become {@code SKILL.md}, and
     * the paths checked after it would hold no trace of it. A file without a {@code /} strips nothing.
     */
    private static List<File> stripTopFolder(List<File> files) {
        String top = null;
        for (File file : files) {
            int slash = file.path().indexOf('/');
            if (slash < 0) {
                return files;
            }
            String first = file.path().substring(0, slash);
            if (top == null) {
                top = first;
            }
            else if (!top.equals(first)) {
                return files;
            }
        }
        if (top == null) {
            return files;
        }
        if (!segmentValid(top)) {
            throw badPath(files.getFirst().path());
        }
        String prefix = top + "/";
        return files.stream().map(file -> new File(file.path().substring(prefix.length()), file.content())).toList();
    }

    /**
     * Every path relative and {@code /}-separated; each segment 1 to 255 bytes without control characters,
     * {@code \} or {@code :}, never {@code .} or {@code ..}; at most 8 segments and 1,024 bytes; no two that differ in
     * case only; no file that is also another's folder.
     */
    static void checkPaths(List<File> files) {
        Set<String> seen = new HashSet<>();
        Set<String> folders = new HashSet<>();
        for (File file : files) {
            String path = file.path();
            if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")
                    || path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
                throw badPath(path);
            }
            String[] segments = path.split("/", -1);
            if (segments.length > MAX_SEGMENTS) {
                throw badPath(path);
            }
            StringBuilder folder = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                if (!segmentValid(segment)) {
                    throw badPath(path);
                }
                if (i < segments.length - 1) {
                    folder.append(folder.isEmpty() ? "" : "/").append(segment);
                    folders.add(folder.toString().toLowerCase(Locale.ROOT));
                }
            }
            if (!seen.add(path.toLowerCase(Locale.ROOT))) {
                throw badPath(path);
            }
        }
        for (String path : seen) {
            if (folders.contains(path)) {
                throw badPath(path);
            }
        }
    }

    private static boolean segmentValid(String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                || segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\' || c == ':') {
                return false;
            }
        }
        return true;
    }

    private static Refused badPath(String path) {
        return Refused.invalid(Refused.Rule.PATH, path, "The path " + quoted(path) + " is not allowed in a skill: "
                + "use relative paths of at most " + MAX_SEGMENTS + " parts, without \\, :, . or .., and no two that "
                + "differ in case only");
    }

    // ---- SKILL.md ----------------------------------------------------------------------------------------------

    /** The front matter's values the library keeps. */
    record FrontMatter(String name, String description) {
    }

    /** The front matter of a {@code SKILL.md} (§2): between a first line {@code ---} and the next line {@code ---}. */
    static FrontMatter frontMatter(String text) {
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        List<String> lines = body.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().strip().equals("---")) {
            throw Refused.invalid(Refused.Rule.FRONT_MATTER, SKILL_MD, "SKILL.md must start with a front matter: a "
                    + "line ---, the name and the description, and another line ---");
        }
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw Refused.invalid(Refused.Rule.FRONT_MATTER, SKILL_MD, "The front matter of SKILL.md has no closing "
                    + "line ---");
        }
        Map<?, ?> map = yaml(String.join("\n", lines.subList(1, end)));
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !KEYS.contains(name)) {
                throw Refused.invalid(Refused.Rule.KEY, SKILL_MD, "The front matter of SKILL.md may hold only "
                        + String.join(", ", KEYS.stream().sorted().toList()) + "; " + quoted(String.valueOf(key))
                        + " is not one of them");
            }
        }
        Object name = map.get("name");
        if (!(name instanceof String n) || !NAME.matcher(n).matches() || n.length() > MAX_NAME) {
            throw Refused.invalid(Refused.Rule.NAME, SKILL_MD, "The name in SKILL.md must be lower-case letters and "
                    + "digits, in parts joined by single hyphens, at most " + MAX_NAME + " characters");
        }
        Object description = map.get("description");
        String trimmed = description instanceof String d ? d.strip() : "";
        if (trimmed.isEmpty() || trimmed.length() > MAX_DESCRIPTION) {
            throw Refused.invalid(Refused.Rule.DESCRIPTION, SKILL_MD, "The description in SKILL.md must have 1 to "
                    + MAX_DESCRIPTION + " characters: it is what the agent reads to choose the skill");
        }
        checkText(map, "license");
        checkText(map, "compatibility");
        checkMetadata(map.get("metadata"));
        checkAllowedTools(map.get("allowed-tools"));
        return new FrontMatter(n, trimmed);
    }

    private static Map<?, ?> yaml(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setAllowRecursiveKeys(false);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(MAX_SKILL_MD_CHARS);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(text);
        }
        catch (YAMLException ex) {
            throw Refused.invalid(Refused.Rule.FRONT_MATTER, SKILL_MD, "The front matter of SKILL.md is not valid "
                    + "YAML");
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw Refused.invalid(Refused.Rule.FRONT_MATTER, SKILL_MD, "The front matter of SKILL.md must be a map "
                    + "of keys and values");
        }
        return map;
    }

    private static void checkText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value != null && (!(value instanceof String text) || text.length() > MAX_TEXT)) {
            throw Refused.invalid(Refused.Rule.KEY, SKILL_MD, "The " + key + " in SKILL.md must be text of at most "
                    + MAX_TEXT + " characters");
        }
    }

    private static void checkMetadata(@Nullable Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof Map<?, ?> map) || map.size() > MAX_METADATA) {
            throw Refused.invalid(Refused.Rule.KEY, SKILL_MD, "The metadata in SKILL.md must be a map of at most "
                    + MAX_METADATA + " keys");
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String text)
                    || text.length() > MAX_TEXT) {
                throw Refused.invalid(Refused.Rule.KEY, SKILL_MD, "Each value of the metadata in SKILL.md must be "
                        + "text of at most " + MAX_TEXT + " characters");
            }
        }
    }

    private static void checkAllowedTools(@Nullable Object value) {
        if (value == null || value instanceof String) {
            return;
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return;
        }
        throw Refused.invalid(Refused.Rule.KEY, SKILL_MD, "The allowed-tools in SKILL.md must be text or a list of "
                + "texts");
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        }
        catch (CharacterCodingException ex) {
            throw Refused.invalid(Refused.Rule.FRONT_MATTER, SKILL_MD, "SKILL.md must be UTF-8 text");
        }
    }

    // ---- the canonical zip -------------------------------------------------------------------------------------

    /**
     * The files written again in the order of their paths, deflated, with one fixed time and no extra fields or
     * comments: the same files give the same bytes.
     */
    static byte[] canonicalZip(List<File> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            zip.setMethod(ZipOutputStream.DEFLATED);
            for (File file : files.stream().sorted(Comparator.comparing(File::path)).toList()) {
                ZipEntry entry = new ZipEntry(file.path());
                entry.setTimeLocal(ZIP_TIME);
                zip.putNextEntry(entry);
                zip.write(file.content());
                zip.closeEntry();
            }
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    /** The files of a canonical zip as stored (no limits: the server wrote it). */
    static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return files;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String mib(int bytes) {
        return (bytes / (1024 * 1024)) + " MiB";
    }

    private static String quoted(String text) {
        String shown = text.length() > 80 ? text.substring(0, 80) + "…" : text;
        return "\"" + shown + "\"";
    }
}
