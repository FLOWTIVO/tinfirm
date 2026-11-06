package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.flowtivo.tinfirm.shared.security.SystemContext;

/**
 * Loads the OIDC user from the IdP, then maps it to a Tinfirm user with {@link ExternalProvisioning}. A provider that
 * fills companies (ADR-0075, ADR-0089) is provisioned as actor SYSTEM in the tenant context of its company, which the
 * server resolves from the deployment's settings, never from the request; the memberships it may grant or end are
 * those of the provider's companies only, each entered by the company module from the same settings.
 */
final class TinfirmOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();

    private final ExternalProvisioning provisioning;

    private final Function<String, ProviderCompanies> companiesOf;

    TinfirmOidcUserService(ExternalProvisioning provisioning) {
        this(provisioning, registrationId -> ProviderCompanies.NONE);
    }

    /** @param companiesOf the companies a registration fills, by registration id */
    TinfirmOidcUserService(ExternalProvisioning provisioning, Function<String, ProviderCompanies> companiesOf) {
        this.provisioning = provisioning;
        this.companiesOf = companiesOf;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        String registrationId = request.getClientRegistration().getRegistrationId();
        OidcUser oidcUser = delegate.loadUser(request);
        ProviderCompanies companies = companiesOf.apply(registrationId);
        UUID companyId = companies.context();
        // The claims of the ID token and, when the IdP has a userinfo endpoint, of the userinfo response. The Entra
        // tenant and object come from the ID token itself, never from the merged claims (the package 5 contract, §3.8).
        String issuer = oidcUser.getIdToken().getClaimAsString(IdTokenClaimNames.ISS);
        ExternalProvisioning.OidcLogin login = new ExternalProvisioning.OidcLogin(registrationId, issuer,
                oidcUser.getSubject(), oidcUser.getClaims(), companies,
                ExternalProvisioning.EntraIdentity.of(issuer, oidcUser.getIdToken().getClaims()));

        Supplier<ExternalProvisioning.Outcome> provision = companyId == null ? () -> provisioning.provision(login)
                : () -> SystemContext.callInCompany(companyId, () -> provisioning.provision(login));
        ExternalProvisioning.Outcome outcome;
        try {
            outcome = provision.get();
        }
        catch (DataIntegrityViolationException concurrentFirstLogin) {
            // Another request provisioned the same identity or email concurrently: decide again on committed data.
            outcome = provision.get();
        }

        return switch (outcome) {
            case ExternalProvisioning.Granted granted -> new TinfirmOidcUser(granted.userId(), oidcUser.getAuthorities(),
                    oidcUser.getIdToken(), oidcUser.getUserInfo());
            case ExternalProvisioning.Denied denied -> throw new OidcLoginDeniedException(denied.code(), denied.reason());
        };
    }
}
