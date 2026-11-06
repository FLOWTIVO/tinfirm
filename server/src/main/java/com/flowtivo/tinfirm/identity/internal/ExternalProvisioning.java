package com.flowtivo.tinfirm.identity.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.ProvisionedMemberships;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * Maps a sign-in through an identity provider to a Tinfirm user (CONVENTIONS §5): an OIDC login, or a SAML one
 * (ADR-0086, decision 11), through one code, so that the email checks, {@code EMAIL_IN_USE}, the SCIM binding, the
 * declared owner, the role and the seat fallback are the same for both.
 * <ul>
 * <li>A user is identified by (issuer, subject), never by email: a SAML user by (the IdP's entity ID, subject). An
 * identity of one kind is never signed in through the other.</li>
 * <li>A SAML provider is trusted to assert the email, as an OIDC provider under {@code TRUST_ISSUER} (ADR-0086,
 * decision 5).</li>
 * <li>An unknown (issuer, subject) gets a new OIDC user if auto-provisioning is on and the email can be trusted, as the
 * registration's {@link AuthProperties.EmailTrust} decides: by default the IdP must assert {@code email_verified=true}
 * for the {@code email} claim; a trusted issuer may omit {@code email_verified} and send the email in one of the
 * configured claims ({@link OidcEmailClaims}).</li>
 * <li>There is no automatic linking: if the email already belongs to another user, the login fails with
 * {@code EMAIL_IN_USE}.</li>
 * <li>Deactivated users cannot log in.</li>
 * <li>A provider that lists email domains creates a user only for an email in one of them
 * ({@code EMAIL_DOMAIN_NOT_ALLOWED} otherwise).</li>
 * <li>A new user becomes a member of the provider's company, the declared company it names or the only declared one
 * (ADR-0075), with the provider's role, and of each further company its role claim fills, with the role mapped there
 * (ADR-0089). A provider without a company creates users without any membership.</li>
 * <li>A provider that decides roles ({@code _ROLE_SOURCE=IDP}, ADR-0088, ADR-0089) applies its claim at every sign-in
 * to each of its companies: the mapped role to the membership, with the seat fallback; a membership where the claim
 * maps a role and the person has none; a departure where the person is a member and the claim maps none any more. A
 * claim that maps to no role in any of its companies refuses the sign-in ({@code ROLE_NOT_MAPPED}) and changes
 * nothing. An OWNER, and a membership SCIM manages, are never changed, ended or refused.</li>
 * <li>Under {@code _ROLE_SOURCE=APP} the mapped roles, else {@code _ROLE} in the provider's company, are a new user's,
 * once; a known identity just logs in, whichever provider it comes through, and no membership is granted again, so
 * one that the company's owners removed stays removed.</li>
 * </ul>
 * Denials are returned (not thrown), so the transaction commits and the failed login stays in the audit log.
 */
@Component
class ExternalProvisioning {

    static final int MAX_DISPLAY_NAME = 100;

    static final int MAX_EMAIL = 320;

    private final UserRepository users;

    private final LoginAudit loginAudit;

    private final AuditLog auditLog;

    private final AuthProperties properties;

    private final TimeSource time;

    private final ProvisionedMemberships memberships;

    private final SamlProperties saml;

    ExternalProvisioning(UserRepository users, LoginAudit loginAudit, AuditLog auditLog, AuthProperties properties,
            TimeSource time, ProvisionedMemberships memberships) {
        this(users, loginAudit, auditLog, properties, time, memberships,
                new SamlProperties(Map.of(), new SamlProperties.Sp(Map.of(), null)));
    }

    @org.springframework.beans.factory.annotation.Autowired
    ExternalProvisioning(UserRepository users, LoginAudit loginAudit, AuditLog auditLog, AuthProperties properties,
            TimeSource time, ProvisionedMemberships memberships, SamlProperties saml) {
        this.users = users;
        this.loginAudit = loginAudit;
        this.auditLog = auditLog;
        this.properties = properties;
        this.time = time;
        this.memberships = memberships;
        this.saml = saml;
    }

    /**
     * A successful OIDC authentication.
     *
     * @param provider  the registration id
     * @param claims    the claims of the ID token and the userinfo response
     * @param companies the companies the provider fills (ADR-0075, ADR-0089), resolved by the server
     */
    record OidcLogin(String provider, String issuer, String subject, Map<String, Object> claims,
            ProviderCompanies companies, @Nullable EntraIdentity entra) {

        OidcLogin {
            claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        }

        /** A login through a provider of one company, or of none. */
        OidcLogin(String provider, String issuer, String subject, Map<String, Object> claims,
                @Nullable UUID companyId) {
            this(provider, issuer, subject, claims, ProviderCompanies.of(companyId), null);
        }

        /** A login through a provider whose new users join no company. */
        OidcLogin(String provider, String issuer, String subject, Map<String, Object> claims) {
            this(provider, issuer, subject, claims, ProviderCompanies.NONE, null);
        }

        /** The provider's own company, or null. */
        @Nullable UUID companyId() {
            return companies.home();
        }
    }

    /**
     * The Entra tenant and object id of a login, read from the ID token itself, never from the merged claims of the ID
     * token and the userinfo (the package 5 contract, §3.8): only for an issuer
     * {@code https://login.microsoftonline.com/{tid}/v2.0} whose {@code tid} claim is the issuer's tenant. A v1.0
     * issuer ({@code https://sts.windows.net/{tid}/}) names none.
     */
    record EntraIdentity(String tenantId, String objectId) {

        private static final java.util.regex.Pattern ISSUER = java.util.regex.Pattern
            .compile("^https://login\\.microsoftonline\\.com/([0-9a-fA-F-]{36})/v2\\.0$");

        /** The identity the ID token's own claims name, or null. */
        static @Nullable EntraIdentity of(@Nullable String issuer, Map<String, Object> idTokenClaims) {
            if (issuer == null) {
                return null;
            }
            java.util.regex.Matcher matcher = ISSUER.matcher(issuer);
            if (!matcher.matches()) {
                return null;
            }
            Object tid = idTokenClaims.get("tid");
            Object oid = idTokenClaims.get("oid");
            if (!(tid instanceof String tenant) || !(oid instanceof String object) || object.isBlank()
                    || object.length() > 64 || !tenant.equalsIgnoreCase(matcher.group(1))) {
                return null;
            }
            return new EntraIdentity(tenant.toLowerCase(java.util.Locale.ROOT),
                    object.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * A SAML assertion the library and Tinfirm's checks accepted (ADR-0086, decisions 5 and 8).
     *
     * @param provider       the registration id
     * @param entityId       the IdP's entity ID, in the issuer's place
     * @param subject        the NameID, or the attribute {@code _SUBJECT} names
     * @param email          the first of the provider's email attributes that holds an email address, or null
     * @param emailAttribute the attribute it came from ({@code NameID} for the NameID), or null
     * @param name           the person's name from the provider's name attributes, or null
     * @param roleValues     the values of the provider's role attribute, empty without one
     * @param companies      the companies the provider fills (ADR-0075, ADR-0089), resolved by the server
     */
    record SamlAssertion(String provider, String entityId, String subject, @Nullable String email,
            @Nullable String emailAttribute, @Nullable String name, List<String> roleValues,
            ProviderCompanies companies) {

        SamlAssertion {
            roleValues = List.copyOf(roleValues);
        }

        /** An assertion of a provider of one company, or of none. */
        SamlAssertion(String provider, String entityId, String subject, @Nullable String email,
                @Nullable String emailAttribute, @Nullable String name, List<String> roleValues,
                @Nullable UUID companyId) {
            this(provider, entityId, subject, email, emailAttribute, name, roleValues, ProviderCompanies.of(companyId));
        }
    }

    sealed interface Outcome permits Granted, Denied {
    }

    record Granted(UUID userId) implements Outcome {
    }

    /**
     * @param reason the audited reason
     * @param code   the error code for the SPA, one of the codes of {@link OidcLoginDeniedException}
     */
    record Denied(String reason, String code) implements Outcome {
    }

    @Transactional
    public Outcome provision(OidcLogin login) {
        AuthProperties.OidcProvider settings = properties.oidc().provider(login.provider());
        OidcEmailClaims.ClaimedEmail claimed = OidcEmailClaims.email(login.claims(), settings.emailClaims());
        ProviderRoles roles = settings.roles();
        List<String> externalIds = new ArrayList<>(List.of(login.subject()));
        if (login.entra() != null) {
            externalIds.add(login.entra().objectId());
        }
        return provision(new SignIn(Kind.OIDC, login.provider(), login.issuer(), login.subject(), login.companies(),
                claimed == null ? null : claimed.email(), claimed == null ? null : claimed.claim(),
                trusted(settings, login.claims()), displayName(login.claims()),
                roles.claim() != null ? login.claims().get(roles.claim()) : null, externalIds, login.entra(),
                new Policy(settings.role(), roles, settings.autoProvisions(properties.oidcAutoProvision()),
                        settings.scimMatch(), settings::allows)));
    }

    /** A SAML sign-in (ADR-0086, decisions 5 to 7); its provider is one the deployment declares. */
    @Transactional
    public Outcome provisionSaml(SamlAssertion login) {
        SamlProperties.Provider settings = java.util.Objects.requireNonNull(saml.provider(login.provider()),
                () -> "No SAML provider " + login.provider());
        ProviderRoles roles = settings.roles();
        return provision(new SignIn(Kind.SAML, login.provider(), login.entityId(), login.subject(), login.companies(),
                login.email(), login.emailAttribute(), true, login.name(),
                roles.claim() != null ? login.roleValues() : null, List.of(login.subject()), null,
                new Policy(settings.role(), roles, settings.autoProvision(), settings.scimMatch(), settings::allows)));
    }

    /** The kind of a sign-in: its users, its audited method and its generic refusal. */
    private enum Kind {

        OIDC(AuthSource.OIDC, "oidc", OidcLoginDeniedException.OIDC_LOGIN_FAILED, "emailClaim"),

        SAML(AuthSource.SAML, "saml", SAML_LOGIN_FAILED, "emailAttribute");

        private final AuthSource source;

        private final String method;

        private final String failed;

        private final String emailDetail;

        Kind(AuthSource source, String method, String failed, String emailDetail) {
            this.source = source;
            this.method = method;
            this.failed = failed;
            this.emailDetail = emailDetail;
        }
    }

    /** Code of every refused SAML sign-in that has no code of its own (ADR-0086, decision 12). */
    static final String SAML_LOGIN_FAILED = "SAML_LOGIN_FAILED";

    /** How a provider makes users: the settings both kinds share (ADR-0075, ADR-0087, ADR-0088). */
    private record Policy(CompanyRole role, ProviderRoles roles, boolean autoProvisions,
            AuthProperties.ScimMatch scimMatch, java.util.function.Predicate<String> allows) {
    }

    /**
     * A sign-in of either kind, as the provider asserted it.
     *
     * @param companies    the companies the provider fills: its own, and those its role claim fills
     * @param email        the asserted email, not normalised, or null
     * @param emailSource  the claim or attribute it came from
     * @param emailTrusted whether the provider's email trust accepts it (always for SAML)
     * @param name         the person's name, or null for the email's local part
     * @param roleValue    the role claim's or attribute's value, or null
     * @param externalIds  the ids SCIM may know the person by (ADR-0087, decision 6)
     */
    private record SignIn(Kind kind, String provider, String issuer, String subject, ProviderCompanies companies,
            @Nullable String email, @Nullable String emailSource, boolean emailTrusted, @Nullable String name,
            @Nullable Object roleValue, List<String> externalIds, @Nullable EntraIdentity entra, Policy policy) {

        /** The provider's own company, or null. */
        @Nullable UUID companyId() {
            return companies.home();
        }

        /** Whether the provider decides the roles, and so the memberships, of its companies at every sign-in. */
        boolean decides() {
            return !companies.isEmpty() && policy.roles().decides();
        }

        /**
         * The role the claim maps in each of the provider's companies, the home company first: null where it maps
         * none, or where the provider names no claim.
         */
        Map<UUID, @Nullable CompanyRole> mappedRoles() {
            Map<UUID, @Nullable CompanyRole> roles = new LinkedHashMap<>();
            ProviderRoles settings = policy.roles();
            if (companies.home() != null) {
                roles.put(companies.home(), settings.claim() != null ? settings.mapped(roleValue) : null);
            }
            List<String> values = ProviderRoles.values(roleValue);
            companies.mapped().forEach((companyId, map) -> roles.put(companyId, map.mapped(values)));
            return roles;
        }

        /** The companies the login may name for the declared owner (ADR-0075): all of them when it asserts the email. */
        Set<UUID> ownerCompanies(String email) {
            return asserts(this, email) ? companies.all() : Set.of();
        }
    }

    private Outcome provision(SignIn login) {
        Optional<UserEntity> linked = users.findByOidcIssuerAndOidcSubject(login.issuer(), login.subject());
        if (linked.isPresent() && linked.get().getAuthSource() != login.kind().source) {
            // The same (issuer, subject) through the other kind: never the same user, never a new one.
            return deny(login, null, "identity_in_use", login.kind().failed);
        }
        if (linked.isPresent()) {
            UserEntity user = linked.get();
            if (!user.isActive()) {
                return deny(login, user.getId(), "inactive", login.kind().failed);
            }
            // ADR-0088, ADR-0089: a provider that decides applies its claim at every sign-in, before the login counts.
            Denied refused = applyClaim(login, user.getId());
            if (refused != null) {
                return refused;
            }
            user.recordLogin(time.now());
            recordEntra(user, login);
            // The provider's companies may make this user their declared owner only when this login asserts their
            // email itself (ADR-0075): the stored one may come from another registration of the same issuer.
            loginAudit.succeeded(user.getId(), login.kind().method, login.provider(),
                    login.ownerCompanies(user.getEmail()));
            return new Granted(user.getId());
        }

        // ADR-0087, decision 6: an unknown identity is bound to the user its company's SCIM created for it.
        if (login.companyId() != null) {
            Optional<ScimBinding> binding = scimUser(login);
            if (binding.isPresent()) {
                return bind(login, binding.get());
            }
        }
        if (!login.policy().autoProvisions()) {
            return deny(login, null, "not_provisioned", login.kind().failed);
        }
        if (login.email() == null) {
            return deny(login, null, "email_missing", OidcLoginDeniedException.EMAIL_MISSING);
        }
        if (!login.emailTrusted()) {
            return deny(login, null, "email_not_verified", OidcLoginDeniedException.EMAIL_NOT_VERIFIED);
        }
        String email = Emails.normalize(login.email());
        if (email.length() > MAX_EMAIL) {
            return deny(login, null, "invalid_email", login.kind().failed);
        }
        // Before the check for an existing email, so that a refused login does not tell whether the address is taken.
        if (!login.policy().allows().test(email)) {
            return deny(login, null, "email_domain_not_allowed", OidcLoginDeniedException.EMAIL_DOMAIN_NOT_ALLOWED);
        }
        // ADR-0088, ADR-0089: the claim's roles, under IDP every time and refused when it maps none anywhere; under
        // APP once, else _ROLE in the provider's own company. Before the check for an existing email too, so that the
        // refusal does not tell whether the address is taken.
        Map<UUID, @Nullable CompanyRole> roles = login.mappedRoles();
        boolean decides = login.decides();
        if (decides && roles.values().stream().allMatch(Objects::isNull)) {
            return deny(login, null, "role_not_mapped", OidcLoginDeniedException.ROLE_NOT_MAPPED);
        }
        if (users.existsByEmail(email)) {
            return deny(login, null, "email_in_use", OidcLoginDeniedException.EMAIL_IN_USE);
        }
        if (login.companyId() != null && roles.get(login.companyId()) == null && !decides) {
            roles.put(login.companyId(), login.policy().role());
        }

        UserEntity user = login.kind() == Kind.SAML
                ? UserEntity.saml(Ids.newId(), email, displayName(login.name(), email), login.issuer(), login.subject(),
                        time.now())
                : UserEntity.oidc(Ids.newId(), email, displayName(login.name(), email), login.issuer(), login.subject(),
                        time.now());
        user.recordLogin(time.now());
        recordEntra(user, login);
        users.saveAndFlush(user);
        // The name of the claim or attribute, never its value: the audit chain holds no emails (CONVENTIONS §8).
        AuditRecord.Builder created = AuditRecord.instance(AuditAction.USER_CREATED, Actor.SYSTEM)
            .target("user", user.getId())
            .detail("authSource", login.kind().source.name())
            .detail("provider", login.provider())
            .detail(login.kind().emailDetail, Objects.requireNonNull(login.emailSource()))
            .detail("instanceAdmin", false);
        List<UUID> joined = roles.entrySet()
            .stream()
            .filter(entry -> entry.getValue() != null)
            .map(Map.Entry::getKey)
            .toList();
        if (joined.size() == 1) {
            created.detail("companyId", joined.getFirst().toString())
                .detail("role", Objects.requireNonNull(roles.get(joined.getFirst())).name());
        }
        else if (!joined.isEmpty()) {
            created.detail("companyIds", joined.stream().map(UUID::toString).toList());
        }
        auditLog.record(created.build());
        // The provider's companies, each with the role the claim or the deployment gave it, each in its own tenant
        // context (the company module enters it).
        RoleSource source = decides ? RoleSource.provider(login.provider()) : RoleSource.APP;
        joined.forEach(companyId -> memberships.grant(companyId, user.getId(),
                Objects.requireNonNull(roles.get(companyId)), login.provider(), source));
        loginAudit.succeeded(user.getId(), login.kind().method, login.provider(), login.ownerCompanies(email));
        return new Granted(user.getId());
    }

    /**
     * Applies the claim of a provider that decides to each of its companies for a known user (ADR-0088 decision 2,
     * ADR-0089): the mapped role to their membership, a membership where they have none, a departure where the claim
     * maps nothing any more. Nothing changes when the claim maps no role in any company: the sign-in is refused
     * ({@code ROLE_NOT_MAPPED}) unless an OWNER's or a SCIM-managed membership keeps them in, in which case they sign
     * in as they are.
     *
     * @return the refusal, or null when the user may sign in
     */
    private @Nullable Denied applyClaim(SignIn login, UUID userId) {
        if (!login.decides()) {
            return null;
        }
        // In the companies' id order, as ProviderCompanies keeps them and mappedRoles() does not (the home company
        // first): each company's lock is taken here (applyProviderRole, leave), and two sign-ins that take theirs in
        // one order never deadlock.
        Map<UUID, @Nullable CompanyRole> roles = new TreeMap<>(login.mappedRoles());
        if (roles.values().stream().allMatch(Objects::isNull)) {
            boolean kept = roles.keySet()
                .stream()
                .map(companyId -> memberships.applyProviderRole(companyId, userId, null, login.provider()))
                .anyMatch(ProvisionedMemberships.ProviderRole.KEPT::equals);
            return kept ? null : deny(login, userId, "role_not_mapped", OidcLoginDeniedException.ROLE_NOT_MAPPED);
        }
        roles.forEach((companyId, role) -> {
            if (role == null) {
                memberships.leave(companyId, userId, login.provider());
            }
            else if (memberships.applyProviderRole(companyId, userId, role, login.provider())
                    == ProvisionedMemberships.ProviderRole.NOT_A_MEMBER) {
                memberships.grant(companyId, userId, role, login.provider(), RoleSource.provider(login.provider()));
            }
        });
        return null;
    }

    /**
     * The user SCIM created for this identity in the provider's company (ADR-0087, decision 6): the member whose
     * {@code externalId} is the sign-in's subject, or for an Entra ID issuer the ID token's {@code oid}; else, when
     * the provider says {@code EXTERNAL_ID_OR_EMAIL}, the member whose email this sign-in asserts under the provider's
     * email trust and domains. Only a user this company's SCIM created ({@code scim_company_id}), with no identity and
     * no password, active, never an instance admin and never an OWNER of any company, so that a SCIM token cannot
     * point another company's user, an owner's account or an instance admin at a subject its holder controls. The
     * companies of one instance share one operator (threat model §2), so this is hardening, not a tenant boundary.
     */
    private Optional<ScimBinding> scimUser(SignIn login) {
        UUID companyId = Objects.requireNonNull(login.companyId());
        Optional<UUID> member = login.externalIds()
            .stream()
            .flatMap(id -> Stream.of(id, id.toLowerCase(Locale.ROOT)))
            .distinct()
            .map(id -> memberships.scimMemberByExternalId(companyId, id))
            .flatMap(Optional::stream)
            .findFirst();
        String by = "externalId";
        if (member.isEmpty() && login.policy().scimMatch() == AuthProperties.ScimMatch.EXTERNAL_ID_OR_EMAIL
                && login.email() != null && asserts(login, Emails.normalize(login.email()))) {
            member = memberships.scimMemberByEmail(companyId, Emails.normalize(login.email()));
            by = "email";
        }
        String matched = by;
        return member.flatMap(users::findById)
            .filter(user -> user.getAuthSource() == AuthSource.SCIM && user.isActive())
            .filter(user -> companyId.equals(user.getScimCompanyId()) && !user.isInstanceAdmin())
            .filter(user -> !memberships.ownsACompany(user.getId()))
            .map(user -> new ScimBinding(user, matched));
    }

    /** A SCIM user a first sign-in binds to, and how it matched: {@code externalId} or {@code email}. */
    private record ScimBinding(UserEntity user, String by) {
    }

    /**
     * Binds the identity to the SCIM user: from now on they are identified by it, as any OIDC or SAML user; audited
     * {@code user.identity_bound} in the company's chain with the provider and how it matched.
     */
    private Outcome bind(SignIn login, ScimBinding binding) {
        UUID companyId = Objects.requireNonNull(login.companyId());
        UserEntity user = binding.user();
        String by = binding.by();
        // The provider's claim applies as at any sign-in, unless SCIM decides the role (ADR-0088 decision 8); before
        // the binding, so that a sign-in refused for its role binds nothing.
        Denied refused = applyClaim(login, user.getId());
        if (refused != null) {
            return refused;
        }
        if (login.kind() == Kind.SAML) {
            user.bindSaml(login.issuer(), login.subject(), time.now());
        }
        else {
            user.bindOidc(login.issuer(), login.subject(), time.now());
        }
        user.recordLogin(time.now());
        recordEntra(user, login);
        users.flush();
        auditLog.record(AuditRecord.company(companyId, AuditAction.USER_IDENTITY_BOUND, Actor.SYSTEM)
            .target("user", user.getId())
            .detail("userId", user.getId().toString())
            .detail("provider", login.provider())
            .detail("by", by)
            .build());
        loginAudit.succeeded(user.getId(), login.kind().method, login.provider(),
                login.ownerCompanies(user.getEmail()));
        return new Granted(user.getId());
    }

    /** Whether the registration's email trust accepts the email of these claims (ADR-0026). */
    private static boolean trusted(AuthProperties.OidcProvider settings, Map<String, Object> claims) {
        Boolean verified = OidcEmailClaims.emailVerified(claims);
        return switch (settings.emailTrust()) {
            case REQUIRE_VERIFIED_CLAIM -> Boolean.TRUE.equals(verified);
            case TRUST_ISSUER -> !Boolean.FALSE.equals(verified);
        };
    }

    /**
     * Whether this login asserts the email as a new user's would be: from the provider's email claims or attributes,
     * under its email trust, in its domains.
     */
    private static boolean asserts(SignIn login, String email) {
        return login.email() != null && login.emailTrusted() && Emails.normalize(login.email()).equals(email)
                && login.policy().allows().test(email);
    }

    /**
     * Keeps an OIDC login's Entra tenant and object on the user, or clears them for a login that names none. Another
     * user holding them (a second registration of the same tenant) keeps them, and this one gets none: Teams links
     * automatically only when exactly one user has them. A SAML login leaves them as they are.
     */
    private void recordEntra(UserEntity user, SignIn login) {
        if (login.kind() != Kind.OIDC) {
            return;
        }
        EntraIdentity entra = login.entra();
        if (entra == null) {
            user.recordEntra(null, null);
            return;
        }
        boolean other = users.findByOidcTenantIdAndOidcObjectId(entra.tenantId(), entra.objectId())
            .filter(found -> !found.getId().equals(user.getId()))
            .isPresent();
        if (other) {
            user.recordEntra(null, null);
            return;
        }
        user.recordEntra(entra.tenantId(), entra.objectId());
    }

    private Denied deny(SignIn login, @Nullable UUID userId, String reason, String code) {
        if (login.kind() == Kind.SAML) {
            loginAudit.failed(userId, reason, login.provider(), login.kind().method);
        }
        else {
            loginAudit.failed(userId, reason, login.provider());
        }
        return new Denied(reason, code);
    }

    /** The name, else the local part of the email; at most {@link #MAX_DISPLAY_NAME} characters. */
    private static String displayName(@Nullable String name, String email) {
        String chosen = name != null && !name.isBlank() ? name.trim()
                : email.substring(0, Math.max(1, email.indexOf('@')));
        return chosen.length() > MAX_DISPLAY_NAME ? chosen.substring(0, MAX_DISPLAY_NAME) : chosen;
    }

    /** The {@code name} claim, else {@code preferred_username}; null for neither. */
    private static @Nullable String displayName(Map<String, Object> claims) {
        String name = text(claims.get(StandardClaimNames.NAME));
        return name != null ? name : text(claims.get(StandardClaimNames.PREFERRED_USERNAME));
    }

    private static @Nullable String text(@Nullable Object claim) {
        return claim instanceof String value && !value.isBlank() ? value.trim() : null;
    }
}
