package com.flowtivo.tinfirm.agent.internal;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentAccess;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Who asks about agents, and {@code Agents.access} (the package 3 contract, §5.1): the caller's principal in the
 * company (none for an instance admin who is not a member), whether they manage every agent (OWNER, ADMIN, an instance
 * admin), and their groups.
 */
@Component
class AgentCallers {

    private final Principals principals;

    private final GroupMembership groups;

    private final CompanyDirectory companies;

    AgentCallers(Principals principals, GroupMembership groups, CompanyDirectory companies) {
        this.principals = principals;
        this.groups = groups;
        this.companies = companies;
    }

    /**
     * A caller.
     *
     * @param principalId their member's principal; null for an instance admin who is not a member
     * @param admin       whether they see and edit every agent (OWNER, ADMIN, an instance admin)
     * @param groupIds    the groups they are a member of
     */
    record Caller(UUID userId, @Nullable UUID principalId, boolean admin, Set<UUID> groupIds) {

        Caller {
            groupIds = Set.copyOf(groupIds);
        }

        /** The caller's principal; 404 for a caller without one (an instance admin who is not a member). */
        UUID requirePrincipal() {
            if (principalId == null) {
                throw ApiException.notFound("You are not a member of this company");
            }
            return principalId;
        }
    }

    /** The caller of the current request, authorised for the company. */
    Caller current(CompanyAccess access) {
        UUID userId = CurrentActor.requireUser().id();
        UUID companyId = access.companyId();
        UUID principalId = principals.ofMember(companyId, userId).map(PrincipalInfo::id).orElse(null);
        return new Caller(userId, principalId, access.covers(CompanyPermission.MANAGE_AGENTS),
                principalId != null ? groups.groupsOf(companyId, userId) : Set.of());
    }

    /** A user of the company, as another module names them; nobody for a user who is not a member. */
    Optional<Caller> member(UUID companyId, UUID userId) {
        Optional<CompanyRole> role = companies.roleOf(companyId, userId);
        if (role.isEmpty()) {
            return Optional.empty();
        }
        UUID principalId = principals.ofMember(companyId, userId).map(PrincipalInfo::id).orElse(null);
        return Optional.of(new Caller(userId, principalId, CompanyPermission.MANAGE_AGENTS.isGrantedTo(role.get()),
                groups.groupsOf(companyId, userId)));
    }

    /**
     * {@code Agents.access(caller, agent)}: its owner sees, uses and edits it; a member of a group it is shared with,
     * and a member it is shared with on their own, see and use it; OWNER and ADMIN see and edit every agent, and use
     * one only as its owner or through a share.
     */
    static AgentAccess access(Caller caller, @Nullable UUID ownerPrincipalId, AgentStore.Shares shares) {
        boolean owner = caller.principalId() != null && caller.principalId().equals(ownerPrincipalId);
        boolean shared = caller.principalId() != null && (shares.principalIds().contains(caller.principalId())
                || !Collections.disjoint(caller.groupIds(), shares.groupIds()));
        boolean uses = owner || shared;
        return new AgentAccess(uses || caller.admin(), uses, owner || caller.admin());
    }
}
