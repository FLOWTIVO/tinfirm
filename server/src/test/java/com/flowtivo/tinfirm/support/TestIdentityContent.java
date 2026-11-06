package com.flowtivo.tinfirm.support;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.retention.IdentityContent;

/**
 * A stand-in for the content modules of packages 4 and 5, which hold the words of outside identities (TEST FIXTURE
 * ONLY): inert, but for the identities a test registers with the messages that stand for that identity's words and
 * the runs that took them. Its erasure removes those messages' bodies, as an {@code EXTERNAL} message's would be.
 */
@Component
public class TestIdentityContent implements IdentityContent {

    /** An identity's words: in these conversations, these messages, taken by these runs. */
    public record Words(Set<UUID> conversationIds, Set<UUID> messageIds, Set<UUID> runIds) {
    }

    private static final Map<String, Words> WORDS = new ConcurrentHashMap<>();

    private final JdbcClient jdbc;

    public TestIdentityContent(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static void register(UUID companyId, UUID systemId, String externalId, Words words) {
        WORDS.put(key(companyId, systemId, externalId), words);
    }

    @Override
    public Set<UUID> conversationsOf(UUID companyId, UUID systemId, String externalId) {
        Words words = WORDS.get(key(companyId, systemId, externalId));
        return words != null ? words.conversationIds() : Set.of();
    }

    @Override
    public Erased erase(UUID companyId, UUID systemId, String externalId, Instant at) {
        Words words = WORDS.get(key(companyId, systemId, externalId));
        if (words == null) {
            return new Erased(Map.of(), Set.of());
        }
        long messages = 0;
        for (UUID messageId : words.messageIds()) {
            messages += jdbc.sql("UPDATE message SET body = NULL, purged_at = ? WHERE id = ? AND purged_at IS NULL")
                .params(at.atOffset(ZoneOffset.UTC), messageId)
                .update();
        }
        return new Erased(Map.of("messages", messages), words.runIds());
    }

    @Override
    public Erased count(UUID companyId, UUID systemId, String externalId) {
        Words words = WORDS.get(key(companyId, systemId, externalId));
        if (words == null) {
            return new Erased(Map.of(), Set.of());
        }
        long messages = 0;
        for (UUID messageId : words.messageIds()) {
            messages += jdbc.sql("SELECT count(*) FROM message WHERE id = ? AND purged_at IS NULL")
                .params(messageId)
                .query(Long.class)
                .single();
        }
        return new Erased(Map.of("messages", messages), words.runIds());
    }

    private static String key(UUID companyId, UUID systemId, String externalId) {
        return companyId + "/" + systemId + "/" + externalId;
    }
}
