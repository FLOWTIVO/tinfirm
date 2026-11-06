package com.flowtivo.tinfirm.identity.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.identity.ProvisionedMemberships;

/**
 * The providers' role sources at the start (ADR-0088). The server does not start on role settings it cannot apply or
 * on two providers that decide one company's roles; a provider without {@code _ROLE_CLAIM} runs as {@code APP}, which
 * the start logs once. Once the server is ready, every membership whose role source is a provider that no longer
 * decides it (switched to {@code APP}, removed, or bound to another company) becomes the app's (decision 7).
 */
@Component
class ProviderRoleSources {

    private static final Logger log = LoggerFactory.getLogger(ProviderRoleSources.class);

    private final ProvisionedMemberships memberships;

    private final Map<String, Set<String>> deciding;

    ProviderRoleSources(AuthProperties properties, SamlProperties saml, ProvisionedMemberships memberships,
            TinfirmClientRegistrations registrations) {
        this.memberships = memberships;
        this.deciding = withSaml(properties.oidc().verifyRoleSources(memberships::companyKeyFor), saml,
                memberships::companyKeyFor);
        List<String> appOnly = new java.util.ArrayList<>();
        for (ClientRegistration registration : registrations) {
            String id = registration.getRegistrationId();
            if (properties.oidc().provider(id).roles().claim() == null) {
                appOnly.add(id);
            }
        }
        if (!appOnly.isEmpty()) {
            log.info("The OIDC providers {} name no role claim: their members' roles stay the app's until "
                    + "TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_CLAIM is set (ADR-0088)", appOnly.stream().sorted().toList());
        }
    }

    /**
     * The deciding providers of both kinds: the SAML ones that decide roles join the OIDC ones (ADR-0086, decision 6),
     * each with the keys of the companies it decides (ADR-0089), and two that decide one company's roles stop the
     * start, whatever their kinds. {@link SamlSettings} reports the role settings a SAML provider cannot apply.
     */
    static Map<String, Set<String>> withSaml(Map<String, Set<String>> oidcDeciding, SamlProperties saml,
            java.util.function.Function<@org.jspecify.annotations.Nullable String, java.util.Optional<String>> companyKey) {
        Map<String, Set<String>> deciding = new java.util.TreeMap<>(oidcDeciding);
        saml.providers().forEach((id, provider) -> {
            String homeKey = companyKey.apply(provider.company()).orElse(null);
            if (provider.problems(homeKey).isEmpty() && provider.roles().decides()) {
                Set<String> keys = provider.companyKeys(homeKey);
                if (!keys.isEmpty()) {
                    deciding.put(id, keys);
                }
            }
        });
        AuthProperties.Oidc.verifyOneDecidingProviderPerCompany(deciding,
                "TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_SOURCE or TINFIRM_AUTH_SAML_PROVIDERS_<ID>_ROLE_SOURCE");
        return deciding;
    }

    @EventListener(ApplicationReadyEvent.class)
    void releaseOthers() {
        try {
            memberships.releaseProviderRoles(deciding);
        }
        catch (RuntimeException ex) {
            // The next start tries again; a sign-in through a provider that no longer decides changes no role meanwhile.
            log.warn("The memberships of providers that no longer decide roles could not be released: {}",
                    ex.toString());
        }
    }
}
