package com.flowtivo.tinfirm.ee.scim;

import java.util.Locale;

import com.flowtivo.tinfirm.company.scim.ScimException;

/**
 * The filters the endpoint serves (ADR-0087, decision 1): {@code userName eq "…"}, {@code externalId eq "…"} and
 * {@code id eq "…"}, the attribute and the operator in any case (RFC 7644 §3.4.2.2), the value a JSON string. How
 * Entra ID and Okta match people, each an index lookup. Any other filter is {@code 400 invalidFilter}.
 *
 * @param attribute {@code userName}, {@code externalId} or {@code id}
 */
record ScimFilter(String attribute, String value) {

    /** The core schema's prefix an attribute may carry. */
    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User:";

    /**
     * Reads {@code [schema:]attribute eq "value"} with a scanner, in one pass over the filter: no regular expression
     * whose nested quantifier would recurse once per character of a long value (the security review of 2026-10-27,
     * L10). The grammar allows whitespace around the filter and needs at least one between the parts.
     */
    static ScimFilter parse(String filter) {
        int end = filter.length();
        while (end > 0 && space(filter.charAt(end - 1))) {
            end--;
        }
        int i = 0;
        while (i < end && space(filter.charAt(i))) {
            i++;
        }
        if (filter.regionMatches(true, i, USER_SCHEMA, 0, USER_SCHEMA.length())) {
            i += USER_SCHEMA.length();
        }
        int start = i;
        while (i < end && !space(filter.charAt(i))) {
            i++;
        }
        String attribute = switch (filter.substring(start, i).toLowerCase(Locale.ROOT)) {
            case "username" -> "userName";
            case "externalid" -> "externalId";
            case "id" -> "id";
            default -> throw unserved();
        };
        i = spaces(filter, i, end);
        if (i + 2 > end || !filter.regionMatches(true, i, "eq", 0, 2)) {
            throw unserved();
        }
        i = spaces(filter, i + 2, end);
        if (i >= end || filter.charAt(i) != '"' || filter.charAt(end - 1) != '"' || end - 1 <= i) {
            throw unserved();
        }
        // The value: every character but an unescaped quote, a backslash escaping any but a line terminator.
        int valueStart = ++i;
        int valueEnd = end - 1;
        while (i < valueEnd) {
            char c = filter.charAt(i);
            if (c == '"') {
                throw unserved();
            }
            if (c == '\\') {
                if (i + 1 >= valueEnd || lineTerminator(filter.charAt(i + 1))) {
                    throw unserved();
                }
                i += 2;
                continue;
            }
            i++;
        }
        return new ScimFilter(attribute, unescape(filter.substring(valueStart, valueEnd)));
    }

    /** At least one whitespace from {@code i}: the index after them. */
    private static int spaces(String filter, int i, int end) {
        int start = i;
        while (i < end && space(filter.charAt(i))) {
            i++;
        }
        if (i == start) {
            throw unserved();
        }
        return i;
    }

    /** {@code \s} as Java's patterns read it without UNICODE_CHARACTER_CLASS. */
    private static boolean space(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    /** What {@code .} does not match by default. */
    private static boolean lineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    private static ScimException unserved() {
        return ScimException.invalidFilter("Only userName eq, externalId eq and id eq are served");
    }

    /** A JSON string's escapes: {@code \"}, {@code \\}, {@code \/} and {@code \}u…. */
    private static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'u' -> {
                    if (i + 4 >= text.length()) {
                        throw ScimException.invalidFilter("A broken escape in the filter's value");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                    }
                    catch (NumberFormatException ex) {
                        throw ScimException.invalidFilter("A broken escape in the filter's value");
                    }
                    i += 4;
                }
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
