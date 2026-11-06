package com.flowtivo.tinfirm.identity;

/**
 * The display names of the instance's identity providers, by registration id, as the login page shows them: for the
 * members page's "managed by <provider>" (ADR-0088).
 */
public interface IdentityProviderNames {

    /** The provider's display name; the registration id itself when no provider of the instance has it. */
    String nameOf(String registrationId);
}
