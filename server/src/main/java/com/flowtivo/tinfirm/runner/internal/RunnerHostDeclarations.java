package com.flowtivo.tinfirm.runner.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.DeclaredRunnerHosts;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;

/**
 * The company runner hosts that the deployment declares (ADR-0101, decision 8). A declared host is a company host
 * with {@code declared} set: registered at the start that first declares it, audited {@code runner_host.registered}
 * with {@code declared}; its token's hash replaced when the declared token changes, audited
 * {@code runner_host.updated} with {@code changed: token}, and its connection, which the earlier token opened, closed
 * after the commit. Nothing else of it is declared: admins change its slots and pool, and may remove it, which the next
 * start undoes. A host registered in the app is never taken over, and a refused token is never in a message.
 */
@Component
class RunnerHostDeclarations implements DeclaredRunnerHosts {

    private static final Logger log = LoggerFactory.getLogger(RunnerHostDeclarations.class);

    /** A company host's name, as {@code runner_host_name_ck} checks it. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

    private final RunnerHostStore store;

    private final RunnerConnectionRegistry connections;

    private final AuditLog auditLog;

    private final TenantScope tenantScope;

    private final TimeSource time;

    RunnerHostDeclarations(RunnerHostStore store, RunnerConnectionRegistry connections, AuditLog auditLog,
            TenantScope tenantScope, TimeSource time) {
        this.store = store;
        this.connections = connections;
        this.auditLog = auditLog;
        this.tenantScope = tenantScope;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UUID companyId, String variable, @Nullable Host host) {
        tenantScope.enterCompany(companyId);
        if (host != null) {
            declare(companyId, variable, host);
        }
        List<String> stay = store.declaredHostNames(companyId)
            .stream()
            .filter(name -> host == null || !name.equals(host.name()))
            .toList();
        if (!stay.isEmpty()) {
            log.warn("Runner hosts that an earlier {}_NAME declared stay, and their tokens keep working, until an "
                    + "owner or admin of the company {} removes them: {}", variable, companyId, stay);
        }
    }

    private void declare(UUID companyId, String variable, Host host) {
        if (!NAME.matcher(host.name()).matches()) {
            throw new IllegalStateException(variable + "_NAME is not a runner host's name: letters, digits, '.', '_' "
                    + "and '-', starting with a letter or digit, at most 100 characters");
        }
        // The token's own format, compared whole: fromValue trims, and a declared token is taken as it is.
        if (!RunnerTokens.fromValue(host.token()).filter(host.token()::equals).isPresent()) {
            throw new IllegalStateException(variable + "_TOKEN is not a runner host token: "
                    + RunnerTokens.PREFIX + " and 43 base64url characters");
        }
        String hash = RunnerTokens.hash(host.token());
        Optional<RunnerHostStore.DeclaredHost> existing = store.lockCompanyHostNamed(companyId, host.name());
        if (existing.isPresent() && !existing.get().declared()) {
            throw new IllegalStateException(variable + "_NAME names the runner host '" + host.name()
                    + "', which was registered in the app: declare another name, or remove that host first");
        }
        if (existing.isPresent() && existing.get().tokenHash().equals(hash)) {
            return;
        }
        if (existing.isPresent()) {
            UUID hostId = existing.get().id();
            write(variable, () -> store.setDeclaredTokenHash(companyId, hostId, hash));
            auditLog.record(AuditRecord.company(companyId, AuditAction.RUNNER_HOST_UPDATED, CurrentActor.actor())
                .target(RunnerHostService.TARGET, hostId)
                .detail("name", host.name())
                .detail("changed", "token")
                .detail("declared", true)
                .build());
            // The connection that the earlier token opened ends; the host connects again with the new one.
            AfterCommit.run(() -> connections.disconnect(hostId, RunnerConnectionRegistry.INVALID_TOKEN));
            log.info("Replaced the token of the declared runner host {} of the company {}", host.name(), companyId);
            return;
        }
        UUID hostId = Ids.newId();
        write(variable, () -> store.insertDeclaredHost(hostId, companyId, host.name(), hash, time.now()));
        auditLog.record(AuditRecord.company(companyId, AuditAction.RUNNER_HOST_REGISTERED, CurrentActor.actor())
            .target(RunnerHostService.TARGET, hostId)
            .detail("name", host.name())
            .detail("scope", HostScope.COMPANY.name())
            .detail("declared", true)
            .build());
        log.info("Registered the declared runner host {} of the company {}", host.name(), companyId);
    }

    /**
     * Writes the token's hash. The name is the company's and was read under the lock, so the token is the only value
     * another host can have: {@code runner_host_token_hash_uq}.
     */
    private static void write(String variable, Runnable write) {
        try {
            write.run();
        }
        catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException(variable + "_TOKEN is another runner host's token: give each declared "
                    + "host a token of its own");
        }
    }
}
