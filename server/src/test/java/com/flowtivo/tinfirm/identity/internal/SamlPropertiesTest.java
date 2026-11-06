package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/** The SAML settings bind from the environment variables ADR-0086 decision 1 names, with its defaults. */
class SamlPropertiesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(Config.class);

    @Test
    void withoutSettingsNoProviderIsDeclared() {
        context.run(app -> {
            SamlProperties properties = app.getBean(SamlProperties.class);
            assertThat(properties.providers()).isEmpty();
            assertThat(properties.sp().credentials()).isEmpty();
        });
    }

    @Test
    void bindsAProviderAndTheServiceProvidersKeysFromEnvironmentVariables() {
        context
            .withInitializer(app -> app.getEnvironment()
                .getPropertySources()
                .addFirst(environment(Map.ofEntries(
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_METADATA_LOCATION",
                                "https://login.example.com/federationmetadata.xml"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_NAME", "Corp SSO"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_COMPANY", "acme"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_ROLE", "ADMIN"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_EMAIL_DOMAINS", "Acme.example"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_SUBJECT",
                                "http://schemas.microsoft.com/identity/claims/objectidentifier"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_ROLE_CLAIM",
                                "http://schemas.microsoft.com/ws/2008/06/identity/claims/role"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_ROLE_MAP_ADMIN", "Tinfirm.Admin"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_AUTO_PROVISION", "false"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_REQUIRE_ENCRYPTED_ASSERTIONS", "true"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_METADATA_REFRESH", "PT1H"),
                        Map.entry("TINFIRM_AUTH_SAML_PROVIDERS_CORP_SCIM_MATCH", "EXTERNAL_ID_OR_EMAIL"),
                        Map.entry("TINFIRM_AUTH_SAML_SP_CREDENTIALS_K2026_KEY", "key-pem"),
                        Map.entry("TINFIRM_AUTH_SAML_SP_CREDENTIALS_K2026_CERT", "cert-pem"),
                        Map.entry("TINFIRM_AUTH_SAML_SP_ACTIVE_CREDENTIAL", "k2026")))))
            .run(app -> {
                SamlProperties properties = app.getBean(SamlProperties.class);
                SamlProperties.Provider corp = properties.provider("corp");
                assertThat(corp.metadataLocation()).isEqualTo("https://login.example.com/federationmetadata.xml");
                assertThat(corp.name()).isEqualTo("Corp SSO");
                assertThat(corp.company()).isEqualTo("acme");
                assertThat(corp.role()).isEqualTo(CompanyRole.ADMIN);
                assertThat(corp.emailDomains()).containsExactly("acme.example");
                assertThat(corp.subjectIsNameId()).isFalse();
                assertThat(corp.roles().decides()).isTrue();
                assertThat(corp.autoProvision()).isFalse();
                assertThat(corp.requireEncryptedAssertions()).isTrue();
                assertThat(corp.metadataRefresh()).isEqualTo(Duration.ofHours(1));
                assertThat(corp.scimMatch()).isEqualTo(AuthProperties.ScimMatch.EXTERNAL_ID_OR_EMAIL);
                assertThat(properties.sp().credentials()).containsOnlyKeys("k2026");
                assertThat(properties.sp().credentials().get("k2026").key()).isEqualTo("key-pem");
                assertThat(properties.sp().activeCredential()).isEqualTo("k2026");
            });
    }

    @Test
    void theDefaultsAreDecisionOnes() {
        context.withPropertyValues("tinfirm.auth.saml.providers.okta.metadata-location=file:/etc/tinfirm/okta.xml")
            .run(app -> {
                SamlProperties.Provider okta = app.getBean(SamlProperties.class).provider("okta");
                assertThat(okta.metadataIsFile()).isTrue();
                assertThat(okta.subjectIsNameId()).isTrue();
                assertThat(okta.role()).isEqualTo(CompanyRole.MEMBER);
                assertThat(okta.emailAttributes()).containsExactly("email", "mail",
                        "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
                        "urn:oid:0.9.2342.19200300.100.1.3");
                assertThat(okta.nameAttributes()).containsExactly("displayName",
                        "http://schemas.microsoft.com/identity/claims/displayname", "urn:oid:2.16.840.1.113730.3.1.241",
                        "name");
                assertThat(okta.autoProvision()).isTrue();
                assertThat(okta.requireEncryptedAssertions()).isFalse();
                assertThat(okta.metadataRefresh()).isEqualTo(Duration.ofHours(12));
                assertThat(okta.scimMatch()).isEqualTo(AuthProperties.ScimMatch.EXTERNAL_ID);
                assertThat(okta.roles().decides()).isFalse();
            });
    }

    @Test
    void metadataOverHttpAnOwnerRoleOrAShortRefreshStopTheStart() {
        context.withPropertyValues("tinfirm.auth.saml.providers.corp.metadata-location=http://login.example.com/m.xml")
            .run(app -> assertThat(app).hasFailed()
                .getFailure()
                .hasStackTraceContaining("metadata-location must be an https:// URL or a file: path"));
        context
            .withPropertyValues("tinfirm.auth.saml.providers.corp.metadata-location=https://login.example.com/m.xml",
                    "tinfirm.auth.saml.providers.corp.role=OWNER")
            .run(app -> assertThat(app).hasFailed()
                .getFailure()
                .hasStackTraceContaining("role must be MEMBER, ADMIN or VIEWER"));
        context
            .withPropertyValues("tinfirm.auth.saml.providers.corp.metadata-location=https://login.example.com/m.xml",
                    "tinfirm.auth.saml.providers.corp.metadata-refresh=PT1M")
            .run(app -> assertThat(app).hasFailed()
                .getFailure()
                .hasStackTraceContaining("metadata-refresh must be at least PT5M"));
        context.withPropertyValues("tinfirm.auth.saml.providers.corp.name=No location")
            .run(app -> assertThat(app).hasFailed());
    }

    private static SystemEnvironmentPropertySource environment(Map<String, Object> variables) {
        return new SystemEnvironmentPropertySource("test-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                variables);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SamlProperties.class)
    static class Config {
    }
}
