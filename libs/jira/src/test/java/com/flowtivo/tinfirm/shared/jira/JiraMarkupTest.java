package com.flowtivo.tinfirm.shared.jira;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Jira wiki markup to Markdown and back (CONVENTIONS §15.2): the common subset converts, the rest stays as it is. */
class JiraMarkupTest {

    // ---- Jira to Markdown ------------------------------------------------------------------------------------------

    @Test
    void headingsEmphasisAndMonospaceBecomeMarkdown() {
        assertThat(JiraMarkup.toMarkdown("h1. Title\nh3. Details")).isEqualTo("# Title\n### Details");
        assertThat(JiraMarkup.toMarkdown("This is *bold*, _italic_ and {{mono}}."))
            .isEqualTo("This is **bold**, *italic* and `mono`.");
        assertThat(JiraMarkup.toMarkdown("*bold _and italic_*")).isEqualTo("**bold *and italic***");
    }

    @Test
    void identifiersAndArithmeticAreNotEmphasis() {
        assertThat(JiraMarkup.toMarkdown("Rename snake_case_name and compute 2 * 3 * 4."))
            .isEqualTo("Rename snake_case_name and compute 2 * 3 * 4.");
        assertThat(JiraMarkup.toMarkdown("See http://jira.example.com/a_b_c for more"))
            .isEqualTo("See http://jira.example.com/a_b_c for more");
        assertThat(JiraMarkup.toMarkdown("{{snake_case_name}} and {{*args}}")).isEqualTo("`snake_case_name` and `*args`");
    }

    @Test
    void codeAndNoformatBlocksBecomeFencesWithoutConvertingTheirContent() {
        assertThat(JiraMarkup.toMarkdown("Before\n{code:java}\nint *x* = a_b_c;\n{code}\nAfter"))
            .isEqualTo("Before\n```java\nint *x* = a_b_c;\n```\nAfter");
        assertThat(JiraMarkup.toMarkdown("{code:title=Main.java|language=Java}\nclass Main {}\n{code}"))
            .isEqualTo("```java\nclass Main {}\n```");
        assertThat(JiraMarkup.toMarkdown("{noformat}\n*not bold*\n{noformat}")).isEqualTo("```\n*not bold*\n```");
        assertThat(JiraMarkup.toMarkdown("{code}one line{code}")).isEqualTo("```\none line\n```");
    }

    @Test
    void anUnclosedCodeBlockRunsToTheEnd() {
        assertThat(JiraMarkup.toMarkdown("{code}\nx = 1\ny = 2")).isEqualTo("```\nx = 1\ny = 2\n```");
    }

    @Test
    void codeWithTripleBackticksGetsAnotherFence() {
        assertThat(JiraMarkup.toMarkdown("{noformat}\n```\n{noformat}")).isEqualTo("~~~~\n```\n~~~~");
    }

    @Test
    void bulletAndNumberedListsKeepTheirDepth() {
        assertThat(JiraMarkup.toMarkdown("* one\n** one.a\n* two\n# first\n## first.a\n*# mixed\n- dash"))
            .isEqualTo("- one\n  - one.a\n- two\n1. first\n   1. first.a\n  1. mixed\n- dash");
    }

    @Test
    void linksAndMentionsBecomeMarkdown() {
        assertThat(JiraMarkup.toMarkdown("Read [the guide|https://example.com/guide_v2] or [https://example.com]."))
            .isEqualTo("Read [the guide](https://example.com/guide_v2) or <https://example.com>.");
        assertThat(JiraMarkup.toMarkdown("Thanks [~alice.smith], see [mailto:team@example.com]"))
            .isEqualTo("Thanks @alice.smith, see <mailto:team@example.com>");
    }

    @Test
    void quotesTablesAndRulesBecomeMarkdown() {
        assertThat(JiraMarkup.toMarkdown("bq. Quoted *text*")).isEqualTo("> Quoted **text**");
        assertThat(JiraMarkup.toMarkdown("{quote}\nline one\nline two\n{quote}\nafter"))
            .isEqualTo("> line one\n> line two\nafter");
        assertThat(JiraMarkup.toMarkdown("||Name||Value||\n|a|1|")).isEqualTo("| Name | Value |\n| --- | --- |\n|a|1|");
        assertThat(JiraMarkup.toMarkdown("above\n----\nbelow")).isEqualTo("above\n---\nbelow");
    }

    @Test
    void whatCannotBeConvertedStaysAsItIs() {
        assertThat(JiraMarkup.toMarkdown("{color:red}red text{color} and !screenshot.png! and [#anchor]"))
            .isEqualTo("{color:red}red text{color} and !screenshot.png! and [#anchor]");
        assertThat(JiraMarkup.toMarkdown("Use {code}inline{code} here")).isEqualTo("Use {code}inline{code} here");
    }

    @Test
    void blankDescriptionsAreNoDescription() {
        assertThat(JiraMarkup.toMarkdown(null)).isNull();
        assertThat(JiraMarkup.toMarkdown("  \n ")).isNull();
        assertThat(JiraMarkup.toMarkdown("line\r\nnext")).isEqualTo("line\nnext");
    }

    // ---- Markdown to Jira ------------------------------------------------------------------------------------------

    @Test
    void headingsEmphasisAndCodeSpansBecomeWikiMarkup() {
        assertThat(JiraMarkup.toWiki("# Title\n## Details")).isEqualTo("h1. Title\nh2. Details");
        assertThat(JiraMarkup.toWiki("This is **bold**, *italic*, _also italic_, __bold__ and ~~gone~~."))
            .isEqualTo("This is *bold*, _italic_, _also italic_, *bold* and -gone-.");
        assertThat(JiraMarkup.toWiki("Call `snake_case()` with `--force`."))
            .isEqualTo("Call {{snake_case()}} with {{\\-\\-force}}.");
        assertThat(JiraMarkup.toWiki("***both***")).isEqualTo("*_both_*");
    }

    @Test
    void fencedBlocksBecomeCodeOrNoformat() {
        assertThat(JiraMarkup.toWiki("Run:\n```bash\nmvn -B verify **not bold**\n```\nDone."))
            .isEqualTo("Run:\n{code:bash}\nmvn -B verify **not bold**\n{code}\nDone.");
        assertThat(JiraMarkup.toWiki("```\nplain\n```")).isEqualTo("{noformat}\nplain\n{noformat}");
        assertThat(JiraMarkup.toWiki("```\nunclosed")).isEqualTo("{noformat}\nunclosed\n{noformat}");
    }

    @Test
    void listsKeepTheirNesting() {
        assertThat(JiraMarkup.toWiki("- one\n  - one.a\n- two\n\n1. first\n   1. first.a\n2. second"))
            .isEqualTo("* one\n** one.a\n* two\n\n# first\n## first.a\n# second");
        assertThat(JiraMarkup.toWiki("* star\n+ plus")).isEqualTo("* star\n* plus");
    }

    @Test
    void linksImagesAndUrlsBecomeWikiLinks() {
        assertThat(JiraMarkup.toWiki("See [the PR](https://github.com/acme/web/pull/42) and <https://example.com/a_b>."))
            .isEqualTo("See [the PR|https://github.com/acme/web/pull/42] and [https://example.com/a_b].");
        assertThat(JiraMarkup.toWiki("Bare https://gitlab.example.com/a_b_c/-/merge_requests/7 stays"))
            .isEqualTo("Bare https://gitlab.example.com/a_b_c/-/merge_requests/7 stays");
        assertThat(JiraMarkup.toWiki("![diagram](https://example.com/d.png)")).isEqualTo("!https://example.com/d.png!");
    }

    @Test
    void quotesTablesAndRulesBecomeWikiMarkup() {
        assertThat(JiraMarkup.toWiki("> One line")).isEqualTo("bq. One line");
        assertThat(JiraMarkup.toWiki("> first\n> - item")).isEqualTo("{quote}\nfirst\n* item\n{quote}");
        assertThat(JiraMarkup.toWiki("| Name | Value |\n| --- | :---: |\n| a | `x` |\n\nafter"))
            .isEqualTo("||Name||Value||\n|a|{{x}}|\n\nafter");
        assertThat(JiraMarkup.toWiki("above\n\n---\n\nbelow")).isEqualTo("above\n\n----\n\nbelow");
    }

    @Test
    void bracesAndBracketsThatJiraWouldReadAsMacrosOrLinksAreEscaped() {
        assertThat(JiraMarkup.toWiki("Set {timeout} in config[0], keep \\[escaped]."))
            .isEqualTo("Set \\{timeout} in config\\[0], keep \\[escaped].");
    }

    @Test
    void theEndOfRunCommentOfAnAgentConverts() {
        String summary = """
                Added `GET /health` and a test.

                Pull requests:
                - server: https://github.com/acme/server/pull/12""";
        assertThat(JiraMarkup.toWiki(summary)).isEqualTo("""
                Added {{GET /health}} and a test.

                Pull requests:
                * server: https://github.com/acme/server/pull/12""");
    }

    @Test
    void blankMarkdownIsEmpty() {
        assertThat(JiraMarkup.toWiki(null)).isEmpty();
        assertThat(JiraMarkup.toWiki(" \n")).isEmpty();
    }

    @Test
    void monospaceEscapesOnlyWhereAnEffectCouldStart() {
        assertThat(JiraMarkup.escapeMonospace("a-b snake_case x+y")).isEqualTo("a-b snake_case x+y");
        assertThat(JiraMarkup.escapeMonospace("*args -v {x} [0] a|b")).isEqualTo("\\*args \\-v \\{x\\} \\[0\\] a\\|b");
    }

    @Test
    void aRoundTripKeepsTheCommonSubset() {
        String markdown = "# Plan\n\n**Goal**: ship *it*.\n\n- step `one`\n  - detail\n1. first\n\n```java\nint x;\n```";
        assertThat(JiraMarkup.toMarkdown(JiraMarkup.toWiki(markdown))).isEqualTo(markdown);
    }
}
