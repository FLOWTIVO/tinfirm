package com.flowtivo.tinfirm.trigger.internal;

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

/**
 * Who calls, and what they may do with a trigger (the package 4 contract, §5.1 as decided in §11, T15):
 * <table>
 * <caption>Rights</caption>
 * <tr><th>Caller</th><th>Sees</th><th>Edits, accepts</th><th>Runs now, pauses</th><th>Resumes, deletes</th>
 * <th>Reassigns</th></tr>
 * <tr><td>Its author</td><td>yes</td><td>yes</td><td>yes</td><td>yes</td><td>no</td></tr>
 * <tr><td>A member of its team</td><td>yes</td><td>no ({@code TRIGGER_NOT_EDITABLE})</td><td>yes</td><td>no</td>
 * <td>no</td></tr>
 * <tr><td>OWNER, ADMIN (an instance admin acting in the company)</td><td>yes</td><td>yes, on the author's behalf
 * (§11)</td><td>yes</td><td>yes</td><td>yes</td></tr>
 * <tr><td>Anyone else</td><td colspan="5">no: {@code 404}</td></tr>
 * </table>
 */
@Component
class TriggerCallers {

    private final Principals principals;

    private final GroupMembership groups;

    TriggerCallers(Principals principals, GroupMembership groups) {
        this.principals = principals;
        this.groups = groups;
    }

    /**
     * A caller in a company.
     *
     * @param principalId their member's principal; null for an instance admin who is not a member
     * @param admin       OWNER or ADMIN, or an instance admin acting in the company
     * @param groupIds    the groups they are in
     */
    record Caller(UUID companyId, UUID userId, @Nullable UUID principalId, boolean admin, Set<UUID> groupIds) {

        Caller {
            groupIds = Set.copyOf(groupIds);
        }

        UUID requirePrincipal() {
            if (principalId == null) {
                throw ApiException.notFound("You are not a member of this company");
            }
            return principalId;
        }

        boolean author(TriggerInfo trigger) {
            return trigger.authorPrincipalId().equals(principalId);
        }

        boolean inTeam(TriggerInfo trigger) {
            return trigger.teamGroupId() != null && groupIds.contains(trigger.teamGroupId());
        }

        Rights rights(TriggerInfo trigger) {
            boolean author = author(trigger);
            boolean team = inTeam(trigger);
            boolean sees = author || team || admin;
            return new Rights(sees, author || admin, sees, sees, author || admin, author || admin, admin);
        }
    }

    /** What a caller may do with a trigger (§5.1, §11). */
    record Rights(boolean sees, boolean edits, boolean runs, boolean pauses, boolean resumes, boolean deletes,
            boolean reassigns) {
    }

    Caller of(CompanyAccess access, UUID userId) {
        UUID principalId = principals.ofMember(access.companyId(), userId).map(PrincipalInfo::id).orElse(null);
        boolean admin = access.viaInstanceAdmin() || access.role() == CompanyRole.OWNER
                || access.role() == CompanyRole.ADMIN;
        return new Caller(access.companyId(), userId, principalId, admin,
                groups.groupsOf(access.companyId(), userId));
    }
}
