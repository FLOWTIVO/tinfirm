package com.flowtivo.tinfirm.trigger.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditTargetNames;

/**
 * The names of {@code trigger} targets in a company's audit (CONVENTIONS §8): a live trigger by its name, which OWNER
 * and ADMIN, the audit's readers, see on every trigger (§15.15). A deleted trigger is {@code 404} and has none.
 */
@Component
class TriggerAuditNames implements AuditTargetNames {

    private final JdbcClient jdbc;

    TriggerAuditNames(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> targetTypes() {
        return Set.of("trigger");
    }

    @Override
    public Map<String, String> names(Reader reader, String targetType, Set<String> targetIds) {
        Set<UUID> ids = AuditTargetNames.uuids(targetIds);
        Map<String, String> names = new HashMap<>();
        if (reader.companyId() == null || ids.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, name FROM trigger WHERE company_id = :companyId AND id IN (:ids) AND deleted_at IS NULL")
            .param("companyId", reader.companyId())
            .param("ids", ids)
            .query(rs -> {
                names.put(rs.getObject("id", UUID.class).toString(), rs.getString("name"));
            });
        return names;
    }
}
