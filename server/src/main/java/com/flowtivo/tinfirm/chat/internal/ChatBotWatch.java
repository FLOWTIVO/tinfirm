package com.flowtivo.tinfirm.chat.internal;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.trigger.ChannelTriggers;

/**
 * The bot's changes, as channel triggers follow them (the package 5 contract, §5.14, open point 21(b)): a bot set,
 * stored again, refused or removed ({@code AccountConnected}, {@code AccountReconnected},
 * {@code AccountNeedsReconnect}, {@code AccountDisconnected} of a chat system's bot account), and its service identity
 * suspended or resumed. After the commit, each concerned system's channel triggers go through the gate again in a
 * transaction of their own, so that they wait {@code BOT_MISSING}, {@code BOT_RECONNECT} or {@code BOT_SUSPENDED}, or go
 * on by themselves; as package 4's kill switches do, apart from the change's transaction and its locks. A change of
 * a system's account, and the system's resume, also forget when its channels were last read from Slack
 * ({@link ChatChannels#forgetRefresh}), so that the next channel list asks Slack at once.
 */
@Component
class ChatBotWatch {

    private static final Logger log = LoggerFactory.getLogger(ChatBotWatch.class);

    private final ChatSystems chatSystems;

    private final Accounts accounts;

    private final ChannelTriggers channelTriggers;

    private final SystemTransactions transactions;

    private final ChatChannels channels;

    ChatBotWatch(ChatSystems chatSystems, Accounts accounts, ChannelTriggers channelTriggers,
            SystemTransactions transactions, ChatChannels channels) {
        this.chatSystems = chatSystems;
        this.accounts = accounts;
        this.channelTriggers = channelTriggers;
        this.transactions = transactions;
        this.channels = channels;
    }

    @TransactionalEventListener
    void systemResumed(SystemEvents.SystemResumed event) {
        channels.forgetRefresh(event.systemId());
    }

    @TransactionalEventListener
    void accountConnected(AccountEvents.AccountConnected event) {
        account(event.companyId(), event.systemId(), event.accountId());
    }

    @TransactionalEventListener
    void accountReconnected(AccountEvents.AccountReconnected event) {
        account(event.companyId(), event.systemId(), event.accountId());
    }

    @TransactionalEventListener
    void accountNeedsReconnect(AccountEvents.AccountNeedsReconnect event) {
        account(event.companyId(), event.systemId(), event.accountId());
    }

    @TransactionalEventListener
    void accountDisconnected(AccountEvents.AccountDisconnected event) {
        account(event.companyId(), event.systemId(), event.accountId());
    }

    @TransactionalEventListener
    void principalSuspended(PrincipalEvents.PrincipalSuspended event) {
        holder(event.companyId(), event.principalId());
    }

    @TransactionalEventListener
    void principalResumed(PrincipalEvents.PrincipalResumed event) {
        holder(event.companyId(), event.principalId());
    }

    /** The account's system, when it is a chat system whose bot it is, or that lost its bot. */
    private void account(UUID companyId, UUID systemId, UUID accountId) {
        // Any account of the system: forgetting costs one call to Slack at most.
        channels.forgetRefresh(systemId);
        later(companyId, () -> chatSystems.find(companyId, systemId)
            .filter(system -> system.botAccountId() == null || accountId.equals(system.botAccountId()))
            .map(system -> Set.of(system.id()))
            .orElse(Set.of()));
    }

    /** The chat systems whose bot the service account borrows (ADR-0072). */
    private void holder(UUID companyId, UUID principalId) {
        later(companyId, () -> {
            Set<UUID> systems = new LinkedHashSet<>();
            for (Accounts.Lent lent : accounts.borrowedBy(companyId, principalId)) {
                chatSystems.botOf(companyId, lent.credential().id()).ifPresent(systems::add);
            }
            return systems;
        });
    }

    private void later(UUID companyId, java.util.function.Supplier<Set<UUID>> concerned) {
        Thread.ofVirtual().name("chat-bot-changed").start(() -> {
            try {
                for (UUID systemId : transactions.inCompany(companyId, concerned)) {
                    transactions.runInCompany(companyId, () -> channelTriggers.botChanged(companyId, systemId));
                }
            }
            catch (RuntimeException ex) {
                log.warn("The channel triggers of company {} could not follow their bot ({})", companyId,
                        ex.getClass().getSimpleName());
            }
        });
    }
}
