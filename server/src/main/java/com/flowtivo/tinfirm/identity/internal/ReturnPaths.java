package com.flowtivo.tinfirm.identity.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The page a sign-in through an identity provider returns to (threat model 3.4 and Q44, CONVENTIONS §5): the web app
 * names it in {@code returnTo} on the start, {@code /oauth2/authorization/{id}?returnTo=} or
 * {@code /saml2/authenticate/{id}?returnTo=}; the server keeps the checked value with that one login's saved request
 * (the OIDC authorization request in the session, the SAML AuthnRequest's row), never takes it from the IdP's
 * response, and redirects there once, after a successful sign-in only.
 * <p>
 * Only a same-origin path of a page is accepted ({@link #check}): printable ASCII, starting with one {@code /}, with
 * no backslash, no fragment, no scheme or host, and none of these in any of its percent-decodings either (no
 * {@code //} or {@code /\} at the start, no backslash or control character, no {@code .} or {@code ..} segment, no
 * {@code ;}), and not a path the server answers itself rather than the web app ({@link #NOT_PAGES}).
 */
public final class ReturnPaths {

    /** The start's query parameter. */
    public static final String PARAMETER = "returnTo";

    /** The request attribute that holds the checked return path of the login a callback completes. */
    public static final String REQUEST_ATTRIBUTE = ReturnPaths.class.getName() + ".returnPath";

    static final int MAX_LENGTH = 2048;

    /** Percent-decodings looked through; a value still encoded deeper than this is refused. */
    private static final int MAX_DECODINGS = 3;

    /**
     * The paths the server answers itself, not pages of the web app: the API, the OAuth and SAML endpoints, the
     * actuator, the well-known documents, SCIM, and the login page itself.
     */
    static final List<String> NOT_PAGES = List.of("/api", "/oauth2", "/login", "/saml2", "/actuator", "/.well-known",
            "/scim");

    private ReturnPaths() {
    }

    /** The value when it is a same-origin path of a page, else null. */
    public static @Nullable String check(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH || !shape(value)) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '\\' || c == '#') {
                return null;
            }
        }
        int query = value.indexOf('?');
        String path = decoded(query < 0 ? value : value.substring(0, query));
        String whole = decoded(value);
        if (path == null || whole == null || !shape(path) || !shape(whole) || path.indexOf(';') >= 0) {
            return null;
        }
        for (int i = 0; i < whole.length(); i++) {
            char c = whole.charAt(i);
            if (Character.isISOControl(c) || c == '\\' || Character.getType(c) == Character.FORMAT
                    || c == '\u2028' || c == '\u2029') {
                return null;
            }
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String prefix : NOT_PAGES) {
            if (lower.equals(prefix) || lower.startsWith(prefix + "/")) {
                return null;
            }
        }
        return value;
    }

    /** Starts with one slash: not scheme-relative ({@code //host}), not {@code /\host}. */
    private static boolean shape(String value) {
        return value.startsWith("/") && !value.startsWith("//") && !value.startsWith("/\\");
    }

    /**
     * The value decoded until no percent-encoding is left, at most {@link #MAX_DECODINGS} times; null when the value
     * is malformed or is still encoded after that. A {@code %} that a later decoding cannot read is a literal one.
     */
    private static @Nullable String decoded(String value) {
        String current = value;
        for (int round = 0; current.indexOf('%') >= 0; round++) {
            if (round == MAX_DECODINGS) {
                return null;
            }
            String next = percentDecode(current);
            if (next == null) {
                return round == 0 ? null : current;
            }
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /** RFC 3986 percent-decoding as UTF-8, {@code +} left as it is; null for a malformed escape or byte sequence. */
    private static @Nullable String percentDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '%') {
                // Only ASCII is accepted at the start; what an earlier decoding yielded beyond it is UTF-8 again.
                int end = Character.isHighSurrogate(c) && i + 1 < value.length() ? i + 2 : i + 1;
                byte[] encoded = value.substring(i, end).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                i = end - 1;
                continue;
            }
            if (i + 2 >= value.length()) {
                return null;
            }
            int high = Character.digit(value.charAt(i + 1), 16);
            int low = Character.digit(value.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes.write(high * 16 + low);
            i += 2;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        }
        catch (CharacterCodingException ex) {
            return null;
        }
    }
}
