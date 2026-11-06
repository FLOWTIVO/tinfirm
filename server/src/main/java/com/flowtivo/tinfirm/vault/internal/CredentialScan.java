package com.flowtivo.tinfirm.vault.internal;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.vault.ScanReport;

/**
 * One credential scan (ADR-0014), independent of the database: the secret values are given, already decrypted.
 * <ul>
 * <li><b>Values.</b> Each value of at least {@value #MIN_VALUE_LENGTH} bytes is searched in plain form, hex (lower
 * and upper case), URL-encoded, and base64 (standard and URL-safe) at all three byte alignments, so that it is
 * found inside a longer encoded blob too.</li>
 * <li><b>Formats.</b> Known credential formats, matched with regular expressions on the bytes read as
 * ISO-8859-1. Each pattern has a left guard or only bounded repetitions, so that it takes linear time: none starts
 * again at every character of a long run of its own characters (a JWT pattern without its guard took minutes for
 * {@code eyJ} repeated 100,000 times).</li>
 * <li><b>Archives.</b> Zip (including Office documents) and gzip content is unpacked and scanned as well, nested up
 * to {@value #MAX_DEPTH} levels and up to {@value #MAX_EXPANDED_BYTES} unpacked bytes in total. Tar needs no
 * unpacking: its members are stored as they are. An archive that cannot be read (encrypted, damaged, too large,
 * nested too deep) makes the content unscannable.</li>
 * <li><b>Streaming.</b> Nothing is held whole but the content given: each level streams through one pass of the
 * values' matcher, which carries over from one read to the next, and through windows of {@value #WINDOW_BYTES}
 * bytes for the formats, each of which repeats the last {@value #OVERLAP_BYTES} bytes of the one before. A format
 * match of up to that length is found wherever it is; a match counts in the window where it starts before the
 * repeated part, so the characters around it are always seen. A member of an archive is never held whole, and no
 * text copy of the content is made.</li>
 * <li><b>Time.</b> A scan that takes longer than its bound ({@link #MAX_SCAN_TIME}) stops, and the content is
 * unscannable: a backstop for an input the patterns' guards do not foresee.</li>
 * </ul>
 * {@link #close()} wipes the search patterns, which are derived from the values.
 */
final class CredentialScan implements AutoCloseable {

    static final int MIN_VALUE_LENGTH = 8;

    static final int MAX_DEPTH = 3;

    /** The most bytes the archives of one content unpack to, all levels together. */
    static final long MAX_EXPANDED_BYTES = 64L * 1024 * 1024;

    /** The longest the scan of one content takes, its archives included; a longer one makes it unscannable. */
    static final Duration MAX_SCAN_TIME = Duration.ofSeconds(10);

    /** The bytes the formats are matched in at once. */
    static final int WINDOW_BYTES = 1024 * 1024;

    /** The bytes of a window that the next one repeats: the longest format match found wherever it is. */
    static final int OVERLAP_BYTES = 64 * 1024;

    /** Why a scan that took too long is unscannable. */
    static final String TOO_SLOW = "content that takes too long to scan";

    /** The first window's size; a window grows to {@link #WINDOW_BYTES} as content comes. */
    private static final int FIRST_WINDOW_BYTES = 64 * 1024;

    private final List<String> names;

    private final List<byte[]> patterns = new ArrayList<>();

    private final ByteMatcher matcher;

    private final long maxExpandedBytes;

    private final long maxScanNanos;

    /** @param values secret name to plaintext value; the caller keeps and wipes the arrays */
    CredentialScan(Map<String, byte[]> values) {
        this(values, MAX_EXPANDED_BYTES, MAX_SCAN_TIME);
    }

    CredentialScan(Map<String, byte[]> values, long maxExpandedBytes) {
        this(values, maxExpandedBytes, MAX_SCAN_TIME);
    }

    CredentialScan(Map<String, byte[]> values, long maxExpandedBytes, Duration maxScanTime) {
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxScanNanos = maxScanTime.toNanos();
        this.names = new ArrayList<>(values.keySet());
        List<Integer> labels = new ArrayList<>();
        for (int label = 0; label < names.size(); label++) {
            for (byte[] variant : variants(values.get(names.get(label)))) {
                patterns.add(variant);
                labels.add(label);
            }
        }
        this.matcher = new ByteMatcher(patterns, labels.stream().mapToInt(Integer::intValue).toArray(),
                names.size());
    }

    ScanReport scan(byte[] content) {
        Walk walk = new Walk(System.nanoTime(), null);
        try {
            walk.visit(new ByteArrayInputStream(content), 0);
        }
        catch (Stop stop) {
            // Unscannable, for the reason recorded; what was found until then is reported as well.
        }
        catch (IOException ex) {
            // Reading the content in memory fails nowhere else: an archive's own failures are caught where it is read.
            walk.unscannable("content that cannot be read");
        }
        return walk.report();
    }

    /**
     * Scans content that streams in (a session bundle as its host uploads it, ADR-0101) as {@link #scan(byte[])} scans
     * content held whole. The time spent waiting for the content does not count against {@link #MAX_SCAN_TIME}, only
     * the scan's own. A failure of the content to arrive is thrown, also where an archive's reader met it.
     */
    ScanReport scan(InputStream content) throws IOException {
        Source source = new Source(content);
        Walk walk = new Walk(System.nanoTime(), source);
        try {
            walk.visit(source, 0);
        }
        catch (Stop stop) {
            // Unscannable, for the reason recorded.
        }
        catch (IOException ex) {
            if (source.failure == null) {
                walk.unscannable("content that cannot be read");
            }
        }
        if (source.failure != null) {
            throw source.failure;
        }
        return walk.report();
    }

    @Override
    public void close() {
        patterns.forEach(pattern -> Arrays.fill(pattern, (byte) 0));
    }

    /** The encodings of a value to search for; none when the value is too short to match reliably. */
    static List<byte[]> variants(byte[] raw) {
        List<byte[]> variants = new ArrayList<>();
        byte[] stripped = strip(raw);
        for (byte[] value : stripped.length == raw.length ? List.of(raw) : List.of(raw, stripped)) {
            if (value.length < MIN_VALUE_LENGTH) {
                continue;
            }
            addDistinct(variants, value.clone());
            addDistinct(variants, HexFormat.of().formatHex(value).getBytes(StandardCharsets.US_ASCII));
            addDistinct(variants, HexFormat.of().withUpperCase().formatHex(value).getBytes(StandardCharsets.US_ASCII));
            String text = utf8(value);
            if (text != null) {
                addDistinct(variants, URLEncoder.encode(text, StandardCharsets.UTF_8).getBytes(StandardCharsets.US_ASCII));
            }
            for (int offset = 0; offset < 3; offset++) {
                addDistinct(variants, base64Core(value, offset, java.util.Base64.getEncoder()));
                addDistinct(variants, base64Core(value, offset, java.util.Base64.getUrlEncoder()));
            }
        }
        variants.removeIf(variant -> variant.length < MIN_VALUE_LENGTH);
        return variants;
    }

    /**
     * The base64 characters that depend only on {@code value} when it starts {@code offset} bytes into a 3-byte
     * group: the characters shared with the prefix bytes and the incomplete last group are dropped.
     */
    static byte[] base64Core(byte[] value, int offset, java.util.Base64.Encoder encoder) {
        byte[] shifted = new byte[offset + value.length];
        System.arraycopy(value, 0, shifted, offset, value.length);
        byte[] encoded = encoder.encode(shifted);
        int skip = switch (offset) {
            case 0 -> 0;
            case 1 -> 2;
            default -> 3;
        };
        int end = (shifted.length / 3) * 4;
        byte[] core = end > skip ? Arrays.copyOfRange(encoded, skip, end) : new byte[0];
        Arrays.fill(shifted, (byte) 0);
        Arrays.fill(encoded, (byte) 0);
        return core;
    }

    private static void addDistinct(List<byte[]> variants, byte[] candidate) {
        if (variants.stream().noneMatch(existing -> Arrays.equals(existing, candidate))) {
            variants.add(candidate);
        }
    }

    private static byte[] strip(byte[] value) {
        int start = 0;
        int end = value.length;
        while (start < end && Character.isWhitespace(value[start])) {
            start++;
        }
        while (end > start && Character.isWhitespace(value[end - 1])) {
            end--;
        }
        return start == 0 && end == value.length ? value : Arrays.copyOfRange(value, start, end);
    }

    private static @Nullable String utf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        }
        catch (CharacterCodingException ex) {
            return null;
        }
    }

    /**
     * A known credential format: its pattern, and the anchors one of which every match starts with (after a guard,
     * which reads no character of its own), so that the pattern is tried only where an anchor is.
     */
    private record Format(String name, Pattern pattern, List<String> anchors) {
    }

    /**
     * The formats. Each has a left guard (a lookbehind: a character of its own kind precedes no match) or only
     * bounded repetitions, so that it takes linear time: an unguarded pattern whose repetition is followed by more
     * pattern starts again at every character of a long run, and takes quadratic time when the rest never comes.
     * The bounds of a trailing repetition change nothing that is found: a longer run holds a match all the same.
     */
    private static final List<Format> FORMAT_LIST = List.of(
            new Format("GitHub token", Pattern.compile(
                    "(?<![A-Za-z0-9_])(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{22,255})"),
                    List.of("ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_")),
            new Format("AWS access key", Pattern.compile("(?<![A-Z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Z0-9])"),
                    List.of("AKIA", "ASIA")),
            new Format("Anthropic API key or OAuth token",
                    Pattern.compile("sk-ant-(?:api|oat|admin)[0-9]{2}-[A-Za-z0-9_-]{20,1024}"), List.of("sk-ant-")),
            new Format("OpenAI API key", Pattern.compile(
                    "(?<![A-Za-z0-9_-])sk-(?!ant-)(?:proj-|svcacct-|admin-)?[A-Za-z0-9_-]{20,1024}"), List.of("sk-")),
            new Format("JSON Web Token (e.g. a Codex access token)", Pattern.compile(
                    "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{16,1024}"),
                    List.of("eyJ")),
            new Format("Private key", Pattern.compile("-----BEGIN (?:[A-Z0-9]{1,32} ){0,8}PRIVATE KEY(?: BLOCK)?-----"),
                    List.of("-----BEGIN ")),
            new Format("Slack token", Pattern.compile("xox[abposr]-[A-Za-z0-9-]{10,1024}"),
                    List.of("xoxa-", "xoxb-", "xoxp-", "xoxo-", "xoxs-", "xoxr-")),
            new Format("Google API key", Pattern.compile("AIza[0-9A-Za-z_-]{35}"), List.of("AIza")),
            new Format("Stripe secret key", Pattern.compile("(?:sk|rk)_live_[A-Za-z0-9]{20,1024}"),
                    List.of("sk_live_", "rk_live_")),
            new Format("Tinfirm runner host token",
                    Pattern.compile("(?<![A-Za-z0-9_])tfr_[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])"), List.of("tfr_")));

    /** The known credential formats, by display name. */
    static final Map<String, Pattern> FORMATS = formats();

    /** The anchors of every format, labelled with the format's index in {@link #FORMAT_LIST}. */
    private static final ByteMatcher ANCHORS = anchors();

    private static Map<String, Pattern> formats() {
        Map<String, Pattern> formats = new LinkedHashMap<>();
        FORMAT_LIST.forEach(format -> formats.put(format.name(), format.pattern()));
        return java.util.Collections.unmodifiableMap(formats);
    }

    private static ByteMatcher anchors() {
        List<byte[]> anchors = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        for (int index = 0; index < FORMAT_LIST.size(); index++) {
            for (String anchor : FORMAT_LIST.get(index).anchors()) {
                anchors.add(anchor.getBytes(StandardCharsets.ISO_8859_1));
                labels.add(index);
            }
        }
        return new ByteMatcher(anchors, labels.stream().mapToInt(Integer::intValue).toArray(), FORMAT_LIST.size());
    }

    /** Stops a scan: its reason is recorded ({@link Walk#unscannable}). */
    private static final class Stop extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Stop() {
            super(null, null, false, false);
        }
    }

    /** The findings of one scan, the unpacking budget shared by all archives in the content, and its deadline. */
    private final class Walk {

        private final BitSet secrets = new BitSet();

        private final TreeSet<String> formats = new TreeSet<>();

        /** The formats found, by index in {@link #FORMAT_LIST}. */
        private final BitSet found = new BitSet(FORMAT_LIST.size());

        private final long started;

        /** The windows' buffers, one per level of nesting, kept for the next member of that level. */
        private final byte[][] buffers = new byte[MAX_DEPTH + 1][];

        private @Nullable String unscannable;

        private long expanded;

        private int reads;

        /** The content of a streamed scan, whose waiting does not count; null for content held whole. */
        private final @Nullable Source source;

        Walk(long started, @Nullable Source source) {
            this.started = started;
            this.source = source;
        }

        ScanReport report() {
            List<String> secrets = this.secrets.stream().mapToObj(names::get).sorted().toList();
            return new ScanReport(secrets, new ArrayList<>(formats), unscannable);
        }

        /**
         * Scans a stream of content at a level of nesting: as it is, and unpacked when it is an archive. Every byte
         * is scanned once as it is read, by the archive's reader or else to the end.
         */
        void visit(InputStream content, int depth) throws IOException {
            PushbackInputStream in = new PushbackInputStream(content, 4);
            byte[] magic = in.readNBytes(4);
            in.unread(magic);
            Window window = new Window(depth);
            InputStream scanned = new Scanned(in, window);
            if (isZip(magic)) {
                unpackZip(scanned, depth);
            }
            else if (isGzip(magic)) {
                unpackGzip(scanned, depth);
            }
            byte[] rest = new byte[8 * 1024];
            while (scanned.read(rest) != -1) {
                // What an archive's reader left (a zip's central directory), or the content itself, is scanned too.
            }
            window.finish();
        }

        private void unpackZip(InputStream scanned, int depth) throws IOException {
            deeper(depth);
            try (ZipInputStream zip = new ZipInputStream(new Shield(scanned))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        visit(new Unpacked(zip), depth + 1);
                    }
                }
            }
            catch (IOException | IllegalArgumentException ex) {
                // ZipException for encrypted entries ("encrypted ZIP entry not supported") and damaged archives.
                stop("an encrypted or damaged zip archive");
            }
        }

        private void unpackGzip(InputStream scanned, int depth) throws IOException {
            deeper(depth);
            try (GZIPInputStream gzip = new GZIPInputStream(new Shield(scanned))) {
                visit(new Unpacked(gzip), depth + 1);
            }
            catch (IOException ex) {
                stop("a damaged gzip stream");
            }
        }

        private void deeper(int depth) {
            if (depth >= MAX_DEPTH) {
                stop("archives nested more than " + MAX_DEPTH + " levels deep");
            }
        }

        /** Counts bytes an archive unpacked against the budget. */
        private void unpacked(int count) {
            expanded += count;
            if (expanded > maxExpandedBytes) {
                stop("archive content larger than " + maxExpandedBytes / (1024 * 1024) + " MiB when unpacked");
            }
        }

        /** Stops the scan once it took longer than its bound. */
        private void inTime() {
            long waited = source != null ? source.waited : 0;
            if (System.nanoTime() - started - waited > maxScanNanos) {
                stop(TOO_SLOW);
            }
        }

        private void stop(String reason) {
            unscannable(reason);
            throw new Stop();
        }

        private void unscannable(String reason) {
            if (unscannable == null) {
                unscannable = reason;
            }
        }

        /**
         * The content of one level as it streams: the values' pass sees every byte once; the formats are matched
         * window by window. A window is matched when it is full, and the next one starts with the byte before its
         * last {@value #OVERLAP_BYTES} (for a pattern's left guard) and those bytes; a match counts in a full window
         * only when it starts before them, so that the bytes after a match, which a right guard reads, are in the
         * window where it counts. The last window counts every match.
         */
        private final class Window {

            private final int depth;

            private final ByteMatcher.Pass values;

            private byte[] buffer;

            private int length;

            /** The first position of the buffer no window matched yet; the one before it is context. */
            private int from;

            Window(int depth) {
                this.depth = depth;
                this.values = matcher.pass(secrets);
                byte[] kept = buffers[depth];
                this.buffer = kept != null ? kept : new byte[FIRST_WINDOW_BYTES];
                buffers[depth] = buffer;
            }

            void accept(byte[] bytes, int offset, int count) {
                values.feed(bytes, offset, count);
                inTime();
                int at = offset;
                int left = count;
                while (left > 0 && found.cardinality() < FORMAT_LIST.size()) {
                    if (length == buffer.length) {
                        if (buffer.length < WINDOW_BYTES) {
                            buffer = Arrays.copyOf(buffer, Math.min(WINDOW_BYTES, buffer.length * 2));
                            buffers[depth] = buffer;
                        }
                        else {
                            match(length - OVERLAP_BYTES);
                            int keep = OVERLAP_BYTES + 1;
                            System.arraycopy(buffer, length - keep, buffer, 0, keep);
                            length = keep;
                            from = 1;
                        }
                    }
                    int part = Math.min(left, buffer.length - length);
                    System.arraycopy(bytes, at, buffer, length, part);
                    length += part;
                    at += part;
                    left -= part;
                }
            }

            void finish() {
                match(length);
            }

            /**
             * Matches the formats not found yet at each of their anchors from {@link #from}; a match counts when it
             * starts before {@code before}.
             */
            private void match(int before) {
                if (from >= length || found.cardinality() == FORMAT_LIST.size()) {
                    return;
                }
                CharSequence text = new Latin1(buffer, length);
                Matcher[] matchers = new Matcher[FORMAT_LIST.size()];
                ANCHORS.forEach(buffer, from, length, (index, start) -> {
                    if (start >= before || found.get(index)) {
                        return;
                    }
                    Matcher match = matchers[index];
                    if (match == null) {
                        match = FORMAT_LIST.get(index).pattern().matcher(text).useTransparentBounds(true)
                            .useAnchoringBounds(false);
                        matchers[index] = match;
                    }
                    if (match.region(start, length).lookingAt()) {
                        found.set(index);
                        formats.add(FORMAT_LIST.get(index).name());
                    }
                });
                inTime();
            }
        }

        /** The bytes of a window as ISO-8859-1 characters, without a copy; reading them keeps to the deadline. */
        private final class Latin1 implements CharSequence {

            private final byte[] bytes;

            private final int length;

            Latin1(byte[] bytes, int length) {
                this.bytes = bytes;
                this.length = length;
            }

            @Override
            public int length() {
                return length;
            }

            @Override
            public char charAt(int index) {
                if ((++reads & 0xffff) == 0) {
                    inTime();
                }
                return (char) (bytes[index] & 0xff);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return new String(bytes, start, end - start, StandardCharsets.ISO_8859_1);
            }

            @Override
            public String toString() {
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
        }

        /** A stream whose every byte read goes through a window as well. */
        private final class Scanned extends FilterInputStream {

            private final Window window;

            private final byte[] one = new byte[1];

            Scanned(InputStream in, Window window) {
                super(in);
                this.window = window;
            }

            @Override
            public int read() throws IOException {
                int value = in.read();
                if (value >= 0) {
                    one[0] = (byte) value;
                    window.accept(one, 0, 1);
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int count) throws IOException {
                int read = in.read(bytes, offset, count);
                if (read > 0) {
                    window.accept(bytes, offset, read);
                }
                return read;
            }

            @Override
            public long skip(long count) throws IOException {
                return skipByReading(this, count);
            }
        }

        /** What an archive unpacks, counted against the budget; closing it leaves the archive open. */
        private final class Unpacked extends FilterInputStream {

            Unpacked(InputStream archive) {
                super(archive);
            }

            @Override
            public int read() throws IOException {
                int value = in.read();
                if (value >= 0) {
                    unpacked(1);
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int count) throws IOException {
                int read = in.read(bytes, offset, count);
                if (read > 0) {
                    unpacked(read);
                }
                return read;
            }

            @Override
            public long skip(long count) throws IOException {
                return skipByReading(this, count);
            }

            @Override
            public void close() {
                // The archive's reader is closed by its owner.
            }
        }
    }

    /** A stream an archive's reader may close, which leaves the stream below open. */
    /** The content of a streamed scan: how long its reads waited, and the failure of one. */
    private static final class Source extends FilterInputStream {

        private long waited;

        private @Nullable IOException failure;

        Source(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            long from = System.nanoTime();
            try {
                return in.read();
            }
            catch (IOException ex) {
                failure = ex;
                throw ex;
            }
            finally {
                waited += System.nanoTime() - from;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            long from = System.nanoTime();
            try {
                return in.read(buffer, offset, length);
            }
            catch (IOException ex) {
                failure = ex;
                throw ex;
            }
            finally {
                waited += System.nanoTime() - from;
            }
        }
    }

    private static final class Shield extends FilterInputStream {

        Shield(InputStream in) {
            super(in);
        }

        @Override
        public long skip(long count) throws IOException {
            return skipByReading(this, count);
        }

        @Override
        public void close() {
            // The stream below is read to its end, then closed by its owner.
        }
    }

    /** Skips by reading, so that a skipped byte is scanned and counted as any other. */
    private static long skipByReading(InputStream in, long count) throws IOException {
        byte[] skipped = new byte[(int) Math.min(8 * 1024, Math.max(count, 0))];
        long done = 0;
        while (done < count) {
            int read = in.read(skipped, 0, (int) Math.min(skipped.length, count - done));
            if (read < 0) {
                break;
            }
            done += read;
        }
        return done;
    }

    private static boolean isZip(byte[] magic) {
        return magic.length >= 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
    }

    private static boolean isGzip(byte[] magic) {
        return magic.length >= 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b;
    }
}
