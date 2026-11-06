package com.flowtivo.tinfirm.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The grammar of a network host an agent's runs may reach (the package 3 contract, §3.5; ADR-0044), for the agent's
 * hosts and, in the run module, a {@code request} of a host: {@code HOST[:PORT]}, where HOST is a DNS name of letters,
 * digits and hyphens, or {@code *.example.com}: a wildcard only as the leading label and with at least two labels after
 * it, never over a public suffix or shared hosting ({@code *.co.uk}, {@code *.github.io}: every tenant there would be
 * reachable). PORT is 1 to 65535; the default 443 stays implicit. No scheme, path, user name or IP literal. The
 * normalized form is lower case, without a trailing dot and without {@code :443}: what is stored, compared and sent in
 * {@code run.assign.allowedHosts}. Public suffixes are those of the Public Suffix List bundled with the server.
 */
public final class NetworkHosts {

    public static final int MAX_HOST_LENGTH = 253;

    private static final int DEFAULT_PORT = 443;

    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private static final Pattern HEX_LABEL = Pattern.compile("0x[0-9a-f]*");

    /**
     * Shared-hosting suffixes whose subdomains belong to any tenant of the service, beside the Public Suffix List's own:
     * a wildcard over one would open the agent's egress to every site on it.
     */
    private static final Set<String> SHARED_SUFFIXES = Set.of("github.io", "githubusercontent.com", "gitlab.io",
            "pages.dev", "workers.dev", "netlify.app", "vercel.app", "herokuapp.com", "azurewebsites.net",
            "cloudfront.net", "amazonaws.com", "s3.amazonaws.com", "appspot.com", "firebaseapp.com", "web.app",
            "ngrok-free.app", "ngrok.io", "trycloudflare.com", "blogspot.com", "wordpress.com", "fly.dev",
            "onrender.com", "repl.co", "glitch.me");

    private NetworkHosts() {
    }

    /** A normalized host, or why the text is none ({@code problem} names the rule, for people). */
    public record Parsed(@Nullable String host, @Nullable String problem) {

        static Parsed ok(String host) {
            return new Parsed(host, null);
        }

        static Parsed refused(String problem) {
            return new Parsed(null, problem);
        }

        public boolean valid() {
            return host != null;
        }
    }

    /** Parses and normalizes a host as a person or an agent wrote it. */
    public static Parsed parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Parsed.refused("Name the host: a DNS name such as registry.npmjs.org, or *.example.com.");
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        if (text.contains("://") || text.contains("/") || text.contains("@") || text.contains("?")
                || text.contains("#") || text.chars().anyMatch(c -> Character.isWhitespace(c) || c < 0x20)) {
            return Parsed.refused("The host is HOST or HOST:PORT only, without a scheme, a path or a user name.");
        }
        String name = text;
        String portText = null;
        int colon = text.indexOf(':');
        if (colon >= 0) {
            if (text.indexOf(':', colon + 1) >= 0) {
                return Parsed.refused("IP addresses are not accepted; name the host by its DNS name.");
            }
            name = text.substring(0, colon);
            portText = text.substring(colon + 1);
        }
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        Integer port = null;
        if (portText != null) {
            if (!DIGITS.matcher(portText).matches() || portText.length() > 5) {
                return Parsed.refused("The port is a number from 1 to 65535.");
            }
            int value = Integer.parseInt(portText);
            if (value < 1 || value > 65_535) {
                return Parsed.refused("The port is a number from 1 to 65535.");
            }
            port = value == DEFAULT_PORT ? null : value;
        }
        String problem = nameProblem(name);
        if (problem != null) {
            return Parsed.refused(problem);
        }
        return Parsed.ok(port != null ? name + ":" + port : name);
    }

    /**
     * Whether an allowed host covers a requested one: the same text, or a wildcard {@code *.example.com[:port]} whose
     * suffix and port the requested host (itself a wildcard or not) has.
     */
    public static boolean covers(String allowed, String requested) {
        if (allowed.equals(requested)) {
            return true;
        }
        if (!allowed.startsWith("*.")) {
            return false;
        }
        String suffix = allowed.substring(1);
        if (!Objects.equals(portOf(suffix), portOf(requested))) {
            return false;
        }
        return nameOf(requested).endsWith(nameOf(suffix));
    }

    /**
     * Whether a stored, normalized host is a wildcard over a public suffix or shared hosting: one that may not be
     * sent in {@code run.assign.allowedHosts}.
     */
    public static boolean wildcardOverSuffix(String host) {
        return host.startsWith("*.") && sharedSuffix(nameOf(host.substring(2)));
    }

    private static @Nullable String nameProblem(String name) {
        if (name.isEmpty() || name.length() > MAX_HOST_LENGTH) {
            return "The host name has 1 to " + MAX_HOST_LENGTH + " characters.";
        }
        String[] labels = name.split("\\.", -1);
        int first = 0;
        if (labels[0].equals("*")) {
            if (labels.length < 3) {
                return "A wildcard needs at least two labels after it: *.example.com, not *.com.";
            }
            first = 1;
            String base = name.substring(2);
            if (sharedSuffix(base)) {
                return "*." + base + " is a public suffix or shared hosting, where every tenant of the service would be "
                        + "reachable: name the exact host instead, such as my-site." + base + ".";
            }
        }
        for (int i = first; i < labels.length; i++) {
            if (labels[i].contains("*")) {
                return "A wildcard is only the first label: *.example.com.";
            }
            if (!LABEL.matcher(labels[i]).matches()) {
                return "The host is a DNS name of letters, digits and hyphens (xn-- for international names).";
            }
        }
        String last = labels[labels.length - 1];
        if (DIGITS.matcher(last).matches() || HEX_LABEL.matcher(last).matches()) {
            return "IP addresses are not accepted; name the host by its DNS name.";
        }
        return null;
    }

    private static boolean sharedSuffix(String base) {
        return SHARED_SUFFIXES.contains(base) || base.endsWith(".amazonaws.com")
                || PublicSuffixes.bundled().isPublicSuffix(base) || PublicSuffixes.bundled().isWildcardParent(base);
    }

    private static @Nullable String portOf(String host) {
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(colon + 1) : null;
    }

    private static String nameOf(String host) {
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }
}
