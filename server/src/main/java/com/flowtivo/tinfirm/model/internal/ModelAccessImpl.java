package com.flowtivo.tinfirm.model.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.model.RunModelContext;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.HostCredentialInfo;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * {@link ModelAccess#forRun} (the package 2 contract, §5.1): reads the paying principal, its key's row and the
 * company's settings, or the subscription's host and credential, in the caller's transaction; it calls nothing
 * outside the server. A key that is missing, still blocked after a resume, or spent past its reset time is synced
 * again after the commit, so that the run's next look finds it current.
 */
@Component
class ModelAccessImpl implements ModelAccess {

    private final Principals principals;

    private final ModelSettingsStore settingsStore;

    private final ModelKeyStore keys;

    private final RunnerDirectory runners;

    private final RunnerConnections connections;

    private final KeyWork work;

    private final TimeSource time;

    ModelAccessImpl(Principals principals, ModelSettingsStore settingsStore, ModelKeyStore keys,
            RunnerDirectory runners, RunnerConnections connections, KeyWork work, TimeSource time) {
        this.principals = principals;
        this.settingsStore = settingsStore;
        this.keys = keys;
        this.runners = runners;
        this.connections = connections;
        this.work = work;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public RunModelAccess forRun(RunModelContext context) {
        UUID companyId = context.companyId();
        Optional<PrincipalInfo> payer = principals.find(companyId, context.payingPrincipalId());
        if (payer.isEmpty() || !payer.get().active()) {
            return new RunModelAccess.Refuse(RunModelAccess.RefuseReason.PRINCIPAL_INACTIVE,
                    "The principal that pays for the run is suspended or no longer in the company");
        }
        return switch (context.access()) {
            case ModelAccessChoice.Budget budget -> budget(companyId, payer.get());
            case ModelAccessChoice.Subscription subscription -> subscription(companyId, payer.get(), subscription);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> models(UUID companyId, UUID principalId) {
        List<String> override = keys.find(KeyHolder.principal(companyId, principalId))
            .map(ModelKeyStore.KeyRow::models)
            .orElse(null);
        if (override != null) {
            return override;
        }
        return settingsStore.find(companyId).map(ModelSettingsStore.SettingsRow::defaultModels).orElse(List.of());
    }

    @Override
    public void refresh(UUID companyId, UUID principalId) {
        work.syncAfterCommit(companyId, principalId);
    }

    private RunModelAccess budget(UUID companyId, PrincipalInfo payer) {
        Optional<ModelSettingsStore.SettingsRow> settings = settingsStore.find(companyId);
        if (settings.isEmpty() || !settings.get().relayEnabled()) {
            return new RunModelAccess.Refuse(RunModelAccess.RefuseReason.GATEWAY_NOT_CONFIGURED,
                    "The company has no model gateway that passed its test: an admin configures it in the model "
                            + "settings");
        }
        Optional<ModelKeyStore.KeyRow> row = keys.find(KeyHolder.principal(companyId, payer.id()));
        if (row.isEmpty() || !row.get().hasKey()) {
            work.syncAfterCommit(companyId, payer.id());
            String reason = row.map(ModelKeyStore.KeyRow::lastError).orElse(null);
            return new RunModelAccess.Wait(RunModelAccess.WaitReason.KEY_ERROR, null,
                    reason != null ? reason : "The principal's model key is being created");
        }
        ModelKeyStore.KeyRow key = row.get();
        List<String> models = key.models() != null ? key.models() : settings.get().defaultModels();
        Instant now = time.now();
        return switch (key.status()) {
            case OK -> new RunModelAccess.Budget(payer.id(), models);
            case BUDGET_SPENT -> {
                Instant resetsAt = key.budgetResetsAt();
                if (resetsAt != null && !resetsAt.isAfter(now)) {
                    // The period ended: LiteLLM has reset the spend or is about to; the key is read again.
                    work.syncAfterCommit(companyId, payer.id());
                    yield new RunModelAccess.Budget(payer.id(), models);
                }
                yield new RunModelAccess.Wait(RunModelAccess.WaitReason.BUDGET_SPENT, resetsAt,
                        "The principal's budget is spent until it resets");
            }
            case BLOCKED -> {
                // The principal is active again; its key is being unblocked.
                work.syncAfterCommit(companyId, payer.id());
                yield new RunModelAccess.Wait(RunModelAccess.WaitReason.KEY_ERROR, null,
                        "The principal's model key is being unblocked");
            }
            case ERROR -> new RunModelAccess.Wait(RunModelAccess.WaitReason.KEY_ERROR, null,
                    key.lastError() != null ? key.lastError() : "LiteLLM refused the principal's model key");
        };
    }

    private RunModelAccess subscription(UUID companyId, PrincipalInfo payer,
            ModelAccessChoice.Subscription subscription) {
        Optional<RunnerHostInfo> host = runners.host(companyId, subscription.runnerHostId());
        if (host.isEmpty()) {
            return gone("The runner host of the subscription no longer exists");
        }
        if (host.get().company()) {
            return new RunModelAccess.Refuse(RunModelAccess.RefuseReason.PERSONAL_HOST_CREDENTIAL_ONLY,
                    "A company runner host holds no subscriptions: only a member's personal host does");
        }
        if (!host.get().personalOf(payer.id())) {
            return gone("The subscription is on another member's host: it serves only its owner's runs");
        }
        Optional<HostCredentialInfo> credential = runners.credentials(companyId, List.of(subscription.runnerHostId()))
            .stream()
            .filter(candidate -> candidate.label().equals(subscription.label()))
            .findFirst();
        if (credential.isEmpty() || !credential.get().reported()) {
            return gone("The host no longer reports the subscription " + subscription.label());
        }
        if (!connections.isConnected(subscription.runnerHostId())) {
            return new RunModelAccess.Wait(RunModelAccess.WaitReason.HOST_OFFLINE, null,
                    "The personal runner host " + host.get().name() + " is not connected");
        }
        return new RunModelAccess.Subscription(subscription.runnerHostId(), subscription.label());
    }

    private static RunModelAccess.Refuse gone(String detail) {
        return new RunModelAccess.Refuse(RunModelAccess.RefuseReason.SUBSCRIPTION_GONE, detail);
    }
}
