package com.flowtivo.tinfirm.model.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.model.ModelEvents;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.GatewayModelList;
import com.flowtivo.tinfirm.shared.api.model.ModelGateway;
import com.flowtivo.tinfirm.shared.api.model.ModelGatewayVendor;
import com.flowtivo.tinfirm.shared.api.model.ModelSettings;
import com.flowtivo.tinfirm.shared.api.model.ModelSettingsRequest;
import com.flowtivo.tinfirm.shared.api.model.ModelSettingsTestResult;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.vault.SecretWriter;

/**
 * The company's model settings (the package 2 contract, §3.1, §6, §8): OWNER and ADMIN choose the gateway (the
 * deployment's bundled LiteLLM or the company's own) and the defaults of every key, and test it; every member lists the
 * models it serves. An {@code EXTERNAL} gateway's admin key is a company secret ({@code MODEL_GATEWAY_ADMIN_KEY}, or
 * {@code LITELLM_ADMIN_KEY} when it was entered before ADR-0094; the settings keep it by its id), entered
 * again with every new URL, so that a key is sent only where it was entered, and never returned; these settings are
 * the only way to a new key (the Secrets API neither replaces nor deletes it while they keep it,
 * {@link ModelSecretKeeper}). A change of the gateway, its URL or its key turns the relay off until the next test
 * passes, as before a first test, and forgets the former one's failure ({@code lastError}), so that the settings show
 * a gateway changed since its last test; the keys made at the old gateway are deleted there after the commit (they are
 * made again at the new one once it passed its test). A change of the defaults reaches every key without an override
 * after the commit, and before the answer the keys whose status it may change. LiteLLM is called outside any
 * transaction.
 */
@Service
class ModelSettingsService {

    static final String RELAY_PATH = ModelRelayImpl.ROOT;

    private static final String TARGET = "model_settings";

    private static final String ADMIN_KEY_SECRET = "MODEL_GATEWAY_ADMIN_KEY";

    private static final String DEFAULT_PERIOD = "30d";

    /** The key of the test of a gateway whose keys its team provides: one no gateway issues. */
    private static final String PROBE_KEY = "tinfirm-test-not-a-key";

    private static final int MAX_MODEL_NAME = 200;

    private static final Duration MODELS_CACHE = Duration.ofMinutes(1);

    private final TransactionTemplate transactions;

    private final CompanyAccessGuard companyAccess;

    private final CompanyDirectory companies;

    private final ModelSettingsStore store;

    private final ModelKeyStore keys;

    private final Gateways gateways;

    private final GatewayHttp gatewayHttp;

    private final GatewayAdapter adapter;

    private final KeyWork work;

    private final KeyReconciliation reconciliation;

    private final SecretWriter secrets;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final ModelProperties properties;

    private final TinfirmProperties tinfirm;

    private final TimeSource time;

    private final Map<UUID, CachedModels> models = new ConcurrentHashMap<>();

    private final BudgetAlertStore alerts;

    ModelSettingsService(PlatformTransactionManager transactionManager, CompanyAccessGuard companyAccess,
            CompanyDirectory companies, ModelSettingsStore store, ModelKeyStore keys, Gateways gateways,
            GatewayHttp gatewayHttp, GatewayAdapter adapter, KeyWork work, KeyReconciliation reconciliation,
            SecretWriter secrets, AuditLog auditLog, ApplicationEventPublisher events, ModelProperties properties,
            TinfirmProperties tinfirm, TimeSource time, BudgetAlertStore alerts) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.alerts = alerts;
        this.companyAccess = companyAccess;
        this.companies = companies;
        this.store = store;
        this.keys = keys;
        this.gateways = gateways;
        this.gatewayHttp = gatewayHttp;
        this.adapter = adapter;
        this.work = work;
        this.reconciliation = reconciliation;
        this.secrets = secrets;
        this.auditLog = auditLog;
        this.events = events;
        this.properties = properties;
        this.tinfirm = tinfirm;
        this.time = time;
    }

    private record CachedModels(Instant updatedAt, List<String> names, Instant until) {
    }

    /** The settings a save answers with, and the keys it syncs before the answer. */
    private record Saved(ModelSettings settings, List<KeyHolder> statusMayChange) {
    }

    ModelSettings get(UUID companyId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
            return view(store.find(companyId).orElse(null));
        }));
    }

    /**
     * Saves the settings, then syncs the keys whose status the new defaults may change before the answer, on the
     * caller's thread outside any transaction, so that the page, which reads the keys again after it, shows their
     * status: a spent key a raised default frees is {@code OK} again. The reconciliation after the commit reaches
     * every key.
     */
    ModelSettings put(UUID companyId, ModelSettingsRequest request) {
        Saved saved = Objects.requireNonNull(transactions.execute(status -> {
            companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
            Optional<ModelSettingsStore.SettingsRow> current = store.lock(companyId);
            ModelSettingsStore.GatewayKind gateway = ModelSettingsStore.GatewayKind.valueOf(request.getGateway()
                .getValue());
            String adminUrl = adminUrl(gateway, request, current.orElse(null));
            String modelUrl = modelUrl(gateway, request, current.orElse(null));
            GatewayVendor vendor = vendor(gateway, request, current.orElse(null));
            boolean memberKeys = memberKeys(gateway, request, current.orElse(null));
            String adminKey = blankToNull(request.getAdminKey());
            boolean wasExternal = current.isPresent()
                    && current.get().gateway() == ModelSettingsStore.GatewayKind.EXTERNAL;
            boolean connectionChanged = current.isEmpty() || current.get().gateway() != gateway
                    || !Objects.equals(current.get().adminUrl(), adminUrl)
                    || !Objects.equals(current.get().modelUrl(), modelUrl);
            if (gateway == ModelSettingsStore.GatewayKind.EXTERNAL && adminKey == null
                    && (connectionChanged || current.get().adminKeySecretId() == null)) {
                throw ApiException.invalid("adminKey is required: the gateway's admin key is entered with its URL",
                        "adminKey");
            }
            BigDecimal budget = budget(request.getDefaultBudgetUsd());
            String period = request.getBudgetPeriod() != null ? request.getBudgetPeriod()
                    : current.map(ModelSettingsStore.SettingsRow::budgetPeriod).orElse(DEFAULT_PERIOD);
            List<String> defaultModels = request.getDefaultModels() != null
                    ? modelNames("defaultModels", request.getDefaultModels())
                    : current.map(ModelSettingsStore.SettingsRow::defaultModels).orElse(List.of());

            List<String> changed = new ArrayList<>();
            if (current.isEmpty() || current.get().gateway() != gateway) {
                changed.add("gateway");
            }
            if (current.isEmpty() ? adminUrl != null : !Objects.equals(current.get().adminUrl(), adminUrl)) {
                changed.add("adminUrl");
            }
            if (adminKey != null) {
                changed.add("adminKey");
            }
            if (current.isEmpty() ? modelUrl != null : !Objects.equals(current.get().modelUrl(), modelUrl)) {
                changed.add("modelUrl");
            }
            if (current.isPresent() && current.get().vendor() != vendor) {
                changed.add("vendor");
            }
            if (current.isEmpty() ? memberKeys : current.get().memberKeys() != memberKeys) {
                changed.add("memberKeys");
            }
            if (current.isEmpty() || current.get().defaultBudgetUsd().compareTo(budget) != 0) {
                changed.add("defaultBudgetUsd");
            }
            if (current.isEmpty() || !current.get().budgetPeriod().equals(period)) {
                changed.add("budgetPeriod");
            }
            if (current.isEmpty() || !current.get().defaultModels().equals(defaultModels)) {
                changed.add("defaultModels");
            }
            BudgetAlertStore.AlertSettings currentAlerts = alerts.settings(companyId);
            BudgetAlertStore.AlertSettings nextAlerts = alertSettings(request, currentAlerts);
            if (!currentAlerts.thresholds().equals(nextAlerts.thresholds())) {
                changed.add("budgetAlertThresholds");
            }
            if (!sameAmount(currentAlerts.monthlyAlertUsd(), nextAlerts.monthlyAlertUsd())) {
                changed.add("monthlyAlertUsd");
            }
            if (changed.isEmpty()) {
                return new Saved(view(current.get()), List.of());
            }
            boolean neitherGatewayNorKeys = changed.stream()
                .allMatch(name -> name.equals("budgetAlertThresholds") || name.equals("monthlyAlertUsd"));
            if (neitherGatewayNorKeys) {
                // Package 6's alert settings (§5.3) touch neither the gateway nor the keys.
                alerts.setSettings(companyId, nextAlerts);
                ModelSettingsStore.SettingsRow kept = current.get();
                auditLog.record(AuditRecord.company(companyId, AuditAction.MODEL_SETTINGS_CHANGED, CurrentActor.actor())
                    .target(TARGET, companyId)
                    .detail("changed", changed)
                    .detail("gateway", gateway.name())
                    .detail("relayEnabled", kept.relayEnabled())
                    .build());
                return new Saved(view(kept), List.of());
            }
            boolean reconnect = connectionChanged || adminKey != null;
            // The keys made at the old gateway are deleted there, with its old key, after the commit; a gateway whose
            // keys its team provided keeps them, as its team decides.
            Optional<Gateway> oldGateway = connectionChanged && current.isPresent()
                    ? gateways.of(current.get()).filter(Gateway::managed)
                    : Optional.empty();

            UUID secretId = current.map(ModelSettingsStore.SettingsRow::adminKeySecretId).orElse(null);
            if (gateway == ModelSettingsStore.GatewayKind.EXTERNAL && adminKey != null) {
                if (secretId == null || !secrets.replace(companyId, secretId, adminKey)) {
                    secretId = secrets.create(companyId, ADMIN_KEY_SECRET,
                            "Admin key of the company's model gateway (Model settings)", adminKey);
                }
            }
            else if (gateway != ModelSettingsStore.GatewayKind.EXTERNAL && wasExternal && secretId != null) {
                secrets.delete(companyId, secretId);
                secretId = null;
            }
            Instant now = time.now();
            Actor actor = CurrentActor.actor();
            // A new connection (gateway, URL or key) is untested: the relay goes off, and a failure of the former one
            // no longer applies; testedAt keeps when the last test ran.
            boolean relayEnabled = !reconnect && current.get().relayEnabled();
            String lastError = reconnect ? null : current.get().lastError();
            ModelSettingsStore.SettingsRow next = new ModelSettingsStore.SettingsRow(companyId, gateway, vendor,
                    adminUrl, modelUrl, memberKeys,
                    gateway == ModelSettingsStore.GatewayKind.EXTERNAL ? secretId : null, relayEnabled, budget, period,
                    defaultModels, current.map(ModelSettingsStore.SettingsRow::testedAt).orElse(null), lastError, now,
                    actor.type() == ActorType.USER ? actor.id() : null);
            store.upsert(next);
            alerts.setSettings(companyId, nextAlerts);
            List<String> oldKeyIds = connectionChanged ? forgetKeys(companyId, now) : List.of();
            auditLog.record(AuditRecord.company(companyId, AuditAction.MODEL_SETTINGS_CHANGED, actor)
                .target(TARGET, companyId)
                .detail("changed", changed)
                .detail("gateway", gateway.name())
                .detail("relayEnabled", relayEnabled)
                .build());
            events.publishEvent(new ModelEvents.ModelSettingsChanged(companyId, relayEnabled));
            AfterCommit.run(() -> gateways.forget(companyId));
            if (oldGateway.isPresent() && !oldKeyIds.isEmpty()) {
                Gateway old = oldGateway.get();
                work.runAfterCommit("Deleting the keys at a company's former gateway", () -> oldKeyIds
                    .forEach(keyId -> work.delete(old, keyId, keyId)));
            }
            List<KeyHolder> statusMayChange = List.of();
            if (relayEnabled) {
                reconciliation.reconcileAfterCommit(companyId);
                statusMayChange = statusMayChange(companyId, changed, budget);
            }
            return new Saved(view(next), statusMayChange);
        }));
        saved.statusMayChange().forEach(work::syncForAnswer);
        return saved.settings();
    }

    /**
     * The keys whose status the changed defaults may change, as far as their last sync tells: a key that
     * follows the default budget and is spent, or whose spend reaches the new budget; and while the period changed,
     * every spent key, whose reset time moves with it.
     */
    private List<KeyHolder> statusMayChange(UUID companyId, List<String> changed, BigDecimal budget) {
        boolean budgetChanged = changed.contains("defaultBudgetUsd");
        boolean periodChanged = changed.contains("budgetPeriod");
        List<KeyHolder> holders = new ArrayList<>();
        for (ModelKeyStore.KeyRow row : keys.all(companyId)) {
            boolean spent = row.status() == ModelKeyStore.KeyStatus.BUDGET_SPENT;
            boolean reached = row.status() == ModelKeyStore.KeyStatus.OK && row.spentUsd() != null
                    && row.spentUsd().compareTo(budget) >= 0;
            boolean followsDefault = row.budgetUsd() == null;
            if (row.hasKey() && (periodChanged && spent || budgetChanged && followsDefault && (spent || reached))) {
                holders.add(row.holder());
            }
        }
        return holders;
    }

    /**
     * Reaches LiteLLM's admin API with the settings, makes sure of the company's team (named after the company) and
     * lists the models it serves, outside any transaction; the outcome turns the relay on or off.
     */
    ModelSettingsTestResult test(UUID companyId) {
        record Target(ModelSettingsStore.SettingsRow settings, Optional<Gateway> gateway, String companyName) {
        }
        Target target = Objects.requireNonNull(transactions.execute(status -> {
            companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
            ModelSettingsStore.SettingsRow settings = store.find(companyId).orElseThrow(ModelSettingsService::missing);
            String companyName = companies.find(companyId)
                .map(CompanyDirectory.CompanySummary::name)
                .orElseGet(() -> Gateway.teamId(companyId));
            return new Target(settings, gateways.of(settings), companyName);
        }));
        List<String> names = List.of();
        String failure = null;
        if (target.gateway().isEmpty()) {
            failure = "The settings are incomplete: the gateway's admin key is missing";
        }
        else if (!target.gateway().get().managed()) {
            failure = probe(target.gateway().get());
        }
        else {
            Gateway gateway = target.gateway().get();
            try {
                // Forgotten first: the test reaches LiteLLM for the team whatever this process remembers of it.
                gateways.teamUnknown(gateway, Gateway.teamId(companyId));
                gateways.ensureTeam(gateway, Gateway.teamId(companyId), target.companyName(),
                        () -> adapter.ensureTeam(gateway, Gateway.teamId(companyId), companyId, target.companyName()));


                names = adapter.models(gateway);
            }
            catch (LiteLlmException ex) {
                failure = ex.getMessage();
            }
        }
        String reason = failure;
        boolean ok = failure == null;
        transactions.executeWithoutResult(status -> {
            companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
            ModelSettingsStore.SettingsRow settings = store.lock(companyId).orElseThrow(ModelSettingsService::missing);
            if (!settings.updatedAt().equals(target.settings().updatedAt())) {
                throw ApiException.conflict(ProblemCode.GATEWAY_NOT_CONFIGURED,
                        "The model settings changed during the test: test them again");
            }
            store.recordTest(companyId, ok, reason, time.now());
            AuditRecord.Builder audit = AuditRecord
                .company(companyId, AuditAction.MODEL_SETTINGS_CHANGED, CurrentActor.actor())
                .target(TARGET, companyId)
                .detail("tested", true)
                .detail("ok", ok)
                .detail("relayEnabled", ok);
            if (reason != null) {
                audit.detail("failure", reason);
            }
            auditLog.record(audit.build());
            events.publishEvent(new ModelEvents.ModelSettingsChanged(companyId, ok));
            if (ok) {
                // Every principal without a key gets one now: those that existed before the gateway was tested.
                reconciliation.reconcileAfterCommit(companyId);
            }
        });
        if (!ok) {
            throw unreachable(reason);
        }
        return new ModelSettingsTestResult(true, names);
    }

    /**
     * Whether a gateway whose keys its team provides answers at its model URL, without a key of Tinfirm's: its model
     * list with a key no gateway issues, which a gateway that checks keys refuses ({@code 401}, {@code 403}) and one
     * that does not lists. Null when it answered so; else why not. Each provided key is checked when it is entered.
     */
    private @Nullable String probe(Gateway gateway) {
        try {
            GatewayAnswers.modelsOfKey(gatewayHttp, gateway, PROBE_KEY, properties.adminTimeout(), "The gateway");
            return null;
        }
        catch (LiteLlmException ex) {
            return ex.status() == 401 || ex.status() == 403 ? null : ex.getMessage();
        }
    }

    /** The models the company's gateway serves (any member), read at most once a minute. */
    GatewayModelList models(UUID companyId) {
        record Target(Instant updatedAt, Gateway gateway) {
        }
        Target target = Objects.requireNonNull(transactions.execute(status -> {
            companyAccess.require(companyId, CompanyPermission.VIEW_MODEL_ACCESS);
            ModelSettingsStore.SettingsRow settings = store.find(companyId)
                .filter(ModelSettingsStore.SettingsRow::relayEnabled)
                .orElseThrow(ModelSettingsService::notTested);
            return new Target(settings.updatedAt(), gateways.of(settings).orElseThrow(ModelSettingsService::notTested));
        }));
        if (!target.gateway().managed()) {
            // Each provided key lists its own models; the gateway's whole list is its team's.
            return new GatewayModelList(new ArrayList<>());
        }
        Instant now = time.now();
        CachedModels cached = models.get(companyId);
        if (cached != null && cached.updatedAt().equals(target.updatedAt()) && now.isBefore(cached.until())) {
            return new GatewayModelList(cached.names());
        }
        try {
            List<String> names = adapter.models(target.gateway());
            models.put(companyId, new CachedModels(target.updatedAt(), names, now.plus(MODELS_CACHE)));
            return new GatewayModelList(names);
        }
        catch (LiteLlmException ex) {
            throw unreachable(ex.getMessage());
        }
    }

    // ---- rules -----------------------------------------------------------------------------------------------------

    /**
     * Forgets every key the company's rows hold (the gateway changed): their secrets go and the rows wait for the new
     * gateway, overrides kept; each audited as {@code model_key.deleted} with the cause {@code gateway.changed}, and a
     * key a gateway team provided, which stays at its gateway, also as {@code model_key.revoke_requested}. The ids of
     * the keys, with those rotations replaced that are still there, to delete at the old gateway.
     */
    private List<String> forgetKeys(UUID companyId, Instant now) {
        List<String> keyIds = new ArrayList<>();
        for (ModelKeyStore.KeyRow row : keys.lockAll(companyId)) {
            if (!row.hasKey()) {
                continue;
            }
            keys.forgetKey(row.holder(), now);
            secrets.deleteInternal(companyId, Objects.requireNonNull(row.secretId()));
            keyIds.add(Objects.requireNonNull(row.gatewayKeyId()));
            if (row.rotation().retiringKeyId() != null) {
                // The key a rotation replaced, still at the old gateway for its grace.
                keyIds.add(row.rotation().retiringKeyId());
            }
            auditLog.record(row.holder()
                .audit(AuditRecord.company(companyId, AuditAction.MODEL_KEY_DELETED, CurrentActor.actor()))
                .detail("cause", "gateway.changed")
                .build());
            if (row.provided() != null) {
                auditLog.record(row.holder()
                    .audit(AuditRecord.company(companyId, AuditAction.MODEL_KEY_REVOKE_REQUESTED,
                            CurrentActor.actor()))
                    .detail("cause", "gateway.changed")
                    .detail("hint", row.provided().hint())
                    .build());
            }
        }
        return keyIds;
    }

    private @Nullable String adminUrl(ModelSettingsStore.GatewayKind gateway, ModelSettingsRequest request,
            ModelSettingsStore.@Nullable SettingsRow current) {
        if (gateway != ModelSettingsStore.GatewayKind.EXTERNAL) {
            if (gateway == ModelSettingsStore.GatewayKind.BUNDLED && !properties.bundled()) {
                throw ApiException.invalid("This deployment bundles no gateway: choose EXTERNAL and enter the "
                        + "admin URL and key of the company's own, or PROVIDED and its model URL", "gateway");
            }
            if (request.getAdminUrl() != null || request.getAdminKey() != null) {
                throw ApiException.invalid("adminUrl and adminKey are for an EXTERNAL gateway only",
                        request.getAdminUrl() != null && request.getAdminKey() != null
                                ? new String[] { "adminUrl", "adminKey" }
                                : new String[] { request.getAdminUrl() != null ? "adminUrl" : "adminKey" });
            }
            return null;
        }
        String requested = blankToNull(request.getAdminUrl());
        if (requested == null) {
            if (current != null && current.gateway() == ModelSettingsStore.GatewayKind.EXTERNAL) {
                return current.adminUrl();
            }
            throw ApiException.invalid("adminUrl is required for an EXTERNAL gateway", "adminUrl");
        }
        String url = requested.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String problem = gatewayHttp.problem(url);
        if (problem != null) {
            String detail = "The admin URL is not usable: " + problem;
            throw ApiException.badRequest(ProblemCode.INVALID_BASE_URL, detail,
                    ApiException.fieldErrors(detail, "adminUrl"));
        }
        return url;
    }

    /** A {@code PROVIDED} gateway's model URL: required at first, kept when not sent; none for the others. */
    private @Nullable String modelUrl(ModelSettingsStore.GatewayKind gateway, ModelSettingsRequest request,
            ModelSettingsStore.@Nullable SettingsRow current) {
        String requested = blankToNull(request.getModelUrl());
        if (gateway != ModelSettingsStore.GatewayKind.PROVIDED) {
            if (requested != null) {
                throw ApiException.invalid("modelUrl is for a PROVIDED gateway only", "modelUrl");
            }
            return null;
        }
        if (requested == null) {
            if (current != null && current.gateway() == ModelSettingsStore.GatewayKind.PROVIDED) {
                return current.modelUrl();
            }
            throw ApiException.invalid("modelUrl is required for a PROVIDED gateway", "modelUrl");
        }
        String url = requested.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String problem = gatewayHttp.problem(url);
        if (problem != null) {
            String detail = "The model URL is not usable: " + problem;
            throw ApiException.badRequest(ProblemCode.INVALID_BASE_URL, detail,
                    ApiException.fieldErrors(detail, "modelUrl"));
        }
        return url;
    }

    /**
     * The gateway's vendor: {@code LITELLM} for every gateway Tinfirm manages; for a {@code PROVIDED} one as sent, or
     * as it was, or {@code GENERIC}.
     */
    private static GatewayVendor vendor(ModelSettingsStore.GatewayKind gateway, ModelSettingsRequest request,
            ModelSettingsStore.@Nullable SettingsRow current) {
        GatewayVendor requested = request.getVendor() != null ? GatewayVendor.valueOf(request.getVendor().getValue())
                : null;
        if (gateway != ModelSettingsStore.GatewayKind.PROVIDED) {
            if (requested != null && requested != GatewayVendor.LITELLM) {
                throw ApiException.invalid("A gateway Tinfirm manages is a LiteLLM: vendor GENERIC is for a PROVIDED "
                        + "gateway only", "vendor");
            }
            return GatewayVendor.LITELLM;
        }
        if (requested != null) {
            return requested;
        }
        return current != null && current.gateway() == ModelSettingsStore.GatewayKind.PROVIDED ? current.vendor()
                : GatewayVendor.GENERIC;
    }

    /** Whether members enter their own key: a {@code PROVIDED} gateway's choice, as sent or as it was; false else. */
    private static boolean memberKeys(ModelSettingsStore.GatewayKind gateway, ModelSettingsRequest request,
            ModelSettingsStore.@Nullable SettingsRow current) {
        if (gateway != ModelSettingsStore.GatewayKind.PROVIDED) {
            if (Boolean.TRUE.equals(request.getMemberKeys())) {
                throw ApiException.invalid("memberKeys is for a PROVIDED gateway only", "memberKeys");
            }
            return false;
        }
        if (request.getMemberKeys() != null) {
            return request.getMemberKeys();
        }
        return current != null && current.gateway() == ModelSettingsStore.GatewayKind.PROVIDED && current.memberKeys();
    }

    private static BigDecimal budget(BigDecimal requested) {
        if (requested.signum() < 0 || requested.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            throw ApiException.invalid("defaultBudgetUsd must be between 0 and 1000000", "defaultBudgetUsd");
        }
        return requested.setScale(4, RoundingMode.HALF_UP);
    }

    /** Model names as entered: trimmed, without blanks, whitespace inside or duplicates, in their order. */
    static List<String> modelNames(String field, List<String> requested) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : requested) {
            String trimmed = name == null ? "" : name.strip();
            if (trimmed.isEmpty() || trimmed.length() > MAX_MODEL_NAME
                    || trimmed.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                throw ApiException.invalid("A model name is 1 to " + MAX_MODEL_NAME
                        + " characters without whitespace", field);
            }
            names.add(trimmed);
        }
        return List.copyOf(names);
    }

    /** The alert settings a request asks for (package 6, §5.3); absent fields keep the stored ones. */
    private static BudgetAlertStore.AlertSettings alertSettings(ModelSettingsRequest request,
            BudgetAlertStore.AlertSettings current) {
        List<Integer> thresholds = current.thresholds();
        if (request.getBudgetAlertThresholds() != null) {
            java.util.TreeSet<Integer> sorted = new java.util.TreeSet<>();
            for (Integer threshold : request.getBudgetAlertThresholds()) {
                if (threshold == null || threshold < 1 || threshold > 100) {
                    throw ApiException.invalid("budgetAlertThresholds: whole percentages from 1 to 100",
                            "budgetAlertThresholds");
                }
                if (!sorted.add(threshold)) {
                    throw ApiException.invalid("budgetAlertThresholds: each percentage once", "budgetAlertThresholds");
                }
            }
            if (sorted.isEmpty() || sorted.size() > 3) {
                throw ApiException.invalid("budgetAlertThresholds: 1 to 3 percentages", "budgetAlertThresholds");
            }
            thresholds = List.copyOf(sorted);
        }
        BigDecimal monthly = current.monthlyAlertUsd();
        boolean clear = Boolean.TRUE.equals(request.getClearMonthlyAlert());
        if (clear && request.getMonthlyAlertUsd() != null) {
            throw ApiException.invalid("monthlyAlertUsd and clearMonthlyAlert exclude each other", "monthlyAlertUsd",
                    "clearMonthlyAlert");
        }
        if (clear) {
            monthly = null;
        }
        else if (request.getMonthlyAlertUsd() != null) {
            BigDecimal requested = request.getMonthlyAlertUsd();
            if (requested.signum() < 0 || requested.compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0) {
                throw ApiException.invalid("monthlyAlertUsd: 0 to 1000000000 USD", "monthlyAlertUsd");
            }
            monthly = requested.setScale(4, java.math.RoundingMode.HALF_UP);
        }
        return new BudgetAlertStore.AlertSettings(thresholds, monthly);
    }

    private static boolean sameAmount(@Nullable BigDecimal a, @Nullable BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private ModelSettings view(ModelSettingsStore.@Nullable SettingsRow row) {
        ModelSettings settings = new ModelSettings().bundledAvailable(properties.bundled())
            .relayBaseUrl(tinfirm.publicBaseUrl() + RELAY_PATH)
            .keyRotationInterval(iso(properties.keyRotationInterval()))
            .keyRotationGrace(iso(properties.keyRotationGrace()));
        if (row == null) {
            return settings.configured(false)
                .gateway(properties.bundled() ? ModelGateway.BUNDLED : ModelGateway.EXTERNAL)
                .vendor(ModelGatewayVendor.LITELLM)
                .memberKeys(false)
                .adminKeySet(false)
                .relayEnabled(false)
                .defaultBudgetUsd(BigDecimal.ZERO)
                .budgetPeriod(DEFAULT_PERIOD)
                .defaultModels(new ArrayList<>())
                .budgetAlertThresholds(new ArrayList<>(BudgetAlertStore.DEFAULT_THRESHOLDS));
        }
        boolean external = row.gateway() == ModelSettingsStore.GatewayKind.EXTERNAL;
        BudgetAlertStore.AlertSettings alertSettings = alerts.settings(row.companyId());
        return settings.configured(true)
            .gateway(ModelGateway.fromValue(row.gateway().name()))
            .vendor(ModelGatewayVendor.fromValue(row.vendor().name()))
            .modelUrl(row.modelUrl())
            .memberKeys(row.memberKeys())
            .adminUrl(Gateways.shownUrl(row))
            .adminKeySet(!external || row.adminKeySecretId() != null)
            .relayEnabled(row.relayEnabled())
            .defaultBudgetUsd(row.defaultBudgetUsd())
            .budgetPeriod(row.budgetPeriod())
            .defaultModels(new ArrayList<>(row.defaultModels()))
            .budgetAlertThresholds(new ArrayList<>(alertSettings.thresholds()))
            .monthlyAlertUsd(alertSettings.monthlyAlertUsd())
            .testedAt(ts(row.testedAt()))
            .lastError(row.lastError())
            .updatedAt(ts(row.updatedAt()))
            .updatedBy(row.updatedBy());
    }

    /** An ISO-8601 duration as it was configured: whole days as days ({@code P30D}), else hours and less. */
    static String iso(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds > 0 && seconds % 86_400 == 0 && duration.getNano() == 0 ? "P" + seconds / 86_400 + "D"
                : duration.toString();
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ApiException missing() {
        return ApiException.conflict(ProblemCode.GATEWAY_NOT_CONFIGURED,
                "The company has no model gateway yet: configure it in the model settings");
    }

    private static ApiException notTested() {
        return ApiException.conflict(ProblemCode.GATEWAY_NOT_CONFIGURED,
                "The company's model gateway is not configured, or has not passed its test");
    }

    private static ApiException unreachable(@Nullable String reason) {
        return new ApiException(HttpStatus.BAD_GATEWAY, ProblemCode.GATEWAY_UNREACHABLE,
                "The gateway did not answer as expected: " + (reason != null ? reason : "no reason given"));
    }
}
