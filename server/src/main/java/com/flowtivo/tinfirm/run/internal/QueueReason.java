package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.run.QueueReasonCode;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Why a QUEUED run waits ({@link QueueReasonCode}; the API's {@code QueueReason} without {@code since}). Which fields
 * are set depends on the code; the others are null. Stored as {@code run.queue_reason} and, for the fields,
 * {@code run.queue_reason_details}: a JSON object with the API's names that leaves null fields out.
 *
 * @param reason          the machine-readable cause beside the code: package 2's refusal or wait
 *                        ({@code PRINCIPAL_INACTIVE}, {@code SUBSCRIPTION_GONE}, …) for {@code MODEL_ACCESS_UNAVAILABLE}
 * @param credentialLabel a subscription's label ({@code CREDENTIAL_POOL_FULL}, and a subscription's run waiting for
 *                        its host)
 */
record QueueReason(QueueReasonCode code, @Nullable UUID runnerHostId, @Nullable String runnerHostName,
        @Nullable String credentialLabel, @Nullable Integer used, @Nullable Integer limit,
        @Nullable Instant lastSeenAt, @Nullable Instant retryAt, @Nullable String reason,
        @Nullable String rejectCode, @Nullable String message, @Nullable RuntimeKind runtime) {

    /** Texts from hosts and other modules are cut to this length. */
    static final int MAX_MESSAGE_LENGTH = 500;

    private static final int MAX_CODE_LENGTH = 64;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // ---- the reasons, in the order of the dispatcher's checks ---------------------------------------------------

    static QueueReason of(QueueReasonCode code) {
        return new QueueReason(code, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The company's runs are stopped (package 6, §5.4): every turn waits until an admin lifts the stop. */
    static QueueReason companyStopped() {
        return of(QueueReasonCode.COMPANY_STOPPED).withMessage("The company's runs are stopped by an admin; this "
                + "turn waits until the stop is lifted.");
    }

    /** The workspace holds a repository of a paused system (package 6, §5.4): the turn waits for its resume. */
    static QueueReason systemPaused(String systemName) {
        return of(QueueReasonCode.SYSTEM_PAUSED).withMessage(systemName + " is paused by an admin; this turn needs "
                + "its repositories and waits until it is resumed.");
    }

    /** The agent is paused (I18): its turns wait until it is resumed. */
    static QueueReason agentPaused(String agentName) {
        return of(QueueReasonCode.AGENT_PAUSED).withMessage("The agent " + agentName
                + " is paused; its turns wait until it is resumed.");
    }

    /** A binding of the workspace waits, as the conversation said. */
    static QueueReason accountRequired(String message) {
        return of(QueueReasonCode.ACCOUNT_REQUIRED).withMessage(message);
    }

    /** The payer's budget is spent until its reset. */
    static QueueReason budgetSpent(@Nullable Instant resetsAt, String message) {
        return of(QueueReasonCode.BUDGET_SPENT).withRetryAt(resetsAt).withMessage(message);
    }

    static QueueReason keyError(String message) {
        return of(QueueReasonCode.KEY_ERROR).withMessage(message);
    }

    /** The payer has no key at a gateway whose keys its team provides (ADR-0094). */
    static QueueReason noModelKey(String message) {
        return of(QueueReasonCode.NO_MODEL_KEY).withMessage(message);
    }

    /** A subscription's personal host is not connected (package 2's {@code Wait(HOST_OFFLINE)}). */
    static QueueReason subscriptionHostOffline(@Nullable RunnerHostInfo host, @Nullable String label,
            String message) {
        QueueReason reason = of(QueueReasonCode.HOST_OFFLINE).withMessage(message).withLabel(label);
        return host != null ? reason.withHost(host.id(), host.name()).withLastSeenAt(host.lastSeenAt()) : reason;
    }

    /** Package 2 refuses the run's model access: no gateway, an inactive payer, a subscription gone. */
    static QueueReason modelAccessUnavailable(String refusal, String message) {
        return of(QueueReasonCode.MODEL_ACCESS_UNAVAILABLE).withReason(refusal).withMessage(message);
    }

    static QueueReason noHostForCredentials(RuntimeKind runtime, String message) {
        return of(QueueReasonCode.NO_HOST_FOR_CREDENTIALS).withRuntime(runtime).withMessage(message);
    }

    static QueueReason noRunnerHost(@Nullable RuntimeKind runtime, @Nullable String message) {
        QueueReason reason = of(QueueReasonCode.NO_RUNNER_HOST).withMessage(message);
        return runtime != null ? reason.withRuntime(runtime) : reason;
    }

    static QueueReason hostOffline(RunnerHostInfo host) {
        return of(QueueReasonCode.RUNNER_HOST_OFFLINE).withHost(host.id(), host.name())
            .withLastSeenAt(host.lastSeenAt());
    }

    static QueueReason hostDraining(RunnerHostInfo host) {
        return of(QueueReasonCode.RUNNER_HOST_DRAINING).withHost(host.id(), host.name());
    }

    static QueueReason poolFull(RunnerHostInfo host, String label, int used, int limit) {
        return of(QueueReasonCode.CREDENTIAL_POOL_FULL).withHost(host.id(), host.name()).withLabel(label)
            .withUsage(used, limit);
    }

    /**
     * Every host that may take it is full. {@code used} and {@code limit} are summed over those hosts; {@code host} is
     * set when there is only one.
     */
    static QueueReason hostsFull(@Nullable RunnerHostInfo host, int used, int limit) {
        QueueReason full = of(QueueReasonCode.RUNNER_HOST_FULL).withUsage(used, limit);
        return host != null ? full.withHost(host.id(), host.name()) : full;
    }

    /** Sent to the chosen host; waiting for {@code run.accepted}. */
    static QueueReason assigned(RunnerHostInfo host, @Nullable String label) {
        return of(QueueReasonCode.ASSIGNED).withHost(host.id(), host.name()).withLabel(label);
    }

    /**
     * The host rejected the run, which is dispatched again at {@code retryAt} at the earliest: {@code TASK_RUN_ACTIVE}
     * is {@code PREVIOUS_RUN_ACTIVE}; any other code, or none, is {@code RUNNER_HOST_REJECTED} with the host's reason.
     */
    static QueueReason rejected(@Nullable UUID runnerHostId, @Nullable String runnerHostName,
            @Nullable String rejectCode, String reason, Instant retryAt) {
        if (RunnerMessages.TASK_RUN_ACTIVE.equals(rejectCode)) {
            return of(QueueReasonCode.PREVIOUS_RUN_ACTIVE).withHost(runnerHostId, runnerHostName)
                .withRetryAt(retryAt);
        }
        return new QueueReason(QueueReasonCode.RUNNER_HOST_REJECTED, runnerHostId, runnerHostName, null, null, null,
                null, retryAt, null, cut(rejectCode, MAX_CODE_LENGTH), cut(reason, MAX_MESSAGE_LENGTH), null);
    }

    /**
     * The conversation's trigger is paused (the package 4 contract, §5.11 and §11): its turns wait until it is resumed,
     * as the conversation said.
     */
    static QueueReason triggerPaused(String message) {
        return of(QueueReasonCode.TRIGGER_PAUSED).withMessage(message);
    }

    /**
     * The agent has skills, and none of the connected hosts that could take the run lists {@code run.skills}
     * (ADR-0064): the run waits for a host that delivers them rather than running without.
     */
    static QueueReason noHostForSkills(String message) {
        return of(QueueReasonCode.NO_HOST_FOR_SKILLS).withMessage(message);
    }

    /**
     * An automatic retry of a failed turn (ADR-0073) waits until {@code at}: the {@code attempt}th of at most
     * {@code attempts} in a row, in {@code used} and {@code limit}.
     */
    static QueueReason retryScheduled(Instant at, int attempt, int attempts) {
        return of(QueueReasonCode.RETRY_SCHEDULED).withRetryAt(at).withUsage(attempt, attempts);
    }

    /** Anything else, explained in {@code message}. */
    static QueueReason other(String message) {
        return of(QueueReasonCode.OTHER).withMessage(message);
    }

    // ---- canonical text ------------------------------------------------------------------------------------------

    /**
     * The canonical English text, for the run page and the inbox. A missing optional fact leaves its clause out
     * rather than printing {@code null}.
     */
    String text() {
        return switch (code) {
            case COMPANY_STOPPED -> message != null ? message : "The company's runs are stopped.";
            case SYSTEM_PAUSED -> message != null ? message : "A system of the workspace is paused.";
            case AGENT_PAUSED -> message != null ? message : "The agent is paused.";
            case ACCOUNT_REQUIRED -> message != null ? message
                    : "An account of the workspace waits for a person to connect it.";
            // The reset is retryAt, for the reader's own clock and locale: never an instant in the words.
            case BUDGET_SPENT -> "The budget that pays for this conversation is spent"
                    + (retryAt != null ? "; the turn goes on once it resets." : ".");
            case KEY_ERROR -> "The model key that pays for this conversation cannot be used right now"
                    + (message != null ? ": " + message : "") + ".";
            case NO_MODEL_KEY -> "Whoever pays for this conversation has no key at the company's model gateway yet: "
                    + "an admin enters it, or they enter their own where the company allows it.";
            case HOST_OFFLINE -> "The personal runner host " + (runnerHostName != null ? runnerHostName + " " : "")
                    + "of the subscription is offline.";
            case MODEL_ACCESS_UNAVAILABLE -> message != null ? message : "The conversation's model access is not "
                    + "available.";
            case NO_HOST_FOR_CREDENTIALS -> message != null ? message : "No runner host may run this conversation's "
                    + "credentials.";
            case NO_RUNNER_HOST -> message != null ? message : "No connected runner host can run the runtime "
                    + (runtime != null ? runtime.name() : "of the agent") + ".";
            case RUNNER_HOST_OFFLINE -> "Runner host " + runnerHostName + " is offline.";
            case RUNNER_HOST_DRAINING -> "Runner host " + runnerHostName + " is finishing its runs before it restarts.";
            case CREDENTIAL_POOL_FULL -> "Waiting for a free slot of the subscription " + credentialLabel + " (" + used
                    + " of " + limit + " in use).";
            case RUNNER_HOST_FULL -> "Waiting for a free slot on "
                    + (runnerHostName != null ? runnerHostName : "a runner host") + " (" + used + " of " + limit
                    + " in use).";
            case PREVIOUS_RUN_ACTIVE -> "The previous turn of this conversation is still ending on " + runnerHostName
                    + ".";
            case RUNNER_HOST_REJECTED -> "Runner host " + runnerHostName + " could not start the run"
                    + (message != null ? ": " + message : "") + ".";
            case ASSIGNED -> "Sent to " + runnerHostName + "; waiting for it to start the run.";
            case TRIGGER_PAUSED -> message != null ? message : "The conversation's trigger is paused; its turns wait "
                    + "until it is resumed.";
            case NO_HOST_FOR_SKILLS -> message != null ? message : "The agent has skills, and no connected runner "
                    + "host that may run this conversation delivers skills yet.";
            // The time is retryAt, for the reader's own clock and locale, as BUDGET_SPENT's.
            case RETRY_SCHEDULED -> "The previous turn failed for a reason that may pass; Tinfirm retries it by itself"
                    + (used != null && limit != null ? " (automatic retry " + used + " of " + limit + ")" : "") + ".";
            case OTHER -> message != null ? message : "The run waits.";
        };
    }

    // ---- storage -------------------------------------------------------------------------------------------------

    /** {@code run.queue_reason_details}: the fields with the API's names; null fields are left out. */
    String detailsJson() {
        Map<String, Object> details = new LinkedHashMap<>();
        put(details, "runnerHostId", runnerHostId);
        put(details, "runnerHostName", runnerHostName);
        put(details, "credentialLabel", credentialLabel);
        put(details, "used", used);
        put(details, "limit", limit);
        put(details, "lastSeenAt", lastSeenAt);
        put(details, "retryAt", retryAt);
        put(details, "reason", reason);
        put(details, "rejectCode", rejectCode);
        put(details, "message", message);
        put(details, "runtime", runtime != null ? runtime.name() : null);
        return JSON.writeValueAsString(details);
    }

    /** Reads the stored columns; null when the run has no reason. */
    static @Nullable QueueReason fromColumns(@Nullable String code, @Nullable String detailsJson) {
        if (code == null) {
            return null;
        }
        JsonNode details = detailsJson != null ? JSON.readTree(detailsJson) : JSON.createObjectNode();
        String runtime = text(details, "runtime");
        return new QueueReason(QueueReasonCode.valueOf(code), uuid(details, "runnerHostId"),
                text(details, "runnerHostName"), text(details, "credentialLabel"), integer(details, "used"),
                integer(details, "limit"), instantOf(details, "lastSeenAt"), instantOf(details, "retryAt"),
                text(details, "reason"), text(details, "rejectCode"), text(details, "message"),
                runtime != null ? RuntimeKind.valueOf(runtime) : null);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private QueueReason withHost(@Nullable UUID hostId, @Nullable String hostName) {
        return new QueueReason(code, hostId, hostName, credentialLabel, used, limit, lastSeenAt, retryAt, reason,
                rejectCode, message, runtime);
    }

    private QueueReason withLabel(@Nullable String label) {
        return new QueueReason(code, runnerHostId, runnerHostName, label, used, limit, lastSeenAt, retryAt, reason,
                rejectCode, message, runtime);
    }

    private QueueReason withLastSeenAt(@Nullable Instant at) {
        return new QueueReason(code, runnerHostId, runnerHostName, credentialLabel, used, limit, at, retryAt, reason,
                rejectCode, message, runtime);
    }

    private QueueReason withRetryAt(@Nullable Instant at) {
        return new QueueReason(code, runnerHostId, runnerHostName, credentialLabel, used, limit, lastSeenAt, at,
                reason, rejectCode, message, runtime);
    }

    private QueueReason withReason(@Nullable String cause) {
        return new QueueReason(code, runnerHostId, runnerHostName, credentialLabel, used, limit, lastSeenAt, retryAt,
                cut(cause, MAX_CODE_LENGTH), rejectCode, message, runtime);
    }

    private QueueReason withMessage(@Nullable String text) {
        return new QueueReason(code, runnerHostId, runnerHostName, credentialLabel, used, limit, lastSeenAt, retryAt,
                reason, rejectCode, cut(text, MAX_MESSAGE_LENGTH), runtime);
    }

    private QueueReason withUsage(int usedSlots, int slotLimit) {
        return new QueueReason(code, runnerHostId, runnerHostName, credentialLabel, usedSlots, slotLimit, lastSeenAt,
                retryAt, reason, rejectCode, message, runtime);
    }

    private QueueReason withRuntime(RuntimeKind kind) {
        return new QueueReason(code, runnerHostId, runnerHostName, credentialLabel, used, limit, lastSeenAt, retryAt,
                reason, rejectCode, message, kind);
    }

    private static @Nullable String cut(@Nullable String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private static void put(Map<String, Object> details, String name, @Nullable Object value) {
        if (value != null) {
            details.put(name, value instanceof UUID || value instanceof Instant ? value.toString() : value);
        }
    }

    private static @Nullable String text(JsonNode details, String name) {
        JsonNode value = details.get(name);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static @Nullable UUID uuid(JsonNode details, String name) {
        String value = text(details, name);
        return value != null ? UUID.fromString(value) : null;
    }

    private static @Nullable Integer integer(JsonNode details, String name) {
        JsonNode value = details.get(name);
        return value != null && value.isIntegralNumber() ? value.intValue() : null;
    }

    private static @Nullable Instant instantOf(JsonNode details, String name) {
        String value = text(details, name);
        return value != null ? Instant.parse(value) : null;
    }
}
