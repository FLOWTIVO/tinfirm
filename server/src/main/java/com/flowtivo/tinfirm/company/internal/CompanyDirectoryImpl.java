package com.flowtivo.tinfirm.company.internal;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.shared.access.CompanyRole;

@Component
class CompanyDirectoryImpl implements CompanyDirectory {

    private final JdbcClient jdbc;

    CompanyDirectoryImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompanySummary> find(UUID companyId) {
        return jdbc.sql("SELECT id, name FROM company WHERE id = ?")
            .param(companyId)
            .query((rs, row) -> new CompanySummary(rs.getObject("id", UUID.class), rs.getString("name")))
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompanyRole> roleOf(UUID companyId, UUID userId) {
        return jdbc.sql("SELECT role FROM company_membership WHERE company_id = ? AND user_id = ?")
            .params(companyId, userId)
            .query((rs, row) -> CompanyRole.valueOf(rs.getString("role")))
            .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean scimSuspended(UUID companyId, UUID userId) {
        return jdbc.sql("SELECT scim_suspended_at IS NOT NULL FROM company_membership WHERE company_id = ? AND user_id = ?")
            .params(companyId, userId)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> usersWithRole(UUID companyId, CompanyRole role) {
        return new HashSet<>(jdbc.sql("SELECT user_id FROM company_membership WHERE company_id = ? AND role = ?")
            .params(companyId, role.name())
            .query(UUID.class)
            .list());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public java.util.List<UUID> companiesOf(UUID userId) {
        return jdbc.sql("SELECT company_id FROM member_companies(?)").param(userId).query(UUID.class).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockMemberships(UUID companyId) {
        jdbc.sql("SELECT id FROM company WHERE id = ? FOR NO KEY UPDATE").param(companyId).query(UUID.class).optional();
    }
}
