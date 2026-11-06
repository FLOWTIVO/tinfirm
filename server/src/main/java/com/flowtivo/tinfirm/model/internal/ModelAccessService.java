package com.flowtivo.tinfirm.model.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.HostCredentialInfo;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ModelAccessUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.ModelAccessView;
import com.flowtivo.tinfirm.shared.api.model.ModelKeyStatus;
import com.flowtivo.tinfirm.shared.api.model.ModelSubscription;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * A principal's model access (the package 2 contract, §3.2, §8): a member reads their own, with the subscriptions on
 * their personal runner hosts; OWNER and ADMIN read any principal's and set or clear its budget and models
 * ({@code model_key.budget_changed}), which reach LiteLLM after the commit and before the answer. OWNER and ADMIN read
 * and set the company's own key's alike (ADR-0065). All show the key's age and its rotation (CONVENTIONS §15.4). The
 * key itself is never shown.
 */
@Service
class ModelAccessService {

    private static final String DEFAULT_PERIOD = "30d";

    private final TransactionTemplate transactions;

    private final CompanyAccessGuard companyAccess;

    private final Principals principals;

    private final ModelSettingsStore settingsStore;

    private final ModelKeyStore keys;

    private final RunnerDirectory runners;

    private final RunnerConnections connections;

    private final KeyWork work;

    private final AuditLog auditLog;

    private final ModelProperties properties;

    private final TimeSource time;

    ModelAccessService(PlatformTransactionManager transactionManager, CompanyAccessGuard companyAccess,
            Principals principals, ModelSettingsStore settingsStore, ModelKeyStore keys, RunnerDirectory runners,
            RunnerConnections connections, KeyWork work, AuditLog auditLog, ModelProperties properties,
            TimeSource time) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.companyAccess = companyAccess;
        this.principals = principals;
        this.settingsStore = settingsStore;
        this.keys = keys;
        this.runners = runners;
        this.connections = connections;
        this.work = work;
        this.auditLog = auditLog;
        this.properties = properties;
        this.time = time;
    }

    /** The caller's own model access; 404 for a caller without a principal in the company. */
    @Transactional(readOnly = true)
    public ModelAccessView mine(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.VIEW_MODEL_ACCESS);
        AuthenticatedUser user = CurrentActor.requireUser();
        PrincipalInfo principal = principals.ofMember(companyId, user.id())
            .orElseThrow(() -> ApiException.notFound("You have no model access in this company"));
        return view(KeyHolder.principal(companyId, principal.id()))
            .subscriptions(subscriptions(companyId, principal.id()));
    }

    /** A principal's model access (OWNER and ADMIN); 404 for an unknown principal or one that left. */
    @Transactional(readOnly = true)
    public ModelAccessView get(UUID companyId, UUID principalId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
        requirePrincipal(companyId, principalId);
        return view(KeyHolder.principal(companyId, principalId));
    }

    /** The company's own key (OWNER and ADMIN; ADR-0065): its budget, models, spend and rotation. */
    @Transactional(readOnly = true)
    public ModelAccessView company(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
        return view(KeyHolder.company(companyId));
    }

    /**
     * Sets or clears the principal's budget and models (OWNER and ADMIN), and brings its key in LiteLLM to them before
     * the answer, on the caller's thread outside any transaction, so that the answer shows the status they give the
     * key: a spent budget raised above the spend is {@code OK} again. While another replica works on the key it follows
     * a moment later; a failure of LiteLLM is the key's {@code ERROR}, which the reconciliation repairs.
     */
    public ModelAccessView update(UUID companyId, UUID principalId, ModelAccessUpdateRequest request) {
        return update(KeyHolder.principal(companyId, principalId), request);
    }

    /** Sets or clears the company's own key's budget and models (OWNER and ADMIN), as for a principal's. */
    public ModelAccessView updateCompany(UUID companyId, ModelAccessUpdateRequest request) {
        return update(KeyHolder.company(companyId), request);
    }

    private ModelAccessView update(KeyHolder holder, ModelAccessUpdateRequest request) {
        UUID companyId = holder.companyId();
        boolean changed = Boolean.TRUE.equals(transactions.execute(status -> change(holder, request)));
        if (changed) {
            work.syncForAnswer(holder);
        }
        // The guard again, as for any read: the caller's rights may have changed while LiteLLM answered.
        return Objects.requireNonNull(transactions.execute(status -> {
            companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
            return view(holder);
        }));
    }

    /** Records the key's new budget and models, audited; whether anything changed. */
    private boolean change(KeyHolder holder, ModelAccessUpdateRequest request) {
        UUID companyId = holder.companyId();
        companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
        if (!holder.isCompany()) {
            requirePrincipal(companyId, holder.requirePrincipal());
        }
        boolean clearBudget = Boolean.TRUE.equals(request.getClearBudget());
        boolean clearModels = Boolean.TRUE.equals(request.getClearModels());
        if (clearBudget && request.getBudgetUsd() != null) {
            throw ApiException.invalid("Set a value or clear it, not both", "budgetUsd", "clearBudget");
        }
        if (clearModels && request.getModels() != null) {
            throw ApiException.invalid("Set a value or clear it, not both", "models", "clearModels");
        }
        Instant now = time.now();
        keys.insertPending(holder, now);
        ModelKeyStore.KeyRow row = keys.lock(holder).orElseThrow();
        BigDecimal budget = clearBudget ? null
                : request.getBudgetUsd() != null ? budget(request.getBudgetUsd()) : row.budgetUsd();
        List<String> models = clearModels ? null
                : request.getModels() != null ? ModelSettingsService.modelNames("models", request.getModels())
                : row.models();
        List<String> changed = new ArrayList<>();
        if (!sameBudget(budget, row.budgetUsd())) {
            changed.add("budgetUsd");
        }
        if (!Objects.equals(models, row.models())) {
            changed.add("models");
        }
        if (!changed.isEmpty()) {
            keys.setOverrides(holder, budget, models, now);
            auditLog.record(holder
                .audit(AuditRecord.company(companyId, AuditAction.MODEL_KEY_BUDGET_CHANGED, CurrentActor.actor()))
                .detail("changed", changed)
                .detail("budgetUsd", budget != null ? budget.toPlainString() : null)
                .detail("models", models)
                .build());
        }
        return !changed.isEmpty();
    }

    private void requirePrincipal(UUID companyId, UUID principalId) {
        principals.find(companyId, principalId)
            .filter(principal -> principal.status() != PrincipalStatus.LEFT)
            .orElseThrow(() -> ApiException.notFound("Principal not found"));
    }

    private ModelAccessView view(KeyHolder holder) {
        UUID companyId = holder.companyId();
        Optional<ModelSettingsStore.SettingsRow> settings = settingsStore.find(companyId);
        Optional<ModelKeyStore.KeyRow> row = keys.find(holder);
        BigDecimal defaultBudget = settings.map(ModelSettingsStore.SettingsRow::defaultBudgetUsd)
            .orElse(BigDecimal.ZERO);
        List<String> defaultModels = settings.map(ModelSettingsStore.SettingsRow::defaultModels).orElse(List.of());
        BigDecimal budget = row.map(ModelKeyStore.KeyRow::budgetUsd).orElse(null);
        List<String> models = row.map(ModelKeyStore.KeyRow::models).orElse(null);
        ModelAccessView view = new ModelAccessView().principalId(holder.principalId())
            .gatewayConfigured(settings.map(ModelSettingsStore.SettingsRow::relayEnabled).orElse(false))
            .budgetUsd(budget != null ? budget : defaultBudget)
            .budgetOverridden(budget != null)
            .budgetPeriod(settings.map(ModelSettingsStore.SettingsRow::budgetPeriod).orElse(DEFAULT_PERIOD))
            .models(new ArrayList<>(models != null ? models : defaultModels))
            .modelsOverridden(models != null)
            .subscriptions(new ArrayList<>());
        row.ifPresent(key -> view.keyStatus(ModelKeyStatus.fromValue(key.status().name()))
            .spentUsd(key.spentUsd())
            .budgetResetsAt(ts(key.budgetResetsAt()))
            .lastError(key.lastError())
            .syncedAt(ts(key.syncedAt())));
        row.ifPresent(key -> rotation(view, key));
        return view;
    }

    /**
     * The key's age and rotation: when it was created and last rotated, when it is rotated next (an admin's request
     * that is not carried out yet, else its creation plus the interval), the last rotation's failure, and while the key
     * it replaced is still in LiteLLM, when that one is blocked.
     */
    private void rotation(ModelAccessView view, ModelKeyStore.KeyRow key) {
        ModelKeyStore.Rotation rotation = key.rotation();
        Instant created = key.hasKey() ? rotation.keyCreatedAt() : null;
        Instant next = rotation.requestedAt() != null ? rotation.requestedAt()
                : created != null ? created.plus(properties.keyRotationInterval()) : null;
        view.keyCreatedAt(ts(created))
            .keyAgeDays(created != null ? (int) Math.max(0, Duration.between(created, time.now()).toDays()) : null)
            .keyRotatedAt(ts(key.hasKey() ? rotation.keyRotatedAt() : null))
            .nextKeyRotationAt(ts(next))
            .keyRotationRequestedAt(ts(rotation.requestedAt()))
            .keyRotationError(rotation.error())
            .previousKeyValidUntil(ts(rotation.retiringKeyId() != null ? rotation.retiringUntil() : null));
    }

    /** The subscriptions on the principal's personal runner hosts, OFFLINE while a host is not connected. */
    private List<ModelSubscription> subscriptions(UUID companyId, UUID principalId) {
        Map<UUID, RunnerHostInfo> hosts = runners.hosts(companyId)
            .stream()
            .filter(host -> host.personalOf(principalId))
            .collect(Collectors.toMap(RunnerHostInfo::id, Function.identity()));
        List<ModelSubscription> subscriptions = new ArrayList<>();
        for (HostCredentialInfo credential : runners.credentials(companyId, hosts.keySet())) {
            RunnerHostInfo host = hosts.get(credential.runnerHostId());
            boolean online = connections.isConnected(credential.runnerHostId());
            subscriptions.add(new ModelSubscription().runnerHostId(credential.runnerHostId())
                .runnerHostName(host.name())
                .label(credential.label())
                .kind(ModelSubscription.KindEnum.fromValue(credential.kind().name()))
                .runtimes(credential.runtimes()
                    .stream()
                    .map(Enum::name)
                    .sorted()
                    .map(ModelSubscription.RuntimesEnum::fromValue)
                    .collect(Collectors.toCollection(ArrayList::new)))
                .status(ModelSubscription.StatusEnum.fromValue(online ? credential.state().name() : "OFFLINE"))
                .reported(credential.reported()));
        }
        return subscriptions;
    }

    private static BigDecimal budget(BigDecimal requested) {
        if (requested.signum() < 0 || requested.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            throw ApiException.invalid("budgetUsd must be between 0 and 1000000", "budgetUsd");
        }
        return requested.setScale(4, RoundingMode.HALF_UP);
    }

    private static boolean sameBudget(@Nullable BigDecimal a, @Nullable BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
