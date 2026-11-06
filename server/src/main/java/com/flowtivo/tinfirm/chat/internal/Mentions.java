package com.flowtivo.tinfirm.chat.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agents a message mentions (PLATFORM.md §7.3): {@code @handle} in its Markdown body.
 * <ul>
 * <li>{@code @} starts a mention at the start of the body or after a character that is not a letter, digit,
 * {@code _}, {@code -}, {@code /}, {@code .} or {@code @}, so {@code ann@example.com} and {@code owner/@repo} mention
 * nobody;</li>
 * <li>the handle is the longest run of letters, digits and hyphens after it, so {@code @test-writer,} mentions
 * {@code test-writer}, and {@code @test} does not mention {@code test-writer};</li>
 * <li>it is matched ignoring case against the handles of the company's live agents; an unknown handle is text;</li>
 * <li>nothing in a fenced code block ({@code ```} or {@code ~~~}) or in inline code ({@code `…`}) mentions.</li>
 * </ul>
 * This is ADR-0034's parser (the package 5 contract, §2.2, §5.5): a direct message names its agent with a leading
 * {@code @handle}, and package 5b reads a mention's handle with it.
 */
final class Mentions {

    /** An opening code fence: up to three spaces, then three or more backticks or tildes, then the info string. */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    private static final Set<Integer> NOT_BEFORE = Set.of((int) '_', (int) '-', (int) '/', (int) '.', (int) '@');

    private Mentions() {
    }

    /**
     * The agents the body mentions, in the order of their first mention, each once.
     *
     * @param handles the company's live agents by handle, in lower case
     */
    static List<UUID> in(String body, Map<String, UUID> handles) {
        if (handles.isEmpty() || body.indexOf('@') < 0) {
            return List.of();
        }
        String text = withoutCode(body);
        Set<UUID> mentioned = new LinkedHashSet<>();
        for (int at = text.indexOf('@'); at >= 0; at = text.indexOf('@', at + 1)) {
            if (at > 0 && !startsAfter(text.codePointBefore(at))) {
                continue;
            }
            int end = at + 1;
            while (end < text.length() && partOfHandle(text.codePointAt(end))) {
                end += Character.charCount(text.codePointAt(end));
            }
            if (end > at + 1) {
                UUID agentId = handles.get(text.substring(at + 1, end).toLowerCase(Locale.ROOT));
                if (agentId != null) {
                    mentioned.add(agentId);
                }
            }
        }
        return List.copyOf(mentioned);
    }

    private static boolean startsAfter(int codePoint) {
        return !Character.isLetterOrDigit(codePoint) && !NOT_BEFORE.contains(codePoint);
    }

    private static boolean partOfHandle(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '-';
    }

    /**
     * The body with the content of fenced code blocks and inline code spans replaced by spaces (line breaks kept). A
     * fence closes with a line of at least as many of its characters; an unclosed fence runs to the end. An inline
     * span opens with a run of backticks and closes with the next run of the same length; a run without one is text.
     */
    static String withoutCode(String body) {
        StringBuilder text = new StringBuilder(body.length());
        char fenceChar = 0;
        int fenceLength = 0;
        int start = 0;
        while (start <= body.length()) {
            int newline = body.indexOf('\n', start);
            int end = newline < 0 ? body.length() : newline;
            String line = body.substring(start, end);
            String content = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (fenceLength > 0) {
                if (closes(content, fenceChar, fenceLength)) {
                    fenceLength = 0;
                }
                text.append(blank(line));
            }
            else {
                Matcher fence = FENCE.matcher(content);
                if (fence.matches() && !(fence.group(1).charAt(0) == '`' && fence.group(2).indexOf('`') >= 0)) {
                    fenceChar = fence.group(1).charAt(0);
                    fenceLength = fence.group(1).length();
                    text.append(blank(line));
                }
                else {
                    text.append(line);
                }
            }
            if (newline < 0) {
                break;
            }
            text.append('\n');
            start = newline + 1;
        }
        return withoutInlineCode(text.toString());
    }

    /** A closing fence: up to three spaces, at least as many of the fence's characters, then only whitespace. */
    private static boolean closes(String line, char fenceChar, int fenceLength) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        String fence = line.substring(indent).stripTrailing();
        return indent <= 3 && fence.length() >= fenceLength && fence.chars().allMatch(c -> c == fenceChar);
    }

    private static String withoutInlineCode(String text) {
        StringBuilder result = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '`') {
                i++;
                continue;
            }
            int length = run(text, i);
            int close = closing(text, i + length, length);
            if (close < 0) {
                i += length;
                continue;
            }
            for (int j = i; j < close + length; j++) {
                if (result.charAt(j) != '\n') {
                    result.setCharAt(j, ' ');
                }
            }
            i = close + length;
        }
        return result.toString();
    }

    /** Where the next run of exactly {@code length} backticks starts, or -1. */
    private static int closing(String text, int from, int length) {
        int i = from;
        while (i < text.length()) {
            if (text.charAt(i) == '`') {
                int run = run(text, i);
                if (run == length) {
                    return i;
                }
                i += run;
            }
            else {
                i++;
            }
        }
        return -1;
    }

    private static int run(String text, int from) {
        int end = from;
        while (end < text.length() && text.charAt(end) == '`') {
            end++;
        }
        return end - from;
    }

    private static String blank(String line) {
        return " ".repeat(line.length());
    }
}
