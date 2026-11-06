package com.flowtivo.tinfirm.model.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalDependents;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.vault.SecretWriter;

/**
 * A principal's key follows its principal (the package 2 contract, §6; M1, M6):
 * <ul>
 * <li>created when the principal is created (a member joins, an admin creates a service identity), after the commit;
 * principals that existed before the company's gateway passed its test get theirs from the reconciliation;</li>
 * <li>blocked when the principal is suspended, unblocked when it is resumed, after the commit;</li>
 * <li>deleted when the member leaves or the service identity is deleted: the row and its secret go in that
 * transaction ({@code model_key.deleted} with the cause), and the key in LiteLLM after the commit, with the key a
 * rotation replaced if it is still there; what fails there the reconciliation deletes.</li>
 * </ul>
 * A key the company's gateway team provided (ADR-0094) is theirs to revoke: Tinfirm forgets it as any other, and asks
 * them to ({@code model_key.revoke_requested}).
 */
@Component
class KeyLifecycle implements PrincipalDependents {

    private final ModelKeyStore keys;

    private final SecretWriter secrets;

    private final KeyWork work;

    private final AuditLog auditLog;

    KeyLifecycle(ModelKeyStore keys, SecretWriter secrets, KeyWork work, AuditLog auditLog) {
        this.keys = keys;
        this.secrets = secrets;
        this.work = work;
        this.auditLog = auditLog;
    }

    @TransactionalEventListener
    void created(PrincipalEvents.PrincipalCreated event) {
        work.syncAfterCommit(event.companyId(), event.principalId());
    }

    @TransactionalEventListener
    void suspended(PrincipalEvents.PrincipalSuspended event) {
        work.syncAfterCommit(event.companyId(), event.principalId());
    }

    @TransactionalEventListener
    void resumed(PrincipalEvents.PrincipalResumed event) {
        work.syncAfterCommit(event.companyId(), event.principalId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void principalDeleting(UUID companyId, UUID principalId) {
        retire(companyId, principalId, "principal.deleted");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberLeaving(UUID companyId, UUID principalId, UUID userId) {
        retire(companyId, principalId, "principal.left");
    }

    private void retire(UUID companyId, UUID principalId, String cause) {
        KeyHolder holder = KeyHolder.principal(companyId, principalId);
        Optional<ModelKeyStore.KeyRow> row = keys.lock(holder);
        String alias = Gateway.keyAlias(companyId, principalId);
        if (row.isEmpty()) {
            // A key LiteLLM created but Tinfirm never recorded would carry the alias.
            work.deleteAfterCommit(companyId, null, alias);
            return;
        }
        keys.delete(holder);
        if (row.get().secretId() != null) {
            secrets.deleteInternal(companyId, row.get().secretId());
        }
        auditLog.record(holder
            .audit(AuditRecord.company(companyId, AuditAction.MODEL_KEY_DELETED, CurrentActor.actor()))
            .detail("cause", cause)
            .build());
        if (row.get().provided() != null) {
            auditLog.record(holder
                .audit(AuditRecord.company(companyId, AuditAction.MODEL_KEY_REVOKE_REQUESTED, CurrentActor.actor()))
                .detail("cause", cause)
                .detail("hint", row.get().provided().hint())
                .build());
            return;
        }
        work.deleteAfterCommit(companyId, row.get().gatewayKeyId(), alias);
        String previous = row.get().rotation().retiringKeyId();
        if (previous != null) {
            // The key a rotation replaced, still in LiteLLM for its grace, goes with it.
            work.deleteAfterCommit(companyId, previous, alias);
        }
    }
}
