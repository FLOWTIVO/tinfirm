package com.flowtivo.tinfirm.chat.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.retention.IdentityContent;

/**
 * {@link IdentityContent} for the chat module's own rows (package 5; the package 6 contract, §5.9, open point 17): what
 * a chat system keeps of a chat user, by their id there ({@code external_id}, the {@code sender} of an event). An
 * erasure deletes their {@code chat_user} row (their name, their direct message's reference), the events they sent
 * ({@code chat_event}: the payload of one not yet handled holds their words, a handled one their id), and their
 * sign-in links ({@code chat_link_request}: their name and team), as the retention sweep deletes such rows when they
 * are due. A link is an account, which this does not touch. What a chat thread's conversation stored of their words
 * (its {@code EXTERNAL} messages, and the decisions they answered or pressed) is the conversation module's, which
 * erases it through its own implementation ({@code ChatThreadIdentityContent}). Holds no conversation's row: runs in the
 * caller's transaction after the conversations were locked, and the chat's rows come after them (CONVENTIONS §10).
 */
@Component
class ChatIdentityContent implements IdentityContent {

    private final ChatStore store;

    ChatIdentityContent(ChatStore store) {
        this.store = store;
    }

    @Override
    public Set<UUID> conversationsOf(UUID companyId, UUID systemId, String externalId) {
        return Set.of();
    }

    @Override
    public Erased erase(UUID companyId, UUID systemId, String externalId, Instant at) {
        return erased(store.deleteUser(companyId, systemId, externalId),
                store.deleteEventsOf(companyId, systemId, externalId),
                store.deleteLinksOf(companyId, systemId, externalId));
    }

    @Override
    public Erased count(UUID companyId, UUID systemId, String externalId) {
        return erased(store.countUser(companyId, systemId, externalId),
                store.countEventsOf(companyId, systemId, externalId),
                store.countLinksOf(companyId, systemId, externalId));
    }

    private static Erased erased(long users, long events, long links) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (users > 0) {
            counts.put("chatUsers", users);
        }
        if (events > 0) {
            counts.put("chatEvents", events);
        }
        if (links > 0) {
            counts.put("chatLinks", links);
        }
        return new Erased(counts, Set.of());
    }
}
