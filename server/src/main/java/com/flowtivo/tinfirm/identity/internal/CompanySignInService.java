package com.flowtivo.tinfirm.identity.internal;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.flowtivo.tinfirm.identity.ProvisionedMemberships;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.CertificateInfo;
import com.flowtivo.tinfirm.shared.api.model.CompanySignIn;
import com.flowtivo.tinfirm.shared.api.model.CompanySignInProvider;
import com.flowtivo.tinfirm.shared.api.model.CompanySignInRoleMap;
import com.flowtivo.tinfirm.shared.api.model.ProviderKind;
import com.flowtivo.tinfirm.shared.api.model.Role;
import com.flowtivo.tinfirm.shared.api.model.SamlProviderDetails;

/**
 * A company's sign-in as the deployment declares it (ADR-0086, decision 15): the OIDC and SAML providers that fill it,
 * as their own company or through their role claim (ADR-0089), read-only, for Settings → Sign-in.
 * {@code MANAGE_MEMBERS}: OWNER, ADMIN, and an instance admin's ADMIN view.
 */
@Service
class CompanySignInService {

    private final CompanyAccessGuard companyAccess;

    private final AuthProperties properties;

    private final TinfirmClientRegistrations oidc;

    private final SamlSettings saml;

    private final ObjectProvider<SamlLogin> samlLogin;

    private final ProvisionedMemberships memberships;

    CompanySignInService(CompanyAccessGuard companyAccess, AuthProperties properties, TinfirmClientRegistrations oidc,
            SamlSettings saml, ObjectProvider<SamlLogin> samlLogin, ProvisionedMemberships memberships) {
        this.companyAccess = companyAccess;
        this.properties = properties;
        this.oidc = oidc;
        this.saml = saml;
        this.samlLogin = samlLogin;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public CompanySignIn describe(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        String base = saml.publicBaseUrl();
        List<CompanySignInProvider> providers = new ArrayList<>();
        for (ClientRegistration registration : oidc) {
            String id = registration.getRegistrationId();
            AuthProperties.OidcProvider settings = properties.oidc().provider(id);
            ProviderCompanies companies = ProviderCompanies.resolve(memberships, settings.company(), settings.roles());
            if (!companies.fills(companyId)) {
                continue;
            }
            String name = StringUtils.hasText(registration.getClientName()) ? registration.getClientName() : id;
            providers.add(provider(id, name, ProviderKind.OIDC, settings.role(), settings.roles(), companies,
                    companyId, settings.emailDomains(), base));
        }
        SamlLogin login = samlLogin.getIfAvailable();
        boolean served = login != null && login.served();
        if (saml.declared() && login != null) {
            Map<String, SamlLogin.ProviderState> states = login.providersOf(companyId)
                .stream()
                .collect(Collectors.toMap(SamlLogin.ProviderState::id, Function.identity()));
            saml.properties().providers().forEach((id, settings) -> {
                SamlLogin.ProviderState state = states.get(id);
                if (state == null) {
                    return;
                }
                providers.add(provider(id, state.name(), ProviderKind.SAML, settings.role(), settings.roles(),
                        saml.companiesOf(id), companyId, settings.emailDomains(), base).saml(details(state)));
            });
        }
        providers.sort(Comparator.comparing(CompanySignInProvider::getId));
        return new CompanySignIn(providers, served);
    }

    /**
     * A provider as the company's page shows it: how it fills this company, as its own ({@code COMPANY}) or through
     * its claim ({@code CLAIM}, ADR-0089), and the claim's values that give each role here.
     */
    private static CompanySignInProvider provider(String id, String name, ProviderKind kind,
            com.flowtivo.tinfirm.shared.access.CompanyRole role, ProviderRoles roles, ProviderCompanies companies,
            UUID companyId, List<String> domains, String base) {
        boolean own = companyId.equals(companies.home());
        ProviderRoles.RoleMap map = own ? roles.home() : companies.mapped().get(companyId);
        return new CompanySignInProvider(id, name, kind,
                own ? CompanySignInProvider.ThroughEnum.COMPANY : CompanySignInProvider.ThroughEnum.CLAIM,
                Role.fromValue(role.name()), CompanySignInProvider.RoleSourceEnum.fromValue(roles.mode().name()),
                domains, base + "/login?provider=" + id)
            .roleClaim(roles.claim())
            .roleMap(roles.claim() == null || map == null ? null
                    : new CompanySignInRoleMap(map.admin(), map.member(), map.viewer()));
    }

    private static SamlProviderDetails details(SamlLogin.ProviderState state) {
        return new SamlProviderDetails(state.spEntityId(), state.acsUrl(), state.spMetadataUrl(), state.startUrl(),
                state.metadataSource(), certificates(state.idpCertificates()), certificates(state.spCertificates()))
            .idpEntityId(state.idpEntityId())
            .metadataReadAt(utc(state.metadataReadAt()))
            .lastError(state.lastError())
            .lastErrorAt(utc(state.lastErrorAt()))
            .validUntil(utc(state.validUntil()));
    }

    private static List<CertificateInfo> certificates(List<SamlLogin.Certificate> certificates) {
        return certificates.stream()
            .map(certificate -> new CertificateInfo(certificate.subject(), certificate.sha256(),
                    certificate.notAfter().atOffset(ZoneOffset.UTC)))
            .toList();
    }

    private static java.time.@Nullable OffsetDateTime utc(java.time.@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
