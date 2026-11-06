package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.ProvisionedMemberships;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * A SAML sign-in through the provisioning OIDC uses (ADR-0086, decisions 5 to 7, 11): the identity is the IdP's entity
 * ID and the subject, the email is trusted and held to the provider's domains, the role and the SCIM binding are the
 * same code.
 */
class SamlProvisioningTest {

    private static final String ENTITY = "https://sts.windows.net/2f4a5b1c-0000-4000-8000-000000000001/";

    private final UserRepository users = mock(UserRepository.class);

    private final LoginAudit loginAudit = mock(LoginAudit.class);

    private final AuditLog auditLog = mock(AuditLog.class);

    private final ProvisionedMemberships memberships = mock(ProvisionedMemberships.class);

    private final TimeSource time = new TimeSource(Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC));

    private final UUID companyId = UUID.randomUUID();

    @Test
    void anUnknownSubjectGetsASamlUserAndAMembership() {
        ExternalProvisioning.Outcome outcome = provisioning(provider(true, List.of("acme.example"), null))
            .provisionSaml(login("oid-1", "Ann@Acme.example", "email", "Ann Example", List.of()));

        UserEntity user = savedUser();
        assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
        assertThat(user.getAuthSource()).isEqualTo(AuthSource.SAML);
        assertThat(user.getEmail()).isEqualTo("ann@acme.example");
        assertThat(user.getDisplayName()).isEqualTo("Ann Example");
        verify(memberships).grant(companyId, user.getId(), CompanyRole.MEMBER, "corp", RoleSource.APP);
        verify(loginAudit).succeeded(user.getId(), "saml", "corp", java.util.Set.of(companyId));
        AuditRecord created = createdEvent();
        assertThat(created.action()).isEqualTo(AuditAction.USER_CREATED);
        assertThat(created.details()).containsEntry("authSource", "SAML")
            .containsEntry("provider", "corp")
            .containsEntry("emailAttribute", "email")
            .doesNotContainValue("ann@acme.example");
    }

    @Test
    void aKnownSubjectSignsInWhateverItsEmailSays() {
        UserEntity user = UserEntity.saml(UUID.randomUUID(), "ann@acme.example", "Ann", ENTITY, "oid-2", time.now());
        when(users.findByOidcIssuerAndOidcSubject(ENTITY, "oid-2")).thenReturn(Optional.of(user));

        ExternalProvisioning.Outcome outcome = provisioning(provider(true, List.of(), null))
            .provisionSaml(login("oid-2", "someone-else@acme.example", "email", null, List.of()));

        assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
        verify(users, never()).saveAndFlush(any());
        assertThat(user.getEmail()).isEqualTo("ann@acme.example");
    }

    @Test
    void anOidcUserOfTheSameIssuerAndSubjectIsNeverTheSamlUser() {
        UserEntity oidc = UserEntity.oidc(UUID.randomUUID(), "o@acme.example", "O", ENTITY, "oid-3", time.now());
        when(users.findByOidcIssuerAndOidcSubject(ENTITY, "oid-3")).thenReturn(Optional.of(oidc));

        assertThat(provisioning(provider(true, List.of(), null))
            .provisionSaml(login("oid-3", "o@acme.example", "email", null, List.of())))
            .isEqualTo(new ExternalProvisioning.Denied("identity_in_use", ExternalProvisioning.SAML_LOGIN_FAILED));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void theRefusalsOfANewUserAreOidcsWithTheSamlCode() {
        ExternalProvisioning withDomains = provisioning(provider(true, List.of("acme.example"), null));
        assertThat(withDomains.provisionSaml(login("oid-4", null, null, null, List.of())))
            .isEqualTo(new ExternalProvisioning.Denied("email_missing", OidcLoginDeniedException.EMAIL_MISSING));
        when(users.existsByEmail("taken@other.example")).thenReturn(true);
        // Outside the domains before in use: the refusal does not tell whether the address is taken.
        assertThat(withDomains.provisionSaml(login("oid-5", "taken@other.example", "mail", null, List.of())))
            .isEqualTo(new ExternalProvisioning.Denied("email_domain_not_allowed",
                    OidcLoginDeniedException.EMAIL_DOMAIN_NOT_ALLOWED));
        assertThat(provisioning(provider(true, List.of(), null))
            .provisionSaml(login("oid-6", "taken@other.example", "mail", null, List.of())))
            .isEqualTo(new ExternalProvisioning.Denied("email_in_use", OidcLoginDeniedException.EMAIL_IN_USE));
        assertThat(provisioning(provider(false, List.of(), null))
            .provisionSaml(login("oid-7", "new@acme.example", "email", null, List.of())))
            .isEqualTo(new ExternalProvisioning.Denied("not_provisioned", ExternalProvisioning.SAML_LOGIN_FAILED));
        verify(loginAudit).failed(isNull(), eq("not_provisioned"), eq("corp"), eq("saml"));
    }

    @Test
    void anInactiveUserIsRefused() {
        UserEntity user = UserEntity.saml(UUID.randomUUID(), "gone@acme.example", "Gone", ENTITY, "oid-8", time.now());
        when(users.findByOidcIssuerAndOidcSubject(ENTITY, "oid-8")).thenReturn(Optional.of(user));
        org.springframework.test.util.ReflectionTestUtils.setField(user, "active", false);

        assertThat(provisioning(provider(true, List.of(), null))
            .provisionSaml(login("oid-8", "gone@acme.example", "email", null, List.of())))
            .isEqualTo(new ExternalProvisioning.Denied("inactive", ExternalProvisioning.SAML_LOGIN_FAILED));
    }

    @Test
    void theRoleAttributeDecidesAtEverySignIn() {
        ExternalProvisioning deciding = provisioning(provider(true, List.of(), "groups"));
        deciding.provisionSaml(login("oid-9", "admin@acme.example", "email", null, List.of("staff", "admins")));
        UserEntity user = savedUser();
        verify(memberships).grant(companyId, user.getId(), CompanyRole.ADMIN, "corp", RoleSource.provider("corp"));

        assertThat(deciding.provisionSaml(login("oid-10", "none@acme.example", "email", null, List.of("guests"))))
            .isEqualTo(new ExternalProvisioning.Denied("role_not_mapped", OidcLoginDeniedException.ROLE_NOT_MAPPED));
    }

    @Test
    void aFirstSignInBindsTheUserScimCreatedForTheSubject() {
        UserEntity scim = UserEntity.scim(UUID.randomUUID(), "bound@acme.example", "Bound", companyId, time.now());
        when(memberships.scimMemberByExternalId(companyId, "oid-11")).thenReturn(Optional.of(scim.getId()));
        when(users.findById(scim.getId())).thenReturn(Optional.of(scim));

        ExternalProvisioning.Outcome outcome = provisioning(provider(false, List.of(), null))
            .provisionSaml(login("oid-11", "bound@acme.example", "email", null, List.of()));

        assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(scim.getId()));
        assertThat(scim.getAuthSource()).isEqualTo(AuthSource.SAML);
        verify(loginAudit).succeeded(scim.getId(), "saml", "corp", java.util.Set.of(companyId));
    }

    @Test
    void theRoleAttributeFillsAFurtherCompanyToo() {
        UUID globex = UUID.randomUUID();
        SamlProperties.Provider several = new SamlProperties.Provider("Corp", "https://idp.acme.example/metadata",
                null, "acme", CompanyRole.MEMBER, List.of(), SamlProperties.Provider.NAMEID, List.of("email"),
                List.of("displayName"), null, "groups", List.of("admins"), List.of("staff"), List.of(), true, false,
                Duration.ofHours(12), AuthProperties.ScimMatch.EXTERNAL_ID, Map.of("globex",
                        new AuthProperties.CompanyRoles(List.of(), List.of("globex-users"), List.of())));
        ProviderCompanies companies = new ProviderCompanies(companyId, Map.of(globex,
                several.roles().companies().get("globex")));

        provisioning(several).provisionSaml(new ExternalProvisioning.SamlAssertion("corp", ENTITY, "oid-12",
                "two@acme.example", "email", null, List.of("staff", "globex-users"), companies));

        UserEntity user = savedUser();
        verify(memberships).grant(companyId, user.getId(), CompanyRole.MEMBER, "corp", RoleSource.provider("corp"));
        verify(memberships).grant(globex, user.getId(), CompanyRole.MEMBER, "corp", RoleSource.provider("corp"));
        verify(loginAudit).succeeded(user.getId(), "saml", "corp", java.util.Set.of(companyId, globex));
    }

    private ExternalProvisioning provisioning(SamlProperties.Provider provider) {
        AuthProperties.Limit limit = new AuthProperties.Limit(5, Duration.ofMinutes(1));
        AuthProperties properties = new AuthProperties(true, true, new AuthProperties.LoginThrottleLimits(limit, limit),
                new AuthProperties.Oidc(Map.of()));
        return new ExternalProvisioning(users, loginAudit, auditLog, properties, time, memberships,
                new SamlProperties(Map.of("corp", provider), new SamlProperties.Sp(Map.of(), null)));
    }

    private static SamlProperties.Provider provider(boolean autoProvision, List<String> domains, String roleClaim) {
        return new SamlProperties.Provider("Corp", "https://idp.acme.example/metadata", null, "acme",
                CompanyRole.MEMBER, domains, SamlProperties.Provider.NAMEID, List.of("email", "mail"),
                List.of("displayName"), null, roleClaim, roleClaim == null ? List.of() : List.of("admins"),
                roleClaim == null ? List.of() : List.of("staff"), List.of(), autoProvision, false,
                Duration.ofHours(12), AuthProperties.ScimMatch.EXTERNAL_ID);
    }

    private ExternalProvisioning.SamlAssertion login(String subject, String email, String attribute, String name,
            List<String> roles) {
        return new ExternalProvisioning.SamlAssertion("corp", ENTITY, subject, email, attribute, name, roles,
                companyId);
    }

    private UserEntity savedUser() {
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private AuditRecord createdEvent() {
        ArgumentCaptor<AuditRecord> audited = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditLog).record(audited.capture());
        return audited.getValue();
    }
}
