package com.flowtivo.tinfirm.vault.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.vault.ScanReport;

/** ADR-0014: the upload scan finds vault values in every supported encoding, known formats, and archive content. */
class CredentialScanTest {

    // Test fixtures, not real credentials.
    private static final String TOKEN = "fixture-git-token-0123456789";

    private static final String OTHER = "another-fixture-value";

    @Test
    void findsAVaultValueInPlainHexUrlAndBase64Form() {
        try (CredentialScan scan = scan()) {
            byte[] value = TOKEN.getBytes(StandardCharsets.UTF_8);
            assertThat(scan.scan(text("token: " + TOKEN + "\n")).vaultSecrets()).containsExactly("GIT_TOKEN");
            assertThat(scan.scan(text(HexFormat.of().formatHex(value))).vaultSecrets()).containsExactly("GIT_TOKEN");
            assertThat(scan.scan(text(HexFormat.of().withUpperCase().formatHex(value))).vaultSecrets())
                .containsExactly("GIT_TOKEN");
            // The base64 of the value inside a longer encoded blob, at each of the three byte alignments.
            for (String prefix : List.of("", "a", "ab", "abc")) {
                for (Base64.Encoder encoder : List.of(Base64.getEncoder(), Base64.getUrlEncoder())) {
                    String blob = encoder.encodeToString((prefix + "user:" + TOKEN + "@host").getBytes(StandardCharsets.UTF_8));
                    assertThat(scan.scan(text("Authorization: Basic " + blob)).vaultSecrets()).as(prefix + blob)
                        .containsExactly("GIT_TOKEN");
                }
            }
        }
        try (CredentialScan scan = new CredentialScan(values(Map.of("WEBHOOK", "p@ss word/with?chars")))) {
            String encoded = URLEncoder.encode("p@ss word/with?chars", StandardCharsets.UTF_8);
            assertThat(scan.scan(text("https://example.test/?secret=" + encoded)).vaultSecrets())
                .containsExactly("WEBHOOK");
        }
    }

    @Test
    void reportsEverySecretFoundAndNothingForCleanContent() {
        try (CredentialScan scan = scan()) {
            ScanReport both = scan.scan(text(OTHER + " and " + TOKEN));
            assertThat(both.vaultSecrets()).containsExactly("GIT_TOKEN", "OTHER");
            ScanReport clean = scan.scan(text("Nothing to see here: fixture-git-token-012345678 is one short."));
            assertThat(clean.clean()).isTrue();
        }
    }

    @Test
    void ignoresValuesTooShortToMatchReliablyAndStripsSurroundingWhitespace() {
        try (CredentialScan scan = new CredentialScan(values(Map.of("PIN", "1234567", "TRAILING", "value-with-newline\n")))) {
            assertThat(scan.scan(text("pin 1234567")).clean()).isTrue();
            assertThat(scan.scan(text("value-with-newline;")).vaultSecrets()).containsExactly("TRAILING");
        }
    }

    @Test
    void findsKnownCredentialFormats() {
        // Fixtures are built from parts, so gitleaks and semgrep do not flag them as secrets.
        try (CredentialScan scan = new CredentialScan(Map.of())) {
            assertThat(scan.scan(text("GITHUB=ghp_" + "a".repeat(36))).formats()).containsExactly("GitHub token");
            assertThat(scan.scan(text("-----BEGIN OPENSSH " + "PRIVATE KEY-----\nabc")).formats())
                .containsExactly("Private key");
            assertThat(scan.scan(text("CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-" + "x".repeat(40))).formats())
                .containsExactly("Anthropic API key or OAuth token");
            assertThat(scan.scan(text("aws AKIA" + "Q".repeat(16))).formats()).containsExactly("AWS access key");
            assertThat(scan.scan(text("tfr_" + "A".repeat(43))).formats()).containsExactly("Tinfirm runner host token");
            assertThat(scan.scan(text("a plain README with sk-short and ghp_short")).clean()).isTrue();
        }
    }

    @Test
    void scansInsideZipAndGzipContentIncludingNestedArchives() throws IOException {
        try (CredentialScan scan = scan()) {
            byte[] zip = zip("docs/notes.txt", text("deflated " + TOKEN));
            assertThat(scan.scan(zip).vaultSecrets()).containsExactly("GIT_TOKEN");
            assertThat(scan.scan(gzip(zip)).vaultSecrets()).containsExactly("GIT_TOKEN");
            assertThat(scan.scan(zip("inner.zip", zip("key.pem", text("-----BEGIN RSA " + "PRIVATE KEY-----")))).formats())
                .containsExactly("Private key");
        }
    }

    @Test
    void anArchiveThatCannotBeReadMakesTheContentUnscannable() throws IOException {
        try (CredentialScan scan = scan()) {
            byte[] encrypted = zip("secret.txt", text("hidden"));
            encrypted[6] |= 1; // general purpose flag bit 0 of the local header: encrypted
            assertThat(scan.scan(encrypted).unscannable()).contains("encrypted or damaged zip");

            byte[] deep = text("x");
            for (int i = 0; i < CredentialScan.MAX_DEPTH + 1; i++) {
                deep = gzip(deep);
            }
            assertThat(scan.scan(deep).unscannable()).contains("nested more than");
        }
        try (CredentialScan small = new CredentialScan(Map.of(), 1024 * 1024)) {
            byte[] bomb = gzip(new byte[2 * 1024 * 1024]);
            assertThat(small.scan(bomb).unscannable()).contains("larger than 1 MiB when unpacked");
        }
    }

    @Test
    void everyFormatScansItsWorstInputInLinearTime() {
        // Each format's own prefix, repeated, and its prefix before a long run that never completes it: a pattern
        // without a left guard or a bound tries every start again (a JWT of "eyJ" × 100,000 took minutes).
        Map<String, List<String>> worst = new LinkedHashMap<>();
        worst.put("JSON Web Token (e.g. a Codex access token)", List.of("eyJ".repeat(100_000),
                ("eyJ" + "a".repeat(8) + ".").repeat(30_000), "eyJ" + "a".repeat(8) + ".eyJ" + "b".repeat(300_000)));
        worst.put("GitHub token", List.of("ghp_".repeat(100_000), "github_pat_".repeat(30_000)));
        worst.put("AWS access key", List.of("AKIA".repeat(100_000)));
        worst.put("Anthropic API key or OAuth token", List.of("sk-ant-api01-x!".repeat(30_000),
                "sk-ant-oat".repeat(40_000)));
        worst.put("OpenAI API key", List.of("sk-".repeat(100_000), "sk-proj-x!".repeat(40_000)));
        worst.put("Private key", List.of("-----BEGIN ".repeat(40_000), "-----BEGIN " + "RSA ".repeat(100_000),
                "-".repeat(300_000)));
        worst.put("Slack token", List.of("xoxb-".repeat(80_000), "xoxb-x!".repeat(50_000)));
        worst.put("Google API key", List.of("AIza".repeat(100_000)));
        worst.put("Stripe secret key", List.of("sk_live_".repeat(50_000), "rk_live_x!".repeat(40_000)));
        worst.put("Tinfirm runner host token", List.of("tfr_".repeat(100_000)));
        assertThat(worst.keySet()).containsExactlyInAnyOrderElementsOf(CredentialScan.FORMATS.keySet());
        try (CredentialScan scan = scan()) {
            worst.forEach((format, inputs) -> inputs.forEach(input -> {
                long started = System.nanoTime();
                ScanReport report = scan.scan(text(input));
                long millis = (System.nanoTime() - started) / 1_000_000;
                assertThat(millis).as("%s: %s… of %d characters", format, input.substring(0, 20), input.length())
                    .isLessThan(2_000);
                assertThat(report.unscannable()).as(format).isNull();
            }));
        }
    }

    @Test
    void aScanThatTakesTooLongMakesTheContentUnscannable() {
        try (CredentialScan slow = new CredentialScan(values(Map.of("GIT_TOKEN", TOKEN)),
                CredentialScan.MAX_EXPANDED_BYTES, java.time.Duration.ZERO)) {
            assertThat(slow.scan(text("x".repeat(1024 * 1024))).unscannable())
                .isEqualTo("content that takes too long to scan");
        }
    }

    /**
     * A scan of content that streams in (ADR-0101) finds what a scan of the same content held whole finds, inside
     * archives too; the time it waits for the content does not count, and a failure to read it is thrown.
     */
    @Test
    void aStreamedScanFindsTheSameAndDoesNotCountItsWaitForTheContent() throws IOException {
        byte[] content = gzip(zip("docs/notes.txt", text("deflated " + TOKEN + " and AKIA" + "ABCDEFGHIJKLMNOP")));
        try (CredentialScan scan = scan()) {
            ScanReport held = scan.scan(content);
            ScanReport streamed = scan.scan(new ByteArrayInputStream(content));
            assertThat(streamed).isEqualTo(held);
            assertThat(streamed.vaultSecrets()).containsExactly("GIT_TOKEN");
        }
        // A content that takes a second to arrive, a byte at a time, against a bound of 50 ms of scanning.
        try (CredentialScan quick = new CredentialScan(values(Map.of("GIT_TOKEN", TOKEN)),
                CredentialScan.MAX_EXPANDED_BYTES, java.time.Duration.ofMillis(50))) {
            InputStream slow = new FilterInputStream(new ByteArrayInputStream(text("slow " + TOKEN))) {

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    try {
                        Thread.sleep(1000 / 34);
                    }
                    catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return super.read(buffer, offset, Math.min(length, 1));
                }
            };
            ScanReport report = quick.scan(slow);
            assertThat(report.unscannable()).isNull();
            assertThat(report.vaultSecrets()).containsExactly("GIT_TOKEN");
        }
        try (CredentialScan scan = scan()) {
            InputStream broken = new InputStream() {

                @Override
                public int read() throws IOException {
                    throw new IOException("the host went away");
                }
            };
            assertThatThrownBy(() -> scan.scan(broken)).isInstanceOf(IOException.class)
                .hasMessage("the host went away");
            // Also where an archive's reader meets it, which takes a damaged archive for unscannable otherwise.
            byte[] gzip = gzip(text("x".repeat(100_000)));
            InputStream cut = new FilterInputStream(new ByteArrayInputStream(gzip)) {

                private int read;

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (read > 100) {
                        throw new IOException("the host went away");
                    }
                    int n = super.read(buffer, offset, Math.min(length, 50));
                    read += Math.max(n, 0);
                    return n;
                }
            };
            assertThatThrownBy(() -> scan.scan(cut)).isInstanceOf(IOException.class).hasMessage("the host went away");
        }
    }

    @Test
    void contentLargerThanAWindowIsScannedAcrossItsWindows() throws IOException {
        // A value and a format that straddle the edge of a window, and a format that ends at the content's end.
        int edge = CredentialScan.WINDOW_BYTES;
        StringBuilder content = new StringBuilder("x".repeat(edge - 10)).append(TOKEN).append(' ')
            .append("y".repeat(edge)).append(" AKIA").append("Q".repeat(16)).append(' ')
            .append("z".repeat(edge - 5)).append(" tfr_").append("A".repeat(43));
        try (CredentialScan scan = scan()) {
            ScanReport report = scan.scan(text(content.toString()));
            assertThat(report.vaultSecrets()).containsExactly("GIT_TOKEN");
            assertThat(report.formats()).containsExactly("AWS access key", "Tinfirm runner host token");
            assertThat(scan.scan(gzip(text(content.toString()))).formats()).hasSize(2);
            // A key glued to more of its own characters is no key, and one alone is, wherever a window ends or the
            // next one begins.
            for (int cut : List.of(edge, edge - CredentialScan.OVERLAP_BYTES)) {
                for (int offset : List.of(0, 1, 2, 4, 19, 20, 21, 24, 40)) {
                    String before = "q".repeat(cut - offset);
                    String glued = before + "AKIA" + "Q".repeat(17) + " " + "Q".repeat(5) + "tfr_" + "A".repeat(43);
                    assertThat(scan.scan(text(glued)).formats()).as("glued, %d before %d", offset, cut).isEmpty();
                    String alone = before + "AKIA" + "Q".repeat(16) + " " + "tfr_" + "A".repeat(43);
                    assertThat(scan.scan(text(alone)).formats()).as("alone, %d before %d", offset, cut)
                        .containsExactly("AWS access key", "Tinfirm runner host token");
                }
            }
        }
    }

    @Test
    void aBombIsRefusedAtTheExpansionLimitWithoutHoldingWhatItUnpacks() throws IOException {
        assertThat(CredentialScan.MAX_EXPANDED_BYTES).isEqualTo(64L * 1024 * 1024);
        // Zeros deflated: some 256 KB that unpack to 256 MiB, nested in a zip as an Office document holds its parts.
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(deflated)) {
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 256; i++) {
                gzip.write(zeros);
            }
        }
        byte[] bomb = zip("word/document.xml.gz", deflated.toByteArray());
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
            .getThreadMXBean();
        try (CredentialScan scan = scan()) {
            long before = threads.getCurrentThreadAllocatedBytes();
            ScanReport report = scan.scan(bomb);
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            assertThat(report.unscannable()).isEqualTo("archive content larger than 64 MiB when unpacked");
            // What it unpacks streams through windows of about 1 MiB: never 64 MiB, let alone a copy of it.
            assertThat(allocated).as("bytes allocated").isLessThan(32L * 1024 * 1024);
        }
    }

    @Test
    void theByteMatcherFindsOverlappingPatterns() {
        List<byte[]> patterns = List.of(text("he"), text("she"), text("his"), text("hers"));
        ByteMatcher matcher = new ByteMatcher(patterns, new int[] { 0, 1, 2, 3 }, 4);
        BitSet found = matcher.find(text("ushers"));
        assertThat(found.stream().boxed().toList()).containsExactly(0, 1, 3);
        assertThat(matcher.find(text("nothing")).isEmpty()).isTrue();
        // In parts, as an archive's member streams in: a pattern cut between two parts is found.
        BitSet parts = new BitSet();
        ByteMatcher.Pass pass = matcher.pass(parts);
        pass.feed(text("xxus"), 0, 4);
        pass.feed(text("hers"), 0, 2);
        pass.feed(text("hers"), 2, 2);
        assertThat(parts.stream().boxed().toList()).containsExactly(0, 1, 3);
    }

    private static CredentialScan scan() {
        return new CredentialScan(values(Map.of("GIT_TOKEN", TOKEN, "OTHER", OTHER)));
    }

    private static Map<String, byte[]> values(Map<String, String> values) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        values.forEach((name, value) -> bytes.put(name, value.getBytes(StandardCharsets.UTF_8)));
        return bytes;
    }

    private static byte[] text(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(String name, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        }
        return out.toByteArray();
    }
}
