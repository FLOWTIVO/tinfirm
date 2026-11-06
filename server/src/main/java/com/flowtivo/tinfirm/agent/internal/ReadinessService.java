package com.flowtivo.tinfirm.agent.internal;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.AccountBinding;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.model.Hosts;
import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.model.RunModelContext;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.api.model.AgentModelAccess;
import com.flowtivo.tinfirm.shared.api.model.AgentReadiness;
import com.flowtivo.tinfirm.shared.api.model.AgentReadinessHosts;
import com.flowtivo.tinfirm.shared.api.model.AgentReadinessPayer;
import com.flowtivo.tinfirm.shared.api.model.AgentReadinessSystem;
import com.flowtivo.tinfirm.shared.api.model.AgentRepositoryAccess;
import com.flowtivo.tinfirm.shared.api.model.SystemKind;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * An agent's readiness for the caller (the package 3 contract, §6, {@code GET /agents/{agentId}/readiness}): before a
 * conversation, and storing nothing, the settings in effect, who pays ({@link ModelAccess#forRun}), how each system
 * would bind now ({@link Accounts#bind}), and whether a runner host may run it ({@link Hosts#eligible}) with the
 * accounts of the starting workspace. Reads only Tinfirm's rows: nothing here calls a system.
 */
@Service
class ReadinessService {

    private final SettingsService settings;

    private final Systems systems;

    private final Accounts accounts;

    private final ModelAccess modelAccess;

    private final Hosts hosts;

    private final RunnerConnections connections;

    ReadinessService(SettingsService settings, Systems systems, Accounts accounts, ModelAccess modelAccess,
            Hosts hosts, RunnerConnections connections) {
        this.connections = connections;
        this.settings = settings;
        this.systems = systems;
        this.accounts = accounts;
        this.modelAccess = modelAccess;
        this.hosts = hosts;
    }

    @Transactional(readOnly = true)
    public AgentReadiness readiness(UUID companyId, UUID agentId) {
        SettingsService.Use use = settings.requireUsable(companyId, agentId);
        UUID principalId = use.principalId();
        EffectiveSettings effective = settings.effective(use.row(), principalId);
        Map<UUID, SystemInfo> infos = systems.findAll(companyId, effective.systems());
        Map<UUID, AccountBinding.Outcome> outcomes = new HashMap<>();
        List<AgentReadinessSystem> systemViews = new ArrayList<>();
        for (UUID systemId : effective.systems()) {
            SystemInfo info = infos.get(systemId);
            if (info == null) {
                continue;
            }
            AccountBinding.Outcome outcome = accounts.bind(new AccountBinding.Personal(companyId, principalId),
                    systemId);
            outcomes.put(systemId, outcome);
            systemViews.add(system(info, effective.ownServer(systemId).isPresent(), outcome));
        }
        // The principals whose git accounts the starting workspace would carry: as each code host binds now.
        Set<UUID> holders = new HashSet<>();
        for (AgentDefinition.RepositoryEntry repository : effective.startingRepositories()) {
            switch (outcomes.get(repository.systemId())) {
                case AccountBinding.Own own -> holders.add(own.account().principalId());
                // A credential is no one's: its id stands for it, which is no host's owner (M4).
                case AccountBinding.Granted granted -> holders.add(granted.account().id());
                case null, default -> {
                }
            }
        }
        Optional<ModelAccessChoice> choice = effective.modelAccess().choice();
        AgentModelAccess kind = choice.isPresent() && choice.get() instanceof ModelAccessChoice.Budget
                ? AgentModelAccess.BUDGET : AgentModelAccess.SUBSCRIPTION;
        AgentReadinessPayer payer = new AgentReadinessPayer(kind, AgentReadinessPayer.StateEnum.REFUSED,
                new ArrayList<>(modelAccess.models(companyId, principalId)));
        AgentReadinessHosts hostView = new AgentReadinessHosts(false, 0);
        if (choice.isEmpty()) {
            payer.reason("SUBSCRIPTION_GONE").detail("The runner host of the subscription was removed");
            hostView.reason("Choose a model access in your settings");
        }
        else {
            RunModelContext context = new RunModelContext(companyId, principalId, choice.get(), effective.runtime(),
                    holders);
            payer(payer, modelAccess.forRun(context), effective.model());
            switch (hosts.eligible(context)) {
                case Hosts.Eligibility.Eligible eligible -> {
                    hostView.runnable(!eligible.hosts().isEmpty())
                        .eligible(eligible.hosts().size())
                        .reason(eligible.whyNone());
                    if (!effective.skills().isEmpty() && noConnectedHostDeliversSkills(eligible.hosts())) {
                        hostView.runnable(false).reason("The agent has skills, and none of the connected runner hosts "
                                + "that may run it delivers skills yet: its turns wait for one that does.");
                    }
                }
                case Hosts.Eligibility.Nowhere nowhere -> hostView.reason(nowhere.refusal().detail());
            }
        }
        return new AgentReadiness(settings.view(use.row(), principalId), payer, systemViews, hostView);
    }

    /**
     * Whether hosts that may run it are connected and none of them lists {@code run.skills} (ADR-0064): the dispatcher
     * would hold the turn with {@code NO_HOST_FOR_SKILLS}. Hosts that are not connected say nothing either way.
     */
    private boolean noConnectedHostDeliversSkills(List<RunnerHostInfo> eligible) {
        List<RunnerHostInfo> connected = eligible.stream().filter(host -> connections.isConfigured(host.id())).toList();
        return !connected.isEmpty() && connected.stream()
            .noneMatch(host -> connections.supports(host.id(), RunnerMessages.FEATURE_RUN_SKILLS));
    }

    private static void payer(AgentReadinessPayer payer, RunModelAccess access, @Nullable String model) {
        switch (access) {
            case RunModelAccess.Budget budget -> {
                RunModelAccess.Refuse refuse = budget.refusal(model);
                if (refuse != null) {
                    payer(payer, refuse, model);
                }
                else {
                    payer.state(AgentReadinessPayer.StateEnum.READY);
                }
            }
            case RunModelAccess.Subscription subscription -> payer.state(AgentReadinessPayer.StateEnum.READY);
            case RunModelAccess.Wait wait -> payer.state(AgentReadinessPayer.StateEnum.WAITING)
                .reason(wait.reason().name())
                .detail(wait.detail())
                .until(wait.until() != null ? wait.until().atOffset(ZoneOffset.UTC) : null);
            case RunModelAccess.Refuse refuse -> payer.state(AgentReadinessPayer.StateEnum.REFUSED)
                .reason(refuse.reason().name())
                .detail(refuse.detail());
        }
    }

    /** How a conversation would bind the system now, in the words of {@code MyAccount.binding}. */
    private static AgentReadinessSystem system(SystemInfo info, boolean own, AccountBinding.Outcome outcome) {
        AgentReadinessSystem view = new AgentReadinessSystem(info.id(), info.name(),
                SystemKind.fromValue(info.kind().name()), own, AgentReadinessSystem.BindingEnum.UNAVAILABLE);
        switch (outcome) {
            case AccountBinding.Own ignored -> view.binding(AgentReadinessSystem.BindingEnum.OWN)
                .access(AgentRepositoryAccess.WRITE);
            case AccountBinding.Granted granted -> view.binding(AgentReadinessSystem.BindingEnum.GRANTED)
                .grantId(granted.grant().id())
                .access(AgentRepositoryAccess.fromValue(granted.grant().access().name()));
            case AccountBinding.Choose ignored -> view.binding(AgentReadinessSystem.BindingEnum.CHOOSE);
            case AccountBinding.ConnectRequired required -> view.binding(switch (required.reason()) {
                case NONE -> AgentReadinessSystem.BindingEnum.CONNECT;
                case RECONNECT_YOURS -> AgentReadinessSystem.BindingEnum.RECONNECT_YOURS;
                case RECONNECT_COMPANY -> AgentReadinessSystem.BindingEnum.RECONNECT_COMPANY;
                case CHOOSE -> AgentReadinessSystem.BindingEnum.RECONNECT_OR_GRANT;
            });
            case AccountBinding.Fixed ignored -> view.binding(AgentReadinessSystem.BindingEnum.OWN);
            case AccountBinding.NotEntitled ignored -> view.binding(AgentReadinessSystem.BindingEnum.UNAVAILABLE);
            case AccountBinding.Open ignored -> view.binding(AgentReadinessSystem.BindingEnum.NOT_NEEDED);
        }
        return view;
    }
}
