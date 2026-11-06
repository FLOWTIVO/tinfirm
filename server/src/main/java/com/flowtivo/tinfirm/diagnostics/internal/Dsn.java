package com.flowtivo.tinfirm.diagnostics.internal;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A Sentry DSN, {@code https://<public key>@<host>[:port][/<path>]/<project id>}, which any tracker that speaks
 * Sentry's protocol gives (Sentry, GlitchTip, Bugsink). {@code https}, or {@code http} to a loopback address
 * (ADR-0070, §5).
 *
 * @param envelope  the project's envelope endpoint,
 *                  {@code <scheme>://<host>[:port][/<path>]/api/<project id>/envelope/}
 * @param publicKey the key of {@code X-Sentry-Auth}, which only lets its holder send events to the project
 * @param host      the destination's host, which the page shows
 */
record Dsn(URI envelope, String publicKey, String host, String raw) {

    /** Sentry's keys are 32 hexadecimal digits; some trackers give a UUID with its dashes. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private static final Pattern PROJECT = Pattern.compile("[0-9]{1,20}");

    /** Parses a DSN; the reason in the exception names no key. */
    static Dsn parse(String dsn) {
        URI uri;
        try {
            uri = new URI(dsn.strip());
        }
        catch (URISyntaxException ex) {
            throw new IllegalArgumentException("TINFIRM_ERROR_REPORTS_DSN is not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() == null) {
            throw new IllegalArgumentException(
                    "TINFIRM_ERROR_REPORTS_DSN must be <scheme>://<public key>@<host>/<project id>");
        }
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback(host))) {
            throw new IllegalArgumentException(
                    "TINFIRM_ERROR_REPORTS_DSN must use https (http only to a loopback address)");
        }
        String userInfo = uri.getRawUserInfo();
        String key = userInfo.contains(":") ? userInfo.substring(0, userInfo.indexOf(':')) : userInfo;
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("TINFIRM_ERROR_REPORTS_DSN has no valid public key");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("TINFIRM_ERROR_REPORTS_DSN takes no query or fragment");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String project = slash >= 0 ? path.substring(slash + 1) : "";
        if (!PROJECT.matcher(project).matches()) {
            throw new IllegalArgumentException("TINFIRM_ERROR_REPORTS_DSN has no numeric project id at its end");
        }
        String prefix = path.substring(0, slash);
        // An IPv6 literal's host keeps its brackets.
        String authority = host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
        URI envelope = URI.create(scheme + "://" + authority + prefix + "/api/" + project + "/envelope/");
        return new Dsn(envelope, key, host.toLowerCase(Locale.ROOT), dsn.strip());
    }

    private static boolean loopback(String host) {
        String name = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        if (name.equalsIgnoreCase("localhost")) {
            return true;
        }
        // A literal only: a name is never resolved here, so a DNS answer cannot make it loopback.
        if (!name.matches("[0-9.]+") && !name.contains(":")) {
            return false;
        }
        try {
            return InetAddress.ofLiteral(name).isLoopbackAddress();
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public String toString() {
        // Never the key: the DSN is logged nowhere, and its record not with it.
        return "Dsn[" + envelope + "]";
    }
}
