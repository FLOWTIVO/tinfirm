package com.flowtivo.tinfirm.chat.internal;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.PlaceConversations;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemEvents;

/**
 * A chat system removed (the package 5 contract, §5.14): its open conversations end {@code SYSTEM_REMOVED}, after the
 * removal's commit, each in a transaction of its own as SYSTEM, so that each is locked first (CONVENTIONS §10). Its
 * links, its bot and its chat rows went with it in the removal's transaction.
 */
@Component
class ChatSystemRemoval {

    private static final Logger log = LoggerFactory.getLogger(ChatSystemRemoval.class);

    private final PlaceConversations places;

    private final ChatBot bot;

    private final SystemTransactions transactions;

    ChatSystemRemoval(PlaceConversations places, ChatBot bot, SystemTransactions transactions) {
        this.places = places;
        this.bot = bot;
        this.transactions = transactions;
    }

    @TransactionalEventListener
    void systemDeleted(SystemEvents.SystemDeleted event) {
        bot.forget(event.systemId());
        UUID companyId = event.companyId();
        Thread.ofVirtual().name("chat-system-removed").start(() -> {
            List<UUID> open = transactions.inCompany(companyId, () -> places.openOnSystem(companyId,
                    event.systemId()));
            for (UUID conversationId : open) {
                try {
                    transactions.runInCompany(companyId, () -> places.end(companyId, conversationId,
                            ConversationInfo.EndedReason.SYSTEM_REMOVED, null));
                }
                catch (RuntimeException ex) {
                    log.warn("Conversation {} of a removed chat system did not end ({})", conversationId,
                            ex.getClass().getSimpleName());
                }
            }
        });
    }
}
