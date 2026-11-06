package com.flowtivo.tinfirm.agent;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Agent handles (the package 3 contract, §3.1; ADR-0034's grammar): how people mention an agent, as {@code @handle}.
 * A handle has 1 to 50 lower-case ASCII letters, digits and hyphens, and does not start or end with a hyphen; it is
 * unique among the live agents of a company. Without one, a handle is made from the agent's name. Public so that the
 * mention parser of chats (package 5) reads handles by the same grammar.
 */
public final class Handles {

    public static final int MAX_LENGTH = 50;

    /** The handle when the name leaves nothing. */
    public static final String FALLBACK = "agent";

    public static final String RULE = "handle must be 1 to " + MAX_LENGTH
            + " lower-case letters, digits and hyphens, and must not start or end with a hyphen";

    private static final Pattern HANDLE = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,48}[a-z0-9])?$");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private static final Pattern OTHERS = Pattern.compile("[^a-z0-9]+");

    private Handles() {
    }

    /**
     * The handle made from a name: Unicode NFD with the combining marks dropped ({@code Vývojář} becomes
     * {@code vyvojar}), lower-cased, every run of other characters one hyphen, hyphens trimmed, cut to 50 characters
     * without a trailing hyphen; {@code agent} when nothing is left.
     */
    public static String fromName(String name) {
        String folded = MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFD)).replaceAll("");
        String handle = trim(OTHERS.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll("-"));
        if (handle.length() > MAX_LENGTH) {
            handle = trim(handle.substring(0, MAX_LENGTH));
        }
        return handle.isEmpty() ? FALLBACK : handle;
    }

    /**
     * The first of {@code base}, {@code base-2}, {@code base-3}… that is not taken; the base is shortened (without a
     * trailing hyphen) so that the whole stays within 50 characters.
     */
    public static String unique(String base, Collection<String> taken) {
        if (!taken.contains(base)) {
            return base;
        }
        for (int n = 2;; n++) {
            String suffix = "-" + n;
            String candidate = trim(base.substring(0, Math.min(base.length(), MAX_LENGTH - suffix.length())))
                    + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** Whether the text is a handle as stored: lower case, within the rule. */
    public static boolean valid(@Nullable String handle) {
        return handle != null && HANDLE.matcher(handle).matches();
    }

    /**
     * A handle a request sends, in lower case.
     *
     * @throws ApiException 400 {@code VALIDATION_FAILED} naming the rule when it does not follow it
     */
    public static String requested(String handle) {
        String lower = handle.strip().toLowerCase(Locale.ROOT);
        if (!valid(lower)) {
            throw ApiException.validation(RULE);
        }
        return lower;
    }

    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '-') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '-') {
            end--;
        }
        return text.substring(start, end);
    }
}
