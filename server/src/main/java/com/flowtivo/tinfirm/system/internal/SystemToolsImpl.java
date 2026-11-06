package com.flowtivo.tinfirm.system.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChangeRequests;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.ToolCallResult;
import com.flowtivo.tinfirm.system.ToolCheck;
import com.flowtivo.tinfirm.system.ToolOutcome;
import com.flowtivo.tinfirm.system.internal.http.OutboundProperties;
import com.flowtivo.tinfirm.system.internal.mcp.McpClient;
import com.flowtivo.tinfirm.system.internal.tools.ToolArguments;
import com.flowtivo.tinfirm.system.internal.tools.ToolCalls;
import com.flowtivo.tinfirm.system.internal.tools.Tracker;
import com.flowtivo.tinfirm.system.internal.tools.Trackers;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link SystemTools} over the system's clients, in the caller's tenant context. What a call needs (the system's row,
 * a repository's, an MCP tool's catalogue entry) is read first in a short transaction of its own; the system is then
 * called outside any transaction on a virtual thread of its own, which the caller waits for at most the tool timeout:
 * past it the call is interrupted and answered {@code TOOL_FAILED}. A result larger than
 * {@link SystemTools#MAX_RESULT_BYTES} as JSON is answered {@code RESULT_TOO_LARGE}. The credential's token never
 * leaves the call: it is taken out of every answer's text, and never logged. Nor does it reach another endpoint than
 * the one it was revealed for: a row whose kind or base URL is not the credential's {@link CallCredential#endpoint()}
 * (an MCP server moved meanwhile, ADR-0079, {@link EndpointPin}) is as gone, and nothing is sent.
 */
@Component
class SystemToolsImpl implements SystemTools {

    private static final Logger log = LoggerFactory.getLogger(SystemToolsImpl.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SystemStore systems;

    private final CatalogueStore catalogue;

    private final SystemClients clients;

    private final TransactionTemplate transactions;

    private final Duration timeout;

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
        .name("system-tool-", 0)
        .factory());

    @Autowired
    SystemToolsImpl(SystemStore systems, CatalogueStore catalogue, SystemClients clients,
            PlatformTransactionManager transactionManager, OutboundProperties properties) {
        this(systems, catalogue, clients, transactionManager, properties.toolTimeout());
    }

    /** With another timeout, for the tests of the timeout. */
    SystemToolsImpl(SystemStore systems, CatalogueStore catalogue, SystemClients clients,
            PlatformTransactionManager transactionManager, Duration timeout) {
        this.systems = systems;
        this.catalogue = catalogue;
        this.clients = clients;
        this.transactions = new TransactionTemplate(transactionManager);
        this.timeout = timeout;
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    @Override
    public ToolCheck check(SystemKind kind, String tool, JsonNode arguments) {
        return ToolArguments.check(kind, tool, arguments);
    }

    @Override
    public ToolOutcome call(UUID companyId, UUID systemId, CallCredential credential, String tool,
            JsonNode arguments) {
        return call(companyId, systemId, credential, tool, arguments, null);
    }

    /** What a call needs, read in a short transaction of its own. */
    private record Target(SystemStore.SystemRow system, CatalogueStore.@Nullable ToolRow mcpTool) {
    }

    @Override
    public ToolOutcome call(UUID companyId, UUID systemId, CallCredential credential, String tool,
            JsonNode arguments, @Nullable String commentLabel) {
        NoTransaction.require("A tool call");
        Target target = Objects.requireNonNull(transactions.execute(status -> systems.find(companyId, systemId)
            .map(system -> new Target(system, system.kind().hasCatalogue()
                    ? catalogue.findTool(companyId, systemId, tool).orElse(null) : null))
            .orElse(null)));
        if (target == null) {
            return new ToolOutcome.Refused(ToolOutcome.Code.UNKNOWN_TOOL, "The system of `" + tool
                    + "` does not exist.");
        }
        SystemStore.SystemRow system = target.system();
        CallResult.Failure moved = EndpointPin.moved(system, credential);
        if (moved != null) {
            // The credential was revealed for another endpoint: as if the system were gone, and nothing is sent.
            return new ToolOutcome.Refused(ToolOutcome.Code.UNKNOWN_TOOL, moved.message());
        }
        ToolCheck checked = ToolArguments.check(system.kind(), tool, arguments);
        if (checked instanceof ToolCheck.Invalid invalid) {
            return new ToolOutcome.Refused(invalid.code(), invalid.message());
        }
        JsonNode normalized = ((ToolCheck.Valid) checked).arguments();
        if (system.kind().hasCatalogue() && (target.mcpTool() == null || !target.mcpTool().enabled())) {
            return new ToolOutcome.Refused(ToolOutcome.Code.UNKNOWN_TOOL, "`" + tool + "` is not a tool of "
                    + system.name() + ", or it is switched off.");
        }
        String failedText = "`" + tool + "` on " + system.name();
        ToolOutcome outcome = bounded(() -> run(system, credential, tool, normalized, commentLabel),
                problem -> new ToolOutcome.Refused(ToolOutcome.Code.TOOL_FAILED, failedText + " " + problem),
                system, tool);
        return limited(scrubbed(outcome, credential.token()), failedText);
    }

    /**
     * The call itself, on the call's thread: a tracker's fixed tool, Slack's {@code history} with the account's user
     * token (never the bot's, C9), or an MCP server's {@code tools/call}.
     */
    private ToolOutcome run(SystemStore.SystemRow system, CallCredential credential, String tool, JsonNode arguments,
            @Nullable String commentLabel) {
        if (system.kind() == SystemKind.SLACK) {
            String token = credential.token();
            if (token == null || token.isBlank() || system.baseUrl() == null) {
                return new ToolOutcome.Reconnect("The account holds no credential to call Slack with");
            }
            return ToolCalls.slack(clients.slack(system), token, system.name(), tool, arguments);
        }
        if (system.kind().hasCatalogue()) {
            McpClient.Target target = new McpClient.Target(Objects.requireNonNull(system.baseUrl()),
                    credential.token() != null && !credential.oauth() ? system.authHeader() : null,
                    credential.token());
            return ToolCalls.mcp(system.name(), tool, clients.mcp(system).callTool(target, tool, arguments));
        }
        CallResult<Tracker> tracker = Trackers.of(clients.http(system), system.kind(), system.baseUrl(), credential);
        if (tracker.failure() != null) {
            return tracker.failure().kind() == CallResult.Kind.UNAUTHORIZED
                    ? new ToolOutcome.Reconnect(tracker.failure().message())
                    : new ToolOutcome.Refused(ToolOutcome.Code.TOOL_FAILED, tracker.failure().message());
        }
        return ToolCalls.run(Objects.requireNonNull(tracker.value()), system.name(), tool, arguments, commentLabel);
    }

    /** {@code RESULT_TOO_LARGE} for a result whose JSON is larger than the protocol carries. */
    private static ToolOutcome limited(ToolOutcome outcome, String call) {
        if (!(outcome instanceof ToolOutcome.Done done)) {
            return outcome;
        }
        int size = JSON.writeValueAsBytes(done.result()).length;
        if (size <= MAX_RESULT_BYTES) {
            return outcome;
        }
        return new ToolOutcome.Refused(ToolOutcome.Code.RESULT_TOO_LARGE, "The result of " + call + " is larger "
                + "than the " + MAX_RESULT_BYTES / 1024 + " KiB a result may have. Narrow the query (a smaller "
                + "`limit`, a more precise query, fewer fields) and call it again.");
    }

    // ---- Change requests -------------------------------------------------------------------------------------------

    /** A repository and its system, read in a short transaction of its own. */
    private record Repository(RepositoryInfo repository, SystemStore.SystemRow system) {
    }

    @Override
    public CallResult<ChangeRequests.Opened> openChangeRequest(UUID companyId, UUID repositoryId,
            CallCredential credential, ChangeRequests.New request) {
        NoTransaction.require("Opening a change request");
        Optional<Repository> found = repository(companyId, repositoryId);
        if (found.isEmpty()) {
            return CallResult.failed(CallResult.Kind.NOT_FOUND, "The repository does not exist");
        }
        RepositoryInfo repository = found.get().repository();
        SystemStore.SystemRow system = found.get().system();
        CallResult<Tracker.CodeHost> host = codeHost(system, credential);
        if (host.failure() != null) {
            return host.failedAs();
        }
        ChangeRequests.New into = request.base() == null && repository.defaultBranch() != null
                ? new ChangeRequests.New(request.title(), request.head(), repository.defaultBranch(), request.body())
                : request;
        return bounded(() -> Objects.requireNonNull(host.value()).open(repository.url(), into),
                problem -> CallResult.failed(CallResult.Kind.UNAVAILABLE, "Opening the change request of "
                        + repository.name() + " " + problem), system, "open_change_request");
    }

    @Override
    public CallResult<ChangeRequests.Read> changeRequestState(UUID companyId, UUID repositoryId,
            CallCredential credential, int number) {
        NoTransaction.require("Reading a change request");
        Optional<Repository> found = repository(companyId, repositoryId);
        if (found.isEmpty()) {
            return CallResult.failed(CallResult.Kind.NOT_FOUND, "The repository does not exist");
        }
        RepositoryInfo repository = found.get().repository();
        SystemStore.SystemRow system = found.get().system();
        CallResult<Tracker.CodeHost> host = codeHost(system, credential);
        if (host.failure() != null) {
            return host.failedAs();
        }
        return bounded(() -> Objects.requireNonNull(host.value()).state(repository.url(), number),
                problem -> CallResult.failed(CallResult.Kind.UNAVAILABLE, "Reading change request " + number + " of "
                        + repository.name() + " " + problem), system, "change request state");
    }

    @Override
    public CallResult<String> changeRequestBody(UUID companyId, UUID repositoryId, CallCredential credential,
            int number) {
        NoTransaction.require("Reading a change request");
        return onCodeHost(companyId, repositoryId, credential, "Reading change request " + number + " of",
                (host, repository) -> host.body(repository.url(), number));
    }

    @Override
    public CallResult<Boolean> updateChangeRequestBody(UUID companyId, UUID repositoryId, CallCredential credential,
            int number, String body) {
        NoTransaction.require("Updating a change request");
        return onCodeHost(companyId, repositoryId, credential, "Updating change request " + number + " of",
                (host, repository) -> host.updateBody(repository.url(), number, body));
    }

    /**
     * Calls the repository's code host with the credential within the tool timeout; a timeout or an interruption is
     * {@code UNAVAILABLE}, worded {@code {what} {repository} {what happened}}.
     */
    private <T> CallResult<T> onCodeHost(UUID companyId, UUID repositoryId, CallCredential credential, String what,
            BiFunction<Tracker.CodeHost, RepositoryInfo, CallResult<T>> call) {
        Optional<Repository> found = repository(companyId, repositoryId);
        if (found.isEmpty()) {
            return CallResult.failed(CallResult.Kind.NOT_FOUND, "The repository does not exist");
        }
        RepositoryInfo repository = found.get().repository();
        SystemStore.SystemRow system = found.get().system();
        CallResult<Tracker.CodeHost> host = codeHost(system, credential);
        if (host.failure() != null) {
            return host.failedAs();
        }
        return bounded(() -> call.apply(Objects.requireNonNull(host.value()), repository),
                problem -> CallResult.failed(CallResult.Kind.UNAVAILABLE, what + " " + repository.name() + " "
                        + problem), system, what);
    }

    /**
     * The system's code host for the credential; {@code INVALID} when the credential was revealed for another
     * endpoint than the row's ({@link EndpointPin}), with nothing sent.
     */
    private CallResult<Tracker.CodeHost> codeHost(SystemStore.SystemRow system, CallCredential credential) {
        CallResult.Failure moved = EndpointPin.moved(system, credential);
        return moved != null ? CallResult.failed(moved)
                : Trackers.codeHost(clients.http(system), system.kind(), system.baseUrl(), credential);
    }

    private Optional<Repository> repository(UUID companyId, UUID repositoryId) {
        return Objects.requireNonNull(transactions.execute(status -> catalogue.findRepository(companyId, repositoryId)
            .flatMap(repository -> systems.find(companyId, repository.systemId())
                .map(system -> new Repository(repository, system)))));
    }

    // ---- The bound -------------------------------------------------------------------------------------------------

    /**
     * Runs the work on a virtual thread of its own and waits for it at most the timeout; past it, or should the work
     * fail inside Tinfirm, the answer of {@code failed} with what happened ("did not finish within 60 s"). The thread
     * that waits is interrupted only when its own caller interrupts it.
     */
    private <T> T bounded(Supplier<T> work, Function<String, T> failed, SystemStore.SystemRow system, String what) {
        Future<T> future = executor.submit(work::get);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("{} on system {} did not finish within {} ms", what, system.id(), timeout.toMillis());
            return failed.apply("did not finish within " + Math.max(1, timeout.toSeconds()) + " s.");
        }
        catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return failed.apply("was interrupted.");
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            // The exception's class only: a message could quote a header, a header could hold the token.
            log.warn("{} on system {} failed inside Tinfirm: {}", what, system.id(), cause.getClass().getName());
            return failed.apply("failed inside Tinfirm (" + cause.getClass().getSimpleName() + ").");
        }
    }

    // ---- The token -------------------------------------------------------------------------------------------------

    /** The outcome without the token in any of its texts, should a system have quoted it. */
    static ToolOutcome scrubbed(ToolOutcome outcome, @Nullable String token) {
        if (token == null || token.isBlank()) {
            return outcome;
        }
        return switch (outcome) {
            case ToolOutcome.Reconnect reconnect -> new ToolOutcome.Reconnect(scrub(reconnect.message(), token));
            case ToolOutcome.Refused refused -> new ToolOutcome.Refused(refused.code(), scrub(refused.message(),
                    token));
            case ToolOutcome.Done done -> new ToolOutcome.Done(new ToolCallResult(done.result()
                .content()
                .stream()
                .map(block -> new ToolCallResult.Content(block.type(), scrub(block.text(), token)))
                .toList(), scrub(done.result().structuredContent(), token), done.result().isError()),
                    done.failure() != null ? new CallResult.Failure(done.failure().kind(),
                            scrub(done.failure().message(), token), done.failure().retryAfter()) : null,
                    done.written());
        };
    }

    private static String scrub(String text, String token) {
        String stripped = token.strip();
        return stripped.length() < 8 ? text : text.replace(stripped, "***");
    }

    /** The structured content without the token in any of its strings: the token as JSON escapes it, replaced. */
    private static @Nullable JsonNode scrub(@Nullable JsonNode structured, String token) {
        String stripped = token.strip();
        if (structured == null || stripped.length() < 8) {
            return structured;
        }
        String quoted = JSON.writeValueAsString(stripped);
        String escaped = quoted.substring(1, quoted.length() - 1);
        String json = JSON.writeValueAsString(structured);
        return json.contains(escaped) ? JSON.readTree(json.replace(escaped, "***")) : structured;
    }
}
