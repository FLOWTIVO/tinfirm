package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.system.SystemKind;

/**
 * The rules of package 3c's part 3 as tables: the texts of a change request (the issue line, the related block, the
 * title), the links {@code save_artifact} takes, the names of files and the names they are mounted under.
 */
class OutputRulesTest {

    private static final ConversationInfo.Subject ISSUE = new ConversationInfo.Subject(UUID.randomUUID(),
            "acme/shop#57", "https://github.com/acme/shop/issues/57", "Totals");

    @Test
    void theIssueLineClosesAnIssueOfTheSameRepositoryAndRefersToOthers() {
        assertThat(ChangeRequestBodies.issueLine(ISSUE, SystemKind.GITHUB, "https://github.com/Acme/Shop.git",
                SystemKind.GITHUB)).isEqualTo("Closes #57");
        assertThat(ChangeRequestBodies.issueLine(ISSUE, SystemKind.GITHUB, "https://github.com/acme/web",
                SystemKind.GITHUB)).isEqualTo("Refs acme/shop#57");
        assertThat(ChangeRequestBodies.issueLine(ISSUE, SystemKind.GITHUB, "https://gitlab.example.com/acme/shop",
                SystemKind.GITLAB)).isEqualTo("Issue: https://github.com/acme/shop/issues/57");
        // The key is not owner/repo#n, but the link names the issue.
        ConversationInfo.Subject byLink = new ConversationInfo.Subject(ISSUE.systemId(), "Totals are wrong",
                "https://github.com/acme/shop/issues/58/", null);
        assertThat(ChangeRequestBodies.issueLine(byLink, SystemKind.GITHUB, "https://github.com/acme/shop",
                SystemKind.GITHUB)).isEqualTo("Closes #58");
        // No subject, a Jira issue, a subject whose system is gone, a key and link that name no issue.
        assertThat(ChangeRequestBodies.issueLine(null, null, "https://github.com/acme/shop", SystemKind.GITHUB))
            .isNull();
        assertThat(ChangeRequestBodies.issueLine(new ConversationInfo.Subject(UUID.randomUUID(), "SHOP-1", null, null),
                SystemKind.JIRA_DC, "https://github.com/acme/shop", SystemKind.GITHUB)).isNull();
        assertThat(ChangeRequestBodies.issueLine(ISSUE, null, "https://github.com/acme/shop", SystemKind.GITHUB))
            .isNull();
        assertThat(ChangeRequestBodies.issueLine(new ConversationInfo.Subject(ISSUE.systemId(), "acme/shop", null,
                null), SystemKind.GITHUB, "https://github.com/acme/shop", SystemKind.GITHUB)).isNull();
    }

    @Test
    void theRelatedBlockIsReplacedOrAppended() {
        String block = ChangeRequestBodies.relatedBlock(List.of(ChangeRequestBodies.line("web",
                "https://github.com/acme/web/pull/3")));
        assertThat(block).isEqualTo("<!-- tinfirm:related -->\nRelated change requests:\n"
                + "- web: https://github.com/acme/web/pull/3\n<!-- /tinfirm:related -->");
        assertThat(ChangeRequestBodies.relatedBlock(List.of()))
            .isEqualTo("<!-- tinfirm:related -->\n<!-- /tinfirm:related -->");
        String body = "Summary\n\n" + ChangeRequestBodies.relatedBlock(List.of()) + "\n\nOpened by Tinfirm agent Dev";
        assertThat(ChangeRequestBodies.withRelated(body, block)).isEqualTo("Summary\n\n" + block
                + "\n\nOpened by Tinfirm agent Dev");
        assertThat(ChangeRequestBodies.withRelated("Edited by hand", block)).isEqualTo("Edited by hand\n\n" + block);
        assertThat(ChangeRequestBodies.withRelated("", block)).isEqualTo(block);
        assertThat(ChangeRequestBodies.withRelated(ChangeRequestBodies.withRelated(body, block), block))
            .isEqualTo(ChangeRequestBodies.withRelated(body, block));
    }

    @Test
    void theAgentsWordsCarryNoMarkerOfTheRelatedBlock() {
        String block = ChangeRequestBodies.relatedBlock(List.of(ChangeRequestBodies.line("web",
                "https://github.com/acme/web/pull/3")));
        String tail = "\n\nhttps://t/c\n\nCloses #57\n\n" + ChangeRequestBodies.relatedBlock(List.of())
                + "\n\nOpened by Alice via Tinfirm agent Dev.";
        // A summary with the block's first marker alone: a rewrite took the block from it to the server's end
        // marker, and deleted the conversation's link and the issue line between them.
        String injected = "Fixed. <!-- tinfirm:related -->";
        String body = ChangeRequestBodies.body(injected, "https://t/c", "Closes #57", List.of(), "Dev", "Alice");
        assertThat(body).isEqualTo("Fixed. <!- - tinfirm:related -->" + tail);
        assertThat(ChangeRequestBodies.withRelated(body, block)).isEqualTo("Fixed. <!- - tinfirm:related -->"
                + tail.replace(ChangeRequestBodies.relatedBlock(List.of()), block));
        // A body opened before, with the marker as the agent wrote it: the server's block is the last one.
        assertThat(ChangeRequestBodies.withRelated(injected + tail, block))
            .isEqualTo(injected + tail.replace(ChangeRequestBodies.relatedBlock(List.of()), block));
        String both = "<!-- /tinfirm:related --> and <!-- tinfirm:related --> x <!-- /tinfirm:related -->";
        assertThat(ChangeRequestBodies.withRelated(both + tail, block))
            .isEqualTo(both + tail.replace(ChangeRequestBodies.relatedBlock(List.of()), block));
        // Every comment opener the agent, the person or a name brings is neutralized, the server's own kept.
        String names = ChangeRequestBodies.body("<!--", "https://t/c", null, List.of("- <!-- x: y"), "D<!--",
                "<!--A");
        assertThat(names).isEqualTo("<!- -\n\nhttps://t/c\n\n<!-- tinfirm:related -->\nRelated change requests:\n"
                + "- <!- - x: y\n<!-- /tinfirm:related -->\n\nOpened by <!- -A via Tinfirm agent D<!- -.");
    }

    @Test
    void theBodyNamesTheTurnTheConversationTheIssueTheOthersAndThePerson() {
        assertThat(ChangeRequestBodies.body("Fixed.", "https://tinfirm.example.com/c/1/conversations/2", "Closes #57",
                List.of("- web: https://github.com/acme/web/pull/3"), "Dev", "Alice"))
            .isEqualTo("Fixed.\n\nhttps://tinfirm.example.com/c/1/conversations/2\n\nCloses #57\n\n"
                    + "<!-- tinfirm:related -->\nRelated change requests:\n- web: https://github.com/acme/web/pull/3\n"
                    + "<!-- /tinfirm:related -->\n\nOpened by Alice via Tinfirm agent Dev.");
        assertThat(ChangeRequestBodies.body("Fixed.", "https://t/c", null, List.of(), "Dev", "Alice"))
            .isEqualTo("Fixed.\n\nhttps://t/c\n\n<!-- tinfirm:related -->\n<!-- /tinfirm:related -->\n\n"
                    + "Opened by Alice via Tinfirm agent Dev.");
        assertThat(CommitAuthor.name("Alice", "Dev")).isEqualTo("Alice via Tinfirm agent Dev");
        UUID agentId = UUID.randomUUID();
        assertThat(CommitAuthor.email(agentId)).isEqualTo("agent+" + agentId + "@tinfirm.invalid");
    }

    @Test
    void theTitleNamesTheSubjectAndFits() {
        assertThat(ChangeRequestBodies.title(conversation("Fix the totals", ISSUE)))
            .isEqualTo("acme/shop#57: Fix the totals");
        assertThat(ChangeRequestBodies.title(conversation("Fix the totals", null))).isEqualTo("Fix the totals");
        String title = ChangeRequestBodies.title(conversation("x".repeat(200), new ConversationInfo.Subject(null,
                "K".repeat(150), null, null)));
        assertThat(title).hasSize(255).endsWith("…");
        assertThat(ChangeRequestBodies.questionsSummary("Dev", " The last words. "))
            .isEqualTo("Dev asked a question in the conversation before finishing; this change request holds the "
                    + "work so far. The conversation continues in Tinfirm.\n\nThe last words.");
    }

    @Test
    void aLinkIsAPlainHttpLinkWithoutUserInfo() {
        assertThat(OutputService.linkProblem("https://wiki.example.com/q3?page=2#top")).isNull();
        assertThat(OutputService.linkProblem("http://localhost:8080/a@b")).isNull();
        for (String bad : List.of("", "ftp://example.com", "https://user:pw@example.com/", "https://user@example.com",
                "https:///path", "https://example.com/a b", "https://example.com/\u0007", "HTTPS://example.com",
                "https://example.com/" + "a".repeat(2000), "javascript:alert(1)")) {
            assertThat(OutputService.linkProblem(bad)).as(bad).isNotNull();
        }
        assertThat(OutputService.title("  Q3  ", "https://x")).isEqualTo("Q3");
        assertThat(OutputService.title(null, " https://x ")).isEqualTo("https://x");
        assertThat(OutputService.title("t".repeat(300), "https://x")).hasSize(200).endsWith("…");
        assertThat(OutputService.documentTitle("reports/" + "a".repeat(300) + ".md")).hasSize(200).startsWith("…")
            .endsWith("a.md");
    }

    @Test
    void namesOfFilesAndOfOutputDocuments() {
        assertThat(AttachmentService.fileName("C:\\Users\\me\\návrh v2.txt")).isEqualTo("návrh v2.txt");
        assertThat(AttachmentService.fileName("/tmp/a/b.txt")).isEqualTo("b.txt");
        for (String bad : List.of("", " ", ".", "..", "a\u0007b", "a\u202Eb", "x".repeat(256),
                "\uD83D\uDCC4".repeat(256))) {
            assertThatThrownBy(() -> AttachmentService.fileName(bad)).as(bad).isInstanceOf(ApiException.class);
        }
        // Characters are code points, as PostgreSQL and the protocol count them: 200 emoji are 400 UTF-16 units.
        String emojis = "\uD83D\uDCC4".repeat(200);
        assertThat(AttachmentService.fileName(emojis)).isEqualTo(emojis);
        assertThat(AttachmentService.pathName("notes/" + emojis)).isEqualTo("notes/" + emojis);
        assertThat(AttachmentService.pathName("reports/q3.md")).isEqualTo("reports/q3.md");
        // At most 1024 characters, as runner protocol v2's ArtifactResult.name: code points, as the protocol counts.
        String longest = "a".repeat(255) + "/" + "b".repeat(255) + "/" + "c".repeat(255) + "/" + "d".repeat(254) + "/e";
        assertThat(longest).hasSize(1024);
        assertThat(AttachmentService.pathName(longest)).isEqualTo(longest);
        String emoji = String.join("/", java.util.Collections.nCopies(8, "\uD83D\uDCC4".repeat(125)));
        assertThat(emoji).hasSize(2007);
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(1007);
        assertThat(AttachmentService.pathName(emoji)).isEqualTo(emoji);
        for (String bad : List.of("/abs.md", "a//b", "a/./b", "../up", "a\\b", "a/ b", "x".repeat(501),
                "a/" + "x".repeat(256), longest + "f")) {
            assertThatThrownBy(() -> AttachmentService.pathName(bad)).as(bad).isInstanceOf(ApiException.class);
        }
        assertThat(AttachmentService.contentType("text/plain; charset=utf-8")).isEqualTo("text/plain");
        assertThat(AttachmentService.contentType("*/*")).isEqualTo("application/octet-stream");
        assertThat(AttachmentService.contentType("not a type")).isEqualTo("application/octet-stream");
        assertThat(AttachmentService.size(10)).isEqualTo("10 bytes");
        assertThat(AttachmentService.size(64 * 1024)).isEqualTo("64 KB");
        assertThat(AttachmentService.size(25L * 1024 * 1024)).isEqualTo("25 MB");
        assertThat(AttachmentService.size(250L * 1024 * 1024 + 300 * 1024)).isEqualTo("250.3 MB");
    }

    @Test
    void aPathIsMountedFlatWithASuffixWhenTheNameIsTaken() {
        List<AttachmentStore.AttachmentRow> files = List.of(file("q3_summary.md"), file("q3/summary.md"),
                file("q3_summary-2.md"), file("x/q3_summary.md"), file("archive"), file("a/" + "é".repeat(200) + ".md"));
        List<String> names = List.copyOf(OutputPrompt.mountNames(files).values());
        assertThat(names.subList(0, 5)).containsExactly("q3_summary.md", "q3_summary-3.md", "q3_summary-2.md",
                "x_q3_summary.md", "archive");
        assertThat(names.get(5).getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(255);
        assertThat(names.get(5)).endsWith("é.md").doesNotContain("/");
        // A name of one segment longer than the 255 bytes a runner host takes is cut as a path is, from the front.
        String wide = "\u8349".repeat(100) + ".txt";
        String mounted = OutputPrompt.mountNames(List.of(file("short.txt"), file(wide))).values().stream().toList()
            .get(1);
        assertThat(mounted.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(255);
        assertThat(mounted).endsWith("\u8349.txt").hasSizeLessThan(wide.length());
    }

    @Test
    void aNameWhoseExtensionLeavesNoRoomForTheSuffixIsSuffixedWhole() {
        // The second is cut to the first's name, whose "extension" is 254 bytes: "-2" and it do not fit in 255.
        String name = "b." + "a".repeat(253);
        List<String> names = List.copyOf(OutputPrompt.mountNames(List.of(file(name), file("x/" + name))).values());
        assertThat(names.getFirst()).isEqualTo(name);
        assertThat(names.get(1)).isNotEqualTo(name).endsWith("a-2");
        assertThat(names.get(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(255);
        // Every way a long name and an extension can meet: within 255 bytes, distinct, never empty.
        for (String extension : List.of("", ".md", "." + "e".repeat(200), "." + "e".repeat(252),
                "." + "e".repeat(253), "." + "\u8349".repeat(84), "." + "\ud83d\udcc4".repeat(63))) {
            for (String stem : List.of("s", "s".repeat(240), "\u8349".repeat(85))) {
                String base = stem + extension;
                List<AttachmentStore.AttachmentRow> files = List.of(file(base.length() > 255 ? "z" : base),
                        file("a/" + base), file("b/" + base), file("c/" + base));
                List<String> mounted = List.copyOf(OutputPrompt.mountNames(files).values());
                assertThat(mounted).as(base).doesNotHaveDuplicates().allSatisfy(each -> {
                    assertThat(each).isNotEmpty().doesNotContain("/");
                    assertThat(each.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(255);
                });
            }
        }
    }

    private static AttachmentStore.AttachmentRow file(String name) {
        return new AttachmentStore.AttachmentRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), name,
                "text/plain", 1, "0".repeat(64), null, null, null, null, Instant.now(), null);
    }

    private static ConversationInfo conversation(String title, ConversationInfo.Subject subject) {
        Instant now = Instant.now();
        return new ConversationInfo(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "shop-57-dev-1", title,
                ConversationInfo.Place.TINFIRM_THREAD, ConversationInfo.Origin.APP, UUID.randomUUID(), subject,
                RuntimeKind.CLAUDE_CODE, UUID.randomUUID(), ModelAccessChoice.BUDGET, null, List.of(), List.of(),
                ConversationInfo.State.OPEN, null, null, null, ConversationInfo.WaitingOn.NONE, null, null, 0, null,
                null, null, now, now);
    }
}
