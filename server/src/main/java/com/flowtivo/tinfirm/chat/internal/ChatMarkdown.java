package com.flowtivo.tinfirm.chat.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agent's Markdown in each platform's text (the package 5 contract, §5.10, open point 12, C11).
 * <ul>
 * <li>Slack mrkdwn: {@code **b**} {@code *b*}, {@code *i*} and {@code _i_} {@code _i_}, {@code ~~s~~} {@code ~s~},
 * {@code [t](u)} {@code <u|t>} for http(s) only (an image as its link), a heading a bold line, a list item
 * {@code •}, quotes and code as written, a fence without its language, a table a fenced block; {@code &}, {@code <}
 * and {@code >} escaped everywhere, so that {@code <!channel>}, {@code <!here>}, {@code <@U…>} and {@code <#C…>} stay
 * text and ping nobody.</li>
 * <li>Teams Markdown: as written, a heading a bold line, a table a fenced block; {@code &} and {@code <} escaped, so
 * that {@code <at>} and HTML stay text.</li>
 * <li>{@link #split}: parts of at most a limit, cut at a blank line, else a line break, else hard; a fence open at a
 * cut is closed and opened again; parts marked {@code (1/3)}.</li>
 * </ul>
 */
final class ChatMarkdown {

    /** Slack's most characters of one message Tinfirm posts, and Teams'. */
    static final int SLACK_PART = 12_000;

    static final int TEAMS_PART = 20_000;

    /** More parts than this post the first and then the whole answer as a file (Slack) or a link (Teams). */
    static final int MAX_PARTS = 4;

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(```|~~~)(.*)$");

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");

    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");

    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");

    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]\\n]*)\\]\\(([^)\\s]+)\\)");

    private static final Pattern BOLD = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");

    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![*\\w])");

    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");

    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]+`");

    private ChatMarkdown() {
    }

    // ---- Slack -----------------------------------------------------------------------------------------------------

    static String toSlack(String markdown) {
        StringBuilder out = new StringBuilder();
        boolean fenced = false;
        boolean table = false;
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                if (table) {
                    out.append("```\n");
                    table = false;
                }
                out.append("```\n");
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                out.append(escapeSlack(line)).append('\n');
                continue;
            }
            if (TABLE_ROW.matcher(line).matches()) {
                if (!table) {
                    out.append("```\n");
                    table = true;
                }
                if (!line.replaceAll("[|:\\-\\s]", "").isEmpty()) {
                    out.append(escapeSlack(line.strip())).append('\n');
                }
                continue;
            }
            if (table) {
                out.append("```\n");
                table = false;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                out.append('*').append(inlineSlack(heading.group(1))).append("*\n");
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                out.append(bullet.group(1)).append("• ").append(inlineSlack(bullet.group(2))).append('\n');
                continue;
            }
            out.append(inlineSlack(line)).append('\n');
        }
        if (table || fenced) {
            out.append("```\n");
        }
        return trimEnd(out);
    }

    /** One line outside code: escaped, then the inline marks and links in Slack's form; inline code kept. */
    private static String inlineSlack(String line) {
        StringBuilder out = new StringBuilder();
        Matcher code = INLINE_CODE.matcher(line);
        int at = 0;
        while (code.find()) {
            out.append(inlineSlackText(line.substring(at, code.start())));
            out.append(escapeSlack(code.group()));
            at = code.end();
        }
        out.append(inlineSlackText(line.substring(at)));
        return out.toString();
    }

    private static String inlineSlackText(String text) {
        StringBuilder out = new StringBuilder();
        Matcher link = LINK.matcher(text);
        int at = 0;
        while (link.find()) {
            out.append(marksSlack(escapeSlack(text.substring(at, link.start()))));
            String label = link.group(1).isBlank() ? link.group(2) : link.group(1);
            String url = link.group(2);
            if (url.startsWith("https://") || url.startsWith("http://")) {
                out.append('<').append(url.replace("|", "%7C").replace(">", "%3E").replace("<", "%3C"))
                    .append('|').append(escapeSlack(label).replace("|", "¦")).append('>');
            }
            else {
                out.append(marksSlack(escapeSlack(label)));
            }
            at = link.end();
        }
        out.append(marksSlack(escapeSlack(text.substring(at))));
        return out.toString();
    }

    private static String marksSlack(String text) {
        String bold = BOLD.matcher(text).replaceAll("\u0001$1\u0001");
        String italic = ITALIC.matcher(bold).replaceAll("_$1_");
        String strike = STRIKE.matcher(italic).replaceAll("~$1~");
        return strike.replace('\u0001', '*');
    }

    /** Slack's three control characters as entities: nothing in the text can become a ping or a link (C11). */
    static String escapeSlack(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- Teams -----------------------------------------------------------------------------------------------------

    static String toTeams(String markdown) {
        StringBuilder out = new StringBuilder();
        boolean fenced = false;
        boolean table = false;
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            if (FENCE.matcher(line).matches()) {
                if (table) {
                    out.append("```\n");
                    table = false;
                }
                out.append("```\n");
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                out.append(escapeTeams(line)).append('\n');
                continue;
            }
            if (TABLE_ROW.matcher(line).matches()) {
                if (!table) {
                    out.append("```\n");
                    table = true;
                }
                out.append(escapeTeams(line.strip())).append('\n');
                continue;
            }
            if (table) {
                out.append("```\n");
                table = false;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                out.append("**").append(escapeTeams(heading.group(1))).append("**\n");
                continue;
            }
            out.append(escapeTeams(line)).append('\n');
        }
        if (table || fenced) {
            out.append("```\n");
        }
        return trimEnd(out);
    }

    /** {@code <at>} and HTML stay text on Teams. */
    static String escapeTeams(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;");
    }

    // ---- splitting -------------------------------------------------------------------------------------------------

    /**
     * The text in parts of at most {@code max} characters (with room for the part's mark), cut at a blank line, else
     * a line break, else hard; a fence open at a cut is closed at its end and opened again in the next part. A text
     * that fits is one part without a mark; parts are marked {@code (1/3)}.
     */
    static List<String> split(String text, int max) {
        if (text.length() <= max) {
            return List.of(text);
        }
        int budget = max - 16;
        List<String> parts = new ArrayList<>();
        String rest = text;
        boolean reopen = false;
        while (!rest.isEmpty()) {
            String prefix = reopen ? "```\n" : "";
            int room = budget - prefix.length() - 4;
            if (rest.length() <= room) {
                parts.add(prefix + rest);
                break;
            }
            int cut = rest.lastIndexOf("\n\n", room);
            if (cut < room / 2) {
                cut = rest.lastIndexOf('\n', room);
            }
            if (cut < room / 2) {
                cut = room;
            }
            String part = rest.substring(0, cut);
            rest = rest.substring(cut).replaceFirst("^\n+", "");
            boolean open = (prefix + part).lines().filter(line -> FENCE.matcher(line).matches()).count() % 2 == 1;
            parts.add(prefix + part + (open ? "\n```" : ""));
            reopen = open;
        }
        List<String> marked = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            marked.add(parts.get(i) + "\n(" + (i + 1) + "/" + parts.size() + ")");
        }
        return marked;
    }

    private static String trimEnd(StringBuilder out) {
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        return out.substring(0, end);
    }
}
