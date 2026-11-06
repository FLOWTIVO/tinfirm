package com.flowtivo.tinfirm.model.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.model.ModelEvents;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.model.RunModelTokens;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerAuthentication;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RunnerVersions;
import com.flowtivo.tinfirm.shared.litellm.LiteLlmRequests;
import com.flowtivo.tinfirm.shared.net.GuardedHosts;
import com.flowtivo.tinfirm.shared.net.InflightBytes;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.VaultService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The model relay (the package 2 contract, §6.1, M7): a runner host's model request with a run's one-run token goes to
 * the company's model gateway with the paying principal's key, and the gateway's answer streams back as it arrives.
 * <ol>
 * <li>The token: its hash names the company ({@code run_model_token_company}); in the company's context the token must
 * be unrevoked, of the endpoint's format, and its run {@code RUNNING} on the token's host with the token's epoch
 * ({@link RunModelTokens}). Anything else is 401, without saying why; without a module that implements the port, every
 * token is.</li>
 * <li>The runner host (the package 2 contract, open point 6): the host's own token in {@code X-Tinfirm-Runner-Token},
 * authenticated as on the runner's other endpoints ({@link RunnerAuthentication}), must be the token's host's, so that
 * a copied run token works only from its own host. Without the header, a host whose latest {@code hello.runnerVersion}
 * is {@link RunnerVersions#HOST_TOKEN_TO_RELAY} or later, which always sends it, is refused, and so is every host while
 * {@code tinfirm.model.relay-require-host-token} is on; an older host is accepted as before, with a warning once a day
 * per host and replica. Each refusal is the same 401.</li>
 * <li>The paying principal's key: the company's gateway passed its test (else 503), the paying principal is
 * {@code ACTIVE} as the principal module has it now (else 403, whether or not LiteLLM has blocked its key yet: a block
 * that failed leaves the key {@code ERROR} until the reconciliation), the key exists (else 503, and it is synced), and
 * it is not {@code BLOCKED} (403). The key is revealed once per token and replica ({@code secret.accessed}, purpose
 * {@code run:<runId>}) and kept in memory while the run uses it; once a rotation replaced it (a new secret), the next
 * request reveals the new key. A request under way keeps the key it took, which LiteLLM honours for the rotation's
 * grace.</li>
 * <li>The limits: at most 16 requests of a run at a time, and 16 per slot of its host's ceiling; more are 429.</li>
 * <li>The body: at most 64 MiB (413), whole within {@code relay-body-timeout} (30 s; else 408), one JSON object
 * without LiteLLM's fields, with {@code drop_params} on for a LiteLLM, and asking the model's provider to run no tool
 * itself: no MCP server, code execution or web search of the provider's (400 naming the field or the tool's type;
 * {@link RelayBody}; ADR-0108), and a model the key allows (403): its models, or the company's default ones, every
 * model when the list is empty. The bodies this replica holds, each counted twice while it is read and written anew, then once
 * until its answer ends, stay within {@code relay-max-inflight-bytes} (256 MiB; {@link InflightBytes}), counted as
 * their bytes arrive, a declared length too, so that a slow upload holds only what it sent; a body beyond it is 429
 * with {@code Retry-After}. What the meter keeps of an answer to read its usage (a plain JSON answer up to 4 MiB, or
 * one line of a stream in a buffer grown step by step up to 4 MiB, {@link RelayUsageMeter}) is reserved from the
 * same budget until the request ends; an answer, or a line, it cannot hold is dropped, and an answer not read counts
 * a request without tokens.</li>
 * <li>Forwarding, outside any transaction: the endpoint's path at the gateway, no query, only the request headers
 * {@code Accept}, {@code anthropic-version}, {@code anthropic-beta} and {@code OpenAI-Beta} (only the values of
 * {@code relay-anthropic-betas} and {@code relay-openai-betas}, the others dropped) and {@code User-Agent}
 * (LiteLLM treats Claude Code and Codex apart by it), the key as the bearer, and the run, its conversation and agent as
 * the gateway's tags (its adapter's headers: LiteLLM's {@code x-litellm-tags}, else {@code x-tinfirm-tags}). The
 * answer streams back part by part, each flushed, with only its {@code Content-Type}, {@code Retry-After}, request ids
 * and rate limit headers (never LiteLLM's own {@code x-litellm-*}); no total timeout, an idle one of 10 minutes between
 * two parts and until the headers.</li>
 * <li>The gateway's refusals, as its vendor's {@link GatewayAdapter} reads them: a spent budget makes the key
 * {@code BUDGET_SPENT} ({@code model_key.budget_spent}) and is answered with the relay's own refusal, whatever the
 * gateway said ({@link #BUDGET_SPENT}); a refused key {@code ERROR} ({@code model_key.refused}) unless a rotation
 * replaced it since the request took it; both are synced again. Every other refusal's text is passed on without the
 * key that paid, whatever its form, and without what it echoes of a key.</li>
 * <li>The token's counters: the request as it is admitted ({@code admitted_count}, before it is forwarded; a request
 * the limits refuse is not admitted), and at its end the request and the bytes of both bodies ({@code request_count});
 * the difference is the run's answers under way ({@link ModelRelay#answersUnderWay}), for which its settlement waits
 * (CONVENTIONS §15.12). Then, outside any transaction, {@link ModelEvents.RelayRequestCounted}, so that a run that
 * ended while its last answer was under way is still settled, after the answer's usage
 * ({@code RelayUsageMeter.Answered}) was recorded.</li>
 * </ol>
 */
@Component
class ModelRelayService {

    private static final Logger log = LoggerFactory.getLogger(ModelRelayService.class);

    /** The longest error answer of LiteLLM that is read, checked and passed on. */
    private static final int MAX_ERROR_BYTES = 64 * 1024;

    /**
     * The relay's answer to a request whose payer's budget is spent (ADR-0094), in the request's format with this
     * error type, as 429, whatever the gateway answered: the runner host recognises its words as a usage limit
     * ({@code limits.rs}), as it recognised LiteLLM's before, so a run fails {@code USAGE_LIMIT} behind any gateway.
     */
    static final String BUDGET_SPENT = "Budget has been exceeded: the model budget that pays for this run is spent";

    /** The error type of {@link #BUDGET_SPENT}, LiteLLM's, which runner hosts recognise too. */
    static final String BUDGET_SPENT_TYPE = "budget_exceeded";

    /** The longest model name the relay notes as not served ({@code run_model_token.model_not_served}). */
    private static final int MAX_MODEL_NAME = 200;

    /** How long a revealed key stays in memory after its run's last request. */
    private static final Duration KEEP_KEY = Duration.ofMinutes(30);

    private static final List<String> REQUEST_HEADERS = List.of("accept", "anthropic-version", "anthropic-beta",
            "openai-beta", "user-agent");

    private static final Set<String> RESPONSE_HEADERS = Set.of("content-type", "retry-after", "request-id",
            "x-request-id");

    private static final List<String> RESPONSE_HEADER_PREFIXES = List.of("anthropic-ratelimit-", "x-ratelimit-");

    private static final int MAX_HEADER_VALUE = 4096;

    /** The runner host's own token ({@code tfr_…}), which its model proxy sends next to the run's token. */
    static final String HOST_TOKEN_HEADER = "X-Tinfirm-Runner-Token";

    /** How often a host that leaves out its own token is warned about, per replica. */
    private static final Duration WARN_WITHOUT_HOST_TOKEN = Duration.ofDays(1);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SystemTransactions transactions;

    private final RelayTokenStore tokens;

    private final ObjectProvider<RunModelTokens> runTokens;

    private final ModelSettingsStore settingsStore;

    private final ModelKeyStore keys;

    private final Gateways gateways;

    private final GatewayAdapter adapter;

    private final GatewayHttp http;

    private final RunnerDirectory runners;

    private final RunnerAuthentication hostAuthentication;

    private final Principals principals;

    private final VaultService vault;

    private final KeyWork work;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final ModelProperties properties;

    private final TimeSource time;

    private final JsonMapper bodies;

    private final Map<String, CachedKey> revealed = new ConcurrentHashMap<>();

    private final Map<UUID, Integer> perRun = new ConcurrentHashMap<>();

    private final Map<UUID, Integer> perHost = new ConcurrentHashMap<>();

    private final InflightBytes inflight;

    /** When each host that left out its own token was last warned about, on this replica. */
    private final Map<UUID, Instant> warnedWithoutHostToken = new ConcurrentHashMap<>();

    private final CompanySwitches companySwitches;

    ModelRelayService(SystemTransactions transactions, RelayTokenStore tokens, ObjectProvider<RunModelTokens> runTokens,
            ModelSettingsStore settingsStore, ModelKeyStore keys, Gateways gateways, GatewayAdapter adapter,
            GatewayHttp http,
            RunnerDirectory runners, RunnerAuthentication hostAuthentication, Principals principals, VaultService vault,
            KeyWork work, AuditLog auditLog, ApplicationEventPublisher events, ModelProperties properties,
            TimeSource time, CompanySwitches companySwitches) {
        this.companySwitches = companySwitches;
        this.transactions = transactions;
        this.tokens = tokens;
        this.runTokens = runTokens;
        this.settingsStore = settingsStore;
        this.keys = keys;
        this.gateways = gateways;
        this.adapter = adapter;
        this.http = http;
        this.runners = runners;
        this.hostAuthentication = hostAuthentication;
        this.principals = principals;
        this.vault = vault;
        this.work = work;
        this.auditLog = auditLog;
        this.events = events;
        this.properties = properties;
        this.time = time;
        this.bodies = RelayBody.mapper(properties.relayMaxRequestBytes());
        this.inflight = new InflightBytes(properties.relayMaxInflightBytes());
    }

    /** The relay's endpoints (§6.1): nothing else is served. */
    enum Endpoint {
        ANTHROPIC_MESSAGES(RelayTokenStore.RelayFormat.ANTHROPIC, true, "/v1/messages"),
        ANTHROPIC_COUNT_TOKENS(RelayTokenStore.RelayFormat.ANTHROPIC, true, "/v1/messages/count_tokens"),
        ANTHROPIC_MODELS(RelayTokenStore.RelayFormat.ANTHROPIC, false, "/v1/models"),
        OPENAI_RESPONSES(RelayTokenStore.RelayFormat.OPENAI, true, "/v1/responses"),
        OPENAI_CHAT_COMPLETIONS(RelayTokenStore.RelayFormat.OPENAI, true, "/v1/chat/completions"),
        OPENAI_MODELS(RelayTokenStore.RelayFormat.OPENAI, false, "/v1/models");

        final RelayTokenStore.RelayFormat format;

        final boolean post;

        /** The path at LiteLLM. */
        final String path;

        Endpoint(RelayTokenStore.RelayFormat format, boolean post, String path) {
            this.format = format;
            this.post = post;
            this.path = path;
        }
    }

    /** A request the relay refuses, with the status and the format's error type; never says which check failed. */
    private static final class Refusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final int status;

        final @Nullable UUID syncCompanyId;

        final @Nullable UUID syncPrincipalId;

        Refusal(int status, String message) {
            this(status, message, null, null);
        }

        Refusal(int status, String message, @Nullable UUID syncCompanyId, @Nullable UUID syncPrincipalId) {
            super(message, null, false, false);
            this.status = status;
            this.syncCompanyId = syncCompanyId;
            this.syncPrincipalId = syncPrincipalId;
        }

        static Refusal unauthorized() {
            return new Refusal(401, "The run's model token is not valid");
        }

        /** The replica holds as many request bytes as {@code relay-max-inflight-bytes} allows. */
        static Refusal busy() {
            return new Refusal(429, "The model relay holds too many request bodies at once; retry shortly");
        }

        /** The body did not arrive whole within {@code relay-body-timeout}. */
        static Refusal slowBody(Duration timeout) {
            return new Refusal(408, "The request body did not arrive within " + timeout.toSeconds() + " seconds");
        }
    }

    /**
     * How the relay takes a request's runner host (the package 2 contract, open point 6): {@code BOUND}, the host's own
     * token is the run token's host's; {@code LEGACY}, no host token from a host older than
     * {@link RunnerVersions#HOST_TOKEN_TO_RELAY}, accepted while the flag allows; {@code REFUSED}, anything else.
     */
    enum HostCheck {
        BOUND, LEGACY, REFUSED
    }

    /**
     * The runner host check of a request whose run token is valid.
     *
     * @param tokenHost        the host the run token was made for (and its run is {@code RUNNING} on)
     * @param presentedHost    the host of the {@code X-Tinfirm-Runner-Token} the request sent, authenticated; null
     *                         when it sent none
     * @param hostVersion      the token's host's latest {@code hello.runnerVersion}
     * @param requireHostToken {@code tinfirm.model.relay-require-host-token}
     */
    static HostCheck hostCheck(UUID tokenHost, @Nullable UUID presentedHost, @Nullable String hostVersion,
            boolean requireHostToken) {
        if (presentedHost != null) {
            return presentedHost.equals(tokenHost) ? HostCheck.BOUND : HostCheck.REFUSED;
        }
        if (requireHostToken || RunnerVersions.sendsHostTokenToRelay(hostVersion)) {
            return HostCheck.REFUSED;
        }
        return HostCheck.LEGACY;
    }

    /**
     * What a request may use: its token, the gateway, the key (and the secret it was revealed from), the models it
     * allows, and its host's limit.
     */
    record Plan(RelayTokenStore.TokenRow token, Gateway gateway, String key, UUID secretId, List<String> models,
            int hostLimit) {

        /** Never the paying principal's key, nor the token's hash. */
        @Override
        public String toString() {
            return "Plan[runId=" + token.runId() + ", epoch=" + token.epoch() + ", runnerHostId="
                    + token.runnerHostId() + ", gateway=" + gateway + ", key=***, secretId=" + secretId + ", models="
                    + models + ", hostLimit=" + hostLimit + "]";
        }
    }

    /** A key revealed for a token, while its run uses it. */
    private static final class CachedKey {

        final UUID runId;

        final UUID secretId;

        final String key;

        volatile Instant lastUsed;

        CachedKey(UUID runId, UUID secretId, String key, Instant lastUsed) {
            this.runId = runId;
            this.secretId = secretId;
            this.key = key;
            this.lastUsed = lastUsed;
        }

        @Override
        public String toString() {
            return "CachedKey[runId=" + runId + ", key=***]";
        }
    }

    /** Serves one relayed request; never inside a transaction. */
    void relay(Endpoint endpoint, HttpServletRequest request, HttpServletResponse response) throws IOException {
        NoTransaction.require("The model relay");
        Plan plan;
        try {
            plan = plan(endpoint, request.getHeader("Authorization"), hostToken(request));
        }
        catch (Refusal refusal) {
            if (refusal.syncCompanyId != null && refusal.syncPrincipalId != null) {
                work.syncAfterCommit(refusal.syncCompanyId, refusal.syncPrincipalId);
            }
            error(response, endpoint.format, refusal.status, refusal.getMessage());
            return;
        }
        RelayTokenStore.TokenRow token = plan.token();
        if (!acquire(perRun, token.runId(), properties.relayRunConcurrency())) {
            tooMany(response, endpoint.format, "the run");
            return;
        }
        try {
            if (!acquire(perHost, token.runnerHostId(), plan.hostLimit())) {
                tooMany(response, endpoint.format, "its runner host");
                return;
            }
            try {
                forward(endpoint, plan, request, response);
            }
            finally {
                release(perHost, token.runnerHostId());
            }
        }
        finally {
            release(perRun, token.runId());
        }
    }

    /** Forgets the keys revealed for the run's tokens (it ended). */
    void forgetRun(UUID runId) {
        revealed.values().removeIf(cached -> cached.runId.equals(runId));
    }

    /** Forgets the keys no request used for a while, on every replica. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    void forgetIdle() {
        Instant before = time.now().minus(KEEP_KEY);
        revealed.values().removeIf(cached -> cached.lastUsed.isBefore(before));
    }

    // ---- the plan ----------------------------------------------------------------------------------------------

    /**
     * The request's {@code X-Tinfirm-Runner-Token}: null when it sent none, its value when it sent one, and a value no
     * token has when it sent more than one.
     */
    private static @Nullable String hostToken(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HOST_TOKEN_HEADER);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String first = values.nextElement();
        return values.hasMoreElements() ? "" : first;
    }

    private Plan plan(Endpoint endpoint, @Nullable String authorization, @Nullable String hostToken) {
        String token = RelayTokenStore.fromAuthorization(authorization).orElseThrow(Refusal::unauthorized);
        String hash = RelayTokenStore.hash(token);
        UUID companyId = transactions.unscoped(() -> tokens.companyOf(hash)).orElseThrow(Refusal::unauthorized);
        RunModelTokens active = runTokens.getIfUnique();
        if (active == null) {
            throw Refusal.unauthorized();
        }
        // The host's own token, when sent, as the runner's other endpoints take it (by its hash, outside the company):
        // a host of the token's company, or a machine present in it (ADR-0092), or the request is refused.
        UUID presentedHost = null;
        if (hostToken != null) {
            presentedHost = hostAuthentication.authenticateToken(hostToken)
                .flatMap(host -> host.in(companyId))
                .map(ConnectedHost::runnerHostId)
                .orElseThrow(Refusal::unauthorized);
        }
        UUID presented = presentedHost;
        return transactions.inCompany(companyId, () -> {
            RelayTokenStore.TokenRow row = tokens.find(companyId, hash)
                .filter(candidate -> candidate.revokedAt() == null && candidate.format() == endpoint.format)
                .orElseThrow(Refusal::unauthorized);
            if (!active.active(companyId, row.runId(), row.epoch(), row.runnerHostId())) {
                throw Refusal.unauthorized();
            }
            RunnerHostInfo host = runners.host(companyId, row.runnerHostId()).orElseThrow(Refusal::unauthorized);
            checkHost(row, host, presented);
            if (companySwitches.runsStopped(companyId).isPresent()) {
                // G4: nothing of the company runs while its runs are stopped (package 6, §5.4).
                throw new Refusal(503, "The company's runs are stopped");
            }
            ModelSettingsStore.SettingsRow settings = settingsStore.find(companyId)
                .filter(ModelSettingsStore.SettingsRow::relayEnabled)
                .orElseThrow(() -> new Refusal(503, "The company's model gateway is not available"));
            UUID payer = row.payingPrincipalId();
            // The principal's own status, not only its key's: the key is BLOCKED only once LiteLLM blocked it.
            PrincipalInfo principal = principals.find(companyId, payer)
                .orElseThrow(() -> new Refusal(403, "The paying principal is no longer active"));
            if (!principal.active()) {
                throw new Refusal(403, principal.status() == PrincipalStatus.SUSPENDED
                        ? "The paying principal is suspended" : "The paying principal is no longer active");
            }
            ModelKeyStore.KeyRow key = keys.find(KeyHolder.principal(companyId, payer))
                .filter(ModelKeyStore.KeyRow::hasKey)
                .orElseThrow(() -> new Refusal(503, "The paying principal's model key is not available yet",
                        companyId, payer));
            if (key.status() == ModelKeyStore.KeyStatus.BLOCKED) {
                throw new Refusal(403, "The paying principal is suspended");
            }
            Gateway gateway = gateways.of(settings)
                .orElseThrow(() -> new Refusal(503, "The company's model gateway is not available"));
            List<String> models = key.models() != null ? key.models() : settings.defaultModels();
            int hostLimit = Math.max(1, host.maxConcurrentRunsCeiling()) * properties.relayHostConcurrencyPerSlot();
            return new Plan(row, gateway, key(companyId, row, key), Objects.requireNonNull(key.secretId()), models,
                    hostLimit);
        });
    }

    /** The runner host check ({@link #hostCheck}): refuses with 401, or warns about a host without its token. */
    private void checkHost(RelayTokenStore.TokenRow row, RunnerHostInfo host, @Nullable UUID presented) {
        boolean required = properties.relayRequireHostToken();
        switch (hostCheck(row.runnerHostId(), presented, host.runnerVersion(), required)) {
            case BOUND -> {
            }
            case LEGACY -> warnWithoutHostToken(row, host);
            case REFUSED -> {
                if (presented != null) {
                    log.warn("Refused a model request with the token of run {}, made for runner host {}, from runner "
                            + "host {}", row.runId(), row.runnerHostId(), presented);
                }
                else {
                    log.info("Refused a model request with the token of run {} without its runner host's own token "
                            + "(host {}, runner {}{})", row.runId(), host.id(), host.runnerVersion(),
                            required ? "; the relay requires it from every host" : "");
                }
                throw Refusal.unauthorized();
            }
        }
    }

    /** Warns once a day per host and replica that a host older than 0.3.0 leaves out its own token. */
    private void warnWithoutHostToken(RelayTokenStore.TokenRow row, RunnerHostInfo host) {
        Instant now = time.now();
        boolean[] due = { false };
        warnedWithoutHostToken.compute(host.id(), (id, last) -> {
            if (last != null && last.plus(WARN_WITHOUT_HOST_TOKEN).isAfter(now)) {
                return last;
            }
            due[0] = true;
            return now;
        });
        if (due[0]) {
            log.warn("Runner host {} (runner {}) sends the model requests of its runs without its own token (run {}): "
                    + "accepted from hosts before runner {} until TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN is on; upgrade "
                    + "the host", host.id(), host.runnerVersion(), row.runId(), RunnerVersions.HOST_TOKEN_TO_RELAY);
        }
    }

    /** The key of the token's run, revealed once per token while its secret is the same. */
    private String key(UUID companyId, RelayTokenStore.TokenRow token, ModelKeyStore.KeyRow key) {
        UUID secretId = Objects.requireNonNull(key.secretId());
        Instant now = time.now();
        CachedKey cached = revealed.get(token.tokenHash());
        if (cached != null && cached.secretId.equals(secretId)) {
            cached.lastUsed = now;
            return cached.key;
        }
        try (RevealedSecret secret = vault.reveal(companyId, secretId, "run:" + token.runId())) {
            String value = secret.asString();
            revealed.put(token.tokenHash(), new CachedKey(token.runId(), secretId, value, now));
            return value;
        }
    }

    // ---- forwarding --------------------------------------------------------------------------------------------

    private void forward(Endpoint endpoint, Plan plan, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // Admitted before anything is forwarded, so that the count at the end never exceeds it; a failure here fails
        // the request before it reaches the gateway, and the runtime tries again.
        RelayTokenStore.TokenRow admitted = plan.token();
        transactions.runInCompany(admitted.companyId(),
                () -> tokens.admitted(admitted.companyId(), admitted.tokenHash()));
        try (InflightBytes.Share share = inflight.share()) {
            forward(endpoint, plan, request, response, share);
        }
    }

    private void forward(Endpoint endpoint, Plan plan, HttpServletRequest request, HttpServletResponse response,
            InflightBytes.Share share) throws IOException {
        byte[] body = null;
        if (endpoint.post) {
            try {
                body = body(request, plan, share);
            }
            catch (Refusal refusal) {
                if (refusal.status == 429) {
                    response.setHeader("Retry-After", "1");
                }
                error(response, endpoint.format, refusal.status, refusal.getMessage());
                return;
            }
        }
        HttpRequest upstream = upstreamRequest(endpoint, plan, request, body);
        RelayTokenStore.TokenRow token = plan.token();
        long sent = body != null ? body.length : 0;
        long received = 0;
        RelayUsageMeter meter = RelayUsageMeter.of(endpoint.name(), token, plan.key(),
                RelayUsageMeter.Budget.of(share));
        NoTransaction.require("A relayed model request");
        try (GuardedHosts.Registration guard = http.guard(plan.gateway())) {
            HttpResponse<Flow.Publisher<List<ByteBuffer>>> answer = http.client()
                .send(upstream, HttpResponse.BodyHandlers.ofPublisher());
            Pump pump = new Pump();
            answer.body().subscribe(pump);
            if (answer.statusCode() >= 200 && answer.statusCode() <= 299) {
                received = stream(answer, pump, response, meter);
            }
            else {
                received = refused(endpoint, plan, body, answer, pump, response);
            }
        }
        catch (LiteLlmException ex) {
            error(response, endpoint.format, 502, "The company's model gateway may not be reached");
        }
        catch (HttpTimeoutException ex) {
            error(response, endpoint.format, 504, "The company's model gateway did not answer in time");
        }
        catch (IOException ex) {
            if (!response.isCommitted()) {
                error(response, endpoint.format, 502, "The company's model gateway cannot be reached");
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        finally {
            count(token, sent, received);
            RelayUsageMeter.Answered answered = meter != null ? meter.result(RelayUsageMeter.requestModel(body)) : null;
            if (answered != null) {
                events.publishEvent(answered);
            }
            // After the usage, so that a settlement the count prompts finds it (§15.12); the listeners catch their
            // own failures.
            events.publishEvent(new ModelEvents.RelayRequestCounted(token.companyId(), token.runId()));
        }
    }

    /**
     * The request's body without LiteLLM's fields, at most the limit, of a model the key allows. The share takes twice
     * the body as its bytes arrive (as received and written anew), and keeps the body written anew.
     */
    private byte[] body(HttpServletRequest request, Plan plan, InflightBytes.Share share) throws IOException {
        long max = properties.relayMaxRequestBytes();
        long length = request.getContentLengthLong();
        if (length > max) {
            throw new Refusal(413, "The request body is larger than " + max + " bytes");
        }
        // Declared or not, the body is read as it arrives and the share taken as its bytes come (M3 of the security
        // review of 2026-10-27): a declared length reserves nothing ahead, so that requests declaring large bodies
        // and sending them slowly hold only what they sent, never the replica's whole budget.
        byte[] raw = read(request.getInputStream(), max, share, properties.relayBodyTimeout());
        RelayBody.Result result;
        try {
            result = RelayBody.transform(bodies, raw, plan.gateway().vendor() == GatewayVendor.LITELLM);
        }
        catch (RelayBody.Refused ex) {
            throw new Refusal(400, ex.getMessage());
        }
        share.keep(result.bytes().length);
        if (result.model() == null) {
            throw new Refusal(400, "The request names no model");
        }
        if (!allowed(plan.models(), result.model())) {
            throw new Refusal(403, "The model `" + shortText(result.model()) + "` is not one this run's model access "
                    + "allows");
        }
        return result.bytes();
    }

    /**
     * The body, its share taken as it grows, up to {@code max} bytes, whole within {@code timeout}: a body still
     * arriving after it is 408, checked as each part arrives. A read that waits for the next part ends at Tomcat's
     * {@code server.tomcat.connection-timeout}, also 408, so that a body that stalls holds its thread at most that long
     * past the deadline.
     */
    static byte[] read(InputStream in, long max, InflightBytes.Share share, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        try {
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                total += read;
                if (total > max) {
                    throw new Refusal(413, "The request body is larger than " + max + " bytes");
                }
                if (!share.add(2L * read)) {
                    throw Refusal.busy();
                }
                out.write(buffer, 0, read);
                if (System.nanoTime() - deadline > 0) {
                    throw Refusal.slowBody(timeout);
                }
            }
        }
        catch (IOException ex) {
            if (timedOut(ex)) {
                throw Refusal.slowBody(timeout);
            }
            throw ex;
        }
        return out.toByteArray();
    }

    /** Whether a read failed because the next part of the body did not come in time (Tomcat's read timeout). */
    private static boolean timedOut(IOException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the key's models allow the model: every model when the list is empty or names LiteLLM's
     * {@code all-proxy-models} or {@code all-team-models}; else the name itself or a pattern ending in {@code *}.
     */
    static boolean allowed(List<String> models, String model) {
        return RunModelAccess.allows(models, model);
    }

    private HttpRequest upstreamRequest(Endpoint endpoint, Plan plan, HttpServletRequest request,
            byte @Nullable [] body) {
        HttpRequest.Builder upstream = HttpRequest.newBuilder(URI.create(plan.gateway().modelUrl() + endpoint.path))
            .timeout(properties.relayIdleTimeout())
            .header("Authorization", "Bearer " + plan.key())
            .header("Accept-Encoding", "identity");
        adapter.tagHeaders(plan.gateway(), tags(plan.token())).forEach(upstream::header);
        for (String name : REQUEST_HEADERS) {
            String value = switch (name) {
                case "anthropic-beta" -> betas(request, name, properties.relayAnthropicBetas());
                case "openai-beta" -> betas(request, name, properties.relayOpenaiBetas());
                default -> request.getHeader(name);
            };
            if (value != null && headerValue(value)) {
                upstream.header(name, value);
            }
        }
        if (body != null) {
            upstream.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        else {
            upstream.GET();
        }
        return upstream.build();
    }

    /**
     * The values of a beta header that the relay passes on, from every line of it, as one value; null when none is.
     * Another value is dropped, not refused (M1 of the security review of 2026-10-27): a beta may let the provider
     * act beyond the model (an MCP connector, code execution, a fetch of any URL).
     */
    private static @Nullable String betas(HttpServletRequest request, String name, @Nullable List<String> allowed) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || allowed == null) {
            return null;
        }
        return LiteLlmRequests.allowedBetas(Collections.list(values), allowed);
    }

    /** The run's tags: its id, conversation and agent (§6.1; exact spend per run), in the adapter's headers. */
    static String tags(RelayTokenStore.TokenRow token) {
        List<String> tags = new ArrayList<>();
        tags.add("run:" + token.runId());
        if (token.conversationId() != null) {
            tags.add("conversation:" + token.conversationId());
        }
        if (token.agentId() != null) {
            tags.add("agent:" + token.agentId());
        }
        return String.join(",", tags);
    }

    private static boolean headerValue(String value) {
        return value.length() <= MAX_HEADER_VALUE
                && value.chars().allMatch(c -> c == '\t' || c >= 0x20 && c < 0x7f || c >= 0x80 && c <= 0xff);
    }

    /** Streams a successful answer back, part by part; the bytes passed on. */
    private long stream(HttpResponse<?> answer, Pump pump, HttpServletResponse response,
            @Nullable RelayUsageMeter meter) throws InterruptedException {
        response.setStatus(answer.statusCode());
        copyHeaders(answer.headers(), response);
        if (meter != null) {
            meter.answered();
        }
        long written = 0;
        try {
            OutputStream out = response.getOutputStream();
            response.flushBuffer();
            Duration idle = properties.relayIdleTimeout();
            byte[] chunk = new byte[16 * 1024];
            while (true) {
                Object item = pump.next(idle);
                if (item == null) {
                    log.info("A relayed model answer was idle for {} and was cut off", idle);
                    pump.cancel();
                    return written;
                }
                if (item == Pump.END) {
                    return written;
                }
                if (item instanceof Throwable failure) {
                    log.info("A relayed model answer failed: {}", failure.getClass().getSimpleName());
                    return written;
                }
                @SuppressWarnings("unchecked")
                List<ByteBuffer> buffers = (List<ByteBuffer>) item;
                for (ByteBuffer buffer : buffers) {
                    while (buffer.hasRemaining()) {
                        int length = Math.min(chunk.length, buffer.remaining());
                        buffer.get(chunk, 0, length);
                        out.write(chunk, 0, length);
                        if (meter != null) {
                            meter.accept(chunk, 0, length);
                        }
                        written += length;
                    }
                }
                out.flush();
                response.flushBuffer();
                pump.more();
            }
        }
        catch (IOException ex) {
            // The runner host went away: so does the request to LiteLLM.
            pump.cancel();
            return written;
        }
    }

    /**
     * Passes on the gateway's refusal without the key that paid and what else it echoes of a key, after noting a spent
     * budget, a refused key or a model it does not serve; the bytes passed on.
     */
    private long refused(Endpoint endpoint, Plan plan, byte @Nullable [] body, HttpResponse<?> answer, Pump pump,
            HttpServletResponse response) throws IOException, InterruptedException {
        byte[] raw = pump.collect(MAX_ERROR_BYTES, properties.relayIdleTimeout());
        String text = adapter.redact(plan.gateway(),
                GatewayAnswers.withoutKey(new String(raw, StandardCharsets.UTF_8), plan.key()));
        int status = answer.statusCode();
        RelayTokenStore.TokenRow token = plan.token();
        switch (adapter.classify(plan.gateway(), status, text)) {
            case BUDGET_SPENT -> {
                Instant resetsAt = budgetSpent(token.companyId(), token.payingPrincipalId());
                return budgetSpentAnswer(response, endpoint.format, resetsAt);
            }
            case KEY_REFUSED -> keyRefused(token.companyId(), token.payingPrincipalId(), plan.secretId(), status,
                    reason(text));
            case MODEL_NOT_SERVED -> {
                String model = RelayUsageMeter.requestModel(body);
                if (model != null) {
                    notServed(token, model);
                }
            }
            case OTHER -> {
                // Passed on as it came.
            }
        }
        response.setStatus(status);
        copyHeaders(answer.headers(), response);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
        return bytes.length;
    }

    private static void copyHeaders(HttpHeaders headers, HttpServletResponse response) {
        headers.map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean passes = RESPONSE_HEADERS.contains(lower)
                    || RESPONSE_HEADER_PREFIXES.stream().anyMatch(lower::startsWith);
            if (passes) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
    }

    private static String reason(String text) {
        try {
            JsonNode message = JSON.readTree(text).path("error").path("message");
            if (message.isString()) {
                return shortText(message.stringValue());
            }
        }
        catch (JacksonException ex) {
            // Not JSON: the text itself.
        }
        return shortText(text);
    }

    // ---- what LiteLLM said of the key -------------------------------------------------------------------------

    /** Notes the spent budget on the payer's key; when it resets, as the gateway last said, or null. */
    private @Nullable Instant budgetSpent(UUID companyId, UUID principalId) {
        Instant[] resetsAt = {null};
        transactions.runInCompany(companyId, () -> {
            KeyHolder holder = KeyHolder.principal(companyId, principalId);
            Optional<ModelKeyStore.KeyRow> row = keys.lock(holder);
            if (row.isPresent()) {
                resetsAt[0] = row.get().budgetResetsAt();
            }
            if (row.isEmpty() || row.get().status() == ModelKeyStore.KeyStatus.BUDGET_SPENT) {
                return;
            }
            keys.recordStatus(holder, ModelKeyStore.KeyStatus.BUDGET_SPENT, null, null, time.now());
            AuditRecord.Builder audit = holder
                .audit(AuditRecord.company(companyId, AuditAction.MODEL_KEY_BUDGET_SPENT, CurrentActor.actor()));
            if (row.get().budgetResetsAt() != null) {
                audit.detail("resetsAt", row.get().budgetResetsAt().toString());
            }
            auditLog.record(audit.build());
            events.publishEvent(new ModelEvents.ModelKeyStatusChanged(companyId, principalId,
                    ModelKeyStore.KeyStatus.BUDGET_SPENT.name()));
        });
        // Its reset time, as the gateway knows it.
        work.syncAfterCommit(companyId, principalId);
        return resetsAt[0];
    }

    private void keyRefused(UUID companyId, UUID principalId, UUID secretId, int status, String reason) {
        boolean[] moved = {false};
        transactions.runInCompany(companyId, () -> {
            KeyHolder holder = KeyHolder.principal(companyId, principalId);
            Optional<ModelKeyStore.KeyRow> row = keys.lock(holder);
            if (row.isPresent() && !secretId.equals(row.get().secretId())) {
                // A rotation replaced the key after the request took it, and blocked it: the current key is not the
                // one LiteLLM refused.
                moved[0] = true;
                return;
            }
            if (row.isEmpty() || row.get().status() == ModelKeyStore.KeyStatus.ERROR) {
                return;
            }
            keys.recordStatus(holder, ModelKeyStore.KeyStatus.ERROR, "The gateway refused the key (" + status + "): "
                    + reason, null, time.now());
            auditLog.record(holder
                .audit(AuditRecord.company(companyId, AuditAction.MODEL_KEY_REFUSED, CurrentActor.actor()))
                .detail("status", status)
                .detail("reason", reason)
                .build());
            events.publishEvent(new ModelEvents.ModelKeyStatusChanged(companyId, principalId,
                    ModelKeyStore.KeyStatus.ERROR.name()));
        });
        if (!moved[0]) {
            // The sync repairs the key, or keeps the error.
            work.syncAfterCommit(companyId, principalId);
        }
    }

    private void count(RelayTokenStore.TokenRow token, long sent, long received) {
        try {
            transactions.runInCompany(token.companyId(),
                    () -> tokens.count(token.companyId(), token.tokenHash(), sent, received));
        }
        catch (RuntimeException ex) {
            log.warn("The relayed request of run {} was not counted", token.runId(), ex);
        }
    }

    /** Notes on the token the model LiteLLM does not serve: the run's end says so ({@code MODEL_NOT_SERVED}). */
    private void notServed(RelayTokenStore.TokenRow token, String model) {
        String name = model.length() <= MAX_MODEL_NAME ? model : model.substring(0, MAX_MODEL_NAME);
        try {
            transactions.runInCompany(token.companyId(),
                    () -> tokens.notServed(token.companyId(), token.tokenHash(), name));
        }
        catch (RuntimeException ex) {
            log.warn("The model run {} asked for and LiteLLM does not serve was not noted", token.runId(), ex);
        }
    }

    // ---- answers of the relay itself ---------------------------------------------------------------------------

    private static void tooMany(HttpServletResponse response, RelayTokenStore.RelayFormat format, String whose)
            throws IOException {
        response.setHeader("Retry-After", "1");
        error(response, format, 429, "Too many model requests of " + whose + " at once");
    }

    /** An error in the format's own shape, so that the runtime shows it as the provider's. */
    static void error(HttpServletResponse response, RelayTokenStore.RelayFormat format, int status, String message)
            throws IOException {
        error(response, format, status,
                format == RelayTokenStore.RelayFormat.ANTHROPIC ? anthropicType(status) : openAiType(status), message);
    }

    /** The bytes written. */
    private static int error(HttpServletResponse response, RelayTokenStore.RelayFormat format, int status, String type,
            String message) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        if (format == RelayTokenStore.RelayFormat.ANTHROPIC) {
            body.put("type", "error");
            body.putObject("error").put("type", type).put("message", message);
        }
        else {
            body.putObject("error")
                .put("message", message)
                .put("type", type)
                .putNull("param")
                .put("code", String.valueOf(status));
        }
        byte[] bytes = JSON.writeValueAsBytes(body);
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(bytes);
        response.flushBuffer();
        return bytes.length;
    }

    /** The relay's own answer to a spent budget ({@link #BUDGET_SPENT}), with its reset time when known. */
    private static long budgetSpentAnswer(HttpServletResponse response, RelayTokenStore.RelayFormat format,
            @Nullable Instant resetsAt) throws IOException {
        String message = resetsAt != null ? BUDGET_SPENT + "; it resets at " + resetsAt + "." : BUDGET_SPENT + ".";
        return error(response, format, 429, BUDGET_SPENT_TYPE, message);
    }

    private static String anthropicType(int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 413 -> "request_too_large";
            case 429 -> "rate_limit_error";
            default -> "api_error";
        };
    }

    private static String openAiType(int status) {
        return switch (status) {
            case 400, 413 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 429 -> "rate_limit_error";
            default -> "api_error";
        };
    }

    private static String shortText(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 299) + "…";
    }

    // ---- limits ------------------------------------------------------------------------------------------------

    private static boolean acquire(Map<UUID, Integer> counts, UUID id, int limit) {
        boolean[] acquired = {false};
        counts.compute(id, (ignored, count) -> {
            int current = count != null ? count : 0;
            if (current >= limit) {
                return count;
            }
            acquired[0] = true;
            return current + 1;
        });
        return acquired[0];
    }

    private static void release(Map<UUID, Integer> counts, UUID id) {
        counts.computeIfPresent(id, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    // ---- the answer's parts ------------------------------------------------------------------------------------

    /**
     * Takes LiteLLM's answer one part at a time: the next part is asked for only when the previous one was written to
     * the runner host, so that nothing piles up in memory, and a wait for a part can time out.
     */
    private static final class Pump implements Flow.Subscriber<List<ByteBuffer>> {

        static final Object END = new Object();

        private final BlockingQueue<Object> items = new LinkedBlockingQueue<>();

        private Flow.@Nullable Subscription subscription;

        private boolean cancelled;

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (cancelled) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            items.add(item);
        }

        @Override
        public void onError(Throwable failure) {
            items.add(failure);
        }

        @Override
        public void onComplete() {
            items.add(END);
        }

        /** The next part, {@link #END}, a failure, or null when none came within {@code idle}. */
        @Nullable Object next(Duration idle) throws InterruptedException {
            return items.poll(idle.toNanos(), TimeUnit.NANOSECONDS);
        }

        synchronized void more() {
            if (subscription != null && !cancelled) {
                subscription.request(1);
            }
        }

        synchronized void cancel() {
            cancelled = true;
            if (subscription != null) {
                subscription.cancel();
            }
        }

        /** Up to {@code max} bytes of the answer; the rest is not read. */
        byte[] collect(int max, Duration idle) throws InterruptedException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (out.size() < max) {
                Object item = next(idle);
                if (item == null || item == END || item instanceof Throwable) {
                    break;
                }
                @SuppressWarnings("unchecked")
                List<ByteBuffer> buffers = (List<ByteBuffer>) item;
                for (ByteBuffer buffer : buffers) {
                    int length = Math.min(buffer.remaining(), max - out.size());
                    byte[] bytes = new byte[length];
                    buffer.get(bytes);
                    out.write(bytes, 0, length);
                }
                more();
            }
            cancel();
            return out.toByteArray();
        }
    }
}
