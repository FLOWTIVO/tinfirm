package com.flowtivo.tinfirm.model.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.model.ModelEvents;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ModelAccessView;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.vault.SecretWriter;

/**
 * Keys a company's gateway team provides (ADR-0094): at a {@code PROVIDED} gateway Tinfirm makes no key, and OWNER and
 * ADMIN enter each principal's and the company's own, as does a member their own where the company's settings let them
 * ({@code memberKeys}). A key is checked with the gateway's model list before it is kept (outside any transaction), is
 * kept as the holder's internal secret, known by its SHA-256 as a key Tinfirm made is, and never shown again but for
 * its last four characters. A key is one holder's: one the company's rows already hold for another principal or for
 * the company is refused (M1). A member replaces or removes only a key they entered themselves. Audited as
 * {@code model_key.provided} and {@code model_key.removed}; never the key.
 */
@Service
class ProvidedKeyService {

    private static final int MIN_KEY = 8;

    private static final int MAX_KEY = 1000;

    private static final int HINT = 4;

    private final TransactionTemplate transactions;

    private final CompanyAccessGuard companyAccess;

    private final Principals principals;

    private final ModelSettingsStore settingsStore;

    private final ModelKeyStore keys;

    private final Gateways gateways;

    private final GatewayHttp http;

    private final SecretWriter secrets;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final ModelAccessService access;

    private final ModelProperties properties;

    private final TimeSource time;

    ProvidedKeyService(PlatformTransactionManager transactionManager, CompanyAccessGuard companyAccess,
            Principals principals, ModelSettingsStore settingsStore, ModelKeyStore keys, Gateways gateways,
            GatewayHttp http, SecretWriter secrets, AuditLog auditLog, ApplicationEventPublisher events,
            ModelAccessService access, ModelProperties properties, TimeSource time) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.companyAccess = companyAccess;
        this.principals = principals;
        this.settingsStore = settingsStore;
        this.keys = keys;
        this.gateways = gateways;
        this.http = http;
        this.secrets = secrets;
        this.auditLog = auditLog;
        this.events = events;
        this.access = access;
        this.properties = properties;
        this.time = time;
    }

    /** Who enters or removes a key: an admin for any holder, or a member for their own. */
    private enum Who {
        ADMIN, SELF
    }

    /** A principal's key (OWNER and ADMIN). */
    ModelAccessView putPrincipal(UUID companyId, UUID principalId, String key) {
        put(KeyHolder.principal(companyId, principalId), Who.ADMIN, key);
        return access.get(companyId, principalId);
    }

    ModelAccessView deletePrincipal(UUID companyId, UUID principalId) {
        delete(KeyHolder.principal(companyId, principalId), Who.ADMIN);
        return access.get(companyId, principalId);
    }

    /** The company's own key (OWNER and ADMIN). */
    ModelAccessView putCompany(UUID companyId, String key) {
        put(KeyHolder.company(companyId), Who.ADMIN, key);
        return access.company(companyId);
    }

    ModelAccessView deleteCompany(UUID companyId) {
        delete(KeyHolder.company(companyId), Who.ADMIN);
        return access.company(companyId);
    }

    /** The caller's own key, where the company lets members enter theirs. */
    ModelAccessView putMine(UUID companyId, String key) {
        put(KeyHolder.principal(companyId, myPrincipal(companyId)), Who.SELF, key);
        return access.mine(companyId);
    }

    ModelAccessView deleteMine(UUID companyId) {
        delete(KeyHolder.principal(companyId, myPrincipal(companyId)), Who.SELF);
        return access.mine(companyId);
    }

    private UUID myPrincipal(UUID companyId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            companyAccess.require(companyId, CompanyPermission.VIEW_MODEL_ACCESS);
            return principals.ofMember(companyId, CurrentActor.requireUser().id())
                .map(PrincipalInfo::id)
                .orElseThrow(() -> ApiException.notFound("You have no model access in this company"));
        }));
    }

    private void put(KeyHolder holder, Who who, String entered) {
        String key = checkedText(entered);
        record Target(Gateway gateway, Instant settingsAt) {
        }
        Target target = Objects.requireNonNull(transactions.execute(status -> {
            ModelSettingsStore.SettingsRow settings = settings(holder, who);
            requireHolder(holder);
            keys.find(holder).ifPresent(row -> requireMayChange(row, who));
            return new Target(gateways.of(settings).orElseThrow(), settings.updatedAt());
        }));
        try {
            GatewayAnswers.modelsOfKey(http, target.gateway(), key, properties.adminTimeout(), "The gateway");
        }
        catch (LiteLlmException ex) {
            if (ex.status() == 401 || ex.status() == 403) {
                throw ApiException.badRequest(ProblemCode.MODEL_KEY_REFUSED, "The gateway refused the key: "
                        + ex.getMessage(), ApiException.fieldErrors("The gateway refused the key", "key"));
            }
            if (ex.status() != 429) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, ProblemCode.GATEWAY_UNREACHABLE,
                        "The gateway did not answer as expected: " + ex.getMessage());
            }
            // The gateway knows the key, and limits it now (its budget, or a rate): the relay learns which.
        }
        transactions.executeWithoutResult(status -> {
            settings(holder, who);
            // The company's settings row, locked: the key entries of one company wait for each other here, so that
            // the check below that no other holder has this key holds until the commit, and a change of the settings
            // made meanwhile is seen.
            ModelSettingsStore.SettingsRow settings = settingsStore.lock(holder.companyId()).orElseThrow();
            if (!settings.updatedAt().equals(target.settingsAt())) {
                throw ApiException.conflict(ProblemCode.MODEL_KEYS_MANAGED,
                        "The model settings changed while the key was checked: enter it again");
            }
            PrincipalInfo principal = requireHolder(holder);
            Instant now = time.now();
            keys.insertPending(holder, now);
            ModelKeyStore.KeyRow row = keys.lock(holder).orElseThrow();
            requireMayChange(row, who);
            String keyId = RelayUsageMeter.keyId(key);
            if (keys.all(holder.companyId()).stream()
                .anyMatch(other -> !other.holder().equals(holder) && keyId.equals(other.gatewayKeyId()))) {
                throw ApiException.invalid("This key is already entered for another principal or for the company: "
                        + "each needs a key of its own", "key");
            }
            boolean replaced = row.secretId() != null;
            if (row.secretId() != null) {
                secrets.deleteInternal(holder.companyId(), row.secretId());
            }
            UUID secretId = secrets.createInternal(holder.companyId(), holder.secretName(),
                    holder.secretDescription(), key);
            ModelKeyStore.KeyStatus next = principal != null && principal.status() == PrincipalStatus.SUSPENDED
                    ? ModelKeyStore.KeyStatus.BLOCKED
                    : ModelKeyStore.KeyStatus.OK;
            String hint = key.substring(key.length() - HINT);
            keys.recordProvided(holder, secretId, keyId, hint, userId(), who == Who.SELF, next, now);
            auditLog.record(holder
                .audit(AuditRecord.company(holder.companyId(), AuditAction.MODEL_KEY_PROVIDED, CurrentActor.actor()))
                .detail("by", who == Who.SELF ? "self" : "admin")
                .detail("hint", hint)
                .detail("replaced", replaced)
                .build());
            statusChanged(holder, row.status(), next);
        });
    }

    private void delete(KeyHolder holder, Who who) {
        transactions.executeWithoutResult(status -> {
            settings(holder, who);
            requireHolder(holder);
            Optional<ModelKeyStore.KeyRow> row = keys.lock(holder);
            if (row.isEmpty() || !row.get().hasKey()) {
                return;
            }
            requireMayChange(row.get(), who);
            secrets.deleteInternal(holder.companyId(), Objects.requireNonNull(row.get().secretId()));
            keys.removeProvided(holder, time.now());
            auditLog.record(holder
                .audit(AuditRecord.company(holder.companyId(), AuditAction.MODEL_KEY_REMOVED, CurrentActor.actor()))
                .detail("by", who == Who.SELF ? "self" : "admin")
                .detail("hint", row.get().provided() != null ? row.get().provided().hint() : null)
                .build());
            statusChanged(holder, row.get().status(), ModelKeyStore.KeyStatus.ERROR);
        });
    }

    /**
     * The company's settings, which must name a gateway whose keys its team provides; for a member's own key, one that
     * lets members enter theirs. Checks the caller's right first.
     */
    private ModelSettingsStore.SettingsRow settings(KeyHolder holder, Who who) {
        UUID companyId = holder.companyId();
        companyAccess.require(companyId,
                who == Who.SELF ? CompanyPermission.VIEW_MODEL_ACCESS : CompanyPermission.MANAGE_MODEL_ACCESS);
        ModelSettingsStore.SettingsRow settings = settingsStore.find(companyId)
            .filter(row -> row.gateway() == ModelSettingsStore.GatewayKind.PROVIDED)
            .orElseThrow(() -> ApiException.conflict(ProblemCode.MODEL_KEYS_MANAGED,
                    "Tinfirm makes this company's model keys itself: a key is entered only at a gateway whose keys "
                            + "its team provides"));
        if (who == Who.SELF && !settings.memberKeys()) {
            throw ApiException.conflict(ProblemCode.MODEL_KEYS_MANAGED,
                    "The company's admins enter the model keys: members do not enter their own");
        }
        return settings;
    }

    /** The holder's principal, which must exist and not have left; null for the company's own key. */
    private @Nullable PrincipalInfo requireHolder(KeyHolder holder) {
        if (holder.isCompany()) {
            return null;
        }
        return principals.lockShared(holder.companyId(), holder.requirePrincipal())
            .filter(principal -> principal.status() != PrincipalStatus.LEFT)
            .orElseThrow(() -> ApiException.notFound("Principal not found"));
    }

    /** A member changes only a key they entered themselves, or none. */
    private static void requireMayChange(ModelKeyStore.KeyRow row, Who who) {
        if (who == Who.SELF && row.provided() != null && !row.provided().bySelf()) {
            throw ApiException.conflict(ProblemCode.MODEL_KEYS_MANAGED,
                    "An admin entered your model key: ask them to change it");
        }
    }

    /** The key as entered, without surrounding whitespace: 8 to 1000 printable characters without spaces. */
    private static String checkedText(@Nullable String entered) {
        String key = entered != null ? entered.strip() : "";
        // Visible ASCII only: the key goes into a request header, which takes nothing else.
        if (key.length() < MIN_KEY || key.length() > MAX_KEY || key.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw ApiException.invalid("A key is " + MIN_KEY + " to " + MAX_KEY
                    + " visible ASCII characters, without spaces", "key");
        }
        return key;
    }

    private static @Nullable UUID userId() {
        Actor actor = CurrentActor.actor();
        return actor.type() == ActorType.USER ? actor.id() : null;
    }

    private void statusChanged(KeyHolder holder, ModelKeyStore.KeyStatus previous, ModelKeyStore.KeyStatus status) {
        if (previous != status && !holder.isCompany()) {
            events.publishEvent(new ModelEvents.ModelKeyStatusChanged(holder.companyId(), holder.requirePrincipal(),
                    status.name()));
        }
    }
}
