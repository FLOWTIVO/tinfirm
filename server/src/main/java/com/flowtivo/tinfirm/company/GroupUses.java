package com.flowtivo.tinfirm.company;

import java.util.List;
import java.util.UUID;

import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.api.model.AccountGrant;
import com.flowtivo.tinfirm.shared.api.model.GroupUsageItem;

/**
 * Port: what a group's members may use through it ({@code getGroupUsage}), each part answered by the module that keeps
 * it: the live agents shared with the group (agent), the grants that lend it a service identity's account (account) and
 * the service identities whose users it is (principal); the company module does not depend on them. Every
 * implementation runs in the caller's transaction and tenant context, answers with a fixed number of queries whatever
 * the group holds, and answers only what the caller's access lets them see in that module's own list (every agent with
 * {@code MANAGE_AGENTS}, the grants with {@code MANAGE_GRANTS}, who may use a service identity with
 * {@code MANAGE_SERVICE_IDENTITIES}), else nothing.
 */
public interface GroupUses {

    /**
     * One module's part of a group's usage; the parts it does not keep are empty.
     *
     * @param agents            the live agents shared with the group, by name
     * @param grants            the grants to the group, as {@code listGrants} shows them, by system and service identity
     *                          name
     * @param serviceIdentities the service identities the group may use, by name
     */
    record Uses(List<GroupUsageItem> agents, List<AccountGrant> grants, List<GroupUsageItem> serviceIdentities) {

        public static final Uses NONE = new Uses(List.of(), List.of(), List.of());

        public Uses {
            agents = List.copyOf(agents);
            grants = List.copyOf(grants);
            serviceIdentities = List.copyOf(serviceIdentities);
        }

        public static Uses agents(List<GroupUsageItem> agents) {
            return new Uses(agents, List.of(), List.of());
        }

        public static Uses grants(List<AccountGrant> grants) {
            return new Uses(List.of(), grants, List.of());
        }

        public static Uses serviceIdentities(List<GroupUsageItem> serviceIdentities) {
            return new Uses(List.of(), List.of(), serviceIdentities);
        }
    }

    /** This module's part of what the members of the company's group (it exists) may use, as the caller may see it. */
    Uses of(CompanyAccess access, UUID groupId);
}
