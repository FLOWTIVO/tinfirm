package com.flowtivo.tinfirm.identity.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * A person's OAuth grants (ADR-0045): what the consent page shows, "Connected apps" on {@code /me}, the revocation
 * of a grant, and the audit of consents and revocations.
 * <p>
 * A grant has no row of its own. It is the set of the user's authorizations ({@code oauth2_authorization}, Spring's)
 * of one client whose {@code resource} is one company's MCP endpoint, and it is active while one of them holds a
 * refresh or access token that is neither expired nor revoked. Its id is derived ({@link OAuthGrantIds}). Consents
 * and revocations are audited in the chain of the grant's company with the user as actor, written in that company's
 * tenant context: the company comes from the stored authorization, which the authorization endpoint accepted only for
 * a company the user could act in.
 */
@Service
class OAuthGrantService {

    private static final Logger log = LoggerFactory.getLogger(OAuthGrantService.class);

    private static final OAuth2TokenType STATE = new OAuth2TokenType(OAuth2ParameterNames.STATE);

    /** The most authorizations of one user that "Connected apps" reads. */
    static final int MAX_AUTHORIZATIONS = 500;

    static final String UNKNOWN_COMPANY = "Unknown company";

    private final OAuth2AuthorizationService authorizations;

    private final OAuth2AuthorizationConsentService consents;

    private final OAuthClients clients;

    private final OAuthResources resources;

    private final CompanyNames companyNames;

    private final AuditLog auditLog;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    OAuthGrantService(OAuth2AuthorizationService authorizations, OAuth2AuthorizationConsentService consents,
            OAuthClients clients, OAuthResources resources, CompanyNames companyNames, AuditLog auditLog,
            JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.authorizations = authorizations;
        this.consents = consents;
        this.clients = clients;
        this.resources = resources;
        this.companyNames = companyNames;
        this.auditLog = auditLog;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * A pending authorization request of the calling user, as the consent page shows it: the scopes the client sent
     * ({@code requested}), and the levels the person may choose from ({@code offered}, always the three).
     */
    record ConsentRequest(OAuthClients.ClientInfo client, UUID companyId, String companyName, List<ApiScope> requested,
            List<ApiScope> offered) {
    }

    /** One grant, as "Connected apps" shows it. */
    record Grant(UUID id, OAuthClients.ClientInfo client, UUID companyId, String companyName, List<ApiScope> scopes,
            Instant grantedAt, @Nullable Instant lastUsedAt) {
    }

    /**
     * The calling user's pending request with this consent state and client; 404 when there is none (unknown,
     * decided, another user's or another client's).
     */
    ConsentRequest consentRequest(String clientId, String state) {
        AuthenticatedUser user = CurrentActor.requireUser();
        OAuth2Authorization authorization = authorizations.findByToken(state, STATE);
        if (authorization == null || !authorization.getPrincipalName().equals(user.id().toString())) {
            throw notFound();
        }
        RegisteredClient client = clients.repository().findById(authorization.getRegisteredClientId());
        OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        UUID companyId = request != null ? companyOf(request).orElse(null) : null;
        if (client == null || !client.getClientId().equals(clientId) || companyId == null) {
            throw notFound();
        }
        String companyName = companyNames.nameOf(companyId).orElseThrow(OAuthGrantService::notFound);
        String sent = ScopeWideningRequestConverter.requestedScope(request.getAdditionalParameters());
        List<ApiScope> requested = sent != null ? scopes(List.of(sent.split(" "))) : scopes(request.getScopes());
        return new ConsentRequest(clients.describe(client), companyId, companyName, requested,
                List.of(ApiScope.values()));
    }

    /** The calling user's active grants, newest consent first. */
    List<Grant> listOwn() {
        AuthenticatedUser user = CurrentActor.requireUser();
        Map<GrantKey, List<OAuth2Authorization>> byGrant = grantsOf(user.id());
        Map<UUID, String> names = companyNames
            .namesOf(byGrant.keySet().stream().map(GrantKey::companyId).collect(Collectors.toSet()));
        List<Grant> grants = new ArrayList<>();
        byGrant.forEach((key, members) -> {
            List<OAuth2Authorization> active = members.stream().filter(OAuthGrantService::isActive).toList();
            if (active.isEmpty()) {
                return;
            }
            OAuthClients.ClientInfo client = clients.describeById(key.registeredClientId());
            if (client == null) {
                return;
            }
            OAuth2Authorization newest = active.stream()
                .max(Comparator.comparing(OAuthGrantService::consentedAt))
                .orElseThrow();
            Instant lastUsed = active.stream()
                .map(OAuth2Authorization::getAccessToken)
                .filter(Objects::nonNull)
                .map(token -> token.getToken().getIssuedAt())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
            grants.add(new Grant(OAuthGrantIds.of(client.clientId(), user.id(), key.companyId()), client,
                    key.companyId(), names.getOrDefault(key.companyId(), UNKNOWN_COMPANY),
                    scopes(newest.getAuthorizedScopes()), consentedAt(newest), lastUsed));
        });
        grants.sort(Comparator.comparing(Grant::grantedAt).reversed());
        return grants;
    }

    /**
     * Revokes one of the calling user's grants: removes every authorization of that client for that company, and the
     * stored consent of the client when no authorization of it remains for another company. Idempotent.
     * <p>
     * The grant is found among the user's distinct (client, resource) pairs and removed with one statement keyed by
     * the user, the client and the resource, so every row of the grant goes, however many the user holds; the list
     * of {@link #listOwn()} is capped, the revocation is not.
     */
    void revokeOwn(UUID grantId) {
        AuthenticatedUser user = CurrentActor.requireUser();
        String principal = user.id().toString();
        List<GrantCoordinates> candidates = jdbc.sql("SELECT DISTINCT registered_client_id, " + RESOURCE
                + " AS resource FROM oauth2_authorization WHERE principal_name = :user")
            .param("user", principal)
            .query((rs, row) -> new GrantCoordinates(rs.getString("registered_client_id"), rs.getString("resource")))
            .list();
        for (GrantCoordinates candidate : candidates) {
            Optional<UUID> company = resources.companyOfResource(candidate.resource());
            if (company.isEmpty()) {
                continue;
            }
            RegisteredClient client = clients.repository().findById(candidate.registeredClientId());
            if (client == null || !OAuthGrantIds.of(client.getClientId(), user.id(), company.get()).equals(grantId)) {
                continue;
            }
            Set<String> scopes = transactions.execute(status -> {
                List<String> removed = jdbc.sql("DELETE FROM oauth2_authorization"
                        + " WHERE principal_name = :user AND registered_client_id = :client AND " + RESOURCE
                        + " = :resource RETURNING coalesce(authorized_scopes, '')")
                    .param("user", principal)
                    .param("client", candidate.registeredClientId())
                    .param("resource", candidate.resource())
                    .query(String.class)
                    .list();
                boolean remains = jdbc.sql("SELECT EXISTS (SELECT 1 FROM oauth2_authorization"
                        + " WHERE principal_name = :user AND registered_client_id = :client)")
                    .param("user", principal)
                    .param("client", candidate.registeredClientId())
                    .query(Boolean.class)
                    .single();
                if (!remains) {
                    OAuth2AuthorizationConsent consent = consents.findById(candidate.registeredClientId(), principal);
                    if (consent != null) {
                        consents.remove(consent);
                    }
                }
                return removed.isEmpty() ? null
                        : removed.stream()
                            .flatMap(value -> StringUtils.commaDelimitedListToSet(value).stream())
                            .collect(Collectors.toSet());
            });
            if (scopes != null) {
                audit(AuditAction.OAUTH_GRANT_REVOKED, user.id(), clients.describe(client), company.get(), grantId,
                        scopes);
            }
            return;
        }
    }

    /**
     * Revokes every grant of the user, or with a company only that company's (CONVENTIONS §5: on a password change or
     * reset, and on leaving the company): removes the authorizations, and the stored consent of a client when none of
     * its authorizations remains. Each grant that held one is audited {@code oauth.grant.revoked} in its company's
     * chain, by the actor of the change, with the {@code reason}. In the caller's transaction, if any.
     *
     * @return the grants revoked
     */
    int revokeAllOf(UUID userId, @Nullable UUID companyId, String reason) {
        Actor actor = CurrentActor.actor();
        String principal = userId.toString();
        List<GrantCoordinates> candidates = jdbc.sql("SELECT DISTINCT registered_client_id, " + RESOURCE
                + " AS resource FROM oauth2_authorization WHERE principal_name = :user")
            .param("user", principal)
            .query((rs, row) -> new GrantCoordinates(rs.getString("registered_client_id"), rs.getString("resource")))
            .list();
        int revoked = 0;
        for (GrantCoordinates candidate : candidates) {
            Optional<UUID> company = resources.companyOfResource(candidate.resource());
            if (companyId != null && !company.equals(Optional.of(companyId))) {
                continue;
            }
            String resource = candidate.resource();
            Set<String> scopes = transactions.execute(status -> {
                String sql = "DELETE FROM oauth2_authorization WHERE principal_name = :user"
                        + " AND registered_client_id = :client AND "
                        + (resource != null ? RESOURCE + " = :resource" : RESOURCE + " IS NULL")
                        + " RETURNING coalesce(authorized_scopes, '')";
                var statement = jdbc.sql(sql)
                    .param("user", principal)
                    .param("client", candidate.registeredClientId());
                if (resource != null) {
                    statement = statement.param("resource", resource);
                }
                List<String> removed = statement.query(String.class).list();
                removeConsentWhenUnused(principal, candidate.registeredClientId());
                return removed.isEmpty() ? null
                        : removed.stream()
                            .flatMap(value -> StringUtils.commaDelimitedListToSet(value).stream())
                            .collect(Collectors.toSet());
            });
            if (scopes == null || company.isEmpty()) {
                continue;
            }
            revoked++;
            RegisteredClient client = clients.repository().findById(candidate.registeredClientId());
            OAuthClients.ClientInfo info = client != null ? clients.describe(client)
                    : new OAuthClients.ClientInfo(candidate.registeredClientId(), candidate.registeredClientId(), null);
            UUID grantCompany = company.get();
            SystemContext.runInCompany(grantCompany, () -> transactions.executeWithoutResult(status -> auditLog
                .record(AuditRecord.company(grantCompany, AuditAction.OAUTH_GRANT_REVOKED, actor)
                    .target("oauth_grant", OAuthGrantIds.of(info.clientId(), userId, grantCompany))
                    .detail("client", info.domain() != null ? info.domain() : info.clientId())
                    .detail("companyId", grantCompany.toString())
                    .detail("scopes", OAuthScopes.known(scopes))
                    .detail("reason", reason)
                    .build())));
        }
        return revoked;
    }

    /** Removes the user's stored consent to the client once no authorization of it remains. */
    private void removeConsentWhenUnused(String principal, String registeredClientId) {
        boolean remains = jdbc.sql("SELECT EXISTS (SELECT 1 FROM oauth2_authorization"
                + " WHERE principal_name = :user AND registered_client_id = :client)")
            .param("user", principal)
            .param("client", registeredClientId)
            .query(Boolean.class)
            .single();
        if (!remains) {
            OAuth2AuthorizationConsent consent = consents.findById(registeredClientId, principal);
            if (consent != null) {
                consents.remove(consent);
            }
        }
    }

    /** Audits a consent the authorization endpoint just turned into a code. */
    void recordConsent(UUID userId, String clientId, UUID companyId, Collection<String> scopes) {
        RegisteredClient client = clients.repository().findByClientId(clientId);
        OAuthClients.ClientInfo info = client != null ? clients.describe(client)
                : new OAuthClients.ClientInfo(clientId, clientId, null);
        audit(AuditAction.OAUTH_CONSENT_GRANTED, userId, info, companyId, OAuthGrantIds.of(clientId, userId, companyId),
                scopes);
    }

    private void audit(AuditAction action, UUID userId, OAuthClients.ClientInfo client, UUID companyId, UUID grantId,
            Collection<String> scopes) {
        SystemContext.runInCompany(companyId, () -> transactions.executeWithoutResult(status -> auditLog
            .record(AuditRecord.company(companyId, action, Actor.user(userId))
                .target("oauth_grant", grantId)
                .detail("client", client.domain() != null ? client.domain() : client.clientId())
                .detail("companyId", companyId.toString())
                .detail("scopes", OAuthScopes.known(scopes))
                .build())));
    }

    /** A grant's coordinates: the client's registered id and the company of the resource. */
    private record GrantKey(String registeredClientId, UUID companyId) {
    }

    /** A grant's coordinates as stored: the client's registered id and the {@code resource} of the request. */
    private record GrantCoordinates(String registeredClientId, @Nullable String resource) {
    }

    /**
     * The {@code resource} of an authorization's request, read in SQL from the JSON in which Spring's
     * {@code JdbcOAuth2AuthorizationService} stores the attributes (the request under its class name, its additional
     * parameters as a map). Null when the attribute or the parameter is missing, or is not a single string.
     */
    static final String RESOURCE = "((attributes::jsonb)"
            + " -> 'org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest'"
            + " -> 'additionalParameters' ->> 'resource')";

    /** The user's authorizations, by grant. */
    private Map<GrantKey, List<OAuth2Authorization>> grantsOf(UUID userId) {
        List<String> ids = jdbc.sql("""
                SELECT id FROM oauth2_authorization
                 WHERE principal_name = :user
                 ORDER BY id
                 LIMIT :limit""")
            .param("user", userId.toString())
            .param("limit", MAX_AUTHORIZATIONS)
            .query(String.class)
            .list();
        Map<GrantKey, List<OAuth2Authorization>> byGrant = new LinkedHashMap<>();
        for (String id : ids) {
            OAuth2Authorization authorization = read(id);
            if (authorization == null) {
                continue;
            }
            OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
            Optional<UUID> company = request != null ? companyOf(request) : Optional.empty();
            company.ifPresent(companyId -> byGrant
                .computeIfAbsent(new GrantKey(authorization.getRegisteredClientId(), companyId), k -> new ArrayList<>())
                .add(authorization));
        }
        return byGrant;
    }

    private @Nullable OAuth2Authorization read(String id) {
        try {
            return authorizations.findById(id);
        }
        catch (DataAccessException | IllegalArgumentException ex) {
            // A client that is no longer known: its authorizations cannot be read, nor used.
            log.debug("Authorization {} not readable", id, ex);
            return null;
        }
    }

    private Optional<UUID> companyOf(OAuth2AuthorizationRequest request) {
        Object resource = request.getAdditionalParameters().get(OAuthAccessTokenCustomizer.RESOURCE);
        return resource instanceof String value ? resources.companyOfResource(value) : Optional.empty();
    }

    /** A grant is active while a refresh or access token of it can still be used. */
    static boolean isActive(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getRefreshToken();
        OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
        return (refresh != null && refresh.isActive()) || (access != null && access.isActive());
    }

    /** When the person consented: the issue of the authorization's code. */
    private static Instant consentedAt(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2AuthorizationCode> code = authorization.getToken(OAuth2AuthorizationCode.class);
        Instant issued = code != null ? code.getToken().getIssuedAt() : null;
        return issued != null ? issued : Instant.EPOCH;
    }

    private static List<ApiScope> scopes(Collection<String> names) {
        return OAuthScopes.known(names).stream().map(OAuthScopes::of).flatMap(Optional::stream).toList();
    }

    private static ApiException notFound() {
        return ApiException.notFound("The authorization request is unknown or no longer pending");
    }
}
