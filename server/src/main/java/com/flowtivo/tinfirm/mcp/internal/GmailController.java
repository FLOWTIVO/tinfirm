package com.flowtivo.tinfirm.mcp.internal;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.GmailApi;
import com.flowtivo.tinfirm.shared.api.model.GmailDeployment;
import com.flowtivo.tinfirm.shared.api.model.GmailLink;
import com.flowtivo.tinfirm.shared.api.model.GmailLinkList;
import com.flowtivo.tinfirm.shared.api.model.GmailLinkRequest;
import com.flowtivo.tinfirm.shared.api.model.GmailLinkTokenRequest;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;

/**
 * The Gmail add-on's operations in the REST API (ADR-0083), session-only like the rest of it: the deployment file and
 * the sign-in link's reading and confirmation, the add-on's ({@link GmailAddOn}, under {@code ee/}); and the person's
 * links, listed and ended in every edition ({@link GmailLinks}). Google's own requests are
 * {@link GmailAddOnController}'s.
 */
@RestController
class GmailController implements GmailApi {

    private final ObjectProvider<GmailAddOn> addOn;

    private final GmailLinks links;

    private final CompanyAccessGuard guard;

    private final TransactionTemplate transactions;

    GmailController(ObjectProvider<GmailAddOn> addOn, GmailLinks links, CompanyAccessGuard guard,
            PlatformTransactionManager transactionManager) {
        this.addOn = addOn;
        this.links = links;
        this.guard = guard;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    @Override
    public ResponseEntity<GmailDeployment> getGmailDeployment(UUID companyId) {
        GmailAddOn found = addOn.getIfAvailable();
        if (found == null) {
            // Community without ee/ (ADR-0077, decision 4): a member learns it after the access check.
            transactions.executeWithoutResult(status -> guard.require(companyId, CompanyPermission.VIEW_COMPANY));
            throw Licensing.editionRequired(Feature.GMAIL_ADDON);
        }
        return ResponseEntity.ok(found.deployment(companyId));
    }

    @Override
    public ResponseEntity<GmailLinkRequest> readGmailLink(UUID companyId, GmailLinkTokenRequest request) {
        return ResponseEntity.ok(linking(companyId).readLink(companyId, request.getToken()));
    }

    @Override
    public ResponseEntity<GmailLink> confirmGmailLink(UUID companyId, GmailLinkTokenRequest request) {
        return ResponseEntity.ok(linking(companyId).confirmLink(companyId, request.getToken()));
    }

    @Override
    public ResponseEntity<Void> deleteGmailLink(UUID companyId, UUID linkId) {
        links.disconnect(companyId, linkId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<GmailLinkList> listMyGmailLinks() {
        return ResponseEntity.ok(links.listOwn());
    }

    /** The add-on, or Community without ee/: the link's reader, an active member, learns it after the access check. */
    private GmailAddOn linking(UUID companyId) {
        GmailAddOn found = addOn.getIfAvailable();
        if (found == null) {
            links.inTransaction(() -> {
                links.member(companyId);
                return companyId;
            });
            throw Licensing.editionRequired(Feature.GMAIL_ADDON);
        }
        return found;
    }
}
