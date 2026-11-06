package com.flowtivo.tinfirm.identity.internal;

import java.net.URI;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.DelegatingRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;

/**
 * Every client Tinfirm's authorization server knows (ADR-0045), as one {@link DelegatingRegisteredClientRepository}
 * over (1) the clients of Client ID Metadata Documents ({@link CimdClients}) and (2) the pre-registered clients in
 * {@code oauth2_registered_client} ({@link JdbcRegisteredClientRepository}), which the built-in list
 * ({@link BuiltInOAuthClients}) is written to at startup. Open dynamic registration is off: nothing else saves a
 * client.
 */
@Component
class OAuthClients implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(OAuthClients.class);

    private final JdbcRegisteredClientRepository stored;

    private final CimdClients documents;

    private final RegisteredClientRepository all;

    private final String publicBaseUrl;

    OAuthClients(JdbcTemplate jdbc, CimdDocumentFetcher fetcher, AuditLog auditLog,
            PlatformTransactionManager transactionManager, OAuthProperties properties, TinfirmProperties tinfirm) {
        this.stored = new JdbcRegisteredClientRepository(jdbc);
        this.publicBaseUrl = tinfirm.publicBaseUrl();
        this.documents = new CimdClients(fetcher, stored, auditLog, new TransactionTemplate(transactionManager),
                properties.cimd().allowHttp());
        this.all = new DelegatingRegisteredClientRepository(List.of(documents, new PreRegistered()));
    }

    /** The repository of the authorization server. */
    RegisteredClientRepository repository() {
        return all;
    }

    /** Writes the built-in clients, so that a later version's changes to them take effect. */
    @Override
    public void afterSingletonsInstantiated() {
        for (RegisteredClient client : BuiltInOAuthClients.all(publicBaseUrl)) {
            try {
                stored.save(client);
            }
            catch (DataAccessException | IllegalArgumentException ex) {
                // Another replica wrote it at the same moment.
                log.debug("Built-in OAuth client {} not written", client.getClientId(), ex);
            }
        }
    }

    /** What the consent page and "Connected apps" show of a client. */
    record ClientInfo(String clientId, String name, @Nullable String domain) {
    }

    /** The client of a registered client id (its {@code id}), from the stored copy; empty when unknown. */
    @Nullable ClientInfo describeById(String registeredClientId) {
        RegisteredClient client = all.findById(registeredClientId);
        return client != null ? describe(client) : null;
    }

    ClientInfo describe(RegisteredClient client) {
        String domain = documents.isDocumentUrl(client.getClientId()) ? URI.create(client.getClientId()).getHost()
                : null;
        return new ClientInfo(client.getClientId(), client.getClientName(), domain);
    }

    /** The pre-registered clients: the stored ones whose id is not a metadata document's URL. */
    private final class PreRegistered implements RegisteredClientRepository {

        @Override
        public void save(RegisteredClient registeredClient) {
            // Open dynamic registration is off.
        }

        @Override
        public @Nullable RegisteredClient findById(String id) {
            return documents.isDocumentUrl(id) ? null : stored.findById(id);
        }

        @Override
        public @Nullable RegisteredClient findByClientId(String clientId) {
            return documents.isDocumentUrl(clientId) ? null : stored.findByClientId(clientId);
        }
    }
}
