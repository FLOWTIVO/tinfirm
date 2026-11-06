package com.flowtivo.tinfirm.company.internal;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.company.scim.ScimDirectory;
import com.flowtivo.tinfirm.company.scim.ScimException;
import com.flowtivo.tinfirm.company.scim.ScimMembers;
import com.flowtivo.tinfirm.company.scim.ScimRoleSource;
import com.flowtivo.tinfirm.company.scim.ScimSettings;
import com.flowtivo.tinfirm.shared.licence.LicenceChanged;
import com.flowtivo.tinfirm.shared.security.SystemContext;

/**
 * Releases the roles SCIM decides when SCIM no longer decides them (ADR-0088, decision 7; ADR-0087, decision 10):
 * every membership whose role source is {@code SCIM} becomes the app's, keeping its role, so that the company's admins
 * can change it, in a company whose {@code _SCIM_ROLE_SOURCE} is {@code APP} or whose SCIM the deployment no longer
 * allows, at the start; and in every company once the licence stops serving SCIM, at once, on the licence's change. A
 * key that serves SCIM again changes nothing here: SCIM takes each role back at its next write that carries one.
 * Audited once per company, {@code member.role_source.changed} with the count, by SYSTEM.
 */
@Component
class ScimRoleRelease {

    private static final Logger log = LoggerFactory.getLogger(ScimRoleRelease.class);

    private final JdbcClient jdbc;

    private final ScimSettings settings;

    private final ScimMembers members;

    private final ObjectProvider<ScimDirectory> directory;

    private final TransactionTemplate transactions;

    ScimRoleRelease(JdbcClient jdbc, ScimSettings settings, ScimMembers members,
            ObjectProvider<ScimDirectory> directory, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.members = members;
        this.directory = directory;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    void atTheStart() {
        releaseWhereScimNoLongerDecides();
    }

    @TransactionalEventListener(fallbackExecution = true)
    void licenceChanged(LicenceChanged event) {
        releaseWhereScimNoLongerDecides();
    }

    /** One pass over the companies that hold memberships whose role SCIM decides; how many were released. */
    int releaseWhereScimNoLongerDecides() {
        int released = 0;
        try {
            boolean served = served();
            List<UUID> companies = SystemContext.call(() -> transactions.execute(status -> jdbc.sql(
                    "SELECT DISTINCT company_id FROM company_membership WHERE role_source = 'SCIM' ORDER BY company_id")
                .query(UUID.class)
                .list()));
            for (UUID companyId : companies) {
                released += SystemContext.callInCompany(companyId, () -> transactions.execute(status -> {
                    ScimSettings.Settings scim = settings.of(companyId);
                    boolean decides = served && scim.enabled() && scim.roleSource() == ScimRoleSource.IDP;
                    return decides ? 0 : members.releaseRoles(companyId);
                }));
            }
        }
        catch (RuntimeException ex) {
            // The next start or licence change tries again; meanwhile the app's lock stays on those roles.
            log.warn("The roles SCIM no longer decides could not be released: {}", ex.toString());
        }
        return released;
    }

    /** Whether the licence serves SCIM now; never without {@code ee/}. */
    private boolean served() {
        ScimDirectory found = directory.getIfAvailable();
        if (found == null) {
            return false;
        }
        try {
            found.requireServed();
            return true;
        }
        catch (ScimException ex) {
            return false;
        }
    }
}
