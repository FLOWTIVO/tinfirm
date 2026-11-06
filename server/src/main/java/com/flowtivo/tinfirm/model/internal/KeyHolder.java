package com.flowtivo.tinfirm.model.internal;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.audit.AuditRecord;

/**
 * Whose LiteLLM key a key row is: a principal's ({@code principal_model_key}, the package 2 contract, §3.2), or the
 * company's own ({@code company_model_key}, ADR-0065), which pays for Tinfirm's own requests and is no principal's.
 * Both are synced, rotated, reconciled and forgotten by the same work; what differs is here: the table, the alias,
 * the internal secret's name, the audit target, and that only a principal's key is blocked (its principal suspended),
 * alerts on its budget and wakes the runs that wait on it.
 *
 * @param principalId the principal; null for the company's own key
 */
record KeyHolder(UUID companyId, @Nullable UUID principalId) {

    /** The audit target of a principal's key: its principal's id, which the audit names (AuditNames). */
    static final String PRINCIPAL_TARGET = "principal_model_key";

    /** The audit target of the company's own key: the company's id. */
    static final String COMPANY_TARGET = "company_model_key";

    KeyHolder {
        Objects.requireNonNull(companyId, "companyId");
    }

    static KeyHolder principal(UUID companyId, UUID principalId) {
        return new KeyHolder(companyId, Objects.requireNonNull(principalId, "principalId"));
    }

    static KeyHolder company(UUID companyId) {
        return new KeyHolder(companyId, null);
    }

    boolean isCompany() {
        return principalId == null;
    }

    /** The principal; only for a principal's key. */
    UUID requirePrincipal() {
        if (principalId == null) {
            throw new IllegalStateException("The company's own key has no principal");
        }
        return principalId;
    }

    /**
     * What the key's work in this process is keyed by: the principal's id, or the company's for its own key. The two
     * are random UUIDs of different tables, so they never meet.
     */
    UUID workId() {
        return principalId != null ? principalId : companyId;
    }

    /** The alias of the key of a generation (see {@link Gateway#keyAlias} and {@link Gateway#companyKeyAlias}). */
    String alias(int generation) {
        return principalId != null ? Gateway.keyAlias(companyId, principalId, generation)
                : Gateway.companyKeyAlias(companyId, generation);
    }

    /** The name of the key's internal secret: one per holder, which the vault suffixes when reused. */
    String secretName() {
        return principalId != null ? "MODEL_KEY_" + principalId.toString().replace("-", "") : "MODEL_KEY_COMPANY";
    }

    String secretDescription() {
        return principalId != null ? "LiteLLM key of a principal (model access, kept by Tinfirm)"
                : "LiteLLM key of the company (Tinfirm's own requests, kept by Tinfirm)";
    }

    /** The audit target's type: a principal's key is its principal's, the company's own key the company's. */
    String auditTarget() {
        return principalId != null ? PRINCIPAL_TARGET : COMPANY_TARGET;
    }

    UUID auditTargetId() {
        return principalId != null ? principalId : companyId;
    }

    /** An audit record of the key: its target, and its principal or {@code holder: company}. */
    AuditRecord.Builder audit(AuditRecord.Builder record) {
        record.target(auditTarget(), auditTargetId());
        return principalId != null ? record.detail("principalId", principalId.toString())
                : record.detail("holder", "company");
    }

    @Override
    public String toString() {
        return principalId != null ? "principal " + principalId + " of company " + companyId
                : "the own key of company " + companyId;
    }
}
