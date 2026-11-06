package com.flowtivo.tinfirm.identity.internal;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Reads the email of an OIDC login from its claims (CONVENTIONS §5). The claims are tried in the configured order, and
 * a claim counts only if its value looks like an email address. A claim may be an array: Entra ID sends
 * {@code verified_primary_email} as one, and its first value that looks like an email counts.
 */
final class OidcEmailClaims {

    static final String EMAIL = "email";

    static final String EMAIL_VERIFIED = "email_verified";

    /** One {@code @}, a local part and a domain of non-empty labels; no whitespace or control characters. */
    private static final Pattern EMAIL_ADDRESS = Pattern
        .compile("[^@\\s\\p{Cntrl}]+@[^@\\s\\p{Cntrl}.]+(\\.[^@\\s\\p{Cntrl}.]+)*");

    private OidcEmailClaims() {
    }

    /** An email and the claim it came from. */
    record ClaimedEmail(String claim, String email) {
    }

    static @Nullable ClaimedEmail email(Map<String, ?> claims, List<String> claimNames) {
        for (String claim : claimNames) {
            String email = firstEmail(claims.get(claim));
            if (email != null) {
                return new ClaimedEmail(claim, email);
            }
        }
        return null;
    }

    /**
     * The {@code email_verified} claim: null when the IdP did not send it, true only for {@code true} (a JSON boolean or
     * the string), false for any other value.
     */
    static @Nullable Boolean emailVerified(Map<String, ?> claims) {
        Object value = claims.get(EMAIL_VERIFIED);
        if (value == null) {
            return null;
        }
        return Boolean.TRUE.equals(value) || value instanceof String text && "true".equalsIgnoreCase(text.trim());
    }

    private static @Nullable String firstEmail(@Nullable Object value) {
        if (value instanceof String text) {
            return trimmedEmail(text);
        }
        if (value instanceof Object[] values) {
            return firstEmail(Arrays.asList(values));
        }
        if (value instanceof Collection<?> values) {
            for (Object element : values) {
                String email = element instanceof String text ? trimmedEmail(text) : null;
                if (email != null) {
                    return email;
                }
            }
        }
        return null;
    }

    private static @Nullable String trimmedEmail(String value) {
        String email = value.trim();
        return EMAIL_ADDRESS.matcher(email).matches() ? email : null;
    }
}
