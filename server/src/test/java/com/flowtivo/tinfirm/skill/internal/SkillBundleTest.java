package com.flowtivo.tinfirm.skill.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

/**
 * A skill's upload as {@link SkillBundle} reads it (the skills contract, §2 and §4.2 steps 1, 2 and 4): paths and
 * limits against what is read, the front matter's keys, the top folder stripped (and checked first, never stripped when
 * it is {@code ..} or another bad segment), and the canonical zip, whose bytes and hash are the same for the same files
 * in another order.
 */
class SkillBundleTest {

    private static final String SKILL_MD = """
            ---
            name: write-ticket
            description: Writes a Jira ticket the way the team does
            ---
            # Write a ticket

            Use the template in templates/ticket.md.
            """;

    @Test
    void readsASkillFromAZipAndWritesTheCanonicalZip() {
        SkillBundle.Parsed parsed = SkillBundle.fromZip(zip(Map.of("SKILL.md", SKILL_MD,
                "templates/ticket.md", "## Summary\n", "scripts/check.py", "print('ok')\n")));

        assertThat(parsed.name()).isEqualTo("write-ticket");
        assertThat(parsed.description()).isEqualTo("Writes a Jira ticket the way the team does");
        assertThat(parsed.files()).extracting(SkillBundle.FileEntry::path)
            .containsExactly("SKILL.md", "scripts/check.py", "templates/ticket.md");
        assertThat(parsed.sha256()).isEqualTo(SkillBundle.sha256(parsed.zip())).hasSize(64);
        assertThat(SkillBundle.unzip(parsed.zip())).containsOnlyKeys("SKILL.md", "scripts/check.py",
                "templates/ticket.md");
        assertThat(parsed.skillMd()).isEqualTo(SKILL_MD);
    }

    @Test
    void theSameFilesInAnotherOrderGiveTheSameBytes() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("SKILL.md", SKILL_MD);
        one.put("a.txt", "a");
        one.put("b/c.txt", "c");
        Map<String, String> other = new LinkedHashMap<>();
        other.put("b/c.txt", "c");
        other.put("a.txt", "a");
        other.put("SKILL.md", SKILL_MD);

        SkillBundle.Parsed first = SkillBundle.fromZip(zip(one));
        SkillBundle.Parsed second = SkillBundle.fromZip(zip(other));

        assertThat(second.zip()).isEqualTo(first.zip());
        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(second.files()).isEqualTo(first.files());
    }

    @Test
    void stripsTheFolderTheZipWasMadeOfAndSkipsWhatArchiversAdd() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("write-ticket/SKILL.md", SKILL_MD);
        files.put("write-ticket/notes.md", "notes");
        files.put("write-ticket/.DS_Store", "finder");
        files.put("__MACOSX/write-ticket/._SKILL.md", "fork");

        SkillBundle.Parsed parsed = SkillBundle.fromZip(zip(files));

        assertThat(parsed.files()).extracting(SkillBundle.FileEntry::path).containsExactly("SKILL.md", "notes.md");
    }

    @Test
    void stripsOneRealTopFolderWithEverythingUnderIt() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("write-ticket.v2/SKILL.md", SKILL_MD);
        files.put("write-ticket.v2/scripts/check.py", "print('ok')\n");
        files.put("write-ticket.v2/templates/..ticket.md", "## Summary\n");

        SkillBundle.Parsed parsed = SkillBundle.fromZip(zip(files));

        assertThat(parsed.files()).extracting(SkillBundle.FileEntry::path)
            .containsExactly("SKILL.md", "scripts/check.py", "templates/..ticket.md");
        assertThat(parsed.zip()).isEqualTo(SkillBundle.fromZip(zip(Map.of("SKILL.md", SKILL_MD, "scripts/check.py",
                "print('ok')\n", "templates/..ticket.md", "## Summary\n"))).zip());
    }

    @Test
    void refusesATopFolderThatBreaksTheRulesInsteadOfStrippingIt() {
        // Every file under one first segment that is not a folder's name: stripped, the paths left would pass.
        for (String top : List.of("..", ".", "", "c:", "a\\b", "..\\..", "x\u0001y", "x".repeat(256))) {
            Map<String, String> files = new LinkedHashMap<>();
            files.put(top + "/SKILL.md", SKILL_MD);
            files.put(top + "/script.sh", "echo hi\n");

            SkillBundle.Refused refused = refused(() -> SkillBundle.fromZip(zip(files)));

            assertThat(refused.rule()).as(top).isEqualTo(SkillBundle.Refused.Rule.PATH);
            assertThat(refused.path()).as(top).isEqualTo(top + "/SKILL.md");
        }
        // Windows' separators are no folder: nothing is stripped and the paths are refused as they are.
        Map<String, String> windows = new LinkedHashMap<>();
        windows.put("write-ticket\\SKILL.md", SKILL_MD);
        windows.put("write-ticket\\notes.md", "notes");
        assertThat(refused(() -> SkillBundle.fromZip(zip(windows))).path()).isEqualTo("write-ticket\\SKILL.md");
        // A good top folder strips, and what lies under it is checked as ever: never normalised.
        for (String rest : List.of("/SKILL.md", "./SKILL.md", "../SKILL.md", "a/../SKILL.md")) {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("write-ticket/SKILL.md", SKILL_MD);
            files.put("write-ticket/" + rest, "x");

            SkillBundle.Refused refused = refused(() -> SkillBundle.fromZip(zip(files)));

            assertThat(refused.rule()).as(rest).isEqualTo(SkillBundle.Refused.Rule.PATH);
            assertThat(refused.path()).as(rest).isEqualTo(rest);
        }
    }

    @Test
    void aSkillOfOneFileFromItsText() {
        SkillBundle.Parsed parsed = SkillBundle.fromSkillMd(SKILL_MD);

        assertThat(parsed.files()).extracting(SkillBundle.FileEntry::path).containsExactly("SKILL.md");
        assertThat(parsed.zip()).isEqualTo(SkillBundle.fromZip(zip(Map.of("SKILL.md", SKILL_MD))).zip());
    }

    @Test
    void refusesWhatIsNotAZip() {
        assertRefused(() -> SkillBundle.fromZip("not a zip".getBytes(StandardCharsets.UTF_8)),
                SkillBundle.Refused.Rule.NOT_A_ZIP);
    }

    @Test
    void refusesAFolderWithoutSkillMd() {
        assertRefused(() -> SkillBundle.fromZip(zip(Map.of("README.md", "hi", "other/SKILL.md", SKILL_MD))),
                SkillBundle.Refused.Rule.NO_SKILL_MD);
    }

    @Test
    void refusesPathsThatLeaveTheFolderOrClash() {
        for (String path : List.of("../SKILL.md2", "/etc/passwd", "a/./b", "a\\b", "c:d", "a//b", "x\u0001y",
                "1/2/3/4/5/6/7/8/9")) {
            assertRefused(() -> SkillBundle.fromZip(zip(Map.of("SKILL.md", SKILL_MD, path, "x"))),
                    SkillBundle.Refused.Rule.PATH);
        }
        Map<String, String> clash = new LinkedHashMap<>();
        clash.put("SKILL.md", SKILL_MD);
        clash.put("Notes.md", "a");
        clash.put("notes.md", "b");
        assertRefused(() -> SkillBundle.fromZip(zip(clash)), SkillBundle.Refused.Rule.PATH);
        Map<String, String> fileAndFolder = new LinkedHashMap<>();
        fileAndFolder.put("SKILL.md", SKILL_MD);
        fileAndFolder.put("docs", "a file");
        fileAndFolder.put("docs/a.md", "in a folder of the same name");
        assertRefused(() -> SkillBundle.fromZip(zip(fileAndFolder)), SkillBundle.Refused.Rule.PATH);
    }

    @Test
    void countsWhatItReadsNotWhatTheZipClaims() {
        // One entry that inflates past the limit: the zip's own sizes are never trusted.
        byte[] big = new byte[SkillBundle.MAX_BYTES + 1];
        byte[] upload = zipBytes(Map.of("SKILL.md", SKILL_MD.getBytes(StandardCharsets.UTF_8), "big.bin", big));
        assertThat(upload.length).isLessThan(SkillBundle.MAX_UPLOAD);

        assertTooLarge(() -> SkillBundle.fromZip(upload), SkillBundle.Refused.Limit.BYTES);
    }

    @Test
    void countsWhatItSkipsToo() {
        // The stream inflates a skipped entry as it does a file: a payload under __MACOSX/, in a .DS_Store or on a
        // directory entry goes through the same budget, and is refused past it, not inflated without limit.
        byte[] big = new byte[SkillBundle.MAX_BYTES + 1];
        for (String skipped : List.of("__MACOSX/._big", "x/.DS_Store", "dir/")) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("SKILL.md", SKILL_MD.getBytes(StandardCharsets.UTF_8));
            files.put(skipped, big);
            byte[] upload = zipBytes(files);
            assertThat(upload.length).isLessThan(SkillBundle.MAX_UPLOAD);

            assertTooLarge(() -> SkillBundle.fromZip(upload), SkillBundle.Refused.Limit.BYTES);
        }
    }

    @Test
    void refusesTooManyFilesAndTooLargeAnUpload() {
        Map<String, String> many = new LinkedHashMap<>();
        many.put("SKILL.md", SKILL_MD);
        for (int i = 0; i < SkillBundle.MAX_FILES; i++) {
            many.put("f" + i + ".txt", "x");
        }
        assertTooLarge(() -> SkillBundle.fromZip(zip(many)), SkillBundle.Refused.Limit.FILES);
        assertTooLarge(() -> SkillBundle.fromZip(new byte[SkillBundle.MAX_UPLOAD + 1]),
                SkillBundle.Refused.Limit.UPLOAD);
    }

    @Test
    void refusesFilesWhoseCanonicalZipPassesWhatAHostTakes() {
        // Random bytes do not compress: deflated, they grow a little. Stored at exactly the upload's limit, and within
        // the files' limit, they pass the protocol's 5 MiB once the server writes them again.
        byte[] skillMd = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        int overhead = storedZip(skillMd, new byte[0]).length - skillMd.length;
        byte[] noise = new byte[SkillBundle.MAX_UPLOAD - skillMd.length - overhead];
        new SecureRandom().nextBytes(noise);
        byte[] upload = storedZip(skillMd, noise);
        assertThat(upload.length).isEqualTo(SkillBundle.MAX_UPLOAD);
        assertThat(skillMd.length + noise.length).isLessThanOrEqualTo(SkillBundle.MAX_BYTES);

        assertTooLarge(() -> SkillBundle.fromZip(upload), SkillBundle.Refused.Limit.STORED);
    }

    @Test
    void checksTheFrontMatter() {
        assertFront("# no front matter", SkillBundle.Refused.Rule.FRONT_MATTER);
        assertFront("---\nname: a\ndescription: b\n", SkillBundle.Refused.Rule.FRONT_MATTER);
        assertFront("---\n[unclosed\n---\n", SkillBundle.Refused.Rule.FRONT_MATTER);
        assertFront("---\n- a list\n---\n", SkillBundle.Refused.Rule.FRONT_MATTER);
        assertFront("---\nname: Write_Ticket\ndescription: b\n---\n", SkillBundle.Refused.Rule.NAME);
        assertFront("---\nname: 42\ndescription: b\n---\n", SkillBundle.Refused.Rule.NAME);
        assertFront("---\nname: a--b\ndescription: b\n---\n", SkillBundle.Refused.Rule.NAME);
        assertFront("---\nname: write\ndescription: '   '\n---\n", SkillBundle.Refused.Rule.DESCRIPTION);
        assertFront("---\nname: write\ndescription: " + "x".repeat(1025) + "\n---\n",
                SkillBundle.Refused.Rule.DESCRIPTION);
        assertFront("---\nname: write\ndescription: b\nhooks: x\n---\n", SkillBundle.Refused.Rule.KEY);
        assertFront("---\nname: write\ndescription: b\nmetadata: [1]\n---\n", SkillBundle.Refused.Rule.KEY);
        assertFront("---\nname: write\ndescription: b\nname: other\n---\n", SkillBundle.Refused.Rule.FRONT_MATTER);
    }

    @Test
    void takesTheSpecificationsOptionalKeys() {
        SkillBundle.FrontMatter front = SkillBundle.frontMatter("""
                ---
                name: evaluate-project
                description: >
                  Evaluates a project's month from its spreadsheet.
                license: Apache-2.0
                compatibility: Needs python3
                metadata:
                  owner: finance
                allowed-tools: [Bash, Read]
                ---
                Body
                """);

        assertThat(front.name()).isEqualTo("evaluate-project");
        assertThat(front.description()).isEqualTo("Evaluates a project's month from its spreadsheet.");
    }

    private static void assertFront(String skillMd, SkillBundle.Refused.Rule rule) {
        assertRefused(() -> SkillBundle.fromSkillMd(skillMd), rule);
    }

    private static void assertRefused(Runnable call, SkillBundle.Refused.Rule rule) {
        assertThatThrownBy(call::run)
            .isInstanceOf(SkillBundle.Refused.class)
            .satisfies(ex -> assertThat(((SkillBundle.Refused) ex).rule()).isEqualTo(rule));
    }

    private static SkillBundle.Refused refused(Runnable call) {
        try {
            call.run();
        }
        catch (SkillBundle.Refused ex) {
            return ex;
        }
        throw new AssertionError("The upload was taken");
    }

    private static void assertTooLarge(Runnable call, SkillBundle.Refused.Limit limit) {
        assertThatThrownBy(call::run)
            .isInstanceOf(SkillBundle.Refused.class)
            .satisfies(ex -> assertThat(((SkillBundle.Refused) ex).limit()).isEqualTo(limit));
    }

    /** A zip that stores its {@code SKILL.md} and {@code noise.bin} without compressing them. */
    private static byte[] storedZip(byte[] skillMd, byte[] noise) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (Map.Entry<String, byte[]> file : Map.of("SKILL.md", skillMd, "noise.bin", noise).entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                CRC32 crc = new CRC32();
                crc.update(file.getValue());
                entry.setSize(file.getValue().length);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out.toByteArray();
    }

    static byte[] zip(Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(bytes);
    }

    static byte[] zipBytes(Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out.toByteArray();
    }
}
