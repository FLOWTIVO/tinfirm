package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunGitRevocations;
import com.flowtivo.tinfirm.run.RunRequests;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * The renewal of a run's git tokens that a GitHub App minted ({@code GIT_TOKEN}, ADR-0116, decision 9), outside any
 * transaction: per binding, the gate again ({@link ConversationBindings#use(UUID, UUID, String,
 * ConversationBindings.GitScope)} with the purpose {@code run:{runId}}), so the binding's check, the kill switches and
 * the personal-host rule hold as at the assignment; the access is the lower of the assignment's (a repository the run
 * may only read) and the binding's now. A binding that waits or is gone, a repository the installation no longer
 * covers, a repository gone, and a binding whose account is no App installation any more are refused for good
 * ({@code GIT_REVOKED}); a kill switch that holds is {@code GIT_HELD}; GitHub not answering now is
 * {@code GIT_UNAVAILABLE}.
 */
@Component
class GitTokenRefresh {

    /** The user name of git over HTTPS with an installation token, as for every GitHub token. */
    static final String USERNAME = "x-access-token";

    private final ConversationBindings bindings;

    private final Systems systems;

    private final BindingTexts texts;

    private final SystemTransactions transactions;

    GitTokenRefresh(ConversationBindings bindings, Systems systems, BindingTexts texts,
            SystemTransactions transactions) {
        this.bindings = bindings;
        this.systems = systems;
        this.texts = texts;
        this.transactions = transactions;
    }

    RunRequests.GitTokenAnswer refresh(ActiveRun run, List<RunRequests.GitTokenRepository> repositories) {
        NoTransaction.require("Renewing a run's git tokens");
        UUID companyId = run.companyId();
        Map<UUID, RunnerMessages.GitToken> tokens = new HashMap<>();
        Map<UUID, RunRequests.Refusal> refused = new HashMap<>();
        Map<UUID, List<RunRequests.GitTokenRepository>> byBinding = new LinkedHashMap<>();
        for (RunRequests.GitTokenRepository repository : repositories) {
            if (repository.bindingId() == null) {
                refused.put(repository.componentId(), revoked(RunGitRevocations.REFRESH_REFUSED,
                        "the repository has no account in this conversation"));
            }
            else {
                byBinding.computeIfAbsent(repository.bindingId(), id -> new ArrayList<>()).add(repository);
            }
        }
        for (Map.Entry<UUID, List<RunRequests.GitTokenRepository>> group : byBinding.entrySet()) {
            List<ConversationBindings.GitRepository> scope = new ArrayList<>();
            for (RunRequests.GitTokenRepository repository : group.getValue()) {
                RepositoryInfo found = transactions.inCompany(companyId,
                        () -> systems.repository(companyId, repository.componentId())).orElse(null);
                if (found == null) {
                    refused.put(repository.componentId(), revoked(RunGitRevocations.REFRESH_REFUSED,
                            "the repository was removed"));
                }
                else {
                    scope.add(new ConversationBindings.GitRepository(found.id(), found.url(), repository.readOnly()));
                }
            }
            if (scope.isEmpty()) {
                continue;
            }
            ConversationBindings.BindingUse use = bindings.use(companyId, group.getKey(), "run:" + run.runId(),
                    new ConversationBindings.GitScope(scope));
            for (ConversationBindings.GitRepository repository : scope) {
                UUID componentId = repository.repositoryId();
                switch (use) {
                    case ConversationBindings.BindingUse.AppTokens app -> {
                        Accounts.RunGitToken.Minted token = app.tokens().get(componentId);
                        if (token != null) {
                            tokens.put(componentId, new RunnerMessages.GitToken(componentId, USERNAME, token.token(),
                                    token.expiresAt().toString()));
                        }
                        else {
                            refused.put(componentId, new RunRequests.Refusal(RunnerMessages.GIT_UNAVAILABLE,
                                    RunGitRevocations.REFRESH_REFUSED, "GitHub minted no token for it now"));
                        }
                    }
                    case ConversationBindings.BindingUse.Held held -> refused.put(componentId,
                            new RunRequests.Refusal(RunnerMessages.GIT_HELD, RunGitRevocations.REFRESH_REFUSED,
                                    "The token is not renewed now: " + held.why() + "."));
                    case ConversationBindings.BindingUse.Failed failed -> refused.put(componentId,
                            new RunRequests.Refusal(RunnerMessages.GIT_UNAVAILABLE, RunGitRevocations.REFRESH_REFUSED,
                                    "GitHub could not mint a new token now."));
                    case ConversationBindings.BindingUse.NotCovered notCovered -> refused.put(componentId,
                            revoked(RunGitRevocations.REFRESH_REFUSED, notCovered.message()));
                    case ConversationBindings.BindingUse.Unavailable unavailable -> {
                        BindingInfo binding = unavailable.binding();
                        String why = transactions.inCompany(companyId, () -> texts.revoked(binding));
                        refused.put(componentId, revoked(binding.reason() != null ? binding.reason().name()
                                : RunGitRevocations.REFRESH_REFUSED, why));
                    }
                    default -> refused.put(componentId, revoked(RunGitRevocations.REFRESH_REFUSED,
                            "the repository's account is no GitHub App installation any more"));
                }
            }
        }
        return new RunRequests.GitTokenAnswer(tokens, refused);
    }

    private static RunRequests.Refusal revoked(String reason, String why) {
        return new RunRequests.Refusal(RunnerMessages.GIT_REVOKED, reason, why);
    }
}
