package com.flowtivo.tinfirm.mcp.internal;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.OutlookApi;
import com.flowtivo.tinfirm.shared.api.model.OutlookManifest;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;

/** {@code GET /api/companies/{companyId}/outlook/manifest} (ADR-0074), session-only like the rest of the API. */
@RestController
class OutlookController implements OutlookApi {

    private final ObjectProvider<OutlookAddIn> addIn;

    private final CompanyAccessGuard guard;

    private final TransactionTemplate transactions;

    OutlookController(ObjectProvider<OutlookAddIn> addIn, CompanyAccessGuard guard,
            PlatformTransactionManager transactionManager) {
        this.addIn = addIn;
        this.guard = guard;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    @Override
    public ResponseEntity<OutlookManifest> getOutlookManifest(UUID companyId) {
        OutlookAddIn found = addIn.getIfAvailable();
        if (found == null) {
            // Community without ee/ (ADR-0077, decision 4): a member learns it after the access check.
            transactions.executeWithoutResult(status -> guard.require(companyId, CompanyPermission.VIEW_COMPANY));
            throw Licensing.editionRequired(Feature.OUTLOOK_ADDIN);
        }
        return ResponseEntity.ok(found.manifest(companyId));
    }
}
