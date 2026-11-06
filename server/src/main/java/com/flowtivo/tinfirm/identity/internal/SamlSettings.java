package com.flowtivo.tinfirm.identity.internal;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.identity.ProvisionedMemberships;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The SAML providers the deployment declares, checked at the start (ADR-0086, decision 1), for {@code ee/}'s SAML
 * login. The server does not start on a provider whose id an OIDC provider also uses or that is not lower-case letters
 * and digits, of an undeclared company, with role settings it cannot apply (ADR-0088), on SAML providers without a
 * service provider key ({@link SamlSpCredentials}), with an {@code _ENTITY_ID} that another provider reading other
 * metadata or an OIDC provider's issuer also names (H4 of the security review of 2026-10-27), or with a public URL
 * that is not https except on localhost: the browser sends the binding cookie, {@code SameSite=None}, over https only.
 * The metadata itself is {@code ee/}'s to read and check, the entity ID it names included.
 */
@Component
public class SamlSettings {

    private static final Logger log = LoggerFactory.getLogger(SamlSettings.class);

    private final SamlProperties properties;

    private final ProvisionedMemberships memberships;

    private final @Nullable SamlSpCredentials credentials;

    private final String publicBaseUrl;

    private final Set<String> oidcIssuers;

    SamlSettings(SamlProperties properties, TinfirmClientRegistrations oidc, ProvisionedMemberships memberships,
            TinfirmProperties tinfirm, TimeSource time) {
        this.properties = properties;
        this.memberships = memberships;
        this.publicBaseUrl = tinfirm.publicBaseUrl();
        Set<String> issuers = new java.util.TreeSet<>();
        oidc.forEach(registration -> {
            String issuer = registration.getProviderDetails().getIssuerUri();
            if (issuer != null) {
                issuers.add(issuer);
            }
        });
        this.oidcIssuers = java.util.Collections.unmodifiableSet(issuers);
        if (properties.providers().isEmpty()) {
            this.credentials = null;
            return;
        }
        List<String> problems = new java.util.ArrayList<>();
        properties.providers().forEach((id, provider) -> {
            String setting = "TINFIRM_AUTH_SAML_PROVIDERS_" + id.toUpperCase(Locale.ROOT);
            if (!SamlProperties.ID.matcher(id).matches() || id.length() > 64) {
                problems.add(setting + ": a SAML provider's id is 1 to 64 lower-case letters and digits");
            }
            if (oidc.isInstanceRegistration(id)) {
                problems.add(setting + ": an OIDC provider has the id " + id + " too; ids are unique across both kinds");
            }
            if (provider.company() != null && !memberships.declares(provider.company())) {
                problems.add(setting + "_COMPANY names " + provider.company() + ", which is not declared: the company "
                        + "must be a key of tinfirm.companies (TINFIRM_COMPANIES_<KEY>_NAME)");
            }
            provider.roles().companies().keySet().stream().filter(key -> !memberships.declares(key)).forEach(key ->
                problems.add(setting + "_COMPANIES_" + key.toUpperCase(Locale.ROOT) + "_ROLE_MAP_* names " + key
                        + ", which is not declared: the company must be a key of tinfirm.companies "
                        + "(TINFIRM_COMPANIES_<KEY>_NAME)"));
            provider.problems(memberships.companyKeyFor(provider.company()).orElse(null))
                .forEach(problem -> problems.add(setting + "_" + problem));
        });
        properties.entityIdConflicts(properties.configuredEntityIds(), oidcIssuers)
            .forEach(conflict -> problems.add("TINFIRM_AUTH_SAML_PROVIDERS_<ID>_ENTITY_ID: " + conflict));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid SAML providers (ADR-0086): " + String.join("; ", problems));
        }
        URI publicUrl = tinfirm.publicUrl();
        if (!"https".equalsIgnoreCase(publicUrl.getScheme()) && !"localhost".equalsIgnoreCase(publicUrl.getHost())) {
            throw new IllegalStateException("SAML sign-in needs an https TINFIRM_PUBLIC_URL (localhost aside): the "
                    + "browser sends its SameSite=None binding cookie over https only (ADR-0086, decision 4)");
        }
        this.credentials = SamlSpCredentials.read(properties.sp(), time.now());
        if (credentials.activeEndsSoon(time.now())) {
            log.warn("The active SAML service provider certificate {} ends on {}: add the next credential, let the "
                    + "identity providers read the metadata again, then make it active (ADR-0086)",
                    credentials.active().kid(), credentials.active().certificate().getNotAfter().toInstant());
        }
    }

    /** Whether the deployment declares SAML providers. */
    public boolean declared() {
        return !properties.providers().isEmpty();
    }

    public SamlProperties properties() {
        return properties;
    }

    /** The service provider's keys; only when {@link #declared()}. */
    public SamlSpCredentials credentials() {
        if (credentials == null) {
            throw new IllegalStateException("No SAML provider is declared");
        }
        return credentials;
    }

    /** The issuers of the OIDC registrations, which no SAML provider's entity ID may be. */
    public Set<String> oidcIssuers() {
        return oidcIssuers;
    }

    /** {@code TINFIRM_PUBLIC_URL} without a trailing slash. */
    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    /**
     * The companies a provider fills (ADR-0075, ADR-0089), resolved from the deployment: its own, and those its role
     * attribute fills; none for an unknown provider.
     */
    ProviderCompanies companiesOf(String registrationId) {
        SamlProperties.Provider provider = properties.provider(registrationId);
        return provider == null ? ProviderCompanies.NONE
                : ProviderCompanies.resolve(memberships, provider.company(), provider.roles());
    }

    /** Whether the provider fills this company: its own, or one its role attribute fills (ADR-0089). */
    public boolean fills(String registrationId, UUID companyId) {
        return companiesOf(registrationId).fills(companyId);
    }
}
