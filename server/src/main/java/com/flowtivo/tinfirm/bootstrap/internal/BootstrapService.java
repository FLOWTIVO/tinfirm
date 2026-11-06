package com.flowtivo.tinfirm.bootstrap.internal;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.identity.UserAccounts;
import com.flowtivo.tinfirm.shared.config.PublishedDevSecrets;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;

/**
 * Creates the first instance admin when no user exists, as actor SYSTEM. A transaction-scoped advisory lock makes
 * concurrent replicas bootstrap at most once; the "no user exists" check makes it idempotent across restarts. The
 * companies come from the deployment's declarations, which the runner applies next (ADR-0075). The bootstrap password
 * that {@code deploy/compose/.env.example} publishes stops the start unless {@code TINFIRM_PUBLIC_URL} is a localhost
 * URL, whether or not users exist (L17): the account it made may still have it.
 */
@Service
@EnableConfigurationProperties(BootstrapProperties.class)
class BootstrapService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);

    /** Arbitrary constant key of the bootstrap advisory lock. */
    private static final long BOOTSTRAP_LOCK_KEY = 0x7469_6e66_6972_6d01L;

    private final BootstrapProperties properties;

    private final UserAccounts userAccounts;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final TinfirmProperties tinfirm;

    BootstrapService(BootstrapProperties properties, UserAccounts userAccounts, JdbcClient jdbc,
            PlatformTransactionManager transactionManager, TinfirmProperties tinfirm) {
        this.tinfirm = tinfirm;
        this.properties = properties;
        this.userAccounts = userAccounts;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** @return true if this call created the first instance admin */
    boolean bootstrapIfNeeded() {
        if (PublishedDevSecrets.isPublishedBootstrapPassword(properties.adminPassword())) {
            PublishedDevSecrets.refuseOffLocalhost(tinfirm.publicUrl(), "TINFIRM_BOOTSTRAP_ADMIN_PASSWORD",
                    "Set a password of your own, and change the bootstrap admin's password if it was made with "
                            + "this one.");
        }
        if (!properties.configured()) {
            log.info("Bootstrap skipped: TINFIRM_BOOTSTRAP_ADMIN_EMAIL and TINFIRM_BOOTSTRAP_ADMIN_PASSWORD are not both set");
            return false;
        }
        Boolean created = SystemContext.call(() -> transactions.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(BOOTSTRAP_LOCK_KEY).query().singleRow();
            if (userAccounts.anyUserExists()) {
                return false;
            }
            String email = properties.adminEmail().trim().toLowerCase(Locale.ROOT);
            userAccounts.createLocalUser(new UserAccounts.NewLocalUser(email, displayName(email),
                    properties.adminPassword(), true));
            return true;
        }));
        if (Boolean.TRUE.equals(created)) {
            log.info("Bootstrap created the first instance admin");
            return true;
        }
        log.info("Bootstrap skipped: users already exist");
        return false;
    }

    private static String displayName(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
