package com.flowtivo.tinfirm.company.internal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.MemberPrincipals;
import com.flowtivo.tinfirm.company.scim.ScimMembers;
import com.flowtivo.tinfirm.identity.ProvisionedMemberships;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The companies a user of an identity provider joins, those memberships (ADR-0075, ADR-0089), the role a provider
 * decides at every sign-in (ADR-0088, through {@link MembershipRoles}) and the membership it ends. The companies are
 * declared ones, found by their keys with the instance's rights. Each membership is written in the login's
 * transaction, in the tenant context of its company, which this class enters first: the company ids come from the
 * deployment's declarations, resolved by the server, never from the request, so row-level security admits each write
 * for that company only.
 */
@Component
class ProvisionedMembershipsImpl implements ProvisionedMemberships {

    private final DeclaredCompaniesProperties declarations;

    private final CompanyQueries queries;

    private final SystemTransactions transactions;

    private final MembershipRepository memberships;

    private final MemberPrincipals principals;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final Licensing licensing;

    private final MembershipRoles roles;

    private final ObjectProvider<ScimMembers> scimMembers;

    private final TenantScope tenantScope;

    private final ObjectProvider<MemberService> members;

    ProvisionedMembershipsImpl(DeclaredCompaniesProperties declarations, CompanyQueries queries,
            SystemTransactions transactions, MembershipRepository memberships, MemberPrincipals principals,
            AuditLog auditLog, TimeSource time, Licensing licensing, MembershipRoles roles,
            ObjectProvider<ScimMembers> scimMembers, TenantScope tenantScope, ObjectProvider<MemberService> members) {
        this.scimMembers = scimMembers;
        this.tenantScope = tenantScope;
        this.members = members;
        this.licensing = licensing;
        this.roles = roles;
        this.declarations = declarations;
        this.queries = queries;
        this.transactions = transactions;
        this.memberships = memberships;
        this.principals = principals;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Override
    public boolean declares(String companyKey) {
        return declarations.companies().containsKey(companyKey);
    }

    @Override
    public Optional<UUID> companyFor(@Nullable String companyKey) {
        return companyKeyFor(companyKey).flatMap(key -> transactions.instanceWide(() -> queries.companyIdByKey(key)));
    }

    @Override
    public Optional<String> companyKeyFor(@Nullable String companyKey) {
        String key = companyKey != null ? companyKey : declarations.onlyKey().orElse(null);
        return key != null && declares(key) ? Optional.of(key) : Optional.empty();
    }

    @Override
    @Transactional
    public CompanyRole grant(UUID companyId, UUID userId, CompanyRole role, String provider, RoleSource source) {
        if (role == CompanyRole.OWNER) {
            throw new IllegalArgumentException("An identity provider never makes owners");
        }
        tenantScope.enterCompany(companyId);
        // ADR-0077, decision 5: without a free seat the new user joins as a Viewer, and the admins are told.
        CompanyRole given = licensing.seatAvailableFor(userId, role) ? role : CompanyRole.VIEWER;
        memberships.saveAndFlush(new MembershipEntity(companyId, userId, given, source, time.now()));
        AuditRecord.Builder added = AuditRecord.company(companyId, AuditAction.MEMBER_ADDED, CurrentActor.actor())
            .target("user", userId)
            .detail("userId", userId.toString())
            .detail("role", given.name())
            .detail("provider", provider);
        if (source.managed()) {
            added.detail("roleSource", "IDP");
        }
        if (given != role) {
            added.detail("seatFallback", true).detail("providerRole", role.name());
            queries.seatFallback(companyId, userId, role, provider, time.now());
        }
        auditLog.record(added.build());
        principals.joined(companyId, userId);
        return given;
    }

    @Override
    @Transactional
    public ProviderRole applyProviderRole(UUID companyId, UUID userId, @Nullable CompanyRole role, String provider) {
        tenantScope.enterCompany(companyId);
        // Under the company's lock, as leave() and the member operations take it, and the membership read after it.
        // The lock lasts until the sign-in commits: of two sign-ins that the claim newly maps into the company, the
        // second waits here and then finds the membership the first granted after NOT_A_MEMBER, instead of granting it
        // too and failing on the membership's key.
        queries.lockCompany(companyId);
        return switch (roles.apply(companyId, userId, role, RoleSource.provider(provider))) {
            case APPLIED -> ProviderRole.APPLIED;
            case KEPT -> ProviderRole.KEPT;
            case NOT_A_MEMBER -> ProviderRole.NOT_A_MEMBER;
            case NOT_MAPPED -> ProviderRole.NOT_MAPPED;
        };
    }

    @Override
    @Transactional
    public Departure leave(UUID companyId, UUID userId, String provider) {
        tenantScope.enterCompany(companyId);
        if (!memberships.existsByCompanyIdAndUserId(companyId, userId)) {
            return Departure.NOT_A_MEMBER;
        }
        // Under the company's lock, as the member operations take it (MemberService, ScimMembersImpl), and the
        // membership read after it: a promotion to OWNER or SCIM taking the role that commits meanwhile is seen, and
        // kept. The sign-in takes the locks of its companies in one order (ProviderCompanies), so two never deadlock.
        queries.lockCompany(companyId);
        Optional<MembershipEntity> found = memberships.findByCompanyIdAndUserId(companyId, userId);
        if (found.isEmpty()) {
            return Departure.NOT_A_MEMBER;
        }
        MembershipEntity membership = found.get();
        if (membership.getRole() == CompanyRole.OWNER || membership.getRoleSource().equals(RoleSource.SCIM)) {
            return Departure.KEPT;
        }
        members.getObject().leave(membership, provider);
        return Departure.LEFT;
    }

    @Override
    public void releaseProviderRoles(Map<String, Set<String>> deciding) {
        Map<UUID, String> keys = new java.util.HashMap<>();
        transactions.instanceWide(() -> {
            declarations.keys().forEach(key -> queries.companyIdByKey(key).ifPresent(id -> keys.put(id, key)));
            return null;
        });
        for (UUID companyId : transactions.instanceWide(queries::companiesWithManagedRoles)) {
            String key = keys.get(companyId);
            transactions.runInCompany(companyId, () -> roles.managedSources(companyId).forEach(source -> {
                String provider = source.provider();
                // A provider decides here still when it does and names this company, as its own or through its
                // claim (ADR-0089); SCIM's are SCIM's to release.
                if (provider != null && !(key != null && deciding.containsKey(provider)
                        && deciding.get(provider).contains(key))) {
                    roles.release(companyId, source);
                }
            }));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<UUID> scimMemberByExternalId(UUID companyId, String externalId) {
        return memberships.findByCompanyIdAndScimExternalId(companyId, externalId).map(MembershipEntity::getUserId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<UUID> scimMemberByEmail(UUID companyId, String email) {
        return scimMembers.getObject().findByEmail(companyId, email).map(ScimMembers.Member::userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean ownsACompany(UUID userId) {
        return scimMembers.getObject().ownsACompany(userId);
    }
}
