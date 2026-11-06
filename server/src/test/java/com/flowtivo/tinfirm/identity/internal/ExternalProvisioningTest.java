package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.ProvisionedMemberships;
import com.flowtivo.tinfirm.identity.internal.AuthProperties.EmailTrust;
import com.flowtivo.tinfirm.identity.internal.AuthProperties.OidcProvider;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/** The OIDC provisioning rules of CONVENTIONS §5, without an identity provider. */
class ExternalProvisioningTest {

    private static final String ISSUER = "https://idp.example.com/realms/acme";

    /** The Entra ID order of email claims (ADR-0026). */
    private static final List<String> ENTRA_CLAIMS = List.of("email", "preferred_username", "verified_primary_email");

    private final UserRepository users = mock(UserRepository.class);

    private final LoginAudit loginAudit = mock(LoginAudit.class);

    private final AuditLog auditLog = mock(AuditLog.class);

    private final ProvisionedMemberships memberships = mock(ProvisionedMemberships.class);

    private final TimeSource time = new TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void anExistingIdentityLogsInAsItsUser() {
        UserEntity user = UserEntity.oidc(UUID.randomUUID(), "known@example.com", "Known", ISSUER, "sub-1", time.now());
        when(users.findByOidcIssuerAndOidcSubject(ISSUER, "sub-1")).thenReturn(Optional.of(user));

        ExternalProvisioning.Outcome outcome = provisioning(true).provision(login("sub-1", "other@example.com", true, null));

        assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
        assertThat(user.getLastLoginAt()).isEqualTo(time.now());
        verify(loginAudit).succeeded(user.getId(), "oidc", "acme", java.util.Set.of());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void aDeactivatedUserIsRejected() {
        UserEntity user = UserEntity.oidc(UUID.randomUUID(), "gone@example.com", "Gone", ISSUER, "sub-2", time.now());
        user.activate(false, time.now());
        when(users.findByOidcIssuerAndOidcSubject(ISSUER, "sub-2")).thenReturn(Optional.of(user));

        ExternalProvisioning.Outcome outcome = provisioning(true).provision(login("sub-2", "gone@example.com", true, null));

        assertThat(outcome).isEqualTo(failed("inactive"));
        verify(loginAudit).failed(user.getId(), "inactive", "acme");
    }

    @Test
    void aNewVerifiedIdentityGetsANewOidcUser() {
        when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

        ExternalProvisioning.Outcome outcome = provisioning(true)
            .provision(login("sub-3", "New.Person@Example.com", true, null));

        UserEntity user = savedUser();
        assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
        assertThat(user.getEmail()).isEqualTo("new.person@example.com");
        assertThat(user.getDisplayName()).isEqualTo("new.person");
        assertThat(user.getAuthSource()).isEqualTo(AuthSource.OIDC);
        assertThat(user.isInstanceAdmin()).isFalse();
        assertThat(user.getPasswordHash()).isNull();
        verify(memberships, never()).grant(any(), any(), any(), any(), any());

        AuditRecord created = createdEvent();
        assertThat(created.action()).isEqualTo(AuditAction.USER_CREATED);
        assertThat(created.actor().type()).isEqualTo(ActorType.SYSTEM);
        assertThat(created.details()).containsEntry("emailClaim", "email")
            .doesNotContainValue("new.person@example.com");
        verify(loginAudit).succeeded(user.getId(), "oidc", "acme", java.util.Set.of());
    }

    @Test
    void anUnverifiedEmailIsNotProvisioned() {
        when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(provisioning(true).provision(login("sub-4", "x@example.com", false, "X")))
            .isEqualTo(notVerified());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void anEmailOfAnotherUserIsNeverLinked() {
        when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
        when(users.existsByEmail("taken@example.com")).thenReturn(true);

        assertThat(provisioning(true).provision(login("sub-5", "Taken@example.com", true, "T")))
            .isEqualTo(new ExternalProvisioning.Denied("email_in_use", OidcLoginDeniedException.EMAIL_IN_USE));
        verify(users, never()).saveAndFlush(any());
        verify(loginAudit).failed(isNull(), eq("email_in_use"), eq("acme"));
    }

    @Test
    void withoutAutoProvisioningUnknownIdentitiesAreRejected() {
        when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(provisioning(false).provision(login("sub-6", "new@example.com", true, "N")))
            .isEqualTo(failed("not_provisioned"));
        verify(users, never()).saveAndFlush(any());
    }

    /** The default for every registration without settings: {@code email_verified=true} on the {@code email} claim. */
    @Nested
    class RequireVerifiedClaim {

        @Test
        void aMissingEmailVerifiedClaimBlocksProvisioning() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(true)
                .provision(entraLogin("acme", "sub-r1", Map.of("email", "r1@example.com", "name", "R One")));

            assertThat(outcome).isEqualTo(notVerified());
            verify(users, never()).saveAndFlush(any());
            verify(loginAudit).failed(isNull(), eq("email_not_verified"), eq("acme"));
        }

        @Test
        void aMissingEmailIsReportedAsMissing() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(true)
                .provision(entraLogin("acme", "sub-r2", Map.of("email_verified", true, "name", "R Two")));

            assertThat(outcome).isEqualTo(emailMissing());
            verify(loginAudit).failed(isNull(), eq("email_missing"), eq("acme"));
        }

        @Test
        void onlyTheEmailClaimCountsBecauseEmailVerifiedSpeaksForIt() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(true).provision(entraLogin("acme", "sub-r3",
                    Map.of("email_verified", true, "preferred_username", "r3@example.com",
                            "verified_primary_email", List.of("r3@example.com"))));

            assertThat(outcome).isEqualTo(emailMissing());
            verify(users, never()).saveAndFlush(any());
        }

        @Test
        void emailVerifiedAsTheStringTrueCounts() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(true)
                .provision(entraLogin("acme", "sub-r4", Map.of("email", "r4@example.com", "email_verified", "true")));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(savedUser().getId()));
        }

        @Test
        void aRegistrationWithoutSettingsKeepsTheDefaultWhenAnotherTrustsItsIssuer() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(true, Map.of("entra", trustIssuer(ENTRA_CLAIMS)))
                .provision(entraLogin("acme", "sub-r5", Map.of("email", "r5@example.com")));

            assertThat(outcome).isEqualTo(notVerified());
        }
    }

    /** Entra ID: no {@code email_verified}; the email in {@code email}, {@code preferred_username} or an array. */
    @Nested
    class TrustIssuer {

        @Test
        void provisionsWithoutEmailVerified() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = entra().provision(entraLogin("entra", "sub-t1",
                    Map.of("email", "Ada.Lovelace@Contoso.com", "preferred_username", "ada@contoso.onmicrosoft.com",
                            "name", "Ada Lovelace")));

            UserEntity user = savedUser();
            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            assertThat(user.getEmail()).isEqualTo("ada.lovelace@contoso.com");
            assertThat(user.getDisplayName()).isEqualTo("Ada Lovelace");
            assertThat(user.getAuthSource()).isEqualTo(AuthSource.OIDC);
            assertThat(createdEvent().details()).containsEntry("emailClaim", "email")
                .containsEntry("provider", "entra")
                .doesNotContainValue("ada.lovelace@contoso.com");
            verify(loginAudit).succeeded(user.getId(), "oidc", "entra", java.util.Set.of());
        }

        @Test
        void fallsBackToAPreferredUsernameThatLooksLikeAnEmail() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = entra().provision(entraLogin("entra", "sub-t2",
                    Map.of("preferred_username", "Grace@Contoso.com", "verified_primary_email",
                            List.of("grace.hopper@contoso.com"))));

            UserEntity user = savedUser();
            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            assertThat(user.getEmail()).isEqualTo("grace@contoso.com");
            assertThat(user.getDisplayName()).isEqualTo("Grace@Contoso.com");
            assertThat(createdEvent().details()).containsEntry("emailClaim", "preferred_username");
        }

        @Test
        void skipsAPreferredUsernameThatIsNoEmailAndReadsTheVerifiedPrimaryEmailArray() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = entra().provision(entraLogin("entra", "sub-t3",
                    Map.of("preferred_username", "+420123456789", "verified_primary_email",
                            List.of("not an email", "Linus@Contoso.com", "second@contoso.com"), "name", "Linus")));

            UserEntity user = savedUser();
            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            assertThat(user.getEmail()).isEqualTo("linus@contoso.com");
            assertThat(createdEvent().details()).containsEntry("emailClaim", "verified_primary_email");
        }

        @Test
        void readsTheVerifiedPrimaryEmailAsAString() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            entra().provision(entraLogin("entra", "sub-t4", Map.of("verified_primary_email", " margaret@contoso.com ")));

            assertThat(savedUser().getEmail()).isEqualTo("margaret@contoso.com");
        }

        @Test
        void readsTheVerifiedPrimaryEmailAsAJavaArray() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            entra().provision(entraLogin("entra", "sub-t5",
                    Map.of("verified_primary_email", new Object[] { 42, "barbara@contoso.com" })));

            assertThat(savedUser().getEmail()).isEqualTo("barbara@contoso.com");
        }

        @Test
        void theEmailClaimComesFirst() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            entra().provision(entraLogin("entra", "sub-t6", Map.of("email", "mail@contoso.com", "preferred_username",
                    "upn@contoso.com", "verified_primary_email", List.of("primary@contoso.com"))));

            assertThat(savedUser().getEmail()).isEqualTo("mail@contoso.com");
        }

        @Test
        void anEmailClaimThatIsNoEmailIsSkipped() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            entra().provision(
                    entraLogin("entra", "sub-t7", Map.of("email", "no-at-sign", "preferred_username", "upn@contoso.com")));

            assertThat(savedUser().getEmail()).isEqualTo("upn@contoso.com");
        }

        @Test
        void withoutAnyUsableEmailTheLoginIsRefusedAsEmailMissing() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = entra().provision(entraLogin("entra", "sub-t8",
                    Map.of("preferred_username", "someone", "verified_primary_email", List.of(), "name", "Someone")));

            assertThat(outcome).isEqualTo(emailMissing());
            verify(users, never()).saveAndFlush(any());
            verify(loginAudit).failed(isNull(), eq("email_missing"), eq("entra"));
        }

        @Test
        void anExplicitlyUnverifiedEmailIsStillRefused() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = entra()
                .provision(entraLogin("entra", "sub-t9", Map.of("email", "t9@contoso.com", "email_verified", false)));

            assertThat(outcome).isEqualTo(notVerified());
            verify(users, never()).saveAndFlush(any());
        }

        @Test
        void onlyTheConfiguredClaimsAreRead() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(true,
                    Map.of("entra", trustIssuer(List.of("email"))))
                .provision(entraLogin("entra", "sub-t10", Map.of("preferred_username", "t10@contoso.com")));

            assertThat(outcome).isEqualTo(emailMissing());
        }

        @Test
        void anEmailOfAnotherUserIsStillNeverLinked() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
            when(users.existsByEmail("admin@contoso.com")).thenReturn(true);

            ExternalProvisioning.Outcome outcome = entra()
                .provision(entraLogin("entra", "sub-t11", Map.of("preferred_username", "Admin@Contoso.com")));

            assertThat(outcome)
                .isEqualTo(new ExternalProvisioning.Denied("email_in_use", OidcLoginDeniedException.EMAIL_IN_USE));
            verify(users, never()).saveAndFlush(any());
        }

        @Test
        void autoProvisioningStillDecides() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(false, Map.of("entra", trustIssuer(ENTRA_CLAIMS)))
                .provision(entraLogin("entra", "sub-t12", Map.of("email", "t12@contoso.com")));

            assertThat(outcome).isEqualTo(failed("not_provisioned"));
        }

        @Test
        void aKnownIdentityLogsInWithoutAnyEmailClaim() {
            UserEntity user = UserEntity.oidc(UUID.randomUUID(), "known@contoso.com", "Known", ISSUER, "sub-t13",
                    time.now());
            when(users.findByOidcIssuerAndOidcSubject(ISSUER, "sub-t13")).thenReturn(Optional.of(user));

            ExternalProvisioning.Outcome outcome = entra().provision(entraLogin("entra", "sub-t13", Map.of()));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(loginAudit).succeeded(user.getId(), "oidc", "entra", java.util.Set.of());
        }
    }

    /** A provider whose new users join a declared company (ADR-0075): its role, and its email domains. */
    @Nested
    class ProviderOfACompany {

        private final UUID companyId = UUID.randomUUID();

        private final String registrationId = "acmesso";

        @Test
        void aNewIdentityBecomesAMemberOfTheCompanyWithTheProvidersRole() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(true, CompanyRole.ADMIN, List.of())
                .provision(companyLogin("sub-c1", Map.of("email", "c1@acme.example", "email_verified", true)));

            UserEntity user = savedUser();
            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships).grant(companyId, user.getId(), CompanyRole.ADMIN, registrationId, RoleSource.APP);
            assertThat(createdEvent().details()).containsEntry("provider", registrationId)
                .containsEntry("companyId", companyId.toString())
                .containsEntry("role", "ADMIN")
                .doesNotContainValue("c1@acme.example");
            verify(loginAudit).succeeded(user.getId(), "oidc", registrationId, java.util.Set.of(companyId));
        }

        @Test
        void anEmailOutsideTheProvidersDomainsIsNeverProvisioned() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
            ExternalProvisioning provisioning = provisioning(true, CompanyRole.MEMBER, List.of("acme.example"));

            // Another domain, a subdomain, and a domain that only ends like the listed one.
            for (String email : List.of("alice@victim.example", "bob@sub.acme.example", "carol@evilacme.example")) {
                ExternalProvisioning.Outcome outcome = provisioning.provision(companyLogin("sub-" + email,
                        Map.of("email", email, "email_verified", true)));
                assertThat(outcome).as(email)
                    .isEqualTo(new ExternalProvisioning.Denied("email_domain_not_allowed",
                            OidcLoginDeniedException.EMAIL_DOMAIN_NOT_ALLOWED));
            }
            verify(users, never()).existsByEmail(anyString());
            verify(users, never()).saveAndFlush(any());
            verify(memberships, never()).grant(any(), any(), any(), any(), any());
            verify(loginAudit, times(3)).failed(null, "email_domain_not_allowed", registrationId);
        }

        @Test
        void aListedDomainMatchesInAnyCase() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(true, CompanyRole.MEMBER, List.of("ACME.example"))
                .provision(companyLogin("sub-c6", Map.of("email", "Cid@ACME.Example", "email_verified", true)));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(savedUser().getId()));
            assertThat(savedUser().getEmail()).isEqualTo("cid@acme.example");
        }

        @Test
        void aKnownIdentityLogsInWithoutAMembershipBeingGrantedAgain() {
            UserEntity user = UserEntity.oidc(UUID.randomUUID(), "known@acme.example", "Known", ISSUER, "sub-c2",
                    time.now());
            when(users.findByOidcIssuerAndOidcSubject(ISSUER, "sub-c2")).thenReturn(Optional.of(user));

            ExternalProvisioning.Outcome outcome = provisioning(true, CompanyRole.MEMBER, List.of("acme.example"))
                .provision(companyLogin("sub-c2", Map.of()));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships, never()).grant(any(), any(), any(), any(), any());
            // The login asserts no email: its company may not make the user its declared owner.
            verify(loginAudit).succeeded(user.getId(), "oidc", registrationId, java.util.Set.of());
        }

        /**
         * A known identity's login names the provider's company for the declared owner only when it asserts the stored
         * email itself, under the registration's email trust and in its domains (ADR-0075).
         */
        @Test
        void aKnownIdentityNamesTheCompanyOnlyWhenTheLoginAssertsItsEmail() {
            UserEntity user = UserEntity.oidc(UUID.randomUUID(), "boss@acme.example", "Boss", ISSUER, "sub-c7",
                    time.now());
            when(users.findByOidcIssuerAndOidcSubject(ISSUER, "sub-c7")).thenReturn(Optional.of(user));
            ExternalProvisioning provisioning = provisioning(true, CompanyRole.MEMBER, List.of("acme.example"));

            provisioning.provision(companyLogin("sub-c7", verified("boss@acme.example")));
            provisioning.provision(companyLogin("sub-c7", verified("other@acme.example")));
            provisioning.provision(companyLogin("sub-c7", Map.of("email", "boss@acme.example")));

            verify(loginAudit).succeeded(user.getId(), "oidc", registrationId, java.util.Set.of(companyId));
            verify(loginAudit, times(2)).succeeded(user.getId(), "oidc", registrationId, java.util.Set.of());
        }

        @Test
        void withoutAutoProvisioningUnknownIdentitiesAreRejected() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = provisioning(false, CompanyRole.MEMBER, List.of())
                .provision(companyLogin("sub-c3", Map.of("email", "c3@acme.example", "email_verified", true)));

            assertThat(outcome).isEqualTo(failed("not_provisioned"));
            verify(users, never()).saveAndFlush(any());
            verify(memberships, never()).grant(any(), any(), any(), any(), any());
        }

        /** ADR-0087, decision 6: the first sign-in binds the identity to the user this company's SCIM created. */
        @Test
        void aFirstSignInBindsTheUserThisCompanysScimCreated() {
            UserEntity user = UserEntity.scim(UUID.randomUUID(), "s1@acme.example", "S1", companyId, time.now());
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
            when(memberships.scimMemberByExternalId(companyId, "sub-s1")).thenReturn(Optional.of(user.getId()));
            when(users.findById(user.getId())).thenReturn(Optional.of(user));

            ExternalProvisioning.Outcome outcome = provisioning(false, CompanyRole.MEMBER, List.of())
                .provision(companyLogin("sub-s1", Map.of()));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            assertThat(user.getAuthSource()).isEqualTo(AuthSource.OIDC);
        }

        /**
         * H1: a user another company's SCIM created, which this company adopted with an {@code externalId} of its
         * choosing, is never bound through this company's provider.
         */
        @Test
        void aUserAnotherCompanysScimCreatedIsNeverBound() {
            UserEntity user = UserEntity.scim(UUID.randomUUID(), "s2@globex.example", "S2", UUID.randomUUID(),
                    time.now());
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
            when(memberships.scimMemberByExternalId(companyId, "sub-s2")).thenReturn(Optional.of(user.getId()));
            when(users.findById(user.getId())).thenReturn(Optional.of(user));

            ExternalProvisioning.Outcome outcome = provisioning(false, CompanyRole.MEMBER, List.of())
                .provision(companyLogin("sub-s2", Map.of()));

            assertThat(outcome).isEqualTo(failed("not_provisioned"));
            assertThat(user.getAuthSource()).isEqualTo(AuthSource.SCIM);
            verify(auditLog, never()).record(any());
        }

        /** M16: an instance admin is never bound by a SCIM token's {@code externalId}, as an OWNER is not. */
        @Test
        void anInstanceAdminIsNeverBound() {
            UserEntity user = UserEntity.scim(UUID.randomUUID(), "s3@acme.example", "S3", companyId, time.now());
            user.grantInstanceAdmin(true, time.now());
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
            when(memberships.scimMemberByExternalId(companyId, "sub-s3")).thenReturn(Optional.of(user.getId()));
            when(users.findById(user.getId())).thenReturn(Optional.of(user));

            ExternalProvisioning.Outcome outcome = provisioning(false, CompanyRole.MEMBER, List.of())
                .provision(companyLogin("sub-s3", Map.of()));

            assertThat(outcome).isEqualTo(failed("not_provisioned"));
            assertThat(user.getAuthSource()).isEqualTo(AuthSource.SCIM);
        }

        private ExternalProvisioning provisioning(boolean autoProvision, CompanyRole role, List<String> domains) {
            return ExternalProvisioningTest.this.provisioning(autoProvision, Map.of(registrationId,
                    new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM, List.of("email"), "acme", role, domains, null, null,
                            List.of(), List.of(), List.of())));
        }

        private static Map<String, Object> verified(String email) {
            return Map.of("email", email, "email_verified", true);
        }

        private ExternalProvisioning.OidcLogin companyLogin(String subject, Map<String, Object> claims) {
            return new ExternalProvisioning.OidcLogin(registrationId, ISSUER, subject, claims, companyId);
        }
    }

    /** A provider of a company that decides its members' roles (ADR-0088). */
    @Nested
    class ProviderThatDecidesRoles {

        private final UUID companyId = UUID.randomUUID();

        private final String registrationId = "acmesso";

        @Test
        void aNewIdentityJoinsWithTheMappedRoleWhichTheProviderDecides() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = deciding(null).provision(companyLogin("sub-r1",
                    Map.of("email", "r1@acme.example", "email_verified", true, "groups", List.of("staff", "admins"))));

            UserEntity user = savedUser();
            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships).grant(companyId, user.getId(), CompanyRole.ADMIN, registrationId,
                    RoleSource.provider(registrationId));
            assertThat(createdEvent().details()).containsEntry("role", "ADMIN");
        }

        @Test
        void aNewIdentityWhoseClaimMapsToNoRoleIsRefusedAndNotCreated() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = deciding(null).provision(companyLogin("sub-r2",
                    Map.of("email", "r2@acme.example", "email_verified", true, "groups", List.of("contractors"))));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Denied("role_not_mapped",
                    OidcLoginDeniedException.ROLE_NOT_MAPPED));
            verify(users, never()).saveAndFlush(any());
            verify(loginAudit).failed(null, "role_not_mapped", registrationId);
        }

        @Test
        void anUnmappedRoleIsRefusedBeforeTheEmailIsLookedUp() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
            when(users.existsByEmail("taken@acme.example")).thenReturn(true);

            ExternalProvisioning.Outcome outcome = deciding(null).provision(companyLogin("sub-r9",
                    Map.of("email", "taken@acme.example", "email_verified", true, "groups", List.of("contractors"))));

            // The same answer whether or not the address has an account.
            assertThat(outcome).isEqualTo(new ExternalProvisioning.Denied("role_not_mapped",
                    OidcLoginDeniedException.ROLE_NOT_MAPPED));
            verify(users, never()).existsByEmail(anyString());
        }

        @Test
        void aKnownIdentityWhoIsNoMemberJoinsWhenTheClaimNamesThem() {
            UserEntity user = known("sub-r10");
            when(memberships.applyProviderRole(companyId, user.getId(), CompanyRole.MEMBER, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.NOT_A_MEMBER);

            ExternalProvisioning.Outcome outcome = deciding(null).provision(companyLogin("sub-r10",
                    Map.of("groups", "staff")));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships).grant(companyId, user.getId(), CompanyRole.MEMBER, registrationId,
                    RoleSource.provider(registrationId));
        }

        @Test
        void aKnownIdentityGetsTheProvidersRoleAtEverySignIn() {
            UserEntity user = known("sub-r3");
            when(memberships.applyProviderRole(companyId, user.getId(), CompanyRole.VIEWER, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.APPLIED);

            ExternalProvisioning.Outcome outcome = deciding(null).provision(companyLogin("sub-r3",
                    Map.of("groups", "viewers")));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships).applyProviderRole(companyId, user.getId(), CompanyRole.VIEWER, registrationId);
            verify(memberships, never()).grant(any(), any(), any(), any(), any());
        }

        @Test
        void aKnownIdentityWhoseClaimMapsToNoRoleIsRefusedAndKeepsTheMembership() {
            UserEntity user = known("sub-r4");
            when(memberships.applyProviderRole(companyId, user.getId(), null, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.NOT_MAPPED);

            ExternalProvisioning.Outcome outcome = deciding(null).provision(companyLogin("sub-r4", Map.of()));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Denied("role_not_mapped",
                    OidcLoginDeniedException.ROLE_NOT_MAPPED));
            assertThat(user.getLastLoginAt()).isNull();
            verify(loginAudit).failed(user.getId(), "role_not_mapped", registrationId);
            verify(loginAudit, never()).succeeded(any(), any(), any(), any());
        }

        @Test
        void anOwnerOrAScimMembershipIsNeverRefused() {
            UserEntity user = known("sub-r5");
            when(memberships.applyProviderRole(companyId, user.getId(), null, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.KEPT);

            ExternalProvisioning.Outcome outcome = deciding(null).provision(companyLogin("sub-r5", Map.of()));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
        }

        @Test
        void underAppTheMappedRoleIsGivenOnceElseTheProvidersRole() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
            ExternalProvisioning app = deciding(ProviderRoles.Mode.APP);

            app.provision(companyLogin("sub-r6", Map.of("email", "r6@acme.example", "email_verified", true,
                    "groups", "staff")));
            app.provision(companyLogin("sub-r7", Map.of("email", "r7@acme.example", "email_verified", true)));

            ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
            verify(users, times(2)).saveAndFlush(saved.capture());
            verify(memberships).grant(companyId, saved.getAllValues().get(0).getId(), CompanyRole.MEMBER,
                    registrationId, RoleSource.APP);
            verify(memberships).grant(companyId, saved.getAllValues().get(1).getId(), CompanyRole.VIEWER,
                    registrationId, RoleSource.APP);
        }

        @Test
        void underAppAKnownIdentityOnlySignsIn() {
            UserEntity user = known("sub-r8");

            ExternalProvisioning.Outcome outcome = deciding(ProviderRoles.Mode.APP)
                .provision(companyLogin("sub-r8", Map.of()));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships, never()).applyProviderRole(any(), any(), any(), any());
        }

        private UserEntity known(String subject) {
            UserEntity user = UserEntity.oidc(UUID.randomUUID(), subject + "@acme.example", "Known", ISSUER, subject,
                    time.now());
            when(users.findByOidcIssuerAndOidcSubject(ISSUER, subject)).thenReturn(Optional.of(user));
            return user;
        }

        /** Maps {@code admins}, {@code staff} and {@code viewers}; {@code _ROLE} is VIEWER, for APP's fallback. */
        private ExternalProvisioning deciding(ProviderRoles.Mode source) {
            return ExternalProvisioningTest.this.provisioning(true, Map.of(registrationId,
                    new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM, List.of("email"), "acme", CompanyRole.VIEWER,
                            List.of(), source, "groups", List.of("admins"), List.of("staff"), List.of("viewers"))));
        }

        private ExternalProvisioning.OidcLogin companyLogin(String subject, Map<String, Object> claims) {
            return new ExternalProvisioning.OidcLogin(registrationId, ISSUER, subject, claims, companyId);
        }
    }

    /** A provider whose claim fills a further company beside its own (ADR-0089). */
    @Nested
    class ProviderOfSeveralCompanies {

        private final UUID acme = UUID.randomUUID();

        private final UUID globex = UUID.randomUUID();

        private final String registrationId = "groupsso";

        private final ProviderCompanies companies = new ProviderCompanies(acme, Map.of(globex,
                new ProviderRoles.RoleMap(List.of("globex-admins"), List.of("globex-users"), List.of())));

        @Test
        void aNewIdentityJoinsEachCompanyTheClaimNames() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            ExternalProvisioning.Outcome outcome = deciding(null).provision(login("sub-m1", Map.of("email",
                    "m1@acme.example", "email_verified", true, "groups", List.of("staff", "globex-admins"))));

            UserEntity user = savedUser();
            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships).grant(acme, user.getId(), CompanyRole.MEMBER, registrationId,
                    RoleSource.provider(registrationId));
            verify(memberships).grant(globex, user.getId(), CompanyRole.ADMIN, registrationId,
                    RoleSource.provider(registrationId));
            assertThat(createdEvent().details()).containsEntry("companyIds", List.of(acme.toString(),
                    globex.toString())).doesNotContainKey("role");
            // Both companies may make them their declared owner: the login asserted the email.
            verify(loginAudit).succeeded(user.getId(), "oidc", registrationId, java.util.Set.of(acme, globex));
        }

        @Test
        void aNewIdentityNamedInOneCompanyJoinsThatOneOnly() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());

            deciding(null).provision(login("sub-m2", Map.of("email", "m2@acme.example", "email_verified", true,
                    "groups", List.of("globex-users"))));

            UserEntity user = savedUser();
            verify(memberships).grant(globex, user.getId(), CompanyRole.MEMBER, registrationId,
                    RoleSource.provider(registrationId));
            verify(memberships, never()).grant(eq(acme), any(), any(), any(), any());
            assertThat(createdEvent().details()).containsEntry("companyId", globex.toString())
                .containsEntry("role", "MEMBER");
        }

        @Test
        void aKnownIdentityFollowsTheClaimIntoAndOutOfEachCompany() {
            UserEntity user = known("sub-m3");
            // A member of acme already; no member of globex yet.
            when(memberships.applyProviderRole(acme, user.getId(), CompanyRole.MEMBER, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.APPLIED);
            when(memberships.applyProviderRole(globex, user.getId(), CompanyRole.ADMIN, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.NOT_A_MEMBER);

            ExternalProvisioning.Outcome joined = deciding(null).provision(login("sub-m3", Map.of("groups",
                    List.of("staff", "globex-admins"))));

            assertThat(joined).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships).grant(globex, user.getId(), CompanyRole.ADMIN, registrationId,
                    RoleSource.provider(registrationId));
            verify(memberships, never()).leave(any(), any(), any());

            // Out of acme's groups at the next sign-in: a departure there, the role in globex applied.
            when(memberships.leave(acme, user.getId(), registrationId))
                .thenReturn(ProvisionedMemberships.Departure.LEFT);
            when(memberships.applyProviderRole(globex, user.getId(), CompanyRole.ADMIN, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.APPLIED);

            ExternalProvisioning.Outcome left = deciding(null).provision(login("sub-m3", Map.of("groups",
                    List.of("globex-admins"))));

            assertThat(left).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships).leave(acme, user.getId(), registrationId);
            verify(memberships, never()).applyProviderRole(eq(acme), any(), isNull(), any());
        }

        @Test
        void aClaimThatNamesNoCompanyRefusesTheSignInAndEndsNothing() {
            UserEntity user = known("sub-m4");
            when(memberships.applyProviderRole(any(), eq(user.getId()), isNull(), eq(registrationId)))
                .thenReturn(ProvisionedMemberships.ProviderRole.NOT_MAPPED);

            ExternalProvisioning.Outcome outcome = deciding(null).provision(login("sub-m4", Map.of("groups",
                    List.of("contractors"))));

            assertThat(outcome).isEqualTo(new ExternalProvisioning.Denied("role_not_mapped",
                    OidcLoginDeniedException.ROLE_NOT_MAPPED));
            verify(memberships, never()).leave(any(), any(), any());
            verify(memberships, never()).grant(any(), any(), any(), any(), any());
        }

        @Test
        void anOwnerOfOneCompanyIsNeverRefusedNorRemovedFromIt() {
            UserEntity user = known("sub-m5");
            when(memberships.applyProviderRole(acme, user.getId(), null, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.KEPT);
            when(memberships.applyProviderRole(globex, user.getId(), null, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.NOT_MAPPED);

            assertThat(deciding(null).provision(login("sub-m5", Map.of())))
                .isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships, never()).leave(any(), any(), any());

            // Named in globex only: the departure from acme is the port's to refuse for an OWNER.
            when(memberships.leave(acme, user.getId(), registrationId))
                .thenReturn(ProvisionedMemberships.Departure.KEPT);
            when(memberships.applyProviderRole(globex, user.getId(), CompanyRole.MEMBER, registrationId))
                .thenReturn(ProvisionedMemberships.ProviderRole.APPLIED);
            assertThat(deciding(null).provision(login("sub-m5", Map.of("groups", "globex-users"))))
                .isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships).leave(acme, user.getId(), registrationId);
        }

        @Test
        void underAppANewIdentityJoinsTheNamedCompaniesOnceAndItsOwnWithTheProvidersRole() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
            ExternalProvisioning app = deciding(ProviderRoles.Mode.APP);

            app.provision(login("sub-m6", Map.of("email", "m6@acme.example", "email_verified", true, "groups",
                    List.of("globex-users"))));
            UserEntity user = savedUser();
            verify(memberships).grant(acme, user.getId(), CompanyRole.VIEWER, registrationId, RoleSource.APP);
            verify(memberships).grant(globex, user.getId(), CompanyRole.MEMBER, registrationId, RoleSource.APP);

            UserEntity knownUser = known("sub-m7");
            app.provision(login("sub-m7", Map.of("groups", List.of("globex-admins"))));
            verify(memberships, never()).grant(any(), eq(knownUser.getId()), any(), any(), any());
            verify(memberships, never()).applyProviderRole(any(), any(), any(), any());
        }

        @Test
        void aProviderWithoutAnOwnCompanyFillsTheClaimsCompaniesAlone() {
            when(users.findByOidcIssuerAndOidcSubject(anyString(), anyString())).thenReturn(Optional.empty());
            ProviderCompanies globexOnly = new ProviderCompanies(null, companies.mapped());

            ExternalProvisioning.Outcome outcome = ExternalProvisioningTest.this.provisioning(true, Map.of(
                    registrationId, new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM, List.of("email"), null,
                            CompanyRole.MEMBER, List.of(), null, "groups", List.of(), List.of(), List.of())
                        .withCompanies(Map.of("globex", new AuthProperties.CompanyRoles(List.of("globex-admins"),
                                List.of("globex-users"), List.of())))))
                .provision(new ExternalProvisioning.OidcLogin(registrationId, ISSUER, "sub-m8", Map.of("email",
                        "m8@acme.example", "email_verified", true, "groups", List.of("globex-users")), globexOnly,
                        null));

            UserEntity user = savedUser();
            assertThat(outcome).isEqualTo(new ExternalProvisioning.Granted(user.getId()));
            verify(memberships).grant(globex, user.getId(), CompanyRole.MEMBER, registrationId,
                    RoleSource.provider(registrationId));
            verify(memberships, never()).grant(eq(acme), any(), any(), any(), any());
        }

        private UserEntity known(String subject) {
            UserEntity user = UserEntity.oidc(UUID.randomUUID(), subject + "@acme.example", "Known", ISSUER, subject,
                    time.now());
            when(users.findByOidcIssuerAndOidcSubject(ISSUER, subject)).thenReturn(Optional.of(user));
            return user;
        }

        /** Its own company acme maps {@code staff}; globex maps its own groups; {@code _ROLE} is VIEWER. */
        private ExternalProvisioning deciding(ProviderRoles.Mode source) {
            return ExternalProvisioningTest.this.provisioning(true, Map.of(registrationId,
                    new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM, List.of("email"), "acme", CompanyRole.VIEWER,
                            List.of(), source, "groups", List.of(), List.of("staff"), List.of())
                        .withCompanies(Map.of("globex", new AuthProperties.CompanyRoles(List.of("globex-admins"),
                                List.of("globex-users"), List.of())))));
        }

        private ExternalProvisioning.OidcLogin login(String subject, Map<String, Object> claims) {
            return new ExternalProvisioning.OidcLogin(registrationId, ISSUER, subject, claims, companies, null);
        }
    }

    private ExternalProvisioning provisioning(boolean autoProvision) {
        return provisioning(autoProvision, Map.of());
    }

    private ExternalProvisioning provisioning(boolean autoProvision, Map<String, OidcProvider> providers) {
        AuthProperties.Limit limit = new AuthProperties.Limit(5, Duration.ofMinutes(1));
        AuthProperties properties = new AuthProperties(true, autoProvision,
                new AuthProperties.LoginThrottleLimits(limit, limit), new AuthProperties.Oidc(providers));
        return new ExternalProvisioning(users, loginAudit, auditLog, properties, time, memberships);
    }

    private ExternalProvisioning entra() {
        return provisioning(true, Map.of("entra", trustIssuer(ENTRA_CLAIMS)));
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

    private static OidcProvider trustIssuer(List<String> emailClaims) {
        return new OidcProvider(EmailTrust.TRUST_ISSUER, emailClaims, null, CompanyRole.MEMBER, List.of(), null,
                null, List.of(), List.of(), List.of());
    }

    private static ExternalProvisioning.Denied failed(String reason) {
        return new ExternalProvisioning.Denied(reason, OidcLoginDeniedException.OIDC_LOGIN_FAILED);
    }

    private static ExternalProvisioning.Denied notVerified() {
        return new ExternalProvisioning.Denied("email_not_verified", OidcLoginDeniedException.EMAIL_NOT_VERIFIED);
    }

    private static ExternalProvisioning.Denied emailMissing() {
        return new ExternalProvisioning.Denied("email_missing", OidcLoginDeniedException.EMAIL_MISSING);
    }

    /** Keycloak style: {@code email} and {@code email_verified}. */
    private static ExternalProvisioning.OidcLogin login(String subject, String email, boolean verified, String name) {
        Map<String, Object> claims = name == null ? Map.of("email", email, "email_verified", verified)
                : Map.of("email", email, "email_verified", verified, "name", name);
        return new ExternalProvisioning.OidcLogin("acme", ISSUER, subject, claims);
    }

    private static ExternalProvisioning.OidcLogin entraLogin(String provider, String subject, Map<String, Object> claims) {
        return new ExternalProvisioning.OidcLogin(provider, ISSUER, subject, claims);
    }
}
