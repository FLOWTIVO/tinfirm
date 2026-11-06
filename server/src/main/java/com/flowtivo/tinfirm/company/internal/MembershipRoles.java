package com.flowtivo.tinfirm.company.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.MemberEvents;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The one writer of a role that something other than the app decides (ADR-0088): an identity provider at a sign-in
 * through it, or SCIM (ADR-0087). Runs in the caller's transaction, in the tenant context of the company.
 * <ul>
 * <li>OWNER is never a provider's or SCIM's: an OWNER's role is never applied, and an OWNER is never refused.</li>
 * <li>SCIM wins over sign-in: a membership whose source is {@link RoleSource#SCIM} keeps its role at a sign-in.</li>
 * <li>The seat rule stays (ADR-0077 decision 5): a role that holds a seat, for a person without one while none is
 * free, gives VIEWER, and the company's Owners and Admins get the {@code SEAT_FALLBACK} item, one per person while it
 * lasts, and is cleared once the role is given in full.</li>
 * </ul>
 * Public for the SCIM directory under {@code ee/}, which lives in a package of this module.
 */
@Component
public class MembershipRoles {

    /** What {@link #apply} did. */
    public enum Outcome {

        /** The membership has the role (given, or kept for want of a seat as VIEWER) and the source. */
        APPLIED,

        /** An OWNER's, or SCIM's at a sign-in: left as it is, and never refused. */
        KEPT,

        /** The person is no member of the company: nothing is granted (ADR-0075 decision 4). */
        NOT_A_MEMBER,

        /** No role was mapped, and nothing keeps the membership from a refusal ({@code ROLE_NOT_MAPPED}). */
        NOT_MAPPED
    }

    private final MembershipRepository memberships;

    private final CompanyQueries queries;

    private final Licensing licensing;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final JdbcClient jdbc;

    MembershipRoles(MembershipRepository memberships, CompanyQueries queries, Licensing licensing, AuditLog auditLog,
            ApplicationEventPublisher events, TimeSource time, JdbcClient jdbc) {
        this.memberships = memberships;
        this.queries = queries;
        this.licensing = licensing;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
        this.jdbc = jdbc;
    }

    /**
     * Applies the role a provider or SCIM decides to an existing membership, audited as {@code member.role.changed}
     * when the role changes.
     *
     * @param role   the mapped role, never OWNER; null when nothing maps to a role
     * @param source who decides: {@link RoleSource#provider} or {@link RoleSource#SCIM}, never {@link RoleSource#APP}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome apply(UUID companyId, UUID userId, @Nullable CompanyRole role, RoleSource source) {
        if (role == CompanyRole.OWNER) {
            throw new IllegalArgumentException("A provider or SCIM never makes owners");
        }
        if (!source.managed()) {
            throw new IllegalArgumentException("The app's role is changed through the members' API");
        }
        Optional<MembershipEntity> found = memberships.findByCompanyIdAndUserId(companyId, userId);
        if (found.isEmpty()) {
            return role == null ? Outcome.NOT_MAPPED : Outcome.NOT_A_MEMBER;
        }
        MembershipEntity membership = found.get();
        CompanyRole previous = membership.getRole();
        if (previous == CompanyRole.OWNER
                || (membership.getRoleSource().equals(RoleSource.SCIM) && !source.equals(RoleSource.SCIM))) {
            return Outcome.KEPT;
        }
        if (role == null) {
            return Outcome.NOT_MAPPED;
        }
        CompanyRole given = licensing.seatAvailableFor(userId, role) ? role : CompanyRole.VIEWER;
        if (given == role) {
            // The role is given in full: an item of an earlier fallback would come back at a later VIEWER otherwise.
            queries.clearSeatFallback(companyId, userId);
        }
        RoleSource previousSource = membership.getRoleSource();
        boolean sourceChanges = !previousSource.equals(source);
        if (given == previous && !sourceChanges) {
            if (given != role) {
                // Still a Viewer for want of a seat: the admins' item stays as it is, once per person.
                seatFallback(companyId, userId, role, source, false);
            }
            return Outcome.APPLIED;
        }
        membership.changeRole(given, source, time.now());
        memberships.flush();
        if (given != role) {
            seatFallback(companyId, userId, role, source, previous != CompanyRole.VIEWER);
        }
        if (given != previous) {
            AuditRecord.Builder changed = AuditRecord
                .company(companyId, AuditAction.MEMBER_ROLE_CHANGED, CurrentActor.actor())
                .target("user", userId)
                .detail("userId", userId.toString())
                .detail("previousRole", previous.name())
                .detail("role", given.name())
                .detail("roleSource", source.provider() != null ? "IDP" : source.value());
            if (source.provider() != null) {
                changed.detail("provider", source.provider());
            }
            if (given != role) {
                changed.detail("seatFallback", true).detail("providerRole", role.name());
            }
            auditLog.record(changed.build());
            events.publishEvent(new MemberEvents.MemberRoleChanged(companyId, userId, previous, given));
        }
        else {
            // The same role, now the provider's or SCIM's: the app may no longer change it, which the audit says.
            AuditRecord.Builder taken = AuditRecord
                .company(companyId, AuditAction.MEMBER_ROLE_SOURCE_CHANGED, CurrentActor.actor())
                .target("user", userId)
                .detail("userId", userId.toString())
                .detail("role", given.name())
                .detail("previousRoleSource", kind(previousSource))
                .detail("roleSource", kind(source));
            if (previousSource.provider() != null) {
                taken.detail("previousProvider", previousSource.provider());
            }
            if (source.provider() != null) {
                taken.detail("provider", source.provider());
            }
            auditLog.record(taken.build());
        }
        return Outcome.APPLIED;
    }

    /**
     * Every membership of the company whose role source is {@code source} becomes the app's, keeping its role, audited
     * once as {@code member.role_source.changed} with their count when there is any (ADR-0088 decision 7). Runs when a
     * provider stops deciding, or SCIM does.
     *
     * @return how many were released
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int release(UUID companyId, RoleSource source) {
        if (!source.managed()) {
            return 0;
        }
        int released = jdbc.sql("""
                UPDATE company_membership SET role_source = 'APP', updated_at = ?, version = version + 1
                 WHERE company_id = ? AND role_source = ?""")
            .params(OffsetDateTime.ofInstant(time.now(), ZoneOffset.UTC), companyId, source.value())
            .update();
        if (released > 0) {
            AuditRecord.Builder changed = AuditRecord
                .company(companyId, AuditAction.MEMBER_ROLE_SOURCE_CHANGED, CurrentActor.actor())
                .target("company", companyId)
                .detail("previousRoleSource", source.provider() != null ? "IDP" : source.value())
                .detail("roleSource", RoleSource.APP.value())
                .detail("count", released);
            if (source.provider() != null) {
                changed.detail("provider", source.provider());
            }
            auditLog.record(changed.build());
        }
        return released;
    }

    /** The role sources of the company's managed memberships, for the release at the start. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<RoleSource> managedSources(UUID companyId) {
        return jdbc.sql("""
                SELECT DISTINCT role_source FROM company_membership
                 WHERE company_id = ? AND role_source <> 'APP' ORDER BY role_source""")
            .param(companyId)
            .query(String.class)
            .list()
            .stream()
            .map(RoleSource::parse)
            .toList();
    }

    /** The audit's name of a role source: {@code APP}, {@code IDP} or {@code SCIM}; the provider goes beside it. */
    private static String kind(RoleSource source) {
        return source.provider() != null ? "IDP" : source.value();
    }

    /**
     * Records the admins' {@code SEAT_FALLBACK} item: one per person; it starts again when they had a seat role in
     * between, so that a fallback after a seat is told again.
     */
    private void seatFallback(UUID companyId, UUID userId, CompanyRole role, RoleSource source, boolean again) {
        String provider = source.manager();
        queries.seatFallbackAgain(companyId, userId, role, provider != null ? provider : source.value(), time.now(),
                again);
    }
}
