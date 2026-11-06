package com.flowtivo.tinfirm.company.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.DeclaredCompanies;
import com.flowtivo.tinfirm.company.DeclaredRunnerHosts;
import com.flowtivo.tinfirm.company.MemberPrincipals;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserEvents;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.licence.LicenceChanged;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * Applies the companies the deployment declares (ADR-0075), as actor SYSTEM with the instance's rights. At every
 * start, in one transaction under an advisory lock, so that replicas starting together apply them once: each declared
 * company is found by its key, else adopted (by its id, else by its name among the companies without a key), else
 * created; then its declared owner, a local user, becomes its OWNER while it has none, and its declared runner host is
 * registered (ADR-0101, decision 8). A company no declaration names is kept, and named in a warning. After each sign-in, in a transaction of its own after the login's commit, the user
 * becomes the OWNER of each declared company that names their email and has none, when they signed in locally or
 * through a provider of that company: another company's provider, which asserts the emails of the users it creates,
 * never claims a company's ownership. A failure there never fails the login.
 */
@Service
@EnableConfigurationProperties(DeclaredCompaniesProperties.class)
class DeclaredCompaniesService implements DeclaredCompanies {

    private static final Logger log = LoggerFactory.getLogger(DeclaredCompaniesService.class);

    /** Arbitrary constant key of the declarations' advisory lock (the bootstrap's is ...01). */
    private static final long DECLARATIONS_LOCK_KEY = 0x7469_6e66_6972_6d02L;

    private final DeclaredCompaniesProperties properties;

    private final @Nullable String defaultOwner;

    private final CompanyService companies;

    private final CompanyQueries queries;

    private final MembershipRepository memberships;

    private final UserDirectory users;

    private final MemberPrincipals principals;

    private final TenantScope tenantScope;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final JdbcClient jdbc;

    private final TransactionTemplate afterCommit;

    private final Licensing licensing;

    private final DeclaredRunnerHosts runnerHosts;

    DeclaredCompaniesService(DeclaredCompaniesProperties properties,
            @Value("${tinfirm.bootstrap.admin-email:}") @Nullable String defaultOwner, CompanyService companies,
            CompanyQueries queries, MembershipRepository memberships, UserDirectory users, MemberPrincipals principals,
            TenantScope tenantScope, AuditLog auditLog, TimeSource time, JdbcClient jdbc,
            PlatformTransactionManager transactionManager, Licensing licensing, DeclaredRunnerHosts runnerHosts) {
        this.licensing = licensing;
        this.runnerHosts = runnerHosts;
        this.properties = properties;
        this.defaultOwner = defaultOwner;
        this.companies = companies;
        this.queries = queries;
        this.memberships = memberships;
        this.users = users;
        this.principals = principals;
        this.tenantScope = tenantScope;
        this.auditLog = auditLog;
        this.time = time;
        this.jdbc = jdbc;
        // After the login's or the key's commit its transaction is still bound to the thread: the work needs its own.
        this.afterCommit = new TransactionTemplate(transactionManager);
        this.afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void apply() {
        if (properties.companies().isEmpty()) {
            log.info("No company is declared (TINFIRM_COMPANIES_<KEY>_NAME): the companies stay as they are");
            return;
        }
        // A transaction of its own: after a key's commit (licenceChanged) the finished one is still bound to the thread.
        SystemContext.call(() -> afterCommit.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(DECLARATIONS_LOCK_KEY).query().singleRow();
            // ADR-0077, decision 4: a declared company beyond the first is Enterprise's, up to its key's cap; one that
            // exists keeps working whatever the licence (decision 7.3), and the others wait for a key that holds them.
            // The ones that exist count first, whatever their keys' order, so that a new one never takes their place:
            // a company with the key, or one from before keys that the declaration adopts, which adds no company.
            Integer allowed = licensing.status().companiesLicensed();
            Map<String, Optional<UUID>> existingByKey = new java.util.LinkedHashMap<>();
            java.util.Set<UUID> adopted = new java.util.HashSet<>();
            properties.companies().forEach((key, declaration) -> {
                Optional<UUID> keyed = queries.companyIdByKey(key);
                existingByKey.put(key, keyed);
                if (keyed.isEmpty()) {
                    adoptable(declaration).ifPresent(adopted::add);
                }
            });
            int declared = (int) existingByKey.values().stream().filter(Optional::isPresent).count() + adopted.size();
            List<String> waiting = new java.util.ArrayList<>();
            for (Map.Entry<String, DeclaredCompaniesProperties.Declaration> entry : properties.companies()
                .entrySet()) {
                String key = entry.getKey();
                DeclaredCompaniesProperties.Declaration declaration = entry.getValue();
                Optional<UUID> existing = existingByKey.get(key);
                // A declaration with an _ID, or whose name several companies without a key have, never creates: it
                // adopts, or the server does not start.
                boolean adopts = existing.isEmpty() && (declaration.id() != null
                        || queries.keylessNamed(declaration.name()).size() > 1
                        || adoptable(declaration).filter(adopted::contains).isPresent());
                if (existing.isEmpty() && !adopts && allowed != null && declared >= allowed) {
                    waiting.add(key);
                    continue;
                }
                if (existing.isEmpty() && !adopts) {
                    declared++;
                }
                UUID companyId = existing.orElseGet(() -> adoptOrCreate(key, declaration));
                String owner = declaration.ownerOr(defaultOwner);
                if (owner != null) {
                    // A local user only: an OIDC user's email is what some provider asserted.
                    users.findLocalByEmail(owner)
                        .filter(UserSummary::active)
                        .ifPresent(user -> claimOwner(companyId, user.id()));
                }
                runnerHosts.apply(companyId, variable(key, "RUNNER_HOST"), runnerHost(key, declaration));
            }
            if (!waiting.isEmpty()) {
                log.warn("Declared companies wait for a licence that holds them (ADR-0077: beyond the first is "
                        + "Enterprise's, up to its key's cap of {}): {}", allowed, waiting);
            }
            List<CompanyQueries.DeclaredRow> undeclared = queries.undeclared(properties.keys());
            if (!undeclared.isEmpty()) {
                log.warn("Companies that no declaration names stay as they are: {}", undeclared.stream()
                    .map(row -> row.name() + " (" + row.id() + (row.key() != null ? ", key " + row.key() : "") + ")")
                    .toList());
            }
            return null;
        }));
    }

    /** Another key came into force: the declarations that waited for one are applied (ADR-0077). */
    @TransactionalEventListener(fallbackExecution = true)
    void licenceChanged(LicenceChanged event) {
        try {
            apply();
        }
        catch (RuntimeException ex) {
            log.warn("The declared companies could not be applied after the licence changed: {}", ex.toString());
        }
    }

    /**
     * After the login's commit: the user becomes the OWNER of each declared company that names them and has none, if
     * they signed in locally or through that company's provider.
     */
    @TransactionalEventListener(fallbackExecution = true)
    void signedIn(UserEvents.UserSignedIn event) {
        if (properties.companies().isEmpty()) {
            return;
        }
        try {
            SystemContext.call(() -> afterCommit.execute(status -> {
                Optional<UserSummary> user = users.findById(event.userId());
                if (user.isEmpty() || !user.get().active()) {
                    return null;
                }
                String email = user.get().email().toLowerCase(java.util.Locale.ROOT);
                properties.companies().forEach((key, declaration) -> {
                    if (email.equals(declaration.ownerOr(defaultOwner))) {
                        queries.companyIdByKey(key)
                            .filter(companyId -> event.local() || event.providerCompanyIds().contains(companyId))
                            .ifPresent(companyId -> claimOwner(companyId, event.userId()));
                    }
                });
                return null;
            }));
        }
        catch (RuntimeException ex) {
            log.warn("The declared owners of user {} could not be applied after their sign-in: {}", event.userId(),
                    ex.toString());
        }
    }

    /**
     * The declared runner host, null for none; one value without the other stops the start. The token is checked by
     * the runner module, which knows its format.
     */
    private static DeclaredRunnerHosts.@Nullable Host runnerHost(String key,
            DeclaredCompaniesProperties.Declaration declaration) {
        DeclaredCompaniesProperties.RunnerHost host = declaration.runnerHost();
        if (host == null || host.blank()) {
            return null;
        }
        String name = host.name() != null ? host.name().trim() : "";
        String token = host.token() != null ? host.token().trim() : "";
        if (name.isEmpty() || token.isEmpty()) {
            throw new IllegalStateException(variable(key, "RUNNER_HOST_NAME") + " and "
                    + variable(key, "RUNNER_HOST_TOKEN") + " go together: set both, or neither");
        }
        return new DeclaredRunnerHosts.Host(name, token);
    }

    private static String variable(String key, String suffix) {
        return "TINFIRM_COMPANIES_" + key.toUpperCase(java.util.Locale.ROOT) + "_" + suffix;
    }

    /** The company from before keys that the declaration would adopt, by its id or its exact name, if any. */
    private Optional<UUID> adoptable(DeclaredCompaniesProperties.Declaration declaration) {
        if (declaration.id() != null) {
            return queries.declaredRow(declaration.id())
                .filter(row -> row.key() == null)
                .map(CompanyQueries.DeclaredRow::id);
        }
        List<CompanyQueries.DeclaredRow> named = queries.keylessNamed(declaration.name());
        return named.size() == 1 ? Optional.of(named.getFirst().id()) : Optional.empty();
    }

    private UUID adoptOrCreate(String key, DeclaredCompaniesProperties.Declaration declaration) {
        String variable = variable(key, "ID");
        if (declaration.id() != null) {
            CompanyQueries.DeclaredRow row = queries.declaredRow(declaration.id())
                .orElseThrow(() -> new IllegalStateException(variable + " names no company: " + declaration.id()));
            if (row.key() != null) {
                throw new IllegalStateException(variable + " names the company " + row.id()
                        + ", which is already declared by the key " + row.key());
            }
            return adopt(row, key, "id");
        }
        List<CompanyQueries.DeclaredRow> named = queries.keylessNamed(declaration.name());
        if (named.size() > 1) {
            throw new IllegalStateException(named.size() + " companies without a key are named '" + declaration.name()
                    + "': set " + variable + " to the one the key " + key + " declares");
        }
        if (named.size() == 1) {
            return adopt(named.getFirst(), key, "name");
        }
        UUID companyId = companies.create(declaration.name(), key, null);
        log.info("Created the declared company {} ({})", key, companyId);
        return companyId;
    }

    private UUID adopt(CompanyQueries.DeclaredRow row, String key, String by) {
        queries.setKey(row.id(), key, time.now());
        tenantScope.enterCompany(row.id());
        auditLog.record(AuditRecord.company(row.id(), AuditAction.COMPANY_UPDATED, CurrentActor.actor())
            .target("company", row.id())
            .detail("key", key)
            .detail("adoptedBy", by)
            .build());
        log.info("The declared company {} adopted the company {} by its {}", key, row.id(), by);
        return row.id();
    }

    /**
     * While the company has no OWNER, the user becomes it: added, or promoted from their role. Under the company's
     * lock, as the member operations take it, so that two of these never both add an owner.
     */
    private void claimOwner(UUID companyId, UUID userId) {
        tenantScope.enterCompany(companyId);
        queries.lockCompany(companyId);
        if (!queries.owners(companyId).isEmpty()) {
            return;
        }
        Optional<MembershipEntity> membership = memberships.findByCompanyIdAndUserId(companyId, userId);
        if (membership.isPresent()) {
            CompanyRole previous = membership.get().getRole();
            membership.get().changeRole(CompanyRole.OWNER, time.now());
            memberships.flush();
            auditLog.record(AuditRecord.company(companyId, AuditAction.MEMBER_ROLE_CHANGED, CurrentActor.actor())
                .target("user", userId)
                .detail("userId", userId.toString())
                .detail("previousRole", previous.name())
                .detail("role", CompanyRole.OWNER.name())
                .detail("declaredOwner", true)
                .build());
            return;
        }
        memberships.saveAndFlush(new MembershipEntity(companyId, userId, CompanyRole.OWNER, time.now()));
        auditLog.record(AuditRecord.company(companyId, AuditAction.MEMBER_ADDED, CurrentActor.actor())
            .target("user", userId)
            .detail("userId", userId.toString())
            .detail("role", CompanyRole.OWNER.name())
            .detail("declaredOwner", true)
            .build());
        principals.joined(companyId, userId);
    }
}
