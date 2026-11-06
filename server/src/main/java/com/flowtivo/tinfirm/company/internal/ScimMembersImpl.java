package com.flowtivo.tinfirm.company.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.MemberEvents;
import com.flowtivo.tinfirm.company.MemberPrincipals;
import com.flowtivo.tinfirm.company.MemberStatuses;
import com.flowtivo.tinfirm.company.scim.ScimMembers;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/** The memberships of a company's SCIM ({@link ScimMembers}). */
@Component
class ScimMembersImpl implements ScimMembers {

    private static final String TARGET = "user";

    private final MembershipRepository memberships;

    private final CompanyQueries queries;

    private final MemberService members;

    private final MemberPrincipals principals;

    private final ObjectProvider<MemberStatuses> statuses;

    private final Licensing licensing;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final JdbcClient jdbc;

    private final MembershipRoles roles;

    private final ApplicationEventPublisher events;

    ScimMembersImpl(MembershipRepository memberships, CompanyQueries queries, MemberService members,
            MemberPrincipals principals, ObjectProvider<MemberStatuses> statuses, Licensing licensing,
            AuditLog auditLog, TimeSource time, JdbcClient jdbc, MembershipRoles roles,
            ApplicationEventPublisher events) {
        this.roles = roles;
        this.events = events;
        this.memberships = memberships;
        this.queries = queries;
        this.members = members;
        this.principals = principals;
        this.statuses = statuses;
        this.licensing = licensing;
        this.auditLog = auditLog;
        this.time = time;
        this.jdbc = jdbc;
    }



    /** Serialises the company's membership changes until the transaction ends (the last-owner checks). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID companyId) {
        queries.lockCompany(companyId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Member> find(UUID companyId, UUID userId) {
        return memberships.findByCompanyIdAndUserId(companyId, userId).map(this::view);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Member> findByExternalId(UUID companyId, String externalId) {
        return memberships.findByCompanyIdAndScimExternalId(companyId, externalId).map(this::view);
    }

    /** The member whose user has this email, case-insensitive. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Member> findByEmail(UUID companyId, String email) {
        return jdbc.sql("""
                SELECT m.user_id FROM company_membership m JOIN app_user u ON u.id = m.user_id
                 WHERE m.company_id = ? AND u.email = lower(btrim(?))""")
            .params(companyId, email)
            .query(UUID.class)
            .optional()
            .flatMap(userId -> find(companyId, userId));
    }

    /** The company's members. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public int count(UUID companyId) {
        return jdbc.sql("SELECT count(*) FROM company_membership WHERE company_id = ?")
            .param(companyId)
            .query(Integer.class)
            .single();
    }

    /**
     * A page of the company's members by the user's id, from a 0-based offset: SCIM's lists are index-based (RFC 7644
     * §3.4.2.4), the one read of the server with an offset, which the caller bounds (CONVENTIONS §7), over the index
     * {@code (company_id, user_id)} inside one company.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<Member> page(UUID companyId, int offset, int limit) {
        List<UUID> userIds = jdbc.sql("""
                SELECT user_id FROM company_membership WHERE company_id = ? ORDER BY user_id OFFSET ? LIMIT ?""")
            .params(companyId, offset, limit)
            .query(UUID.class)
            .list();
        Set<UUID> suspended = suspendedUsers(companyId);
        return userIds.stream()
            .map(userId -> memberships.findByCompanyIdAndUserId(companyId, userId))
            .flatMap(Optional::stream)
            .map(membership -> view(membership, suspended))
            .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Given add(UUID companyId, UUID userId, CompanyRole role, String externalId, boolean managesRole) {
        requireNotOwner(role);
        Instant now = time.now();
        CompanyRole given = licensing.seatAvailableFor(userId, role) ? role : CompanyRole.VIEWER;
        RoleSource source = managesRole ? RoleSource.SCIM : RoleSource.APP;
        MembershipEntity membership = new MembershipEntity(companyId, userId, given, source, now);
        membership.scimExternalId(externalId, now);
        memberships.saveAndFlush(membership);
        AuditRecord.Builder added = AuditRecord.company(companyId, AuditAction.MEMBER_ADDED, CurrentActor.actor())
            .target(TARGET, userId)
            .detail("userId", userId.toString())
            .detail("role", given.name())
            .detail("provider", SOURCE)
            .detail("roleSource", source.value());
        if (given != role) {
            added.detail("seatFallback", true).detail("providerRole", role.name());
            queries.seatFallbackAgain(companyId, userId, role, SOURCE, now, true);
        }
        auditLog.record(added.build());
        principals.joined(companyId, userId);
        return new Given(given, given != role);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Given changeRole(UUID companyId, UUID userId, CompanyRole role) {
        requireNotOwner(role);
        // The one writer of a role an IdP or SCIM decides (ADR-0088): never an OWNER's, the seat fallback, the source.
        roles.apply(companyId, userId, role, RoleSource.SCIM);
        CompanyRole now = require(companyId, userId).getRole();
        return new Given(now, now != CompanyRole.OWNER && now != role);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int releaseRoles(UUID companyId) {
        return roles.release(companyId, RoleSource.SCIM);
    }

    /**
     * Sets the member's {@code externalId}; the caller never changes an OWNER's (decision 7).
     *
     * @throws DataIntegrityViolationException when another member holds it
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void setExternalId(UUID companyId, UUID userId, String externalId) {
        MembershipEntity membership = require(companyId, userId);
        membership.scimExternalId(externalId, time.now());
        memberships.flush();
    }

    /**
     * Sets or clears SCIM's own suspension of the member on the membership (decision 7); the principal's suspension is
     * the principal module's, which the caller tells after.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void setScimSuspended(UUID companyId, UUID userId, boolean suspended) {
        MembershipEntity membership = require(companyId, userId);
        membership.scimSuspended(suspended, time.now());
        memberships.flush();
    }

    /**
     * The member leaves the company as a member an admin removes does (decision 7): their principal leaves, their
     * content stays, the departure's checklist goes to the Owners and Admins. The caller checked the last OWNER.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void remove(UUID companyId, UUID userId) {
        members.leave(require(companyId, userId));
    }

    /**
     * Whether the member is an OWNER and the company's last active one: a suspended owner counts for nothing, so that
     * the company keeps an owner who can act.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean lastActiveOwner(UUID companyId, UUID userId) {
        Set<UUID> suspended = suspendedUsers(companyId);
        List<UUID> owners = queries.owners(companyId);
        return owners.contains(userId)
                && owners.stream().noneMatch(owner -> !owner.equals(userId) && !suspended.contains(owner));
    }

    /**
     * SCIM tried to deactivate or delete this OWNER, the company's last active one, and was refused: the OWNER's inbox
     * says so ({@code SCIM_LAST_OWNER}, decision 7).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lastOwnerRefused(UUID companyId, UUID userId) {
        jdbc.sql("UPDATE company_membership SET scim_last_owner_refused_at = ? WHERE company_id = ? AND user_id = ?")
            .params(OffsetDateTime.ofInstant(time.now(), ZoneOffset.UTC), companyId, userId)
            .update();
    }

    /** When SCIM was last refused the deactivation or deletion of this OWNER, if since {@code since}. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Instant> lastOwnerRefusedSince(UUID companyId, UUID userId, Instant since) {
        return jdbc.sql("""
                SELECT scim_last_owner_refused_at FROM company_membership
                 WHERE company_id = ? AND user_id = ? AND role = 'OWNER' AND scim_last_owner_refused_at >= ?""")
            .params(companyId, userId, OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
            .query(OffsetDateTime.class)
            .optional()
            .map(OffsetDateTime::toInstant);
    }

    /**
     * The member, who becomes active again after SCIM's deactivation of the user, takes VIEWER when the role they hold
     * needs a seat and none is free (decision 8): audited {@code member.role.changed} with {@code seatFallback}, and
     * {@code MemberRoleChanged} published as every other role change publishes it, so that the member's triggers pause
     * at once (a Viewer authors none: the security review of 2026-10-27, M7).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Given fallBackToViewer(UUID companyId, UUID userId) {
        MembershipEntity membership = require(companyId, userId);
        CompanyRole previous = membership.getRole();
        if (previous == CompanyRole.OWNER || previous == CompanyRole.VIEWER) {
            return new Given(previous, false);
        }
        Instant now = time.now();
        membership.changeRole(CompanyRole.VIEWER, membership.getRoleSource(), now);
        memberships.flush();
        auditLog.record(AuditRecord.company(companyId, AuditAction.MEMBER_ROLE_CHANGED, CurrentActor.actor())
            .target(TARGET, userId)
            .detail("userId", userId.toString())
            .detail("previousRole", previous.name())
            .detail("role", CompanyRole.VIEWER.name())
            .detail("provider", SOURCE)
            .detail("seatFallback", true)
            .detail("providerRole", previous.name())
            .build());
        queries.seatFallbackAgain(companyId, userId, previous, SOURCE, now, true);
        events.publishEvent(new MemberEvents.MemberRoleChanged(companyId, userId, previous, CompanyRole.VIEWER));
        return new Given(CompanyRole.VIEWER, true);
    }

    /** Whether the user is an OWNER of any company: no sign-in binds to an owner's account (decision 6). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean ownsACompany(UUID userId) {
        return jdbc.sql("SELECT user_owns_a_company(?)").param(userId).query(Boolean.class).single();
    }

    /**
     * The user's memberships of other companies, and how many of them are not suspended: SCIM deactivates the user
     * only when no other company holds them, and renames them only while this company is their only one.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Elsewhere elsewhere(UUID companyId, UUID userId) {
        return jdbc.sql("SELECT memberships, active_memberships FROM user_memberships_elsewhere(?, ?)")
            .params(userId, companyId)
            .query((rs, row) -> new Elsewhere(rs.getInt("memberships"), rs.getInt("active_memberships")))
            .single();
    }


    private MembershipEntity require(UUID companyId, UUID userId) {
        return memberships.findByCompanyIdAndUserId(companyId, userId)
            .orElseThrow(() -> new IllegalStateException("Not a member of the company"));
    }

    private Member view(MembershipEntity membership) {
        return view(membership, suspendedUsers(membership.getCompanyId()));
    }

    private static Member view(MembershipEntity membership, Set<UUID> suspended) {
        return new Member(membership.getCompanyId(), membership.getUserId(), membership.getRole(),
                membership.getScimExternalId(), membership.getScimSuspendedAt() != null,
                suspended.contains(membership.getUserId()), membership.getCreatedAt(), membership.getUpdatedAt());
    }

    private Set<UUID> suspendedUsers(UUID companyId) {
        MemberStatuses status = statuses.getIfAvailable();
        return status != null ? status.suspendedUsers(companyId) : Set.of();
    }

    private static void requireNotOwner(CompanyRole role) {
        if (role == CompanyRole.OWNER) {
            throw new IllegalArgumentException("SCIM never makes owners");
        }
    }
}
