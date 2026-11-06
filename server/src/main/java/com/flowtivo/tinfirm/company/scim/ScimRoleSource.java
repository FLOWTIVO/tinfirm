package com.flowtivo.tinfirm.company.scim;

/**
 * Who decides the role of a member SCIM manages after their creation (ADR-0088, decision 8): {@code IDP}, SCIM, at
 * every request that carries the role, and the app cannot change it; {@code APP}, SCIM gives it once, at creation.
 */
public enum ScimRoleSource {
    IDP, APP
}
