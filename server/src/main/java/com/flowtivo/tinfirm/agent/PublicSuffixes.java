package com.flowtivo.tinfirm.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The Public Suffix List bundled with the server ({@code psl/public_suffix_list.dat}, its private section of shared
 * hosting such as {@code github.io} included), parsed once: whether a domain is a suffix under which unrelated parties
 * register names, which no wildcard network host may cover ({@link NetworkHosts}).
 */
final class PublicSuffixes {

    private static final String RESOURCE = "/psl/public_suffix_list.dat";

    private final Set<String> rules = new HashSet<>();

    /** The parents of {@code *.parent} rules: every name directly under one is a suffix. */
    private final Set<String> wildcardParents = new HashSet<>();

    /** {@code !name} rules: names that are not suffixes although a wildcard rule would make them one. */
    private final Set<String> exceptions = new HashSet<>();

    /** The list as the file holds it (UTF-8), for the runner hosts ({@link PublicSuffixList}). */
    private final byte[] bytes;

    /** The rules of the file, each counted once as written. */
    private final int ruleCount;

    private PublicSuffixes(byte[] bytes) {
        this.bytes = bytes.clone();
        int count = 0;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            String rule = line.strip();
            if (rule.isEmpty() || rule.startsWith("//")) {
                continue;
            }
            count++;
            rule = rule.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            try {
                if (rule.startsWith("!")) {
                    exceptions.add(IDN.toASCII(rule.substring(1)));
                }
                else if (rule.startsWith("*.")) {
                    wildcardParents.add(IDN.toASCII(rule.substring(2)));
                }
                else {
                    rules.add(IDN.toASCII(rule));
                }
            }
            catch (IllegalArgumentException ex) {
                // A name without an ASCII form cannot be a network host either.
            }
        }
        this.ruleCount = count;
    }

    static PublicSuffixes bundled() {
        return Bundled.INSTANCE;
    }

    private static final class Bundled {

        static final PublicSuffixes INSTANCE = load();

        private static PublicSuffixes load() {
            try (InputStream in = PublicSuffixes.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("The resource " + RESOURCE + " is missing");
                }
                return new PublicSuffixes(in.readAllBytes());
            }
            catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /** The list's bytes as the file holds them. */
    byte[] bytes() {
        return bytes.clone();
    }

    int ruleCount() {
        return ruleCount;
    }

    /** Whether {@code domain} is the parent of a {@code *.domain} rule: every name directly under it is a suffix. */
    boolean isWildcardParent(String domain) {
        return wildcardParents.contains(domain);
    }

    /** Whether {@code domain} (lower case, ASCII, no trailing dot) is a public suffix; a top-level domain always is. */
    boolean isPublicSuffix(String domain) {
        int dot = domain.indexOf('.');
        if (dot < 0) {
            return true;
        }
        if (exceptions.contains(domain)) {
            return false;
        }
        return rules.contains(domain) || wildcardParents.contains(domain.substring(dot + 1));
    }
}
