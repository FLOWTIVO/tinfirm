package com.flowtivo.tinfirm.conversation.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditConversationKeys;
import com.flowtivo.tinfirm.audit.AuditTargetNames;

/**
 * Conversations for the company's audit (CONVENTIONS §8, §15.11): the id of a key, for its {@code conversationKey}
 * filter, and the names of {@code conversation} targets as {@code SpendKey.label} names them (G1). The reader's own
 * personal conversation and a trigger's (whose audience OWNER and ADMIN are) by key and title; another person's
 * personal conversation by key alone to OWNER, who may list it, and not at all to ADMIN, from whom spend folds it into
 * its person. Its title is never shown to anyone but its person, so no name is a read of it ({@code conversation.read}).
 * A purged or erased title leaves the key.
 */
@Component
class ConversationAuditNames implements AuditTargetNames, AuditConversationKeys {

    private final JdbcClient jdbc;

    private final ConversationStore store;

    ConversationAuditNames(JdbcClient jdbc, ConversationStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @Override
    public Optional<UUID> idOfKey(UUID companyId, String key) {
        return store.idOfKey(companyId, key);
    }

    @Override
    public Set<String> targetTypes() {
        return Set.of("conversation");
    }

    @Override
    public Map<String, String> names(Reader reader, String targetType, Set<String> targetIds) {
        Set<UUID> ids = AuditTargetNames.uuids(targetIds);
        Map<String, String> names = new HashMap<>();
        if (reader.companyId() == null || ids.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, key, title, person_principal_id, trigger_id FROM conversation"
                + " WHERE company_id = :companyId AND id IN (:ids)")
            .param("companyId", reader.companyId())
            .param("ids", ids)
            .query(rs -> {
                String key = rs.getString("key");
                String title = rs.getString("title");
                UUID person = rs.getObject("person_principal_id", UUID.class);
                boolean triggered = rs.getObject("trigger_id", UUID.class) != null;
                boolean own = person != null && Objects.equals(person, reader.principalId());
                String name = null;
                if (triggered || own) {
                    name = title != null && !title.isBlank() && !title.equals(key) ? key + " " + title : key;
                }
                else if (reader.owner()) {
                    name = key;
                }
                if (name != null) {
                    names.put(rs.getObject("id", UUID.class).toString(), name);
                }
            });
        return names;
    }
}
