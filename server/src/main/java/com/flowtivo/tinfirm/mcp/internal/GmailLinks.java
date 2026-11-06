package com.flowtivo.tinfirm.mcp.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.identity.UserMembership;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.GmailLink;
import com.flowtivo.tinfirm.shared.api.model.GmailLinkList;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * A person's links of Google accounts for the Gmail add-on (ADR-0083, decision 3), in every edition: their links in
 * every company of theirs, and their end (Disconnect in Tinfirm; the add-on's Sign out, 30 days unused and the person
 * no longer an active member end one through {@link #end}). Linking itself is the add-on's, a paid feature under
 * {@code ee/} ({@link GmailAddOn}); the links that exist are the person's to see and end whatever the licence, as the
 * legal holds that exist are listed and lifted in every edition.
 */
@Component
class GmailLinks {

    /** A link unused this long has ended. */
    static final Duration UNUSED = Duration.ofDays(30);

    private final GmailStore store;

    private final CompanyAccessGuard guard;

    private final MembershipDirectory memberships;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final SystemTransactions system;

    private final TransactionTemplate transactions;

    GmailLinks(GmailStore store, CompanyAccessGuard guard, MembershipDirectory memberships, AuditLog auditLog,
            TimeSource time, SystemTransactions system, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.guard = guard;
        this.memberships = memberships;
        this.auditLog = auditLog;
        this.time = time;
        this.system = system;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** {@code GET /me/gmail/links}: the caller's links in every company of theirs, newest first. */
    GmailLinkList listOwn() {
        AuthenticatedUser caller = CurrentActor.requireUser();
        Instant now = time.now();
        List<GmailLink> links = new ArrayList<>();
        for (UserMembership membership : memberships.membershipsOf(caller.id())) {
            for (GmailStore.Link link : system.inCompany(membership.companyId(), () -> store.linksOf(caller.id()))) {
                if (!link.lastUsedAt().plus(UNUSED).isBefore(now)) {
                    links.add(view(link, membership.companyName()));
                }
            }
        }
        links.sort(Comparator.comparing(GmailLink::getLinkedAt).reversed());
        return new GmailLinkList(links);
    }

    /** {@code DELETE /gmail/links/{linkId}}: one of the caller's own links ends. */
    void disconnect(UUID companyId, UUID linkId) {
        inTransaction(() -> {
            member(companyId);
            AuthenticatedUser caller = CurrentActor.requireUser();
            GmailStore.Link link = store.link(linkId)
                .filter(found -> found.userId().equals(caller.id()))
                .orElseThrow(GmailLinks::notFound);
            end(link, CurrentActor.actor(), "DISCONNECTED");
            return link;
        });
    }

    /** The link ends, audited with why: in the caller's transaction and the company's context. */
    void end(GmailStore.Link link, Actor actor, String reason) {
        if (store.deleteLink(link.id())) {
            auditLog.record(AuditRecord.company(link.companyId(), AuditAction.GMAIL_UNLINKED, actor)
                .target("gmail_link", link.id())
                .detail("googleSubject", link.googleSubject())
                .detail("reason", reason)
                .build());
        }
    }

    static GmailLink view(GmailStore.Link link, String companyName) {
        return new GmailLink(link.id(), link.companyId(), companyName, link.linkedAt().atOffset(ZoneOffset.UTC),
                link.lastUsedAt().atOffset(ZoneOffset.UTC))
            .googleEmail(link.googleEmail());
    }

    /** The caller as an active member of the company, never an instance admin's break-glass: 404 otherwise. */
    void member(UUID companyId) {
        CompanyAccess access = guard.require(companyId, CompanyPermission.VIEW_COMPANY);
        if (access.viaInstanceAdmin()) {
            throw notFound();
        }
    }

    <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    static ApiException notFound() {
        return ApiException.notFound("Link not found");
    }
}
