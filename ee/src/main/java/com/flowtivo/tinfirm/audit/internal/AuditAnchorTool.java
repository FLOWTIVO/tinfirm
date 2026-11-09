package com.flowtivo.tinfirm.audit.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The offline tool of the audit's anchor (ADR-0111, decision 4), {@code tinfirm-audit-verify} in the server's image.
 * It needs neither the server nor the database's writer: the checkpoints come from the sink, the trusted public keys
 * from the command line, and the chains from a read-only connection to the database or from exports.
 *
 * <pre>
 * tinfirm-audit-verify keygen &lt;key id&gt; [&lt;directory&gt;]
 *     Writes &lt;key id&gt;.key (the private key, PKCS#8 PEM, readable by its owner only) and &lt;key id&gt;.pub (the
 *     public key, X.509 PEM) to the directory (the current one by default).
 *
 * tinfirm-audit-verify verify --checkpoints &lt;source&gt; --public-key &lt;key id&gt;=&lt;file&gt; [--public-key …]
 *         (--jdbc-url &lt;url&gt; [--jdbc-user &lt;user&gt;] | --export &lt;file.ndjson&gt; [--export …])
 *         [--distrust &lt;key id&gt;@&lt;time&gt;]
 *     &lt;source&gt;: the instance's folder of the file sink (&lt;dir&gt;/&lt;instance&gt;), an S3 prefix
 *     (s3://&lt;bucket&gt;/&lt;prefix&gt;&lt;instance&gt;/, with --s3-endpoint, --s3-region, --s3-path-style and the key in
 *     AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY), or a file of syslog messages (syslog:&lt;file&gt;). The database's
 *     password is read from TINFIRM_AUDIT_VERIFY_JDBC_PASSWORD, never from the command line.
 * </pre>
 *
 * Exit status: 0 when everything verified, 1 on any finding, 2 for a wrong invocation or a source that cannot be
 * read.
 */
public final class AuditAnchorTool {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final PrintStream out;

    private AuditAnchorTool(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.getenv()));
    }

    /** Runs the tool; the exit status. */
    static int run(String[] args, PrintStream out, Map<String, String> env) {
        if (args.length == 0) {
            usage(out);
            return 2;
        }
        try {
            return switch (args[0]) {
                case "keygen" -> new AuditAnchorTool(out).keygen(List.of(args).subList(1, args.length));
                case "verify" -> new AuditAnchorTool(out).verify(Options.parse(List.of(args).subList(1, args.length)),
                        env);
                default -> {
                    usage(out);
                    yield 2;
                }
            };
        }
        catch (UsageException ex) {
            out.println("error: " + ex.getMessage());
            usage(out);
            return 2;
        }
        catch (IOException | SQLException | IllegalArgumentException | IllegalStateException ex) {
            out.println("error: " + ex.getMessage());
            return 2;
        }
    }

    private static void usage(PrintStream out) {
        out.println("""
                usage: tinfirm-audit-verify keygen <key id> [<directory>]
                       tinfirm-audit-verify verify --checkpoints <dir | s3://bucket/prefix/instance/ | syslog:file>
                           --public-key <key id>=<file> [--public-key ...]
                           (--jdbc-url <url> [--jdbc-user <user>] | --export <file.ndjson> [--export ...])
                           [--distrust <key id>@<time>] [--s3-endpoint <url>] [--s3-region <region>] [--s3-path-style]
                       The database's password: TINFIRM_AUDIT_VERIFY_JDBC_PASSWORD. An S3 key: AWS_ACCESS_KEY_ID and
                       AWS_SECRET_ACCESS_KEY.""");
    }

    // ---- keygen ----------------------------------------------------------------------------------------------------

    private int keygen(List<String> args) throws IOException {
        if (args.isEmpty() || args.size() > 2) {
            throw new UsageException("keygen takes a key id and, optionally, a directory");
        }
        String id = args.getFirst().toLowerCase(Locale.ROOT);
        if (!AuditAnchorProperties.KEY_ID.matcher(id).matches()) {
            throw new UsageException("a key id is 1 to 32 lowercase letters and digits");
        }
        Path dir = Path.of(args.size() == 2 ? args.get(1) : ".");
        Path privateFile = dir.resolve(id + ".key");
        Path publicFile = dir.resolve(id + ".pub");
        if (Files.exists(privateFile) || Files.exists(publicFile)) {
            throw new UsageException(privateFile + " or " + publicFile + " exists already");
        }
        KeyPair pair = AnchorKeys.generate();
        Files.createFile(privateFile);
        try {
            Files.setPosixFilePermissions(privateFile, PosixFilePermissions.fromString("rw-------"));
        }
        catch (UnsupportedOperationException ex) {
            // A file system without POSIX permissions: the operator protects the file.
        }
        Files.writeString(privateFile, AnchorKeys.pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(publicFile, AnchorKeys.publicPem(pair.getPublic()), StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW);
        out.println("Wrote " + privateFile + " (the private key: TINFIRM_AUDIT_ANCHOR_KEYS_"
                + id.toUpperCase(Locale.ROOT) + "_PRIVATE, a Secret) and " + publicFile
                + " (the public key: for whoever verifies, out of band).");
        return 0;
    }

    // ---- verify ----------------------------------------------------------------------------------------------------

    /** The verification's options. */
    record Options(String checkpoints, Map<String, Path> publicKeys, @Nullable String jdbcUrl,
            @Nullable String jdbcUser, List<Path> exports, Map<String, Instant> distrust, @Nullable String s3Endpoint,
            String s3Region, boolean s3PathStyle) {

        static Options parse(List<String> args) {
            String checkpoints = null;
            Map<String, Path> keys = new LinkedHashMap<>();
            String jdbcUrl = null;
            String jdbcUser = null;
            List<Path> exports = new ArrayList<>();
            Map<String, Instant> distrust = new HashMap<>();
            String endpoint = null;
            String region = "us-east-1";
            boolean pathStyle = false;
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                switch (arg) {
                    case "--checkpoints" -> checkpoints = value(args, ++i, arg);
                    case "--public-key" -> {
                        String[] parts = value(args, ++i, arg).split("=", 2);
                        if (parts.length != 2) {
                            throw new UsageException("--public-key takes <key id>=<file>");
                        }
                        keys.put(parts[0].toLowerCase(Locale.ROOT), Path.of(parts[1]));
                    }
                    case "--jdbc-url" -> jdbcUrl = value(args, ++i, arg);
                    case "--jdbc-user" -> jdbcUser = value(args, ++i, arg);
                    case "--export" -> exports.add(Path.of(value(args, ++i, arg)));
                    case "--distrust" -> {
                        String[] parts = value(args, ++i, arg).split("@", 2);
                        if (parts.length != 2) {
                            throw new UsageException("--distrust takes <key id>@<time>, such as k2026@2026-11-07T10:00:00Z");
                        }
                        distrust.put(parts[0].toLowerCase(Locale.ROOT), Instant.parse(parts[1]));
                    }
                    case "--s3-endpoint" -> endpoint = value(args, ++i, arg);
                    case "--s3-region" -> region = value(args, ++i, arg);
                    case "--s3-path-style" -> pathStyle = true;
                    default -> throw new UsageException("unknown option " + arg);
                }
            }
            if (checkpoints == null) {
                throw new UsageException("--checkpoints names where the checkpoints are");
            }
            if (keys.isEmpty()) {
                throw new UsageException("--public-key names at least one key that verifies");
            }
            if ((jdbcUrl == null) == exports.isEmpty()) {
                throw new UsageException("the chains come from --jdbc-url or from --export, one of the two");
            }
            return new Options(checkpoints, keys, jdbcUrl, jdbcUser, exports, distrust, endpoint, region, pathStyle);
        }

        private static String value(List<String> args, int index, String option) {
            if (index >= args.size()) {
                throw new UsageException(option + " needs a value");
            }
            return args.get(index);
        }
    }

    /** A wrong invocation. */
    static final class UsageException extends RuntimeException {

        UsageException(String message) {
            super(message);
        }
    }

    /** A checkpoint as the tool read it, with its place in the sink and what the sink says of it. */
    private record Read(long number, Checkpoint.Signed signed, @Nullable Instant storedAt, int versions) {
    }

    /** What a chain must hold: the head a checkpoint names. */
    private record Expected(long checkpoint, long seq, String hash, Instant issuedAt) {
    }

    private int verify(Options options, Map<String, String> env) throws IOException, SQLException {
        Map<String, PublicKey> trusted = new LinkedHashMap<>();
        options.publicKeys().forEach((id, file) -> {
            try {
                trusted.put(id, AnchorKeys.publicKey(Files.readString(file, StandardCharsets.US_ASCII), id));
            }
            catch (IOException ex) {
                throw new IllegalArgumentException("Cannot read the public key " + file + ": " + ex.getMessage(), ex);
            }
        });
        AnchorKeys keys = AnchorKeys.verifyingOnly(trusted);
        List<String> findings = new ArrayList<>();

        List<Read> checkpoints = readCheckpoints(options, env, keys, findings);
        Map<String, List<Expected>> expected = new TreeMap<>();
        long previousNumber = 0;
        String previousDigest = null;
        Instant firstAt = null;
        Instant lastAt = null;
        Duration longest = Duration.ZERO;
        Instant previousAt = null;
        for (Read read : checkpoints) {
            Checkpoint checkpoint = read.signed().checkpoint();
            boolean usable = true;
            if (!read.signed().verified()) {
                findings.add(String.format("checkpoint %-8d BAD SIGNATURE: %s", read.number(), read.signed().reason()));
                usable = false;
            }
            else if (checkpoint.number() != read.number()) {
                findings.add(String.format("checkpoint %-8d MISPLACED: it names number %d", read.number(),
                        checkpoint.number()));
                usable = false;
            }
            Instant distrustedAfter = options.distrust().get(checkpoint.keyId());
            if (usable && distrustedAfter != null) {
                Instant when = read.storedAt() != null ? read.storedAt() : checkpoint.issuedAt();
                if (read.storedAt() == null || when.isAfter(distrustedAfter)) {
                    findings.add(String.format("checkpoint %-8d DISTRUSTED: signed with %s, stored %s, after %s",
                            read.number(), checkpoint.keyId(), read.storedAt() == null ? "at an unknown time" : when,
                            distrustedAfter));
                    usable = false;
                }
            }
            if (read.versions() > 1) {
                findings.add(String.format("checkpoint %-8d OVERWRITTEN: the sink holds %d versions; the first is"
                        + " read", read.number(), read.versions()));
            }
            if (previousNumber > 0 && read.number() != previousNumber + 1) {
                findings.add(String.format("checkpoint %-8s MISSING (%d -> %d)",
                        read.number() - 1 == previousNumber + 1 ? Long.toString(previousNumber + 1)
                                : (previousNumber + 1) + "-" + (read.number() - 1),
                        previousNumber, read.number()));
            }
            else if (previousNumber > 0 && previousDigest != null && !checkpoint.prev().equals(previousDigest)) {
                findings.add(String.format("checkpoint %-8d BROKEN LINK: its prev is not checkpoint %d's digest",
                        read.number(), previousNumber));
            }
            else if (previousNumber == 0 && read.number() == 1 && !checkpoint.prev().equals(Checkpoint.NO_PREVIOUS)) {
                findings.add(String.format("checkpoint %-8d BROKEN LINK: the first names a previous checkpoint",
                        read.number()));
            }
            previousNumber = read.number();
            previousDigest = checkpoint.digest();
            if (usable) {
                for (Checkpoint.Head head : checkpoint.chains()) {
                    expected.computeIfAbsent(chainName(head.companyId()), name -> new ArrayList<>())
                        .add(new Expected(read.number(), head.seq(), head.hash(), checkpoint.issuedAt()));
                }
                if (previousAt != null) {
                    Duration between = Duration.between(previousAt, checkpoint.issuedAt());
                    if (between.compareTo(longest) > 0) {
                        longest = between;
                    }
                }
                previousAt = checkpoint.issuedAt();
                if (firstAt == null) {
                    firstAt = checkpoint.issuedAt();
                }
                lastAt = checkpoint.issuedAt();
            }
        }
        if (checkpoints.isEmpty()) {
            findings.add("checkpoints        NONE found in " + options.checkpoints());
        }
        else {
            long first = checkpoints.getFirst().number();
            out.printf("checkpoints   %d..%d read (keys %s); %s to %s; the longest time between two: %s%n", first,
                    checkpoints.getLast().number(), String.join(", ", trusted.keySet()), firstAt, lastAt, longest);
            if (first != 1) {
                // The writer never starts a sink's chain anywhere but at 1 (ADR-0111, decision 2): the sink lost its
                // beginning, or this is not the whole sink.
                findings.add(String.format("checkpoints        STARTS AT %d: checkpoints 1..%d are not in this source",
                        first, first - 1));
            }
        }

        Map<String, ChainResult> chains = options.jdbcUrl() != null
                ? chainsFromDatabase(options, env, expected)
                : chainsFromExports(options.exports(), expected);
        for (Map.Entry<String, List<Expected>> chain : expected.entrySet()) {
            if (!chains.containsKey(chain.getKey()) && options.jdbcUrl() != null) {
                Expected last = chain.getValue().getLast();
                findings.add(String.format("chain %-20s MISSING: checkpoint %d names seq %d, the database holds"
                        + " none of its events", chain.getKey(), last.checkpoint(), last.seq()));
            }
        }
        for (Map.Entry<String, ChainResult> chain : chains.entrySet()) {
            ChainResult result = chain.getValue();
            String name = chain.getKey();
            if (result.firstBroken() != null) {
                findings.add(String.format("chain %-20s BROKEN at seq %d: it does not recompute", name,
                        result.firstBroken()));
            }
            for (String mismatch : result.mismatches()) {
                findings.add(String.format("chain %-20s %s", name, mismatch));
            }
            if (result.firstBroken() == null && result.mismatches().isEmpty()) {
                // A range that does not start at the chain's first event chains from the export's own prevHash:
                // only the events up to a checkpoint inside the range are proven, never those before it.
                String part = result.fromSeq() > 1
                        ? "; a part of the chain, whose first event chains from the export's prevHash: only the events"
                                + " up to a checkpoint in the range are proven"
                        : "";
                out.printf("chain %-20s seq %d..%d OK%s%s%n", name, result.fromSeq(), result.toSeq(),
                        result.anchoredSeq() != null ? ", anchored to seq " + result.anchoredSeq() + " (checkpoint "
                                + result.anchoredBy() + ")" : ", no checkpoint names it in this range", part);
            }
        }
        for (String finding : findings) {
            out.println(finding);
        }
        out.println(findings.isEmpty() ? "result: OK" : "result: FAILED (" + findings.size() + " findings)");
        return findings.isEmpty() ? 0 : 1;
    }

    private static String chainName(@Nullable UUID companyId) {
        return companyId == null ? "instance" : companyId.toString();
    }

    private List<Read> readCheckpoints(Options options, Map<String, String> env, AnchorKeys keys,
            List<String> findings) throws IOException {
        String source = options.checkpoints();
        List<Read> reads = new ArrayList<>();
        if (source.startsWith("syslog:")) {
            Path file = Path.of(source.substring("syslog:".length()));
            try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    int start = line.indexOf("{\"checkpoint\":");
                    if (start < 0) {
                        continue;
                    }
                    Checkpoint.Signed signed = Checkpoint.parse(line.substring(start), keys);
                    reads.add(new Read(signed.checkpoint().number(), signed, null, 1));
                }
            }
            reads.sort((a, b) -> Long.compare(a.number(), b.number()));
            // A collector may hold a message twice (a retry): the same checkpoint twice is one.
            List<Read> distinct = new ArrayList<>();
            for (Read read : reads) {
                if (!distinct.isEmpty() && distinct.getLast().number() == read.number()
                        && distinct.getLast().signed().checkpoint().canonical()
                            .equals(read.signed().checkpoint().canonical())) {
                    continue;
                }
                distinct.add(read);
            }
            return distinct;
        }
        AnchorSink sink;
        UUID instance;
        if (source.startsWith("s3://")) {
            String rest = source.substring("s3://".length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                throw new UsageException("an S3 source is s3://<bucket>/<prefix><instance>/");
            }
            String bucket = rest.substring(0, slash);
            String path = rest.substring(slash + 1);
            path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int last = path.lastIndexOf('/');
            String prefix = last < 0 ? "" : path.substring(0, last + 1);
            instance = instanceOf(path.substring(last + 1));
            String accessKey = env.get("AWS_ACCESS_KEY_ID");
            String secret = env.get("AWS_SECRET_ACCESS_KEY");
            if (accessKey == null || secret == null) {
                throw new UsageException("an S3 source needs AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY");
            }
            sink = new S3AnchorSink(new AuditAnchorProperties.S3(options.s3Endpoint(), options.s3Region(), bucket,
                    options.s3PathStyle(), prefix, accessKey, secret, Duration.ofDays(1), false), null, false,
                    Clock.systemUTC());
        }
        else {
            Path folder = Path.of(source).toAbsolutePath().normalize();
            if (!Files.isDirectory(folder)) {
                throw new UsageException(folder + " is not a directory");
            }
            instance = instanceOf(folder.getFileName().toString());
            sink = new FileAnchorSink(folder.getParent());
        }
        try (AnchorSink reader = sink) {
            for (long number : reader.numbers(instance, 0)) {
                Optional<AnchorSink.Stored> stored = reader.read(instance, number);
                if (stored.isEmpty()) {
                    continue;
                }
                Checkpoint.Signed signed;
                try {
                    signed = Checkpoint.parse(stored.get().document(), keys);
                }
                catch (IllegalArgumentException ex) {
                    findings.add(String.format("checkpoint %-8d UNREADABLE: %s", number, ex.getMessage()));
                    signed = null;
                }
                if (signed != null) {
                    if (!signed.checkpoint().instance().equals(instance)) {
                        signed = new Checkpoint.Signed(signed.checkpoint(), false,
                                "it names the instance " + signed.checkpoint().instance());
                    }
                    reads.add(new Read(number, signed, stored.get().storedAt(), stored.get().versions()));
                }
            }
        }
        return reads;
    }

    private static UUID instanceOf(String name) {
        try {
            return UUID.fromString(name);
        }
        catch (IllegalArgumentException ex) {
            throw new UsageException("the checkpoints' source ends in the instance's id, a UUID, not " + name);
        }
    }

    /** A chain verified: its range, its first event that does not recompute, and where it disagrees. */
    private record ChainResult(long fromSeq, long toSeq, @Nullable Long firstBroken, List<String> mismatches,
            @Nullable Long anchoredSeq, @Nullable Long anchoredBy) {
    }

    /** Verifies one chain's events as they stream, against what the checkpoints name. */
    private static final class ChainCheck {

        private final List<Expected> expected;

        private final Map<Long, Expected> bySeq = new HashMap<>();

        private final Map<Long, Boolean> seen = new HashMap<>();

        private final List<String> mismatches = new ArrayList<>();

        private long fromSeq = -1;

        private long expectedSeq;

        private String previousHash;

        private long lastSeq;

        private @Nullable Long firstBroken;

        ChainCheck(List<Expected> expected, long fromSeq, String prevHash) {
            this.expected = expected;
            for (Expected head : expected) {
                bySeq.putIfAbsent(head.seq(), head);
            }
            this.expectedSeq = fromSeq;
            this.previousHash = prevHash;
        }

        void accept(long seq, String canonical, String prevHash, String hash) {
            if (fromSeq < 0) {
                fromSeq = seq;
            }
            if (firstBroken == null) {
                if (seq != expectedSeq || !prevHash.equals(previousHash)
                        || !hash.equals(AuditCanonicalForm.hash(prevHash, canonical))) {
                    firstBroken = seq != expectedSeq ? expectedSeq : seq;
                }
            }
            Expected head = bySeq.get(seq);
            if (head != null) {
                seen.put(seq, true);
                for (Expected each : expected) {
                    if (each.seq() == seq && !each.hash().equals(hash)) {
                        mismatches.add(String.format("MISMATCH at seq %d: the hash differs from checkpoint %d (%s)",
                                seq, each.checkpoint(), each.issuedAt()));
                    }
                }
            }
            previousHash = hash;
            expectedSeq = seq + 1;
            lastSeq = seq;
        }

        /** {@code complete}: the source holds the whole chain, so that a checkpoint beyond its end is a cut. */
        ChainResult result(boolean complete, long coveredTo) {
            Long anchoredSeq = null;
            Long anchoredBy = null;
            for (Expected head : expected) {
                if (head.seq() == 0) {
                    continue;
                }
                if (head.seq() > lastSeq) {
                    if (complete) {
                        mismatches.add(String.format("SHORTER: the last event is seq %d, checkpoint %d (%s) names"
                                + " seq %d", lastSeq, head.checkpoint(), head.issuedAt(), head.seq()));
                    }
                    continue;
                }
                if (head.seq() <= coveredTo && head.seq() >= fromSeq && !seen.containsKey(head.seq())) {
                    mismatches.add(String.format("MISMATCH at seq %d: the event checkpoint %d names is missing",
                            head.seq(), head.checkpoint()));
                }
                if (seen.containsKey(head.seq()) && (anchoredSeq == null || head.seq() >= anchoredSeq)) {
                    anchoredSeq = head.seq();
                    anchoredBy = head.checkpoint();
                }
            }
            return new ChainResult(Math.max(fromSeq, 0), lastSeq, firstBroken, List.copyOf(mismatches), anchoredSeq,
                    anchoredBy);
        }
    }

    private Map<String, ChainResult> chainsFromDatabase(Options options, Map<String, String> env,
            Map<String, List<Expected>> expected) throws SQLException {
        Properties connection = new Properties();
        if (options.jdbcUser() != null) {
            connection.setProperty("user", options.jdbcUser());
        }
        String password = env.get("TINFIRM_AUDIT_VERIFY_JDBC_PASSWORD");
        if (password != null) {
            connection.setProperty("password", password);
        }
        connection.setProperty("readOnly", "true");
        Map<String, ChainResult> results = new TreeMap<>();
        try (Connection db = DriverManager.getConnection(options.jdbcUrl(), connection)) {
            db.setReadOnly(true);
            db.setAutoCommit(false);
            // Row-level security shows every chain to server-side work (V016's app.system); the role needs nothing
            // but SELECT on audit_event.
            try (PreparedStatement system = db.prepareStatement("SELECT set_config('app.system', 'true', true)")) {
                system.execute();
            }
            try (PreparedStatement select = db.prepareStatement("SELECT " + AuditEventStore.COLUMNS
                    + " FROM audit_event ORDER BY company_id NULLS FIRST, seq")) {
                select.setFetchSize(1000);
                try (ResultSet rows = select.executeQuery()) {
                    String current = null;
                    ChainCheck check = null;
                    while (rows.next()) {
                        StoredAuditEvent event = AuditEventStore.map(rows, 0);
                        String name = chainName(event.companyId());
                        if (!name.equals(current)) {
                            if (check != null) {
                                results.put(current, check.result(true, Long.MAX_VALUE));
                            }
                            current = name;
                            check = new ChainCheck(expected.getOrDefault(name, List.of()), 1,
                                    AuditCanonicalForm.GENESIS_HASH);
                        }
                        check.accept(event.seq(), AuditCanonicalForm.canonical(event.content()), event.prevHash(),
                                event.hash());
                    }
                    if (check != null) {
                        results.put(current, check.result(true, Long.MAX_VALUE));
                    }
                }
            }
            db.rollback();
        }
        return results;
    }

    private Map<String, ChainResult> chainsFromExports(List<Path> exports, Map<String, List<Expected>> expected)
            throws IOException {
        Map<String, ChainResult> results = new TreeMap<>();
        for (Path export : exports) {
            try (BufferedReader lines = Files.newBufferedReader(export, StandardCharsets.UTF_8)) {
                String headerLine = lines.readLine();
                if (headerLine == null) {
                    throw new IllegalArgumentException(export + " is empty");
                }
                JsonNode header = MAPPER.readTree(headerLine);
                if (!"export".equals(header.path("kind").asString(null))) {
                    throw new IllegalArgumentException(export + " is not an export of the audit chain");
                }
                String name = header.path("companyId").asString();
                long fromSeq = header.path("fromSeq").asLong();
                long toSeq = header.path("toSeq").asLong();
                ChainCheck check = new ChainCheck(expected.getOrDefault(name, List.of()), fromSeq,
                        header.path("prevHash").isNull() ? AuditCanonicalForm.GENESIS_HASH
                                : header.path("prevHash").asString());
                long headSeq = -1;
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    ObjectNode event = (ObjectNode) MAPPER.readTree(line);
                    if ("head".equals(event.path("kind").asString(null))) {
                        headSeq = event.path("seq").asLong();
                        continue;
                    }
                    String prevHash = event.path("prevHash").asString();
                    String hash = event.path("hash").asString();
                    event.remove("prevHash");
                    event.remove("hash");
                    check.accept(event.path("seq").asLong(), AuditCanonicalForm.canonicalJson(event), prevHash, hash);
                }
                // An export from the chain's first event up to its head holds the whole chain at that time.
                boolean complete = fromSeq <= 1 && headSeq >= 0 && toSeq == headSeq;
                ChainResult result = check.result(complete, toSeq);
                // Two exports of one chain are reported apart, the second by its first seq.
                results.put(results.containsKey(name) ? name + " from " + fromSeq : name, result);
            }
        }
        return results;
    }
}
