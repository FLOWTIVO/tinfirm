package com.flowtivo.tinfirm.identity.internal;

/**
 * How a user authenticates. {@code SCIM}: a user a company's SCIM client created (ADR-0087), with no password and no
 * identity until their first sign-in binds one, which makes them {@code OIDC} or {@code SAML}. {@code SAML}: a user a SAML provider signed in (ADR-0086).
 */
enum AuthSource {
    LOCAL, OIDC, SCIM, SAML
}
