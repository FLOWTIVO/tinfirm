package com.flowtivo.tinfirm.diagnostics.internal;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Masks what may be the company's data in a message before it is stored (ADR-0070, §2): URLs, emails, UUIDs, quoted
 * strings (from a line's first quote to its last, so that a value holding an apostrophe is masked whole), IP
 * addresses, file paths, hexadecimal values, tokens of letters and digits, keys behind a short prefix, and numbers
 * of 4 digits or more. Then
 * whitespace is folded and the message cut to {@value #MAX_MESSAGE} characters. The masks run in this order, so
 * that a URL's path or an email's host is masked as the URL or the email.
 */
public final class Scrubber {

    static final int MAX_MESSAGE = 300;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t\\n\\r]]");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_+/=-]{16,}");

    /**
     * A key's shape: a short prefix of up to three parts ({@code sk-}, {@code sk-proj-}, {@code github_pat_}), then 16
     * or more letters and digits in one part, perhaps padded with {@code =}. Found within a run, after an assignment
     * ({@code apiKey=sk-…}) too, but never from or to the middle of a word.
     */
    private static final Pattern PREFIXED_KEY = Pattern.compile("(?<![A-Za-z0-9_-])"
            + "[A-Za-z][A-Za-z0-9]{0,9}(?:[_-][A-Za-z0-9]{1,10}){0,2}[_-][A-Za-z0-9]{16,}=*(?![A-Za-z0-9_-])");

    private static final List<Mask> MASKS = List.of(
            new Mask(Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]{0,20}://[^\\s'\"<>]+"), "<url>"),
            new Mask(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"), "<email>"),
            new Mask(Pattern.compile(
                    "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), "<uuid>"),
            // Greedy, to the last quote of the line: a quoted value may hold an apostrophe ('Bob's salary').
            new Mask(Pattern.compile("'[^\\n]*'|\"[^\\n]*\"|`[^\\n]*`|«[^\\n]*»|“[^\\n]*”|„[^\\n]*“"), "<str>"),
            new Mask(Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b"), "<ip>"),
            new Mask(Pattern.compile("(?i)(?<![\\w:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![\\w:])"), "<ip>"),
            new Mask(Pattern.compile("(?<![\\w.<>-])(?:~|\\.{1,2})?(?:[/\\\\][\\w.@%+~-]+){2,}[/\\\\]?"), "<path>"),
            new Mask(Pattern.compile("(?i)\\b0x[0-9a-f]+\\b"), "<hex>"),
            new Mask(Pattern.compile("(?i)\\b[0-9a-f]{12,}\\b"), "<hex>"));

    private static final Pattern NUMBER = Pattern.compile("\\d{4,}");

    private Scrubber() {
    }

    /** The message masked, folded and cut; null for a message that is null or blank. */
    public static @Nullable String mask(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String text = CONTROL.matcher(message).replaceAll(" ");
        for (Mask mask : MASKS) {
            text = mask.pattern().matcher(text).replaceAll(Matcher.quoteReplacement(mask.replacement()));
        }
        text = TOKEN.matcher(text).replaceAll(Scrubber::token);
        text = NUMBER.matcher(text).replaceAll("<n>");
        text = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return cut(text, MAX_MESSAGE);
    }

    /**
     * A run of 16 or more is a token (a key, an id, a hash) when it has a digit and a letter, or when it has a key's
     * shape, letters alone included ({@code sk-abcdefghijklmnopqrstuvwxyz}). Else it is code: an exception's name, a
     * snake_case constraint or a kebab-case name, whose parts are short words. A code name whose last part is a word of
     * 16 letters or more behind a short prefix ({@code fk_internationalization}) is masked as well.
     */
    private static String token(MatchResult match) {
        String run = match.group();
        boolean digits = run.chars().anyMatch(Character::isDigit);
        boolean letters = run.chars().anyMatch(Character::isLetter);
        boolean key = (digits && letters) || PREFIXED_KEY.matcher(run).find();
        return Matcher.quoteReplacement(key ? "<token>" : run);
    }

    /** At most {@code max} characters, never splitting a surrogate pair. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    /** A code-defined name (a type, a logger, a function, a file): control characters dropped, cut. */
    static @Nullable String name(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        String text = CONTROL.matcher(value).replaceAll("").strip();
        return text.isEmpty() ? null : cut(text, max);
    }

    private record Mask(Pattern pattern, String replacement) {
    }
}
