package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.flowtivo.tinfirm.identity.internal.AuthProperties.EmailTrust;
import com.flowtivo.tinfirm.identity.internal.AuthProperties.OidcProvider;
import com.flowtivo.tinfirm.shared.access.CompanyRole;

/** The OIDC provider settings bind from the environment variables that CONVENTIONS §4 documents. */
class AuthPropertiesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(Config.class)
        .withPropertyValues("tinfirm.auth.login-throttle.per-email.capacity=5",
                "tinfirm.auth.login-throttle.per-email.period=1m", "tinfirm.auth.login-throttle.per-ip.capacity=30",
                "tinfirm.auth.login-throttle.per-ip.period=1m");

    @Test
    void withoutSettingsEveryRegistrationRequiresTheVerifiedClaim() {
        context.run(app -> {
            AuthProperties properties = app.getBean(AuthProperties.class);
            assertThat(properties.oidc().providers()).isEmpty();
            assertThat(properties.oidc().provider("entra")).isEqualTo(OidcProvider.DEFAULT);
            assertThat(OidcProvider.DEFAULT.emailTrust()).isEqualTo(EmailTrust.REQUIRE_VERIFIED_CLAIM);
            assertThat(OidcProvider.DEFAULT.emailClaims()).containsExactly("email");
        });
    }

    @Test
    void bindsTheEntraSettingsFromEnvironmentVariables() {
        context
            .withInitializer(app -> app.getEnvironment()
                .getPropertySources()
                .addFirst(environment(Map.of("TINFIRM_AUTH_OIDC_PROVIDERS_ENTRA_EMAIL_TRUST", "TRUST_ISSUER",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_ENTRA_EMAIL_CLAIMS",
                        "email, preferred_username, verified_primary_email", "TINFIRM_AUTH_OIDC_AUTO_PROVISION",
                        "false"))))
            .run(app -> {
                AuthProperties properties = app.getBean(AuthProperties.class);
                assertThat(properties.oidcAutoProvision()).isFalse();
                assertThat(properties.oidc().providers()).containsOnlyKeys("entra");
                assertThat(properties.oidc().provider("entra")).isEqualTo(new OidcProvider(EmailTrust.TRUST_ISSUER,
                        List.of("email", "preferred_username", "verified_primary_email"), null, CompanyRole.MEMBER,
                        List.of(), null, null, List.of(), List.of(), List.of()));
                assertThat(properties.oidc().provider("keycloak")).isEqualTo(OidcProvider.DEFAULT);
            });
    }

    @Test
    void bindsTheCompanyOfAProviderFromEnvironmentVariables() {
        context
            .withInitializer(app -> app.getEnvironment()
                .getPropertySources()
                .addFirst(environment(Map.of("TINFIRM_AUTH_OIDC_PROVIDERS_ACMESSO_COMPANY", "acme",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_ACMESSO_ROLE", "ADMIN",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_ACMESSO_EMAIL_DOMAINS", "Acme.example, acme.cz",
                        // Compose passes an unset variable as an empty one.
                        "TINFIRM_AUTH_OIDC_PROVIDERS_OTHER_COMPANY", "",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_OTHER_EMAIL_DOMAINS", ""))))
            .run(app -> {
                AuthProperties.Oidc oidc = app.getBean(AuthProperties.class).oidc();
                OidcProvider acme = oidc.provider("acmesso");
                assertThat(acme.company()).isEqualTo("acme");
                assertThat(acme.role()).isEqualTo(CompanyRole.ADMIN);
                assertThat(acme.emailDomains()).containsExactly("acme.example", "acme.cz");
                assertThat(acme.allows("ann@acme.cz")).isTrue();
                assertThat(acme.allows("ann@sub.acme.cz")).isFalse();
                OidcProvider other = oidc.provider("other");
                assertThat(other.company()).isNull();
                assertThat(other.role()).isEqualTo(CompanyRole.MEMBER);
                assertThat(other.emailDomains()).isEmpty();
                assertThat(other.allows("anyone@anywhere.example")).isTrue();
            });
    }

    @Test
    void aProviderNeverMakesOwners() {
        context.withPropertyValues("tinfirm.auth.oidc.providers.corp.role=OWNER")
            .run(app -> assertThat(app).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("role must be MEMBER, ADMIN or VIEWER"));
    }

    @Test
    void anEmailDomainMustBeADomainName() {
        context.withPropertyValues("tinfirm.auth.oidc.providers.corp.email-domains=@acme.example")
            .run(app -> assertThat(app).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("email-domains must be domain names"));
    }

    @Test
    void aCompanyThatIsNotDeclaredStopsTheStart() {
        OidcProvider acme = new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM, List.of("email"), "acme",
                CompanyRole.MEMBER, List.of(), null, null, List.of(), List.of(), List.of());
        AuthProperties.Oidc oidc = new AuthProperties.Oidc(Map.of("acmesso", acme, "entra", OidcProvider.DEFAULT));

        assertThatIllegalStateException().isThrownBy(() -> oidc.verifyCompanies(Set.of("beta")::contains))
            .withMessageContaining("acmesso -> acme")
            .withMessageContaining("TINFIRM_COMPANIES_<KEY>_NAME");
        oidc.verifyCompanies(Set.of("acme")::contains);
    }

    @Test
    void bindsTheCompaniesOfAProvidersClaimFromEnvironmentVariables() {
        context
            .withInitializer(app -> app.getEnvironment()
                .getPropertySources()
                .addFirst(environment(Map.of("TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_ROLE_CLAIM", "groups",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_ROLE_MAP_MEMBER", "staff",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_COMPANIES_GLOBEX_ROLE_MAP_ADMIN", "globex-admins",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_COMPANIES_GLOBEX_ROLE_MAP_MEMBER",
                        "globex-users, globex-engineers",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_COMPANIES_INITECH_ROLE_MAP_VIEWER", "initech-all"))))
            .run(app -> {
                ProviderRoles roles = app.getBean(AuthProperties.class).oidc().provider("groupsso").roles();
                assertThat(roles.decides()).isTrue();
                assertThat(roles.companies()).containsOnlyKeys("globex", "initech");
                assertThat(roles.companies().get("globex").admin()).containsExactly("globex-admins");
                assertThat(roles.companies().get("globex").member()).containsExactly("globex-users",
                        "globex-engineers");
                assertThat(roles.companies().get("initech").viewer()).containsExactly("initech-all");
                assertThat(roles.problems()).isEmpty();
            });
    }

    @Test
    void aCompanyOfTheClaimThatIsNotDeclaredStopsTheStart() {
        OidcProvider several = provider(null, "groups", List.of(), List.of("staff"), List.of())
            .withCompanies(Map.of("globex", new AuthProperties.CompanyRoles(List.of("g-admins"), List.of(), List.of())));
        AuthProperties.Oidc oidc = new AuthProperties.Oidc(Map.of("groupsso", several));

        assertThatIllegalStateException().isThrownBy(() -> oidc.verifyCompanies(Set.of("main")::contains))
            .withMessageContaining("TINFIRM_AUTH_OIDC_PROVIDERS_GROUPSSO_COMPANIES_GLOBEX_ROLE_MAP_* -> globex");
        oidc.verifyCompanies(Set.of("main", "globex")::contains);
    }

    @Test
    void aProviderDecidesEachCompanyItsClaimFills() {
        AuthProperties.CompanyRoles globex = new AuthProperties.CompanyRoles(List.of("g-admins"), List.of(), List.of());
        OidcProvider several = provider(null, "groups", List.of(), List.of("staff"), List.of())
            .withCompanies(Map.of("globex", globex));
        java.util.function.Function<String, java.util.Optional<String>> main = key -> java.util.Optional.of("main");

        assertThat(new AuthProperties.Oidc(Map.of("groupsso", several)).verifyRoleSources(main::apply))
            .containsExactly(Map.entry("groupsso", Set.of("main", "globex")));
        // A provider of globex alone decides it too: two deciding providers of one company.
        OidcProvider globexOwn = new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM, List.of("email"), "globex",
                CompanyRole.MEMBER, List.of(), null, "groups", List.of(), List.of("staff"), List.of());
        assertThatIllegalStateException()
            .isThrownBy(() -> new AuthProperties.Oidc(Map.of("groupsso", several, "globexsso", globexOwn))
                .verifyRoleSources(key -> java.util.Optional.ofNullable(key != null ? key : "main")))
            .withMessageContaining("company globex [globexsso, groupsso]");
        // Without a home company the claim's companies are the provider's only ones.
        assertThat(new AuthProperties.Oidc(Map.of("groupsso", several)).verifyRoleSources(key -> java.util.Optional
            .empty())).containsExactly(Map.entry("groupsso", Set.of("globex")));
    }

    @Test
    void theClaimsCompaniesMayNotNameTheProvidersOwnOrLeaveItWithoutAMap() {
        AuthProperties.CompanyRoles map = new AuthProperties.CompanyRoles(List.of("g-admins"), List.of(), List.of());
        java.util.function.Function<String, java.util.Optional<String>> main = key -> java.util.Optional.of("main");

        assertThatIllegalStateException()
            .isThrownBy(() -> new AuthProperties.Oidc(Map.of("corp", provider(null, "groups", List.of(),
                    List.of("staff"), List.of()).withCompanies(Map.of("main", map)))).verifyRoleSources(main::apply))
            .withMessageContaining("TINFIRM_AUTH_OIDC_PROVIDERS_CORP_COMPANIES_MAIN_ROLE_MAP_* names the provider's "
                    + "own company");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AuthProperties.Oidc(Map.of("corp", provider(null, "groups", List.of(), List.of(),
                    List.of()).withCompanies(Map.of("globex", map)))).verifyRoleSources(main::apply))
            .withMessageContaining("TINFIRM_AUTH_OIDC_PROVIDERS_CORP_ROLE_MAP_*: the provider decides the roles of "
                    + "its own company main");
        // Under APP the own company takes _ROLE, so no map is needed there.
        assertThat(new AuthProperties.Oidc(Map.of("corp", provider(ProviderRoles.Mode.APP, "groups", List.of(),
                List.of(), List.of()).withCompanies(Map.of("globex", map)))).verifyRoleSources(main::apply)).isEmpty();
    }

    @Test
    void aTrustedIssuerReadsTheEmailClaimUnlessToldOtherwise() {
        context.withPropertyValues("tinfirm.auth.oidc.providers.corp.email-trust=TRUST_ISSUER").run(app -> {
            OidcProvider corp = app.getBean(AuthProperties.class).oidc().provider("corp");
            assertThat(corp.emailTrust()).isEqualTo(EmailTrust.TRUST_ISSUER);
            assertThat(corp.emailClaims()).containsExactly("email");
        });
    }

    @Test
    void otherEmailClaimsNeedATrustedIssuer() {
        context.withPropertyValues("tinfirm.auth.oidc.providers.corp.email-claims=email,preferred_username")
            .run(app -> assertThat(app).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("oidc.providers[corp]")
                .hasMessageContaining("email-claims other than [email] require email-trust TRUST_ISSUER"));
    }

    @Test
    void anUnknownEmailTrustStopsTheStart() {
        context.withPropertyValues("tinfirm.auth.oidc.providers.corp.email-trust=TRUST_EVERYONE")
            .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void settingsForAnUnknownRegistrationStopTheStart() {
        AuthProperties.Oidc oidc = new AuthProperties.Oidc(
                Map.of("entra", OidcProvider.DEFAULT, "entar", OidcProvider.DEFAULT));

        assertThatIllegalStateException().isThrownBy(() -> oidc.verifyRegistrations(Set.of("entra")::contains))
            .withMessageContaining("[entar]")
            .withMessageContaining("spring.security.oauth2.client.registration.<id>");
        oidc.verifyRegistrations(Set.of("entra", "entar", "keycloak")::contains);
    }

    /** Variables as the operating system passes them, with Spring's relaxed binding for environment variables. */
    @Test
    void bindsTheRoleSettingsFromEnvironmentVariables() {
        context
            .withInitializer(app -> app.getEnvironment()
                .getPropertySources()
                .addFirst(environment(Map.of("TINFIRM_AUTH_OIDC_PROVIDERS_ENTRA_ROLE_SOURCE", "IDP",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_ENTRA_ROLE_CLAIM", "roles",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_ENTRA_ROLE_MAP_ADMIN", "Tinfirm.Admin",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_ENTRA_ROLE_MAP_MEMBER", "Tinfirm.Member, Tinfirm.User",
                        "TINFIRM_AUTH_OIDC_PROVIDERS_ENTRA_ROLE_MAP_VIEWER", "Tinfirm.Viewer"))))
            .run(app -> {
                ProviderRoles roles = app.getBean(AuthProperties.class).oidc().provider("entra").roles();
                assertThat(roles.mode()).isEqualTo(ProviderRoles.Mode.IDP);
                assertThat(roles.claim()).isEqualTo("roles");
                assertThat(roles.home().admin()).containsExactly("Tinfirm.Admin");
                assertThat(roles.home().member()).containsExactly("Tinfirm.Member", "Tinfirm.User");
                assertThat(roles.home().viewer()).containsExactly("Tinfirm.Viewer");
                assertThat(roles.problems()).isEmpty();
            });
    }

    @Test
    void aProviderWithoutARoleClaimRunsAsApp() {
        context.withPropertyValues("tinfirm.auth.oidc.providers.corp.role=ADMIN").run(app -> {
            ProviderRoles roles = app.getBean(AuthProperties.class).oidc().provider("corp").roles();
            assertThat(roles.mode()).isEqualTo(ProviderRoles.Mode.APP);
            assertThat(roles.decides()).isFalse();
        });
        assertThat(OidcProvider.DEFAULT.roles().mode()).isEqualTo(ProviderRoles.Mode.APP);
    }

    @Test
    void aRoleClaimMakesTheProviderDecideUnlessItIsSetToApp() {
        context.withPropertyValues("tinfirm.auth.oidc.providers.corp.role-claim=groups",
                "tinfirm.auth.oidc.providers.corp.role-map-member=staff",
                "tinfirm.auth.oidc.providers.other.role-claim=groups",
                "tinfirm.auth.oidc.providers.other.role-map-member=staff",
                "tinfirm.auth.oidc.providers.other.role-source=APP").run(app -> {
                    AuthProperties.Oidc oidc = app.getBean(AuthProperties.class).oidc();
                    assertThat(oidc.provider("corp").roles().mode()).isEqualTo(ProviderRoles.Mode.IDP);
                    assertThat(oidc.provider("other").roles().mode()).isEqualTo(ProviderRoles.Mode.APP);
                });
    }

    @Test
    void anUnknownRoleSourceStopsTheStart() {
        context.withPropertyValues("tinfirm.auth.oidc.providers.corp.role-source=BOTH")
            .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void anExplicitIdpWithoutARoleClaimStopsTheStartNamingTheSetting() {
        AuthProperties.Oidc oidc = new AuthProperties.Oidc(Map.of("corp", provider(ProviderRoles.Mode.IDP, null,
                List.of(), List.of(), List.of())));

        assertThatIllegalStateException().isThrownBy(() -> oidc.verifyRoleSources(key -> java.util.Optional.of("main")))
            .withMessageContaining("TINFIRM_AUTH_OIDC_PROVIDERS_CORP_ROLE_SOURCE=IDP needs ROLE_CLAIM");
    }

    @Test
    void roleMapsThatCannotBeAppliedStopTheStart() {
        java.util.function.Function<String, java.util.Optional<String>> main = key -> java.util.Optional.of("main");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AuthProperties.Oidc(Map.of("corp", provider(null, null, List.of("admins"), List.of(),
                    List.of()))).verifyRoleSources(main::apply))
            .withMessageContaining("CORP_ROLE_MAP_* needs ROLE_CLAIM");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AuthProperties.Oidc(Map.of("corp", provider(null, "groups", List.of(" "), List.of(),
                    List.of()))).verifyRoleSources(main::apply))
            .withMessageContaining("CORP_ROLE_MAP_ADMIN holds an empty value");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AuthProperties.Oidc(Map.of("corp", provider(null, "groups", List.of("staff"),
                    List.of("staff"), List.of()))).verifyRoleSources(main::apply))
            .withMessageContaining("CORP_ROLE_MAP_MEMBER lists 'staff', which ROLE_MAP_ADMIN lists too");
        assertThatIllegalStateException()
            .isThrownBy(() -> new AuthProperties.Oidc(Map.of("corp", provider(null, "groups", List.of(), List.of(),
                    List.of()))).verifyRoleSources(main::apply))
            .withMessageContaining("CORP_ROLE_CLAIM needs at least one of ROLE_MAP_ADMIN");
    }

    @Test
    void twoProvidersThatDecideOneCompanysRolesStopTheStart() {
        OidcProvider deciding = provider(null, "groups", List.of(), List.of("staff"), List.of());
        OidcProvider app = provider(ProviderRoles.Mode.APP, "groups", List.of(), List.of("staff"), List.of());
        java.util.function.Function<String, java.util.Optional<String>> main = key -> java.util.Optional.of("main");

        assertThatIllegalStateException()
            .isThrownBy(() -> new AuthProperties.Oidc(Map.of("first", deciding, "second", deciding))
                .verifyRoleSources(main::apply))
            .withMessageContaining("company main [first, second]")
            .withMessageContaining("ROLE_SOURCE=APP");
        assertThat(new AuthProperties.Oidc(Map.of("first", deciding, "second", app)).verifyRoleSources(main::apply))
            .containsExactly(Map.entry("first", Set.of("main")));
        // Providers of two companies each decide their own.
        OidcProvider acme = new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM, List.of("email"), "acme",
                CompanyRole.MEMBER, List.of(), null, "groups", List.of(), List.of("staff"), List.of());
        OidcProvider beta = new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM, List.of("email"), "beta",
                CompanyRole.MEMBER, List.of(), null, "groups", List.of(), List.of("staff"), List.of());
        assertThat(new AuthProperties.Oidc(Map.of("acmesso", acme, "betasso", beta))
            .verifyRoleSources(java.util.Optional::ofNullable))
            .containsExactlyInAnyOrderEntriesOf(Map.of("acmesso", Set.of("acme"), "betasso", Set.of("beta")));
    }

    private static OidcProvider provider(ProviderRoles.Mode source, String claim, List<String> admin,
            List<String> member, List<String> viewer) {
        return new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM, List.of("email"), null, CompanyRole.MEMBER,
                List.of(), source, claim, admin, member, viewer);
    }

    private static SystemEnvironmentPropertySource environment(Map<String, Object> variables) {
        return new SystemEnvironmentPropertySource("test-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                variables);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class Config {
    }
}
