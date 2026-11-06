package com.flowtivo.tinfirm.shared.jira;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;


import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts between the Atlassian Document Format (ADF), the JSON that Jira Cloud's REST API v3 returns and accepts
 * for descriptions and comments, and the Markdown that tools read and write (the package 3 contract, §5.4: a
 * {@code get} reads an issue as Markdown, a {@code comment} writes Markdown as ADF). A body in the system's markup is
 * the document as JSON text, as a Jira Data Center body is wiki markup. Both directions cover the common subset:
 * headings, bold, italic, strikethrough, code spans, code blocks, bullet, numbered and task lists, links, quotes,
 * rules and simple tables; reading also mentions, emoji, dates, statuses, panels, expands and the names of attached
 * media. What a direction cannot convert keeps its text, so nothing is lost; inside code
 * nothing is converted. A body that is not a document (plain text, or JSON of another shape) is read as text.
 */
public final class Adf {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** A Jira Cloud mention in wiki markup, as the v2 API shows it: {@code [~accountid:5b10a...]}. */
    private static final String WIKI_MENTION = "[~accountid:";

    // ---- Markdown to ADF -------------------------------------------------------------------------------------------

    private static final Pattern MD_FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*([\\w+#.-]*)\\s*$");

    private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");

    private static final Pattern MD_LIST = Pattern.compile("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$");

    private static final Pattern MD_TASK = Pattern.compile("^\\[([ xX])]\\s+(.*)$");

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

    private static final Pattern MD_BOLD_ITALIC = Pattern.compile("\\*\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*\\*");

    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");

    private static final Pattern MD_BOLD_UNDERSCORE = Pattern
        .compile("(?<![\\p{L}\\p{N}_])__(?=\\S)(.+?)(?<=\\S)__(?![\\p{L}\\p{N}_])");

    private static final Pattern MD_ITALIC = Pattern
        .compile("(?<![\\p{L}\\p{N}*\\\\])\\*(?=[^\\s*])(.+?)(?<=[^\\s*\\\\])\\*(?![\\p{L}\\p{N}*])");

    private static final Pattern MD_ITALIC_UNDERSCORE = Pattern
        .compile("(?<![\\p{L}\\p{N}_\\\\])_(?=[^\\s_])(.+?)(?<=[^\\s_\\\\])_(?![\\p{L}\\p{N}_])");

    private static final Pattern MD_STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");

    /** Characters a backslash escapes in Markdown. */
    private static final String ESCAPABLE = "\\`*_{}[]()#+-.!~|>";

    /** The kinds of list a run of items becomes. */
    private enum ListKind {
        BULLET, ORDERED, TASK
    }

    /** One line of a list run, as parsed. */
    private record Item(int indent, ListKind kind, boolean done, String text) {
    }

    /** A list under construction: its node, its indentation and its last item, which nested lists go into. */
    private record Level(int indent, ListKind kind, ObjectNode list, @Nullable ObjectNode lastItem) {
    }

    private Adf() {
    }

    // ---- Reading: ADF to Markdown ----------------------------------------------------------------------------------

    /**
     * A body of Jira Cloud as Markdown: an ADF document converted, anything else (plain text, JSON of another shape)
     * as it is; null and blank give null.
     */
    public static @Nullable String toMarkdown(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode node = parse(body);
        if (node == null || !isDocument(node)) {
            String text = node != null && node.isString() ? node.stringValue() : body;
            return text.isBlank() ? null : text.strip();
        }
        String markdown = String.join("\n", blocks(node.get("content"))).strip();
        return markdown.isEmpty() ? null : markdown;
    }

    /** Whether the body is an ADF document: a JSON object of type {@code doc}. */
    public static boolean isDocument(@Nullable String body) {
        JsonNode node = body != null ? parse(body) : null;
        return node != null && isDocument(node);
    }

    /** Whether the body mentions the account: a {@code mention} node with its id, or {@code [~accountid:...]}. */
    public static boolean mentions(String body, String accountId) {
        JsonNode node = parse(body);
        if (node == null || !isDocument(node)) {
            return body.toLowerCase(Locale.ROOT).contains(WIKI_MENTION + accountId.toLowerCase(Locale.ROOT) + "]");
        }
        return any(node, candidate -> "mention".equals(type(candidate))
                && accountId.equalsIgnoreCase(attribute(candidate, "id")));
    }

    /** The account ids an ADF document mentions ({@code mention} nodes), lower case; none for another body. */
    public static java.util.Set<String> mentionIds(String body) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        JsonNode node = parse(body);
        if (node != null && isDocument(node)) {
            any(node, candidate -> {
                String id = "mention".equals(type(candidate)) ? attribute(candidate, "id") : null;
                if (id != null && !id.isBlank()) {
                    ids.add(id.toLowerCase(Locale.ROOT));
                }
                return false;
            });
        }
        return ids;
    }

    private static boolean isDocument(JsonNode node) {
        return node.isObject() && "doc".equals(type(node));
    }

    /** The lines of the blocks, each block followed by a blank line. */
    private static List<String> blocks(@Nullable JsonNode content) {
        List<String> out = new ArrayList<>();
        if (content == null || !content.isArray()) {
            return out;
        }
        for (JsonNode node : content) {
            List<String> lines = block(node);
            if (lines.isEmpty()) {
                continue;
            }
            out.addAll(lines);
            out.add("");
        }
        while (!out.isEmpty() && out.getLast().isEmpty()) {
            out.removeLast();
        }
        return out;
    }

    /** One block as Markdown lines. Unknown nodes give their children, or their text. */
    private static List<String> block(JsonNode node) {
        String type = type(node);
        List<String> out = new ArrayList<>();
        switch (type) {
            case "paragraph" -> out.addAll(lines(inline(node.get("content"))));
            case "heading" -> {
                int level = Math.clamp(integer(node.path("attrs"), "level", 1), 1, 6);
                out.add("#".repeat(level) + " " + inline(node.get("content")).replace('\n', ' '));
            }
            case "bulletList", "orderedList", "taskList", "decisionList" -> out.addAll(list(node));
            case "codeBlock" -> {
                String code = inline(node.get("content"));
                String fence = code.contains("```") ? "~~~~" : "```";
                out.add(fence + safeLanguage(attribute(node, "language")));
                out.addAll(lines(code));
                out.add(fence);
            }
            case "blockquote", "panel" -> blocks(node.get("content")).forEach(line -> out.add(line.isEmpty() ? ">"
                    : "> " + line));
            case "rule" -> out.add("---");
            case "table" -> out.addAll(table(node));
            case "expand", "nestedExpand" -> {
                String title = attribute(node, "title");
                if (title != null && !title.isBlank()) {
                    out.add("**" + title.strip() + "**");
                    out.add("");
                }
                out.addAll(blocks(node.get("content")));
            }
            case "mediaSingle", "mediaGroup" -> {
                JsonNode media = node.get("content");
                if (media != null && media.isArray()) {
                    media.forEach(child -> out.add(mediaName(child)));
                }
            }
            case "media", "mediaInline" -> out.add(mediaName(node));
            case "blockCard", "embedCard" -> {
                String url = attribute(node, "url");
                out.add(url != null ? "<" + url + ">" : "[card]");
            }
            case "extension", "bodiedExtension", "multiBodiedExtension" -> {
                out.addAll(blocks(node.get("content")));
                if (out.isEmpty()) {
                    out.add("[extension]");
                }
            }
            default -> {
                JsonNode content = node.get("content");
                if (content != null && content.isArray() && !content.isEmpty()) {
                    out.addAll(content.get(0).has("content") || isBlockType(type(content.get(0)))
                            ? blocks(content) : lines(inline(content)));
                }
                else {
                    JsonNode text = node.get("text");
                    if (text != null && text.isString()) {
                        out.addAll(lines(text.stringValue()));
                    }
                }
            }
        }
        return out;
    }

    private static boolean isBlockType(String type) {
        return switch (type) {
            case "paragraph", "heading", "bulletList", "orderedList", "taskList", "decisionList", "codeBlock",
                    "blockquote", "panel", "rule", "table", "expand", "nestedExpand", "mediaSingle", "mediaGroup",
                    "blockCard", "embedCard", "layoutSection", "layoutColumn" -> true;
            default -> false;
        };
    }

    /** {@code [attachment: alt]}, or {@code [attachment]} without a name. */
    private static String mediaName(JsonNode media) {
        String alt = attribute(media, "alt");
        return alt != null && !alt.isBlank() ? "[attachment: " + alt.strip() + "]" : "[attachment]";
    }

    /** A list: each item with its marker, nested blocks indented by the marker's width. */
    private static List<String> list(JsonNode list) {
        List<String> out = new ArrayList<>();
        JsonNode items = list.get("content");
        if (items == null || !items.isArray()) {
            return out;
        }
        String type = type(list);
        boolean inlineItems = "taskList".equals(type) || "decisionList".equals(type);
        for (JsonNode item : items) {
            String itemType = type(item);
            if (inlineItems && ("taskList".equals(itemType) || "decisionList".equals(itemType))) {
                // A nested task list is a child of the list itself, not of an item.
                list(item).forEach(line -> out.add("  " + line));
                continue;
            }
            String marker = switch (type) {
                case "orderedList" -> "1. ";
                case "taskList" -> "DONE".equals(attribute(item, "state")) ? "- [x] " : "- [ ] ";
                default -> "- ";
            };
            // Task and decision items hold inline content; list items hold blocks.
            List<String> lines = inlineItems ? lines(inline(item.get("content"))) : itemBlocks(item.get("content"));
            if (lines.isEmpty()) {
                out.add(marker.strip());
                continue;
            }
            String indent = " ".repeat(marker.length());
            out.add(marker + lines.getFirst());
            for (int i = 1; i < lines.size(); i++) {
                out.add(lines.get(i).isEmpty() ? "" : indent + lines.get(i));
            }
        }
        return out;
    }

    /** The blocks of a list item: as {@link #blocks}, but a nested list follows its paragraph without a blank line. */
    private static List<String> itemBlocks(@Nullable JsonNode content) {
        List<String> out = new ArrayList<>();
        if (content == null || !content.isArray()) {
            return out;
        }
        boolean afterList = false;
        for (JsonNode node : content) {
            List<String> lines = block(node);
            if (lines.isEmpty()) {
                continue;
            }
            boolean list = switch (type(node)) {
                case "bulletList", "orderedList", "taskList", "decisionList" -> true;
                default -> false;
            };
            if (!out.isEmpty() && !list && !afterList) {
                out.add("");
            }
            out.addAll(lines);
            afterList = list;
        }
        return out;
    }

    /** A table with its first row as the header; a cell's blocks are joined by a space, {@code |} escaped. */
    private static List<String> table(JsonNode table) {
        List<String> out = new ArrayList<>();
        JsonNode rows = table.get("content");
        if (rows == null || !rows.isArray()) {
            return out;
        }
        boolean first = true;
        for (JsonNode row : rows) {
            JsonNode cells = row.get("content");
            if (cells == null || !cells.isArray()) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            for (JsonNode cell : cells) {
                String text = String.join(" ", blocks(cell.get("content")).stream().filter(line -> !line.isEmpty())
                    .toList());
                texts.add(text.replace("|", "\\|"));
            }
            out.add("| " + String.join(" | ", texts) + " |");
            if (first) {
                out.add("|" + " --- |".repeat(texts.size()));
                first = false;
            }
        }
        return out;
    }

    /** The inline content as Markdown; hard breaks are newlines. */
    private static String inline(@Nullable JsonNode content) {
        if (content == null || !content.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        List<JsonNode> nodes = merged(content);
        for (JsonNode node : nodes) {
            switch (type(node)) {
                case "text" -> out.append(marked(text(node), node.get("marks")));
                case "hardBreak" -> out.append('\n');
                case "mention" -> {
                    String text = attribute(node, "text");
                    String id = attribute(node, "id");
                    out.append(text != null && !text.isBlank() ? (text.startsWith("@") ? text : "@" + text)
                            : "@" + Objects.requireNonNullElse(id, "someone"));
                }
                case "emoji" -> {
                    String text = attribute(node, "text");
                    out.append(text != null && !text.isBlank() ? text
                            : Objects.requireNonNullElse(attribute(node, "shortName"), ""));
                }
                case "inlineCard" -> {
                    String url = attribute(node, "url");
                    out.append(url != null ? "<" + url + ">" : "[card]");
                }
                case "date" -> out.append(date(attribute(node, "timestamp")));
                case "status" -> out.append("**").append(Objects.requireNonNullElse(attribute(node, "text"), ""))
                    .append("**");
                case "inlineExtension" -> out.append("[extension]");
                default -> {
                    JsonNode children = node.get("content");
                    if (children != null && children.isArray()) {
                        out.append(inline(children));
                    }
                    else {
                        out.append(text(node));
                    }
                }
            }
        }
        return out.toString();
    }

    /** Adjacent text nodes with the same marks as one, so a bold run is not opened and closed in the middle. */
    private static List<JsonNode> merged(JsonNode content) {
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode node : content) {
            if ("text".equals(type(node)) && !nodes.isEmpty() && "text".equals(type(nodes.getLast()))
                    && Objects.equals(marks(nodes.getLast()), marks(node))) {
                ObjectNode joined = (ObjectNode) nodes.getLast().deepCopy();
                joined.put("text", text(nodes.getLast()) + text(node));
                nodes.set(nodes.size() - 1, joined);
            }
            else {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private static @Nullable JsonNode marks(JsonNode node) {
        JsonNode marks = node.get("marks");
        return marks != null && marks.isArray() && !marks.isEmpty() ? marks : null;
    }

    /** The text with its marks as Markdown: code wins; else emphasis inside a link. */
    private static String marked(String text, @Nullable JsonNode marks) {
        if (text.isEmpty() || marks == null || !marks.isArray()) {
            return text;
        }
        String href = null;
        boolean code = false;
        boolean strong = false;
        boolean em = false;
        boolean strike = false;
        for (JsonNode mark : marks) {
            switch (type(mark)) {
                case "code" -> code = true;
                case "strong" -> strong = true;
                case "em" -> em = true;
                case "strike" -> strike = true;
                case "link" -> href = attribute(mark, "href");
                default -> {
                }
            }
        }
        String result = code ? codeSpan(text) : text;
        if (!code) {
            if (em) {
                result = wrap(result, "*");
            }
            if (strong) {
                result = wrap(result, "**");
            }
            if (strike) {
                result = wrap(result, "~~");
            }
        }
        if (href != null && !href.isBlank()) {
            result = result.equals(href) ? "<" + href + ">" : "[" + result + "](" + href + ")";
        }
        return result;
    }

    /** The marker around the text, outside its leading and trailing whitespace. */
    private static String wrap(String text, String marker) {
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start == end) {
            return text;
        }
        return text.substring(0, start) + marker + text.substring(start, end) + marker + text.substring(end);
    }

    /**
     * A Markdown code span that holds {@code text}: its fence is one backtick longer than the longest run of
     * backticks in the text, so no run inside can close it, and a space keeps a backtick at either end apart from it.
     */
    static String codeSpan(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);
        String padding = text.startsWith("`") || text.endsWith("`") ? " " : "";
        return fence + padding + text + padding + fence;
    }

    /** A {@code date} node's timestamp (milliseconds) as an ISO date; the text as it is when it is not one. */
    private static String date(@Nullable String timestamp) {
        if (timestamp == null) {
            return "";
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(timestamp.strip())).atOffset(ZoneOffset.UTC).toLocalDate()
                .toString();
        }
        catch (NumberFormatException ex) {
            return timestamp;
        }
    }

    // ---- Writing: Markdown to ADF ----------------------------------------------------------------------------------

    /** Markdown as an ADF document, as JSON text; null and blank give an empty document. */
    public static String fromMarkdown(@Nullable String markdown) {
        return document(markdown == null || markdown.isBlank() ? List.of() : blocksOf(lines(markdown))).toString();
    }

    /**
     * The document a body is: the ADF it holds, or a document with the text as one paragraph when it is not ADF (a
     * plain text keeps its lines as hard breaks).
     */
    public static ObjectNode documentOf(String body) {
        JsonNode node = parse(body);
        if (node != null && isDocument(node)) {
            return (ObjectNode) node;
        }
        if (body.isBlank()) {
            return document(List.of());
        }
        return document(List.of(paragraph(lines(body.strip()))));
    }

    /**
     * A bold label in front of a document, e.g. {@code Tinfirm:} before a note: on the first line of a leading
     * paragraph, else as a paragraph of its own before the first block.
     */
    public static String withLabel(String label, String body) {
        ObjectNode document = documentOf(body);
        ArrayNode content = (ArrayNode) document.get("content");
        ObjectNode strong = text(label, List.of(mark("strong")));
        if (!content.isEmpty() && "paragraph".equals(type(content.get(0)))) {
            ObjectNode first = (ObjectNode) content.get(0);
            ArrayNode inline = first.has("content") && first.get("content").isArray()
                    ? (ArrayNode) first.get("content") : first.putArray("content");
            inline.insert(0, text(" ", List.of()));
            inline.insert(0, strong);
        }
        else {
            ObjectNode paragraph = MAPPER.createObjectNode().put("type", "paragraph");
            paragraph.putArray("content").add(strong);
            content.insert(0, paragraph);
        }
        return document.toString();
    }

    static ObjectNode document(List<ObjectNode> content) {
        ObjectNode document = MAPPER.createObjectNode();
        document.put("version", 1);
        document.put("type", "doc");
        ArrayNode blocks = document.putArray("content");
        content.forEach(blocks::add);
        return document;
    }

    /** The blocks of Markdown lines. */
    private static List<ObjectNode> blocksOf(List<String> lines) {
        List<ObjectNode> out = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher fence = MD_FENCE.matcher(line);
            if (fence.matches()) {
                flush(paragraph, out);
                i = fencedBlock(lines, i, fence, out);
                continue;
            }
            Matcher item = MD_LIST.matcher(line);
            if (item.matches() && !MD_RULE.matcher(line).matches()) {
                flush(paragraph, out);
                i = listRun(lines, i, out);
                continue;
            }
            if (MD_QUOTE.matcher(line).matches()) {
                flush(paragraph, out);
                i = quote(lines, i, out);
                continue;
            }
            if (i + 1 < lines.size() && line.contains("|") && MD_TABLE_SEPARATOR.matcher(lines.get(i + 1)).matches()) {
                flush(paragraph, out);
                i = table(lines, i, out);
                continue;
            }
            i++;
            if (line.isBlank()) {
                flush(paragraph, out);
                continue;
            }
            Matcher heading = MD_HEADING.matcher(line);
            if (heading.matches()) {
                flush(paragraph, out);
                ObjectNode node = MAPPER.createObjectNode().put("type", "heading");
                node.putObject("attrs").put("level", heading.group(1).length());
                node.set("content", inlineNodes(heading.group(2), List.of()));
                out.add(node);
            }
            else if (MD_RULE.matcher(line).matches()) {
                flush(paragraph, out);
                out.add(MAPPER.createObjectNode().put("type", "rule"));
            }
            else {
                paragraph.add(line);
            }
        }
        flush(paragraph, out);
        return out;
    }

    private static void flush(List<String> paragraph, List<ObjectNode> out) {
        if (!paragraph.isEmpty()) {
            out.add(paragraph(List.copyOf(paragraph)));
            paragraph.clear();
        }
    }

    /** A paragraph whose lines are separated by hard breaks. */
    private static ObjectNode paragraph(List<String> lines) {
        ObjectNode node = MAPPER.createObjectNode().put("type", "paragraph");
        ArrayNode content = node.putArray("content");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                content.add(MAPPER.createObjectNode().put("type", "hardBreak"));
            }
            inlineNodes(lines.get(i).strip(), List.of()).forEach(content::add);
        }
        return node;
    }

    /** A fenced block as a {@code codeBlock}, with its language when it names one; unclosed, it runs to the end. */
    private static int fencedBlock(List<String> lines, int start, Matcher fence, List<ObjectNode> out) {
        String marker = fence.group(1);
        String language = safeLanguage(fence.group(2));
        List<String> content = new ArrayList<>();
        int i = start + 1;
        while (i < lines.size()) {
            String line = lines.get(i++);
            if (line.strip().startsWith(marker) && line.strip().chars().allMatch(c -> c == marker.charAt(0))) {
                break;
            }
            content.add(line);
        }
        ObjectNode node = MAPPER.createObjectNode().put("type", "codeBlock");
        if (!language.isEmpty()) {
            node.putObject("attrs").put("language", language);
        }
        ArrayNode text = node.putArray("content");
        String code = String.join("\n", content);
        if (!code.isEmpty()) {
            text.add(text(code, List.of()));
        }
        out.add(node);
        return i;
    }

    /** Consecutive quoted lines as one {@code blockquote}; headings inside become bold paragraphs. */
    private static int quote(List<String> lines, int start, List<ObjectNode> out) {
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
        ObjectNode node = MAPPER.createObjectNode().put("type", "blockquote");
        ArrayNode content = node.putArray("content");
        for (ObjectNode block : blocksOf(quoted)) {
            content.add(switch (type(block)) {
                case "heading" -> {
                    ObjectNode paragraph = MAPPER.createObjectNode().put("type", "paragraph");
                    ArrayNode inline = paragraph.putArray("content");
                    block.get("content").forEach(text -> inline.add(withMark(text, mark("strong"))));
                    yield paragraph;
                }
                case "rule", "table" -> {
                    ObjectNode paragraph = MAPPER.createObjectNode().put("type", "paragraph");
                    paragraph.putArray("content").add(text("rule".equals(type(block)) ? "---"
                            : String.join("\n", table(block)), List.of()));
                    yield paragraph;
                }
                default -> block;
            });
        }
        out.add(node);
        return i;
    }

    private static JsonNode withMark(JsonNode text, ObjectNode mark) {
        if (!"text".equals(type(text))) {
            return text;
        }
        ObjectNode copy = (ObjectNode) text.deepCopy();
        ArrayNode marks = copy.has("marks") && copy.get("marks").isArray() ? (ArrayNode) copy.get("marks")
                : copy.putArray("marks");
        marks.add(mark);
        return copy;
    }

    /** A table with a header row, then a row per line until a blank line. */
    private static int table(List<String> lines, int start, List<ObjectNode> out) {
        ObjectNode table = MAPPER.createObjectNode().put("type", "table");
        table.putObject("attrs").put("isNumberColumnEnabled", false).put("layout", "default");
        ArrayNode rows = table.putArray("content");
        rows.add(row(lines.get(start), "tableHeader"));
        int i = start + 2;
        while (i < lines.size() && lines.get(i).contains("|") && !lines.get(i).isBlank()) {
            rows.add(row(lines.get(i), "tableCell"));
            i++;
        }
        out.add(table);
        return i;
    }

    private static ObjectNode row(String line, String cellType) {
        ObjectNode row = MAPPER.createObjectNode().put("type", "tableRow");
        ArrayNode cells = row.putArray("content");
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        for (String cell : trimmed.split("(?<!\\\\)\\|", -1)) {
            ObjectNode node = MAPPER.createObjectNode().put("type", cellType);
            node.putObject("attrs");
            ObjectNode paragraph = MAPPER.createObjectNode().put("type", "paragraph");
            paragraph.set("content", inlineNodes(cell.strip().replace("\\|", "|"), List.of()));
            node.putArray("content").add(paragraph);
            cells.add(node);
        }
        return row;
    }

    /** Consecutive list lines as nested lists; a run whose every item is a task becomes a task list. */
    private static int listRun(List<String> lines, int start, List<ObjectNode> out) {
        List<Item> items = new ArrayList<>();
        int i = start;
        while (i < lines.size()) {
            Matcher matcher = MD_LIST.matcher(lines.get(i));
            if (!matcher.matches() || MD_RULE.matcher(lines.get(i)).matches()) {
                break;
            }
            int indent = width(matcher.group(1));
            boolean ordered = Character.isDigit(matcher.group(2).charAt(0));
            String text = matcher.group(3);
            Matcher task = MD_TASK.matcher(text);
            if (!ordered && task.matches()) {
                items.add(new Item(indent, ListKind.TASK, !task.group(1).isBlank(), task.group(2)));
            }
            else {
                items.add(new Item(indent, ordered ? ListKind.ORDERED : ListKind.BULLET, false, text));
            }
            i++;
        }
        boolean tasks = items.stream().allMatch(item -> item.kind() == ListKind.TASK);
        List<Level> stack = new ArrayList<>();
        for (Item item : items) {
            ListKind kind = tasks ? ListKind.TASK : item.kind() == ListKind.TASK ? ListKind.BULLET : item.kind();
            String text = tasks || item.kind() != ListKind.TASK ? item.text()
                    : "[" + (item.done() ? "x" : " ") + "] " + item.text();
            while (!stack.isEmpty() && stack.getLast().indent() > item.indent()) {
                stack.removeLast();
            }
            Level top = stack.isEmpty() ? null : stack.getLast();
            if (top != null && top.indent() == item.indent() && top.kind() != kind) {
                // Another kind of item at the same depth starts a sibling list under the same parent.
                stack.removeLast();
                top = stack.isEmpty() ? null : stack.getLast();
            }
            if (top == null || top.indent() < item.indent()) {
                ObjectNode list = newList(kind);
                attach(top, list, out);
                stack.add(new Level(item.indent(), kind, list, null));
            }
            Level level = stack.getLast();
            ObjectNode node = kind == ListKind.TASK ? taskItem(item.done(), text) : listItem(text);
            ((ArrayNode) level.list().get("content")).add(node);
            stack.set(stack.size() - 1, new Level(level.indent(), level.kind(), level.list(), node));
        }
        return i;
    }

    /** A nested list goes into the parent's last item (a task list into the parent list), else to the blocks. */
    private static void attach(@Nullable Level parent, ObjectNode list, List<ObjectNode> out) {
        if (parent == null) {
            out.add(list);
        }
        else if (parent.kind() == ListKind.TASK || parent.lastItem() == null) {
            ((ArrayNode) parent.list().get("content")).add(list);
        }
        else {
            ((ArrayNode) parent.lastItem().get("content")).add(list);
        }
    }

    private static ObjectNode newList(ListKind kind) {
        ObjectNode list = MAPPER.createObjectNode();
        switch (kind) {
            case ORDERED -> {
                list.put("type", "orderedList");
                list.putObject("attrs").put("order", 1);
            }
            case TASK -> {
                list.put("type", "taskList");
                list.putObject("attrs").put("localId", UUID.randomUUID().toString());
            }
            default -> list.put("type", "bulletList");
        }
        list.putArray("content");
        return list;
    }

    private static ObjectNode listItem(String text) {
        ObjectNode item = MAPPER.createObjectNode().put("type", "listItem");
        ObjectNode paragraph = MAPPER.createObjectNode().put("type", "paragraph");
        paragraph.set("content", inlineNodes(text.strip(), List.of()));
        item.putArray("content").add(paragraph);
        return item;
    }

    private static ObjectNode taskItem(boolean done, String text) {
        ObjectNode item = MAPPER.createObjectNode().put("type", "taskItem");
        item.putObject("attrs").put("localId", UUID.randomUUID().toString()).put("state", done ? "DONE" : "TODO");
        item.set("content", inlineNodes(text.strip(), List.of()));
        return item;
    }

    /**
     * The inline nodes of a Markdown text: code spans, images (as links), links, autolinks and bare URLs, bold,
     * italic and strikethrough, nested; a backslash escapes the next Markdown character.
     */
    private static ArrayNode inlineNodes(String text, List<ObjectNode> marks) {
        ArrayNode out = MAPPER.createArrayNode();
        inline(text, marks, out);
        return out;
    }

    private static void inline(String text, List<ObjectNode> marks, ArrayNode out) {
        StringBuilder plain = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            Matcher m;
            if (c == '`' && (m = at(MD_CODE, text, i)) != null) {
                flush(plain, marks, out);
                out.add(text(m.group(2).strip(), with(marks.stream().filter(mark -> "link".equals(type(mark)))
                    .toList(), mark("code"))));
                i = m.end();
            }
            else if (c == '!' && (m = at(MD_IMAGE, text, i)) != null) {
                flush(plain, marks, out);
                String url = m.group(2);
                out.add(text(m.group(1).isBlank() ? url : m.group(1), with(marks, link(url))));
                i = m.end();
            }
            else if (c == '[' && (m = at(MD_LINK, text, i)) != null) {
                flush(plain, marks, out);
                inline(m.group(1), with(marks, link(m.group(2))), out);
                i = m.end();
            }
            else if (c == '<' && (m = at(MD_AUTOLINK, text, i)) != null) {
                flush(plain, marks, out);
                out.add(text(m.group(1), with(marks, link(m.group(1)))));
                i = m.end();
            }
            else if ((c == 'h' || c == 'm') && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))
                    && (m = at(URL, text, i)) != null) {
                flush(plain, marks, out);
                out.add(text(m.group(), with(marks, link(m.group()))));
                i = m.end();
            }
            else if (c == '*' && (m = at(MD_BOLD_ITALIC, text, i)) != null) {
                flush(plain, marks, out);
                inline(m.group(1), with(with(marks, mark("strong")), mark("em")), out);
                i = m.end();
            }
            else if (c == '*' && (m = at(MD_BOLD, text, i)) != null) {
                flush(plain, marks, out);
                inline(m.group(1), with(marks, mark("strong")), out);
                i = m.end();
            }
            else if (c == '_' && (m = at(MD_BOLD_UNDERSCORE, text, i)) != null) {
                flush(plain, marks, out);
                inline(m.group(1), with(marks, mark("strong")), out);
                i = m.end();
            }
            else if (c == '*' && (m = at(MD_ITALIC, text, i)) != null) {
                flush(plain, marks, out);
                inline(m.group(1), with(marks, mark("em")), out);
                i = m.end();
            }
            else if (c == '_' && (m = at(MD_ITALIC_UNDERSCORE, text, i)) != null) {
                flush(plain, marks, out);
                inline(m.group(1), with(marks, mark("em")), out);
                i = m.end();
            }
            else if (c == '~' && (m = at(MD_STRIKE, text, i)) != null) {
                flush(plain, marks, out);
                inline(m.group(1), with(marks, mark("strike")), out);
                i = m.end();
            }
            else if (c == '\\' && i + 1 < n && ESCAPABLE.indexOf(text.charAt(i + 1)) >= 0) {
                plain.append(text.charAt(i + 1));
                i += 2;
            }
            else {
                plain.append(c);
                i++;
            }
        }
        flush(plain, marks, out);
    }

    private static void flush(StringBuilder plain, List<ObjectNode> marks, ArrayNode out) {
        if (!plain.isEmpty()) {
            out.add(text(plain.toString(), marks));
            plain.setLength(0);
        }
    }

    /** The pattern matched at the position, looking behind it where the pattern asks; null when it does not match. */
    private static @Nullable Matcher at(Pattern pattern, String text, int position) {
        Matcher matcher = pattern.matcher(text);
        matcher.region(position, text.length());
        matcher.useTransparentBounds(true);
        matcher.useAnchoringBounds(false);
        return matcher.lookingAt() ? matcher : null;
    }

    /** The marks with another, each type once. */
    private static List<ObjectNode> with(List<ObjectNode> marks, ObjectNode mark) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode existing : marks) {
            if (!type(existing).equals(type(mark))) {
                result.add(existing);
            }
        }
        result.add(mark);
        return List.copyOf(result);
    }

    private static ObjectNode mark(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }

    private static ObjectNode link(String href) {
        ObjectNode mark = mark("link");
        mark.putObject("attrs").put("href", href);
        return mark;
    }

    private static ObjectNode text(String text, List<ObjectNode> marks) {
        ObjectNode node = MAPPER.createObjectNode().put("type", "text").put("text", text);
        if (!marks.isEmpty()) {
            ArrayNode array = node.putArray("marks");
            marks.forEach(array::add);
        }
        return node;
    }

    // ---- Shared ----------------------------------------------------------------------------------------------------

    static @Nullable JsonNode parse(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("\"")) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    private static boolean any(JsonNode node, Predicate<JsonNode> test) {
        if (test.test(node)) {
            return true;
        }
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode child : content) {
                if (any(child, test)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String type(@Nullable JsonNode node) {
        String type = node != null ? JiraJson.text(node, "type") : null;
        return type != null ? type : "";
    }

    private static String text(JsonNode node) {
        String text = JiraJson.text(node, "text");
        return text != null ? text : "";
    }

    private static @Nullable String attribute(JsonNode node, String name) {
        JsonNode attrs = node.get("attrs");
        if (attrs == null || !attrs.isObject()) {
            return null;
        }
        JsonNode value = attrs.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isString() ? value.stringValue() : value.asString();
    }

    private static int integer(JsonNode node, String field, int fallback) {
        return JiraJson.integer(node, field, fallback);
    }

    private static String safeLanguage(@Nullable String language) {
        return language != null && language.matches("[\\w+#.-]{1,30}") ? language.toLowerCase(Locale.ROOT) : "";
    }

    private static List<String> lines(String text) {
        return new ArrayList<>(List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
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
