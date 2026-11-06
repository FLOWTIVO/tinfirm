package com.flowtivo.tinfirm.identity.internal;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.security.common.url.DefaultUrlValidator;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.ClientIdMetadataDocumentRegisteredClientRepository;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.ClientIdUrlValidator;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.ClientMetadataValidator;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.DefaultClientMetadataValidator;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.InvalidClientMetadataException;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientMetadataClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.security.Actor;

/**
 * The clients known by their Client ID Metadata Document (ADR-0045): MCP Security's
 * {@link ClientIdMetadataDocumentRegisteredClientRepository} with Tinfirm's guarded fetch
 * ({@link CimdDocumentFetcher}), a stricter metadata validator, and a converter that gives every such client Tinfirm's
 * defaults ({@link OAuthClientDefaults}), whatever its document asks for.
 * <p>
 * This repository answers only for client ids that are https URLs; everything else is a pre-registered client. A
 * document's client is written to {@code oauth2_registered_client} whenever it was fetched again and differs from
 * the stored copy, and that first resolution is audited as {@code oauth.client.resolved} (actor SYSTEM, target
 * {@code oauth_client}, the client id's host). {@link #findById} reads the stored copy, so stored authorizations stay
 * readable without a fetch; {@link #findByClientId}, which the authorization and token endpoints use, always goes
 * through the document.
 */
final class CimdClients implements RegisteredClientRepository {

    private static final Logger log = LoggerFactory.getLogger(CimdClients.class);

    static final int MAX_REDIRECT_URIS = 10;

    static final int MAX_REDIRECT_URI_LENGTH = 200;

    static final int MAX_NAME_LENGTH = 100;

    private final ClientIdMetadataDocumentRegisteredClientRepository documents;

    private final CimdDocumentFetcher fetcher;

    private final JdbcRegisteredClientRepository stored;

    private final AuditLog auditLog;

    private final TransactionTemplate transactions;

    private final boolean allowHttp;

    CimdClients(CimdDocumentFetcher fetcher, JdbcRegisteredClientRepository stored, AuditLog auditLog,
            TransactionTemplate transactions, boolean allowHttp) {
        this.fetcher = fetcher;
        this.stored = stored;
        this.auditLog = auditLog;
        this.transactions = transactions;
        this.allowHttp = allowHttp;
        this.documents = new ClientIdMetadataDocumentRegisteredClientRepository();
        this.documents.setMetadataDocumentResolver(fetcher);
        // https only; http only on loopback, and only where the tests allow it.
        this.documents.setClientIdUrlValidator(new ClientIdUrlValidator(allowHttp));
        this.documents.setMetadataValidator(new Validator());
        this.documents.setRegisteredClientConverter(CimdClients::toRegisteredClient);
    }

    /** Whether the client id is the URL of a metadata document, the only ids this repository answers for. */
    boolean isDocumentUrl(@Nullable String clientId) {
        if (clientId == null) {
            return false;
        }
        String lower = clientId.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || (allowHttp && lower.startsWith("http://"));
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        // Clients of metadata documents register themselves by publishing the document.
    }

    @Override
    public @Nullable RegisteredClient findById(String id) {
        return isDocumentUrl(id) ? stored.findById(id) : null;
    }

    @Override
    public @Nullable RegisteredClient findByClientId(String clientId) {
        if (!isDocumentUrl(clientId) || clientId.length() > 500) {
            return null;
        }
        RegisteredClient client;
        try {
            client = documents.findByClientId(clientId);
        }
        catch (IllegalArgumentException ex) {
            // Refused: the URL, the fetch or the document. The authorization endpoint answers invalid_request.
            log.debug("Client {} refused", clientId, ex);
            return null;
        }
        if (client != null && fetcher.firstResolution(URI.create(clientId))) {
            record(client);
        }
        return client;
    }

    /** Stores the client when it is new or its document changed, and audits that. */
    private void record(RegisteredClient client) {
        transactions.executeWithoutResult(status -> {
            RegisteredClient previous = stored.findById(client.getId());
            if (previous != null && sameDocument(previous, client)) {
                return;
            }
            stored.save(client);
            auditLog.record(AuditRecord.instance(AuditAction.OAUTH_CLIENT_RESOLVED, Actor.SYSTEM)
                .target("oauth_client", client.getClientId())
                .detail("host", URI.create(client.getClientId()).getHost())
                .detail("changed", previous != null)
                .build());
        });
    }

    private static boolean sameDocument(RegisteredClient a, RegisteredClient b) {
        return Objects.equals(a.getClientName(), b.getClientName()) && a.getRedirectUris().equals(b.getRedirectUris());
    }

    /** A document's client with Tinfirm's defaults: only its name and redirect URIs are taken from it. */
    static RegisteredClient toRegisteredClient(OAuth2ClientRegistration document) {
        String clientId = document.getClientId();
        String name = document.getClientName();
        if (name == null || name.isBlank()) {
            name = URI.create(clientId).getHost();
        }
        name = name.strip();
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        List<String> redirectUris = document.getRedirectUris() != null ? document.getRedirectUris() : List.of();
        return OAuthClientDefaults.publicClient(clientId, clientId, name, redirectUris).build();
    }

    /**
     * MCP Security's validator (the document's {@code client_id} equals its URL, no secret, redirect URIs https or
     * http on loopback), and Tinfirm's limits: a public client ({@code none} or no method), at most 10 redirect URIs
     * of at most 200 characters each.
     */
    private static final class Validator implements ClientMetadataValidator {

        private final ClientMetadataValidator delegate = new DefaultClientMetadataValidator(
                new DefaultUrlValidator(true));

        @Override
        public void validate(String clientIdUrl, OAuth2ClientRegistration metadata)
                throws InvalidClientMetadataException {
            delegate.validate(clientIdUrl, metadata);
            String method = metadata.getTokenEndpointAuthenticationMethod();
            if (method != null && !ClientAuthenticationMethod.NONE.getValue().equals(method)) {
                throw new InvalidClientMetadataException(clientIdUrl,
                        OAuth2ClientMetadataClaimNames.TOKEN_ENDPOINT_AUTH_METHOD);
            }
            List<String> redirectUris = Objects.requireNonNull(metadata.getRedirectUris());
            if (redirectUris.size() > MAX_REDIRECT_URIS
                    || redirectUris.stream().anyMatch(uri -> uri.length() > MAX_REDIRECT_URI_LENGTH)) {
                throw new InvalidClientMetadataException(clientIdUrl, OAuth2ClientMetadataClaimNames.REDIRECT_URIS);
            }
        }
    }
}
