package com.flowtivo.tinfirm.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentReads;
import com.flowtivo.tinfirm.conversation.ConversationCommands;
import com.flowtivo.tinfirm.conversation.ConversationCommands.Submitted;
import com.flowtivo.tinfirm.conversation.ConversationReads;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.conversation.StagedFiles;
import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.mcp.internal.GmailEmails.Action;
import com.flowtivo.tinfirm.mcp.internal.GoogleIdTokens.GoogleAccount;
import com.flowtivo.tinfirm.shared.api.model.AgentSummary;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationStartRequest;
import com.flowtivo.tinfirm.shared.api.model.DecisionKind;
import com.flowtivo.tinfirm.shared.api.model.DecisionStatus;
import com.flowtivo.tinfirm.shared.api.model.GmailDeployment;
import com.flowtivo.tinfirm.shared.api.model.GmailLink;
import com.flowtivo.tinfirm.shared.api.model.GmailLinkRequest;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.BearerGrant;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.GmailMessages;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the Gmail add-on does for a verified request (ADR-0083, decisions 3 to 7): an unlinked Google account gets the
 * sign-in card; a linked one acts as its person, as an AI client does (ADR-0045): through {@link ConversationReads} and
 * {@link ConversationCommands} in the {@code CLIENT} view, a start of the origin {@code CLIENT} under the AI clients'
 * hourly limit, the person's tenant context and authentication, and a {@link BearerGrant} of the kind
 * {@code GMAIL_LINK} with {@code tinfirm:write}, so that the audit of its writes names the link. The email is read from
 * Gmail only for a start, with its attachments when the person sends them (held as theirs, ADR-0084, and moved into
 * the conversation; a click sent again is answered from its first conversation before the email is read, and a start
 * that takes no files, replayed or refused, releases those it held), and its reply headers only for a draft of an
 * answer (ADR-0084); outside any transaction, with the event's tokens, which are then dropped. The add-on is Pro's
 * and Enterprise's (ADR-0077, decision 4,
 * {@code GMAIL_ADDON}): a paid feature's code, under {@code ee/}, which checks the licence in force; the core verifies
 * Google's requests before it calls this.
 */
@Component
class GmailAddOnImpl implements GmailAddOn {

    private static final Logger log = LoggerFactory.getLogger(GmailAddOnImpl.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The agents the start card offers at most. */
    static final int MAX_AGENTS = 50;

    /** The attachments a start sends at most: Google waits half a minute for the add-on's answer. */
    static final int MAX_ATTACHMENTS = 20;

    /** The megabytes of attachments a start sends at most, in all. */
    static final int MAX_ATTACHMENT_MEGABYTES = 25;

    private static final long MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MEGABYTES * 1024L * 1024L;

    private final GmailLinking links;

    private final GmailDeployments deployments;

    private final GmailStore store;

    private final GmailMessages gmail;

    private final AgentReads agents;

    private final ConversationReads reads;

    private final ConversationCommands commands;

    private final StagedFiles staged;

    private final OwnerNotices notices;

    private final CompanyNames companies;

    private final McpProperties mcp;

    private final TinfirmProperties tinfirm;

    private final SystemTransactions system;

    private final TimeSource time;

    private final Licensing licensing;

    private final SecurityContextHolderStrategy security = SecurityContextHolder.getContextHolderStrategy();

    GmailAddOnImpl(GmailLinking links, GmailDeployments deployments, GmailStore store, GmailMessages gmail,
            AgentReads agents, ConversationReads reads, ConversationCommands commands, StagedFiles staged,
            OwnerNotices notices, CompanyNames companies, McpProperties mcp, TinfirmProperties tinfirm,
            SystemTransactions system, TimeSource time, Licensing licensing) {
        this.links = links;
        this.deployments = deployments;
        this.store = store;
        this.gmail = gmail;
        this.agents = agents;
        this.reads = reads;
        this.commands = commands;
        this.staged = staged;
        this.notices = notices;
        this.companies = companies;
        this.mcp = mcp;
        this.tinfirm = tinfirm;
        this.system = system;
        this.time = time;
        this.licensing = licensing;
    }

    @Override
    public GmailDeployment deployment(UUID companyId) {
        return deployments.deployment(companyId);
    }

    @Override
    public GmailLinkRequest readLink(UUID companyId, String token) {
        return links.read(companyId, token);
    }

    @Override
    public GmailLink confirmLink(UUID companyId, String token) {
        return links.confirm(companyId, token);
    }

    /** The URLs of the company's add-on and pages. */
    GmailCards.Urls urls(UUID companyId) {
        String base = tinfirm.publicBaseUrl();
        return new GmailCards.Urls(base + "/api/gmail/" + companyId + "/act", base + "/c/" + companyId);
    }

    @Override
    public Map<String, Object> handle(UUID companyId, String companyName, String trigger, Event event,
            GoogleAccount account) {
        if (!licensing.has(Feature.GMAIL_ADDON)) {
            // ADR-0077, decision 4 (ADR-0083): Pro's and Enterprise's, as the Outlook add-in. Said in the card.
            return GmailAddOn.unlicensed(trigger);
        }
        boolean button = "act".equals(trigger);
        Optional<GmailLinking.Active> found = links.active(companyId, account);
        if (found.isEmpty()) {
            Map<String, Object> card = GmailCards.signIn(companyName, account.email(), links.offer(companyId, account));
            return button ? GmailCards.updated(card) : GmailCards.pushed(card);
        }
        GmailLinking.Active active = found.get();
        Map<String, Object> card = switch (trigger) {
            case "home" -> GmailCards.home(companyName, active.user().displayName(), urls(companyId), null);
            case "open" -> emailCard(companyId, active, event, null);
            default -> act(companyId, companyName, active, event, account);
        };
        return button ? GmailCards.updated(card) : GmailCards.pushed(card);
    }

    private Map<String, Object> act(UUID companyId, String companyName, GmailLinking.Active active, Event event,
            GoogleAccount account) {
        String what = Objects.requireNonNullElse(event.parameters().get("do"), "");
        return switch (what) {
            case "start" -> start(companyId, active, event);
            case "refresh" -> conversationCard(companyId, active, event, uuid(event.parameters().get("conversationId")),
                    null);
            case "send" -> send(companyId, active, event);
            case "draft" -> draft(companyId, active, event, account);
            case "new" -> {
                EmailKeys keys = EmailKeys.of(event);
                if (keys != null) {
                    system.runInCompany(companyId, () -> store.forget(companyId, active.user().id(), keys.email(),
                            keys.thread()));
                }
                yield startCard(companyId, active, null);
            }
            case "signout" -> {
                links.signOut(companyId, active);
                yield GmailCards.signIn(companyName, account.email(), links.offer(companyId, account));
            }
            default -> emailCard(companyId, active, event, null);
        };
    }

    // ---- the open email ------------------------------------------------------------------------------------------------

    /** The keys of the open message and its thread. */
    private record EmailKeys(String email, @Nullable String thread) {

        static @Nullable EmailKeys of(Event event) {
            if (event.messageId() == null || event.messageId().isBlank()) {
                return null;
            }
            return new EmailKeys(GmailEmails.key("gmail", event.messageId()),
                    event.threadId() != null && !event.threadId().isBlank()
                            ? GmailEmails.key("gmail-thread", event.threadId()) : null);
        }
    }

    /** The open email's card: its conversation when the person has one, else the start card. */
    private Map<String, Object> emailCard(UUID companyId, GmailLinking.Active active, Event event,
            @Nullable String error) {
        EmailKeys keys = EmailKeys.of(event);
        if (keys == null) {
            return GmailCards.home(companyName(companyId), active.user().displayName(), urls(companyId), error);
        }
        Optional<GmailStore.EmailConversation> remembered = system.inCompany(companyId,
                () -> store.conversationOf(companyId, active.user().id(), keys.email(), keys.thread()));
        if (remembered.isEmpty()) {
            return startCard(companyId, active, error);
        }
        try {
            Conversation conversation = as(companyId, active,
                    () -> reads.get(companyId, ConversationView.CLIENT, remembered.get().conversationId()));
            return GmailCards.conversation(conversation, !remembered.get().emailKey().equals(keys.email()),
                    urls(companyId), error);
        }
        catch (ApiException ex) {
            if (ex.status() == HttpStatus.NOT_FOUND) {
                // Not the person's to read any more: forgotten, and the email starts afresh.
                system.runInCompany(companyId, () -> store.forget(companyId, active.user().id(), keys.email(),
                        keys.thread()));
                return startCard(companyId, active, error);
            }
            return startCard(companyId, active, ex.getMessage());
        }
    }

    private Map<String, Object> startCard(UUID companyId, GmailLinking.Active active, @Nullable String error) {
        List<AgentSummary> usable;
        try {
            usable = as(companyId, active, () -> agents.list(companyId, true, MAX_AGENTS, null).getItems());
        }
        catch (ApiException ex) {
            return GmailCards.home(companyName(companyId), active.user().displayName(), urls(companyId),
                    ex.getMessage());
        }
        return GmailCards.start(active.user().displayName(), usable, active.link().lastAgentId(),
                notices.of(companyId, GmailLinking.CLIENT), urls(companyId), error);
    }

    // ---- buttons -------------------------------------------------------------------------------------------------------

    /** Reads the open email from Gmail, starts the conversation about it and remembers it. */
    private Map<String, Object> start(UUID companyId, GmailLinking.Active active, Event event) {
        EmailKeys keys = EmailKeys.of(event);
        Action action = Action.of(event.parameters().get("action"));
        UUID agentId = uuid(event.inputs().get("agent"));
        String ownWords = Objects.requireNonNullElse(event.inputs().get("question"), "").strip();
        if (keys == null || event.messageToken() == null || event.userOAuthToken() == null) {
            return GmailCards.home(companyName(companyId), active.user().displayName(), urls(companyId),
                    "Open the email again, then choose what to ask.");
        }
        if (action == null || agentId == null) {
            return startCard(companyId, active, "Choose an agent and what to ask.");
        }
        if (action == Action.ASK && ownWords.isEmpty()) {
            return startCard(companyId, active, "Write your question first.");
        }
        boolean withAttachments = "yes".equals(event.inputs().get("attachments"));
        // The same click sent again (a retry of Google's, a double click) answers the conversation it started: the
        // start card's own id, which a new card changes, makes the same request, its fence's tag of the same seed,
        // which the email's author cannot know. A card without an id counts the minute instead.
        UUID card = uuid(event.parameters().get("card"));
        String click = card != null ? card.toString() : "minute:" + time.now().getEpochSecond() / 60;
        String requestId = UUID.nameUUIDFromBytes((active.link().id() + ":" + keys.email() + ":" + click + ":" + action
                + ":" + agentId + ":" + ownWords + ":" + withAttachments).getBytes(StandardCharsets.UTF_8)).toString();
        try {
            // Its conversation, when the click was answered already: before the email and its attachments are read
            // from Gmail again, and before anything is held (ADR-0084, decision 9). The start's own key still decides.
            Optional<Conversation> before = as(companyId, active, () -> commands.startedWith(companyId, requestId));
            if (before.isPresent()) {
                return started(companyId, active, keys, action, agentId, before.get());
            }
        }
        catch (ApiException ex) {
            return startCard(companyId, active, refusal(ex));
        }
        CallResult<String> read = gmail.message(event.userOAuthToken(), event.messageToken(),
                Objects.requireNonNull(event.messageId()));
        JsonNode message = read.succeeded() ? parse(Objects.requireNonNull(read.value())) : null;
        if (message == null) {
            log.warn("The Gmail add-on could not read the open email in company {}: {}", companyId,
                    read.failureMessage() != null ? read.failureMessage() : "not JSON");
            return startCard(companyId, active, "Gmail did not give the email to Tinfirm. Open it again and retry.");
        }
        GmailEmails.Email email = GmailEmails.parse(message);
        Held held = hold(companyId, active, event, email.attachments(), withAttachments);
        String request = GmailEmails.request(action, ownWords, email, held.files(),
                GmailEmails.fenceTag(requestId));
        boolean taken = false;
        try {
            Submitted<Conversation> started = as(companyId, active, () -> commands.startFromClient(companyId,
                    new ConversationStartRequest(agentId, request), mcp.startsPerHour(), requestId, held.ids()));
            taken = !started.replayed();
            return started(companyId, active, keys, action, agentId, started.body());
        }
        catch (ApiException ex) {
            return startCard(companyId, active, refusal(ex));
        }
        finally {
            if (!taken) {
                // A replay's conversation has the files of the click that started it, and a refused start took none:
                // what this click held would otherwise sit on the person's limits for an hour (ADR-0084).
                release(companyId, active, held.ids());
            }
        }
    }

    /** The conversation the click started, now or when it was first sent: remembered for the email, and its card. */
    private Map<String, Object> started(UUID companyId, GmailLinking.Active active, EmailKeys keys, Action action,
            UUID agentId, Conversation started) {
        system.runInCompany(companyId, () -> store.remember(companyId, active.user().id(),
                new GmailStore.EmailConversation(keys.email(), keys.thread(), started.getId(), action.name(),
                        time.now())));
        links.lastAgent(companyId, active.link().id(), agentId);
        return GmailCards.conversation(started, false, urls(companyId), null);
    }

    /**
     * Lets go of the files this click held that its start did not take ({@link StagedFiles#release}): a failure here
     * costs nothing but their hour, so it is logged, never answered.
     */
    private void release(UUID companyId, GmailLinking.Active active, List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            as(companyId, active, () -> {
                staged.release(companyId, ids);
                return null;
            });
        }
        catch (RuntimeException ex) {
            log.warn("The Gmail add-on could not release {} held files of a start in company {}", ids.size(),
                    companyId, ex);
        }
    }

    /** The attachments held for a start: their ids, and the names the request gives them and those left out. */
    private record Held(List<UUID> ids, GmailEmails.Files files) {
    }

    /**
     * The email's attachments, held as the person's for the start (ADR-0084) when they send them: at most
     * {@value #MAX_ATTACHMENTS} files and {@value #MAX_ATTACHMENT_MEGABYTES} MB in all, each within a conversation's
     * largest file, read from Gmail and checked and scanned as an upload is. What is not held is named with why.
     */
    private Held hold(UUID companyId, GmailLinking.Active active, Event event, List<GmailEmails.Attachment> attachments,
            boolean send) {
        if (attachments.isEmpty()) {
            return new Held(List.of(), GmailEmails.Files.NONE);
        }
        List<UUID> ids = new ArrayList<>();
        List<String> attached = new ArrayList<>();
        List<String> leftOut = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long maxFile = staged.maxFileBytes();
        long bytes = 0;
        for (GmailEmails.Attachment attachment : attachments) {
            String name = uniqueName(attachment.fileName(), names);
            names.add(name);
            if (!send) {
                leftOut.add(name + " (not sent)");
                continue;
            }
            if (ids.size() >= MAX_ATTACHMENTS) {
                leftOut.add(name + " (more than " + MAX_ATTACHMENTS + " files)");
                continue;
            }
            if (attachment.sizeBytes() > maxFile) {
                leftOut.add(name + " (larger than " + megabytes(maxFile) + ")");
                continue;
            }
            if (bytes + attachment.sizeBytes() > MAX_ATTACHMENT_BYTES) {
                leftOut.add(name + " (more than " + MAX_ATTACHMENT_MEGABYTES + " MB of attachments in all)");
                continue;
            }
            byte[] content = content(event, attachment, Math.min(maxFile, MAX_ATTACHMENT_BYTES - bytes));
            if (content == null) {
                leftOut.add(name + " (Gmail did not give it)");
                continue;
            }
            try {
                StagedFiles.StagedFile file = as(companyId, active,
                        () -> staged.stage(companyId, name, attachment.mimeType(), content));
                if (!file.fileName().equals(name) && !names.add(file.fileName())) {
                    // Checked, its name became another's: held, but not sent.
                    leftOut.add(name + " (another attachment has its name)");
                    continue;
                }
                ids.add(file.id());
                attached.add(file.fileName());
                bytes += file.sizeBytes();
            }
            catch (ApiException ex) {
                leftOut.add(name + " (" + leftOutBecause(ex) + ")");
            }
        }
        return new Held(List.copyOf(ids), new GmailEmails.Files(List.copyOf(attached), List.copyOf(leftOut)));
    }

    /** The attachment's bytes: sent with the message, or read from Gmail; null when Gmail does not give them. */
    private byte @Nullable [] content(Event event, GmailEmails.Attachment attachment, long maxBytes) {
        if (attachment.attachmentId() == null) {
            try {
                byte[] bytes = Base64.getUrlDecoder()
                    .decode(Objects.requireNonNullElse(attachment.data(), "").replace("=", "").strip());
                return bytes.length <= maxBytes ? bytes : null;
            }
            catch (IllegalArgumentException ex) {
                return null;
            }
        }
        CallResult<byte[]> read = gmail.attachment(Objects.requireNonNull(event.userOAuthToken()),
                Objects.requireNonNull(event.messageToken()), Objects.requireNonNull(event.messageId()),
                attachment.attachmentId(), maxBytes);
        if (!read.succeeded()) {
            log.warn("The Gmail add-on could not read an attachment: {}", read.failureMessage());
            return null;
        }
        return read.value();
    }

    /** The attachment's name, and a number before its extension when another attachment has it already. */
    private static String uniqueName(String original, Set<String> taken) {
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1).strip();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "attachment";
        }
        if (!taken.contains(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int number = 2;; number++) {
            String candidate = stem + " (" + number + ")" + extension;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** Why the attachment was not held, in the request's words. */
    private static String leftOutBecause(ApiException ex) {
        ProblemCode code = ex.code();
        if (code == null) {
            return "Tinfirm refused it";
        }
        return switch (code) {
            case CREDENTIAL_FOUND -> "it holds a credential";
            case ATTACHMENT_NOT_SCANNABLE -> "it cannot be scanned for credentials";
            case ATTACHMENT_TOO_LARGE -> "too large";
            case TOO_MANY_ATTACHMENTS -> "you hold too many files for a start";
            default -> "Tinfirm refused it";
        };
    }

    private static String megabytes(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    /**
     * Saves the answer of the turn as a draft reply in the open email's thread (ADR-0084, decision 7), as the Outlook
     * pane's <b>Use as reply</b>: the turn's summary, to the email's sender. The person edits and sends it in Gmail.
     */
    private Map<String, Object> draft(UUID companyId, GmailLinking.Active active, Event event, GoogleAccount account) {
        UUID conversationId = uuid(event.parameters().get("conversationId"));
        UUID runId = uuid(event.parameters().get("runId"));
        if (conversationId == null || runId == null) {
            return emailCard(companyId, active, event, null);
        }
        if (event.messageId() == null || event.threadId() == null || event.messageToken() == null
                || event.userOAuthToken() == null) {
            return conversationCard(companyId, active, event, conversationId, "Open the email again, then retry.");
        }
        String answer;
        try {
            Conversation conversation = as(companyId, active,
                    () -> reads.get(companyId, ConversationView.CLIENT, conversationId));
            answer = GmailCards.reply(conversation, runId);
        }
        catch (ApiException ex) {
            return emailCard(companyId, active, event, ex.getMessage());
        }
        if (answer == null) {
            return conversationCard(companyId, active, event, conversationId, "This answer cannot be a reply.");
        }
        CallResult<String> read = gmail.messageHeaders(event.userOAuthToken(), event.messageToken(),
                event.messageId());
        JsonNode headers = read.succeeded() ? parse(Objects.requireNonNull(read.value())) : null;
        byte[] reply = headers != null ? GmailReplies.reply(GmailReplies.Original.of(headers), answer,
                account.email()) : null;
        if (reply == null) {
            log.warn("The Gmail add-on could not read the open email's headers in company {}: {}", companyId,
                    headers == null ? Objects.requireNonNullElse(read.failureMessage(), "not JSON")
                            : "no address to reply to");
            return conversationCard(companyId, active, event, conversationId, headers == null
                    ? "Gmail did not give the email to Tinfirm. Open it again and retry."
                    : "The email names nobody to reply to.");
        }
        CallResult<GmailMessages.Draft> saved = gmail.createDraft(event.userOAuthToken(), event.messageToken(),
                event.threadId(), reply);
        if (!saved.succeeded()) {
            log.warn("The Gmail add-on could not save a draft in company {}: {}", companyId, saved.failureMessage());
            return conversationCard(companyId, active, event, conversationId,
                    "Gmail did not save the draft. Open the email again and retry.");
        }
        log.info("The Gmail add-on saved an answer as a draft reply for link {} in company {}", active.link().id(),
                companyId);
        GmailMessages.Draft draft = Objects.requireNonNull(saved.value());
        return conversationCard(companyId, active, event, conversationId, null, draftUrl(account, draft));
    }

    /** Gmail's page of the draft, in the person's Google account. */
    static String draftUrl(GoogleAccount account, GmailMessages.Draft draft) {
        String user = account.email() != null
                ? java.net.URLEncoder.encode(account.email(), StandardCharsets.UTF_8) : "0";
        return "https://mail.google.com/mail/?authuser=" + user + "#drafts?compose=" + draft.messageId();
    }

    /** The person's follow-up, or their answer to the agent's open questions. */
    private Map<String, Object> send(UUID companyId, GmailLinking.Active active, Event event) {
        UUID conversationId = uuid(event.parameters().get("conversationId"));
        String reply = Objects.requireNonNullElse(event.inputs().get("reply"), "").strip();
        if (conversationId == null) {
            return emailCard(companyId, active, event, null);
        }
        if (reply.isEmpty()) {
            return conversationCard(companyId, active, event, conversationId, "Write your message first.");
        }
        UUID card = uuid(event.parameters().get("card"));
        String click = card != null ? card.toString() : "minute:" + time.now().getEpochSecond() / 60;
        String requestId = UUID.nameUUIDFromBytes((active.link().id() + ":" + conversationId + ":" + click + ":"
                + reply).getBytes(StandardCharsets.UTF_8)).toString();
        try {
            as(companyId, active, () -> {
                Conversation current = reads.get(companyId, ConversationView.CLIENT, conversationId);
                boolean answers = current.getDecisions().stream()
                    .anyMatch(decision -> decision.getKind() == DecisionKind.QUESTION
                            && decision.getStatus() == DecisionStatus.OPEN);
                return commands.post(companyId, ConversationView.CLIENT, conversationId, reply, answers, requestId);
            });
            return conversationCard(companyId, active, event, conversationId, null);
        }
        catch (ApiException ex) {
            return conversationCard(companyId, active, event, conversationId, refusal(ex));
        }
    }

    private Map<String, Object> conversationCard(UUID companyId, GmailLinking.Active active, Event event,
            @Nullable UUID conversationId, @Nullable String error) {
        return conversationCard(companyId, active, event, conversationId, error, null);
    }

    private Map<String, Object> conversationCard(UUID companyId, GmailLinking.Active active, Event event,
            @Nullable UUID conversationId, @Nullable String error, @Nullable String draftUrl) {
        if (conversationId == null) {
            return emailCard(companyId, active, event, error);
        }
        EmailKeys keys = EmailKeys.of(event);
        try {
            Conversation conversation = as(companyId, active,
                    () -> reads.get(companyId, ConversationView.CLIENT, conversationId));
            boolean another = keys != null && system.inCompany(companyId,
                    () -> store.conversationOf(companyId, active.user().id(), keys.email(), keys.thread()))
                .map(remembered -> !remembered.emailKey().equals(keys.email()))
                .orElse(false);
            return GmailCards.conversation(conversation, another, urls(companyId), error, draftUrl);
        }
        catch (ApiException ex) {
            return emailCard(companyId, active, event, ex.getMessage());
        }
    }

    // ---- acting as the person ------------------------------------------------------------------------------------------

    /**
     * Runs the work as the link's person, as {@link ToolInvoker} runs a tool: their authentication, their tenant
     * context and the link as the bearer grant, opened for the call and restored afterwards.
     */
    private <T> T as(UUID companyId, GmailLinking.Active active, Supplier<T> work) {
        AuthenticatedUser user = active.user();
        SecurityContext previous = security.getContext();
        SecurityContext own = security.createEmptyContext();
        own.setAuthentication(new ActorAuthentication(user));
        security.setContext(own);
        try (TenantContextHolder.Scope ignoredTenant = TenantContextHolder
            .open(TenantContext.forUser(user.id(), user.instanceAdmin()));
                BearerGrantHolder.Scope ignoredGrant = BearerGrantHolder
                    .open(new BearerGrant(BearerGrant.Kind.GMAIL_LINK, active.link().id(), ApiScope.WRITE))) {
            return work.get();
        }
        finally {
            security.setContext(previous);
        }
    }

    /** A refusal of the services, in the card's words. */
    private static String refusal(ApiException ex) {
        if (ex.retryAfter() != null) {
            long minutes = Math.max(1, (ex.retryAfter().toSeconds() + 59) / 60);
            return ex.getMessage() + " Try again in " + minutes + (minutes == 1 ? " minute." : " minutes.");
        }
        return Objects.requireNonNullElse(ex.getMessage(), "Tinfirm refused this.");
    }

    private String companyName(UUID companyId) {
        return companies.nameOf(companyId).orElse("your company");
    }

    private static @Nullable JsonNode parse(String body) {
        try {
            JsonNode tree = JSON.readTree(body);
            return tree != null && tree.isObject() ? tree : null;
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    private static @Nullable UUID uuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
