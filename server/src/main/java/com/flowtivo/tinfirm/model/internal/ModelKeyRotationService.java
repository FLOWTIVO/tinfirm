package com.flowtivo.tinfirm.model.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ModelAccessView;
import com.flowtivo.tinfirm.shared.api.model.ModelKeyRotationRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Rotation on suspicion (security review row 2 of the platform redesign; CONVENTIONS §15.4): OWNER and ADMIN rotate one
 * principal's key, the company's own (ADR-0065), or every key of the company, its own among them (a suspected leak of
 * the vault or of LiteLLM), at once and with no grace for the key it replaces. The request is recorded and audited
 * ({@code model_key.rotation_requested}) in its own transaction, so that it outlives a failure: {@link KeyRotation}
 * carries it out, on the caller's thread for one principal (outside any transaction), after the commit for the company,
 * and the reconciliation whatever is left.
 */
@Service
class ModelKeyRotationService {

    private final TransactionTemplate transactions;

    private final CompanyAccessGuard companyAccess;

    private final Principals principals;

    private final ModelSettingsStore settingsStore;

    private final ModelKeyStore keys;

    private final KeyWork work;

    private final ModelAccessService access;

    private final AuditLog auditLog;

    private final TimeSource time;

    ModelKeyRotationService(PlatformTransactionManager transactionManager, CompanyAccessGuard companyAccess,
            Principals principals, ModelSettingsStore settingsStore, ModelKeyStore keys, KeyWork work,
            ModelAccessService access, AuditLog auditLog, TimeSource time) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.companyAccess = companyAccess;
        this.principals = principals;
        this.settingsStore = settingsStore;
        this.keys = keys;
        this.work = work;
        this.access = access;
        this.auditLog = auditLog;
        this.time = time;
    }

    /**
     * Rotates the principal's key now: 200 once the next key is in place and the previous one blocked, 202 while
     * another replica works on the key (it is rotated in a moment), 502 when LiteLLM failed (the request stays).
     */
    ResponseEntity<ModelAccessView> rotate(UUID companyId, UUID principalId) {
        return rotate(KeyHolder.principal(companyId, principalId));
    }

    /** Rotates the company's own key now, as {@link #rotate(UUID, UUID)} a principal's. */
    ResponseEntity<ModelAccessView> rotateCompany(UUID companyId) {
        return rotate(KeyHolder.company(companyId));
    }

    private ResponseEntity<ModelAccessView> rotate(KeyHolder holder) {
        UUID companyId = holder.companyId();
        transactions.executeWithoutResult(status -> {
            companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
            if (!holder.isCompany()) {
                principals.find(companyId, holder.requirePrincipal())
                    .filter(principal -> principal.status() != PrincipalStatus.LEFT)
                    .orElseThrow(() -> ApiException.notFound("Principal not found"));
            }
            requireTested(companyId);
            Instant now = time.now();
            keys.insertPending(holder, now);
            keys.requestRotation(holder, now);
            audit(holder, holder.isCompany() ? "company_key" : "principal");
        });
        KeyWork.Synced done = work.syncNow(holder);
        if (done.busy()) {
            work.syncSoon(holder);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(holder));
        }
        ModelAccessView view = view(holder);
        // Failed: the next key is not in place, or the old one is not blocked yet.
        if (done.rotation().result() == KeyRotation.Result.FAILED || view.getKeyRotationRequestedAt() != null) {
            String reason = done.rotation().failure() != null ? done.rotation().failure()
                    : view.getKeyRotationError() != null ? view.getKeyRotationError()
                            : Objects.requireNonNullElse(view.getLastError(), "LiteLLM did not create the key");
            throw new ApiException(HttpStatus.BAD_GATEWAY, ProblemCode.GATEWAY_UNREACHABLE, reason
                    + ". The reconciliation carries the rotation out once LiteLLM answers");
        }
        return ResponseEntity.ok(view);
    }

    private ModelAccessView view(KeyHolder holder) {
        return holder.isCompany() ? access.company(holder.companyId())
                : access.get(holder.companyId(), holder.requirePrincipal());
    }

    /** Requests the rotation of every key of the company, its own among them, and carries it out after the commit. */
    ModelKeyRotationRequest rotateAll(UUID companyId) {
        Instant now = time.now();
        List<KeyHolder> requested = Objects.requireNonNull(transactions.execute(status -> {
            companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
            requireTested(companyId);
            List<KeyHolder> holders = new ArrayList<>();
            for (ModelKeyStore.KeyRow row : keys.lockAll(companyId)) {
                if (!row.hasKey()) {
                    continue;
                }
                keys.requestRotation(row.holder(), now);
                audit(row.holder(), "company");
                holders.add(row.holder());
            }
            work.runAfterCommit("Rotating every LiteLLM key of a company", () -> {
                for (KeyHolder holder : holders) {
                    if (work.syncNow(holder).busy()) {
                        work.syncSoon(holder);
                    }
                }
            });
            return holders;
        }));
        return new ModelKeyRotationRequest(requested.size(), now.atOffset(ZoneOffset.UTC));
    }

    private void requireTested(UUID companyId) {
        ModelSettingsStore.SettingsRow settings = settingsStore.find(companyId)
            .filter(ModelSettingsStore.SettingsRow::relayEnabled)
            .orElseThrow(() -> ApiException.conflict(ProblemCode.GATEWAY_NOT_CONFIGURED,
                    "The company's model gateway is not configured, or has not passed its test"));
        if (settings.gateway() == ModelSettingsStore.GatewayKind.PROVIDED) {
            throw ApiException.conflict(ProblemCode.MODEL_KEYS_MANAGED,
                    "The company's gateway team makes and rotates its keys: enter a new key instead");
        }
    }

    private void audit(KeyHolder holder, String scope) {
        auditLog.record(holder
            .audit(AuditRecord.company(holder.companyId(), AuditAction.MODEL_KEY_ROTATION_REQUESTED,
                    CurrentActor.actor()))
            .detail("scope", scope)
            .build());
    }
}
