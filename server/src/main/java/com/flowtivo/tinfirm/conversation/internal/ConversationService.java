package com.flowtivo.tinfirm.conversation.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.account.AccountBinding;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.agent.AgentAccess;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentEvents;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationCommands.Submitted;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.model.Hosts;
import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.model.RunModelContext;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.run.Turns;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationBinding;
import com.flowtivo.tinfirm.shared.api.model.ConversationPage;
import com.flowtivo.tinfirm.shared.api.model.ConversationStartRequest;
import com.flowtivo.tinfirm.shared.api.model.ConversationSubjectInput;
import com.flowtivo.tinfirm.shared.api.model.SubjectPage;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * Conversations through the API (the package 3 contract, §5.7, §5.10, §6): starting one, reading them, the person's
 * messages, renaming, cancelling, retrying and ending; and the end of a deleted agent's and a leaving person's
 * conversations. Every read and write first checks who sees the conversation ({@link ConversationsImpl}): its person
 * and OWNER; to anyone else it is {@code 404}. Only its person speaks, renames and binds in it
 * ({@code CONVERSATION_BOUND}, I15). OWNER's reads of another person's conversation are audited ({@link OwnerReads}).
 */
@Service
class ConversationService {

    static final String TARGET = "conversation";

    static final int MAX_TITLE = 200;

    static final int MAX_MESSAGE = 100_000;

    static final int MAX_SUBJECT_KEY = 200;

    static final int MAX_SUBJECT_TITLE = 500;

    static final int MAX_URL = 2000;

    /** The window of the limit on starts through AI clients. */
    static final Duration CLIENT_START_WINDOW = Duration.ofHours(1);

    private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9]+");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private final ConversationStore store;

    private final BindingStore bindingStore;

    private final ConversationsImpl conversations;

    private final BindingService bindings;

    private final TurnService turnService;

    private final ConversationViews views;

    private final ConversationMessagesImpl messages;

    private final DecisionReads decisions;

    private final CompanyAccessGuard companyAccess;

    private final Agents agents;

    private final Settings settings;

    private final Systems systems;

    private final Accounts accounts;

    private final ModelAccess modelAccess;

    private final Hosts hosts;

    private final RunnerDirectory runners;

    private final Turns turns;

    private final ObjectProvider<OpenDecisions> openDecisions;

    private final ObjectProvider<QuestionAnswers> questionAnswers;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final AttachmentService attachments;

    private final OwnerReads ownerReads;

    private final PlaceStore places;

    private final CompanySwitches companySwitches;

    private final SubmissionKeys keys;

    private final ConversationHolds holds;

    ConversationService(ConversationStore store, BindingStore bindingStore, ConversationsImpl conversations,
            BindingService bindings, TurnService turnService, ConversationViews views,
            ConversationMessagesImpl messages, DecisionReads decisions, CompanyAccessGuard companyAccess,
            Agents agents, Settings settings, Systems systems, Accounts accounts, ModelAccess modelAccess, Hosts hosts,
            RunnerDirectory runners, Turns turns, ObjectProvider<OpenDecisions> openDecisions,
            ObjectProvider<QuestionAnswers> questionAnswers, AuditLog auditLog, ApplicationEventPublisher events,
            TimeSource time, AttachmentService attachments, OwnerReads ownerReads, PlaceStore places,
            CompanySwitches companySwitches, SubmissionKeys keys, ConversationHolds holds) {
        this.holds = holds;
        this.companySwitches = companySwitches;
        this.store = store;
        this.bindingStore = bindingStore;
        this.conversations = conversations;
        this.bindings = bindings;
        this.turnService = turnService;
        this.views = views;
        this.messages = messages;
        this.decisions = decisions;
        this.companyAccess = companyAccess;
        this.agents = agents;
        this.settings = settings;
        this.systems = systems;
        this.accounts = accounts;
        this.modelAccess = modelAccess;
        this.hosts = hosts;
        this.runners = runners;
        this.turns = turns;
        this.openDecisions = openDecisions;
        this.questionAnswers = questionAnswers;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
        this.attachments = attachments;
        this.ownerReads = ownerReads;
        this.places = places;
        this.keys = keys;
    }

    // ---- start -----------------------------------------------------------------------------------------------------

    /**
     * {@code POST /conversations}: the caller starts a conversation with an agent they may use, and is its person. It
     * refuses what can never run (§5.7): {@code MODEL_ACCESS_UNAVAILABLE} and {@code NO_HOST_FOR_CONVERSATION}. A start
     * through a person's AI client has the origin {@code CLIENT} and counts against {@code clientStartsPerHour} (the
     * package 6 contract, open point 14; 0: no limit). With an idempotency key (package R, §3.5), the same request
     * again answers the conversation it started, as it is now, and starts nothing.
     */
    @Transactional
    public Submitted<Conversation> start(UUID companyId, ConversationStartRequest request,
            ConversationInfo.Origin origin, int clientStartsPerHour, @Nullable String idempotencyKey) {
        return start(companyId, request, origin, clientStartsPerHour, idempotencyKey, List.of());
    }

    /**
     * As {@link #start(UUID, ConversationStartRequest, ConversationInfo.Origin, int, String)}, with the caller's held
     * files (ADR-0084), moved into the conversation before its first turn.
     */
    @Transactional
    public Submitted<Conversation> start(UUID companyId, ConversationStartRequest request,
            ConversationInfo.Origin origin, int clientStartsPerHour, @Nullable String idempotencyKey,
            List<UUID> stagedFileIds) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        ConversationsImpl.Caller caller = conversations.current(access);
        UUID person = caller.requirePrincipal();
        // The key before anything is counted, locked or made (CONVENTIONS §10, §15.14). A replay answers what the first
        // request started, a stop of the company's runs since or not; a request refused below (startFor checks the stop
        // again) rolls back, and stores no key.
        SubmissionKeys.Claim claim = keys.claim(companyId, person, idempotencyKey,
                SubmissionKeys.Operation.startConversation, SubmissionKeys.requestSha256("POST",
                        "/api/companies/" + companyId + "/conversations", request));
        if (claim instanceof SubmissionKeys.Claim.Replay replay) {
            return new Submitted<>(views.conversation(store.find(companyId, replay.resourceId())
                .orElseThrow(ConversationService::notFound)), true);
        }
        requireRunsNotStopped(companyId);
        if (origin == ConversationInfo.Origin.CLIENT && clientStartsPerHour > 0) {
            refuseOverTheClientLimit(companyId, person, clientStartsPerHour);
        }
        String message = request.getMessage() != null ? request.getMessage().strip() : "";
        StartedTurn started = startFor(new StartInput(companyId, person, caller.userId(), request.getAgentId(),
                message, null, null, request.getSubject(), request.getRepositoryIds(), request.getAttachmentIds(),
                stagedFileIds, ConversationInfo.Place.TINFIRM_THREAD, origin, null, Map.of()));
        keys.made(claim, SubmissionKeys.CONVERSATION, started.conversationId());
        return new Submitted<>(views.conversation(store.find(companyId, started.conversationId()).orElseThrow()),
                false);
    }

    /**
     * What the caller's start under the idempotency key made, as it is now, when a first request with it was
     * answered; empty otherwise. A read for a client that does work before its start (holds the email's attachments,
     * ADR-0084): a start sent again then holds nothing twice. It claims nothing: the start's own claim decides.
     */
    @Transactional(readOnly = true)
    public Optional<Conversation> started(UUID companyId, String idempotencyKey) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        UUID person = conversations.current(access).requirePrincipal();
        return keys.find(companyId, person, idempotencyKey, SubmissionKeys.Operation.startConversation)
            .flatMap(conversationId -> store.find(companyId, conversationId))
            .map(views::conversation);
    }

    /**
     * What a start takes: the person, their user (who uses the agent), the first message and its chat source, the
     * subject, repositories and files of the app's start, the files a client held for it (ADR-0084), the place and its
     * reference, and the audit's place details.
     */
    record StartInput(UUID companyId, UUID personPrincipalId, UUID personUserId, UUID agentId, String message,
            @Nullable String externalId, @Nullable Map<String, Object> externalAuthor,
            @Nullable ConversationSubjectInput subject, @Nullable List<UUID> repositoryIds,
            @Nullable List<UUID> attachmentIds, List<UUID> stagedFileIds, ConversationInfo.Place place,
            ConversationInfo.Origin origin, @Nullable Map<String, String> placeRef, Map<String, Object> auditDetails) {
    }

    /** A started conversation and its first turn's run, when one started. */
    record StartedTurn(UUID conversationId, String key, @Nullable UUID runId) {
    }

    /** G4: no conversation starts while the company's runs are stopped ({@code 409 COMPANY_STOPPED}). */
    private void requireRunsNotStopped(UUID companyId) {
        if (companySwitches.runsStopped(companyId).isPresent()) {
            throw ApiException.conflict(ProblemCode.COMPANY_STOPPED, "The company's runs are stopped by an admin: no "
                    + "conversation starts until the stop is lifted");
        }
    }

    /**
     * The start of a personal conversation (§5.7), the app's and a direct message's ({@code PersonalConversations},
     * the package 5 contract §5.6), in the caller's transaction: the person uses the agent; nothing is stored for what
     * can never run, nor while the company's runs are stopped (the package 6 contract, §5.4, G4: from the app, a
     * direct message or the endpoint alike). Throws the app's problems.
     */
    StartedTurn startFor(StartInput input) {
        UUID companyId = input.companyId();
        requireRunsNotStopped(companyId);
        UUID person = input.personPrincipalId();
        AgentInfo agent = agents.find(companyId, input.agentId()).filter(AgentInfo::live)
            .orElseThrow(() -> ApiException.notFound("Agent not found"));
        AgentAccess agentAccess = agents.access(companyId, agent.id(), input.personUserId());
        if (!agentAccess.sees()) {
            throw ApiException.notFound("Agent not found");
        }
        if (!agentAccess.uses()) {
            throw ApiException.forbidden(ProblemCode.AGENT_NOT_USABLE, "You may not use the agent " + agent.name()
                    + ": it is shared with groups you are not in");
        }
        String message = input.message();
        if (message.isEmpty() || message.length() > MAX_MESSAGE) {
            throw ApiException.validation("message must have 1 to " + MAX_MESSAGE + " characters");
        }
        ConversationInfo.Subject subject = subject(companyId, input.subject());

        EffectiveSettings effective = settings.firstUse(companyId, agent.id(), person);
        ModelAccessChoice choice = effective.modelAccess().choice()
            .orElseThrow(() -> unavailable("SUBSCRIPTION_GONE", "The personal runner host of the subscription your "
                    + "settings name was removed: choose a model access in your settings of " + agent.name()));
        List<ConversationInfo.WorkspaceEntry> workspace = workspace(effective, input.repositoryIds());
        refuseWhatCannotRun(companyId, person, choice, effective, workspace);

        Instant now = time.now();
        UUID id = Ids.newId();
        String key = key(companyId, subject, agent.handle());
        String label = choice instanceof ModelAccessChoice.Subscription subscription ? subscription.label() : null;
        UUID subscriptionHost = choice instanceof ModelAccessChoice.Subscription subscription
                ? subscription.runnerHostId() : null;
        ConversationInfo conversation = new ConversationInfo(id, companyId, agent.id(), key, title(message),
                input.place(), input.origin(), person, subject, effective.runtime(), person, choice, label, workspace,
                List.of(), ConversationInfo.State.OPEN, null, null, null, ConversationInfo.WaitingOn.NONE, null, null,
                0, null, null, null, now, now);
        store.insert(conversation, subscriptionHost, input.personUserId(),
                input.placeRef() != null ? ConversationJson.write(input.placeRef()) : null);
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.CONVERSATION_STARTED,
                CurrentActor.actor())
            .target(TARGET, id)
            .detail("agentId", agent.id().toString())
            .detail("personPrincipalId", person.toString())
            .detail("key", key)
            .detail("place", conversation.place().name())
            .detail("origin", conversation.origin().name())
            .detail("subjectSystemId", subject != null && subject.systemId() != null ? subject.systemId().toString()
                    : null)
            .detail("subjectKey", subject != null ? subject.key() : null)
            .detail("payerPrincipalId", person.toString())
            .detail("modelAccess", label != null ? "SUBSCRIPTION" : "BUDGET")
            .detail("runtime", effective.runtime().name());
        input.auditDetails().forEach(audit::detail);
        auditLog.record(audit.build());
        attachments.carryInto(companyId, id, input.attachmentIds(), person, input.personUserId());
        attachments.carryStaged(companyId, id, input.stagedFileIds(), person, input.personUserId());
        if (input.externalId() != null && input.externalAuthor() != null) {
            places.insertMessage(companyId, id, "PERSON", person, message, false, input.externalId(),
                    input.externalAuthor());
        }
        else {
            messages.person(companyId, id, person, message, false);
        }
        Optional<UUID> runId = turnService.request(companyId, id, TurnReason.FIRST);
        events.publishEvent(new ConversationEvents.ConversationStarted(companyId, id, agent.id(), person));
        return new StartedTurn(id, key, runId.orElse(null));
    }

    /**
     * The hourly limit of a member's starts through AI clients (open point 14): counted from the company's
     * {@code CLIENT} conversations of the past hour under a transaction's lock per member, so that two replicas starting
     * for one member at once count each other. Over it, {@code 429 RATE_LIMITED} with the wait until the oldest of
     * those starts leaves the hour.
     */
    private void refuseOverTheClientLimit(UUID companyId, UUID person, int perHour) {
        store.lockClientStarts(person);
        Instant now = time.now();
        List<Instant> recent = store.clientStartsSince(companyId, person, now.minus(CLIENT_START_WINDOW), perHour);
        if (recent.size() >= perHour) {
            Duration wait = Duration.between(now, recent.getLast().plus(CLIENT_START_WINDOW));
            throw ApiException.rateLimited(wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait,
                    "You started " + perHour + " conversations through AI clients in the past hour, the most allowed: "
                            + "wait, or start this one in Tinfirm");
        }
    }

    /** The subject: a company system with issues or change requests, its key, an http(s) link and a title. */
    private ConversationInfo.@Nullable Subject subject(UUID companyId, @Nullable ConversationSubjectInput input) {
        if (input == null) {
            return null;
        }
        SystemInfo system = systems.find(companyId, input.getSystemId())
            .filter(found -> !found.personal() && ConversationViews.subjectKind(found.kind()))
            .orElseThrow(() -> ApiException.validation("subject.systemId is not a company system with issues or "
                    + "change requests"));
        String key = input.getKey() != null ? input.getKey().strip() : "";
        if (key.isEmpty() || key.length() > MAX_SUBJECT_KEY) {
            throw ApiException.validation("subject.key must have 1 to " + MAX_SUBJECT_KEY + " characters");
        }
        String url = input.getUrl() != null && !input.getUrl().isBlank() ? input.getUrl().strip() : null;
        if (url != null && !link(url)) {
            throw ApiException.validation("subject.url must be an http(s) link without user info, at most "
                    + MAX_URL + " characters");
        }
        String title = input.getTitle() != null && !input.getTitle().isBlank() ? input.getTitle().strip() : null;
        if (title != null && title.length() > MAX_SUBJECT_TITLE) {
            throw ApiException.validation("subject.title has at most " + MAX_SUBJECT_TITLE + " characters");
        }
        return new ConversationInfo.Subject(system.id(), key, url, title);
    }

    static boolean link(String url) {
        if (url.length() > MAX_URL || url.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getRawAuthority() != null && uri.getRawUserInfo() == null && !uri.getRawAuthority()
                        .contains("@");
        }
        catch (URISyntaxException ex) {
            return false;
        }
    }

    /**
     * The start's workspace (§5.6): the named repositories, each one of the agent's, and none for an empty list;
     * without a list, the settings' defaults, else every repository of the agent; more than 20 without a list is
     * {@code TOO_MANY_REPOSITORIES}.
     */
    private List<ConversationInfo.WorkspaceEntry> workspace(EffectiveSettings effective,
            @Nullable List<UUID> repositoryIds) {
        List<AgentDefinition.RepositoryEntry> chosen = new ArrayList<>();
        if (repositoryIds != null) {
            for (UUID repositoryId : new LinkedHashSet<>(repositoryIds)) {
                chosen.add(effective.repositories().stream()
                    .filter(entry -> entry.repositoryId().equals(repositoryId))
                    .findFirst()
                    .orElseThrow(() -> ApiException.validation("repositoryIds names a repository the agent does not "
                            + "list: " + repositoryId)));
            }
            if (chosen.size() > ConversationInfo.MAX_WORKSPACE) {
                throw ApiException.validation("A workspace holds at most " + ConversationInfo.MAX_WORKSPACE
                        + " repositories");
            }
        }
        else {
            chosen.addAll(effective.startingRepositories());
            if (chosen.size() > ConversationInfo.MAX_WORKSPACE) {
                throw ApiException.conflict(ProblemCode.TOO_MANY_REPOSITORIES, "The agent lists " + chosen.size()
                        + " repositories, more than the " + ConversationInfo.MAX_WORKSPACE + " a workspace holds: "
                        + "choose them with repositoryIds, or set default repositories in your settings");
            }
        }
        return chosen.stream()
            .map(entry -> new ConversationInfo.WorkspaceEntry(entry.repositoryId(),
                    entry.access() == RepositoryAccess.READ, ConversationInfo.WorkspaceEntry.Source.AGENT))
            .toList();
    }

    /**
     * {@code POST /conversations} refuses what can never run (§5.7): no gateway, an inactive payer, a subscription
     * gone, or a budget's settings that name no model or one its key does not allow ({@code MODEL_ACCESS_UNAVAILABLE});
     * no registered host, connected or not, that may run its credentials by package 2's rule
     * ({@code NO_HOST_FOR_CONVERSATION}). The workspace's accounts are those its code hosts would bind now. A spent
     * budget or an offline host does not refuse: the first turn waits.
     */
    private void refuseWhatCannotRun(UUID companyId, UUID person, ModelAccessChoice choice,
            EffectiveSettings effective, List<ConversationInfo.WorkspaceEntry> workspace) {
        Set<UUID> systemsOfWorkspace = new LinkedHashSet<>();
        for (AgentDefinition.RepositoryEntry entry : effective.repositories()) {
            if (workspace.stream().anyMatch(item -> item.repositoryId().equals(entry.repositoryId()))) {
                systemsOfWorkspace.add(entry.systemId());
            }
        }
        Set<UUID> holders = new HashSet<>();
        for (UUID systemId : systemsOfWorkspace) {
            switch (accounts.bind(new AccountBinding.Personal(companyId, person), systemId)) {
                case AccountBinding.Own own -> holders.add(own.account().principalId());
                // A credential is no one's: its id stands for it, which is no host's owner (M4).
                case AccountBinding.Granted granted -> holders.add(granted.account().id());
                default -> {
                }
            }
        }
        RunModelContext context = new RunModelContext(companyId, person, choice, effective.runtime(), holders);
        RunModelAccess payment = modelAccess.forRun(context);
        if (payment instanceof RunModelAccess.Budget budget) {
            RunModelAccess.Refuse refuse = budget.refusal(effective.model());
            if (refuse != null) {
                throw unavailable(refuse.reason().name(), refuse.detail());
            }
        }
        if (payment instanceof RunModelAccess.Refuse refuse) {
            if (refuse.reason() == RunModelAccess.RefuseReason.CANNOT_RUN_ANYWHERE) {
                throw noHost("CANNOT_RUN_ANYWHERE", refuse.detail());
            }
            throw unavailable(refuse.reason().name(), refuse.detail());
        }
        switch (choice) {
            case ModelAccessChoice.Subscription subscription -> {
                switch (hosts.eligible(context)) {
                    case Hosts.Eligibility.Nowhere nowhere -> throw noHost("CANNOT_RUN_ANYWHERE",
                            nowhere.refusal().detail());
                    case Hosts.Eligibility.Eligible eligible -> {
                        if (eligible.hosts().isEmpty()) {
                            throw noHost("NO_RUNNER_HOST", eligible.whyNone() != null ? eligible.whyNone()
                                    : "No runner host can run this conversation");
                        }
                    }
                }
            }
            case ModelAccessChoice.Budget budget -> {
                List<RunnerHostInfo> serving = runners.hosts(companyId).stream()
                    .filter(host -> host.serves(effective.runtime()))
                    .toList();
                boolean ownWorkspace = context.workspaceAllOf(person);
                boolean may = serving.stream()
                    .anyMatch(host -> host.company() || ownWorkspace && host.personalOf(person));
                if (!may) {
                    throw serving.isEmpty()
                            ? noHost("NO_RUNNER_HOST", "No runner host of the company, and none of yours, can run "
                                    + effective.runtime())
                            : noHost("NO_HOST_FOR_CREDENTIALS", "No runner host may run this conversation's accounts: "
                                    + "a personal host runs only its owner's own accounts, and the company has no "
                                    + "company host for " + effective.runtime());
                }
            }
        }
    }

    private static ApiException unavailable(String reason, String detail) {
        return ApiException.conflict(ProblemCode.MODEL_ACCESS_UNAVAILABLE, detail,
                Map.of("details", Map.of("reason", reason)));
    }

    private static ApiException noHost(String reason, String detail) {
        return ApiException.conflict(ProblemCode.NO_HOST_FOR_CONVERSATION, detail,
                Map.of("details", Map.of("reason", reason)));
    }

    /** The first line of the first message, at most 200 characters. */
    static String title(String message) {
        String line = message.lines().map(String::strip).filter(text -> !text.isEmpty()).findFirst().orElse("");
        line = line.replaceFirst("^#+\\s*", "").strip();
        if (line.isEmpty()) {
            line = "Conversation";
        }
        return ConversationStore.cut(line, MAX_TITLE);
    }

    /** {@code {base}-{n}}: the subject's key as a slug and the agent's handle, or the handle alone (§3.8). */
    String key(UUID companyId, ConversationInfo.@Nullable Subject subject, String handle) {
        String base = handle;
        if (subject != null) {
            String slug = slug(subject.key());
            if (!slug.isEmpty()) {
                base = slug + "-" + handle;
            }
        }
        if (base.length() > ConversationInfo.MAX_KEY_BASE_LENGTH) {
            base = trimHyphens(base.substring(0, ConversationInfo.MAX_KEY_BASE_LENGTH));
        }
        return base + "-" + store.nextKeyNumber(companyId, base);
    }

    static String slug(String text) {
        String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD)).replaceAll("");
        return trimHyphens(NOT_SLUG.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll("-"));
    }

    private static String trimHyphens(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '-') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '-') {
            end--;
        }
        return text.substring(start, end);
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    /**
     * {@code GET /conversations}. In the app, OWNER lists anyone's with {@code personId} (everyone's without), anyone
     * else their own; through a client, everyone their own (G9, open point 11).
     */
    @Transactional(readOnly = true)
    public ConversationPage list(UUID companyId, ConversationView view, @Nullable UUID agentId,
            @Nullable UUID subjectSystemId, @Nullable String subjectKey, ConversationInfo.@Nullable State state,
            ConversationInfo.@Nullable WaitingOn waitingOn, @Nullable UUID personId, @Nullable UUID triggerId,
            @Nullable Integer limit, @Nullable String cursor) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        ConversationsImpl.Caller caller = conversations.current(access);
        boolean everyone = caller.owner() && view == ConversationView.APP;
        ConversationStore.Scope scope = scope(caller, view);
        if (scope.none()) {
            return new ConversationPage(new ArrayList<>());
        }
        PageRequest request = PageRequest.of(limit, cursor);
        ConversationStore.Filter filter = new ConversationStore.Filter(scope, everyone ? personId : null, triggerId,
                agentId, subjectSystemId, subjectKey, state, waitingOn);
        List<ConversationStore.ConversationRow> rows = request.firstPage()
                ? store.page(companyId, filter, null, null, request.fetchSize())
                : store.page(companyId, filter, request.instantKey(0, 2), request.uuidKey(1, 2), request.fetchSize());
        if (agentId != null && rows.isEmpty() && request.firstPage()
                && !agents.access(companyId, agentId, caller.userId()).sees()) {
            // An agent the caller does not see, and of none of their conversations: 404, as another company's agent
            // and an unknown id are (CONVENTIONS §6). One they see, or of their conversations (a deleted agent's),
            // lists what there is.
            throw ApiException.notFound("Agent not found");
        }
        Page<ConversationStore.ConversationRow> page = Page.of(rows, request,
                row -> List.of(row.info().updatedAt().toString(), row.id().toString()));
        // A trigger's conversation has no person: nobody's conversations are listed by it (§5.13).
        ownerReads.listed(companyId, caller, page.items().stream().map(row -> row.info().personPrincipalId())
            .filter(Objects::nonNull).toList());
        return new ConversationPage(new ArrayList<>(views.summaries(companyId, page.items())))
            .nextCursor(page.nextCursor());
    }

    /**
     * {@code GET /subjects}: OWNER's counts every conversation, but a subject shows its system's issue and counts, and
     * nothing of any conversation or person, as the inbox's accounts do; not audited as a read ({@link OwnerReads}).
     */
    @Transactional(readOnly = true)
    public SubjectPage subjects(UUID companyId, ConversationView view, @Nullable UUID systemId,
            @Nullable Integer limit, @Nullable String cursor) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        ConversationsImpl.Caller caller = conversations.current(access);
        ConversationStore.Scope scope = scope(caller, view);
        if (scope.none()) {
            return new SubjectPage(new ArrayList<>());
        }
        PageRequest request = PageRequest.of(limit, cursor);
        List<ConversationStore.SubjectRow> rows = request.firstPage()
                ? store.subjects(companyId, scope, systemId, null, null, request.fetchSize())
                : store.subjects(companyId, scope, systemId, request.key(0, 2), request.uuidKey(1, 2),
                        request.fetchSize());
        Page<ConversationStore.SubjectRow> page = Page.of(rows, request, row -> List.of(row.key(),
                (row.systemId() != null ? row.systemId() : new UUID(0, 0)).toString()));
        return new SubjectPage(new ArrayList<>(views.subjects(companyId, page.items()))).nextCursor(page.nextCursor());
    }

    @Transactional(readOnly = true)
    public Conversation get(UUID companyId, ConversationView view, UUID conversationId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        return views.conversation(readable(access, conversationId, view));
    }

    /** A conversation by its key, as {@link #get} reads it by id; a key nobody sees is {@code 404}. */
    @Transactional(readOnly = true)
    public Conversation getByKey(UUID companyId, ConversationView view, String key) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        UUID id = store.idOfKey(companyId, key.strip()).orElseThrow(ConversationService::notFound);
        return views.conversation(readable(access, id, view));
    }

    // ---- the person's acts -----------------------------------------------------------------------------------------

    @Transactional
    public Conversation rename(UUID companyId, ConversationView view, UUID conversationId, String title) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        ConversationStore.ConversationRow row = person(access, conversationId, view);
        String text = title != null ? title.strip() : "";
        if (text.isEmpty() || text.length() > MAX_TITLE) {
            throw ApiException.validation("title must have 1 to " + MAX_TITLE + " characters");
        }
        store.lock(companyId, conversationId);
        if (!text.equals(row.info().title())) {
            store.rename(companyId, conversationId, text, time.now());
        }
        return views.conversation(store.find(companyId, conversationId).orElseThrow());
    }

    /**
     * A person's message (§5.7): it waits for the next turn; it starts one when no turn is active and no decision open.
     * While a turn runs, a message that answered no question goes to it (steering): the agent takes it after its
     * current step when its runner host and runtime can, else it waits for the next turn.
     * With {@code answersOpenQuestions} it answers every open question first (part 2), and the turn is {@code DECIDED}.
     * With an idempotency key (package R, §3.5), the same request again answers the conversation as it is now, and
     * posts nothing.
     */
    @Transactional
    public Submitted<Conversation> message(UUID companyId, ConversationView view, UUID conversationId, String body,
            boolean answersOpenQuestions, @Nullable String idempotencyKey) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        ConversationStore.ConversationRow found = person(access, conversationId, view);
        // The key is the caller's: a personal conversation's person, a trigger's author or a member of its team.
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("body", body);
        sent.put("answersOpenQuestions", answersOpenQuestions);
        SubmissionKeys.Claim claim = keys.claim(companyId, conversations.current(access).principalId(),
                idempotencyKey, SubmissionKeys.Operation.postConversationMessage, SubmissionKeys.requestSha256("POST",
                        "/api/companies/" + companyId + "/conversations/" + conversationId + "/messages", sent));
        if (claim instanceof SubmissionKeys.Claim.Replay) {
            return new Submitted<>(views.conversation(store.find(companyId, conversationId).orElseThrow()), true);
        }
        String text = body != null ? body.strip() : "";
        if (text.isEmpty() || text.length() > MAX_MESSAGE) {
            throw ApiException.validation("body must have 1 to " + MAX_MESSAGE + " characters");
        }
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        requireOpen(conversation);
        requireAgentUsable(conversation);
        // A trigger's conversation: its author or a member of its team speaks (T12); on a tracker's issue or in a chat's
        // thread only to answer the agent's open questions, since its place is the issue or the thread (the package 4
        // contract, §5.8; the package 5 contract, §6). A webhook's has no place outside Tinfirm: any message.
        UUID author = conversation.triggered() ? conversations.current(access).requirePrincipal()
                : Objects.requireNonNull(conversation.personPrincipalId());
        boolean external = conversation.place() == ConversationInfo.Place.TRACKER_ISSUE
                || conversation.place() == ConversationInfo.Place.CHAT_THREAD;
        if (external && (!answersOpenQuestions
                || decisions.open(companyId, conversationId).stream().noneMatch(open -> "QUESTION".equals(open.kind())))) {
            throw ApiException.conflict(ProblemCode.PLACE_IS_EXTERNAL, conversation.place()
                    == ConversationInfo.Place.CHAT_THREAD ? "This conversation's place is its chat thread: write "
                            + "there, or answer the agent's open questions here"
                            : "This conversation's place is its issue: comment there, or answer the agent's open "
                                    + "questions here");
        }
        UUID messageId = messages.person(companyId, conversationId, author, text, answersOpenQuestions);
        int answered = answersOpenQuestions ? questionAnswers.getIfAvailable(() -> QuestionAnswers.NONE)
            .answerAll(conversation, messageId, text, author) : 0;
        if (decisions.open(companyId, conversationId).isEmpty()) {
            turnService.request(companyId, conversationId, answered > 0 ? TurnReason.DECIDED : TurnReason.MESSAGE);
        }
        if (answered == 0) {
            // A turn runs, so none started: the message goes to it when it can (# Answers tells answers instead).
            turnService.steer(conversation, messageId, author, text);
        }
        turnService.refreshWaiting(companyId, conversationId);
        keys.made(claim, SubmissionKeys.MESSAGE, messageId);
        return new Submitted<>(views.conversation(store.find(companyId, found.id()).orElseThrow()), false);
    }

    /**
     * Cancel (§5.7, the person or OWNER): the active run (a queued one at once, a running one through its host), the
     * open decisions and the parked calls; waiting messages stay unanswered; the conversation stays open, its turn
     * {@code CANCELLED}.
     */
    @Transactional
    public Conversation cancel(UUID companyId, ConversationView view, UUID conversationId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        ConversationStore.ConversationRow found = steward(access, conversationId, view);
        requireOpen(found.info());
        // The conversation first, then its run (CONVENTIONS §10), as every run's end locks them: the run module ends a
        // queued run in this transaction and tells TurnEnded.
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        Optional<UUID> cancelled = turns.cancelActive(companyId, conversationId);
        store.dropWaiting(companyId, conversationId);
        cancelOpen(companyId, conversationId, "CANCELLED");
        if (cancelled.isEmpty() && conversation.activeRunId() == null
                && conversation.turnStatus() != ConversationInfo.TurnStatus.FAILED) {
            store.updateWaiting(companyId, conversationId, ConversationInfo.TurnStatus.CANCELLED,
                    ConversationInfo.WaitingOn.NONE, null, time.now());
        }
        turnService.refreshWaiting(companyId, conversationId);
        return views.conversation(store.find(companyId, conversationId).orElseThrow());
    }

    /**
     * Retry (§5.7, the person or OWNER): the last turn's request again, when it failed or was cancelled. With an
     * idempotency key (package R, §3.5), the same request again answers the conversation as it is now, and starts no
     * turn.
     */
    @Transactional
    public Submitted<Conversation> retry(UUID companyId, ConversationView view, UUID conversationId,
            @Nullable String idempotencyKey) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        steward(access, conversationId, view);
        SubmissionKeys.Claim claim = keys.claim(companyId, conversations.current(access).principalId(),
                idempotencyKey, SubmissionKeys.Operation.retryConversation, SubmissionKeys.requestSha256("POST",
                        "/api/companies/" + companyId + "/conversations/" + conversationId + "/retry", null));
        if (claim instanceof SubmissionKeys.Claim.Replay) {
            return new Submitted<>(views.conversation(store.find(companyId, conversationId).orElseThrow()), true);
        }
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        requireOpen(conversation);
        requireAgentUsable(conversation);
        if (conversation.activeRunId() != null) {
            throw ApiException.conflict(ProblemCode.TURN_ACTIVE, "The conversation has a queued or running turn");
        }
        if (conversation.turnStatus() != ConversationInfo.TurnStatus.FAILED
                && conversation.turnStatus() != ConversationInfo.TurnStatus.CANCELLED) {
            throw ApiException.conflict(ProblemCode.NOTHING_TO_RETRY, "Only a failed or cancelled turn is retried");
        }
        turnService.request(companyId, conversationId, TurnReason.RETRY);
        keys.made(claim, SubmissionKeys.CONVERSATION, conversationId);
        return new Submitted<>(views.conversation(store.find(companyId, conversationId).orElseThrow()), false);
    }

    /**
     * End (§5.7, the person or OWNER), idempotent: waiting messages stay unanswered; a running turn finishes and its
     * result is recorded; open decisions and parked calls are cancelled. Audited as {@code conversation.ended}.
     */
    @Transactional
    public Conversation end(UUID companyId, ConversationView view, UUID conversationId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        ConversationStore.ConversationRow found = steward(access, conversationId, view);
        if (found.info().open()) {
            end(store.lock(companyId, conversationId).orElseThrow().info(), ConversationInfo.EndedReason.USER,
                    CurrentActor.requireUser().id());
        }
        return views.conversation(store.find(companyId, conversationId).orElseThrow());
    }

    /** {@code …/bindings/{systemId}/choose}: the person picks a grant, or reconnects their own account. */
    @Transactional
    public ConversationBinding choose(UUID companyId, ConversationView view, UUID conversationId, UUID systemId,
            @Nullable UUID grantId, boolean reconnect) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        ConversationStore.ConversationRow found = person(access, conversationId, view);
        if ((grantId == null) == !reconnect) {
            throw ApiException.validation("Name either grantId or reconnect");
        }
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        requireOpen(conversation);
        BindingInfo binding = bindings.choose(conversation, systemId, grantId, reconnect,
                CurrentActor.requireUser().id());
        return views.binding(binding);
    }

    /** {@code …/bindings/{systemId}/continue}: a waiting binding resolved again. */
    @Transactional
    public ConversationBinding continueBinding(UUID companyId, ConversationView view, UUID conversationId,
            UUID systemId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        person(access, conversationId, view);
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        requireOpen(conversation);
        return views.binding(bindings.continueWaiting(conversation, systemId));
    }

    // ---- the ends that follow other modules ------------------------------------------------------------------------

    /**
     * The agent was deleted (in its transaction): its conversations end ({@code AGENT_DELETED}, §5.1), locked in the
     * order of their ids (CONVENTIONS §10).
     */
    @EventListener
    void agentDeleted(AgentEvents.AgentDeleted event) {
        for (ConversationStore.ConversationRow row : inLockOrder(store.openOfAgent(event.companyId(),
                event.agentId()))) {
            end(store.lock(event.companyId(), row.id()).orElseThrow().info(), ConversationInfo.EndedReason.AGENT_DELETED,
                    null);
        }
    }

    /**
     * The person left (in the leaving transaction): their conversations end ({@code PERSON_LEFT}), their running turns
     * are cancelled, and the conversations stay readable to OWNER (§5.7). Each conversation is locked before its run,
     * the conversations in the order of their ids (CONVENTIONS §10).
     */
    @EventListener
    void personLeft(PrincipalEvents.PrincipalLeft event) {
        for (ConversationStore.ConversationRow row : inLockOrder(store.openOfPerson(event.companyId(),
                event.principalId()))) {
            store.lock(event.companyId(), row.id());
            turns.cancelActive(event.companyId(), row.id());
            end(store.lock(event.companyId(), row.id()).orElseThrow().info(), ConversationInfo.EndedReason.PERSON_LEFT,
                    null);
        }
    }

    /** Conversations in the order a transaction that locks several of them takes them: by id (CONVENTIONS §10). */
    private static List<ConversationStore.ConversationRow> inLockOrder(List<ConversationStore.ConversationRow> rows) {
        return rows.stream().sorted(Comparator.comparing(ConversationStore.ConversationRow::id)).toList();
    }

    /** Ends a locked, open conversation. */
    void end(ConversationInfo conversation, ConversationInfo.EndedReason reason, @Nullable UUID endedBy) {
        if (!conversation.open()) {
            return;
        }
        UUID companyId = conversation.companyId();
        store.end(companyId, conversation.id(), reason, endedBy, time.now());
        store.dropWaiting(companyId, conversation.id());
        cancelOpen(companyId, conversation.id(), reason == ConversationInfo.EndedReason.AGENT_DELETED
                || reason == ConversationInfo.EndedReason.PERSON_LEFT ? reason.name() : "ENDED");
        auditLog.record(AuditRecord.company(companyId, AuditAction.CONVERSATION_ENDED, CurrentActor.actor())
            .target(TARGET, conversation.id())
            .detail("cause", reason.name())
            .detail("turns", conversation.turnCount())
            .build());
        events.publishEvent(new ConversationEvents.ConversationEnded(companyId, conversation.id(), reason));
        turnService.refreshWaiting(companyId, conversation.id());
    }

    /** The open decisions and the parked calls: part 2's, else the decisions here (unaudited). */
    private void cancelOpen(UUID companyId, UUID conversationId, String cause) {
        OpenDecisions open = openDecisions.getIfAvailable();
        if (open != null) {
            open.cancelAll(companyId, conversationId, cause);
            return;
        }
        decisions.cancelOpen(companyId, conversationId, cause);
        bindingStore.dropParkedCalls(companyId, conversationId, time.now());
    }

    // ---- who ------------------------------------------------------------------------------------------------------

    /**
     * What the caller sees in lists in the view (§5.10; the package 4 contract, §5.13). In the app: their own
     * conversations, every personal one as OWNER, and the conversations of the triggers whose audience they are in
     * and of those they started as their author. Through a client, as {@link ConversationsImpl.Caller#sees(
     * ConversationInfo, ConversationView)} reads one: their own personal conversations, and the conversations of the
     * triggers they act on as their people, as author or in their team (T12; the package 6 contract, open point 15).
     */
    static ConversationStore.Scope scope(ConversationsImpl.Caller caller, ConversationView view) {
        if (view == ConversationView.CLIENT) {
            return new ConversationStore.Scope(false, caller.principalId(), caller.acting().all(),
                    caller.acting().triggerIds(), false);
        }
        return new ConversationStore.Scope(caller.owner(), caller.principalId(), caller.triggers().all(),
                caller.triggers().triggerIds(), true);
    }

    /**
     * The conversation, when the caller sees it in the view, for a request that answers it in full: OWNER's read of
     * another person's conversation is on record ({@link OwnerReads}), before the transaction locks anything; 404
     * otherwise (I17, G9). Through a client the caller is always its person, so nothing is recorded there.
     */
    private ConversationStore.ConversationRow readable(CompanyAccess access, UUID conversationId,
            ConversationView view) {
        ConversationsImpl.Caller caller = conversations.current(access);
        ConversationStore.ConversationRow row = visible(access, caller, conversationId, view);
        ownerReads.read(access.companyId(), caller, row.info(), OwnerReads.What.CONVERSATION);
        return row;
    }

    /** The conversation, when the caller sees it in the view; 404 otherwise (I17, G9). */
    private ConversationStore.ConversationRow visible(CompanyAccess access, ConversationsImpl.Caller caller,
            UUID conversationId, ConversationView view) {
        ConversationStore.ConversationRow row = store.find(access.companyId(), conversationId)
            .orElseThrow(ConversationService::notFound);
        if (!caller.sees(row.info(), view)) {
            throw notFound();
        }
        return row;
    }

    /**
     * The conversation, when the caller cancels, retries and ends it: whoever sees a personal one (its person, OWNER);
     * a trigger's audience for a trigger's (its author, its team, OWNER and ADMIN, the package 4 contract, §5.7), not
     * the author it started with once that is no longer its trigger's ({@code CONVERSATION_BOUND}).
     */
    private ConversationStore.ConversationRow steward(CompanyAccess access, UUID conversationId,
            ConversationView view) {
        ConversationsImpl.Caller caller = conversations.current(access);
        ConversationStore.ConversationRow row = readable(access, conversationId, view);
        if (row.info().triggered() && !caller.triggers().sees(row.info().triggerId())) {
            throw ApiException.conflict(ProblemCode.CONVERSATION_BOUND, "Only the trigger's author, its team and "
                    + "admins act on its conversations");
        }
        return row;
    }

    /** The conversation, when the caller is its person; OWNER, who sees it, is {@code CONVERSATION_BOUND} (I15). */
    private ConversationStore.ConversationRow person(CompanyAccess access, UUID conversationId,
            ConversationView view) {
        ConversationsImpl.Caller caller = conversations.current(access);
        ConversationStore.ConversationRow row = visible(access, caller, conversationId, view);
        if (!caller.isPerson(row.info())) {
            throw ApiException.conflict(ProblemCode.CONVERSATION_BOUND, "Only the conversation's person speaks, "
                    + "answers, renames and connects accounts in it");
        }
        return row;
    }

    /**
     * L22: a personal conversation's person may still use its agent; the share that let them may have been removed, or
     * they may have left its group ({@code 409 AGENT_NOT_USABLE}). Its turns wait for the same reason at dispatch.
     */
    private void requireAgentUsable(ConversationInfo conversation) {
        if (!holds.personMayUseAgent(conversation)) {
            String agent = agents.find(conversation.companyId(), conversation.agentId()).map(AgentInfo::name)
                .orElse("The agent");
            throw ApiException.conflict(ProblemCode.AGENT_NOT_USABLE, agent + " is no longer shared with this "
                    + "conversation's person: it takes no message and no retry until it is shared with them again");
        }
    }

    private static void requireOpen(ConversationInfo conversation) {
        if (!conversation.open()) {
            throw ApiException.conflict(ProblemCode.CONVERSATION_ENDED, "The conversation ended and takes nothing "
                    + "more: start a new one");
        }
    }

    static ApiException notFound() {
        return ApiException.notFound("Conversation not found");
    }
}
