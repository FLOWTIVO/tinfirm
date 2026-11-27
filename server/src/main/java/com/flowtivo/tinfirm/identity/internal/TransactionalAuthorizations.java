package com.flowtivo.tinfirm.identity.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authorization server's stored authorizations and consents, each call in one transaction. The library's JDBC
 * services take no transaction of their own: each statement commits by itself, and a row's mapping looks up its
 * registered client ({@link OAuthClients}) while the query still holds its connection, which asked the pool for a
 * second one. In a transaction, the lookup joins the query's connection, and a save (a read, then an update or an
 * insert) or a removal commits whole or not at all. A call made inside the caller's transaction joins it.
 */
final class TransactionalAuthorizations {

    private TransactionalAuthorizations() {
    }

    static OAuth2AuthorizationService of(OAuth2AuthorizationService stored, PlatformTransactionManager manager) {
        TransactionTemplate writes = new TransactionTemplate(manager);
        TransactionTemplate reads = readOnly(manager);
        return new OAuth2AuthorizationService() {

            @Override
            public void save(OAuth2Authorization authorization) {
                writes.executeWithoutResult(status -> stored.save(authorization));
            }

            @Override
            public void remove(OAuth2Authorization authorization) {
                writes.executeWithoutResult(status -> stored.remove(authorization));
            }

            @Override
            public @Nullable OAuth2Authorization findById(String id) {
                return reads.execute(status -> stored.findById(id));
            }

            @Override
            public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
                return reads.execute(status -> stored.findByToken(token, tokenType));
            }
        };
    }

    static OAuth2AuthorizationConsentService of(OAuth2AuthorizationConsentService stored,
            PlatformTransactionManager manager) {
        TransactionTemplate writes = new TransactionTemplate(manager);
        TransactionTemplate reads = readOnly(manager);
        return new OAuth2AuthorizationConsentService() {

            @Override
            public void save(OAuth2AuthorizationConsent consent) {
                writes.executeWithoutResult(status -> stored.save(consent));
            }

            @Override
            public void remove(OAuth2AuthorizationConsent consent) {
                writes.executeWithoutResult(status -> stored.remove(consent));
            }

            @Override
            public @Nullable OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
                return reads.execute(status -> stored.findById(registeredClientId, principalName));
            }
        };
    }

    private static TransactionTemplate readOnly(PlatformTransactionManager manager) {
        TransactionTemplate reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        return reads;
    }
}
