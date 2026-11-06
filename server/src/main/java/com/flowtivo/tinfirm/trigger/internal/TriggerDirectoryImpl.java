package com.flowtivo.tinfirm.trigger.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.trigger.TriggerDirectory;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

/** {@link TriggerDirectory} on the {@code trigger} table, in the caller's transaction and tenant context. */
@Component
class TriggerDirectoryImpl implements TriggerDirectory {

    private final JdbcClient jdbc;

    private final TriggerStore store;

    private final Principals principals;

    private final GroupMembership groups;

    TriggerDirectoryImpl(JdbcClient jdbc, TriggerStore store, Principals principals, GroupMembership groups) {
        this.jdbc = jdbc;
        this.store = store;
        this.principals = principals;
        this.groups = groups;
    }

    @Override
    public Set<UUID> ofPrincipal(UUID companyId, UUID principalId) {
        Optional<UUID> user = principals.find(companyId, principalId).map(PrincipalInfo::userId);
        if (user.isEmpty()) {
            // A service identity authors nothing and is in no team.
            return Set.of();
        }
        Set<UUID> teams = groups.groupsOf(companyId, user.get());
        return new HashSet<>(jdbc.sql("""
                SELECT id FROM trigger
                 WHERE company_id = ? AND (author_principal_id = ? OR team_group_id = ANY (CAST(? AS uuid[])))""")
            .params(companyId, principalId, ids(teams))
            .query(UUID.class)
            .list());
    }

    @Override
    public Map<UUID, String> names(UUID companyId, Collection<UUID> triggerIds) {
        if (triggerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        jdbc.sql("SELECT id, name FROM trigger WHERE company_id = ? AND id = ANY (CAST(? AS uuid[]))")
            .params(companyId, ids(triggerIds))
            .query((rs, row) -> names.put(rs.getObject("id", UUID.class), rs.getString("name")))
            .list();
        return names;
    }

    @Override
    public List<TriggerInfo> livePaidBy(UUID companyId, UUID payerPrincipalId) {
        return jdbc.sql("""
                SELECT id FROM trigger
                 WHERE company_id = ? AND payer_principal_id = ? AND deleted_at IS NULL
                 ORDER BY lower(name), id""")
            .params(companyId, payerPrincipalId)
            .query(UUID.class)
            .list()
            .stream()
            .flatMap(id -> store.find(companyId, id).stream())
            .toList();
    }

    private static String[] ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }
}
