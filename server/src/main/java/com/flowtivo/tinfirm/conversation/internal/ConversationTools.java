package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.ToolInfo;

import tools.jackson.databind.JsonNode;

/**
 * {@code ConversationTools} (the package 3 contract, §5.4): the tools of a conversation's run, {@code list} for
 * {@code run.assign.tools} ({@link TurnTools}), and the rule each call passes, {@code modeOf} ({@link #decide}), both
 * from {@link ToolRules}: served, effect, agent, settings, binding; the account's own permissions are the system's
 * answer to the call. A run lists at most 200 tools, by the systems' names, each named as the conversation keeps it
 * ({@link ToolNameStore}), its description ending with whom it runs as once bound.
 */
@Component
class ConversationTools implements TurnTools {

    /** The most tools a run lists (the protocol's {@code run.assign.tools}). */
    static final int MAX_TOOLS = 200;

    private final Systems systems;

    private final Agents agents;

    private final Accounts accounts;

    private final Principals principals;

    private final GroupMembership groups;

    private final ConversationCore core;

    private final ToolNameStore names;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final TriggerPorts triggers;

    private final PlaceStore places;

    private final MemoryTools memory;

    ConversationTools(Systems systems, Agents agents, Accounts accounts, Principals principals,
            GroupMembership groups, ConversationCore core, ToolNameStore names, SystemTransactions transactions,
            TimeSource time, TriggerPorts triggers, PlaceStore places, MemoryTools memory) {
        this.triggers = triggers;
        this.places = places;
        this.systems = systems;
        this.agents = agents;
        this.accounts = accounts;
        this.principals = principals;
        this.groups = groups;
        this.core = core;
        this.names = names;
        this.transactions = transactions;
        this.time = time;
        this.memory = memory;
    }

    /** A tool a run lists. */
    record Listed(String name, UUID systemId, String tool, ToolRules.Decision decision,
            RunnerMessages.ToolSpec spec) {
    }

    /**
     * A call's tool, mapped back from the name the run listed it by.
     *
     * @param served the tool as its system serves it now; null when it serves none of that name any more
     */
    record Target(SystemInfo system, String tool, @Nullable ToolInfo served) {
    }

    /**
     * {@code run.assign.tools}: outside any transaction, in a short one of its own in the turn's company. The memory
     * tools first while memory is in effect (ADR-0063), then the systems' tools, at most {@value #MAX_TOOLS} in all.
     */
    @Override
    public List<RunnerMessages.ToolSpec> tools(TurnContext turn) {
        ConversationInfo conversation = turn.conversation();
        return transactions.inCompany(conversation.companyId(), () -> {
            List<RunnerMessages.ToolSpec> specs = new ArrayList<>(turn.settings().memory()
                    ? memory.specs(turn, texts(conversation)) : List.of());
            int room = MAX_TOOLS - specs.size();
            list(conversation, turn.settings()).stream().limit(room).map(Listed::spec).forEach(specs::add);
            return specs;
        });
    }

    /**
     * {@code ConversationTools.list(conversation)}: the tools whose steps 1 to 5 answer {@code ALLOW} or {@code ASK},
     * by the systems' names, then the tools' names, at most {@value #MAX_TOOLS}. In the caller's transaction; stores
     * the name of a tool the conversation lists for the first time.
     */
    List<Listed> list(ConversationInfo conversation, EffectiveSettings settings) {
        UUID companyId = conversation.companyId();
        Map<UUID, SystemInfo> found = systems.findAll(companyId, settings.systems());
        List<SystemInfo> ordered = found.values()
            .stream()
            .sorted(Comparator.comparing((SystemInfo system) -> system.name().toLowerCase(Locale.ROOT))
                .thenComparing(SystemInfo::id))
            .toList();
        Map<UUID, BindingInfo> bindings = core.bindings()
            .of(companyId, conversation.id())
            .stream()
            .collect(Collectors.toMap(BindingInfo::systemId, Function.identity(), (a, b) -> a));
        Texts texts = texts(conversation);
        List<Listed> listed = new ArrayList<>();
        for (SystemInfo system : ordered) {
            List<AgentDefinition.ToolEntry> entries = settings.tools(system.id())
                .stream()
                .sorted(Comparator.comparing(AgentDefinition.ToolEntry::name))
                .toList();
            if (entries.isEmpty()) {
                continue;
            }
            Map<String, ToolInfo> served = systems.tools(companyId, system.id())
                .stream()
                .collect(Collectors.toMap(ToolInfo::name, Function.identity(), (a, b) -> a));
            ToolRules.BindingView binding = view(conversation, system, bindings.get(system.id()), texts);
            ToolRules.Names named = texts.names(system);
            for (AgentDefinition.ToolEntry entry : entries) {
                ToolInfo tool = served.get(entry.name());
                ToolRules.Decision decision = ToolRules.decide(system, entry.name(), entry.name(), tool,
                        conversation.actingPrincipalId(), settings, binding, named);
                if (!decision.listed() || tool == null) {
                    continue;
                }
                if (listed.size() >= MAX_TOOLS) {
                    return listed;
                }
                String name = names.nameOf(companyId, conversation.id(), system.id(), system.handle(), tool.name(),
                        time.now());
                listed.add(new Listed(name, system.id(), tool.name(), decision, spec(name,
                        ToolRules.description(tool, decision, binding, named), tool)));
            }
        }
        return listed;
    }

    /**
     * A listed tool as {@code run.assign.tools} carries it: its name, description and schema, and its replay class
     * (package R, §3.1: {@code replay}, for the runtime's information; the server decides).
     */
    static RunnerMessages.ToolSpec spec(String name, String description, ToolInfo tool) {
        return new RunnerMessages.ToolSpec(name, description, tool.inputSchema(),
                RunnerMessages.ToolReplay.valueOf(tool.replay().name()));
    }

    /**
     * The tool a call names: the name the conversation listed it by, else (a name the run did not list, which the agent
     * may craft) the tool of a system the person sees whose name it would have, so that a tool the agent does not list
     * is refused for what it is, not as unknown. Empty for a name no system's tool has. In the caller's transaction.
     */
    Optional<Target> resolve(ConversationInfo conversation, String name) {
        UUID companyId = conversation.companyId();
        Optional<ToolNameStore.Target> held = names.target(conversation.id(), name);
        if (held.isPresent()) {
            return systems.find(companyId, held.get().systemId())
                .map(system -> new Target(system, held.get().tool(), served(companyId, system.id(),
                        held.get().tool())));
        }
        for (SystemInfo system : systems.visibleTo(companyId, conversation.actingPrincipalId())) {
            if (!name.startsWith(system.handle() + "_")) {
                continue;
            }
            for (ToolInfo tool : systems.tools(companyId, system.id())) {
                if (ToolNameStore.wireName(system.handle(), tool.name()).equals(name)) {
                    return Optional.of(new Target(system, tool.name(), tool));
                }
            }
        }
        return Optional.empty();
    }

    /** {@code ConversationTools.modeOf}: steps 1 to 5 for a call of the target, with the binding as it is now. */
    ToolRules.Decision decide(ConversationInfo conversation, EffectiveSettings settings, Target target, String name,
            ToolRules.BindingView binding) {
        return ToolRules.decide(target.system(), name, target.tool(), target.served(),
                conversation.actingPrincipalId(), settings, binding, texts(conversation).names(target.system()));
    }

    /** The binding as listing sees it: read only, without checking it (a call checks it, {@link #view(UUID, ConversationBindings.BindingCheck, Texts)}). */
    ToolRules.BindingView view(UUID companyId, @Nullable BindingInfo binding, Texts texts) {
        if (binding == null) {
            return ToolRules.BindingView.unbound();
        }
        return switch (binding.state()) {
            case WAITING -> ToolRules.BindingView.waiting(binding.reason() != null ? binding.reason()
                    : BindingInfo.Reason.NONE);
            case GONE -> ToolRules.BindingView.gone(binding.reason());
            case BOUND -> {
                if (binding.open()) {
                    yield ToolRules.BindingView.open();
                }
                if (binding.how() == BindingInfo.How.FIXED) {
                    yield ToolRules.BindingView.fixed(holderName(companyId, binding), fixedAccess(binding));
                }
                if (binding.grantId() == null) {
                    yield ToolRules.BindingView.own();
                }
                Optional<GrantInfo> grant = accounts.grant(companyId, binding.grantId());
                yield grant.isEmpty() ? ToolRules.BindingView.gone(BindingInfo.Reason.GRANT_REMOVED)
                        : granted(companyId, grant.get().access(), binding, texts);
            }
        };
    }

    /** The binding as a call's check found it ({@code ConversationBindings.check}). */
    ToolRules.BindingView view(UUID companyId, ConversationBindings.BindingCheck check, Texts texts) {
        BindingInfo binding = check.binding();
        return switch (check) {
            case ConversationBindings.BindingCheck.Usable usable -> binding.how() == BindingInfo.How.FIXED
                    ? ToolRules.BindingView.fixed(holderName(companyId, binding), usable.access())
                    : binding.grantId() == null
                            ? ToolRules.BindingView.own() : granted(companyId, usable.access(), binding, texts);
            case ConversationBindings.BindingCheck.Waiting waiting -> ToolRules.BindingView.waiting(
                    binding.reason() != null ? binding.reason() : BindingInfo.Reason.NONE);
            case ConversationBindings.BindingCheck.Gone gone -> ToolRules.BindingView.gone(binding.reason());
            case ConversationBindings.BindingCheck.Open open -> ToolRules.BindingView.open();
        };
    }

    /** A bound {@code FIXED} binding's access as it is lent now; the call's check reads it again. */
    private GrantInfo.Access fixedAccess(BindingInfo binding) {
        return binding.holderPrincipalId() == null || binding.accountId() == null ? GrantInfo.Access.READ
                : accounts.fixedAccess(binding.companyId(), binding.systemId(), binding.holderPrincipalId(),
                        binding.accountId());
    }

    private String holderName(UUID companyId, BindingInfo binding) {
        return binding.holderPrincipalId() != null
                ? principals.displayNames(companyId, Set.of(binding.holderPrincipalId()))
                    .getOrDefault(binding.holderPrincipalId(), "a service account")
                : "a service account";
    }

    /** A grant's binding: through the credential it lends (ADR-0072), by its name. */
    private ToolRules.BindingView granted(UUID companyId, GrantInfo.Access access, BindingInfo binding, Texts texts) {
        String holder = binding.accountId() != null
                ? accounts.find(companyId, binding.accountId()).map(AccountInfo::name).orElse("a shared credential")
                : "a shared credential";
        String group = accounts.grant(companyId, binding.grantId())
            .map(grant -> groups.groupNames(companyId, Set.of(grant.groupId())).get(grant.groupId()))
            .orElse(null);
        return ToolRules.BindingView.granted(access, holder, group != null ? group : "a group");
    }

    /** The names a conversation's texts use. */
    Texts texts(ConversationInfo conversation) {
        String agent = agents.find(conversation.companyId(), conversation.agentId()).map(AgentInfo::name)
            .orElse("the agent");
        String person = principals.displayNames(conversation.companyId(), Set.of(conversation.actingPrincipalId()))
            .getOrDefault(conversation.actingPrincipalId(), conversation.triggered() ? "the trigger's author"
                    : "the person");
        return new Texts(agent, person, conversation.triggered()
                ? triggers.name(conversation.companyId(), conversation.triggerId()) : null);
    }

    /**
     * The agent's and the person's names, for a conversation's texts; a trigger's conversation's author and trigger.
     */
    record Texts(String agent, String person, @Nullable String trigger) {

        Texts(String agent, String person) {
            this(agent, person, null);
        }

        ToolRules.Names names(SystemInfo system) {
            return new ToolRules.Names(agent, person, system.name(), trigger);
        }
    }

    /**
     * The binding as listing sees it, for the conversation: none yet on an MCP server that needs no account is
     * {@code OPEN} (ADR-0071); a trigger's has no binding where it has no row.
     */
    ToolRules.BindingView view(ConversationInfo conversation, SystemInfo system, @Nullable BindingInfo binding,
            Texts texts) {
        if (binding == null && !system.accountsRequired()) {
            return ToolRules.BindingView.open();
        }
        if (binding == null && conversation.triggered()) {
            return ToolRules.BindingView.noRow();
        }
        return view(conversation.companyId(), binding, texts);
    }

    /**
     * A call that reaches outside its channel's thread ({@link ToolRules#outsideThread}): its refusal, or null. In the
     * caller's transaction.
     *
     * @param arguments the call's arguments as {@code SystemTools.check} normalized them
     */
    ToolRules.Decision.@Nullable Refused outsideThread(ConversationInfo conversation, Target target, String name,
            JsonNode arguments, Texts texts) {
        if (conversation.place() != ConversationInfo.Place.CHAT_THREAD) {
            return null;
        }
        Map<String, String> ref = places.placeRef(conversation.id()).orElse(Map.of());
        if (!"CHANNEL".equals(ref.get("kind"))) {
            return null;
        }
        // A channel's thread whose place is incomplete matches no read: it fails closed.
        String systemId = ref.get("systemId");
        ToolRules.ThreadPlace thread = new ToolRules.ThreadPlace(systemId != null ? UUID.fromString(systemId)
                : new UUID(0, 0), Objects.requireNonNullElse(ref.get("channelId"), ""),
                Objects.requireNonNullElse(ref.get("threadId"), ""));
        return ToolRules.outsideThread(thread, target.system(), name, target.tool(), text(arguments, "channel"),
                text(arguments, "thread"), texts.names(target.system()));
    }

    private static @Nullable String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    /** The system, if it still exists. */
    @Nullable SystemInfo systemOf(UUID companyId, UUID systemId) {
        return systems.find(companyId, systemId).orElse(null);
    }

    private @Nullable ToolInfo served(UUID companyId, UUID systemId, String tool) {
        Map<String, ToolInfo> served = new HashMap<>();
        systems.tools(companyId, systemId).forEach(info -> served.putIfAbsent(info.name(), info));
        return served.get(tool);
    }
}
