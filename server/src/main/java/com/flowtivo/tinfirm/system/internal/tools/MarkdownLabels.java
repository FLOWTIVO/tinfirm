package com.flowtivo.tinfirm.system.internal.tools;

import java.util.regex.Pattern;

/** A bold label in front of a Markdown comment (GitHub, GitLab). */
public final class MarkdownLabels {

    /** A Markdown line that starts a block: a heading, a list, a quote, a fence, a table, HTML or indented code. */
    private static final Pattern BLOCK_START = Pattern.compile(
            "^( {4}|\\t|#{1,6}(\\s|$)|[-*+]\\s|\\d{1,9}[.)]\\s|>|```|~~~|\\||<).*", Pattern.DOTALL);

    /** Characters that would start Markdown inside a bold label. */
    private static final Pattern MARKDOWN_SPECIAL = Pattern.compile("([\\\\`*_\\[\\]<>#|~!])");

    private MarkdownLabels() {
    }

    /** {@code **label**} before text on the same line, or in a paragraph of its own before a block. */
    public static String withLabel(String label, String body) {
        String bold = "**" + MARKDOWN_SPECIAL.matcher(label).replaceAll("\\\\$1") + "**";
        if (body.isEmpty()) {
            return bold;
        }
        return BLOCK_START.matcher(body).matches() ? bold + "\n\n" + body : bold + " " + body;
    }
}
