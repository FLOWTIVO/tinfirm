package com.flowtivo.tinfirm.identity.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * The SAML identity providers the deployment declares (ADR-0086, decision 1), beside the OIDC ones, and the service
 * provider's keys (decision 2): {@code TINFIRM_AUTH_SAML_*}. Public for {@code ee/}'s SAML login, which reads it; the
 * core checks it at the start ({@link SamlSettings}) and provisions the users it signs in.
 *
 * @param providers the providers, by registration id ({@code TINFIRM_AUTH_SAML_PROVIDERS_<ID>_*})
 * @param sp        the service provider's credentials
 */
@Validated
@ConfigurationProperties("tinfirm.auth.saml")
public record SamlProperties(@DefaultValue Map<String, @Valid Provider> providers, @Valid @DefaultValue Sp sp) {

    /** A registration id, unique across the SAML and the OIDC providers. */
    static final Pattern ID = Pattern.compile("^[a-z0-9]+$");

    /** The settings of a provider; null when the deployment declares none of that id. */
    public @Nullable Provider provider(String registrationId) {
        return providers.get(registrationId);
    }

    /** The entity IDs the providers' {@code _ENTITY_ID} pin, by registration id; a provider without one is absent. */
    public Map<String, String> configuredEntityIds() {
        Map<String, String> pinned = new TreeMap<>();
        providers.forEach((id, provider) -> {
            if (provider.entityId() != null) {
                pinned.put(id, provider.entityId());
            }
        });
        return pinned;
    }

    /**
     * Why a provider may not name this IdP, or null (H4 of the security review of 2026-10-27): a SAML user is (the
     * entity ID, the subject), and the keys that sign for it come from each provider's own document, so no two
     * providers that read different metadata name one entity ID, and none names an OIDC provider's issuer. Two
     * providers that read the same metadata location are one IdP with two sets of settings.
     *
     * @param id          the provider
     * @param entityId    the entity ID it would name
     * @param entityIds   the entity IDs of the providers, configured or pinned, by registration id
     * @param oidcIssuers the issuers of the OIDC registrations
     */
    public @Nullable String entityIdConflict(String id, String entityId, Map<String, String> entityIds,
            Set<String> oidcIssuers) {
        if (oidcIssuers.contains(entityId)) {
            return "the SAML provider " + id + " names the IdP " + entityId + ", which is an OIDC provider's issuer";
        }
        for (Map.Entry<String, String> other : new TreeMap<>(entityIds).entrySet()) {
            if (!other.getKey().equals(id) && entityId.equals(other.getValue()) && !sameMetadata(id, other.getKey())) {
                return "the SAML providers " + id + " and " + other.getKey() + " name the IdP " + entityId
                        + " from different metadata: one provider fills several companies instead (ADR-0089)";
            }
        }
        return null;
    }

    /** {@link #entityIdConflict} of every provider of the map, in the order of their ids; empty when none. */
    public List<String> entityIdConflicts(Map<String, String> entityIds, Set<String> oidcIssuers) {
        List<String> conflicts = new ArrayList<>();
        new TreeMap<>(entityIds).forEach((id, entityId) -> {
            String conflict = entityIdConflict(id, entityId, entityIds, oidcIssuers);
            if (conflict != null) {
                conflicts.add(conflict);
            }
        });
        return conflicts;
    }

    private boolean sameMetadata(String a, String b) {
        Provider first = providers.get(a);
        Provider second = providers.get(b);
        return first != null && second != null
                && java.util.Objects.equals(first.metadataLocation(), second.metadataLocation());
    }

    /**
     * The service provider's keys: {@code TINFIRM_AUTH_SAML_SP_CREDENTIALS_<KID>_KEY} and {@code _CERT}, and
     * {@code TINFIRM_AUTH_SAML_SP_ACTIVE_CREDENTIAL}, the one that signs. Every one is published and tried to decrypt.
     *
     * @param credentials      the credentials by key id
     * @param activeCredential the key id of the credential that signs
     */
    public record Sp(@DefaultValue Map<String, @Valid Credential> credentials, @Nullable String activeCredential) {
    }

    /**
     * @param key  a PKCS#8 PEM private key, RSA of at least 3072 bits or EC P-256
     * @param cert its X.509 certificate in PEM; self-signed is fine
     */
    public record Credential(@NotBlank String key, @NotBlank String cert) {

        @Override
        public String toString() {
            return "Credential[key=<hidden>]";
        }
    }

    /**
     * A SAML identity provider (ADR-0086, decision 1). The company, role, domains, role source, auto-provisioning and
     * SCIM match are an OIDC provider's ({@link AuthProperties.OidcProvider}).
     *
     * @param name                       {@code _NAME}: the login page's button; the id when unset
     * @param metadataLocation           {@code _METADATA_LOCATION}: an {@code https://} URL, or a {@code file:} path
     * @param entityId                   {@code _ENTITY_ID}: the IdP to take from a document that holds several, and
     *                                   the IdP the provider is; when unset, the IdP its first good document names
     *                                   is pinned (H4 of the security review of 2026-10-27)
     * @param subject                    {@code _SUBJECT}: {@code NAMEID}, or the attribute that identifies the person
     * @param emailAttributes            {@code _EMAIL_ATTRIBUTES}: the attributes read for the email, in order
     * @param nameAttributes             {@code _NAME_ATTRIBUTES}: the attributes read for the name, in order
     * @param requireEncryptedAssertions {@code _REQUIRE_ENCRYPTED_ASSERTIONS}: refuse a plain assertion (decision 9)
     * @param metadataRefresh            {@code _METADATA_REFRESH}: how often the metadata is read again (decision 3)
     * @param companies                  {@code _COMPANIES_<KEY>_ROLE_MAP_*}: the further declared companies the role
     *                                   attribute fills, by key, each with the values that give each role there
     *                                   (ADR-0089)
     */
    public record Provider(@Nullable String name, @NotBlank String metadataLocation, @Nullable String entityId,
            @Nullable String company, @DefaultValue("MEMBER") @NotNull CompanyRole role,
            @DefaultValue List<String> emailDomains, @DefaultValue(NAMEID) @NotBlank String subject,
            @DefaultValue({ "email", "mail", "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
                    "urn:oid:0.9.2342.19200300.100.1.3" }) @NotEmpty List<@NotBlank String> emailAttributes,
            @DefaultValue({ "displayName", "http://schemas.microsoft.com/identity/claims/displayname",
                    "urn:oid:2.16.840.1.113730.3.1.241",
                    "name" }) @NotEmpty List<@NotBlank String> nameAttributes,
            ProviderRoles.@Nullable Mode roleSource, @Nullable String roleClaim,
            @DefaultValue List<String> roleMapAdmin, @DefaultValue List<String> roleMapMember,
            @DefaultValue List<String> roleMapViewer, @DefaultValue("true") boolean autoProvision,
            @DefaultValue("false") boolean requireEncryptedAssertions,
            @DefaultValue("PT12H") @NotNull Duration metadataRefresh,
            @DefaultValue("EXTERNAL_ID") AuthProperties.@NotNull ScimMatch scimMatch,
            @DefaultValue Map<String, AuthProperties.@Valid CompanyRoles> companies) {

        /** {@code _SUBJECT}'s value for the assertion's NameID. */
        public static final String NAMEID = "NAMEID";

        /** The least refresh: a URL's IdP is not read more than every 5 minutes. */
        static final Duration MIN_REFRESH = Duration.ofMinutes(5);

        /** A provider of one company (ADR-0086). */
        public Provider(@Nullable String name, String metadataLocation, @Nullable String entityId,
                @Nullable String company, CompanyRole role, List<String> emailDomains, String subject,
                List<String> emailAttributes, List<String> nameAttributes, ProviderRoles.@Nullable Mode roleSource,
                @Nullable String roleClaim, List<String> roleMapAdmin, List<String> roleMapMember,
                List<String> roleMapViewer, boolean autoProvision, boolean requireEncryptedAssertions,
                Duration metadataRefresh, AuthProperties.ScimMatch scimMatch) {
            this(name, metadataLocation, entityId, company, role, emailDomains, subject, emailAttributes,
                    nameAttributes, roleSource, roleClaim, roleMapAdmin, roleMapMember, roleMapViewer, autoProvision,
                    requireEncryptedAssertions, metadataRefresh, scimMatch, Map.of());
        }

        @ConstructorBinding
        public Provider {
            name = name == null || name.isBlank() ? null : name.trim();
            companies = companies == null ? Map.of() : Map.copyOf(companies);
            metadataLocation = metadataLocation == null ? null : metadataLocation.trim();
            entityId = entityId == null || entityId.isBlank() ? null : entityId.trim();
            company = company == null || company.isBlank() ? null : company.trim();
            subject = subject == null ? null : subject.trim();
            emailDomains = emailDomains.stream()
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .toList();
            emailAttributes = emailAttributes.stream().map(String::trim).toList();
            nameAttributes = nameAttributes.stream().map(String::trim).toList();
        }

        /** Whether the subject is the assertion's NameID rather than an attribute. */
        public boolean subjectIsNameId() {
            return NAMEID.equals(subject);
        }

        /** The provider's role settings (ADR-0088), as an OIDC provider's, with its claim's companies (ADR-0089). */
        ProviderRoles roles() {
            return new ProviderRoles(roleSource, roleClaim, new ProviderRoles.RoleMap(roleMapAdmin, roleMapMember,
                    roleMapViewer), AuthProperties.CompanyRoles.maps(companies));
        }

        /** The problems of the role settings and the companies, as an OIDC provider's. */
        List<String> problems(@Nullable String homeKey) {
            return AuthProperties.CompanyRoles.problems(roles(), homeKey, "COMPANY");
        }

        /** The keys of the declared companies the provider fills: its own and its attribute's (ADR-0089). */
        java.util.Set<String> companyKeys(@Nullable String homeKey) {
            return AuthProperties.CompanyRoles.companyKeys(roles(), homeKey);
        }

        /** The attribute that carries the role's values, or null (ADR-0088). */
        public @Nullable String roleAttribute() {
            return roles().claim();
        }

        /** Whether the metadata is a file the deployment mounts, rather than a URL. */
        public boolean metadataIsFile() {
            return metadataLocation.startsWith("file:");
        }

        @AssertTrue(message = "role must be MEMBER, ADMIN or VIEWER")
        boolean isRoleNotOwner() {
            return role != CompanyRole.OWNER;
        }

        @AssertTrue(message = "email-domains must be domain names, such as example.com")
        boolean isEmailDomainsValid() {
            return emailDomains.stream().allMatch(domain -> DOMAIN.matcher(domain).matches());
        }

        @AssertTrue(message = "metadata-location must be an https:// URL or a file: path; http: is refused")
        boolean isMetadataLocationValid() {
            if (metadataLocation == null) {
                return true;
            }
            // Lower case, as the reader takes them.
            return (metadataLocation.startsWith("https://") && metadataLocation.length() > "https://".length())
                    || (metadataLocation.startsWith("file:") && metadataLocation.length() > "file:".length());
        }

        @AssertTrue(message = "metadata-refresh must be at least PT5M")
        boolean isMetadataRefreshValid() {
            return metadataRefresh == null || metadataRefresh.compareTo(MIN_REFRESH) >= 0;
        }

        /** Whether a new user may have this (normalised) email: any, unless domains are listed. */
        boolean allows(String email) {
            if (emailDomains.isEmpty()) {
                return true;
            }
            int at = email.lastIndexOf('@');
            return at > 0 && emailDomains.contains(email.substring(at + 1));
        }

        /** A domain name of at least two labels, lower case, without a trailing dot. */
        private static final Pattern DOMAIN = Pattern
            .compile("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    }
}
