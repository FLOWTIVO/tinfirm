package com.flowtivo.tinfirm.system.internal;

import java.net.URI;
import java.net.http.HttpRequest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ChatSystemDetails;
import com.flowtivo.tinfirm.shared.api.model.ExternalSystem;
import com.flowtivo.tinfirm.shared.api.model.ExternalSystemPage;
import com.flowtivo.tinfirm.shared.api.model.SystemAppRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemLastTest;
import com.flowtivo.tinfirm.shared.api.model.SystemSettings;
import com.flowtivo.tinfirm.shared.api.model.SystemTestResult;
import com.flowtivo.tinfirm.shared.api.model.SystemUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.SlackSystemSettings;
import com.flowtivo.tinfirm.shared.api.model.TeamsSystemSettings;
import com.flowtivo.tinfirm.shared.api.model.WebhookMapping;
import com.flowtivo.tinfirm.shared.api.model.WebhookSystemDetails;
import com.flowtivo.tinfirm.shared.api.model.WebhookSystemSettings;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystemTests;
import com.flowtivo.tinfirm.system.SystemAccounts;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.WebhookLimits;
import com.flowtivo.tinfirm.system.WebhookSystems;
import com.flowtivo.tinfirm.system.internal.http.ChatEndpoints;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.http.OutboundProperties;
import com.flowtivo.tinfirm.system.internal.mcp.McpClient;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthClient;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthDiscovery;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthProfile;
import com.flowtivo.tinfirm.vault.SecretWriter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Systems (the package 1 contract, §3.3, §6 and §10): registering, changing and removing them, their apps, testing
 * them, and the company's switch of personal MCP servers. Admins manage company systems; any member registers and
 * manages their own personal MCP servers while the company allows them. A system's kind and base URL never change. A
 * removal disconnects every account on it first ({@link SystemAccounts#systemDeleting}). Every change is audited; a
 * removal and the switch are announced ({@link SystemEvents}). What waits on the system (the app's discovery and
 * registration, a new MCP server's included, the test) runs outside any transaction, between a transaction that
 * authorises the caller and one that stores the outcome.
 */
@Service
class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);

    static final int MAX_NAME = 100;

    static final String TARGET = "system";

    /** A tracker's settings' poll interval in seconds (the package 4 contract, §5.4; {@link SystemPollsImpl}). */
    static final String POLL_INTERVAL = "pollIntervalSeconds";

    /** The secret name of a company system's client secret, which the vault lists as managed. */
    static final String CLIENT_SECRET_NAME = "SYSTEM_OAUTH_CLIENT_SECRET";

    /** The secret name of a personal server's client secret, which its owner owns. */
    static final String OWNED_CLIENT_SECRET_NAME = "PERSONAL_SYSTEM_OAUTH_CLIENT_SECRET";

    private static final Pattern HANDLE = Pattern.compile("^[a-z][a-z0-9_]{0,19}$");

    /** The most characters a handle has ({@code HANDLE}). */
    private static final int HANDLE_LENGTH = 20;

    /** A header a token may go in: a token of RFC 9110, not one Tinfirm or the transport sets. */
    private static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private static final Set<String> RESERVED_HEADERS = Set.of("host", "content-type", "content-length", "accept",
            "connection", "transfer-encoding", "user-agent", "mcp-session-id", "mcp-protocol-version", "upgrade",
            "expect", "te", "trailer", "keep-alive", "proxy-authorization", "cookie");

    private static final String NAME_INDEX = "external_system_name_uq";

    private static final String CHAT_WORKSPACE_INDEX = "external_system_chat_workspace_uq";

    /** A GUID: an Entra tenant's id, an Azure Bot's Microsoft App ID. */
    private static final Pattern GUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final SystemStore store;

    private final SystemAccess access;

    private final SystemClients clients;

    private final CompanyAccessGuard guard;

    private final Principals principals;

    private final SecretWriter secrets;

    private final SystemAccounts accounts;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final SystemMapper mapper;

    private final TransactionTemplate transactions;

    private final TimeSource time;

    private final ChatSystemsImpl chatSystems;

    private final ChatEndpoints chatEndpoints;

    private final TinfirmProperties tinfirm;

    private final ObjectProvider<ChatSystemTests> chatTests;

    private final WebhookSystemsImpl webhookSystems;

    private final ObjectProvider<WebhookLimits> webhookLimits;

    /** The operator's internal hosts, which a system's view names ({@code internalHost}). */
    private final OutboundProperties outbound;

    SystemService(SystemStore store, SystemAccess access, SystemClients clients, CompanyAccessGuard guard,
            Principals principals, SecretWriter secrets, SystemAccounts accounts, AuditLog auditLog,
            ApplicationEventPublisher events, SystemMapper mapper, PlatformTransactionManager transactionManager,
            TimeSource time, ChatSystemsImpl chatSystems, ChatEndpoints chatEndpoints, TinfirmProperties tinfirm,
            ObjectProvider<ChatSystemTests> chatTests, WebhookSystemsImpl webhookSystems,
            ObjectProvider<WebhookLimits> webhookLimits, OutboundProperties outbound) {
        this.outbound = outbound;
        this.webhookSystems = webhookSystems;
        this.webhookLimits = webhookLimits;
        this.store = store;
        this.access = access;
        this.clients = clients;
        this.guard = guard;
        this.principals = principals;
        this.secrets = secrets;
        this.accounts = accounts;
        this.auditLog = auditLog;
        this.events = events;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.time = time;
        this.chatSystems = chatSystems;
        this.chatEndpoints = chatEndpoints;
        this.tinfirm = tinfirm;
        this.chatTests = chatTests;
    }

    /** The caller and the system they manage, as the transaction before a call to the system found them. */
    record Managed(SystemAccess.Caller caller, SystemStore.SystemRow system) {
    }

    /** Authorises the caller to manage the system, in a transaction of its own before a call to it. */
    Managed managed(UUID companyId, UUID systemId) {
        return inTransaction(() -> {
            SystemAccess.Caller caller = access.caller(companyId);
            return new Managed(caller, access.managed(caller, systemId));
        });
    }

    /** Work in a transaction of its own, in the tenant context the request entered. */
    <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    @Transactional(readOnly = true)
    public ExternalSystemPage list(UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        SystemAccess.Caller caller = access.caller(companyId);
        PageRequest request = PageRequest.of(limit, cursor);
        List<SystemStore.SystemRow> rows = request.firstPage()
                ? store.page(companyId, caller.principalId(), null, null, request.fetchSize())
                : store.page(companyId, caller.principalId(), request.key(0, 2), request.uuidKey(1, 2),
                        request.fetchSize());
        Page<SystemStore.SystemRow> page = Page.of(rows, request, row -> List.of(row.name(), row.id().toString()));
        // The testers' names of the page's systems the caller manages, read at once.
        Map<UUID, String> testers = testers(caller, page.items());
        return new ExternalSystemPage(page.items().stream().map(row -> view(caller, row, testers)).toList())
            .nextCursor(page.nextCursor());
    }

    @Transactional(readOnly = true)
    public ExternalSystem get(UUID companyId, UUID systemId) {
        SystemAccess.Caller caller = access.caller(companyId);
        return view(caller, access.visible(caller, systemId));
    }

    /**
     * Registers a system. A new MCP server's app is then set up as its discovery finds it ({@link #setUpApp}), after
     * the transaction that registered the server, so that accounts sign in to it without anyone setting the app first.
     */
    public ExternalSystem create(UUID companyId, SystemCreateRequest input) {
        Created created = inTransaction(() -> register(companyId, input));
        if (created.system().kind() != SystemKind.MCP) {
            return created.view();
        }
        return setUpApp(created.caller(), created.system()).orElse(created.view());
    }

    /** A system just registered: who registered it, its row, and the system as they see it. */
    private record Created(SystemAccess.Caller caller, SystemStore.SystemRow system, ExternalSystem view) {
    }

    private Created register(UUID companyId, SystemCreateRequest input) {
        SystemAccess.Caller caller = access.caller(companyId);
        SystemKind kind = SystemKind.valueOf(input.getKind().name());
        boolean personal = Boolean.TRUE.equals(input.getPersonal());
        if (personal) {
            if (kind != SystemKind.MCP) {
                throw ApiException.badRequest(ProblemCode.PERSONAL_SYSTEM, "A personal system is an MCP server");
            }
            if (caller.principalId() == null) {
                throw ApiException.forbidden("Only members register personal MCP servers");
            }
            if (!store.personalSystemsOn(companyId)) {
                throw ApiException.conflict(ProblemCode.PERSONAL_SYSTEMS_OFF,
                        "The company switched personal MCP servers off");
            }
        }
        else {
            access.requireAdmin(caller);
        }
        if (!kind.registrable()) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID,
                    "A " + kind + " system is parked and cannot be registered");
        }
        refuseHandle(input.getHandle());
        String name = requireName(input.getName());
        String baseUrl = requireBaseUrl(kind, personal, input.getBaseUrl());
        ObjectNode settings = (ObjectNode) Json.tree(Map.of());
        String authHeader = authHeader(kind, input.getAuthHeader());
        if (authHeader != null) {
            settings.put("authHeader", authHeader);
        }
        chatSettings(kind, input.getSlack(), input.getTeams(), settings);
        webhookSettings(companyId, kind, input.getWebhook(), settings, true);
        Integer pollInterval = pollInterval(kind, input.getPollIntervalSeconds());
        if (pollInterval != null) {
            settings.put(POLL_INTERVAL, pollInterval);
        }
        // Registrations of the company wait for each other from here to the commit, so that two never take one handle.
        store.lockHandles(companyId);
        if (store.nameTaken(companyId, name, null)) {
            throw duplicateName();
        }
        String handle = freeHandle(companyId, name);
        Instant now = time.now();
        SystemStore.SystemRow row = new SystemStore.SystemRow(Ids.newId(), companyId, name, handle, kind, baseUrl,
                personal ? caller.principalId() : null, null, null, null, null, settings, "NEW", null, now, now, null,
                null, true);
        try {
            store.insert(row, caller.userId());
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicate(ex);
        }
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.SYSTEM_CREATED, CurrentActor.actor())
            .target(TARGET, row.id())
            .detail("kind", kind.name())
            .detail("name", name)
            .detail("handle", handle);
        for (String field : List.of("transport", "tenantId", "appId", "scheme")) {
            String value = Json.text(settings, field);
            if (value != null) {
                audit.detail(field, value);
            }
        }
        if (kind == SystemKind.WEBHOOK) {
            audit.detail("mapping", Json.asMap(settings.path("mapping")));
            if (settings.hasNonNull("maxPerMinute")) {
                audit.detail("maxPerMinute", settings.get("maxPerMinute").asInt());
            }
        }
        if (pollInterval != null) {
            audit.detail(POLL_INTERVAL, pollInterval);
        }
        if (personal) {
            audit.detail("ownerPrincipalId", Objects.requireNonNull(caller.principalId()).toString());
        }
        auditLog.record(audit.build());
        // A webhook's secret, generated now and answered once (the package 5 contract, §5.12).
        String secret = kind == SystemKind.WEBHOOK ? webhookSystems.generate(companyId, row.id(), handle) : null;
        ExternalSystem created = view(caller, kind == SystemKind.WEBHOOK
                ? store.find(companyId, row.id()).orElseThrow(SystemAccess::notFound) : row);
        if (secret != null && created.getWebhook() != null) {
            created.getWebhook().secret(secret);
        }
        return new Created(caller, row, created);
    }

    /**
     * Renames the system or changes its settings. An MCP server set to need no account (ADR-0071), or one that needs
     * none moved to a new endpoint (ADR-0079), is reached first at the address it will have, without a credential and
     * outside any transaction, between the one that authorises the caller and the one that stores the change; nothing
     * changes when it does not answer. The change checks again, the system locked, that such a server was reached at
     * that address: another change may have set it to need accounts, or moved it, meanwhile, and then the server is
     * reached again before this one is stored; and that it is not paused, since an admin may have paused it while it
     * was reached. A server moved to a new endpoint then sets its app up again, as a new one does ({@link #setUpApp}).
     */
    public ExternalSystem update(UUID companyId, UUID systemId, SystemUpdateRequest input) {
        @Nullable String reached = null;
        boolean mustReach = false;
        while (true) {
            if (reached == null && (Boolean.FALSE.equals(input.getAccountsRequired()) || input.getBaseUrl() != null)) {
                Managed managed = managed(companyId, systemId);
                SystemStore.SystemRow system = managed.system();
                String target = endpoint(system, input.getBaseUrl());
                if (system.kind() == SystemKind.MCP && target != null
                        && !accountsRequired(system, input.getAccountsRequired())
                        && (system.accountsRequired() || !target.equals(system.baseUrl()) || mustReach)) {
                    // A paused system is called for nothing, its check included (the package 6 contract, §5.4).
                    requireNotPaused(system);
                    requireAnswersWithoutCredential(system, target);
                    reached = target;
                }
            }
            @Nullable String checked = reached;
            Changed changed;
            try {
                changed = inTransaction(() -> change(companyId, systemId, input, checked));
            }
            catch (NotReached ex) {
                // Set to need accounts, or moved, since it was read: reached now at the address it will have, whatever
                // the next read says, then changed.
                reached = null;
                mustReach = true;
                continue;
            }
            SystemStore.SystemRow moved = changed.moved();
            return moved != null ? setUpApp(changed.caller(), moved).orElse(changed.view()) : changed.view();
        }
    }

    /** A change stored: who made it, the system as they see it, and its row when it moved to a new endpoint. */
    private record Changed(SystemAccess.Caller caller, ExternalSystem view, SystemStore.@Nullable SystemRow moved) {
    }

    /**
     * The endpoint the system has after the change: the one asked for, checked as at registration, else its own. Only
     * an MCP server's changes (ADR-0079): every other kind's base URL is fixed, a token being sent only where it was
     * connected, and the trackers' and code hosts' accounts name the site they were made on.
     */
    private @Nullable String endpoint(SystemStore.SystemRow system, @Nullable String requested) {
        if (requested == null) {
            return system.baseUrl();
        }
        if (system.kind() != SystemKind.MCP) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "A " + system.kind().label()
                    + " system's base URL never changes: register a new one at the other address");
        }
        return requireBaseUrl(SystemKind.MCP, system.personal(), requested);
    }

    /** A change that would set a server to need no account without having reached it: reach it first. */
    private static final class NotReached extends RuntimeException {

        NotReached() {
            super(null, null, false, false);
        }
    }

    /**
     * An MCP server answers {@code initialize} and {@code tools/list} at the endpoint without a credential, else
     * {@code 400 CREDENTIAL_REQUIRED} with its answer. A server that answers {@code initialize} and asks for a token
     * on {@code tools/list} is refused too: its tools are what runs call.
     */
    private void requireAnswersWithoutCredential(SystemStore.SystemRow system, String endpoint) {
        CallResult<McpClient.Listed> listed = clients.mcp(system)
            .listTools(new McpClient.Target(endpoint, null, null));
        if (listed.failure() == null) {
            return;
        }
        String answer = Objects.requireNonNull(listed.failureMessage());
        throw ApiException.badRequest(ProblemCode.CREDENTIAL_REQUIRED, listed.failure().kind()
                == CallResult.Kind.UNAUTHORIZED
                        ? system.name() + " asks for a credential, so it needs accounts: " + answer
                        : system.name() + " did not answer without a credential: " + answer);
    }

    /**
     * The change, in its transaction, the system locked. A new endpoint (ADR-0079) takes every account and credential
     * on the server with it, and the app found at the old address; the catalogue, its confirmed effects, the agents'
     * tools and the triggers stay, and a refresh with a new credential compares the tools there with them.
     *
     * @param reached the endpoint the server was reached at without a credential before this transaction; null when
     *                it was not
     */
    private Changed change(UUID companyId, UUID systemId, SystemUpdateRequest input, @Nullable String reached) {
        SystemAccess.Caller caller = access.caller(companyId);
        SystemStore.SystemRow current = access.managed(caller, systemId);
        refuseHandle(input.getHandle());
        boolean accountsRequired = accountsRequired(current, input.getAccountsRequired());
        String baseUrl = endpoint(current, input.getBaseUrl());
        boolean moved = !Objects.equals(baseUrl, current.baseUrl());
        if (!accountsRequired && (current.accountsRequired() || moved)) {
            if (reached == null || !reached.equals(baseUrl)) {
                throw new NotReached();
            }
            // Paused while it was reached: refused as before the call (the package 6 contract, §5.4).
            requireNotPaused(current);
        }
        String name = input.getName() != null ? requireName(input.getName()) : current.name();
        ObjectNode settings = current.settings().deepCopy() instanceof ObjectNode object ? object
                : (ObjectNode) Json.tree(Map.of());
        if (input.getAuthHeader() != null) {
            String header = input.getAuthHeader().isBlank() ? null : authHeader(current.kind(), input.getAuthHeader());
            if (header != null) {
                settings.put("authHeader", header);
            }
            else {
                settings.remove("authHeader");
            }
        }
        String transportBefore = Json.text(current.settings(), "transport");
        JsonNode webhookBefore = current.settings().deepCopy();
        webhookSettings(companyId, current.kind(), input.getWebhook(), settings, false);
        if (input.getSlack() != null) {
            if (current.kind() != SystemKind.SLACK) {
                throw ApiException.validation("slack is for a SLACK system only");
            }
            settings.put("transport", input.getSlack().getTransport().getValue());
        }
        Integer pollInterval = pollInterval(current.kind(), input.getPollIntervalSeconds());
        if (pollInterval != null) {
            settings.put(POLL_INTERVAL, pollInterval);
        }
        List<String> changed = new ArrayList<>();
        if (!name.equals(current.name())) {
            changed.add("name");
        }
        if (moved) {
            changed.add("baseUrl");
        }
        if (!java.util.Objects.equals(Json.text(settings, "authHeader"), current.authHeader())) {
            changed.add("authHeader");
        }
        if (accountsRequired != current.accountsRequired()) {
            changed.add("accountsRequired");
        }
        if (!java.util.Objects.equals(Json.text(settings, "transport"), transportBefore)) {
            changed.add("transport");
        }
        for (String field : List.of("scheme", "secretHeader", "mapping", "maxPerMinute", POLL_INTERVAL)) {
            if (!java.util.Objects.equals(settings.get(field), webhookBefore.get(field))) {
                changed.add(field);
            }
        }
        if (changed.isEmpty()) {
            return new Changed(caller, view(caller, current), null);
        }
        if (changed.contains("name") && store.nameTaken(companyId, name, systemId)) {
            throw duplicateName();
        }
        Instant now = time.now();
        try {
            store.update(companyId, systemId, name, settings, now);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicate(ex);
        }
        if (changed.contains("accountsRequired")) {
            store.updateAccountsRequired(companyId, systemId, accountsRequired, now);
        }
        int accountsRemoved = 0;
        if (moved) {
            // No token reaches the new address: the accounts and credentials go first, each audited, then the app.
            accountsRemoved = accounts.systemMoving(companyId, systemId);
            store.move(companyId, systemId, Objects.requireNonNull(baseUrl), now);
            deleteClientSecret(current, current.oauthClientSecretId());
        }
        AuditRecord.Builder changedAudit = AuditRecord.company(companyId, AuditAction.SYSTEM_CHANGED,
                CurrentActor.actor())
            .target(TARGET, systemId)
            .detail("changed", changed)
            .detail("name", name)
            .detail("handle", current.handle());
        if (changed.contains("accountsRequired")) {
            changedAudit.detail("accountsRequired", accountsRequired);
        }
        if (moved) {
            changedAudit.detail("baseUrl", Objects.requireNonNull(baseUrl))
                .detail("previousBaseUrl", Objects.requireNonNull(current.baseUrl()))
                .detail("accountsRemoved", accountsRemoved)
                .detail("appRemoved", current.oauthClientId() != null);
        }
        if (changed.contains("transport")) {
            changedAudit.detail("transport", Json.text(settings, "transport"));
        }
        if (changed.contains("scheme")) {
            changedAudit.detail("scheme", Json.text(settings, "scheme"));
        }
        if (changed.contains("mapping")) {
            changedAudit.detail("mapping", Json.asMap(settings.path("mapping")));
        }
        if (changed.contains("maxPerMinute")) {
            changedAudit.detail("maxPerMinute", settings.hasNonNull("maxPerMinute")
                    ? settings.get("maxPerMinute").asInt() : null);
        }
        if (changed.contains(POLL_INTERVAL)) {
            changedAudit.detail(POLL_INTERVAL, settings.get(POLL_INTERVAL).asInt());
        }
        auditLog.record(changedAudit.build());
        if (changed.contains("transport")) {
            events.publishEvent(new SystemEvents.ChatTransportChanged(companyId, systemId));
        }
        if (changed.contains("accountsRequired")) {
            events.publishEvent(new SystemEvents.AccountsRequiredChanged(companyId, systemId, accountsRequired));
        }
        if (moved) {
            events.publishEvent(new SystemEvents.SystemMoved(companyId, systemId));
        }
        SystemStore.SystemRow after = store.find(companyId, systemId).orElseThrow(SystemAccess::notFound);
        return new Changed(caller, view(caller, after), moved ? after : null);
    }

    /**
     * Whether the system needs accounts after the change: false only for an MCP server (ADR-0071), whose check ran
     * before this transaction; true for any kind.
     */
    private static boolean accountsRequired(SystemStore.SystemRow current, @Nullable Boolean requested) {
        if (requested == null) {
            return current.accountsRequired();
        }
        if (!requested && current.kind() != SystemKind.MCP) {
            throw ApiException.invalid("accountsRequired is false only for an MCP server", "accountsRequired");
        }
        return requested;
    }

    @Transactional
    public void delete(UUID companyId, UUID systemId) {
        SystemAccess.Caller caller = access.caller(companyId);
        remove(access.managed(caller, systemId), null);
    }

    /**
     * Removes the system, already locked: its accounts first, then its app's client secret, then the system with its
     * repositories and tools.
     *
     * @param cause what made the removal, when nobody asked for it ({@code principal.left})
     */
    void remove(SystemStore.SystemRow system, @Nullable String cause) {
        UUID companyId = system.companyId();
        accounts.systemDeleting(companyId, system.id());
        deleteClientSecret(system, system.oauthClientSecretId());
        if (system.kind().chat()) {
            chatSystems.deleteSecrets(companyId, system.id());
        }
        if (system.kind() == SystemKind.WEBHOOK) {
            webhookSystems.deleteSecrets(companyId, system.id());
        }
        store.delete(companyId, system.id());
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.SYSTEM_DELETED, CurrentActor.actor())
            .target(TARGET, system.id())
            .detail("kind", system.kind().name())
            .detail("name", system.name());
        if (cause != null) {
            audit.detail("cause", cause);
        }
        auditLog.record(audit.build());
        events.publishEvent(new SystemEvents.SystemDeleted(companyId, system.id()));
    }

    // ---- The app ---------------------------------------------------------------------------------------------------

    /**
     * Sets the app. An MCP server's discovery, and Tinfirm's registration at its authorization server, run between
     * the transaction that authorises the caller and the one that stores the app.
     */
    public ExternalSystem setApp(UUID companyId, UUID systemId, SystemAppRequest input) {
        Managed managed = managed(companyId, systemId);
        SystemAccess.Caller caller = managed.caller();
        SystemStore.SystemRow system = managed.system();
        if (!system.kind().oauth() || system.baseUrl() == null) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, system.name() + " is a "
                    + system.kind().label() + " system, which has no OAuth app");
        }
        String clientId = blankToNull(input.getClientId());
        String clientSecret = blankToNull(input.getClientSecret());
        String scopes = blankToNull(input.getScopes());
        JsonNode metadata = null;
        boolean registered = false;
        if (system.kind() == SystemKind.MCP) {
            McpApp discovered = mcpApp(system, clientId);
            metadata = discovered.metadata();
            if (discovered.clientId() != null) {
                clientId = discovered.clientId();
                if (discovered.registered()) {
                    clientSecret = discovered.clientSecret();
                    registered = true;
                }
            }
        }
        if (clientId == null) {
            throw ApiException.validation("clientId is required: register Tinfirm at the system with the callback URL "
                    + clients.redirectUri());
        }
        if (system.kind() == SystemKind.SLACK) {
            // The package 5 contract, open point 13: the Slack app's client, for history's user accounts; its user
            // scopes are fixed, and Slack's token endpoint takes only a confidential client.
            if (scopes != null) {
                throw ApiException.validation("A Slack app's user scopes are fixed: channels:history and "
                        + "groups:history, as its manifest asks for them");
            }
            if (clientSecret == null && (system.oauthClientSecretId() == null
                    || !clientId.equals(system.oauthClientId()))) {
                throw ApiException.validation("clientSecret is required: the Slack app's client secret, from its "
                        + "Basic Information page");
            }
        }
        if (clientId.length() > 500 || (clientSecret != null && clientSecret.length() > 2000)
                || (scopes != null && scopes.length() > 2000)) {
            throw ApiException.validation("clientId, clientSecret or scopes is too long");
        }
        App app = new App(clientId, clientSecret, scopes, metadata, registered,
                Boolean.TRUE.equals(input.getConfirmEndpointsChange()));
        return inTransaction(() -> storeApp(caller, systemId, app, false, system.baseUrl()));
    }

    /**
     * Sets a new MCP server's app up as its discovery finds it, as {@link #setApp} without a client id does: Tinfirm's
     * client metadata document where the server takes one, else a client Tinfirm registers (RFC 7591). A server that
     * needs no token, whose discovery fails, or that needs a client registered by hand gets no app: whoever manages it
     * sets one on its page, which tells why. Audited as {@code system.app_changed} with {@code automatic}.
     *
     * @return the system with its app; empty when it got none
     */
    private Optional<ExternalSystem> setUpApp(SystemAccess.Caller caller, SystemStore.SystemRow system) {
        try {
            McpApp discovered = mcpApp(system, null);
            if (discovered.clientId() == null) {
                log.info("The new MCP server {} needs a client registered by hand: it has no app yet", system.id());
                return Optional.empty();
            }
            App app = new App(discovered.clientId(), discovered.clientSecret(), null, discovered.metadata(),
                    discovered.registered(), false);
            return Optional.of(inTransaction(() -> storeApp(caller, system.id(), app, true, system.baseUrl())));
        }
        catch (ApiException ex) {
            // No OAuth, a failed discovery or registration, or the server removed meanwhile: it stays as registered.
            log.info("The new MCP server {} has no app: {}", system.id(), ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * An MCP server's app as its discovery finds it: the endpoints, and a client unless {@code clientId} is given:
     * Tinfirm's client metadata document where the server takes one, else a client Tinfirm registers (RFC 7591), else
     * none (the server needs a client registered by hand). The discovery and the registration wait on the server, so
     * this runs outside any transaction.
     *
     * @throws ApiException {@code OAUTH_NOT_AVAILABLE}: the server offers no OAuth, or Tinfirm could not register
     */
    private McpApp mcpApp(SystemStore.SystemRow system, @Nullable String clientId) {
        OAuthDiscovery.Outcome outcome = clients.discovery(system.personal())
            .discover(Objects.requireNonNull(system.baseUrl()));
        OAuthDiscovery.Discovered discovered = outcome.discovered();
        if (discovered == null) {
            throw ApiException.conflict(ProblemCode.OAUTH_NOT_AVAILABLE, outcome.error() != null
                    ? "The server's OAuth discovery failed: " + outcome.error()
                    : "The server answers without a token, so it offers no OAuth");
        }
        JsonNode metadata = metadata(discovered);
        if (clientId != null || (!discovered.cimdSupported() && discovered.registrationEndpoint() == null)) {
            return new McpApp(metadata, null, null, false);
        }
        if (discovered.cimdSupported()) {
            return new McpApp(metadata, clients.clientMetadataUrl(), null, false);
        }
        CallResult<OAuthClient.Registered> registration = clients.oauth(system)
            .register(Objects.requireNonNull(discovered.registrationEndpoint()), clients.redirectUri());
        if (registration.failure() != null) {
            throw ApiException.conflict(ProblemCode.OAUTH_NOT_AVAILABLE, "Tinfirm could not register at the server: "
                    + registration.failureMessage());
        }
        OAuthClient.Registered client = Objects.requireNonNull(registration.value());
        return new McpApp(metadata, client.clientId(), client.clientSecret(), true);
    }

    /**
     * An MCP server's discovered app: its endpoints, and the client the discovery set up when it set one up, with the
     * secret a registration issued, if any.
     */
    private record McpApp(JsonNode metadata, @Nullable String clientId, @Nullable String clientSecret,
            boolean registered) {

        @Override
        public String toString() {
            return "McpApp[clientId=" + clientId + ", clientSecret=" + (clientSecret != null ? "***" : "none") + "]";
        }
    }

    /**
     * An app to store: its client, the client secret when one was typed or issued, the scopes, the endpoints, whether
     * Tinfirm registered itself, and whether the caller confirmed that a change of the token or revocation endpoint
     * removes the OAuth accounts and credentials on the server ({@code confirmEndpointsChange}).
     */
    private record App(String clientId, @Nullable String clientSecret, @Nullable String scopes,
            @Nullable JsonNode metadata, boolean registered, boolean confirmEndpointsChange) {

        @Override
        public String toString() {
            return "App[clientId=" + clientId + ", clientSecret=" + (clientSecret != null ? "***" : "none") + "]";
        }
    }

    /**
     * Stores the app on the system as it is now, locked, with an audit. An MCP server moved to another endpoint since
     * its discovery ran (ADR-0079) does not take an app found at the one before, whose tokens are another server's: an
     * automatic setup leaves it to the move's own, and one asked for is {@code 409 OAUTH_NOT_AVAILABLE}.
     *
     * @param automatic    set up as the server was registered or moved ({@link #setUpApp}): an app someone set
     *                     meanwhile stays
     * @param discoveredAt the base URL the app was found at, before this transaction
     */
    private ExternalSystem storeApp(SystemAccess.Caller caller, UUID systemId, App app, boolean automatic,
            @Nullable String discoveredAt) {
        UUID companyId = caller.companyId();
        SystemStore.SystemRow system = access.relocked(caller, systemId);
        if (!Objects.equals(system.baseUrl(), discoveredAt)) {
            if (automatic) {
                return view(caller, system);
            }
            throw ApiException.conflict(ProblemCode.OAUTH_NOT_AVAILABLE, system.name() + "'s endpoint changed while "
                    + "its app was set up: set it up again");
        }
        if (automatic && system.oauthClientId() != null) {
            return view(caller, system);
        }
        int oauthRemoved = 0;
        List<String> endpointsChanged = system.kind() == SystemKind.MCP && app.metadata() != null
                ? changedEndpoints(system.oauthMetadata(), app.metadata()) : List.of();
        if (!endpointsChanged.isEmpty()) {
            SystemAccounts.OAuthOnSystem on = accounts.oauthOn(companyId, systemId);
            if (!on.none()) {
                if (automatic) {
                    return view(caller, system);
                }
                if (!app.confirmEndpointsChange()) {
                    throw endpointsChanged(system, app.metadata(), endpointsChanged, on);
                }
                // No refresh token, code or token to revoke reaches the new endpoints: the OAuth accounts and
                // credentials go first, each audited, with the pending sign-ins (ADR-0079's rule, M8).
                oauthRemoved = accounts.appEndpointsChanging(companyId, systemId);
            }
        }
        String clientId = app.clientId();
        String clientSecret = app.clientSecret();
        String scopes = app.scopes();
        UUID secretId = system.oauthClientSecretId();
        if (clientSecret != null) {
            secretId = storeClientSecret(system, secretId, clientSecret);
        }
        else if (!clientId.equals(system.oauthClientId())) {
            deleteClientSecret(system, secretId);
            secretId = null;
        }
        store.updateApp(companyId, systemId, clientId, secretId, scopes, app.metadata(), time.now());
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.SYSTEM_APP_CHANGED, CurrentActor.actor())
            .target(TARGET, systemId)
            .detail("clientId", clientId)
            .detail("clientSecret", secretId != null)
            .detail("scopes", scopes);
        if (app.registered()) {
            audit.detail("registered", true);
        }
        if (automatic) {
            audit.detail("automatic", true);
        }
        if (!endpointsChanged.isEmpty()) {
            audit.detail("endpointsChanged", endpointsChanged)
                .detail("tokenEndpoint", Json.text(app.metadata(), "tokenEndpoint"))
                .detail("previousTokenEndpoint", Json.text(system.oauthMetadata(), "tokenEndpoint"))
                .detail("revocationEndpoint", Json.text(app.metadata(), "revocationEndpoint"))
                .detail("previousRevocationEndpoint", Json.text(system.oauthMetadata(), "revocationEndpoint"))
                .detail("accountsRemoved", oauthRemoved);
        }
        auditLog.record(audit.build());
        return view(caller, store.find(companyId, systemId).orElseThrow(SystemAccess::notFound));
    }

    /**
     * Which of the endpoints a refresh token, a code or a token to revoke goes to ({@code tokenEndpoint},
     * {@code revocationEndpoint}) the new app names otherwise than the stored one; a server without stored endpoints
     * (no app, or one removed) counts as changed, since accounts may still hold tokens of the endpoints before.
     */
    static List<String> changedEndpoints(@Nullable JsonNode before, JsonNode after) {
        List<String> changed = new ArrayList<>();
        for (String field : List.of("tokenEndpoint", "revocationEndpoint")) {
            if (!Objects.equals(Json.text(before, field), Json.text(after, field))) {
                changed.add(field);
            }
        }
        return changed;
    }

    /**
     * {@code 409 SYSTEM_APP_ENDPOINTS_CHANGED}: the endpoints before and after, and what a confirmed save removes (the
     * number of members' own OAuth accounts, the OAuth credentials by name).
     */
    private static ApiException endpointsChanged(SystemStore.SystemRow system, JsonNode metadata, List<String> fields,
            SystemAccounts.OAuthOnSystem on) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changed", fields);
        details.put("tokenEndpoint", Json.text(metadata, "tokenEndpoint"));
        details.put("previousTokenEndpoint", Json.text(system.oauthMetadata(), "tokenEndpoint"));
        details.put("revocationEndpoint", Json.text(metadata, "revocationEndpoint"));
        details.put("previousRevocationEndpoint", Json.text(system.oauthMetadata(), "revocationEndpoint"));
        details.put("accounts", on.accounts());
        List<Map<String, Object>> credentials = new ArrayList<>();
        for (SystemAccounts.NamedCredential credential : on.credentials()) {
            Map<String, Object> named = new LinkedHashMap<>();
            named.put("id", credential.id().toString());
            named.put("name", credential.name());
            credentials.add(named);
        }
        details.put("credentials", credentials);
        int count = on.accounts() + on.credentials().size();
        return ApiException.conflict(ProblemCode.SYSTEM_APP_ENDPOINTS_CHANGED, system.name() + "'s sign-in now names "
                + "another " + (fields.size() == 2 ? "token and revocation endpoint" : fields.contains("tokenEndpoint")
                        ? "token endpoint" : "revocation endpoint")
                + ": the " + count + (count == 1 ? " account or credential" : " accounts and credentials")
                + " that signed in would send their tokens there. Save again to remove them and set the app, or "
                + "check the server's metadata first.", Map.of("details", details));
    }

    /**
     * Removes the app: its client, client secret, scopes and endpoints. The accounts on the system stay as they are:
     * an OAuth account whose grant expires can no longer be refreshed, and asks to be connected again then. Audited
     * as {@code system.app_changed} with {@code removed}; 404 when the system has no app.
     */
    @Transactional
    public void removeApp(UUID companyId, UUID systemId) {
        SystemAccess.Caller caller = access.caller(companyId);
        SystemStore.SystemRow system = access.managed(caller, systemId);
        String clientId = system.oauthClientId();
        if (clientId == null) {
            throw ApiException.notFound("The system has no app");
        }
        store.clearApp(companyId, systemId, time.now());
        deleteClientSecret(system, system.oauthClientSecretId());
        auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_APP_CHANGED, CurrentActor.actor())
            .target(TARGET, systemId)
            .detail("removed", true)
            .detail("clientId", clientId)
            .detail("clientSecret", false)
            .build());
    }

    private static JsonNode metadata(OAuthDiscovery.Discovered discovered) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("authorizationEndpoint", discovered.authorizationEndpoint());
        metadata.put("tokenEndpoint", discovered.tokenEndpoint());
        metadata.put("revocationEndpoint", discovered.revocationEndpoint());
        metadata.put("registrationEndpoint", discovered.registrationEndpoint());
        metadata.put("scopesSupported", discovered.scopesSupported());
        metadata.put("clientIdMetadataDocumentSupported", discovered.cimdSupported());
        return Json.tree(metadata);
    }

    /** Stores the client secret as a new version of the system's, or as a new secret when it has none. */
    private UUID storeClientSecret(SystemStore.SystemRow system, @Nullable UUID secretId, String value) {
        UUID companyId = system.companyId();
        UUID owner = ownerUserId(system);
        if (secretId != null) {
            boolean replaced = owner != null ? secrets.replaceOwned(companyId, owner, secretId, value)
                    : secrets.replace(companyId, secretId, value);
            if (replaced) {
                return secretId;
            }
        }
        String description = "The client secret of the app of the system " + system.handle();
        return owner != null ? secrets.createOwned(companyId, owner, OWNED_CLIENT_SECRET_NAME, description, value)
                : secrets.create(companyId, CLIENT_SECRET_NAME, description, value);
    }

    private void deleteClientSecret(SystemStore.SystemRow system, @Nullable UUID secretId) {
        if (secretId == null) {
            return;
        }
        UUID owner = ownerUserId(system);
        if (owner != null) {
            secrets.deleteOwned(system.companyId(), owner, secretId);
        }
        else {
            secrets.delete(system.companyId(), secretId);
        }
    }

    private @Nullable UUID ownerUserId(SystemStore.SystemRow system) {
        UUID owner = system.ownerPrincipalId();
        if (owner == null) {
            return null;
        }
        return principals.find(system.companyId(), owner)
            .map(principal -> Objects.requireNonNull(principal.userId()))
            .orElseThrow(() -> new IllegalStateException("A personal system without its owner's principal"));
    }

    // ---- Test ------------------------------------------------------------------------------------------------------

    /**
     * Reaches the system: with an account (the credential named, the caller's own {@code OK} account, the system's
     * only usable credential: {@link #reach}) its "who am I", else a request without a credential; the outcome becomes
     * the system's status. The calls run outside any transaction, between the one that authorises the caller and the
     * one that stores the status.
     */
    public SystemTestResult test(UUID companyId, UUID systemId, @Nullable UUID credentialId) {
        Managed managed = managed(companyId, systemId);
        SystemAccess.Caller caller = managed.caller();
        SystemStore.SystemRow system = managed.system();
        requireNotPaused(system);
        if (system.kind().chat()) {
            return testChat(caller, system);
        }
        if (system.baseUrl() == null) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, system.name() + " is a "
                    + system.kind().label() + " system, which has nothing to reach");
        }
        Reach reach = reach(managed, credentialId, "system-test:" + systemId);
        if (reach.unusable()) {
            // Nothing was sent: the system's status stays as it is.
            return new SystemTestResult(false, false, false, UNUSABLE_CREDENTIAL).credentialId(reach.credentialId())
                .credentialName(reach.credentialName());
        }
        SystemTestResult result;
        if (reach.credential() != null) {
            requireStill(system, reach.credential());
            CallResult<SystemCalls.Identity> identity = clients.whoAmI(system, reach.credential().credential(),
                    reach.credential().apiBaseUrl());
            result = identity.failure() != null
                    ? new SystemTestResult(false, true, identity.failure().kind() == CallResult.Kind.UNAUTHORIZED,
                            (reach.own() ? "Your account" : "The credential") + " was not accepted: "
                                    + identity.failureMessage())
                    : new SystemTestResult(true, true, false, reach.own() ? "Reached with your own account"
                            : "Reached with the credential")
                        .login(Objects.requireNonNull(identity.value()).login());
            result.credentialId(reach.credentialId()).credentialName(reach.credentialName());
        }
        else {
            result = reachWithoutCredential(system);
        }
        SystemTestResult outcome = result;
        // The account the test revealed, not the one held now: it may have been replaced while the test ran, and it
        // is named only while it still exists.
        UUID revealed = reach.credential() != null ? reach.credential().accountId() : null;
        return inTransaction(() -> {
            access.relocked(caller, systemId);
            UUID accountId = revealed != null && (accounts.holderOf(companyId, revealed).isPresent()
                    || accounts.credentialOf(companyId, systemId, revealed)) ? revealed : null;
            recordTest(caller, systemId, outcome.getOk(), outcome.getMessage(), outcome.getWithAccount(), accountId);
            return outcome;
        });
    }

    /** What a test or a refresh says when the credential named cannot be used. */
    static final String UNUSABLE_CREDENTIAL = "The credential is disabled, or must be signed in again";

    /**
     * The credential a test or a refresh revealed goes only to the endpoint it was revealed for (ADR-0079): the
     * row read before the reveal must still have the credential's kind and base URL (a credential stored at a new
     * endpoint after the row was read is not sent to the old one), else {@code 409 SYSTEM_MOVED} with nothing sent.
     */
    static void requireStill(SystemStore.SystemRow system, SystemAccounts.OwnCredential credential) {
        if (!credential.endpoint().is(system.kind(), system.baseUrl())) {
            throw ApiException.conflict(ProblemCode.SYSTEM_MOVED, "The endpoint of " + system.name() + " changed "
                    + "while its credential was revealed: nothing was sent; try again");
        }
    }

    /**
     * What an admin's test or refresh reaches the system with (ADR-0072).
     *
     * @param credential   the account revealed; null for none
     * @param own          whether it is the caller's own account
     * @param credentialId   the system's credential named or taken; null for the caller's own account or none
     * @param credentialName its name
     * @param unusable       the credential named is disabled or not {@code OK}: nothing was revealed
     */
    record Reach(SystemAccounts.@Nullable OwnCredential credential, boolean own, @Nullable UUID credentialId,
            @Nullable String credentialName, boolean unusable) {
    }

    /**
     * The account a test or a refresh reaches the system with: the credential named, which must be one of the system's
     * ({@code 404} otherwise); else the caller's own {@code OK} account; else the system's only enabled {@code OK}
     * credential; else none. Outside any transaction, since an expiring OAuth grant is refreshed first; the caller was
     * authorised before.
     */
    Reach reach(Managed managed, @Nullable UUID credentialId, String purpose) {
        UUID companyId = managed.caller().companyId();
        UUID systemId = managed.system().id();
        if (credentialId != null) {
            String name = inTransaction(() -> accounts.credentialName(companyId, systemId, credentialId))
                .orElseThrow(() -> ApiException.notFound("Credential not found"));
            Optional<SystemAccounts.OwnCredential> revealed = accounts.revealCredential(companyId, credentialId,
                    purpose);
            return new Reach(revealed.orElse(null), false, credentialId, name, revealed.isEmpty());
        }
        Optional<SystemAccounts.OwnCredential> own = ownCredential(managed.caller(), systemId, purpose);
        if (own.isPresent()) {
            return new Reach(own.get(), true, null, null, false);
        }
        Optional<UUID> only = inTransaction(() -> accounts.onlyUsableCredential(companyId, systemId));
        if (only.isPresent()) {
            Optional<SystemAccounts.OwnCredential> revealed = accounts.revealCredential(companyId, only.get(),
                    purpose);
            if (revealed.isPresent()) {
                String name = inTransaction(() -> accounts.credentialName(companyId, systemId, only.get()))
                    .orElse(null);
                return new Reach(revealed.get(), false, only.get(), name, false);
            }
        }
        return new Reach(null, false, null, null, false);
    }

    /**
     * Stores a test's outcome (V043): the system's status and last error, and its last test, when and by whom, with
     * the tester's own account it used; in the transaction after the test, the system locked again.
     */
    private void recordTest(SystemAccess.Caller caller, UUID systemId, boolean ok, @Nullable String message,
            boolean withAccount, @Nullable UUID accountId) {
        String error = ok ? null : Json.cut(message == null || message.isBlank() ? "The test failed" : message, 500);
        store.recordTest(caller.companyId(), systemId, new SystemStore.LastTest(time.now(), ok, error, withAccount,
                caller.principalId(), accountId));
    }

    /**
     * A chat system's test (the package 5 contract, §5.1): the bot, the transport and the stored secrets, by the chat
     * module, outside any transaction; the outcome becomes the system's status.
     */
    private SystemTestResult testChat(SystemAccess.Caller caller, SystemStore.SystemRow system) {
        ChatSystemTests tests = chatTests.getIfAvailable();
        if (tests == null) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "Chat systems are not tested here");
        }
        ChatSystemTests.Outcome outcome = tests.test(system.companyId(), system.id());
        SystemTestResult result = new SystemTestResult(outcome.ok(), outcome.withBot(), false, outcome.message());
        return inTransaction(() -> {
            access.relocked(caller, system.id());
            // The bot is a service identity's account, never the tester's own: the last test names no account.
            recordTest(caller, system.id(), outcome.ok(), outcome.message(), outcome.withBot(), null);
            return result;
        });
    }

    /**
     * The caller's own {@code OK} account on the system with its credential, for a call that acts as them; outside
     * any transaction, since an expiring OAuth grant is refreshed first.
     */
    private Optional<SystemAccounts.OwnCredential> ownCredential(SystemAccess.Caller caller, UUID systemId,
            String purpose) {
        return accounts.ownCredential(caller.companyId(), systemId, caller.userId(), purpose);
    }

    private SystemTestResult reachWithoutCredential(SystemStore.SystemRow system) {
        String base = Objects.requireNonNull(system.baseUrl());
        if (system.kind() == SystemKind.MCP) {
            CallResult<Boolean> probe = clients.mcp(system).probe(new McpClient.Target(base, null, null));
            if (probe.failure() == null) {
                return new SystemTestResult(true, false, false, "The server answers without a token");
            }
            if (probe.failure().kind() == CallResult.Kind.UNAUTHORIZED) {
                return new SystemTestResult(true, false, true, "The server answers and asks for a token");
            }
            return new SystemTestResult(false, false, false, Objects.requireNonNull(probe.failureMessage()));
        }
        String path = switch (system.kind()) {
            case JIRA_DC -> "/rest/api/2/serverInfo";
            case JIRA_CLOUD -> "/rest/api/3/serverInfo";
            case GITHUB -> "/meta";
            case GITLAB -> base.endsWith("/api/v4") ? "/version" : "/api/v4/version";
            default -> "";
        };
        OutboundHttp http = clients.http(system);
        HttpRequest request = http.request(base + path).header("Accept", "application/json").GET().build();
        CallResult<OutboundHttp.Response> sent = http.send(base, request);
        if (sent.failure() != null) {
            return new SystemTestResult(false, false, false, Objects.requireNonNull(sent.failureMessage()));
        }
        int status = Objects.requireNonNull(sent.value()).status();
        return status < 500 && (status < 300 || status >= 400)
                ? new SystemTestResult(true, false, status == 401, "Reached: the system answered " + status)
                : new SystemTestResult(false, false, false, "The system answered " + status);
    }

    // ---- The company's setting --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SystemSettings settings(UUID companyId) {
        guard.require(companyId, CompanyPermission.VIEW_SYSTEMS);
        return new SystemSettings(store.personalSystemsOn(companyId));
    }

    @Transactional
    public SystemSettings replaceSettings(UUID companyId, SystemSettings input) {
        guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
        boolean on = Boolean.TRUE.equals(input.getPersonalMcpServers());
        if (store.personalSystemsOn(companyId) != on) {
            Actor actor = CurrentActor.actor();
            store.setPersonalSystems(companyId, on, time.now(), actor.type() == ActorType.USER ? actor.id() : null);
            auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_SETTINGS_CHANGED, actor)
                .detail("personalMcpServers", on)
                .build());
            events.publishEvent(new SystemEvents.PersonalSystemsSwitched(companyId, on));
        }
        return new SystemSettings(on);
    }

    // ---- Rules -----------------------------------------------------------------------------------------------------

    private static String requireName(String requested) {
        String name = requested.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw ApiException.validation("name must be 1 to " + MAX_NAME + " characters");
        }
        return name;
    }

    /**
     * A handle is never chosen (CONVENTIONS §15.2): Tinfirm makes it from the name at registration,
     * and it never changes, since it names the system's tools in open conversations and in agents' instructions. A
     * request that names one is refused, so that no client believes it chose or renamed one.
     */
    private static void refuseHandle(@Nullable String requested) {
        if (requested != null) {
            throw ApiException.invalid("handle is made from the name when the system is registered and never "
                    + "changes: leave it out", "handle");
        }
    }

    /**
     * A handle made from the name, with {@code _2}, {@code _3}, ... when the company has it already; under
     * {@link SystemStore#lockHandles}, so that no other registration takes it before the commit.
     */
    private String freeHandle(UUID companyId, String name) {
        String handle = freeHandle(name, candidate -> store.handleTaken(companyId, candidate, null));
        if (!HANDLE.matcher(handle).matches()) {
            throw new IllegalStateException("A handle made from a name does not fit the handle's form");
        }
        return handle;
    }

    /**
     * A handle made from the name that {@code taken} does not refuse: the name's slug, cut to 17 characters when
     * longer than 20, then that base with {@code _2}, {@code _3}, ... The base is cut further by what a suffix adds
     * beyond 3 characters ({@code _100} on), so that every handle fits {@code HANDLE}'s 20 characters.
     */
    static String freeHandle(String name, Predicate<String> taken) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) {
            slug = "s" + (slug.isEmpty() ? "" : "_" + slug);
        }
        String handle = slug.length() <= HANDLE_LENGTH ? slug : cut(slug, HANDLE_LENGTH - 3);
        for (int suffix = 2; taken.test(handle); suffix++) {
            String tail = "_" + suffix;
            handle = cut(slug, HANDLE_LENGTH - Math.max(3, tail.length())) + tail;
        }
        return handle;
    }

    /** The slug's first characters, at most {@code length}, without the underscores they end with. */
    private static String cut(String slug, int length) {
        return slug.length() > length ? slug.substring(0, length).replaceAll("_+$", "") : slug;
    }

    private @Nullable String requireBaseUrl(SystemKind kind, boolean personal, @Nullable String requested) {
        if (kind == SystemKind.SLACK || kind == SystemKind.TEAMS) {
            if (requested != null && !requested.isBlank()) {
                throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "A " + kind + " system's base URL is "
                        + "fixed");
            }
            return kind == SystemKind.SLACK ? trimSlash(chatEndpoints.slackApi())
                    : trimSlash(chatEndpoints.teamsService());
        }
        if (!kind.hasBaseUrl()) {
            if (requested != null && !requested.isBlank()) {
                throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "A " + kind + " system has no base URL");
            }
            return null;
        }
        if (requested == null || requested.isBlank()) {
            throw ApiException.badRequest(ProblemCode.INVALID_BASE_URL, "A " + kind + " system needs a base URL");
        }
        String url = requested.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String problem = clients.http(personal).urlProblem(url);
        if (problem != null) {
            throw ApiException.badRequest(ProblemCode.INVALID_BASE_URL, "baseUrl is not usable: " + problem);
        }
        if (kind == SystemKind.JIRA_CLOUD && URI.create(url).getRawPath() != null
                && !URI.create(url).getRawPath().isEmpty()) {
            throw ApiException.badRequest(ProblemCode.INVALID_BASE_URL, "A Jira Cloud base URL is the site, e.g. "
                    + "https://acme.atlassian.net");
        }
        return url;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * A chat system's settings (the package 5 contract, §3.1): Slack's transport; Teams' tenant and app id, GUIDs.
     * Required for its kind, refused for another.
     */
    private static void chatSettings(SystemKind kind, @Nullable SlackSystemSettings slack,
            @Nullable TeamsSystemSettings teams, ObjectNode settings) {
        if (slack != null && kind != SystemKind.SLACK) {
            throw ApiException.validation("slack is for a SLACK system only");
        }
        if (teams != null && kind != SystemKind.TEAMS) {
            throw ApiException.validation("teams is for a TEAMS system only");
        }
        if (kind == SystemKind.SLACK) {
            if (slack == null || slack.getTransport() == null) {
                throw ApiException.validation("A SLACK system needs slack.transport: EVENTS_API or SOCKET_MODE");
            }
            settings.put("transport", slack.getTransport().getValue());
        }
        if (kind == SystemKind.TEAMS) {
            if (teams == null || teams.getTenantId() == null || teams.getAppId() == null
                    || !GUID.matcher(teams.getTenantId()).matches() || !GUID.matcher(teams.getAppId()).matches()) {
                throw ApiException.validation("A TEAMS system needs teams.tenantId and teams.appId, GUIDs");
            }
            settings.put("tenantId", teams.getTenantId().toLowerCase(Locale.ROOT));
            settings.put("appId", teams.getAppId().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * A webhook system's settings (the package 5 contract, §3.1, §5.12): its scheme, the header of
     * {@code SECRET_HEADER}, the mapping's JSON Pointers and its deliveries a minute within the company's maximum
     * ({@code 400 WEBHOOK_LIMIT_TOO_HIGH}). A create needs the scheme and the mapping; a change keeps what it leaves
     * out. Refused for another kind.
     */
    private void webhookSettings(UUID companyId, SystemKind kind, @Nullable WebhookSystemSettings webhook,
            ObjectNode settings, boolean create) {
        if (webhook != null && kind != SystemKind.WEBHOOK) {
            throw ApiException.validation("webhook is for a WEBHOOK system only");
        }
        if (kind != SystemKind.WEBHOOK) {
            return;
        }
        if (create && (webhook == null || webhook.getScheme() == null || webhook.getMapping() == null)) {
            throw ApiException.validation("A WEBHOOK system needs webhook.scheme and webhook.mapping");
        }
        if (webhook == null) {
            return;
        }
        WebhookSystems.WebhookSystem current = WebhookSystemsImpl.system(new java.util.UUID(0, 0), companyId, "",
                "", false, settings, null, null);
        WebhookSystems.Scheme scheme = webhook.getScheme() != null
                ? WebhookSystems.Scheme.valueOf(webhook.getScheme().getValue()) : current.scheme();
        String header = Json.text(settings, "secretHeader");
        if (webhook.getSecretHeader() != null && !webhook.getSecretHeader().isBlank()) {
            if (scheme != WebhookSystems.Scheme.SECRET_HEADER) {
                throw ApiException.validation("secretHeader is for the SECRET_HEADER scheme only");
            }
            header = webhook.getSecretHeader().strip();
            String lower = header.toLowerCase(Locale.ROOT);
            if (!HEADER.matcher(header).matches() || RESERVED_HEADERS.contains(lower) || lower.startsWith("webhook-")
                    || lower.equals("authorization")) {
                throw ApiException.validation("secretHeader must be a header name such as X-Tinfirm-Secret");
            }
        }
        Map<String, String> mapping = webhook.getMapping() != null ? mapping(webhook.getMapping())
                : current.mapping();
        Integer max = webhook.getMaxPerMinute() != null ? webhook.getMaxPerMinute() : current.maxPerMinute();
        if (webhook.getMaxPerMinute() != null) {
            int companyMax = webhookLimits.getIfAvailable() != null
                    ? Objects.requireNonNull(webhookLimits.getIfAvailable()).maxPerMinute(companyId) : 600;
            if (max < 1) {
                throw ApiException.validation("maxPerMinute must be at least 1");
            }
            if (max > companyMax) {
                throw ApiException.badRequest(ProblemCode.WEBHOOK_LIMIT_TOO_HIGH, "maxPerMinute is at most the "
                        + "company's maximum, " + companyMax);
            }
        }
        WebhookSystemsImpl.settings(settings, scheme, header, mapping, max);
    }

    /** The mapping's pointers by field, each a JSON Pointer (RFC 6901): empty, or {@code /} and escaped segments. */
    static Map<String, String> mapping(WebhookMapping input) {
        Map<String, String> mapping = new LinkedHashMap<>();
        put(mapping, WebhookSystems.MappingField.EVENT, input.getEvent());
        put(mapping, WebhookSystems.MappingField.SUBJECT_KEY, input.getSubjectKey());
        put(mapping, WebhookSystems.MappingField.SUBJECT_TITLE, input.getSubjectTitle());
        put(mapping, WebhookSystems.MappingField.SUBJECT_URL, input.getSubjectUrl());
        put(mapping, WebhookSystems.MappingField.ACTOR_ID, input.getActorId());
        put(mapping, WebhookSystems.MappingField.ACTOR_NAME, input.getActorName());
        put(mapping, WebhookSystems.MappingField.TEXT, input.getText());
        put(mapping, WebhookSystems.MappingField.DELIVERY_ID, input.getDeliveryId());
        return mapping;
    }

    private static void put(Map<String, String> mapping, WebhookSystems.MappingField field,
            @Nullable String pointer) {
        if (pointer == null) {
            return;
        }
        if (!pointer(pointer)) {
            throw ApiException.badRequest(ProblemCode.MAPPING_INVALID, "mapping." + field.key() + " is not a JSON "
                    + "Pointer (RFC 6901): empty, or segments each after a /, with ~ only as ~0 or ~1",
                    Map.of("details", Map.of("field", field.key())));
        }
        mapping.put(field.key(), pointer);
    }

    /** Whether the text is a JSON Pointer (RFC 6901) of at most 500 characters. */
    static boolean pointer(String text) {
        if (text.length() > 500 || (!text.isEmpty() && text.charAt(0) != '/')) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '~' && (i + 1 >= text.length() || (text.charAt(i + 1) != '0'
                    && text.charAt(i + 1) != '1'))) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String authHeader(SystemKind kind, @Nullable String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        if (kind != SystemKind.MCP) {
            throw ApiException.validation("authHeader is for MCP servers only");
        }
        String header = requested.strip();
        if (!HEADER.matcher(header).matches() || RESERVED_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
            throw ApiException.validation("authHeader must be a header name such as X-API-Key");
        }
        return header.equalsIgnoreCase("Authorization") ? null : header;
    }

    // ---- Views -----------------------------------------------------------------------------------------------------

    private ExternalSystem view(SystemAccess.Caller caller, SystemStore.SystemRow system) {
        return view(caller, system, testers(caller, List.of(system)));
    }

    /** Whether the caller manages the system: a company system's admins, a personal server's owner. */
    private static boolean manages(SystemAccess.Caller caller, SystemStore.SystemRow system) {
        return system.personal() ? caller.owns(system) : caller.admin();
    }

    /**
     * The names of who ran the last tests of the systems the caller manages, by principal: read when shown, never
     * stored, so a deleted user reads as one ({@link Principals#displayNames}).
     */
    private Map<UUID, String> testers(SystemAccess.Caller caller, List<SystemStore.SystemRow> systems) {
        Set<UUID> ids = new java.util.HashSet<>();
        for (SystemStore.SystemRow system : systems) {
            if (system.lastTest() != null && system.lastTest().byPrincipalId() != null && manages(caller, system)) {
                ids.add(system.lastTest().byPrincipalId());
            }
        }
        return ids.isEmpty() ? Map.of() : principals.displayNames(caller.companyId(), ids);
    }

    /**
     * The system's last test (V043): when, how it ended and whether with an account, for every member who sees the
     * system; who ran it and the system's credential it used, by its name now, for whoever manages it only.
     */
    private @Nullable SystemLastTest lastTest(SystemStore.SystemRow system, boolean manages,
            Map<UUID, String> testers) {
        SystemStore.LastTest test = system.lastTest();
        if (test == null) {
            return null;
        }
        SystemLastTest view = new SystemLastTest(test.at().atOffset(java.time.ZoneOffset.UTC), test.ok(),
                test.withAccount())
            .error(test.error());
        if (manages) {
            UUID by = test.byPrincipalId();
            view.byPrincipalId(by).byName(by != null ? testers.get(by) : null);
            // The credential it used, by its name now; the tester's own account is named by no one but them.
            if (test.accountId() != null) {
                accounts.credentialName(system.companyId(), system.id(), test.accountId())
                    .ifPresent(name -> view.credentialId(test.accountId()).credentialName(name));
            }
        }
        return view;
    }

    private ExternalSystem view(SystemAccess.Caller caller, SystemStore.SystemRow system, Map<UUID, String> testers) {
        boolean manages = manages(caller, system);
        SystemMapper.AppDetails app = null;
        boolean oauth = clients.profile(system).isPresent();
        if (manages && system.oauthClientId() != null) {
            OAuthProfile profile = clients.profile(system).orElse(null);
            app = new SystemMapper.AppDetails(system.oauthClientId(), system.oauthClientSecretId() != null,
                    system.oauthScopes(), clients.redirectUri(),
                    profile != null ? profile.authorizationEndpoint() : null);
        }
        // Whoever sets the app registers it at the system first, with this redirect URI.
        String callbackUrl = manages && system.kind().oauth() ? clients.redirectUri() : null;
        ChatSystemDetails chat = system.kind().chat() ? chatDetails(system, manages) : null;
        WebhookSystemDetails webhook = system.kind() == SystemKind.WEBHOOK ? webhookDetails(system, manages) : null;
        return mapper.toDto(new SystemMapper.SystemDetails(system.id(), system.name(), system.handle(), system.kind(),
                system.baseUrl(), system.personal(), system.status(), system.lastError(), system.paused(), oauth, app,
                callbackUrl, system.authHeader(), chat, system.createdAt(), system.updatedAt(),
                system.accountsRequired(), system.kind().receivesData(),
                !system.personal() && outbound.internalHost(system.baseUrl())))
            .webhook(webhook)
            .pollIntervalSeconds(system.kind().tracker()
                    ? (int) SystemPollsImpl.interval(system.settings()).toSeconds() : null)
            .lastTest(lastTest(system, manages, testers));
    }

    /**
     * A tracker's poll interval as requested (the package 4 contract, §5.4): how often its triggers' polls read it,
     * {@code 30} to {@code 3600} seconds; null when the request names none.
     */
    private static @Nullable Integer pollInterval(SystemKind kind, @Nullable Integer seconds) {
        if (seconds == null) {
            return null;
        }
        if (!kind.tracker()) {
            throw ApiException.invalid("pollIntervalSeconds is a tracker's only: Jira, GitHub or GitLab",
                    POLL_INTERVAL);
        }
        if (seconds < SystemPollsImpl.MIN_INTERVAL.toSeconds() || seconds > SystemPollsImpl.MAX_INTERVAL.toSeconds()) {
            throw ApiException.invalid("pollIntervalSeconds is " + SystemPollsImpl.MIN_INTERVAL.toSeconds() + " to "
                    + SystemPollsImpl.MAX_INTERVAL.toSeconds() + " seconds", POLL_INTERVAL);
        }
        return seconds;
    }

    /**
     * A webhook system's scheme, mapping, rate and URL (the package 5 contract, §5.12), for whoever manages it; its
     * scheme alone for anyone else.
     */
    private WebhookSystemDetails webhookDetails(SystemStore.SystemRow system, boolean manages) {
        WebhookSystems.WebhookSystem webhook = webhookSystems.row(system.companyId(), system.id(), false)
            .map(WebhookSystemsImpl.Row::system)
            .orElse(null);
        WebhookSystemDetails details = new WebhookSystemDetails()
            .scheme(WebhookSystemDetails.SchemeEnum.fromValue(webhook != null ? webhook.scheme().name()
                    : WebhookSystems.Scheme.STANDARD.name()))
            .mapping(new WebhookMapping())
            .perMinute(0)
            .url(trimSlash(tinfirm.publicUrl().toString()) + "/api/hooks/" + system.id());
        if (webhook == null || !manages) {
            return details;
        }
        WebhookLimits limits = webhookLimits.getIfAvailable();
        int perMinute = webhook.maxPerMinute() != null ? webhook.maxPerMinute()
                : limits != null ? limits.defaultPerMinute(system.companyId()) : 60;
        return details.secretHeader(webhook.scheme() == WebhookSystems.Scheme.SECRET_HEADER ? webhook.secretHeader()
                    : null)
            .mapping(new WebhookMapping()
                .event(webhook.pointer(WebhookSystems.MappingField.EVENT))
                .subjectKey(webhook.pointer(WebhookSystems.MappingField.SUBJECT_KEY))
                .subjectTitle(webhook.pointer(WebhookSystems.MappingField.SUBJECT_TITLE))
                .subjectUrl(webhook.pointer(WebhookSystems.MappingField.SUBJECT_URL))
                .actorId(webhook.pointer(WebhookSystems.MappingField.ACTOR_ID))
                .actorName(webhook.pointer(WebhookSystems.MappingField.ACTOR_NAME))
                .text(webhook.pointer(WebhookSystems.MappingField.TEXT))
                .deliveryId(webhook.pointer(WebhookSystems.MappingField.DELIVERY_ID)))
            .maxPerMinute(webhook.maxPerMinute())
            .perMinute(perMinute)
            .secretSetAt(webhook.secretSetAt() != null ? webhook.secretSetAt().atOffset(java.time.ZoneOffset.UTC)
                    : null)
            .previousUntil(webhook.previousUntil() != null && webhook.previousUntil().isAfter(time.now())
                    ? webhook.previousUntil().atOffset(java.time.ZoneOffset.UTC) : null);
    }

    /** A chat system's settings, its bot, and for whoever manages it where the platform sends its events. */
    private ChatSystemDetails chatDetails(SystemStore.SystemRow system, boolean manages) {
        ChatSystemInfo info = chatSystems.find(system.companyId(), system.id()).orElse(null);
        ChatSystemDetails details = new ChatSystemDetails()
            .botSet(info != null && info.hasBot())
            .signingSecretSet(info != null && info.signingSecretId() != null)
            .appTokenSet(info != null && info.appTokenId() != null);
        if (info == null) {
            return details;
        }
        details.transport(info.transport() != null ? ChatSystemDetails.TransportEnum.fromValue(info.transport())
                : null)
            .teamId(info.teamId())
            .teamName(info.teamName())
            .tenantId(info.tenantId())
            .appId(info.appId())
            .botUserId(info.botUserId())
            .botCredentialId(manages ? info.botAccountId() : null)
            .botCredentialName(manages && info.botAccountId() != null
                    ? accounts.credentialName(system.companyId(), system.id(), info.botAccountId()).orElse(null)
                    : null);
        if (manages && info.botAccountId() != null) {
            // Whose account the bot is: the service identity, by id and name.
            accounts.holderOf(system.companyId(), info.botAccountId()).ifPresent(holder -> details
                .botPrincipalId(holder)
                .botPrincipalName(principals.displayNames(system.companyId(), List.of(holder)).get(holder)));
        }
        if (manages) {
            String base = trimSlash(tinfirm.publicUrl().toString());
            if (info.slack()) {
                details.eventsUrl(base + "/api/chat/slack/" + system.id() + "/events")
                    .interactionsUrl(base + "/api/chat/slack/" + system.id() + "/interactions");
            }
            else {
                details.messagingUrl(base + "/api/chat/teams/" + system.id() + "/messages");
            }
        }
        return details;
    }

    /** A paused system is reached by nothing: testing, refreshing and connecting wait for its resume (§5.4). */
    static void requireNotPaused(SystemStore.SystemRow system) {
        if (system.paused()) {
            throw ApiException.conflict(ProblemCode.SYSTEM_PAUSED, system.name() + " is paused by an admin");
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException duplicateName() {
        return ApiException.conflict(ProblemCode.DUPLICATE_SYSTEM_NAME, "A system with this name already exists");
    }

    /**
     * A unique index refused the row: another system took the name, or the chat workspace, at the same moment. The
     * handle's index ({@code external_system_handle_uq}) stays as the last guard, but never refuses a registration:
     * registrations take the company's handles one at a time ({@link SystemStore#lockHandles}).
     */
    private static RuntimeException duplicate(DataIntegrityViolationException ex) {
        if (Constraints.violated(ex, NAME_INDEX)) {
            return duplicateName();
        }
        if (Constraints.violated(ex, CHAT_WORKSPACE_INDEX)) {
            return ApiException.conflict(ProblemCode.CHAT_WORKSPACE_TAKEN, "The company has a system of this "
                    + "workspace or tenant already");
        }
        return ex;
    }
}
