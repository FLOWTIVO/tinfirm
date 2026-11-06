package com.flowtivo.tinfirm.chat.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.PersonalConversations;
import com.flowtivo.tinfirm.conversation.PlaceConversations;
import com.flowtivo.tinfirm.conversation.PlaceFiles;
import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystemInfo;

/**
 * {@code DirectMessages.receive} (the package 5 contract, §5.6, open points 4 and 5, C5, C8, C9): a direct message to
 * the bot from a linked member is their personal conversation, exactly as the app's (their bindings, budget, settings
 * and visibility, {@code PersonalConversations.start}), held in the chat ({@code CHAT_THREAD},
 * {@code DIRECT_MESSAGE}).
 * <ol>
 * <li>The person: the sender's link; without one, the sign-in link (or Teams' automatic link) and nothing else
 * (C5).</li>
 * <li>The thread: on Slack each top-level message starts a conversation, answered in its thread, and a reply in the
 * thread continues it; Teams' personal chat has one open conversation per member per system, which another agent's
 * handle or "New conversation" ends {@code SUPERSEDED}.</li>
 * <li>An existing conversation: its person must be the sender's member now ({@code NOT_YOURS}), and it is open
 * ({@code THREAD_ENDED}, C8); the message is the person's, which answers every open question (ADR-0033), else is the
 * next turn's, or waits.</li>
 * <li>A new conversation: the agent of a leading {@code @handle}, else the member's default, else a picker of the
 * agents they use; the message waits for a press of the same chat user ({@code AWAITING_AGENT}, an hour, at most 5).
 * The first conversation of a chat user carries the Owner notice (row 18).</li>
 * <li>Files, on Slack only, downloaded outside any transaction and added in the conversation's; on Teams the bot
 * points to the app.</li>
 * </ol>
 * While the company's runs are stopped (the package 6 contract, §5.4, G4), a message that would start a conversation
 * starts none and holds for no picker ({@code COMPANY_STOPPED}); a message to an open conversation is taken and its
 * turn waits. Either way the chat user is told at most once an hour ({@code chat_user.stop_told_at}).
 */
@Component
class DirectMessages implements ChatInbox.Handler {

    static final int MAX_HELD = 5;

    static final int PICKER_AGENTS = 10;

    private static final Pattern LEADING_HANDLE = Pattern.compile("^\\s*@([\\p{L}\\p{N}-]+)[\\s,:;.!?]*");

    static final String NOTICE = "This is a personal conversation: %s acts with your own accounts and budget. The "
            + "Owner of %s can read it for support and audit.";

    private final ChatInbox inbox;

    private final ChatStore store;

    private final ChatLinks links;

    private final ChatPoster poster;

    private final PersonalConversations personal;

    private final PlaceConversations places;

    private final PlaceFiles files;

    private final Agents agents;

    private final CompanyNames companies;

    private final CompanySwitches switches;

    private final ChatOutbox outbox;

    private final SystemTransactions transactions;

    private final TinfirmProperties tinfirm;

    private final ChatProperties properties;

    private final TimeSource time;

    private final long maxFileBytes;

    private final CompanyDirectory roles;

    DirectMessages(ChatInbox inbox, ChatStore store, ChatLinks links, ChatPoster poster,
            PersonalConversations personal, PlaceConversations places, PlaceFiles files, Agents agents,
            CompanyNames companies, CompanySwitches switches, ChatOutbox outbox, SystemTransactions transactions,
            TinfirmProperties tinfirm, ChatProperties properties, TimeSource time, CompanyDirectory roles,
            @org.springframework.beans.factory.annotation.Value("${tinfirm.attachment.max-file-size:25MB}")
            org.springframework.util.unit.DataSize maxFileSize) {
        this.roles = roles;
        this.inbox = inbox;
        this.store = store;
        this.links = links;
        this.poster = poster;
        this.personal = personal;
        this.places = places;
        this.files = files;
        this.agents = agents;
        this.companies = companies;
        this.switches = switches;
        this.outbox = outbox;
        this.transactions = transactions;
        this.tinfirm = tinfirm;
        this.properties = properties;
        this.time = time;
        this.maxFileBytes = maxFileSize.toBytes();
        inbox.register(Inbound.MESSAGE, this);
    }

    /** A direct message as its payload holds it. */
    record Dm(String platform, String user, String channel, String ts, @Nullable String threadTs, String text,
            @Nullable String serviceUrl, @Nullable String aadObjectId, List<Map<String, Object>> files,
            boolean teamsFiles) {

        boolean slack() {
            return "SLACK".equals(platform);
        }

        /** Where a reply to this message goes before a conversation holds it: its own thread on Slack. */
        ChatPoster.Target here() {
            return new ChatPoster.Target(channel, slack() ? (threadTs != null ? threadTs : ts) : null, serviceUrl);
        }

        @SuppressWarnings("unchecked")
        static Dm of(ChatStore.EventRow event) {
            Map<String, Object> payload = event.payload();
            Object files = payload.get("files");
            return new Dm(String.valueOf(payload.get("platform")), Objects.requireNonNull(event.sender()),
                    Objects.requireNonNull(ChatInbox.text(payload, "channel")),
                    Objects.requireNonNull(ChatInbox.text(payload, "ts")), ChatInbox.text(payload, "threadTs"),
                    Objects.requireNonNullElse(ChatInbox.text(payload, "text"), ""),
                    ChatInbox.text(payload, "serviceUrl"), ChatInbox.text(payload, "aadObjectId"),
                    files instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(),
                    Boolean.TRUE.equals(payload.get("hasFiles")));
        }
    }

    @Override
    public ChatInbox.Outcome handle(ChatSystemInfo system, ChatStore.EventRow event, ChatAudience.Who who) {
        Dm dm = Dm.of(event);
        UUID companyId = system.companyId();
        Map<String, Object> dmRef = new LinkedHashMap<>();
        dmRef.put("channel", dm.channel());
        if (dm.serviceUrl() != null) {
            dmRef.put("serviceUrl", dm.serviceUrl());
        }
        transactions.runInCompany(companyId, () -> store.seen(companyId, system.id(), dm.user(), dm.aadObjectId(),
                who.name(), dmRef, time.now()));
        ChatLinks.Member member = transactions.inCompany(companyId, () -> links.memberOf(system, dm.user(),
                who.aadObjectId() != null ? who.aadObjectId() : dm.aadObjectId())
            .orElse(null));
        if (member == null) {
            member = links.automatic(system, dm.user(), who, dm.here()).orElse(null);
        }
        if (member == null) {
            return links.offer(system, event, who, dm.here());
        }
        ChatLinks.Member person = member;
        if (transactions.inCompany(companyId, () -> roles.roleOf(companyId, person.userId()).orElse(null))
                == CompanyRole.VIEWER) {
            // ADR-0077, decision 5: a Viewer starts and continues no conversation, here neither.
            inbox.tell(system, event, "You are a Viewer in this company: you read what it shares with you in "
                    + "Tinfirm, and start or continue no conversation with its agents. An admin can change your role.");
            return ChatInbox.Outcome.refused("VIEWER_READ_ONLY");
        }
        Leading leading = leading(companyId, dm.text());

        ConversationInfo existing;
        ConversationInfo superseded = null;
        String threadId;
        if (dm.slack()) {
            threadId = dm.threadTs() != null ? dm.threadTs() : dm.ts();
            existing = transactions.inCompany(companyId, () -> places.ofThread(companyId, system.id(), dm.channel(),
                    threadId).orElse(null));
        }
        else {
            threadId = dm.ts();
            ConversationInfo open = transactions.inCompany(companyId, () -> places.openInChat(companyId,
                    person.principalId(), system.id())
                .stream()
                .filter(conversation -> dm.channel().equals(places.placeRef(companyId, conversation.id())
                    .map(ref -> ref.get("channelId"))
                    .orElse(null)))
                .reduce((first, second) -> second)
                .orElse(null));
            boolean otherAgent = open != null && leading.agent() != null
                    && !leading.agent().id().equals(open.agentId());
            existing = otherAgent ? null : open;
            superseded = otherAgent ? open : null;
        }
        if (existing != null) {
            return continueIn(system, event, dm, who, person, existing);
        }
        if (transactions.inCompany(companyId, () -> switches.runsStopped(companyId).isPresent())) {
            // G4: nothing starts, nor waits for the picker, while the company's runs are stopped.
            stopped(system, event, false);
            return ChatInbox.Outcome.refused("COMPANY_STOPPED");
        }
        if (leading.handle() != null && leading.agent() == null) {
            inbox.tell(system, event, "No agent is called @" + leading.handle() + ". Ask one of the agents you use, "
                    + "or start without a handle.");
            return ChatInbox.Outcome.refused("NO_AGENT_CHOSEN");
        }
        AgentInfo agent = leading.agent();
        String text = leading.agent() != null ? leading.rest() : dm.text();
        if (agent == null) {
            agent = transactions.inCompany(companyId, () -> store.defaultAgent(person.principalId())
                .flatMap(id -> agents.find(companyId, id))
                .filter(AgentInfo::live)
                .filter(found -> agents.access(companyId, found.id(), person.userId()).uses())
                .orElse(null));
        }
        if (agent == null) {
            return picker(system, event, dm, person);
        }
        return start(system, event, dm, who, person, agent, text, threadId, superseded);
    }

    /** The leading {@code @handle} of a message that starts a conversation, its agent, and the text after it. */
    private record Leading(@Nullable String handle, @Nullable AgentInfo agent, String rest) {
    }

    private Leading leading(UUID companyId, String text) {
        Matcher matcher = LEADING_HANDLE.matcher(text);
        if (!matcher.find()) {
            return new Leading(null, null, text);
        }
        String handle = matcher.group(1);
        AgentInfo agent = transactions.inCompany(companyId, () -> agents.byHandle(companyId, handle).orElse(null));
        String rest = text.substring(matcher.end()).strip();
        return new Leading(handle, agent, rest.isEmpty() ? text.strip() : rest);
    }

    // ---- an existing conversation -----------------------------------------------------------------------------------

    private ChatInbox.Outcome continueIn(ChatSystemInfo system, ChatStore.EventRow event, Dm dm, ChatAudience.Who who,
            ChatLinks.Member member, ConversationInfo conversation) {
        UUID companyId = system.companyId();
        if (!member.principalId().equals(conversation.personPrincipalId())) {
            inbox.tell(system, event, "This conversation is another member's.");
            return ChatInbox.Outcome.refused("NOT_YOURS");
        }
        if (!conversation.open()) {
            inbox.tell(system, event, "This conversation ended; send a new message to start one.");
            return ChatInbox.Outcome.refused("THREAD_ENDED");
        }
        List<Downloaded> downloaded = download(system, dm);
        Received received = transactions.inCompany(companyId, () -> {
            PlaceConversations.Received result = places.receive(companyId, conversation.id(),
                    new PlaceConversations.PlaceMessage(PlaceConversations.Kind.PERSON, member.principalId(), dm.ts(),
                            author(dm, who), dm.text().isBlank() ? "(a file)" : dm.text(), true));
            List<String> refused = result instanceof PlaceConversations.Stored ? add(companyId, conversation.id(),
                    downloaded) : List.of();
            if (result instanceof PlaceConversations.Stored) {
                outbox.received(companyId, conversation.id(), dm.ts());
            }
            store.finish(event.id(), "HANDLED", null, time.now());
            return new Received(result, refused, switches.runsStopped(companyId).isPresent());
        });
        ChatPoster.Target target = reply(dm, conversation.id(), threadOf(companyId, conversation.id(), dm));
        if (received.result() instanceof PlaceConversations.Ended) {
            inbox.tell(system, event, "This conversation ended; send a new message to start one.");
            return ChatInbox.Outcome.RECORDED;
        }
        if (received.result() instanceof PlaceConversations.Stored) {
            if (received.stopped()) {
                // G4: the message is taken, and its turn waits for the lift.
                stopped(system, event, true);
            }
        }
        after(system, dm, conversation.id(), target, received.refused());
        return ChatInbox.Outcome.RECORDED;
    }

    private record Received(PlaceConversations.Received result, List<String> refused, boolean stopped) {
    }

    // ---- a new conversation -----------------------------------------------------------------------------------------

    /**
     * Starts the conversation with the agent: {@code PersonalConversations.start}, exactly the app's start, the files
     * added in its transaction, the event recorded; a refusal is posted with its reason. Also the press of the
     * picker, with the held message ({@link #startHeld}).
     */
    ChatInbox.Outcome start(ChatSystemInfo system, ChatStore.EventRow event, Dm dm, ChatAudience.Who who,
            ChatLinks.Member member, AgentInfo agent, String text, String threadId,
            @Nullable ConversationInfo superseded) {
        UUID companyId = system.companyId();
        boolean uses = transactions.inCompany(companyId, () -> agents.access(companyId, agent.id(), member.userId())
            .uses());
        if (!uses) {
            inbox.tell(system, event, "You may not use @" + agent.handle() + ".");
            return ChatInbox.Outcome.refused("NOT_USABLE");
        }
        if (agent.paused()) {
            inbox.tell(system, event, agent.name() + " is paused.");
            return ChatInbox.Outcome.refused("AGENT_PAUSED");
        }
        List<Downloaded> downloaded = download(system, dm);
        Map<String, String> placeRef = new LinkedHashMap<>();
        placeRef.put("systemId", system.id().toString());
        placeRef.put("kind", "DM");
        if (system.workspace() != null) {
            placeRef.put("teamId", system.workspace());
        }
        placeRef.put("channelId", dm.channel());
        placeRef.put("threadId", threadId);
        if (dm.serviceUrl() != null) {
            placeRef.put("serviceUrl", dm.serviceUrl());
        }
        Started started;
        try {
            started = transactions.inCompany(companyId, () -> {
                if (superseded != null) {
                    places.end(companyId, superseded.id(), ConversationInfo.EndedReason.SUPERSEDED, member.userId());
                }
                PersonalConversations.Started conversation = personal.start(companyId, member.principalId(),
                        agent.id(), new PersonalConversations.FirstMessage(text, dm.ts(), author(dm, who)),
                        ConversationInfo.Place.CHAT_THREAD, ConversationInfo.Origin.DIRECT_MESSAGE, placeRef);
                List<String> refused = add(companyId, conversation.conversationId(), downloaded);
                store.finish(event.id(), "HANDLED", null, time.now());
                boolean notice = store.user(system.id(), dm.user())
                    .map(user -> user.noticeShownAt() == null)
                    .orElse(true);
                if (notice) {
                    store.noticeShown(system.id(), dm.user(), time.now());
                    // The first conversation of a chat user carries the notice (row 18), before anything else.
                    String company = companies.nameOf(companyId).orElse("your company");
                    outbox.notice(companyId, conversation.conversationId(), "first", NOTICE.formatted(agent.name(),
                            company));
                }
                outbox.received(companyId, conversation.conversationId(), dm.ts());
                return new Started(conversation.conversationId(), refused, notice);
            });
        }
        catch (PersonalConversations.StartRefused refusal) {
            if ("COMPANY_STOPPED".equals(refusal.code())) {
                // The stop came between the check and the start, or the picker's press came during it (G4).
                stopped(system, event, false);
                return ChatInbox.Outcome.refused("COMPANY_STOPPED");
            }
            inbox.tell(system, event, agent.name() + " cannot start this conversation: " + refusal.getMessage());
            return ChatInbox.Outcome.refused("START_REFUSED");
        }
        ChatPoster.Target target = new ChatPoster.Target(dm.channel(), dm.slack() ? threadId : null,
                dm.serviceUrl());
        after(system, dm, started.conversationId(), target, started.refused());
        return ChatInbox.Outcome.RECORDED;
    }

    private record Started(UUID conversationId, List<String> refused, boolean notice) {
    }

    /**
     * The picker's press of the held message's own sender: the conversation starts with it and the chosen agent.
     * Records the held event; the press is the caller's.
     */
    ChatInbox.Outcome startHeld(ChatSystemInfo system, ChatStore.EventRow held, ChatAudience.Who who,
            ChatLinks.Member member, AgentInfo agent) {
        Dm dm = Dm.of(held);
        String threadId = dm.slack() ? (dm.threadTs() != null ? dm.threadTs() : dm.ts()) : dm.ts();
        ConversationInfo superseded = null;
        if (!dm.slack()) {
            UUID companyId = system.companyId();
            superseded = transactions.inCompany(companyId, () -> places.openInChat(companyId, member.principalId(),
                    system.id()).stream().reduce((first, second) -> second).orElse(null));
        }
        Leading leading = leading(system.companyId(), dm.text());
        String text = leading.agent() != null ? leading.rest() : dm.text();
        return start(system, held, dm, who, member, agent, text, threadId, superseded);
    }

    /** No agent named and no default: the message waits for the picker, at most 5 per chat user, for an hour. */
    private ChatInbox.Outcome picker(ChatSystemInfo system, ChatStore.EventRow event, Dm dm,
            ChatLinks.Member member) {
        UUID companyId = system.companyId();
        List<AgentInfo> usable = transactions.inCompany(companyId, () -> agents.usedBy(companyId, member.userId(),
                PICKER_AGENTS));
        if (usable.isEmpty()) {
            inbox.tell(system, event, "You use no agent in Tinfirm yet: ask an admin to share one with you.");
            return ChatInbox.Outcome.refused("NO_AGENT_CHOSEN");
        }
        Instant until = time.now().plus(properties.agentHold());
        boolean held = transactions.inCompany(companyId, () -> {
            store.lockUser(system.id(), dm.user());
            if (store.held(system.id(), dm.user(), "AWAITING_AGENT").size() >= MAX_HELD) {
                store.finish(event.id(), "IGNORED", "NO_AGENT_CHOSEN", time.now());
                return false;
            }
            store.hold(event.id(), "AWAITING_AGENT", until);
            return true;
        });
        if (!held) {
            inbox.tell(system, event, "You have " + MAX_HELD + " messages waiting for an agent: choose one for them "
                    + "first.");
            return ChatInbox.Outcome.RECORDED;
        }
        List<ChatCards.Button> buttons = new ArrayList<>();
        for (AgentInfo agent : usable) {
            buttons.add(ChatCards.Button.primary("@" + agent.handle(), Map.of("a", "pick", "e",
                    event.id().toString(), "g", agent.id().toString())));
        }
        for (AgentInfo agent : usable.subList(0, Math.min(usable.size(), 10))) {
            buttons.add(ChatCards.Button.press("Always @" + agent.handle(), Map.of("a", "pick", "e",
                    event.id().toString(), "g", agent.id().toString(), "d", true)));
        }
        poster.post(companyId, system.id(), dm.here(), ChatCards.Message.of("Which agent should answer this? "
                + "Your message waits for an hour. \"Always\" makes the agent your default here; start a message "
                + "with @handle to ask another.").withButtons(buttons), ChatPoster.Recorded.of("PICKER", null));
        return ChatInbox.Outcome.RECORDED;
    }

    // ---- the company's stop -----------------------------------------------------------------------------------------

    /**
     * Tells the chat user that the company's runs are stopped, at most once an hour (the package 6 contract, §5.4):
     * their message waits, or nothing was started.
     */
    private void stopped(ChatSystemInfo system, ChatStore.EventRow event, boolean waits) {
        UUID companyId = system.companyId();
        String sender = Objects.requireNonNull(event.sender());
        Instant now = time.now();
        boolean tell = transactions.inCompany(companyId, () -> store.tellStop(system.id(), sender, now,
                now.minus(ChatInbox.REFUSAL_TOLD)));
        if (tell) {
            String company = companies.nameOf(companyId).orElse("your company");
            inbox.tell(system, event, "Tinfirm is stopped in " + company + " by an admin; "
                    + (waits ? "your message waits until the stop is lifted." : "nothing was started."));
        }
    }

    // ---- files ------------------------------------------------------------------------------------------------------

    private record Downloaded(String name, @Nullable String contentType, byte @Nullable [] content,
            @Nullable String why) {
    }

    /** Slack's files of the message, outside any transaction; Teams' are not read (open point 12). */
    private List<Downloaded> download(ChatSystemInfo system, Dm dm) {
        List<Downloaded> downloaded = new ArrayList<>();
        if (!dm.slack()) {
            return downloaded;
        }
        for (Map<String, Object> file : dm.files()) {
            String name = Objects.requireNonNullElse(ChatInbox.text(file, "name"), "file");
            String url = ChatInbox.text(file, "url");
            Object size = file.get("size");
            if (url == null) {
                downloaded.add(new Downloaded(name, null, null, "it could not be read"));
                continue;
            }
            if (size instanceof Number number && number.longValue() > maxFileBytes) {
                downloaded.add(new Downloaded(name, null, null, "it is too large"));
                continue;
            }
            Optional<byte[]> bytes = poster.download(system.companyId(), system.id(), url, maxFileBytes);
            downloaded.add(bytes.map(content -> new Downloaded(name, ChatInbox.text(file, "mimetype"), content,
                    null)).orElseGet(() -> new Downloaded(name, null, null, "it could not be read")));
        }
        return downloaded;
    }

    /** Adds the files in the conversation's transaction: the names of those left out, with why. */
    private List<String> add(UUID companyId, UUID conversationId, List<Downloaded> downloaded) {
        List<String> refused = new ArrayList<>();
        for (Downloaded file : downloaded) {
            if (file.content() == null) {
                refused.add(file.name() + ": " + file.why());
                continue;
            }
            PlaceFiles.Added added = files.add(companyId, conversationId, new PlaceFiles.PlaceFile(file.name(),
                    file.contentType(), file.content(), "From Slack"), "SLACK");
            if (added instanceof PlaceFiles.Refused no) {
                refused.add(file.name() + ": " + no.message());
            }
        }
        return refused;
    }

    /** What follows a stored message: the files left out, and on Teams the files it cannot read. */
    private void after(ChatSystemInfo system, Dm dm, UUID conversationId, ChatPoster.Target target,
            List<String> refused) {
        if (!refused.isEmpty()) {
            poster.post(system.companyId(), system.id(), target, ChatCards.Message.of("Not added to the conversation: "
                    + String.join("; ", refused) + "."), ChatPoster.Recorded.of("SYSTEM", conversationId));
        }
        if (!dm.slack() && dm.teamsFiles()) {
            poster.post(system.companyId(), system.id(), target, ChatCards.Message.of("Files sent in Teams are not "
                    + "read yet; add the file to this conversation in Tinfirm: " + page(system.companyId(),
                            conversationId)), ChatPoster.Recorded.of("SYSTEM", conversationId));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Map<String, Object> author(Dm dm, ChatAudience.Who who) {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("id", dm.user());
        author.put("name", who.name() != null ? who.name() : dm.user());
        author.put("via", dm.platform());
        author.put("verified", true);
        return author;
    }

    private @Nullable String threadOf(UUID companyId, UUID conversationId, Dm dm) {
        if (!dm.slack()) {
            return null;
        }
        return transactions.inCompany(companyId, () -> places.placeRef(companyId, conversationId)
            .map(ref -> ref.get("threadId"))
            .orElse(null));
    }

    private static ChatPoster.Target reply(Dm dm, UUID conversationId, @Nullable String threadId) {
        return new ChatPoster.Target(dm.channel(), threadId, dm.serviceUrl());
    }

    String page(UUID companyId, UUID conversationId) {
        String base = tinfirm.publicUrl().toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/c/" + companyId
                + "/conversations/" + conversationId;
    }
}
