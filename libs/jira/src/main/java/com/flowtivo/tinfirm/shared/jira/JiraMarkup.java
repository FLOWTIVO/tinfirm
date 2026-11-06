package com.flowtivo.tinfirm.shared.jira;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Converts between Jira wiki markup (what Jira Data Center's REST API v2 returns and accepts) and the Markdown that
 * tools read and write (the package 3 contract, §5.4). Both directions cover the common subset: headings, bold,
 * italic, monospace, code and noformat blocks, bullet and numbered lists, links, quotes and simple tables. What a
 * direction cannot convert stays as it is, so no text is lost; inside code nothing is converted.
 */
public final class JiraMarkup {

    /** Stands in for a protected span (code, link, URL) while the rest of a line is converted. */
    private static final char MARK = '\u0000';

    private static final Pattern PLACEHOLDER = Pattern.compile(MARK + "(\\d+)" + MARK);

    // ---- Jira to Markdown ------------------------------------------------------------------------------------------

    private static final Pattern WIKI_BLOCK_START = Pattern.compile("^\\s*\\{(code|noformat)(?::([^}]*))?\\}(.*)$");

    private static final Pattern WIKI_HEADING = Pattern.compile("^\\s*h([1-6])\\.\\s+(.*)$");

    private static final Pattern WIKI_LIST = Pattern.compile("^\\s*([*#]+|-)\\s+(.*)$");

    private static final Pattern WIKI_QUOTE_LINE = Pattern.compile("^\\s*bq\\.\\s+(.*)$");

    private static final Pattern WIKI_RULE = Pattern.compile("^\\s*-{4,}\\s*$");

    private static final Pattern WIKI_HEADER_ROW = Pattern.compile("^\\s*\\|\\|(.*?)\\|\\|?\\s*$");

    private static final Pattern WIKI_MONO = Pattern.compile("\\{\\{(.+?)}}");

    private static final Pattern WIKI_LINK = Pattern.compile("\\[([^\\[\\]|]*)\\|((?:https?|mailto):[^\\[\\]|\\s]+)]");

    private static final Pattern WIKI_BARE_LINK = Pattern.compile("\\[((?:https?|mailto):[^\\[\\]|\\s]+)]");

    private static final Pattern WIKI_MENTION = Pattern.compile("\\[~([^\\[\\]|\\s]+)]");

    private static final Pattern WIKI_BOLD = Pattern
        .compile("(?<![\\p{L}\\p{N}*\\\\])\\*(?=[^\\s*])(.+?)(?<=[^\\s*\\\\])\\*(?![\\p{L}\\p{N}*])");

    private static final Pattern WIKI_ITALIC = Pattern
        .compile("(?<![\\p{L}\\p{N}_\\\\])_(?=[^\\s_])(.+?)(?<=[^\\s_\\\\])_(?![\\p{L}\\p{N}_])");

    // ---- Markdown to Jira ------------------------------------------------------------------------------------------

    private static final Pattern MD_FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*([\\w+#.-]*)\\s*$");

    private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");

    private static final Pattern MD_LIST = Pattern.compile("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$");

    private static final Pattern MD_QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");

    private static final Pattern MD_RULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");

    private static final Pattern MD_TABLE_SEPARATOR = Pattern
        .compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");

    private static final Pattern MD_CODE = Pattern.compile("(`+)(.+?)\\1");

    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\((\\S+?)\\)");

    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)]\\(((?:https?|mailto):[^)\\s]+)\\)");

    private static final Pattern MD_AUTOLINK = Pattern.compile("<((?:https?|mailto):[^>\\s]+)>");

    /** A bare URL; it does not end in punctuation or in an emphasis marker that closes around it. */
    private static final Pattern URL = Pattern
        .compile("(?:https?|mailto):[^\\s<>\\[\\]{}|]*[^\\s<>\\[\\]{}|*_.,;:!?)'\"]");

    /** A {@code {} or {@code [} that is not escaped yet: Jira would read it as a macro or a link. */
    private static final Pattern WIKI_OPENER = Pattern.compile("(?<!\\\\)([{\\[])");

    private static final Pattern MD_BOLD_ITALIC = Pattern.compile("\\*\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*\\*");

    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");

    private static final Pattern MD_BOLD_UNDERSCORE = Pattern
        .compile("(?<![\\p{L}\\p{N}_])__(?=\\S)(.+?)(?<=\\S)__(?![\\p{L}\\p{N}_])");

    private static final Pattern MD_ITALIC = Pattern
        .compile("(?<![\\p{L}\\p{N}*\\\\])\\*(?=[^\\s*])(.+?)(?<=[^\\s*\\\\])\\*(?![\\p{L}\\p{N}*])");

    private static final Pattern MD_STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");

    /** Characters that start or end an effect in Jira wiki markup next to a word boundary. */
    private static final String WIKI_EFFECTS = "*_+-^~";

    /** Characters that always mean something in Jira wiki markup. */
    private static final String WIKI_SYNTAX = "{}[]|!";

    private JiraMarkup() {
    }

    /** Jira wiki markup to Markdown; null and blank stay null. */
    public static @Nullable String toMarkdown(@Nullable String wiki) {
        if (wiki == null || wiki.isBlank()) {
            return null;
        }
        List<String> lines = lines(wiki);
        List<String> out = new ArrayList<>();
        boolean quote = false;
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher block = WIKI_BLOCK_START.matcher(line);
            if (block.matches()) {
                i = wikiBlock(lines, i, block, out, quote);
                continue;
            }
            i++;
            String prefix = quote ? "> " : "";
            if (line.strip().equals("{quote}")) {
                quote = !quote;
                continue;
            }
            Matcher matcher = WIKI_HEADING.matcher(line);
            if (matcher.matches()) {
                out.add(prefix + "#".repeat(Integer.parseInt(matcher.group(1))) + " " + wikiInline(matcher.group(2)));
                continue;
            }
            matcher = WIKI_QUOTE_LINE.matcher(line);
            if (matcher.matches()) {
                out.add("> " + wikiInline(matcher.group(1)));
                continue;
            }
            if (WIKI_RULE.matcher(line).matches()) {
                out.add(prefix + "---");
                continue;
            }
            matcher = WIKI_LIST.matcher(line);
            if (matcher.matches()) {
                out.add(prefix + markdownListItem(matcher.group(1)) + wikiInline(matcher.group(2)));
                continue;
            }
            matcher = WIKI_HEADER_ROW.matcher(line);
            if (matcher.matches()) {
                List<String> cells = List.of(matcher.group(1).split("\\|\\|", -1));
                out.add(prefix + "| " + String.join(" | ", cells.stream().map(cell -> wikiInline(cell.strip()))
                    .toList()) + " |");
                out.add(prefix + "|" + " --- |".repeat(cells.size()));
                continue;
            }
            out.add(prefix + wikiInline(line));
        }
        return String.join("\n", out).strip();
    }

    /** A {@code {code}} or {@code {noformat}} block as a fenced block; unclosed, it runs to the end. */
    private static int wikiBlock(List<String> lines, int start, Matcher block, List<String> out, boolean quote) {
        String kind = block.group(1);
        String closing = "{" + kind + "}";
        String language = "code".equals(kind) ? language(block.group(2)) : "";
        List<String> content = new ArrayList<>();
        String after = null;
        String rest = block.group(3);
        int i = start + 1;
        int close = rest.indexOf(closing);
        if (close >= 0) {
            content.add(rest.substring(0, close));
            after = rest.substring(close + closing.length());
        }
        else {
            if (!rest.isBlank()) {
                content.add(rest);
            }
            while (i < lines.size()) {
                String line = lines.get(i++);
                close = line.indexOf(closing);
                if (close >= 0) {
                    if (!line.substring(0, close).isBlank()) {
                        content.add(line.substring(0, close));
                    }
                    after = line.substring(close + closing.length());
                    break;
                }
                content.add(line);
            }
        }
        String prefix = quote ? "> " : "";
        String fence = content.stream().anyMatch(line -> line.contains("```")) ? "~~~~" : "```";
        out.add(prefix + fence + language);
        content.forEach(line -> out.add(prefix + line));
        out.add(prefix + fence);
        if (after != null && !after.isBlank()) {
            out.add(prefix + wikiInline(after.strip()));
        }
        return i;
    }

    /** The language of {@code {code:java}} or {@code {code:language=java|title=x}}; empty without one. */
    private static String language(@Nullable String parameters) {
        if (parameters == null) {
            return "";
        }
        for (String parameter : parameters.split("\\|")) {
            String trimmed = parameter.strip();
            if (!trimmed.contains("=")) {
                return safeLanguage(trimmed);
            }
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("language=")) {
                return safeLanguage(trimmed.substring("language=".length()));
            }
        }
        return "";
    }

    private static String safeLanguage(String language) {
        return language.matches("[\\w+#.-]{1,30}") ? language.toLowerCase(Locale.ROOT) : "";
    }

    /** {@code *}, {@code **}, {@code #}, {@code *#} or {@code -}: a Markdown item indented by its depth. */
    private static String markdownListItem(String markers) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < markers.length() - 1; i++) {
            indent.append(markers.charAt(i) == '#' ? "   " : "  ");
        }
        return indent + (markers.charAt(markers.length() - 1) == '#' ? "1. " : "- ");
    }

    /** Monospace, links and mentions are protected first, so bold and italic never change them. */
    private static String wikiInline(String text) {
        List<String> kept = new ArrayList<>();
        String result = protect(text, WIKI_MONO, kept, matcher -> codeSpan(matcher.group(1)));
        result = protect(result, WIKI_LINK, kept, matcher -> "[" + (matcher.group(1).isBlank() ? matcher.group(2)
                : matcher.group(1)) + "](" + matcher.group(2) + ")");
        result = protect(result, WIKI_BARE_LINK, kept, matcher -> "<" + matcher.group(1) + ">");
        result = protect(result, WIKI_MENTION, kept, matcher -> "@" + matcher.group(1));
        result = protect(result, URL, kept, Matcher::group);
        result = WIKI_BOLD.matcher(result).replaceAll(matcher -> Matcher.quoteReplacement("**" + matcher.group(1)
                + "**"));
        result = WIKI_ITALIC.matcher(result).replaceAll(matcher -> Matcher.quoteReplacement("*" + matcher.group(1)
                + "*"));
        return restore(result, kept);
    }

    /** A Markdown code span that holds {@code text}, with a longer fence when the text has backticks. */
    private static String codeSpan(String text) {
        String fence = text.contains("`") ? "``" : "`";
        String padding = text.startsWith("`") || text.endsWith("`") ? " " : "";
        return fence + padding + text + padding + fence;
    }

    /** Markdown to Jira wiki markup; null and blank give an empty text. */
    public static String toWiki(@Nullable String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        List<String> lines = lines(markdown);
        List<String> out = new ArrayList<>();
        List<Integer> listIndents = new ArrayList<>();
        StringBuilder listMarkers = new StringBuilder();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher fence = MD_FENCE.matcher(line);
            if (fence.matches()) {
                i = markdownFence(lines, i, fence, out);
                listIndents.clear();
                listMarkers.setLength(0);
                continue;
            }
            Matcher item = MD_LIST.matcher(line);
            if (item.matches() && !MD_RULE.matcher(line).matches()) {
                int indent = width(item.group(1));
                char marker = Character.isDigit(item.group(2).charAt(0)) ? '#' : '*';
                while (!listIndents.isEmpty() && listIndents.getLast() > indent) {
                    listIndents.removeLast();
                    listMarkers.setLength(listMarkers.length() - 1);
                }
                if (listIndents.isEmpty() || listIndents.getLast() < indent) {
                    listIndents.add(indent);
                    listMarkers.append(marker);
                }
                else {
                    listMarkers.setCharAt(listMarkers.length() - 1, marker);
                }
                out.add(listMarkers + " " + markdownInline(item.group(3)));
                i++;
                continue;
            }
            if (!line.isBlank()) {
                listIndents.clear();
                listMarkers.setLength(0);
            }
            if (MD_QUOTE.matcher(line).matches()) {
                i = markdownQuote(lines, i, out);
                continue;
            }
            if (i + 1 < lines.size() && line.contains("|") && MD_TABLE_SEPARATOR.matcher(lines.get(i + 1)).matches()) {
                i = markdownTable(lines, i, out);
                continue;
            }
            i++;
            Matcher heading = MD_HEADING.matcher(line);
            if (heading.matches()) {
                out.add("h" + heading.group(1).length() + ". " + markdownInline(heading.group(2)));
            }
            else if (MD_RULE.matcher(line).matches()) {
                out.add("----");
            }
            else {
                out.add(markdownInline(line));
            }
        }
        return String.join("\n", out).strip();
    }

    /** A fenced block as {@code {code:language}}, or {@code {noformat}} without a language; unclosed, it runs to the end. */
    private static int markdownFence(List<String> lines, int start, Matcher fence, List<String> out) {
        String marker = fence.group(1);
        String language = fence.group(2).toLowerCase(Locale.ROOT);
        List<String> content = new ArrayList<>();
        int i = start + 1;
        while (i < lines.size()) {
            String line = lines.get(i++);
            if (line.strip().startsWith(marker) && line.strip().chars().allMatch(c -> c == marker.charAt(0))) {
                break;
            }
            content.add(line);
        }
        String closing = language.isEmpty() ? "{noformat}" : "{code}";
        out.add(language.isEmpty() ? "{noformat}" : "{code:" + language + "}");
        content.forEach(line -> out.add(line.replace(closing, "\\" + closing)));
        out.add(closing);
        return i;
    }

    /** Consecutive quoted lines: one becomes {@code bq.}, several a {@code {quote}} block. */
    private static int markdownQuote(List<String> lines, int start, List<String> out) {
        List<String> quoted = new ArrayList<>();
        int i = start;
        while (i < lines.size()) {
            Matcher matcher = MD_QUOTE.matcher(lines.get(i));
            if (!matcher.matches()) {
                break;
            }
            quoted.add(matcher.group(1));
            i++;
        }
        if (quoted.size() == 1) {
            out.add("bq. " + markdownInline(quoted.getFirst()));
        }
        else {
            out.add("{quote}");
            String inner = toWiki(String.join("\n", quoted));
            out.addAll(lines(inner));
            out.add("{quote}");
        }
        return i;
    }

    /** A table with a header row: {@code ||a||b||}, then {@code |c|d|} for every row. */
    private static int markdownTable(List<String> lines, int start, List<String> out) {
        out.add("||" + String.join("||", cells(lines.get(start))) + "||");
        int i = start + 2;
        while (i < lines.size() && lines.get(i).contains("|") && !lines.get(i).isBlank()) {
            out.add("|" + String.join("|", cells(lines.get(i))) + "|");
            i++;
        }
        return i;
    }

    private static List<String> cells(String row) {
        String trimmed = row.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("(?<!\\\\)\\|", -1)) {
            String converted = markdownInline(cell.strip());
            cells.add(converted.isEmpty() ? " " : converted);
        }
        return cells;
    }

    /**
     * Code, images, links and URLs are protected first. Bold is marked, then italic converted, then the bold mark
     * becomes Jira's {@code *}, so a converted bold is never read as Markdown italic.
     */
    private static String markdownInline(String text) {
        List<String> kept = new ArrayList<>();
        String result = protect(text, MD_CODE, kept, matcher -> "{{" + escapeMonospace(matcher.group(2).strip())
                + "}}");
        result = protect(result, MD_IMAGE, kept, matcher -> "!" + matcher.group(2) + "!");
        result = protect(result, MD_LINK, kept, matcher -> "[" + matcher.group(1).replace("|", "\\|") + "|"
                + matcher.group(2) + "]");
        result = protect(result, MD_AUTOLINK, kept, matcher -> "[" + matcher.group(1) + "]");
        result = protect(result, URL, kept, Matcher::group);
        result = WIKI_OPENER.matcher(result).replaceAll("\\\\$1");
        String bold = "\u0001";
        result = MD_BOLD_ITALIC.matcher(result).replaceAll(matcher -> Matcher.quoteReplacement(bold + "_"
                + matcher.group(1) + "_" + bold));
        result = MD_BOLD.matcher(result).replaceAll(matcher -> Matcher.quoteReplacement(bold + matcher.group(1)
                + bold));
        result = MD_BOLD_UNDERSCORE.matcher(result).replaceAll(matcher -> Matcher.quoteReplacement(bold
                + matcher.group(1) + bold));
        result = MD_ITALIC.matcher(result).replaceAll(matcher -> Matcher.quoteReplacement("_" + matcher.group(1)
                + "_"));
        result = MD_STRIKE.matcher(result).replaceAll(matcher -> Matcher.quoteReplacement("-" + matcher.group(1)
                + "-"));
        return restore(result.replace(bold, "*"), kept);
    }

    /**
     * Inside Jira's {@code {{...}}} effects still apply. Syntax characters are always escaped with a backslash, effect
     * characters only next to a word boundary, where they could open or close an effect: {@code a-b} and
     * {@code snake_case} stay, {@code --force} becomes {@code \-\-force}.
     */
    public static String escapeMonospace(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean syntax = WIKI_SYNTAX.indexOf(c) >= 0;
            boolean effect = WIKI_EFFECTS.indexOf(c) >= 0 && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))
                    || i == text.length() - 1 || !Character.isLetterOrDigit(text.charAt(i + 1)));
            if (syntax || effect) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    // ---- Shared ----------------------------------------------------------------------------------------------------

    /** Replaces each match with a placeholder; the converted text of the match is kept for {@link #restore}. */
    private static String protect(String text, Pattern pattern, List<String> kept,
            Function<Matcher, String> converted) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (insidePlaceholder(text, matcher.start())) {
                continue;
            }
            kept.add(converted.apply(matcher));
            matcher.appendReplacement(result, Matcher.quoteReplacement(MARK + String.valueOf(kept.size() - 1) + MARK));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Whether the position is within a placeholder (between its two marks). */
    private static boolean insidePlaceholder(String text, int position) {
        int marks = 0;
        for (int i = 0; i < position; i++) {
            if (text.charAt(i) == MARK) {
                marks++;
            }
        }
        return marks % 2 == 1;
    }

    private static String restore(String text, List<String> kept) {
        String result = text;
        // Placeholders can hold others (a link text with code), so restore until none is left.
        for (int round = 0; round < 3 && result.indexOf(MARK) >= 0; round++) {
            result = PLACEHOLDER.matcher(result).replaceAll(matcher -> Matcher.quoteReplacement(kept.get(
                    Integer.parseInt(matcher.group(1)))));
        }
        return result;
    }

    private static List<String> lines(String text) {
        return List.of(text.replace("\r\n", "\n").replace('\r', '\n').replace(MARK, ' ').split("\n", -1));
    }

    /** The indentation of a list item, a tab counting as four spaces. */
    private static int width(String indentation) {
        int width = 0;
        for (char c : indentation.toCharArray()) {
            width += c == '\t' ? 4 : 1;
        }
        return width;
    }
}
