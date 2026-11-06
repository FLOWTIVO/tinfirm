package com.flowtivo.tinfirm.system.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.api.model.SystemRepository;
import com.flowtivo.tinfirm.shared.api.model.SystemRepositoryCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemRepositoryPage;
import com.flowtivo.tinfirm.shared.api.model.SystemTool;
import com.flowtivo.tinfirm.shared.api.model.SystemToolList;
import com.flowtivo.tinfirm.shared.api.model.SystemToolRefresh;
import com.flowtivo.tinfirm.shared.api.model.SystemToolUpdateRequest;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolInfo;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.mcp.McpClient;

/**
 * A code host's repositories and a system's tools (the package 1 contract, §3.4 and §3.5). Members read them; admins
 * (the owner, for a personal server) add and remove repositories, read an MCP server's catalogue again and confirm
 * each tool's effect or switch it off. A refresh keeps confirmed effects, except for a tool whose description, schema
 * or hints changed, which goes back to {@code UNKNOWN} (the security review of the redesign, row 13). Every change is
 * audited and announced ({@link SystemEvents}). The catalogue is read from the server outside any transaction,
 * between the one that authorises the caller and the one that stores what changed; at most once a minute per server
 * ({@link RefreshLimiter}, {@code 429 RATE_LIMITED}), and within the client's caps (500 tools, 64 KiB per schema,
 * 2 MiB in all, {@link McpClient}), a catalogue beyond them failing the refresh with the reason.
 */
@Service
class CatalogueService {

    static final int MAX_URL_LENGTH = 500;

    static final int MAX_BRANCH_LENGTH = 255;

    private static final Pattern BRANCH = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._/-]*$");

    private static final String REPOSITORY = "repository";

    private final CatalogueStore catalogue;

    private final SystemStore systems;

    private final SystemAccess access;

    private final SystemClients clients;

    private final SystemService systemService;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final SystemMapper mapper;

    private final TimeSource time;

    private final RefreshLimiter refreshes;

    /**
     * @param refreshInterval the shortest time between two reads of one server's catalogue
     *                        ({@code tinfirm.system.catalogue-refresh-interval}, a minute; zero: no limit, the tests)
     */
    CatalogueService(CatalogueStore catalogue, SystemStore systems, SystemAccess access, SystemClients clients,
            SystemService systemService, AuditLog auditLog, ApplicationEventPublisher events, SystemMapper mapper,
            TimeSource time, @Value("${tinfirm.system.catalogue-refresh-interval:PT1M}") Duration refreshInterval) {
        this.refreshes = new RefreshLimiter(refreshInterval);
        this.catalogue = catalogue;
        this.systems = systems;
        this.access = access;
        this.clients = clients;
        this.systemService = systemService;
        this.auditLog = auditLog;
        this.events = events;
        this.mapper = mapper;
        this.time = time;
    }

    // ---- Repositories ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SystemRepositoryPage repositories(UUID companyId, UUID systemId, @Nullable Integer limit,
            @Nullable String cursor) {
        SystemAccess.Caller caller = access.caller(companyId);
        access.visible(caller, systemId);
        PageRequest request = PageRequest.of(limit, cursor);
        List<RepositoryInfo> rows = request.firstPage()
                ? catalogue.repositoryPage(companyId, systemId, null, null, request.fetchSize())
                : catalogue.repositoryPage(companyId, systemId, request.key(0, 2), request.uuidKey(1, 2),
                        request.fetchSize());
        Page<RepositoryInfo> page = Page.of(rows, request, row -> List.of(row.name(), row.id().toString()));
        return new SystemRepositoryPage(page.items().stream().map(mapper::toDto).toList())
            .nextCursor(page.nextCursor());
    }

    @Transactional
    public SystemRepository addRepository(UUID companyId, UUID systemId, SystemRepositoryCreateRequest input) {
        SystemAccess.Caller caller = access.caller(companyId);
        SystemStore.SystemRow system = access.managed(caller, systemId);
        if (!system.kind().hasRepositories()) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, system.name() + " is a "
                    + system.kind().label() + " system, which holds no repositories");
        }
        String url = repositoryUrl(system, input.getUrl());
        String branch = input.getDefaultBranch() != null && !input.getDefaultBranch().isBlank()
                ? branch(input.getDefaultBranch()) : null;
        if (catalogue.repositoryExists(companyId, systemId, url)) {
            throw duplicateRepository();
        }
        RepositoryInfo repository = new RepositoryInfo(Ids.newId(), companyId, systemId, url, repositoryName(url),
                branch);
        try {
            catalogue.insertRepository(repository, time.now(), caller.userId());
        }
        catch (DuplicateKeyException ex) {
            throw duplicateRepository();
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.REPOSITORY_ADDED, CurrentActor.actor())
            .target(REPOSITORY, repository.id())
            .detail("systemId", systemId.toString())
            .detail("url", url)
            .build());
        return mapper.toDto(repository);
    }

    @Transactional
    public void removeRepository(UUID companyId, UUID systemId, UUID repositoryId) {
        SystemAccess.Caller caller = access.caller(companyId);
        access.managed(caller, systemId);
        RepositoryInfo repository = catalogue.findRepository(companyId, systemId, repositoryId)
            .orElseThrow(() -> ApiException.notFound("Repository not found"));
        catalogue.deleteRepository(companyId, repositoryId);
        auditLog.record(AuditRecord.company(companyId, AuditAction.REPOSITORY_REMOVED, CurrentActor.actor())
            .target(REPOSITORY, repositoryId)
            .detail("systemId", systemId.toString())
            .detail("url", repository.url())
            .build());
        events.publishEvent(new SystemEvents.RepositoryRemoved(companyId, systemId, repositoryId));
    }

    /**
     * A repository's clone URL: https, without user info, query or fragment, at most 500 characters; on the system's
     * host for GITHUB ({@code github.com} for {@code api.github.com}) and GITLAB, naming {@code owner/repo} on GitHub
     * and a project path on GitLab.
     */
    private static String repositoryUrl(SystemStore.SystemRow system, String value) {
        String url = value.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.length() > MAX_URL_LENGTH || url.chars().anyMatch(Character::isWhitespace)) {
            throw ApiException.validation("url must be an https URL of at most " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        }
        catch (URISyntaxException ex) {
            throw ApiException.validation("url is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiException.validation("url must be an https URL");
        }
        if (uri.getRawUserInfo() != null || uri.getRawAuthority().contains("@")) {
            throw ApiException.validation("url must not contain credentials; an account holds the token");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw ApiException.validation("url must not contain a query or fragment");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+", "").replaceAll("\\.git$", "");
        String[] segments = path.isEmpty() ? new String[0] : path.split("/");
        if (segments.length == 0) {
            throw ApiException.validation("url must name a repository on its host");
        }
        if (system.kind() == SystemKind.GITHUB || system.kind() == SystemKind.GITLAB) {
            String host = repositoryHost(system);
            if (!host.equals(uri.getHost().toLowerCase(Locale.ROOT))) {
                throw ApiException.validation("url must be on the system's host " + host);
            }
        }
        if (system.kind() == SystemKind.GITHUB && segments.length != 2) {
            throw ApiException.validation("url must name a GitHub repository, https://<host>/<owner>/<repo>(.git)");
        }
        if (system.kind() == SystemKind.GITLAB && segments.length < 2) {
            throw ApiException.validation("url must name a GitLab project, https://<host>/<group>/<project>(.git)");
        }
        return url;
    }

    /** The host repositories of the system live on: {@code github.com} for {@code api.github.com}. */
    private static String repositoryHost(SystemStore.SystemRow system) {
        String host = URI.create(Objects.requireNonNull(system.baseUrl())).getHost().toLowerCase(Locale.ROOT);
        return system.kind() == SystemKind.GITHUB && host.equals("api.github.com") ? "github.com" : host;
    }

    /** The repository's path without {@code .git}, e.g. {@code acme/shop}. */
    private static String repositoryName(String url) {
        String path = URI.create(url).getPath().replaceAll("^/+", "").replaceAll("\\.git$", "");
        return path.length() <= 200 ? path : path.substring(path.length() - 200);
    }

    private static String branch(String value) {
        String branch = value.strip();
        if (branch.length() > MAX_BRANCH_LENGTH || !BRANCH.matcher(branch).matches() || branch.contains("..")
                || branch.contains("//") || branch.endsWith("/") || branch.endsWith(".") || branch.endsWith(".lock")
                || branch.contains("@{")) {
            throw ApiException.validation("defaultBranch is not a valid branch name");
        }
        return branch;
    }

    private static ApiException duplicateRepository() {
        return ApiException.conflict(ProblemCode.DUPLICATE_REPOSITORY, "The system has a repository with this URL");
    }

    // ---- Tools -----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SystemToolList tools(UUID companyId, UUID systemId) {
        SystemAccess.Caller caller = access.caller(companyId);
        SystemStore.SystemRow system = access.visible(caller, systemId);
        if (system.kind().hasFixedTools()) {
            return new SystemToolList(FixedTools.tools(system.kind()).stream().map(this::fixed).toList());
        }
        return new SystemToolList(catalogue.tools(companyId, systemId).stream().map(this::catalogued).toList());
    }

    /**
     * Reads the MCP server's catalogue again, with an account ({@link SystemService#reach}: the credential named, the
     * caller's own {@code OK} account, the system's only usable credential), else without a credential, and stores
     * what changed. The read runs outside any transaction: the caller is authorised in one before it, and what changed
     * is stored in another after it, on the system as it is then, locked.
     */
    public SystemToolRefresh refresh(UUID companyId, UUID systemId, @Nullable UUID credentialId) {
        SystemService.Managed managed = systemService.managed(companyId, systemId);
        SystemAccess.Caller caller = managed.caller();
        SystemStore.SystemRow system = managed.system();
        SystemService.requireNotPaused(system);
        if (!system.kind().hasCatalogue()) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, system.name() + " is a "
                    + system.kind().label() + " system, whose tools are Tinfirm's fixed catalogue");
        }
        // One read per server and minute, before anything is revealed or sent (M9).
        Optional<Duration> wait = refreshes.admit(systemId, time.now());
        if (wait.isPresent()) {
            long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
            throw ApiException.rateLimited(Duration.ofSeconds(seconds), system.name() + "'s tools were read less "
                    + "than a minute ago: refresh again in " + seconds + " s");
        }
        SystemService.Reach reach = systemService.reach(managed, credentialId, "system-tools:" + systemId);
        if (reach.unusable()) {
            // Nothing was sent: the catalogue and the system's status stay as they are.
            return new SystemToolRefresh(false, false, false, List.of(), List.of(), List.of())
                .credentialId(reach.credentialId())
                .credentialName(reach.credentialName())
                .message(SystemService.UNUSABLE_CREDENTIAL);
        }
        if (reach.credential() != null) {
            SystemService.requireStill(system, reach.credential());
        }
        SystemCalls.Credential credential = reach.credential() != null ? reach.credential().credential() : null;
        McpClient.Target target = new McpClient.Target(Objects.requireNonNull(system.baseUrl()),
                credential != null && !credential.oauth() ? system.authHeader() : null,
                credential != null ? credential.token() : null);
        CallResult<McpClient.Listed> listed = clients.mcp(system).listTools(target);
        return systemService.inTransaction(() -> {
            access.relocked(caller, systemId);
            return store(companyId, system, listed, credential != null, reach.own())
                .credentialId(reach.credentialId())
                .credentialName(reach.credentialName());
        });
    }

    /**
     * Stores what the read found, in the transaction after it: the changes of the catalogue, or why it failed. A
     * server that asks for a credential when none was given was reached, as a test without an account finds: its
     * status is {@code OK}, and the answer says to add a credential (or connect one's own account) and refresh again.
     *
     * @param withAccount whether an account was used
     * @param own         whether it was the caller's own
     */
    private SystemToolRefresh store(UUID companyId, SystemStore.SystemRow system, CallResult<McpClient.Listed> listed,
            boolean withAccount, boolean own) {
        UUID systemId = system.id();
        if (listed.failure() != null) {
            boolean credentialRequired = listed.failure().kind() == CallResult.Kind.UNAUTHORIZED;
            String answer = Objects.requireNonNull(listed.failureMessage());
            String reason;
            if (credentialRequired && !withAccount) {
                reason = Json.cut(system.name() + " asks for a token: add a credential, or connect your own account, "
                        + "and refresh again (" + answer + ")", 500);
                systems.updateStatus(companyId, systemId, "OK", null);
            }
            else {
                reason = Json.cut(credentialRequired
                        ? system.name() + " refused " + (own ? "your account" : "the credential") + ": " + answer
                        : "The catalogue could not be read: " + answer, 500);
                systems.updateStatus(companyId, systemId, "ERROR", reason);
            }
            return new SystemToolRefresh(false, withAccount, credentialRequired, List.of(), List.of(), List.of())
                .message(reason);
        }
        Map<String, CatalogueStore.ToolRow> stored = new HashMap<>();
        catalogue.tools(companyId, systemId).forEach(tool -> stored.put(tool.name(), tool));
        Instant now = time.now();
        List<String> added = new ArrayList<>();
        List<String> unconfirmed = new ArrayList<>();
        Set<String> changed = new LinkedHashSet<>();
        Set<String> listedNames = new LinkedHashSet<>();
        for (McpClient.ListedTool tool : Objects.requireNonNull(listed.value()).tools()) {
            listedNames.add(tool.name());
            CatalogueStore.ToolRow fresh = new CatalogueStore.ToolRow(tool.name(), tool.title(), tool.description(),
                    tool.inputSchema(), ToolEffect.UNKNOWN, tool.readOnlyHint(), tool.destructiveHint(), false, true);
            CatalogueStore.ToolRow known = stored.get(tool.name());
            if (known == null) {
                catalogue.insertTool(companyId, systemId, fresh, now);
                added.add(tool.name());
                changed.add(tool.name());
                continue;
            }
            boolean differs = !Objects.equals(known.description(), fresh.description())
                    || !known.inputSchema().equals(fresh.inputSchema())
                    || !Objects.equals(known.hintReadOnly(), fresh.hintReadOnly())
                    || !Objects.equals(known.hintDestructive(), fresh.hintDestructive());
            if (differs || !Objects.equals(known.title(), fresh.title())) {
                catalogue.updateTool(companyId, systemId, fresh, differs && known.confirmed(), now);
                changed.add(tool.name());
                if (differs && known.confirmed()) {
                    unconfirmed.add(tool.name());
                }
            }
        }
        List<String> removed = stored.keySet().stream().filter(name -> !listedNames.contains(name)).sorted().toList();
        for (String name : removed) {
            catalogue.deleteTool(companyId, systemId, name);
            changed.add(name);
        }
        systems.updateStatus(companyId, systemId, "OK", null);
        if (!changed.isEmpty()) {
            auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_TOOLS_REFRESHED, CurrentActor.actor())
                .target(SystemService.TARGET, systemId)
                .detail("added", added)
                .detail("removed", removed)
                .detail("unconfirmed", unconfirmed)
                .build());
            events.publishEvent(new SystemEvents.SystemToolsChanged(companyId, systemId, changed));
        }
        return new SystemToolRefresh(true, withAccount, false, added, removed, unconfirmed);
    }

    /** Confirms an MCP tool's effect, or switches it on or off. */
    @Transactional
    public SystemTool updateTool(UUID companyId, UUID systemId, String name, SystemToolUpdateRequest input) {
        SystemAccess.Caller caller = access.caller(companyId);
        SystemStore.SystemRow system = access.managed(caller, systemId);
        if (!system.kind().hasCatalogue()) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "A fixed tool's effect is known and it is "
                    + "always on");
        }
        CatalogueStore.ToolRow tool = catalogue.lockTool(companyId, systemId, name)
            .orElseThrow(() -> ApiException.notFound("Tool not found"));
        if (input.getEffect() == null && input.getEnabled() == null) {
            throw ApiException.validation("Name an effect to confirm or whether the tool is enabled");
        }
        Instant now = time.now();
        Actor actor = CurrentActor.actor();
        if (input.getEffect() != null) {
            ToolEffect effect = ToolEffect.valueOf(input.getEffect().getValue());
            catalogue.confirmTool(companyId, systemId, name, effect, actor.type() == ActorType.USER ? actor.id() : null,
                    now);
            auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_TOOL_EFFECT_CONFIRMED, actor)
                .target(SystemService.TARGET, systemId)
                .detail("tool", name)
                .detail("effect", effect.name())
                .build());
        }
        if (input.getEnabled() != null && input.getEnabled() != tool.enabled()) {
            catalogue.switchTool(companyId, systemId, name, input.getEnabled(), now);
            auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_TOOL_SWITCHED, actor)
                .target(SystemService.TARGET, systemId)
                .detail("tool", name)
                .detail("enabled", input.getEnabled())
                .build());
        }
        events.publishEvent(new SystemEvents.SystemToolsChanged(companyId, systemId, Set.of(name)));
        return catalogued(catalogue.lockTool(companyId, systemId, name).orElseThrow());
    }

    private SystemTool fixed(ToolInfo tool) {
        return mapper.toDto(new SystemMapper.ToolDetails(tool.name(), tool.title(), tool.description(),
                Json.asMap(tool.inputSchema()), tool.effect(), null, null, null, true, true));
    }

    private SystemTool catalogued(CatalogueStore.ToolRow tool) {
        return mapper.toDto(new SystemMapper.ToolDetails(tool.name(), tool.title(), tool.description(),
                Json.asMap(tool.inputSchema()), tool.effect(), suggested(tool.hintReadOnly(), tool.hintDestructive()),
                tool.hintReadOnly(), tool.hintDestructive(), tool.enabled(), false));
    }

    /**
     * The effect the hints suggest (MCP's tool annotations): {@code READ} for a read-only tool, {@code WRITE} for one
     * that says it is not read-only or is destructive, {@code UNKNOWN} without hints. A suggestion only.
     */
    static ToolEffect suggested(@Nullable Boolean readOnly, @Nullable Boolean destructive) {
        if (Boolean.TRUE.equals(destructive) || Boolean.FALSE.equals(readOnly)) {
            return ToolEffect.WRITE;
        }
        return Boolean.TRUE.equals(readOnly) ? ToolEffect.READ : ToolEffect.UNKNOWN;
    }
}
