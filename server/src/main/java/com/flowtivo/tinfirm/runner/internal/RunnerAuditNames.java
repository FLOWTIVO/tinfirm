package com.flowtivo.tinfirm.runner.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditTargetNames;

/**
 * The names of {@code runner_host} targets in a company's audit (CONVENTIONS §8): a company host by its name; a
 * personal host only to its owner, since it is {@code 404} to everyone else, OWNER and ADMIN included (§15.4). A
 * removed host has none.
 */
@Component
class RunnerAuditNames implements AuditTargetNames {

    private final JdbcClient jdbc;

    RunnerAuditNames(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> targetTypes() {
        return Set.of("runner_host");
    }

    @Override
    public Map<String, String> names(Reader reader, String targetType, Set<String> targetIds) {
        Set<UUID> ids = AuditTargetNames.uuids(targetIds);
        Map<String, String> names = new HashMap<>();
        if (reader.companyId() == null || ids.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, name, owner_principal_id FROM runner_host WHERE company_id = :companyId AND id IN (:ids)")
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
