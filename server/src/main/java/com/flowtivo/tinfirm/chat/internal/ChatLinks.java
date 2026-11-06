package com.flowtivo.tinfirm.chat.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.ChatAccounts;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ChatLink;
import com.flowtivo.tinfirm.shared.api.model.SystemKind;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;

/**
 * Linking chat users to members (the package 5 contract, §5.4, C4; the security review of 2026-10-12, rows 5 and 6). A
 * link is the member's {@code LINK} account on the chat system, its identity Slack's user id or, on Teams, the user's
 * Entra object id; one identity is one member per system (I2), a member one identity per system (I1). Never by an email
 * or a name.
 * <ul>
 * <li>{@link #automatic}, Teams only: the activity's {@code aadObjectId} and the system's tenant match exactly one
 * user's {@code oidc_object_id} and {@code oidc_tenant_id} (the ID token of their last Entra v2.0 login), an
 * {@code ACTIVE} member of the company without an account on the system.</li>
 * <li>{@link #offer}: an unlinked member of the workspace gets a sign-in link in their direct message, at most one per
 * 10 minutes, for that chat user only, used once, for 10 minutes; their messages wait for it (at most 5).</li>
 * <li>{@link #read} and {@link #confirm}: the token in a request's body; the page names the chat user by what they
 * cannot choose, and by who the platform says they are when the page loads (Slack's {@code users.info}, its picture;
 * the Entra name on Teams); confirming consumes the request whatever the outcome, and drops the messages that waited
 * for it rather than sending them as the member who confirmed (the security review of 2026-10-27, L3).</li>
 * <li>{@link #accountConnected}, Slack only: the member's own Slack sign-in for {@code history} (open point 13), whose
 * {@code OAUTH} account is their link; the account module refuses it for another Slack user than their link
 * ({@code already_linked}).</li>
 * </ul>
 * Both sides are told of a link: the direct message, and the member's inbox for 7 days with its unlink.
 */
@Component
class ChatLinks {

    private static final Logger log = LoggerFactory.getLogger(ChatLinks.class);

    static final int MAX_HELD = 5;

    static final Duration NOTICE_KEPT = Duration.ofDays(7);

    static final String OWNER_NOTICE = "Your conversations with Tinfirm's agents in this chat are personal: they act "
            + "with your own accounts and budget, and the Owner of %s can read them for support and audit.";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChatStore store;

    private final ChatSystems chatSystems;

    private final ChatAccounts chatAccounts;

    private final Accounts accounts;

    private final Principals principals;

    private final UserDirectory users;

    private final CompanyNames companies;

    private final CompanyAccessGuard guard;

    private final ChatPoster poster;

    private final AuditLog auditLog;

    private final SystemTransactions system;

    private final TransactionTemplate transactions;

    private final TinfirmProperties tinfirm;

    private final ChatProperties properties;

    private final TimeSource time;

    private final ChatBot bot;

    private final ChatCalls calls;

    ChatLinks(ChatStore store, ChatSystems chatSystems, ChatAccounts chatAccounts, Accounts accounts,
            Principals principals, UserDirectory users, CompanyNames companies, CompanyAccessGuard guard,
            ChatPoster poster, AuditLog auditLog, SystemTransactions system,
            PlatformTransactionManager transactionManager, TinfirmProperties tinfirm, ChatProperties properties,
            TimeSource time, ChatBot bot, ChatCalls calls) {
        this.bot = bot;
        this.calls = calls;
        this.store = store;
        this.chatSystems = chatSystems;
        this.chatAccounts = chatAccounts;
        this.accounts = accounts;
        this.principals = principals;
        this.users = users;
        this.companies = companies;
        this.guard = guard;
        this.poster = poster;
        this.auditLog = auditLog;
        this.system = system;
        this.transactions = new TransactionTemplate(transactionManager);
        this.tinfirm = tinfirm;
        this.properties = properties;
        this.time = time;
    }

    /** A member linked to a chat user. */
    record Member(UUID principalId, UUID userId, String name) {
    }

    /** The identity a link names: Slack's user id; on Teams the Entra object id, without which none links. */
    static @Nullable String identity(ChatSystemInfo system, String chatUserId, @Nullable String aadObjectId) {
        if (system.slack()) {
            return chatUserId;
        }
        return aadObjectId != null && !aadObjectId.isBlank() ? aadObjectId.toLowerCase(Locale.ROOT) : null;
    }

    /** The member linked to the chat user, an {@code ACTIVE} one; in the caller's transaction. */
    Optional<Member> memberOf(ChatSystemInfo chat, String chatUserId, @Nullable String aadObjectId) {
        String identity = identity(chat, chatUserId, aadObjectId);
        if (identity == null) {
            return Optional.empty();
        }
        return chatAccounts.ofIdentity(chat.companyId(), chat.id(), identity)
            .flatMap(account -> principals.find(chat.companyId(), account.principalId()))
            .filter(PrincipalInfo::member)
            .filter(PrincipalInfo::active)
            .filter(principal -> principal.userId() != null)
            .map(principal -> new Member(principal.id(), Objects.requireNonNull(principal.userId()),
                    name(chat.companyId(), principal)));
    }

    /**
     * Whether the chat user is a suspended member (the package 6 contract, §5.4, G6): linked to one, or on Teams the
     * user whom the Entra object id and the tenant name, as {@link #automatic} finds them. Such a chat user is refused
     * as a non-member is, and never offered a sign-in link. In the caller's transaction.
     */
    boolean suspended(ChatSystemInfo chat, String chatUserId, @Nullable String aadObjectId) {
        String identity = identity(chat, chatUserId, aadObjectId);
        if (identity == null) {
            return false;
        }
        Optional<AccountInfo> linked = chatAccounts.ofIdentity(chat.companyId(), chat.id(), identity);
        if (linked.isPresent()) {
            return principals.find(chat.companyId(), linked.get().principalId())
                .filter(PrincipalInfo::member)
                .map(principal -> principal.status() == com.flowtivo.tinfirm.principal.PrincipalStatus.SUSPENDED)
                .orElse(false);
        }
        if (!chat.teams() || chat.tenantId() == null) {
            return false;
        }
        return users.findByEntraObject(chat.tenantId(), identity)
            .flatMap(user -> principals.ofMember(chat.companyId(), user.id()))
            .map(principal -> principal.status() == com.flowtivo.tinfirm.principal.PrincipalStatus.SUSPENDED)
            .orElse(false);
    }

    private String name(UUID companyId, PrincipalInfo principal) {
        return principals.displayNames(companyId, List.of(principal.id())).getOrDefault(principal.id(), "a member");
    }

    // ---- Teams: automatically (row 6) ------------------------------------------------------------------------------

    /**
     * Links a Teams user by the Entra object id of the activity and the system's tenant, when exactly one user's last
     * Entra v2.0 login named both, an {@code ACTIVE} member without an account on the system. Both sides are told.
     */
    Optional<Member> automatic(ChatSystemInfo chat, String chatUserId, ChatAudience.Who who,
            ChatPoster.Target directMessage) {
        if (!chat.teams() || !who.member() || who.aadObjectId() == null || chat.tenantId() == null) {
            return Optional.empty();
        }
        String objectId = who.aadObjectId().toLowerCase(Locale.ROOT);
        Optional<Member> linked = system.inCompany(chat.companyId(), () -> {
            UserSummary user = users.findByEntraObject(chat.tenantId(), objectId).filter(UserSummary::active)
                .orElse(null);
            if (user == null) {
                return Optional.<Member>empty();
            }
            PrincipalInfo principal = principals.ofMember(chat.companyId(), user.id()).filter(PrincipalInfo::active)
                .orElse(null);
            if (principal == null || accounts.of(chat.companyId(), chat.id(), principal.id()).isPresent()
                    || chatAccounts.ofIdentity(chat.companyId(), chat.id(), objectId).isPresent()) {
                return Optional.<Member>empty();
            }
            chatAccounts.link(chat.companyId(), chat.id(), principal.id(), objectId, who.name(),
                    ChatAccounts.How.AUTOMATIC, chatUserId);
            notice(chat, principal.id(), chatUserId, who.name(), "AUTOMATIC");
            return Optional.of(new Member(principal.id(), user.id(), name(chat.companyId(), principal)));
        });
        linked.ifPresent(member -> told(chat, member, directMessage, 0));
        return linked;
    }

    // ---- the sign-in link (row 5) ----------------------------------------------------------------------------------

    /**
     * An unlinked member of the workspace wrote: the event waits for a link ({@code AWAITING_LINK}, at most 5); a new
     * link is posted unless one was in the last 10 minutes. Records the event itself.
     */
    ChatInbox.Outcome offer(ChatSystemInfo chat, ChatStore.EventRow event, ChatAudience.Who who,
            ChatPoster.Target directMessage) {
        String chatUserId = Objects.requireNonNull(event.sender());
        Instant now = time.now();
        String token = system.inCompany(chat.companyId(), () -> {
            store.lockUser(chat.id(), chatUserId);
            Optional<ChatStore.LinkRequest> pending = store.pendingLink(chat.id(), chatUserId, now);
            int held = store.held(chat.id(), chatUserId, "AWAITING_LINK").size();
            if (pending.isPresent()) {
                if (held < MAX_HELD) {
                    store.holdOnLink(pending.get().tokenHash(), event.id());
                    store.hold(event.id(), "AWAITING_LINK", pending.get().expiresAt());
                }
                else {
                    store.finish(event.id(), "IGNORED", "UNLINKED", now);
                }
                return null;
            }
            String fresh = token();
            Instant expires = now.plus(properties.linkLifetime());
            store.insertLink(new ChatStore.LinkRequest(hash(fresh), chat.companyId(), chat.id(), chatUserId,
                    who.name(), chat.workspace(), chat.teamName(), List.of(event.id()), now, expires, null, null));
            store.hold(event.id(), "AWAITING_LINK", expires);
            store.linkOffered(chat.id(), chatUserId, now);
            auditLog.record(AuditRecord.company(chat.companyId(), AuditAction.CHAT_LINK_OFFERED, Actor.SYSTEM)
                .target("system", chat.id())
                .detail("systemId", chat.id().toString())
                .detail("chatUserId", chatUserId)
                .build());
            return fresh;
        });
        if (token != null) {
            String url = trim(tinfirm.publicUrl().toString()) + "/link/" + chat.companyId() + "#" + token;
            String platform = chat.slack() ? "Slack" : "Teams";
            poster.post(chat.companyId(), chat.id(), directMessage, ChatCards.Message.of("To work with Tinfirm's "
                    + "agents here, link your " + platform + " account to your Tinfirm account. Your message waits "
                    + "until you do. The link works once, for " + properties.linkLifetime().toMinutes()
                    + " minutes, and only for you.")
                .withButtons(List.of(ChatCards.Button.link("Link my account", url))),
                    ChatPoster.Recorded.of("LINK", null));
        }
        return ChatInbox.Outcome.RECORDED;
    }

    /** What a read found in its transaction: the view, the system and the chat user's known Teams name. */
    private record Read(ChatLink view, ChatSystemInfo chat, String externalId, @Nullable String knownName) {
    }

    /**
     * {@code POST /chat-links/read}: what the link links, for a member of the company. Consumes nothing. Then, outside
     * any transaction, who the platform says the chat user is now (the security review of 2026-10-27, L3): Slack's
     * {@code users.info} with the bot's token, its real name and profile picture; on Teams the Entra display name the
     * Bot Framework's roster gave when the chat user was checked, which the user does not set.
     */
    ChatLink read(UUID companyId, String token) {
        Read read = inTransaction(() -> {
            guard.require(companyId, CompanyPermission.VIEW_COMPANY);
            caller(companyId);
            ChatStore.LinkRequest request = store.link(hash(token), false).orElseThrow(ChatLinks::notFound);
            requireUsable(request);
            ChatSystemInfo chat = chatSystems.find(companyId, request.systemId()).orElseThrow(ChatLinks::notFound);
            String known = store.user(chat.id(), request.externalId()).map(ChatStore.ChatUser::displayName)
                .orElse(null);
            return new Read(view(request, false, null), chat, request.externalId(), known);
        });
        return live(read);
    }

    /** The view with who the platform says the chat user is now; unchanged when it cannot be asked. */
    private ChatLink live(Read read) {
        ChatLink view = read.view().platformChecked(false);
        ChatSystemInfo chat = read.chat();
        if (chat.teams()) {
            return read.knownName() != null ? view.platformName(read.knownName()).platformChecked(true) : view;
        }
        try {
            ChatBot.Credential credential = bot.credential(chat.companyId(), chat.id()).orElse(null);
            if (credential == null) {
                return view;
            }
            CallResult<ChatCalls.SlackUser> found = calls.slackUser(chat.system(), credential.secret(),
                    read.externalId());
            if (found.failure() != null) {
                return view;
            }
            ChatCalls.SlackUser user = Objects.requireNonNull(found.value());
            String avatar = null;
            if (user.avatarUrl() != null) {
                CallResult<String> picture = calls.slackAvatar(chat.system(), user.avatarUrl());
                avatar = picture.failure() == null ? picture.value() : null;
            }
            return view.platformName(user.realName() != null ? user.realName() : user.name())
                .avatar(avatar)
                .platformChecked(true);
        }
        catch (RuntimeException ex) {
            log.info("Slack could not be asked who the chat user of a sign-in link is ({})",
                    ex.getClass().getSimpleName());
            return view;
        }
    }

    /** What a confirmation did: linked, or refused with the problem it answers after its commit. */
    private record Confirmed(@Nullable ChatLink link, @Nullable ApiException refusal, @Nullable Member member,
            @Nullable ChatSystemInfo chat, @Nullable Map<String, Object> dmRef, int dropped) {
    }

    /**
     * {@code POST /chat-links/confirm}: in one transaction the request is consumed whatever the outcome, and linked
     * when the chat user is still a member of the workspace, nobody's yet, and the caller holds no other identity on
     * the system. A refusal answers after the consumption committed.
     */
    ChatLink confirm(UUID companyId, String token) {
        Confirmed confirmed = inTransaction(() -> {
            guard.require(companyId, CompanyPermission.VIEW_COMPANY);
            PrincipalInfo caller = caller(companyId);
            ChatStore.LinkRequest request = store.link(hash(token), true).orElseThrow(ChatLinks::notFound);
            requireUsable(request);
            ChatSystemInfo chat = chatSystems.find(companyId, request.systemId()).orElseThrow(ChatLinks::notFound);
            ChatStore.ChatUser chatUser = store.user(chat.id(), request.externalId()).orElse(null);
            UUID userId = CurrentActor.requireUser().id();
            Instant now = time.now();
            String identity = chatUser != null ? identity(chat, chatUser.externalId(), chatUser.aadObjectId()) : null;
            ApiException refusal = null;
            String reason = null;
            if (chatUser == null || !"MEMBER".equals(chatUser.audience()) || identity == null) {
                // A guest, another organisation's user, a bot: never linked (§5.4); to the caller, nothing to link.
                reason = "NOT_MEMBER";
                refusal = ApiException.notFound("This chat user may not be linked: only members of the workspace are");
            }
            else if (chatAccounts.ofIdentity(companyId, chat.id(), identity).isPresent()) {
                reason = "IDENTITY_IN_USE";
                refusal = ApiException.conflict(ProblemCode.IDENTITY_IN_USE, "This chat user is linked to a member "
                        + "already");
            }
            else if (accounts.of(companyId, chat.id(), caller.id()).isPresent()) {
                reason = "ALREADY_LINKED";
                refusal = ApiException.conflict(ProblemCode.ALREADY_LINKED, "You are linked to another chat user of "
                        + "this system already: unlink it in My accounts first");
            }
            store.consumeLink(request.tokenHash(), userId, refusal == null ? "LINKED" : "REFUSED", reason, now);
            if (refusal != null) {
                auditLog.record(AuditRecord.company(companyId, AuditAction.CHAT_LINK_REFUSED, CurrentActor.actor())
                    .target("system", chat.id())
                    .detail("systemId", chat.id().toString())
                    .detail("chatUserId", request.externalId())
                    .detail("reason", reason)
                    .build());
                return new Confirmed(null, refusal, null, null, null, 0);
            }
            AccountInfo account = chatAccounts.link(companyId, chat.id(), caller.id(), Objects.requireNonNull(identity),
                    chatUser.displayName(), ChatAccounts.How.SIGN_IN_LINK, request.externalId());
            notice(chat, caller.id(), request.externalId(), chatUser.displayName() != null ? chatUser.displayName()
                    : request.displayName(), "SIGN_IN_LINK");
            // Not released (the security review of 2026-10-27, L3): whoever confirmed a forwarded link may not be who
            // wrote them, so they would run as someone else. Dropped, and the chat user is asked to send them again.
            int dropped = 0;
            for (ChatStore.EventRow held : store.held(chat.id(), request.externalId(), "AWAITING_LINK")) {
                if (request.heldEventIds().contains(held.id())) {
                    store.finish(held.id(), "IGNORED", "UNLINKED", now);
                    dropped++;
                }
            }
            Member member = new Member(caller.id(), userId, name(companyId, caller));
            return new Confirmed(view(request, true, account.id()), null, member, chat, chatUser.dmRef(),
                    dropped);
        });
        if (confirmed.refusal() != null) {
            throw confirmed.refusal();
        }
        ChatSystemInfo chat = Objects.requireNonNull(confirmed.chat());
        ChatPoster.Target target = target(confirmed.dmRef());
        if (target != null) {
            Member member = Objects.requireNonNull(confirmed.member());
            int dropped = confirmed.dropped();
            Thread.ofVirtual().name("chat-link-told").start(() -> com.flowtivo.tinfirm.shared.security.SystemContext
                .runInCompany(companyId, () -> told(chat, member, target, dropped)));
        }
        return Objects.requireNonNull(confirmed.link());
    }

    /**
     * A member's own Slack sign-in linked them (§5.4, open point 13): their new {@code OAUTH} account on a Slack system
     * is their link to that Slack user. After the commit, in a transaction of its own: their inbox is told, with its
     * unlink, and the direct messages that waited for the chat user's link are taken now. A sign-in that replaced
     * their {@code LINK} of the same user ({@code AccountReconnected}) links nothing new.
     */
    @TransactionalEventListener
    void accountConnected(AccountEvents.AccountConnected event) {
        UUID companyId = event.companyId();
        Thread.ofVirtual().name("chat-link-signed-in").start(() -> {
            try {
                signedIn(companyId, event);
            }
            catch (RuntimeException ex) {
                log.warn("The Slack sign-in link of the account {} was not told ({})", event.accountId(),
                        ex.getClass().getSimpleName());
            }
        });
    }

    private void signedIn(UUID companyId, AccountEvents.AccountConnected event) {
        system.runInCompany(companyId, () -> {
            AccountInfo account = accounts.find(companyId, event.accountId()).orElse(null);
            String identity = account != null ? account.externalId() : null;
            if (account == null || identity == null || account.kind() != AccountInfo.Kind.OAUTH
                    || account.principalKind() != PrincipalKind.MEMBER) {
                return;
            }
            ChatSystemInfo chat = chatSystems.find(companyId, event.systemId()).filter(ChatSystemInfo::slack)
                .orElse(null);
            if (chat == null) {
                return;
            }
            notice(chat, account.principalId(), identity, store.user(chat.id(), identity)
                .map(ChatStore.ChatUser::displayName)
                .orElse(null), "SLACK_SIGN_IN");
            Instant now = time.now();
            for (ChatStore.EventRow held : store.held(chat.id(), identity, "AWAITING_LINK")) {
                store.release(held.id(), now);
            }
        });
    }

    /**
     * The chat side of a link: "Linked to {member}", with the Owner notice (row 18), and, for a sign-in link, that the
     * messages that waited were not sent.
     */
    private void told(ChatSystemInfo chat, Member member, ChatPoster.Target directMessage, int dropped) {
        String company = companies.nameOf(chat.companyId()).orElse("your company");
        poster.post(chat.companyId(), chat.id(), directMessage, ChatCards.Message.of("Linked to " + member.name()
                + " in Tinfirm. " + OWNER_NOTICE.formatted(company) + dropped(dropped)),
                ChatPoster.Recorded.of("NOTICE", null));
    }

    /** What the direct message says of the messages a sign-in link held: not sent, to send again. */
    static String dropped(int count) {
        if (count == 0) {
            return "";
        }
        return count == 1 ? " The message you wrote before linking was not sent: send it again."
                : " The " + count + " messages you wrote before linking were not sent: send them again.";
    }

    /**
     * The member's side of a link: an item of their inbox for 7 days, with its unlink.
     *
     * @param chatUserName the chat user's display name as the platform last said it; null when it said none
     */
    private void notice(ChatSystemInfo chat, UUID principalId, String chatUserId, @Nullable String chatUserName,
            String how) {
        Instant now = time.now();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("chatUserId", chatUserId);
        details.put("chatUserName", chatUserName);
        details.put("workspaceId", chat.workspace());
        details.put("workspaceName", chat.slack() ? chat.teamName() : null);
        details.put("platform", chat.slack() ? "Slack" : "Teams");
        details.put("linkedAt", now.toString());
        details.put("how", how);
        store.notice(Ids.newId(), chat.companyId(), chat.id(), principalId, "LINKED", details, now,
                now.plus(NOTICE_KEPT));
    }

    /** The direct message a chat user's row names; null when it names none. */
    static ChatPoster.@Nullable Target target(@Nullable Map<String, Object> dmRef) {
        if (dmRef == null) {
            return null;
        }
        Object channel = dmRef.get("channel");
        if (!(channel instanceof String id)) {
            return null;
        }
        Object serviceUrl = dmRef.get("serviceUrl");
        return new ChatPoster.Target(id, null, serviceUrl instanceof String url ? url : null);
    }

    private ChatLink view(ChatStore.LinkRequest request, boolean linked, @Nullable UUID accountId) {
        ChatSystemInfo chat = chatSystems.find(request.companyId(), request.systemId())
            .orElseThrow(ChatLinks::notFound);
        String chatUserId = request.externalId();
        if (chat.teams()) {
            chatUserId = store.user(chat.id(), request.externalId())
                .map(ChatStore.ChatUser::aadObjectId)
                .filter(Objects::nonNull)
                .orElse(request.externalId());
        }
        String company = companies.nameOf(request.companyId()).orElse("this company");
        return new ChatLink(chat.id(), SystemKind.fromValue(chat.kind().name()), chat.name(), chatUserId,
                request.createdAt().atOffset(ZoneOffset.UTC), request.expiresAt().atOffset(ZoneOffset.UTC))
            .displayName(request.displayName())
            .workspaceId(request.teamId())
            .workspaceName(request.teamName())
            .linked(linked)
            .accountId(accountId)
            .ownerNotice(OWNER_NOTICE.formatted(company))
            .waitingMessages(request.heldEventIds().size());
    }

    private void requireUsable(ChatStore.LinkRequest request) {
        if (request.consumedAt() != null) {
            throw ApiException.conflict(ProblemCode.CHAT_LINK_USED, "This link was used already");
        }
        if (!request.expiresAt().isAfter(time.now())) {
            throw new ApiException(HttpStatus.GONE, ProblemCode.CHAT_LINK_EXPIRED, "This link expired: write to the "
                    + "bot again for a new one");
        }
    }

    private PrincipalInfo caller(UUID companyId) {
        UUID userId = CurrentActor.requireUser().id();
        return principals.ofMember(companyId, userId).filter(PrincipalInfo::active).orElseThrow(ChatLinks::notFound);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    private static ApiException notFound() {
        return ApiException.notFound("Link not found");
    }

    /** 32 random bytes, base64url without padding. */
    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.strip().getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** A time as the link's page names it. */
    static String clock(Instant at) {
        return DateTimeFormatter.ofPattern("HH:mm 'UTC'").format(at.atOffset(ZoneOffset.UTC));
    }
}
