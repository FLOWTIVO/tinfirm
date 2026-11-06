package com.flowtivo.tinfirm.company.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyProvisioning;
import com.flowtivo.tinfirm.company.MemberPrincipals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.api.model.Company;
import com.flowtivo.tinfirm.shared.api.model.CompanyPage;
import com.flowtivo.tinfirm.shared.api.model.CompanyUpdateRequest;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;

@Service
class CompanyService implements CompanyProvisioning {

    private static final int MAX_NAME_LENGTH = 100;

    private final CompanyRepository companies;

    private final MembershipRepository memberships;

    private final CompanyQueries queries;

    private final CompanyAccessGuard companyAccess;

    private final TenantScope tenantScope;

    private final MemberPrincipals principals;

    private final AuditLog auditLog;

    private final CompanyMapper mapper;

    private final TimeSource time;

    CompanyService(CompanyRepository companies, MembershipRepository memberships, CompanyQueries queries,
            CompanyAccessGuard companyAccess, TenantScope tenantScope, MemberPrincipals principals, AuditLog auditLog,
            CompanyMapper mapper, TimeSource time) {
        this.companies = companies;
        this.memberships = memberships;
        this.queries = queries;
        this.companyAccess = companyAccess;
        this.tenantScope = tenantScope;
        this.principals = principals;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.time = time;
    }

    /** Companies the caller is a member of; instance admins see all companies. */
    @Transactional(readOnly = true)
    public CompanyPage list(@Nullable Integer limit, @Nullable String cursor) {
        AuthenticatedUser user = CurrentActor.requireUser();
        PageRequest request = PageRequest.of(limit, cursor);
        List<CompanyQueries.CompanyRow> rows = request.firstPage()
                ? queries.companies(user.id(), user.instanceAdmin(), null, null, request.fetchSize())
                : queries.companies(user.id(), user.instanceAdmin(), request.key(0, 2), request.uuidKey(1, 2),
                        request.fetchSize());
        Page<Company> page = Page.of(rows, request, row -> List.of(row.name(), row.id().toString()))
            .map(mapper::toDto);
        return new CompanyPage(page.items()).nextCursor(page.nextCursor());
    }

    @Transactional(readOnly = true)
    public Company get(UUID companyId) {
        // A suspended member still sees the company's page, with their suspension (the package 6 contract, open point 24).
        companyAccess.requireAllowingSuspended(companyId, CompanyPermission.VIEW_COMPANY);
        return mapper.toDto(loadRow(companyId));
    }

    @Transactional
    public Company rename(UUID companyId, CompanyUpdateRequest request) {
        companyAccess.require(companyId, CompanyPermission.RENAME_COMPANY);
        CompanyEntity company = companies.findById(companyId)
            .orElseThrow(() -> ApiException.notFound("Company not found"));
        String previousName = company.getName();
        if (!previousName.equals(request.getName())) {
            company.rename(request.getName(), time.now());
            companies.flush();
            auditLog.record(AuditRecord.company(companyId, AuditAction.COMPANY_UPDATED, CurrentActor.actor())
                .target("company", companyId)
                .detail("name", request.getName())
                .detail("previousName", previousName)
                .build());
        }
        return mapper.toDto(loadRow(companyId));
    }

    @Override
    @Transactional
    public UUID createCompany(String name, UUID ownerUserId) {
        return create(name, null, ownerUserId);
    }

    /**
     * A new company, audited as {@code company.created} with the current actor and the key; with an owner, that
     * user's OWNER membership too. No HTTP endpoint creates companies: the deployment declares them (ADR-0075).
     */
    @Transactional
    UUID create(String name, @Nullable String key, @Nullable UUID ownerUserId) {
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw ApiException.validation("Invalid company name");
        }
        CompanyEntity company = new CompanyEntity(Ids.newId(), name, key, time.now());
        companies.saveAndFlush(company);
        // The company was just created by a trusted caller: enter its context for the owner and its audit chain.
        tenantScope.enterCompany(company.getId());
        AuditRecord.Builder created = AuditRecord.company(company.getId(), AuditAction.COMPANY_CREATED,
                CurrentActor.actor())
            .target("company", company.getId())
            .detail("name", name);
        if (key != null) {
            created.detail("key", key);
        }
        auditLog.record(created.build());
        if (ownerUserId != null) {
            memberships.saveAndFlush(new MembershipEntity(company.getId(), ownerUserId, CompanyRole.OWNER,
                    time.now()));
            auditLog.record(AuditRecord.company(company.getId(), AuditAction.MEMBER_ADDED, CurrentActor.actor())
                .target("user", ownerUserId)
                .detail("userId", ownerUserId.toString())
                .detail("role", CompanyRole.OWNER.name())
                .build());
            principals.joined(company.getId(), ownerUserId);
        }
        return company.getId();
    }

    private CompanyQueries.CompanyRow loadRow(UUID companyId) {
        return queries.company(companyId, CurrentActor.requireUser().id())
            .orElseThrow(() -> ApiException.notFound("Company not found"));
    }
}
