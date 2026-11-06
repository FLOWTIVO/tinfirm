package com.flowtivo.tinfirm.model.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The reconciliation of keys (the package 2 contract, §6; M6; ADR-0065): every few minutes a {@code worker} replica
 * compares, for each company whose gateway passed its test, Tinfirm's keys with LiteLLM's and repairs what drifted:
 * every principal that has not left, and the company itself for its own key, gets its key synced (created when missing,
 * changed, blocked or unblocked, its spend read) and what is due of its rotation run (a key as old as the rotation
 * interval or one an admin asked to rotate is rotated, a previous key past its grace is blocked and then deleted, a
 * rotation that stopped half-way is finished: {@link KeyRotation}), and every key of the company's team whose alias is
 * Tinfirm's but that no key row holds, as its current or its previous key (a principal that left or was deleted while
 * LiteLLM could not be reached, a key that could not be recorded, a rotation's next key that a crash kept from being
 * recorded) is deleted. Keys whose alias is not Tinfirm's are never touched. Public for the tests, which run it on
 * demand; nothing else outside the module calls it.
 */
@Component
public class KeyReconciliation {

    private static final Logger log = LoggerFactory.getLogger(KeyReconciliation.class);

    private final SystemTransactions transactions;

    private final ModelSettingsStore settings;

    private final ModelKeyStore keys;

    private final Principals principals;

    private final Gateways gateways;

    private final GatewayAdapter adapter;

    private final KeyWork work;

    private final ObjectProvider<CompanyCleanup> cleanups;

    private final TinfirmProperties tinfirm;

    private final TimeSource time;

    KeyReconciliation(SystemTransactions transactions, ModelSettingsStore settings, ModelKeyStore keys,
            Principals principals, Gateways gateways, GatewayAdapter adapter, KeyWork work,
            ObjectProvider<CompanyCleanup> cleanups, TinfirmProperties tinfirm, TimeSource time) {
        this.transactions = transactions;
        this.settings = settings;
        this.keys = keys;
        this.principals = principals;
        this.gateways = gateways;
        this.adapter = adapter;
        this.work = work;
        this.cleanups = cleanups;
        this.tinfirm = tinfirm;
        this.time = time;
    }

    /** Other work of a company's model access that the reconciliation does each round (the relay's token sweep). */
    interface CompanyCleanup {

        void cleanUp(UUID companyId);
    }

    @Scheduled(fixedDelayString = "${tinfirm.model.reconcile-interval:PT5M}",
            initialDelayString = "${tinfirm.model.reconcile-initial-delay:PT1M}")
    void reconcileAll() {
        if (!tinfirm.hasRole(TinfirmProperties.WORKER_ROLE)) {
            return;
        }
        for (UUID companyId : transactions.unscoped(settings::enabledCompanies)) {
            try {
                reconcile(companyId);
            }
            catch (RuntimeException ex) {
                log.warn("Reconciling the LiteLLM keys of company {} failed", companyId, ex);
            }
        }
    }

    /** Reconciles the company's keys after the current transaction commits, off the caller's thread. */
    void reconcileAfterCommit(UUID companyId) {
        work.runAfterCommit("Reconciling the LiteLLM keys of a company", () -> reconcile(companyId));
    }

    /** Reconciles one company's keys now; never inside a transaction. */
    public void reconcile(UUID companyId) {
        List<UUID> current = transactions.inCompany(companyId, () -> principals.currentIds(companyId));
        for (UUID principalId : current) {
            work.syncNow(KeyHolder.principal(companyId, principalId));
        }
        work.syncNow(KeyHolder.company(companyId));
        sweep(companyId);
        cleanups.orderedStream().forEach(cleanup -> cleanup.cleanUp(companyId));
    }

    /** Deletes the keys of the company's team that carry Tinfirm's alias but that no key row holds. */
    private void sweep(UUID companyId) {
        Optional<Gateway> gateway = transactions.inCompany(companyId, () -> settings.find(companyId)
            .filter(ModelSettingsStore.SettingsRow::relayEnabled)
            .flatMap(gateways::of)
            .filter(Gateway::managed));
        if (gateway.isEmpty()) {
            return;
        }
        List<GatewayAdapter.KeyState> atGateway;
        try {
            atGateway = adapter.teamKeys(gateway.get(), Gateway.teamId(companyId));
        }
        catch (LiteLlmException ex) {
            log.warn("The keys of company {} could not be listed in LiteLLM: {}", companyId, ex.getMessage());
            return;
        }
        List<ModelKeyStore.KeyRow> rows = transactions.inCompany(companyId, () -> keys.all(companyId));
        Map<UUID, ModelKeyStore.KeyRow> byPrincipal = new HashMap<>();
        ModelKeyStore.KeyRow own = null;
        Set<String> held = new HashSet<>();
        for (ModelKeyStore.KeyRow row : rows) {
            if (row.holder().isCompany()) {
                own = row;
            }
            else {
                byPrincipal.put(row.holder().requirePrincipal(), row);
            }
            if (row.gatewayKeyId() != null) {
                held.add(row.gatewayKeyId());
            }
            if (row.rotation().retiringKeyId() != null) {
                held.add(row.rotation().retiringKeyId());
            }
        }
        String prefix = Gateway.aliasPrefix(companyId);
        Instant now = time.now();
        for (GatewayAdapter.KeyState key : atGateway) {
            String alias = key.alias();
            if (alias == null || !alias.startsWith(prefix) || held.contains(key.keyId())) {
                continue;
            }
            ModelKeyStore.KeyRow row = Gateway.companyKeyOf(alias, companyId) ? own
                    : Gateway.principalOf(alias, companyId).map(byPrincipal::get).orElse(null);
            if (row != null && row.syncLeaseUntil() != null && row.syncLeaseUntil().isAfter(now)) {
                // Its holder's key is being synced or rotated: the key may be about to be recorded.
                continue;
            }
            work.delete(gateway.get(), key.keyId(), alias);
        }
    }
}
