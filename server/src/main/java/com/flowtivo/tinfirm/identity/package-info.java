/**
 * Users and authentication: local login (Argon2id, throttled), OIDC login with provisioning by (issuer, subject),
 * server-side sessions (Spring Session JDBC), CSRF, security headers, the current user and user administration.
 * <p>
 * API: {@link com.flowtivo.tinfirm.identity.UserDirectory} and {@link com.flowtivo.tinfirm.identity.UserAccounts}
 * for other modules; {@link com.flowtivo.tinfirm.identity.MembershipDirectory} is a port implemented by the company
 * module. {@link com.flowtivo.tinfirm.identity.McpChallenges} formats the MCP endpoint's step-up challenge for the
 * {@code mcp} module, beside the bearer chain's 401 (ADR-0045).
 * <p>
 * The OIDC providers are the deployment's (ADR-0075): a user one creates joins the provider's declared company, with
 * the provider's role, through the port {@link com.flowtivo.tinfirm.identity.ProvisionedMemberships} that the company
 * module implements. Each successful login is published as
 * {@link com.flowtivo.tinfirm.identity.UserEvents.UserSignedIn}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package com.flowtivo.tinfirm.identity;
