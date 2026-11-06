package com.flowtivo.tinfirm.company.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditTargetNames;

/**
 * The names of the company module's audit targets (CONVENTIONS §8): a {@code company} by its name, in the instance's
 * chain any company (whose reader, an instance admin, sees every one) and in a company's chain that company; a
 * {@code group} by its name in its company's chain, which its admins see. A deleted group has none.
 */
@Component
class CompanyAuditNames implements AuditTargetNames {

    private static final String COMPANY = "company";

    private static final String GROUP = "group";

    private final JdbcClient jdbc;

    CompanyAuditNames(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> targetTypes() {
        return Set.of(COMPANY, GROUP);
    }

    @Override
    public Map<String, String> names(Reader reader, String targetType, Set<String> targetIds) {
        Set<UUID> ids = AuditTargetNames.uuids(targetIds);
        if (reader.companyId() != null && COMPANY.equals(targetType)) {
            ids.retainAll(Set.of(reader.companyId()));
        }
        Map<String, String> names = new HashMap<>();
        if (ids.isEmpty() || GROUP.equals(targetType) && reader.companyId() == null) {
            return names;
        }
        String sql = COMPANY.equals(targetType) ? "SELECT id, name FROM company WHERE id IN (:ids)"
                : "SELECT id, name FROM user_group WHERE company_id = :companyId AND id IN (:ids)";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("ids", ids);
        if (GROUP.equals(targetType)) {
            statement = statement.param("companyId", reader.companyId());
        }
        statement.query(rs -> {
            names.put(rs.getObject("id", UUID.class).toString(), rs.getString("name"));
        });
        return names;
    }
}
