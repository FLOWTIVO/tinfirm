package com.flowtivo.tinfirm.system.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditTargetNames;

/**
 * The names of {@code system} and {@code repository} targets in a company's audit (CONVENTIONS §8): a company system
 * and a repository (always a company code host's) by their names; a personal MCP server only to its owner, since it is
 * {@code 404} to everyone else, admins included (I6, §15.2). A removed system or repository has none.
 */
@Component
class SystemAuditNames implements AuditTargetNames {

    private static final String SYSTEM = "system";

    private static final String REPOSITORY = "repository";

    private final JdbcClient jdbc;

    SystemAuditNames(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> targetTypes() {
        return Set.of(SYSTEM, REPOSITORY);
    }

    @Override
    public Map<String, String> names(Reader reader, String targetType, Set<String> targetIds) {
        Set<UUID> ids = AuditTargetNames.uuids(targetIds);
        Map<String, String> names = new HashMap<>();
        if (reader.companyId() == null || ids.isEmpty()) {
            return names;
        }
        if (REPOSITORY.equals(targetType)) {
            jdbc.sql("SELECT id, name FROM system_repository WHERE company_id = :companyId AND id IN (:ids)")
                .param("companyId", reader.companyId())
                .param("ids", ids)
                .query(rs -> {
                    names.put(rs.getObject("id", UUID.class).toString(), rs.getString("name"));
                });
            return names;
        }
        jdbc.sql("SELECT id, name, owner_principal_id FROM external_system"
                + " WHERE company_id = :companyId AND id IN (:ids)")
            .param("companyId", reader.companyId())
            .param("ids", ids)
            .query(rs -> {
                UUID owner = rs.getObject("owner_principal_id", UUID.class);
                if (owner == null || Objects.equals(owner, reader.principalId())) {
                    names.put(rs.getObject("id", UUID.class).toString(), rs.getString("name"));
                }
            });
        return names;
    }
}
