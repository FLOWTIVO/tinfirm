package com.flowtivo.tinfirm.shared.access;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Who decides a membership's role (ADR-0088), stored as {@code company_membership.role_source}: the app ({@code APP}),
 * the identity provider of one registration at every sign-in through it ({@code IDP:<registration id>}), or the
 * company's SCIM client ({@code SCIM}, ADR-0087). While it is not the app's, changing the role in the app is
 * {@code 409 ROLE_MANAGED}. OWNER is always the app's.
 *
 * @param value the stored form
 */
public record RoleSource(String value) {

    /** The company's Owners and Admins decide, in the app. */
    public static final RoleSource APP = new RoleSource("APP");

    /** The company's SCIM client decides (ADR-0087, ADR-0088 decision 8). */
    public static final RoleSource SCIM = new RoleSource("SCIM");

    private static final String IDP_PREFIX = "IDP:";

    /** The registration ids a provider's role source may name, as the column's check allows. */
    private static final Pattern REGISTRATION_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public RoleSource {
        if (!value.equals("APP") && !value.equals("SCIM") && !(value.startsWith(IDP_PREFIX)
                && REGISTRATION_ID.matcher(value.substring(IDP_PREFIX.length())).matches())) {
            throw new IllegalArgumentException("Not a role source: " + value);
        }
    }

    /** The identity provider of this registration decides, at every sign-in through it. */
    public static RoleSource provider(String registrationId) {
        return new RoleSource(IDP_PREFIX + registrationId);
    }

    /** Whether a registration id can be a provider's role source. */
    public static boolean isRegistrationId(String registrationId) {
        return REGISTRATION_ID.matcher(registrationId).matches();
    }

    public static RoleSource parse(String value) {
        return switch (value) {
            case "APP" -> APP;
            case "SCIM" -> SCIM;
            default -> new RoleSource(value);
        };
    }

    /** Whether something other than the app decides the role, so that the app may not change it. */
    public boolean managed() {
        return !equals(APP);
    }

    /** The registration id of a provider's role source, else null. */
    public @Nullable String provider() {
        return value.startsWith(IDP_PREFIX) ? value.substring(IDP_PREFIX.length()) : null;
    }

    /**
     * Who manages the role as {@code 409 ROLE_MANAGED} names it in {@code details.provider}: the registration id, or
     * {@code scim}; null for the app.
     */
    public @Nullable String manager() {
        if (equals(SCIM)) {
            return "scim";
        }
        return provider();
    }

    @Override
    public String toString() {
        return value;
    }
}
