package com.flowtivo.tinfirm.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.ee.licence.TestLicences;

/**
 * Base class of the end-to-end OIDC sign-in tests against {@link OidcProviderStub} (discovery via issuer-uri,
 * confidential client). The server runs on a fixed port that is also its {@code TINFIRM_PUBLIC_URL}, as in production.
 * <p>
 * One context for every such test, so that Spring's cache starts it once: the registrations of all of them share the
 * one stub, and each test uses its own.
 * <ul>
 * <li>Sign-in ({@code OidcLoginIntegrationTest}): {@code stub-idp} keeps the default email trust
 * ({@code REQUIRE_VERIFIED_CLAIM}); {@code stub-entra} is configured as ADR-0026 describes for Entra ID
 * ({@code TRUST_ISSUER} with the Entra ID order of email claims); {@code stubacme} and {@code stubbeta} make their new
 * users members of the declared companies {@code acme} and {@code beta} (ADR-0075); {@code stubroles} decides the roles
 * of the declared company {@code roles} at every sign-in, from the claim {@code groups} (ADR-0088); {@code stubmulti}
 * fills its own company {@code multia} and {@code multib} from {@code groups} (ADR-0089).</li>
 * <li>SCIM's binding ({@code ScimOidcBindingIntegrationTest}, ADR-0087 decision 6): the declared company
 * {@code scimbind} serves SCIM; {@code stubscim} decides its roles from {@code groups} and binds by {@code externalId};
 * {@code stubscimemail} binds by email too. Neither creates users. In {@code scimbindapp}, SCIM gives the role once
 * ({@code APP}) and {@code stubscimapp} decides it from {@code groups}.</li>
 * </ul>
 * The stub lives as long as the cached context, until the test JVM ends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@Import(TestLicences.class)
@ExtendWith(EnterpriseLicence.class)
public abstract class OidcIntegrationTest {

    protected static final OidcProviderStub IDP = new OidcProviderStub("tinfirm-stub", "stub-only-client-secret");

    protected static final int PORT = freePort();

    protected static final String DEFAULT_TRUST = "stub-idp";

    protected static final String ENTRA = "stub-entra";

    /** A provider whose new users join the declared company {@code acme} as ADMIN, for acme.example only (ADR-0075). */
    protected static final String ACME = "stubacme";

    protected static final String ACME_COMPANY = "Acme Sign-in Co";

    /** The declared owner of a third company, {@code beta}, who has no account when the server starts. */
    protected static final String BETA_OWNER = "beta-owner-" + UUID.randomUUID() + "@acme.example";

    /** A provider whose new users join {@code beta}; it shares the stub's issuer with the others. */
    protected static final String BETA = "stubbeta";

    /**
     * A provider that decides the roles of the declared company {@code roles} at every sign-in (ADR-0088), from the
     * claim {@code groups}.
     */
    protected static final String ROLES = "stubroles";

    protected static final String ROLES_COMPANY = "Roles From The IdP Co";

    /** Fills two declared companies from the claim {@code groups} (ADR-0089): its own and {@code multib}. */
    protected static final String MULTI = "stubmulti";

    protected static final String MULTI_A_COMPANY = "Multi A Co";

    protected static final String MULTI_B_COMPANY = "Multi B Co";

    /** The company {@code scimbind} that serves SCIM, its provider that binds by externalId, and the one by email. */
    protected static final String SCIM_COMPANY = "SCIM Binding Co";

    protected static final String SCIM_BY_ID = "stubscim";

    protected static final String SCIM_BY_EMAIL = "stubscimemail";

    /** The provider of {@code scimbindapp}, where SCIM gives the role once and the provider decides it after. */
    protected static final String SCIM_APP = "stubscimapp";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> TestDatabase.APP);
        registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> TestDatabase.OWNER);
        registry.add("spring.flyway.password", () -> TestDatabase.OWNER_PASSWORD);
        TestIdp.register(registry);
        registry.add("server.port", () -> PORT);
        registry.add("tinfirm.public-url", () -> "http://localhost:" + PORT);
        register(registry, DEFAULT_TRUST, "Stub IdP");
        register(registry, ENTRA, "Stub Entra ID");
        registry.add("tinfirm.auth.oidc.providers." + ENTRA + ".email-trust", () -> "TRUST_ISSUER");
        registry.add("tinfirm.auth.oidc.providers." + ENTRA + ".email-claims",
                () -> "email,preferred_username,verified_primary_email");
        // A second declared company: the providers that name none then give no membership.
        registry.add("tinfirm.companies.acme.name", () -> ACME_COMPANY);
        register(registry, ACME, "Stub Acme SSO");
        registry.add("tinfirm.auth.oidc.providers." + ACME + ".company", () -> "acme");
        registry.add("tinfirm.auth.oidc.providers." + ACME + ".role", () -> "ADMIN");
        registry.add("tinfirm.auth.oidc.providers." + ACME + ".email-domains", () -> "acme.example");
        registry.add("tinfirm.companies.beta.name", () -> "Beta Owned Co");
        registry.add("tinfirm.companies.beta.owner", () -> BETA_OWNER);
        register(registry, BETA, "Stub Beta SSO");
        registry.add("tinfirm.auth.oidc.providers." + BETA + ".company", () -> "beta");
        registry.add("tinfirm.companies.roles.name", () -> ROLES_COMPANY);
        register(registry, ROLES, "Stub Roles SSO");
        registry.add("tinfirm.auth.oidc.providers." + ROLES + ".company", () -> "roles");
        registry.add("tinfirm.auth.oidc.providers." + ROLES + ".role-claim", () -> "groups");
        registry.add("tinfirm.auth.oidc.providers." + ROLES + ".role-map-admin", () -> "admins");
        registry.add("tinfirm.auth.oidc.providers." + ROLES + ".role-map-member", () -> "staff,engineers");
        registry.add("tinfirm.auth.oidc.providers." + ROLES + ".role-map-viewer", () -> "viewers");
        registry.add("tinfirm.companies.multia.name", () -> MULTI_A_COMPANY);
        registry.add("tinfirm.companies.multib.name", () -> MULTI_B_COMPANY);
        register(registry, MULTI, "Stub Multi SSO");
        registry.add("tinfirm.auth.oidc.providers." + MULTI + ".company", () -> "multia");
        registry.add("tinfirm.auth.oidc.providers." + MULTI + ".role-claim", () -> "groups");
        registry.add("tinfirm.auth.oidc.providers." + MULTI + ".role-map-admin", () -> "a-admins");
        registry.add("tinfirm.auth.oidc.providers." + MULTI + ".role-map-member", () -> "a-staff");
        registry.add("tinfirm.auth.oidc.providers." + MULTI + ".companies.multib.role-map-admin", () -> "b-admins");
        registry.add("tinfirm.auth.oidc.providers." + MULTI + ".companies.multib.role-map-member", () -> "b-staff");
        // SCIM's binding at a first sign-in (ADR-0087).
        registry.add("tinfirm.companies.scimbind.name", () -> SCIM_COMPANY);
        registry.add("tinfirm.companies.scimbind.scim.enabled", () -> "true");
        register(registry, SCIM_BY_ID, "Stub SCIM SSO");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_BY_ID + ".company", () -> "scimbind");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_BY_ID + ".auto-provision", () -> "false");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_BY_ID + ".role-claim", () -> "groups");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_BY_ID + ".role-map-admin", () -> "admins");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_BY_ID + ".role-map-member", () -> "staff");
        register(registry, SCIM_BY_EMAIL, "Stub SCIM SSO by email");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_BY_EMAIL + ".company", () -> "scimbind");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_BY_EMAIL + ".auto-provision", () -> "false");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_BY_EMAIL + ".scim-match", () -> "EXTERNAL_ID_OR_EMAIL");
        registry.add("tinfirm.companies.scimbindapp.name", () -> "SCIM Binding App Co");
        registry.add("tinfirm.companies.scimbindapp.scim.enabled", () -> "true");
        registry.add("tinfirm.companies.scimbindapp.scim.role-source", () -> "APP");
        register(registry, SCIM_APP, "Stub SCIM SSO, roles from the provider");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_APP + ".company", () -> "scimbindapp");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_APP + ".auto-provision", () -> "false");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_APP + ".role-claim", () -> "groups");
        registry.add("tinfirm.auth.oidc.providers." + SCIM_APP + ".role-map-member", () -> "staff");
    }

    private static void register(DynamicPropertyRegistry registry, String registrationId, String name) {
        String registration = "spring.security.oauth2.client.registration." + registrationId + ".";
        registry.add(registration + "client-id", () -> "tinfirm-stub");
        registry.add(registration + "client-secret", () -> "stub-only-client-secret");
        registry.add(registration + "client-name", () -> name);
        registry.add(registration + "scope", () -> "openid,email,profile");
        registry.add("spring.security.oauth2.client.provider." + registrationId + ".issuer-uri", IDP::issuer);
    }

    private static int freePort() {
        // Only reserves a free local port for the server; no data goes over it.
        try (ServerSocket socket = new ServerSocket(0)) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            return socket.getLocalPort();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
