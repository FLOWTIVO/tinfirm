package com.flowtivo.tinfirm.chat.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The agent's Markdown in each platform's text (the package 5 contract, §5.10, C11): Slack's mrkdwn with its control
 * characters escaped, so nothing becomes a ping or a link but an http(s) link; Teams' Markdown with {@code <at>} and
 * HTML as text; parts cut at blank lines, never inside a fence.
 */
class ChatMarkdownTest {

    @Test
    void writesSlacksMrkdwn() {
        assertThat(ChatMarkdown.toSlack("**bold**, *italic* and ~~gone~~")).isEqualTo("*bold*, _italic_ and ~gone~");
        assertThat(ChatMarkdown.toSlack("# Title\n- one\n- two")).isEqualTo("*Title*\n• one\n• two");
        assertThat(ChatMarkdown.toSlack("See [the docs](https://example.test/a|b) and [x](javascript:alert)"))
            .isEqualTo("See <https://example.test/a%7Cb|the docs> and x");
        assertThat(ChatMarkdown.toSlack("```java\nint a = 1 < 2;\n```")).isEqualTo("```\nint a = 1 &lt; 2;\n```");
        assertThat(ChatMarkdown.toSlack("| a | b |\n|---|---|\n| 1 | 2 |")).isEqualTo("```\n| a | b |\n| 1 | 2 |\n```");
        assertThat(ChatMarkdown.toSlack("`**not bold**`")).isEqualTo("`**not bold**`");
    }

    @Test
    void neverPingsOrLinksInSlack() {
        String slack = ChatMarkdown.toSlack("Hey <!channel> and <!here>, ask <@U123> in <#C123> or <https://x.test|y>");
        assertThat(slack).doesNotContain("<!").doesNotContain("<@").doesNotContain("<#").doesNotContain("<https")
            .contains("&lt;!channel&gt;").contains("&lt;@U123&gt;");
        assertThat(ChatMarkdown.toSlack("A & B")).isEqualTo("A &amp; B");
    }

    @Test
    void writesTeamsMarkdownWithMentionsAndHtmlAsText() {
        assertThat(ChatMarkdown.toTeams("**bold** <at>Everyone</at> <b>x</b>"))
            .isEqualTo("**bold** &lt;at>Everyone&lt;/at> &lt;b>x&lt;/b>");
        assertThat(ChatMarkdown.toTeams("## Heading")).isEqualTo("**Heading**");
    }

    @Test
    void splitsAtBlankLinesAndNeverInsideAFence() {
        assertThat(ChatMarkdown.split("short", 100)).containsExactly("short");
        String paragraph = "word ".repeat(30).strip();
        String text = paragraph + "\n\n" + paragraph + "\n\n" + paragraph;
        List<String> parts = ChatMarkdown.split(text, 200);
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts.getFirst()).endsWith("(1/" + parts.size() + ")");
        assertThat(parts).allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(200));

        String code = "```\n" + "line of code\n".repeat(40) + "```";
        List<String> fenced = ChatMarkdown.split(code, 200);
        assertThat(fenced).hasSizeGreaterThan(1).allSatisfy(part -> {
            long fences = part.lines().filter(line -> line.startsWith("```")).count();
            assertThat(fences % 2).as("fences of %s", part).isZero();
        });
    }
}
