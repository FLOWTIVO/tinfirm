package com.flowtivo.tinfirm.identity.internal;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * @param localEnabled      {@code TINFIRM_AUTH_LOCAL_ENABLED}
 * @param oidcAutoProvision {@code TINFIRM_AUTH_OIDC_AUTO_PROVISION}
 * @param loginThrottle     per-email and per-IP login limits (in memory, per replica)
 * @param oidc              settings per OIDC registration ({@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_*})
 */
@Validated
@ConfigurationProperties("tinfirm.auth")
record AuthProperties(boolean localEnabled, boolean oidcAutoProvision, @Valid @NotNull LoginThrottleLimits loginThrottle,
        @Valid @DefaultValue Oidc oidc) {

    record LoginThrottleLimits(@Valid @NotNull Limit perEmail, @Valid @NotNull Limit perIp) {
    }

    /** At most {@code capacity} attempts, refilled gradually over {@code period}. */
    record Limit(@Positive int capacity, @NotNull Duration period) {
    }

    /**
     * @param providers settings keyed by registration id, the {@code <id>} of
     *                  {@code spring.security.oauth2.client.registration.<id>}. A registration without an entry gets
     *                  {@link OidcProvider#DEFAULT}.
     */
    record Oidc(@DefaultValue Map<String, @Valid OidcProvider> providers) {

        OidcProvider provider(String registrationId) {
            return providers.getOrDefault(registrationId, OidcProvider.DEFAULT);
        }

        /**
         * Fails when an entry names no OIDC registration. A misspelt id would otherwise leave that registration on the
         * default without a sign.
         */
        void verifyRegistrations(Predicate<String> registered) {
            List<String> unknown = providers.keySet().stream().filter(registered.negate()).sorted().toList();
            if (!unknown.isEmpty()) {
                throw new IllegalStateException("tinfirm.auth.oidc.providers names no OIDC registration for " + unknown
                        + ": the ids must match spring.security.oauth2.client.registration.<id>");
            }
        }

        /**
         * Fails on role settings a provider cannot apply (ADR-0088, ADR-0089), naming each setting, and when two
         * providers that decide roles give the roles of one company: the same identity could sign in through either,
         * and each would undo the other's role. Also fails on a deciding provider whose registration id a membership's
         * role source cannot hold, on a deciding provider of a company without a map for it, and on a provider whose
         * {@code _COMPANIES_<KEY>_*} names its own company.
         *
         * @param companyKey the declared company a provider's members join, by its {@code company} setting
         * @return the deciding providers' registration ids, each with the keys of the companies it decides
         */
        Map<String, Set<String>> verifyRoleSources(
                java.util.function.Function<@Nullable String, java.util.Optional<String>> companyKey) {
            Map<String, Set<String>> deciding = new java.util.TreeMap<>();
            List<String> problems = new java.util.ArrayList<>();
            providers.forEach((id, provider) -> provider.problems(companyKey.apply(provider.company()).orElse(null))
                .forEach(problem -> problems.add(variable(id) + "_" + problem)));
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Invalid role settings (ADR-0088): "
                        + String.join("; ", problems.stream().sorted().toList()));
            }
            providers.forEach((id, provider) -> {
                if (!provider.roles().decides()) {
                    return;
                }
                if (!com.flowtivo.tinfirm.shared.access.RoleSource.isRegistrationId(id)) {
                    throw new IllegalStateException("tinfirm.auth.oidc.providers." + id + " decides roles, but its id "
                            + "is not 1 to 64 letters, digits, dots, dashes or underscores");
                }
                Set<String> keys = provider.companyKeys(companyKey);
                if (!keys.isEmpty()) {
                    deciding.put(id, keys);
                }
            });
            verifyOneDecidingProviderPerCompany(deciding, "TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_SOURCE");
            return deciding;
        }

        /**
         * Fails when two deciding providers, of either kind, give the roles of one company (ADR-0088 decision 2).
         *
         * @param deciding the deciding providers, each with the keys of the companies it decides
         * @param setting  the setting to name in the message
         */
        static void verifyOneDecidingProviderPerCompany(Map<String, Set<String>> deciding, String setting) {
            Map<String, List<String>> byCompany = new java.util.TreeMap<>();
            deciding.forEach((id, keys) -> keys
                .forEach(key -> byCompany.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(id)));
            byCompany.forEach((key, ids) -> {
                if (ids.size() > 1) {
                    throw new IllegalStateException("More than one provider decides the roles of the company " + key
                            + " " + ids.stream().sorted().toList() + ": set ROLE_SOURCE=APP on all but one of them ("
                            + setting + ", ADR-0088)");
                }
            });
        }

        private static String variable(String id) {
            return "TINFIRM_AUTH_OIDC_PROVIDERS_" + id.toUpperCase(Locale.ROOT);
        }

        /**
         * Fails when an entry names a company the deployment does not declare (ADR-0075): its new users would join no
         * company without a sign.
         */
        void verifyCompanies(Predicate<String> declared) {
            List<String> unknown = new java.util.ArrayList<>();
            providers.forEach((id, provider) -> {
                if (provider.company() != null && !declared.test(provider.company())) {
                    unknown.add(id + " -> " + provider.company());
                }
                provider.companies().keySet().stream().filter(declared.negate()).forEach(key -> unknown
                    .add(variable(id) + "_COMPANIES_" + key.toUpperCase(Locale.ROOT) + "_ROLE_MAP_* -> " + key));
            });
            if (!unknown.isEmpty()) {
                throw new IllegalStateException("tinfirm.auth.oidc.providers names a company that is not declared "
                        + unknown.stream().sorted().toList() + ": the company must be a key of tinfirm.companies "
                        + "(TINFIRM_COMPANIES_<KEY>_NAME)");
            }
        }
    }

    /**
     * How a new OIDC user is created from the IdP's claims (CONVENTIONS §5): the email and its trust, which emails get
     * an account, and the company the user joins (ADR-0075).
     *
     * @param emailTrust   {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_TRUST}
     * @param emailClaims  {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_CLAIMS}: the claims that may carry the email,
     *                     in order. The first value that looks like an email address wins.
     * @param company      {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANY}: the key of the declared company a new user
     *                     joins; null for the only declared company when exactly one is declared, else none
     * @param role         {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE}: the new member's role, MEMBER, ADMIN or
     *                     VIEWER (ADR-0077); a role that holds a seat falls back to VIEWER when no seat is free
     * @param emailDomains {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_DOMAINS}: when not empty, only an email in one
     *                     of these domains gets a new user
     * @param roleSource   {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_SOURCE}: whether the provider decides the role
     *                     at every sign-in ({@code IDP}) or gives a new user one once ({@code APP}); unset, {@code IDP}
     *                     when {@code roleClaim} is set, else {@code APP} (ADR-0088)
     * @param roleClaim    {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_CLAIM}: the claim of the ID token or the userinfo
     *                     response that carries the role's values, a string or an array
     * @param roleMapAdmin {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_ROLE_MAP_ADMIN}, and the two below: the claim's
     *                     values that give each role
     * @param autoProvision {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_AUTO_PROVISION}: whether an unknown identity that
     *                     binds to no SCIM user gets a new user; unset, the instance's
     *                     {@code TINFIRM_AUTH_OIDC_AUTO_PROVISION} (ADR-0087, decision 6)
     * @param scimMatch    {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_SCIM_MATCH}: how a first sign-in finds the user the
     *                     company's SCIM created: by {@code externalId} only, or by email too (ADR-0087, decision 6)
     * @param companies    {@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_COMPANIES_<KEY>_ROLE_MAP_*}: the further declared
     *                     companies the role claim fills, by key, each with the claim's values that give each role
     *                     there (ADR-0089)
     */
    record OidcProvider(@DefaultValue("REQUIRE_VERIFIED_CLAIM") @NotNull EmailTrust emailTrust,
            @DefaultValue(OidcEmailClaims.EMAIL) @NotEmpty List<@NotBlank String> emailClaims,
            @Nullable String company, @DefaultValue("MEMBER") @NotNull CompanyRole role,
            @DefaultValue List<String> emailDomains, ProviderRoles.@Nullable Mode roleSource,
            @Nullable String roleClaim, @DefaultValue List<String> roleMapAdmin,
            @DefaultValue List<String> roleMapMember, @DefaultValue List<String> roleMapViewer,
            @Nullable Boolean autoProvision, @DefaultValue("EXTERNAL_ID") @NotNull ScimMatch scimMatch,
            @DefaultValue Map<String, @Valid CompanyRoles> companies) {

        static final OidcProvider DEFAULT = new OidcProvider(EmailTrust.REQUIRE_VERIFIED_CLAIM,
                List.of(OidcEmailClaims.EMAIL), null, CompanyRole.MEMBER, List.of(), null, null, List.of(),
                List.of(), List.of());

        /** A provider whose new users and SCIM binding follow the instance's defaults, of one company. */
        OidcProvider(EmailTrust emailTrust, List<String> emailClaims, @Nullable String company, CompanyRole role,
                List<String> emailDomains, ProviderRoles.@Nullable Mode roleSource, @Nullable String roleClaim,
                List<String> roleMapAdmin, List<String> roleMapMember, List<String> roleMapViewer) {
            this(emailTrust, emailClaims, company, role, emailDomains, roleSource, roleClaim, roleMapAdmin,
                    roleMapMember, roleMapViewer, null, ScimMatch.EXTERNAL_ID, Map.of());
        }

        /** A provider of one company, with the instance's defaults for auto-provisioning and the SCIM match. */
        OidcProvider(EmailTrust emailTrust, List<String> emailClaims, @Nullable String company, CompanyRole role,
                List<String> emailDomains, ProviderRoles.@Nullable Mode roleSource, @Nullable String roleClaim,
                List<String> roleMapAdmin, List<String> roleMapMember, List<String> roleMapViewer,
                @Nullable Boolean autoProvision, ScimMatch scimMatch) {
            this(emailTrust, emailClaims, company, role, emailDomains, roleSource, roleClaim, roleMapAdmin,
                    roleMapMember, roleMapViewer, autoProvision, scimMatch, Map.of());
        }

        /** A provider whose claim fills several companies (ADR-0089). */
        OidcProvider withCompanies(Map<String, CompanyRoles> companies) {
            return new OidcProvider(emailTrust, emailClaims, company, role, emailDomains, roleSource, roleClaim,
                    roleMapAdmin, roleMapMember, roleMapViewer, autoProvision, scimMatch, companies);
        }

        /** Whether an unknown identity that binds to no SCIM user gets a new user, the instance's default unless set. */
        boolean autoProvisions(boolean instanceDefault) {
            return autoProvision != null ? autoProvision : instanceDefault;
        }

        /** The provider's role settings (ADR-0088), with the further companies its claim fills (ADR-0089). */
        ProviderRoles roles() {
            return new ProviderRoles(roleSource, roleClaim, new ProviderRoles.RoleMap(roleMapAdmin, roleMapMember,
                    roleMapViewer), CompanyRoles.maps(companies));
        }

        /**
         * The problems of the role settings and the companies (ADR-0088, ADR-0089), each naming the setting as a
         * suffix of the provider's variables: {@link ProviderRoles#problems()}, a company of the claim that is the
         * provider's own, and a deciding provider of a company without a map for it.
         *
         * @param homeKey the key of the provider's own company, as the deployment resolves it, or null for none
         */
        List<String> problems(@Nullable String homeKey) {
            return CompanyRoles.problems(roles(), homeKey, "COMPANY");
        }

        /** The keys of the declared companies the provider fills: its own and its claim's (ADR-0089). */
        Set<String> companyKeys(
                java.util.function.Function<@Nullable String, java.util.Optional<String>> companyKey) {
            return CompanyRoles.companyKeys(roles(), companyKey.apply(company).orElse(null));
        }

        /** A domain name of at least two labels, lower case, without a trailing dot. */
        private static final Pattern DOMAIN = Pattern
            .compile("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

        @ConstructorBinding
        OidcProvider {
            company = company == null || company.isBlank() ? null : company.trim();
            companies = companies == null ? Map.of() : Map.copyOf(companies);
            emailDomains = emailDomains.stream()
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .toList();
        }

        /** A provider never makes owners: a company's owner comes from its declaration (ADR-0075). */
        @AssertTrue(message = "role must be MEMBER, ADMIN or VIEWER")
        boolean isRoleNotOwner() {
            return role != CompanyRole.OWNER;
        }

        @AssertTrue(message = "email-domains must be domain names, such as example.com")
        boolean isEmailDomainsValid() {
            return emailDomains.stream().allMatch(domain -> DOMAIN.matcher(domain).matches());
        }

        /** Whether a new user may have this (normalised) email: any, unless domains are listed. */
        boolean allows(String email) {
            if (emailDomains.isEmpty()) {
                return true;
            }
            int at = email.lastIndexOf('@');
            return at > 0 && emailDomains.contains(email.substring(at + 1));
        }

        /** {@code email_verified} speaks for the {@code email} claim only, so other claims need a trusted issuer. */
        @AssertTrue(message = "email-claims other than [email] require email-trust TRUST_ISSUER")
        boolean isEmailClaimsCoveredByTrust() {
            return emailTrust == EmailTrust.TRUST_ISSUER || List.of(OidcEmailClaims.EMAIL).equals(emailClaims);
        }
    }

    /**
     * The claim's values that give each role in a further company the provider's claim fills (ADR-0089), bound from
     * {@code ..._COMPANIES_<KEY>_ROLE_MAP_ADMIN}, {@code _ROLE_MAP_MEMBER} and {@code _ROLE_MAP_VIEWER}, for an OIDC
     * and a SAML provider alike.
     */
    record CompanyRoles(@DefaultValue List<String> roleMapAdmin, @DefaultValue List<String> roleMapMember,
            @DefaultValue List<String> roleMapViewer) {

        ProviderRoles.RoleMap map() {
            return new ProviderRoles.RoleMap(roleMapAdmin, roleMapMember, roleMapViewer);
        }

        /** The role maps by company key. */
        static Map<String, ProviderRoles.RoleMap> maps(@Nullable Map<String, CompanyRoles> companies) {
            Map<String, ProviderRoles.RoleMap> maps = new java.util.TreeMap<>();
            if (companies != null) {
                companies.forEach((key, roles) -> maps.put(key, roles == null ? ProviderRoles.RoleMap.NONE
                        : roles.map()));
            }
            return maps;
        }

        /**
         * The problems of a provider's role settings with its companies: {@link ProviderRoles#problems()}; a company
         * of the claim that is the provider's own, whose map belongs in {@code ROLE_MAP_*}; and a provider that
         * decides the roles of its own company without a map for it, which would make nobody its member.
         *
         * @param homeKey        the key of the provider's own company, or null for none
         * @param companySetting the setting that names the provider's own company, {@code COMPANY}
         */
        static List<String> problems(ProviderRoles roles, @Nullable String homeKey, String companySetting) {
            List<String> problems = new java.util.ArrayList<>(roles.problems());
            if (homeKey != null && roles.companies().containsKey(homeKey)) {
                problems.add("COMPANIES_" + homeKey.toUpperCase(Locale.ROOT) + "_ROLE_MAP_* names the provider's own "
                        + "company (" + companySetting + "): put its values in ROLE_MAP_ADMIN, ROLE_MAP_MEMBER and "
                        + "ROLE_MAP_VIEWER");
            }
            if (homeKey != null && roles.decides() && !roles.home().isMapped() && roles.fillsCompanies()) {
                problems.add("ROLE_MAP_*: the provider decides the roles of its own company " + homeKey + " ("
                        + companySetting + ") but maps no value to a role there; set ROLE_MAP_ADMIN, ROLE_MAP_MEMBER "
                        + "or ROLE_MAP_VIEWER, or unset " + companySetting);
            }
            return problems;
        }

        /** The keys of the declared companies a provider fills: its own, when it has one, and its claim's. */
        static Set<String> companyKeys(ProviderRoles roles, @Nullable String homeKey) {
            Set<String> keys = new java.util.TreeSet<>(roles.companies().keySet());
            if (homeKey != null) {
                keys.add(homeKey);
            }
            return keys;
        }
    }

    /** How a first sign-in finds the user the company's SCIM created (ADR-0087, decision 6). */
    enum ScimMatch {

        /** By the IdP's {@code externalId} only: OIDC's {@code sub}, or Entra ID's {@code oid}. */
        EXTERNAL_ID,

        /**
         * By {@code externalId}, else by the email the sign-in asserts under the provider's email trust and domains:
         * the one link by email the server makes, for an IdP that cannot send the same id in both.
         */
        EXTERNAL_ID_OR_EMAIL
    }

    enum EmailTrust {

        /** Provisioning needs {@code email_verified=true}, and the email comes from the {@code email} claim. */
        REQUIRE_VERIFIED_CLAIM,

        /**
         * The issuer is trusted to assert the email: a missing {@code email_verified} claim does not block provisioning.
         * An explicit {@code email_verified=false} still does.
         */
        TRUST_ISSUER
    }
}
