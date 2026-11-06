package com.flowtivo.tinfirm.run.internal;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The names a run works with:
 * <ul>
 * <li>the branch of a conversation, {@code agent/{key}} (the package 3 contract, §5.7): the key lower-cased, every run
 * of characters other than {@code a-z0-9._-} one hyphen, hyphens and dots trimmed from both ends, the whole at most
 * 100 characters, so that two conversations on one subject never share a branch;</li>
 * <li>the directory of a repository under {@code /workspace}: NFKD without combining marks, lowercase, each run of
 * characters other than {@code a-z0-9} one hyphen, trimmed, cut to 40 characters and trimmed again, {@code repo} when
 * empty, {@code -2}, {@code -3}... when another repository of the run has it.</li>
 * </ul>
 */
final class Branches {

    static final String PREFIX = "agent/";

    static final int MAX_BRANCH_LENGTH = 100;

    private static final int MAX_DIR_BASE_LENGTH = 40;

    private static final String EMPTY_DIR = "repo";

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private static final Pattern NOT_DIR = Pattern.compile("[^a-z0-9]+");

    private static final Pattern NOT_BRANCH = Pattern.compile("[^a-z0-9._-]+");

    private Branches() {
    }

    /** The branch of the conversation with this key. */
    static String of(String key) {
        String sanitized = NOT_BRANCH.matcher(key.strip().toLowerCase(Locale.ROOT)).replaceAll("-");
        sanitized = sanitized.replace("..", ".");
        int max = MAX_BRANCH_LENGTH - PREFIX.length();
        if (sanitized.length() > max) {
            sanitized = sanitized.substring(0, max);
        }
        sanitized = trim(sanitized, "-.");
        return PREFIX + (sanitized.isEmpty() ? "work" : sanitized);
    }

    /** The directory for a repository {@code name}, without the collision suffix. */
    static String dir(String name) {
        String plain = COMBINING_MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFKD)).replaceAll("");
        String dir = trim(NOT_DIR.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll("-"), "-");
        if (dir.length() > MAX_DIR_BASE_LENGTH) {
            dir = trim(dir.substring(0, MAX_DIR_BASE_LENGTH), "-");
        }
        return dir.isEmpty() ? EMPTY_DIR : dir;
    }

    /** The directory for {@code name} in a run whose other repositories have the directories {@code taken}. */
    static String uniqueDir(String name, Collection<String> taken) {
        String base = dir(name);
        if (!taken.contains(base)) {
            return base;
        }
        for (int suffix = 2;; suffix++) {
            String candidate = base + "-" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static String trim(String value, String characters) {
        int start = 0;
        int end = value.length();
        while (start < end && characters.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && characters.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }
}
