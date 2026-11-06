package com.flowtivo.tinfirm.shared.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The URL half of the outbound guard (CONVENTIONS §14.4); {@link AddressGuard} is the address half. A URL that
 * someone outside the server chose, and that the server will call, must be an absolute https URL (http only where
 * the operator allows it), with a host, without user info, query or fragment, at most 500 characters, and its host
 * must resolve only to addresses an outbound request may go to.
 */
public final class OutboundUrls {

    public static final int MAX_URL_LENGTH = 500;

    private OutboundUrls() {
    }

    /**
     * What the guard allows.
     *
     * @param allowHttp accept http URLs as well as https
     * @param reach     the addresses the host may resolve to ({@link AddressGuard.Reach})
     * @param httpHint  how the operator allows http, named in the refusal of an http URL
     */
    public record Policy(boolean allowHttp, AddressGuard.Reach reach, String httpHint) {

        /**
         * A policy that allows public addresses only, or any address.
         *
         * @param allowPrivateAddresses accept hosts that resolve to loopback, link-local or private addresses
         */
        public Policy(boolean allowHttp, boolean allowPrivateAddresses, String httpHint) {
            this(allowHttp, allowPrivateAddresses ? AddressGuard.Reach.ANY : AddressGuard.Reach.PUBLIC, httpHint);
        }
    }

    /** Why the URL may not be called, or null when it may. Resolves the host. */
    public static @Nullable String problem(String url, Policy policy) {
        if (url.length() > MAX_URL_LENGTH || url.chars().anyMatch(OutboundUrls::forbidden)) {
            return "it must be at most " + MAX_URL_LENGTH + " characters without whitespace";
        }
        URI uri;
        try {
            uri = new URI(url);
        }
        catch (URISyntaxException ex) {
            return "it is not a valid URL";
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : null;
        if (scheme == null || uri.getRawAuthority() == null || uri.getHost() == null || uri.getHost().isBlank()) {
            return "it must be an absolute https URL with a host, e.g. https://jira.example.com";
        }
        String schemeProblem = schemeProblem(scheme, policy);
        if (schemeProblem != null) {
            return schemeProblem;
        }
        if (uri.getRawUserInfo() != null || uri.getRawAuthority().contains("@")) {
            return "it must not contain credentials";
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "it must not contain a query or fragment";
        }
        return AddressGuard.problem(uri.getHost(), policy.reach());
    }

    /** Why a URL of this (lower-case) scheme may not be called, or null for https, and for http where allowed. */
    public static @Nullable String schemeProblem(String scheme, Policy policy) {
        if ("https".equals(scheme)) {
            return null;
        }
        if ("http".equals(scheme)) {
            return policy.allowHttp() ? null : "it must be https (" + policy.httpHint() + ")";
        }
        return "it must be an https URL";
    }

    /** Whitespace and control characters never belong in a URL. */
    private static boolean forbidden(int character) {
        return Character.isWhitespace(character) || Character.isISOControl(character);
    }
}
