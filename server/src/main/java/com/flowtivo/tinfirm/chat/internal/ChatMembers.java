package com.flowtivo.tinfirm.chat.internal;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.ChatAccounts;
import com.flowtivo.tinfirm.agent.AgentAccess;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ChatPreference;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * A member's side of the chats (the package 5 contract, §5.5, §6, open point 16): the agent their direct messages go
 * to without a handle ({@code /me/chat}), and an admin's removal of a member's chat account, the kill switch for a
 * compromised one ({@code account.disconnected} with {@code cause} {@code admin}).
 */
@Component
class ChatMembers {

    private final ChatStore store;

    private final Principals principals;

    private final Agents agents;

    private final Accounts accounts;

    private final ChatAccounts chatAccounts;

    private final Systems systems;

    private final CompanyAccessGuard guard;

    private final TransactionTemplate transactions;

    private final TimeSource time;

    ChatMembers(ChatStore store, Principals principals, Agents agents, Accounts accounts, ChatAccounts chatAccounts,
            Systems systems, CompanyAccessGuard guard, PlatformTransactionManager transactionManager,
            TimeSource time) {
        this.store = store;
        this.principals = principals;
        this.agents = agents;
        this.accounts = accounts;
        this.chatAccounts = chatAccounts;
        this.systems = systems;
        this.guard = guard;
        this.transactions = new TransactionTemplate(transactionManager);
        this.time = time;
    }

    ChatPreference preference(UUID companyId) {
        return inTransaction(() -> {
            guard.require(companyId, CompanyPermission.VIEW_COMPANY);
            PrincipalInfo caller = caller(companyId);
            return new ChatPreference().defaultAgentId(store.defaultAgent(caller.id()).orElse(null));
        });
    }

    /** An agent the caller uses ({@code 404} one they do not see, {@code 403 AGENT_NOT_USABLE}); null for none. */
    ChatPreference setPreference(UUID companyId, ChatPreference preference) {
        @Nullable UUID agentId = preference.getDefaultAgentId();
        return inTransaction(() -> {
            guard.require(companyId, CompanyPermission.VIEW_COMPANY);
            PrincipalInfo caller = caller(companyId);
            if (agentId != null) {
                UUID userId = Objects.requireNonNull(caller.userId());
                if (agents.find(companyId, agentId).isEmpty()) {
                    throw ApiException.notFound("Agent not found");
                }
                AgentAccess access = agents.access(companyId, agentId, userId);
                if (!access.sees()) {
                    throw ApiException.notFound("Agent not found");
                }
                if (!access.uses()) {
                    throw ApiException.forbidden(ProblemCode.AGENT_NOT_USABLE, "You do not use this agent");
                }
            }
            store.defaultAgent(companyId, caller.id(), agentId, time.now());
            return new ChatPreference().defaultAgentId(agentId);
        });
    }

    /** {@code DELETE /members/{userId}/accounts/{systemId}} (OWNER, ADMIN). */
    void removeMemberAccount(UUID companyId, UUID userId, UUID systemId) {
        inTransaction(() -> {
            guard.require(companyId, CompanyPermission.MANAGE_MEMBERS);
            PrincipalInfo member = principals.ofMember(companyId, userId)
                .orElseThrow(() -> ApiException.notFound("Member not found"));
            SystemInfo system = systems.find(companyId, systemId)
                .orElseThrow(() -> ApiException.notFound("System not found"));
            if (!system.kind().chat()) {
                throw ApiException.badRequest(ProblemCode.NOT_A_CHAT_ACCOUNT, "Only an account on a chat system is "
                        + "removed here");
            }
            AccountInfo account = accounts.of(companyId, systemId, member.id())
                .orElseThrow(() -> ApiException.notFound("The member holds no account on this system"));
            chatAccounts.removeByAdmin(companyId, account.id());
            return Boolean.TRUE;
        });
    }

    private PrincipalInfo caller(UUID companyId) {
        return principals.ofMember(companyId, CurrentActor.requireUser().id())
            .filter(principal -> principal.userId() != null)
            .orElseThrow(() -> ApiException.notFound("Member not found"));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }
}
