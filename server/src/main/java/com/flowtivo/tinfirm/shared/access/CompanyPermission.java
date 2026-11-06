package com.flowtivo.tinfirm.shared.access;

import java.util.EnumSet;
import java.util.Set;

/**
 * Company-scoped operations and the roles that may perform them (the matrix of CONVENTIONS §6 with the three roles of
 * the platform redesign, Part 8). An instance admin who is not a member may perform all of them, audited as
 * {@code admin.company.accessed} (the break-glass); one who is a member gets their role's answer, as every member does.
 * A specific permission beyond the roles (who may use a service identity, who may act with a granted account) is a
 * group's, never a role's.
 * <p>
 * VIEWER (ADR-0077, decision 5) holds the permissions that every role holds, which read, and none that starts or
 * continues work ({@link #WORK}); the access guard also refuses a Viewer's writes under a reading permission
 * ({@code 403 VIEWER_READ_ONLY}).
 */
public enum CompanyPermission {

    VIEW_COMPANY(EnumSet.allOf(CompanyRole.class)),
    RENAME_COMPANY(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    VIEW_MEMBERS(EnumSet.allOf(CompanyRole.class)),
    MANAGE_MEMBERS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /** List the company's groups by name, so that whoever picks a group can (ADR-0049 §2). */
    VIEW_GROUPS(EnumSet.allOf(CompanyRole.class)),
    /** Create, rename, fill and delete groups, and see their members (ADR-0049 §2). */
    MANAGE_GROUPS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /** Grant, change or revoke the OWNER role (on top of {@link #MANAGE_MEMBERS}). */
    MANAGE_OWNERS(EnumSet.of(CompanyRole.OWNER)),
    VIEW_AUDIT(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    VIEW_SECRETS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    MANAGE_SECRETS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /**
     * List the runner hosts one sees with their live status: one's own personal hosts, and the company hosts with
     * {@link #MANAGE_RUNNER_HOSTS} (the package 2 contract, §8).
     */
    VIEW_RUNNER_HOSTS(EnumSet.allOf(CompanyRole.class)),
    /** Register one's own personal runner host, and change or remove one's own personal hosts (contract §8). */
    REGISTER_RUNNER_HOSTS(Roles.SEATED),
    /** Register, see, change and remove company runner hosts (on top of {@link #REGISTER_RUNNER_HOSTS}). */
    MANAGE_RUNNER_HOSTS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /**
     * Read and change the company's model settings (its LiteLLM, the defaults), test them, and read and change any
     * principal's budget and models (the package 2 contract, §8).
     */
    MANAGE_MODEL_ACCESS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /** List the models the gateway serves, and read one's own model access (contract §8). */
    VIEW_MODEL_ACCESS(EnumSet.allOf(CompanyRole.class)),
    /**
     * List the service identities with whether the caller may use each (the package 1 contract, §6): their names,
     * descriptions and status.
     */
    VIEW_SERVICE_IDENTITIES(EnumSet.allOf(CompanyRole.class)),
    /**
     * Create, rename, describe, suspend, resume and delete service identities, set the groups that may use them, and
     * connect and disconnect their accounts (contract §6, invariant I4).
     */
    MANAGE_SERVICE_IDENTITIES(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /**
     * See the company's systems, their repositories and tools, and register, change and remove one's own personal MCP
     * servers (contract §6); connect and disconnect one's own accounts.
     */
    VIEW_SYSTEMS(EnumSet.allOf(CompanyRole.class)),
    /**
     * Register, change and remove company systems, their apps, repositories and tool catalogues, and switch personal
     * MCP servers on or off (contract §6, §10).
     */
    MANAGE_SYSTEMS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /** Lend service identities' accounts to groups, change and withdraw the grants (contract §6). */
    MANAGE_GRANTS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /**
     * List the agents one sees and create one's own (the package 3 contract, §6): one's own, those shared with one's
     * groups, and with {@link #MANAGE_AGENTS} every agent.
     */
    VIEW_AGENTS(EnumSet.allOf(CompanyRole.class)),
    /**
     * See, edit, share, pause and delete every agent of the company (contract §5.1, open point 11); using one stays its
     * owner's and its groups' right.
     */
    MANAGE_AGENTS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /**
     * Set the retention periods, hold and release conversations and people, erase content on request, and acknowledge
     * a failed check of the audit chain (the package 6 contract, §6): the company's record-keeping is the OWNER's.
     */
    MANAGE_RETENTION(EnumSet.of(CompanyRole.OWNER)),
    /** See the company's SCIM and its tokens (ADR-0087, decision 11). */
    VIEW_PROVISIONING(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /**
     * Issue and revoke the company's SCIM tokens (ADR-0087, decision 3): who joins the company through its identity
     * provider is the OWNER's.
     */
    MANAGE_PROVISIONING(EnumSet.of(CompanyRole.OWNER)),
    /**
     * Stop and lift every run of the company, the kill switch for an incident (the package 6 contract, §5.4); an
     * instance admin under break-glass too.
     */
    STOP_RUNS(EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN)),
    /**
     * Start or continue work (ADR-0077, decision 5): start, message, retry and bind conversations; create, own and edit
     * agents and skills, and one's own settings of them; author triggers; answer and decide requests. Every role that
     * holds a seat; never VIEWER.
     */
    WORK(Roles.SEATED);

    /** The roles that hold a seat under a licence that counts them, and do work (ADR-0077, decision 5). */
    private static final class Roles {
        static final Set<CompanyRole> SEATED = EnumSet.of(CompanyRole.OWNER, CompanyRole.ADMIN, CompanyRole.MEMBER);
    }

    private final Set<CompanyRole> roles;

    CompanyPermission(Set<CompanyRole> roles) {
        this.roles = roles;
    }

    public boolean isGrantedTo(CompanyRole role) {
        return roles.contains(role);
    }
}
