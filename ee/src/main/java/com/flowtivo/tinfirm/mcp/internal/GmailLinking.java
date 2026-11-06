package com.flowtivo.tinfirm.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.mcp.internal.GoogleIdTokens.GoogleAccount;
import com.flowtivo.tinfirm.shared.api.model.GmailLink;
import com.flowtivo.tinfirm.shared.api.model.GmailLinkRequest;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Linking a Google account to a person for the Gmail add-on (ADR-0083, decision 3): the sign-in link an unlinked
 * account is offered ({@code /gmail/link/{companyId}#{token}}, 10 minutes, one use, the token kept as its SHA-256), its
 * reading and confirmation by a signed-in active member, the linked account's person on each of Google's requests, and
 * the add-on's Sign out. A Google account is linked to one person per company at most, never by its email, which is
 * shown and never matched. The add-on is Pro's and Enterprise's (ADR-0077, decision 4, {@code GMAIL_ADDON}): a paid
 * feature's code, under {@code ee/}; a person's links are listed and ended in every edition ({@link GmailLinks}).
 */
@Component
class GmailLinking {

    /** How long a sign-in link works. */
    static final Duration LINK_LIFETIME = Duration.ofMinutes(10);

    /** The name the Owner's notice gives the client. */
    static final String CLIENT = "the Gmail add-on";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A linked account's request: its link, and its person as they are now. */
    record Active(GmailStore.Link link, AuthenticatedUser user) {
    }

    private final GmailLinks links;

    private final GmailStore store;

    private final MembershipDirectory memberships;

    private final UserDirectory users;

    private final CompanyNames companies;

    private final OwnerNotices notices;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final TinfirmProperties tinfirm;

    private final SystemTransactions system;

    private final Licensing licensing;

    GmailLinking(GmailLinks links, GmailStore store, MembershipDirectory memberships, UserDirectory users,
            CompanyNames companies, OwnerNotices notices, AuditLog auditLog, TimeSource time, TinfirmProperties tinfirm,
            SystemTransactions system, Licensing licensing) {
        this.links = links;
        this.store = store;
        this.memberships = memberships;
        this.users = users;
        this.companies = companies;
        this.notices = notices;
        this.auditLog = auditLog;
        this.time = time;
        this.tinfirm = tinfirm;
        this.system = system;
        this.licensing = licensing;
    }

    // ---- the add-on's side ---------------------------------------------------------------------------------------------

    /**
     * The account's link and its person, the link's use recorded; empty when it has none, or when it ended: unused for
     * 30 days, or its person deactivated or no longer a member, which deletes it.
     */
    Optional<Active> active(UUID companyId, GoogleAccount account) {
        Optional<GmailStore.Link> found = system.inCompany(companyId, () -> store.linkOf(companyId, account.subject()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        GmailStore.Link link = found.get();
        Instant now = time.now();
        Optional<UserSummary> user = users.findById(link.userId()).filter(UserSummary::active);
        // In the company's context, where its memberships are visible.
        boolean member = user.isPresent() && system.inCompany(companyId, () -> memberships.membershipsOf(link.userId())
            .stream()
            .anyMatch(membership -> membership.companyId().equals(companyId)));
        if (!member || link.lastUsedAt().plus(GmailLinks.UNUSED).isBefore(now)) {
            system.runInCompany(companyId, () -> links.end(link, Actor.SYSTEM, member ? "UNUSED" : "NOT_A_MEMBER"));
            return Optional.empty();
        }
        system.runInCompany(companyId, () -> store.touch(link.id(), now));
        UserSummary person = user.get();
        return Optional.of(new Active(link, new AuthenticatedUser(person.id(), person.email(), person.displayName(),
                person.instanceAdmin())));
    }

    /** A sign-in link for the account, replacing its unused one: the URL its card opens. */
    String offer(UUID companyId, GoogleAccount account) {
        String token = token();
        Instant now = time.now();
        system.runInCompany(companyId, () -> store.offer(new GmailStore.LinkRequest(hash(token), companyId,
                account.subject(), account.email(), now, now.plus(LINK_LIFETIME), null)));
        return tinfirm.publicBaseUrl() + "/gmail/link/" + companyId + "#" + token;
    }

    /** The add-on's Sign out: the link ends, as its person. */
    void signOut(UUID companyId, Active active) {
        system.runInCompany(companyId, () -> links.end(active.link(), Actor.user(active.user().id()), "SIGN_OUT"));
    }

    /** Remembers the agent the person chose, offered first next time. */
    void lastAgent(UUID companyId, UUID linkId, UUID agentId) {
        system.runInCompany(companyId, () -> store.lastAgent(linkId, agentId));
    }

    // ---- Tinfirm's side ------------------------------------------------------------------------------------------------

    /**
     * {@code POST /gmail/links/read}: what the link links, for an active member of the company, then
     * {@code 403 EDITION_REQUIRED} unless the licence holds the add-on. Consumes nothing.
     */
    GmailLinkRequest read(UUID companyId, String token) {
        return links.inTransaction(() -> {
            links.member(companyId);
            licensing.require(Feature.GMAIL_ADDON);
            GmailStore.LinkRequest request = store.request(hash(token), false).orElseThrow(GmailLinks::notFound);
            requireUsable(request);
            return new GmailLinkRequest(request.createdAt().atOffset(ZoneOffset.UTC),
                    request.expiresAt().atOffset(ZoneOffset.UTC), notices.of(companyId, CLIENT))
                .googleEmail(request.googleEmail());
        });
    }

    /**
     * {@code POST /gmail/links/confirm}: as {@link #read}, then in one transaction the request is consumed whatever the
     * outcome, and the account linked to the caller unless it is another member's. A refusal answers after the
     * consumption committed.
     */
    GmailLink confirm(UUID companyId, String token) {
        Object outcome = links.inTransaction(() -> {
            links.member(companyId);
            licensing.require(Feature.GMAIL_ADDON);
            AuthenticatedUser caller = CurrentActor.requireUser();
            GmailStore.LinkRequest request = store.request(hash(token), true).orElseThrow(GmailLinks::notFound);
            requireUsable(request);
            Instant now = time.now();
            store.consume(request.tokenHash(), caller.id(), now);
            Optional<GmailStore.Link> linked = store.upsertLink(Ids.newId(), companyId, caller.id(),
                    request.googleSubject(), request.googleEmail(), now);
            if (linked.isEmpty()) {
                return ApiException.conflict(ProblemCode.IDENTITY_IN_USE, "This Google account is linked to another "
                        + "member of this company");
            }
            auditLog.record(AuditRecord.company(companyId, AuditAction.GMAIL_LINKED, CurrentActor.actor())
                .target("gmail_link", linked.get().id())
                .detail("googleSubject", request.googleSubject())
                .build());
            return GmailLinks.view(linked.get(), companies.nameOf(companyId).orElse(""));
        });
        if (outcome instanceof ApiException refusal) {
            throw refusal;
        }
        return (GmailLink) outcome;
    }

    private void requireUsable(GmailStore.LinkRequest request) {
        if (request.consumedAt() != null) {
            throw ApiException.conflict(ProblemCode.GMAIL_LINK_USED, "This link was used already");
        }
        if (!request.expiresAt().isAfter(time.now())) {
            throw new ApiException(HttpStatus.GONE, ProblemCode.GMAIL_LINK_EXPIRED, "This link expired: choose Sign "
                    + "in to Tinfirm in Gmail again for a new one");
        }
    }

    /** 32 random bytes, base64url without padding. */
    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.strip().getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
