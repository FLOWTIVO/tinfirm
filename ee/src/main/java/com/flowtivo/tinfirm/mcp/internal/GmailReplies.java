package com.flowtivo.tinfirm.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * An agent's answer as a reply in Gmail (ADR-0084, decision 7), as the Outlook pane's <b>Use as reply</b> opens
 * Outlook's reply form: a whole RFC 2822 message for {@code users.drafts.create} in the email's thread, to the email's
 * {@code Reply-To}, else its sender (or, for the person's own email, its recipients), with {@code Re:} and the email's
 * subject, {@code In-Reply-To} and {@code References}, and the answer as text and as HTML. The draft is the person's to
 * edit and send; Tinfirm never sends it.
 */
final class GmailReplies {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The headers a reply needs of the email, as Gmail has them ({@code format=metadata}). */
    record Original(@Nullable String subject, @Nullable String from, @Nullable String replyTo, @Nullable String to,
            @Nullable String messageId, @Nullable String references) {

        static Original of(JsonNode message) {
            JsonNode payload = message.path("payload");
            return new Original(header(payload, "Subject"), header(payload, "From"), header(payload, "Reply-To"),
                    header(payload, "To"), header(payload, "Message-ID"), header(payload, "References"));
        }
    }

    private GmailReplies() {
    }

    /**
     * The reply's message: the answer's Markdown as its text, and as simple HTML. Null when the email names nobody to
     * reply to.
     *
     * @param ownAddress the person's Google address: a reply to their own email goes to its recipients
     */
    static byte @Nullable [] reply(Original original, String markdown, @Nullable String ownAddress) {
        List<Address> to = addresses(original.replyTo());
        if (to.isEmpty()) {
            to = addresses(original.from());
        }
        if (ownAddress != null && to.size() == 1 && to.getFirst().address().equalsIgnoreCase(ownAddress.strip())) {
            List<Address> recipients = addresses(original.to());
            if (!recipients.isEmpty()) {
                to = recipients;
            }
        }
        if (to.isEmpty()) {
            return null;
        }
        String boundary = "tinfirm-" + HexFormat.of().formatHex(random(12));
        StringBuilder mime = new StringBuilder();
        header(mime, "To", String.join(", ", to.stream().map(Address::encoded).toList()));
        header(mime, "Subject", encodedWords(subject(original.subject())));
        String id = messageId(original.messageId());
        if (id != null) {
            header(mime, "In-Reply-To", id);
            String references = original.references() != null ? oneLine(original.references()) : "";
            header(mime, "References", (references.isEmpty() ? "" : references + " ") + id);
        }
        header(mime, "MIME-Version", "1.0");
        header(mime, "Content-Type", "multipart/alternative; boundary=\"" + boundary + "\"");
        mime.append("\r\n");
        part(mime, boundary, "text/plain", markdown.replace("\r\n", "\n").strip() + "\n");
        part(mime, boundary, "text/html", "<div>" + html(markdown) + "</div>\n");
        mime.append("--").append(boundary).append("--\r\n");
        return mime.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** {@code Re:} and the subject, once. */
    static String subject(@Nullable String subject) {
        String plain = subject != null ? oneLine(subject) : "";
        return plain.regionMatches(true, 0, "Re:", 0, 3) ? plain : ("Re: " + plain).strip();
    }

    // ---- addresses -----------------------------------------------------------------------------------------------------

    /** One address of a header: its display name, if any, and the address. */
    record Address(String name, String address) {

        /** The address as a header carries it: a name of other than ASCII as an encoded word, else quoted. */
        String encoded() {
            if (name.isEmpty()) {
                return address;
            }
            String shown = ascii(name) ? "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    : encodedWords(name);
            return shown + " <" + address + ">";
        }
    }

    private static final Pattern ANGLED = Pattern.compile("^(.*?)<([^<>]+)>\\s*$", Pattern.DOTALL);

    private static final Pattern ADDRESS = Pattern.compile("[^\\s<>()\\[\\],;:\"@]+@[^\\s<>()\\[\\],;:\"@]+");

    /** The addresses of a header such as {@code From} or {@code To}: those that are plainly addresses only. */
    static List<Address> addresses(@Nullable String header) {
        List<Address> found = new ArrayList<>();
        if (header == null) {
            return found;
        }
        for (String entry : split(oneLine(header))) {
            String name = "";
            String address = entry.strip();
            Matcher angled = ANGLED.matcher(address);
            if (angled.matches()) {
                name = angled.group(1).strip();
                address = angled.group(2).strip();
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\").strip();
                }
            }
            if (ADDRESS.matcher(address).matches()) {
                found.add(new Address(name, address));
            }
        }
        return found;
    }

    /** The entries of an address list: commas outside quotes and angle brackets. */
    private static List<String> split(String header) {
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean angled = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '\\' && quoted && i + 1 < header.length()) {
                current.append(c).append(header.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            }
            else if (!quoted && c == '<') {
                angled = true;
            }
            else if (!quoted && c == '>') {
                angled = false;
            }
            if (c == ',' && !quoted && !angled) {
                entries.add(current.toString());
                current.setLength(0);
            }
            else {
                current.append(c);
            }
        }
        entries.add(current.toString());
        return entries.stream().filter(entry -> !entry.isBlank()).toList();
    }

    private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]+@[^<>\\s]+>");

    /** The email's {@code Message-ID}, only when it is one. */
    private static @Nullable String messageId(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String id = value.strip();
        return MESSAGE_ID.matcher(id).matches() ? id : null;
    }

    // ---- MIME ----------------------------------------------------------------------------------------------------------

    private static void header(StringBuilder mime, String name, String value) {
        mime.append(name).append(": ").append(oneLine(value)).append("\r\n");
    }

    private static void part(StringBuilder mime, String boundary, String type, String text) {
        mime.append("--").append(boundary).append("\r\n")
            .append("Content-Type: ").append(type).append("; charset=UTF-8\r\n")
            .append("Content-Transfer-Encoding: base64\r\n\r\n")
            .append(Base64.getMimeEncoder(76, new byte[] { '\r', '\n' })
                .encodeToString(text.getBytes(StandardCharsets.UTF_8)))
            .append("\r\n");
    }

    /** A header value as RFC 2047 encoded words of UTF-8 when it is not printable ASCII, whole characters each. */
    static String encodedWords(String value) {
        if (ascii(value)) {
            return value;
        }
        List<String> words = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            // 45 bytes are 60 base64 characters: a word within the 75 of RFC 2047.
            if (bytes + size > 45) {
                words.add(word(chunk.toString()));
                chunk.setLength(0);
                bytes = 0;
            }
            chunk.append(character);
            bytes += size;
            i += Character.charCount(codePoint);
        }
        if (!chunk.isEmpty()) {
            words.add(word(chunk.toString()));
        }
        return String.join(" ", words);
    }

    private static String word(String text) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static boolean ascii(String value) {
        return value.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s*[\\r\\n]+\\s*", " ").strip();
    }

    // ---- the answer as HTML --------------------------------------------------------------------------------------------

    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");

    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d{1,9}[.)]\\s+(.*)$");

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*#*\\s*$");

    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]+)]\\((https?://[^\\s)]+|mailto:[^\\s)]+)\\)");

    /**
     * The answer's Markdown as an email's simple HTML: paragraphs with their line breaks, lists, headings in bold, bold,
     * italics, code and web links. Everything else is text, escaped: no HTML of the answer is ever kept.
     */
    static String html(String markdown) {
        StringBuilder html = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        String list = null;
        boolean fenced = false;
        StringBuilder code = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").strip().split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                if (fenced) {
                    html.append("<pre>").append(escape(code.toString().stripTrailing())).append("</pre>");
                    code.setLength(0);
                }
                else {
                    paragraph(html, paragraph);
                    list = close(html, list);
                }
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                code.append(line).append('\n');
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);
            Matcher heading = HEADING.matcher(line);
            if (bullet.matches() || numbered.matches()) {
                paragraph(html, paragraph);
                String kind = bullet.matches() ? "ul" : "ol";
                if (!kind.equals(list)) {
                    close(html, list);
                    html.append('<').append(kind).append('>');
                    list = kind;
                }
                html.append("<li>").append(inline(bullet.matches() ? bullet.group(1) : numbered.group(1)))
                    .append("</li>");
            }
            else if (heading.matches()) {
                paragraph(html, paragraph);
                list = close(html, list);
                html.append("<p><b>").append(inline(heading.group(1))).append("</b></p>");
            }
            else if (line.isBlank()) {
                paragraph(html, paragraph);
                list = close(html, list);
            }
            else {
                list = close(html, list);
                paragraph.add(line.strip());
            }
        }
        if (fenced) {
            html.append("<pre>").append(escape(code.toString().stripTrailing())).append("</pre>");
        }
        paragraph(html, paragraph);
        close(html, list);
        return html.toString();
    }

    private static void paragraph(StringBuilder html, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        html.append("<p>").append(String.join("<br>", lines.stream().map(GmailReplies::inline).toList()))
            .append("</p>");
        lines.clear();
    }

    private static @Nullable String close(StringBuilder html, @Nullable String list) {
        if (list != null) {
            html.append("</").append(list).append('>');
        }
        return null;
    }

    /** A line's bold, italics, code and links; the rest escaped. */
    private static String inline(String text) {
        StringBuilder html = new StringBuilder();
        Matcher link = LINK.matcher(text);
        int from = 0;
        while (link.find()) {
            html.append(emphasis(text.substring(from, link.start())));
            html.append("<a href=\"").append(escape(link.group(2)).replace("\"", "&quot;")).append("\">")
                .append(emphasis(link.group(1))).append("</a>");
            from = link.end();
        }
        return html.append(emphasis(text.substring(from))).toString();
    }

    private static String emphasis(String text) {
        StringBuilder html = new StringBuilder();
        String[] pieces = text.split("`", -1);
        for (int i = 0; i < pieces.length; i++) {
            // Odd pieces are inside backticks, when the backtick closes.
            if (i % 2 == 1 && i < pieces.length - 1) {
                html.append("<code>").append(escape(pieces[i])).append("</code>");
            }
            else {
                String piece = (i % 2 == 1 ? "`" : "") + escape(pieces[i]);
                html.append(piece.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>")
                    .replaceAll("__([^_]+)__", "<b>$1</b>")
                    .replaceAll("(?<![*\\w])\\*([^*\\s][^*]*?)\\*(?![*\\w])", "<i>$1</i>")
                    .replaceAll("(?<![_\\w])_([^_\\s][^_]*?)_(?![_\\w])", "<i>$1</i>"));
            }
        }
        return html.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static @Nullable String header(JsonNode payload, String name) {
        for (JsonNode header : payload.path("headers")) {
            if (name.equalsIgnoreCase(header.path("name").asString(""))) {
                return header.path("value").asString("");
            }
        }
        return null;
    }
}
