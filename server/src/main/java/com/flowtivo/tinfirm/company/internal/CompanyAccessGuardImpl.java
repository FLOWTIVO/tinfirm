package com.flowtivo.tinfirm.company.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.flowtivo.tinfirm.company.MemberStatuses;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Company-scoped authorisation (CONVENTIONS §6). Must run inside the caller's transaction, so the company context
 * it establishes applies to the rest of that transaction.
 * <ul>
 * <li>A member acts by their role only, an instance admin who is a member included.</li>
 * <li>An instance admin who is not a member gets ADMIN's view under break-glass (the package 6 contract, §5.10, open
 * point 12): the permissions ADMIN holds, and {@code MANAGE_OWNERS} for the audited right to appoint an OWNER
 * (the member service lets it promote and nothing more); any other permission is 403. Which conversations it sees
 * follows from its role, none: a personal conversation is its person's and OWNER's only.</li>
 * <li>A suspended member reads only what {@link #requireAllowingSuspended} guards: elsewhere a read is
 * {@code 403 PRINCIPAL_SUSPENDED} and a write {@code 409 PRINCIPAL_SUSPENDED}, told apart by the request's method.</li>
 * <li>A bearer call gets no break-glass, and its suspended member is a non-member: 404.</li>
 * <li>A Viewer (ADR-0077) holds no {@code WORK}, and a session's write under a reading permission is
 * {@code 403 VIEWER_READ_ONLY} too, but their inbox's dismissals.</li>
 * </ul>
 */
@Component
class CompanyAccessGuardImpl implements CompanyAccessGuard {

    private final CompanyQueries queries;

    private final CompanyRepository companies;

    private final TenantScope tenantScope;

    private final AdminAccessAudit adminAccessAudit;

    private final UserDirectory users;

    private final ObjectProvider<MemberStatuses> statuses;

    CompanyAccessGuardImpl(CompanyQueries queries, CompanyRepository companies, TenantScope tenantScope,
            AdminAccessAudit adminAccessAudit, UserDirectory users, ObjectProvider<MemberStatuses> statuses) {
        this.queries = queries;
        this.companies = companies;
        this.tenantScope = tenantScope;
        this.adminAccessAudit = adminAccessAudit;
        this.users = users;
        this.statuses = statuses;
    }

    private enum Mode {
        DEFAULT, ALLOW_SUSPENDED, ACTIVE_MEMBER
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CompanyAccess require(UUID companyId, CompanyPermission permission) {
        AuthenticatedUser user = CurrentActor.requireUser();
        return decide(companyId, permission, user.id(), user.instanceAdmin(), true, bearerOr(Mode.DEFAULT));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CompanyAccess requireAllowingSuspended(UUID companyId, CompanyPermission permission) {
        AuthenticatedUser user = CurrentActor.requireUser();
        return decide(companyId, permission, user.id(), user.instanceAdmin(), true, bearerOr(Mode.ALLOW_SUSPENDED));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CompanyAccess requireActiveMember(UUID companyId, CompanyPermission permission) {
        AuthenticatedUser user = CurrentActor.requireUser();
        return decide(companyId, permission, user.id(), user.instanceAdmin(), true, Mode.ACTIVE_MEMBER);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CompanyAccess recheck(CompanyAccess granted, CompanyPermission permission) {
        UUID userId = CurrentActor.requireUser().id();
        // The user as they are now: the request's copy is as old as the request.
        UserSummary user = users.findById(userId)
            .filter(UserSummary::active)
            .orElseThrow(CompanyAccessGuardImpl::notFound);
        return decide(granted.companyId(), permission, userId, user.instanceAdmin(), !granted.viaInstanceAdmin(),
                bearerOr(Mode.DEFAULT));
    }

    /** A bearer call is answered for an active member only (G6, G9). */
    private static Mode bearerOr(Mode mode) {
        return BearerGrantHolder.current().isPresent() ? Mode.ACTIVE_MEMBER : mode;
    }

    private CompanyAccess decide(UUID companyId, CompanyPermission permission, UUID userId, boolean instanceAdmin,
            boolean auditBreakGlass, Mode mode) {
        Optional<CompanyRole> role = queries.roleOf(companyId, userId);

        if (role.isPresent()) {
            // A member gets their role's answer, an instance admin too: the break-glass is for one who is not a member
            // (CONVENTIONS §6). The member's principal is read in the company's tenant context.
            tenantScope.enterCompany(companyId);
            MemberStatuses status = statuses.getIfAvailable();
            if (mode != Mode.ALLOW_SUSPENDED && status != null && status.suspended(companyId, userId)) {
                if (mode == Mode.ACTIVE_MEMBER) {
                    throw notFound();
                }
                throw suspended();
            }
            if (!permission.isGrantedTo(role.get())) {
                if (role.get() == CompanyRole.VIEWER && (permission == CompanyPermission.WORK || writes())) {
                    throw viewerReadOnly();
                }
                throw ApiException.forbidden("Your role in this company does not allow this operation");
            }
            if (role.get() == CompanyRole.VIEWER && mode != Mode.ACTIVE_MEMBER && writes()) {
                throw viewerReadOnly();
            }
            return new CompanyAccess(companyId, role.get(), false);
        }
        if (mode != Mode.ACTIVE_MEMBER && instanceAdmin && companies.existsById(companyId)) {
            if (!CompanyAccess.breakGlassAllows(permission)) {
                throw ApiException.forbidden("An instance admin who is not a member has an admin's view of the "
                        + "company, and may appoint an owner; this needs the company's own owner");
            }
            tenantScope.enterCompany(companyId);
            if (auditBreakGlass) {
                adminAccessAudit.record(companyId, userId, permission);
            }
            return new CompanyAccess(companyId, null, true);
        }
        // Non-members must not learn whether the company exists.
        throw notFound();
    }

    /**
     * Whether the session's request writes (ADR-0077, decision 5): a Viewer reads what the company shows its members,
     * and writes nothing under a reading permission but their own inbox's dismissals and the reading of a chat link,
     * which changes nothing. A bearer call, whose every tool is a POST, is answered by the tools' own checks, and work
     * outside a request by its services'.
     */
    private static boolean writes() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        String method = attributes.getRequest().getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        String path = attributes.getRequest().getRequestURI();
        return !path.contains("/inbox/dismissals/") && !path.endsWith("/chat-links/read");
    }

    private static ApiException viewerReadOnly() {
        return ApiException.forbidden(ProblemCode.VIEWER_READ_ONLY,
                "A Viewer reads what the company shows its members, and starts, changes or decides nothing");
    }

    private static ApiException notFound() {
        return ApiException.notFound("Company not found");
    }

    /** A suspended member: a read is 403, a write 409, as the request's method says. */
    private static ApiException suspended() {
        String detail = "You are suspended in this company: an admin resumes you";
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String method = attributes.getRequest().getMethod();
            if ("GET".equals(method) || "HEAD".equals(method)) {
                return ApiException.forbidden(ProblemCode.PRINCIPAL_SUSPENDED, detail);
            }
        }
        return ApiException.conflict(ProblemCode.PRINCIPAL_SUSPENDED, detail);
    }
}
