package com.flowtivo.tinfirm.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.mcp.internal.GmailReplies.Address;
import com.flowtivo.tinfirm.mcp.internal.GmailReplies.Original;

/** An agent's answer as a draft reply in Gmail (ADR-0084, decision 7). */
class GmailRepliesTest {

    private static String reply(Original original, String markdown, String ownAddress) {
        return new String(Objects.requireNonNull(GmailReplies.reply(original, markdown, ownAddress)),
                StandardCharsets.US_ASCII);
    }

    @Test
    void repliesToTheSenderInTheThreadWithTheAnswerAsTextAndHtml() {
        String mime = reply(new Original("Invoice\r\n overdue", "Bob <bob@example.test>", null,
                "me@example.test", "<m1@example.test>", "<m0@example.test>"), "Hello **Bob**", "me@example.test");
        assertThat(mime).startsWith("To: \"Bob\" <bob@example.test>\r\n"
                + "Subject: Re: Invoice overdue\r\n"
                + "In-Reply-To: <m1@example.test>\r\n"
                + "References: <m0@example.test> <m1@example.test>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"tinfirm-");
        assertThat(mime).contains("Content-Type: text/plain; charset=UTF-8\r\n")
            .contains(Base64.getEncoder().encodeToString("Hello **Bob**\n".getBytes(StandardCharsets.UTF_8)))
            .contains(Base64.getEncoder().encodeToString("<div><p>Hello <b>Bob</b></p></div>\n"
                .getBytes(StandardCharsets.UTF_8)))
            .endsWith("--\r\n");
    }

    @Test
    void repliesToReplyToToTheRecipientsOfOnesOwnEmailAndToNobodyElse() {
        assertThat(reply(new Original("Re: Hi", "Bob <bob@example.test>", "Desk <desk@example.test>", null, null,
                null), "x", "me@example.test")).startsWith("To: \"Desk\" <desk@example.test>\r\nSubject: Re: Hi\r\n"
                        + "MIME-Version");
        assertThat(reply(new Original("Hi", "Me <ME@example.test>", null, "Ann <ann@example.test>, bob@example.test",
                null, null), "x", "me@example.test")).startsWith("To: \"Ann\" <ann@example.test>, bob@example.test\r\n");
        assertThat(GmailReplies.reply(new Original("Hi", "undisclosed", null, null, null, null), "x", null)).isNull();
    }

    @Test
    void headersStayOnOneLineAndWordsOtherThanAsciiAreEncoded() {
        assertThat(GmailReplies.addresses("\"Doe, Jane\" <jane@example.test>, Žofie <zofie@example.test>, "
                + "evil@example.test\r\nBcc: x@example.test, not an address"))
            .containsExactly(new Address("Doe, Jane", "jane@example.test"), new Address("Žofie", "zofie@example.test"));
        assertThat(new Address("Žofie", "zofie@example.test").encoded())
            .isEqualTo("=?UTF-8?B?" + Base64.getEncoder().encodeToString("Žofie".getBytes(StandardCharsets.UTF_8))
                    + "?= <zofie@example.test>");
        assertThat(GmailReplies.subject("Faktura č. 5")).isEqualTo("Re: Faktura č. 5");
        assertThat(GmailReplies.encodedWords("Re: Faktura č. 5")).startsWith("=?UTF-8?B?").doesNotContain("\n");
        assertThat(GmailReplies.encodedWords("ž".repeat(40)).split(" ")).hasSize(2)
            .allSatisfy(word -> assertThat(word.length()).isLessThanOrEqualTo(75));
        assertThat(GmailReplies.subject("RE: twice")).isEqualTo("RE: twice");
        assertThat(GmailReplies.subject(null)).isEqualTo("Re:");
        assertThat(reply(new Original("Hi\r\nBcc: x@example.test", "bob@example.test", null, null, "not an id",
                null), "x", null)).startsWith("To: bob@example.test\r\nSubject: Re: Hi Bcc: x@example.test\r\n"
                        + "MIME-Version");
    }

    @Test
    void theAnswersMarkdownIsSimpleHtmlAndNeverItsOwnHtml() {
        assertThat(GmailReplies.html("# Cause\nThe <script> in a&b, *really*.\nSee [the docs](https://x.test/a?b=1&c=2)"
                + " and `x < y`.\n\n1. one\n2. two\n\n```\n<b>code</b>\n```\n[bad](javascript:alert(1)) snake_case_name"))
            .isEqualTo("<p><b>Cause</b></p><p>The &lt;script&gt; in a&amp;b, <i>really</i>.<br>See "
                    + "<a href=\"https://x.test/a?b=1&amp;c=2\">the docs</a> and <code>x &lt; y</code>.</p>"
                    + "<ol><li>one</li><li>two</li></ol><pre>&lt;b&gt;code&lt;/b&gt;</pre>"
                    + "<p>[bad](javascript:alert(1)) snake_case_name</p>");
    }
}
