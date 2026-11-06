package com.flowtivo.tinfirm.agent.internal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.model.PlatformModel;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.MemorySettings;
import com.flowtivo.tinfirm.shared.api.model.MemorySettingsRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * The company's memory settings (ADR-0063, decision 8; ADR-0065): the model that tidies agents' memories every 30 days,
 * on the company's own key, which OWNER and ADMIN choose among the models the key allows ({@code MANAGE_MODEL_ACCESS},
 * as they set its budget); none, and a compaction only removes duplicates. Audited as {@code memory_settings.changed}.
 */
@Service
class MemorySettingsService {

    private final MemorySettingsStore store;

    private final PlatformModel platform;

    private final CompanyAccessGuard companyAccess;

    private final AuditLog auditLog;

    private final TimeSource time;

    MemorySettingsService(MemorySettingsStore store, PlatformModel platform, CompanyAccessGuard companyAccess,
            AuditLog auditLog, TimeSource time) {
        this.store = store;
        this.platform = platform;
        this.companyAccess = companyAccess;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public MemorySettings get(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
        return view(companyId);
    }

    @Transactional
    public MemorySettings put(UUID companyId, MemorySettingsRequest request) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MODEL_ACCESS);
        String model = request.getCompactionModel() != null && !request.getCompactionModel().isBlank()
                ? request.getCompactionModel().strip() : null;
        if (model != null && !platform.allows(companyId, model)) {
            throw ApiException.badRequest(ProblemCode.MODEL_NOT_ALLOWED, "The company's own key does not allow "
                    + model + ": choose one of its models, or widen them under Model access");
        }
        if (!Objects.equals(model, store.compactionModel(companyId).orElse(null))) {
            store.setCompactionModel(companyId, model, time.now(), CurrentActor.requireUser().id());
            auditLog.record(AuditRecord.company(companyId, AuditAction.MEMORY_SETTINGS_CHANGED, CurrentActor.actor())
                .target("memory_settings", companyId)
                .detail("changed", List.of("compactionModel"))
                .detail("compactionModel", model)
                .build());
        }
        return view(companyId);
    }

    /**
     * The model a memory's compaction asks, in the caller's transaction; empty without one, and while the company's
     * own key does not allow it.
     */
    Optional<String> compactionModel(UUID companyId) {
        return store.compactionModel(companyId).filter(model -> platform.allows(companyId, model));
    }

    private MemorySettings view(UUID companyId) {
        @Nullable String model = store.compactionModel(companyId).orElse(null);
        return new MemorySettings().compactionModel(model)
            .compactionModelAllowed(model != null && platform.allows(companyId, model));
    }
}
