package com.flowtivo.tinfirm.runner;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import tools.jackson.databind.JsonNode;

/**
 * The runner protocol, versions 1 and 2 (CONVENTIONS §14.10, {@code protocol/runner-v1.schema.json} and
 * {@code protocol/runner-v2.schema.json}), hand-written. Every frame is one JSON text message whose {@code type}
 * names the record. Unknown fields are ignored. A schema test keeps these types and the sample messages under
 * {@code src/test/resources/protocol/} (v1) and {@code runner/crates/tinfirm-protocol/tests/samples/v2/} (v2) honest.
 * <p>
 * The host names its version in {@code hello}; the server speaks it on that connection. Version 2 replaces
 * {@code run.ask}, {@code run.repository_request}, {@code run.task_create} and their acks by one
 * {@link RunRequest} / {@link RunRequestAck} pair, and {@code run.assign.task} by {@code workItem} and {@code job},
 * and adds {@code run.assign.tools} and {@code run.finished.artifacts}. Everything else is shared.
 * <p>
 * Inbound records carry Bean Validation constraints for what the schema requires. Frames that carry secrets
 * ({@link RunAssign}) mask them in {@code toString()}; frames are never logged.
 */
public final class RunnerMessages {

    /** The protocol of {@code runner-v1.schema.json}. */
    public static final int PROTOCOL_V1 = 1;

    /** The protocol of {@code runner-v2.schema.json} (PLATFORM.md §11.1). */
    public static final int PROTOCOL_V2 = 2;

    private static final String LABEL = "^[a-z0-9][a-z0-9-]{0,62}$";

    /** {@code error} codes sent by the server. */
    public static final String BAD_MESSAGE = "BAD_MESSAGE";

    public static final String UNKNOWN_TYPE = "UNKNOWN_TYPE";

    public static final String STALE_EPOCH = "STALE_EPOCH";

    public static final String UNKNOWN_RUN = "UNKNOWN_RUN";

    public static final String UNKNOWN_CREDENTIAL = "UNKNOWN_CREDENTIAL";

    public static final String UNSUPPORTED_PROTOCOL = "UNSUPPORTED_PROTOCOL";

    /**
     * A company runner host reported credentials in its {@code hello}: only a member's personal host holds credentials
     * (the package 2 contract, M5), so they are ignored. The connection goes on.
     */
    public static final String PERSONAL_HOST_CREDENTIAL_ONLY = "PERSONAL_HOST_CREDENTIAL_ONLY";

    /**
     * A runner host reported, in its {@code hello}, credentials of a kind this server does not know: a host of another
     * version's (a {@code setup-token} token, {@code CLAUDE_OAUTH_TOKEN}, of a host before ADR-0085). They are ignored
     * and named; the host's other credentials and its runtimes serve, and the connection goes on.
     */
    public static final String UNKNOWN_CREDENTIAL_KIND = "UNKNOWN_CREDENTIAL_KIND";

    /**
     * A {@code hello} that lists a feature the licence in force does not hold, before {@code welcome}: the sandbox
     * bridge ({@link #FEATURE_SANDBOX_BRIDGE}) without elastic runners (ADR-0100). The connection is closed with 1008,
     * and the host does not reconnect.
     */
    public static final String EDITION_REQUIRED = "EDITION_REQUIRED";

    /**
     * The {@code error} code, and the {@code run.request.ack} code, of a frame refused because its host, its company or
     * its run sent too many (the security review of 2026-10-27, M4): a {@code run.event} over the rate limits, a
     * {@code run.request} over them, and a {@code TOOL} request while {@code tinfirm.runner.tool-calls-in-flight} of
     * its run wait for their answer. The frame is not processed; a request may be sent again with a new id.
     */
    public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";

    /**
     * The {@code error} code of a {@code run.event} whose payload is over {@link #MAX_EVENT_PAYLOAD_BYTES}, or whose
     * text chunk is over {@link #MAX_TEXT_CHUNK_BYTES}: the event is not stored.
     */
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

    /**
     * The {@code error} code of a {@code run.event} of a run whose stored events reached
     * {@code tinfirm.runner.events-max-bytes}: the event is not stored ({@code LOG} events are dropped without it).
     */
    public static final String RUN_EVENTS_FULL = "RUN_EVENTS_FULL";

    /** The largest {@code run.event} payload, as UTF-8 JSON (the schema's {@code RunEvent}). */
    public static final int MAX_EVENT_PAYLOAD_BYTES = 256 * 1024;

    /** The largest {@code text} of an {@code AGENT_MESSAGE} or {@code AGENT_THOUGHT} event, in UTF-8. */
    public static final int MAX_TEXT_CHUNK_BYTES = 32 * 1024;

    /**
     * {@code run.rejected} code of a host that still runs an earlier run of the same task (docs/archive/v1/CONVENTIONS.md §15.4 step 1);
     * the server shows it as the queue reason {@code PREVIOUS_RUN_ACTIVE}.
     */
    public static final String TASK_RUN_ACTIVE = "TASK_RUN_ACTIVE";

    /**
     * The bound of a credential's pool, and of a host with Docker: ceilings, desired and effective values
     * ({@code $defs/Capacity}).
     */
    public static final int MAX_CAPACITY = 16;

    /**
     * The bound of a runner host's slots in protocol v2 ({@code $defs/HostCapacity}): a host that creates a sandbox per
     * run has no fixed maximum of its own (D12 of the Kubernetes runner analysis); its operator's ceiling and the
     * provider's quota bound it.
     */
    public static final int MAX_HOST_CAPACITY = 1000;

    /** The most server-executed tools one {@code run.assign} lists ({@code $defs/RunAssign.tools}). */
    public static final int MAX_TOOLS = 200;

    /** The most skills of {@code run.assign.skills} (ADR-0064): an agent's limit. */
    public static final int MAX_SKILLS = 20;

    /**
     * The optional feature of protocol 2 ({@code hello.features}) whose host takes {@link RunSteer} and answers each
     * with {@link RunSteerAck}; the server sends {@code run.steer} only to a host that lists it.
     */
    public static final String FEATURE_RUN_STEER = "run.steer";

    /**
     * The optional feature of protocol 2 ({@code hello.features}) whose host takes {@code run.assign.skills}
     * (ADR-0064): it downloads each skill and mounts it read-only where the runtime reads a user's skills. A run whose
     * agent has skills goes only to such a host.
     */
    public static final String FEATURE_RUN_SKILLS = "run.skills";

    /**
     * The optional feature of protocol 2 ({@code hello.features}) whose host takes {@code run.assign.sessionBundle}
     * and reports {@code run.finished.sessionBundle} (ADR-0097): it restores the conversation's session folders from
     * the server's bundle before the sandbox starts, and uploads them again when the run ends.
     */
    public static final String FEATURE_SESSION_BUNDLE = "session.bundle";

    /**
     * The optional feature of protocol 2 ({@code hello.features}) whose host keeps ready sandboxes (ADR-0098) and takes
     * Tinfirm's count of them (ADR-0099): it sends {@code hello.pool} and {@link HostPoolStatus}, takes
     * {@code host.config.pool} and reports {@code host.config.applied.pool}.
     */
    public static final String FEATURE_SANDBOX_POOL = "sandbox.pool";

    /**
     * The optional feature of protocol 2 ({@code hello.features}) whose host runs its agents over the sandbox bridge
     * (ADR-0095): with {@code run --sandbox-bridge}, and always with a VM per run (ADR-0096). It adds no frame; a
     * server whose licence does not hold elastic runners refuses the hello with {@link #EDITION_REQUIRED} (ADR-0100).
     */
    public static final String FEATURE_SANDBOX_BRIDGE = "sandbox.bridge";

    /** The largest session bundle, compressed ({@code run.assign.sessionBundle.sizeBytes} maximum): 32 MiB. */
    public static final int MAX_SESSION_BUNDLE_BYTES = 32 * 1024 * 1024;

    /** The longest {@code run.steer.text}, in characters. */
    public static final int MAX_STEER_TEXT = 110_000;

    private RunnerMessages() {
    }

    /** Whether the server speaks this version of the protocol. */
    public static boolean supported(int protocolVersion) {
        return protocolVersion == PROTOCOL_V1 || protocolVersion == PROTOCOL_V2;
    }

    /**
     * Parses an RFC 3339 timestamp ({@code Instant} in the schema), with {@code Z} or a numeric offset.
     *
     * @throws IllegalArgumentException if the value is not a timestamp
     */
    public static Instant parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        }
        catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Not an RFC 3339 timestamp", ex);
        }
    }

    /** Any frame. The JSON property {@code type} selects the record. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({ @JsonSubTypes.Type(value = Hello.class, name = "hello"),
            @JsonSubTypes.Type(value = Welcome.class, name = "welcome"),
            @JsonSubTypes.Type(value = RunAssign.class, name = "run.assign"),
            @JsonSubTypes.Type(value = RunAccepted.class, name = "run.accepted"),
            @JsonSubTypes.Type(value = RunRejected.class, name = "run.rejected"),
            @JsonSubTypes.Type(value = RunEvent.class, name = "run.event"),
            @JsonSubTypes.Type(value = RunHeartbeat.class, name = "run.heartbeat"),
            @JsonSubTypes.Type(value = RunFinished.class, name = "run.finished"),
            @JsonSubTypes.Type(value = RunCancel.class, name = "run.cancel"),
            @JsonSubTypes.Type(value = RunRepositoryRequest.class, name = "run.repository_request"),
            @JsonSubTypes.Type(value = RunRepositoryRequestAck.class, name = "run.repository_request.ack"),
            @JsonSubTypes.Type(value = RunTaskCreate.class, name = "run.task_create"),
            @JsonSubTypes.Type(value = RunTaskCreateAck.class, name = "run.task_create.ack"),
            @JsonSubTypes.Type(value = RunAsk.class, name = "run.ask"),
            @JsonSubTypes.Type(value = RunAskAck.class, name = "run.ask.ack"),
            @JsonSubTypes.Type(value = RunRequest.class, name = "run.request"),
            @JsonSubTypes.Type(value = RunRequestAck.class, name = "run.request.ack"),
            @JsonSubTypes.Type(value = RunSteer.class, name = "run.steer"),
            @JsonSubTypes.Type(value = RunSteerAck.class, name = "run.steer.ack"),
            @JsonSubTypes.Type(value = CredentialStatus.class, name = "credential.status"),
            @JsonSubTypes.Type(value = HostConfig.class, name = "host.config"),
            @JsonSubTypes.Type(value = HostConfigApplied.class, name = "host.config.applied"),
            @JsonSubTypes.Type(value = HostPoolStatus.class, name = "host.pool.status"),
            @JsonSubTypes.Type(value = PslUpdate.class, name = "psl.update"),
            @JsonSubTypes.Type(value = ProtocolError.class, name = "error") })
    public sealed interface Message {
    }

    /** Runner host to server. */
    public sealed interface Inbound extends Message {
    }

    /** Runner host to server, about one or more runs. */
    public sealed interface RunMessage extends Inbound {
    }

    /** A run message about one run, which it names. */
    public sealed interface OneRunMessage extends RunMessage {

        UUID runId();
    }

    /** Server to runner host. */
    public sealed interface Outbound extends Message {
    }

    // ---- hello / welcome -------------------------------------------------------------------------------------

    /**
     * The first frame of a runner host after connecting. {@code maxConcurrentRuns} and each credential's
     * {@code poolSize} are the host's ceilings (archived v1 CONVENTIONS §14.8): 1 to 16 for a credential and a host
     * with Docker, up to 1000 for a host that creates a sandbox per run. {@code pool} comes with the feature
     * {@link #FEATURE_SANDBOX_POOL}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Hello(@NotNull Integer protocolVersion,
            @NotBlank @Size(max = 100) String runnerVersion, @Nullable @Size(max = 255) String hostname,
            @Nullable @Size(max = 64) String os, @Nullable @Size(max = 64) String arch,
            @NotEmpty List<@NotNull @Pattern(regexp = "docker") String> executors,
            @NotEmpty List<@NotNull @Pattern(regexp = "container|gvisor|microvm") String> isolationTiers,
            @NotNull @Min(1) @Max(MAX_HOST_CAPACITY) Integer maxConcurrentRuns,
            @NotNull @Size(max = 100) List<@Valid @NotNull HelloCredential> credentials,
            @NotNull List<@Valid @NotNull HelloRuntime> runtimes,
            @Nullable List<@Valid @NotNull ActiveRun> activeRuns,
            @Nullable @Valid PublicSuffixListVersion publicSuffixList,
            @Nullable @Size(max = 50) List<@NotNull @Pattern(regexp = "^[a-z][a-z0-9._-]{0,63}$") String> features,
            @Nullable @Valid HelloPool pool) implements Inbound {

        /** A hello of a host before {@code hello.pool}. */
        public Hello(Integer protocolVersion, String runnerVersion, @Nullable String hostname, @Nullable String os,
                @Nullable String arch, List<String> executors, List<String> isolationTiers, Integer maxConcurrentRuns,
                List<HelloCredential> credentials, List<HelloRuntime> runtimes, @Nullable List<ActiveRun> activeRuns,
                @Nullable PublicSuffixListVersion publicSuffixList, @Nullable List<String> features) {
            this(protocolVersion, runnerVersion, hostname, os, arch, executors, isolationTiers, maxConcurrentRuns,
                    credentials, runtimes, activeRuns, publicSuffixList, features, null);
        }

        /** The optional features the host lists; empty when it lists none. */
        public Set<String> featureSet() {
            return features != null ? Set.copyOf(features) : Set.of();
        }

        /** The operator's ceiling of ready sandboxes of a host with {@link #FEATURE_SANDBOX_POOL}; else null. */
        public @Nullable Integer poolCeiling() {
            return featureSet().contains(FEATURE_SANDBOX_POOL) && pool != null ? pool.maxReady() : null;
        }
    }

    /** {@code hello.pool} (ADR-0099): the operator's {@code --pool-max-ready}; 0 turns ready sandboxes off. */
    public record HelloPool(@NotNull @Min(0) @Max(MAX_HOST_CAPACITY) Integer maxReady) {
    }

    /**
     * The version of the Public Suffix List a v2 host holds, in {@code hello.publicSuffixList}: the SHA-256 (lower-case
     * hex) of its uncompressed bytes. Absent when the host holds none.
     */
    public record PublicSuffixListVersion(@NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String sha256) {
    }

    /** Credential metadata reported in {@code hello}; the secret stays on the runner host. */
    public record HelloCredential(@NotNull @Pattern(regexp = LABEL) String label,
            @NotNull CredentialKind kind, @NotEmpty List<@NotNull RuntimeKind> runtimes,
            @NotNull @Min(1) @Max(MAX_CAPACITY) Integer poolSize) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HelloRuntime(@NotNull RuntimeKind runtime, @NotBlank String image, @Nullable String version) {
    }

    /** A run still running in the host process after a reconnect. */
    public record ActiveRun(@NotNull UUID runId, @NotNull Long epoch, @NotNull @Min(0) Long lastSeq) {
    }

    /**
     * The answer to {@code hello}. {@code protocolVersion} is the host's version, which the server speaks on this
     * connection: required under v2, ignored by a v1 host.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Welcome(int protocolVersion, UUID runnerHostId, UUID companyId, @Nullable String serverVersion,
            long heartbeatIntervalSeconds, long leaseTtlSeconds) implements Outbound {
    }

    // ---- run.assign --------------------------------------------------------------------------------------------

    /**
     * Starts a run on the host. Carries the git tokens of the workspace's repositories, and the key of a model
     * credential: never log it. A v1 host gets {@code task}; a v2 host gets {@code workItem}, {@code job} and
     * {@code tools} instead ({@link #v2}).
     *
     * @param credentialLabel         the host credential the run uses; null for a run with {@code modelAccess}
     * @param modelAccess             the company model credential the run uses (ADR-0027); null for a run with a
     *                                {@code credentialLabel}. Exactly one of the two is set.
     * @param task                    v1: the work item; null under v2
     * @param workspaces              the workspace's repositories in order, each cloned into
     *                                {@code /workspace/<dir>}; empty for work without repositories
     * @param requestableRepositories the other repositories the agent may ask for
     * @param attachments             the work item's attached files (ADR-0014); null when it has none
     * @param workItem                v2: the work item; null under v1
     * @param job                     v2: the job (thread) the run is a turn of; null under v1
     * @param tools                   v2: the server-executed tools of the run; null under v1 and when there are none
     * @param allowedHosts            v2: the network hosts the sandbox may reach besides the platform's own: the union of
     *                                the job's granted hosts and the agent's, sorted and distinct, always present (maybe
     *                                empty); null under v1
     * @param platformTools           v2: the platform tools the agent's policies allow, by name (ADR-0046); the host
     *                                lists only those (and always {@code finish} and {@code list_repositories}); null
     *                                means every platform tool, and under v1
     * @param skills                  v2, {@link #FEATURE_RUN_SKILLS}: the agent's skills; null for none
     * @param sessionBundle           v2, {@link #FEATURE_SESSION_BUNDLE}: the conversation's session bundle, for a
     *                                company host while the server keeps bundles; null otherwise
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunAssign(UUID runId, long epoch, @Nullable String credentialLabel, @Nullable ModelAccess modelAccess,
            AssignedAgent agent, @Nullable AssignedTask task, String prompt, @Nullable String resumeSessionId,
            List<Workspace> workspaces, List<RequestableRepository> requestableRepositories, Limits limits,
            @Nullable String resumePrompt, @Nullable List<AssignedAttachment> attachments,
            @Nullable AssignedWorkItem workItem, @Nullable AssignedJob job, @Nullable List<ToolSpec> tools,
            @Nullable List<String> allowedHosts, @Nullable List<String> platformTools,
            @Nullable List<AssignedSkill> skills, @Nullable SessionBundle sessionBundle) implements Outbound {

        public RunAssign {
            if ((credentialLabel == null) == (modelAccess == null)) {
                throw new IllegalArgumentException("A run uses either a credential label or model access");
            }
            if ((task == null) == (workItem == null) || (workItem == null) != (job == null)) {
                throw new IllegalArgumentException("A run names its task (v1) or its work item and job (v2)");
            }
            if (task != null && tools != null) {
                throw new IllegalArgumentException("Protocol v1 has no server-executed tools");
            }
            if (task != null && allowedHosts != null) {
                throw new IllegalArgumentException("Protocol v1 has no allowed hosts");
            }
            if (task != null && platformTools != null) {
                throw new IllegalArgumentException("Protocol v1 has no platform tool list");
            }
            if (task != null && agent.effort() != null) {
                throw new IllegalArgumentException("Protocol v1 has no agent effort");
            }
            if (workItem != null && allowedHosts == null) {
                throw new IllegalArgumentException("A protocol v2 assignment lists its allowed hosts, maybe none");
            }
            if (tools != null && tools.size() > MAX_TOOLS) {
                throw new IllegalArgumentException("At most " + MAX_TOOLS + " tools");
            }
            workspaces = List.copyOf(workspaces);
            requestableRepositories = List.copyOf(requestableRepositories);
            tools = tools != null ? List.copyOf(tools) : null;
            allowedHosts = allowedHosts != null ? List.copyOf(allowedHosts) : null;
            platformTools = platformTools != null ? List.copyOf(platformTools) : null;
            if (task != null && skills != null) {
                throw new IllegalArgumentException("Protocol v1 has no skills");
            }
            if (skills != null && skills.size() > MAX_SKILLS) {
                throw new IllegalArgumentException("At most " + MAX_SKILLS + " skills");
            }
            skills = skills != null ? List.copyOf(skills) : null;
            if (task != null && sessionBundle != null) {
                throw new IllegalArgumentException("Protocol v1 has no session bundles");
            }
        }

        /** An assignment without a session bundle. */
        public RunAssign(UUID runId, long epoch, @Nullable String credentialLabel, @Nullable ModelAccess modelAccess,
                AssignedAgent agent, @Nullable AssignedTask task, String prompt, @Nullable String resumeSessionId,
                List<Workspace> workspaces, List<RequestableRepository> requestableRepositories, Limits limits,
                @Nullable String resumePrompt, @Nullable List<AssignedAttachment> attachments,
                @Nullable AssignedWorkItem workItem, @Nullable AssignedJob job, @Nullable List<ToolSpec> tools,
                @Nullable List<String> allowedHosts, @Nullable List<String> platformTools,
                @Nullable List<AssignedSkill> skills) {
            this(runId, epoch, credentialLabel, modelAccess, agent, task, prompt, resumeSessionId, workspaces,
                    requestableRepositories, limits, resumePrompt, attachments, workItem, job, tools, allowedHosts,
                    platformTools, skills, null);
        }

        /** An assignment without skills. */
        public RunAssign(UUID runId, long epoch, @Nullable String credentialLabel, @Nullable ModelAccess modelAccess,
                AssignedAgent agent, @Nullable AssignedTask task, String prompt, @Nullable String resumeSessionId,
                List<Workspace> workspaces, List<RequestableRepository> requestableRepositories, Limits limits,
                @Nullable String resumePrompt, @Nullable List<AssignedAttachment> attachments,
                @Nullable AssignedWorkItem workItem, @Nullable AssignedJob job, @Nullable List<ToolSpec> tools,
                @Nullable List<String> allowedHosts, @Nullable List<String> platformTools) {
            this(runId, epoch, credentialLabel, modelAccess, agent, task, prompt, resumeSessionId, workspaces,
                    requestableRepositories, limits, resumePrompt, attachments, workItem, job, tools, allowedHosts,
                    platformTools, null);
        }

        /** An assignment without a platform tool list: every platform tool. */
        public RunAssign(UUID runId, long epoch, @Nullable String credentialLabel, @Nullable ModelAccess modelAccess,
                AssignedAgent agent, @Nullable AssignedTask task, String prompt, @Nullable String resumeSessionId,
                List<Workspace> workspaces, List<RequestableRepository> requestableRepositories, Limits limits,
                @Nullable String resumePrompt, @Nullable List<AssignedAttachment> attachments,
                @Nullable AssignedWorkItem workItem, @Nullable AssignedJob job, @Nullable List<ToolSpec> tools,
                @Nullable List<String> allowedHosts) {
            this(runId, epoch, credentialLabel, modelAccess, agent, task, prompt, resumeSessionId, workspaces,
                    requestableRepositories, limits, resumePrompt, attachments, workItem, job, tools, allowedHosts,
                    null);
        }

        /** A protocol v1 assignment: the work item as {@code task}. */
        public RunAssign(UUID runId, long epoch, @Nullable String credentialLabel, @Nullable ModelAccess modelAccess,
                AssignedAgent agent, AssignedTask task, String prompt, @Nullable String resumeSessionId,
                List<Workspace> workspaces, List<RequestableRepository> requestableRepositories, Limits limits,
                @Nullable String resumePrompt, @Nullable List<AssignedAttachment> attachments) {
            this(runId, epoch, credentialLabel, modelAccess, agent, task, prompt, resumeSessionId, workspaces,
                    requestableRepositories, limits, resumePrompt, attachments, null, null, null, null, null);
        }

        /**
         * A protocol v2 assignment: the work item, the job, the server-executed tools (none: empty), the allowed
         * network hosts (none: empty) and every platform tool.
         */
        public static RunAssign v2(UUID runId, long epoch, @Nullable String credentialLabel,
                @Nullable ModelAccess modelAccess, AssignedAgent agent, AssignedWorkItem workItem, AssignedJob job,
                String prompt, @Nullable String resumeSessionId, List<Workspace> workspaces,
                List<RequestableRepository> requestableRepositories, Limits limits, @Nullable String resumePrompt,
                @Nullable List<AssignedAttachment> attachments, List<ToolSpec> tools, List<String> allowedHosts) {
            return v2(runId, epoch, credentialLabel, modelAccess, agent, workItem, job, prompt, resumeSessionId,
                    workspaces, requestableRepositories, limits, resumePrompt, attachments, tools, allowedHosts, null);
        }

        /**
         * A protocol v2 assignment with the platform tools the agent's policies allow (ADR-0046; null: every platform
         * tool).
         */
        public static RunAssign v2(UUID runId, long epoch, @Nullable String credentialLabel,
                @Nullable ModelAccess modelAccess, AssignedAgent agent, AssignedWorkItem workItem, AssignedJob job,
                String prompt, @Nullable String resumeSessionId, List<Workspace> workspaces,
                List<RequestableRepository> requestableRepositories, Limits limits, @Nullable String resumePrompt,
                @Nullable List<AssignedAttachment> attachments, List<ToolSpec> tools, List<String> allowedHosts,
                @Nullable List<String> platformTools) {
            return v2(runId, epoch, credentialLabel, modelAccess, agent, workItem, job, prompt, resumeSessionId,
                    workspaces, requestableRepositories, limits, resumePrompt, attachments, tools, allowedHosts,
                    platformTools, List.of());
        }

        /**
         * A protocol v2 assignment with the agent's skills (ADR-0064; none: absent), for a host that lists
         * {@link #FEATURE_RUN_SKILLS}.
         */
        public static RunAssign v2(UUID runId, long epoch, @Nullable String credentialLabel,
                @Nullable ModelAccess modelAccess, AssignedAgent agent, AssignedWorkItem workItem, AssignedJob job,
                String prompt, @Nullable String resumeSessionId, List<Workspace> workspaces,
                List<RequestableRepository> requestableRepositories, Limits limits, @Nullable String resumePrompt,
                @Nullable List<AssignedAttachment> attachments, List<ToolSpec> tools, List<String> allowedHosts,
                @Nullable List<String> platformTools, List<AssignedSkill> skills) {
            return v2(runId, epoch, credentialLabel, modelAccess, agent, workItem, job, prompt, resumeSessionId,
                    workspaces, requestableRepositories, limits, resumePrompt, attachments, tools, allowedHosts,
                    platformTools, skills, null);
        }

        /**
         * A protocol v2 assignment with the session bundle of the conversation (ADR-0097; null: absent), for a host
         * that lists {@link #FEATURE_SESSION_BUNDLE}.
         */
        public static RunAssign v2(UUID runId, long epoch, @Nullable String credentialLabel,
                @Nullable ModelAccess modelAccess, AssignedAgent agent, AssignedWorkItem workItem, AssignedJob job,
                String prompt, @Nullable String resumeSessionId, List<Workspace> workspaces,
                List<RequestableRepository> requestableRepositories, Limits limits, @Nullable String resumePrompt,
                @Nullable List<AssignedAttachment> attachments, List<ToolSpec> tools, List<String> allowedHosts,
                @Nullable List<String> platformTools, List<AssignedSkill> skills, @Nullable SessionBundle sessionBundle) {
            return new RunAssign(runId, epoch, credentialLabel, modelAccess, agent, null, prompt, resumeSessionId,
                    workspaces, requestableRepositories, limits, resumePrompt, attachments, workItem, job,
                    tools.isEmpty() ? null : tools, allowedHosts, platformTools, skills.isEmpty() ? null : skills,
                    sessionBundle);
        }

        @Override
        public String toString() {
            return "RunAssign[runId=" + runId + ", epoch=" + epoch + ", credentialLabel=" + credentialLabel
                    + ", modelAccess=" + modelAccess + ", workspaces=" + workspaces + ", attachments="
                    + (attachments != null ? attachments.size() : 0) + ", tools=" + (tools != null ? tools.size() : 0)
                    + ", skills=" + (skills != null ? skills.size() : 0) + ", sessionBundle=" + sessionBundle + "]";
        }
    }

    /**
     * A company model credential for one run (ADR-0027, ADR-0042): the provider, a gateway's base URL or the AWS
     * region and access key ID of Bedrock, and the key, which the host passes into the sandbox environment. Absent
     * fields are not written. {@code toString()} masks the key.
     */
    public record ModelAccess(ModelAccessFormat format,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String baseUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String region,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String accessKeyId, String apiKey) {

        /** A gateway's endpoint, or a provider's own API with a null {@code baseUrl}. */
        public ModelAccess(ModelAccessFormat format, @Nullable String baseUrl, String apiKey) {
            this(format, baseUrl, null, null, apiKey);
        }

        @Override
        public String toString() {
            return "ModelAccess[format=" + format + ", baseUrl=" + baseUrl + ", region=" + region + ", accessKeyId="
                    + accessKeyId + ", apiKey=***]";
        }
    }

    /** The provider whose API a model credential's key opens ({@code $defs/ModelAccess.format}). */
    public enum ModelAccessFormat {
        @JsonProperty("anthropic")
        ANTHROPIC,
        @JsonProperty("openai")
        OPENAI,
        @JsonProperty("bedrock")
        BEDROCK,
        @JsonProperty("google")
        GOOGLE,
        @JsonProperty("openrouter")
        OPENROUTER,
        @JsonProperty("mistral")
        MISTRAL,
        @JsonProperty("groq")
        GROQ,
        @JsonProperty("xai")
        XAI,
        @JsonProperty("deepseek")
        DEEPSEEK
    }

    /**
     * The agent of a run.
     *
     * @param model  the model the runtime uses; null for the runtime's default, always written
     * @param effort v2: how much the runtime thinks (CONVENTIONS §14.10); null for the runtime's default, and under v1
     */
    public record AssignedAgent(UUID id, String name, RuntimeKind runtime,
            @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String model,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable EffortLevel effort) {

        public AssignedAgent {
            if (effort == EffortLevel.DEFAULT) {
                throw new IllegalArgumentException("The runtime's default effort is no effort");
            }
        }

        /** An agent with the runtime's default effort. */
        public AssignedAgent(UUID id, String name, RuntimeKind runtime, @Nullable String model) {
            this(id, name, runtime, model, null);
        }
    }

    /** v1: the work item of the run, under its v1 name. */
    public record AssignedTask(UUID id, String key, String title) {
    }

    /** v2: the work item of the run. */
    public record AssignedWorkItem(UUID id, String key, String title) {
    }

    /** v2: the job (thread) the run is a turn of. */
    public record AssignedJob(UUID id) {
    }

    /**
     * v2: a server-executed tool of the run, as MCP describes a tool. The host lists it in {@code tools/list} and
     * forwards its calls as {@link RunRequest} kind {@code TOOL}.
     *
     * @param name        {@code ^[a-z0-9][a-z0-9_-]{0,99}$}, never a platform tool's name
     * @param inputSchema the JSON Schema of the call's {@code arguments}
     * @param replay      whether the server may run the call again after it lost track of it (package R, §3.1), for
     *                    the runtime's information only: the server decides. Optional; a host that does not know it
     *                    ignores it, and without it the class follows the tool's effect
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolSpec(String name, String description, JsonNode inputSchema, @Nullable ToolReplay replay) {

        /** A tool without its replay class. */
        public ToolSpec(String name, String description, JsonNode inputSchema) {
            this(name, description, inputSchema, null);
        }
    }

    /**
     * {@code run.assign.tools[].replay} (package R, §3.1): {@code SAFE} when running the call again does no harm,
     * {@code UNSAFE} when it may write twice.
     */
    public enum ToolReplay {
        SAFE, UNSAFE
    }

    /**
     * A file attached to the task (ADR-0014). The host downloads it from
     * {@code GET /api/runner/v1/runs/{runId}/attachments/{id}?epoch=}, checks the size and SHA-256, and mounts it
     * read-only at {@code /attachments/<fileName>}.
     */
    /**
     * {@code run.assign.skills[]} (ADR-0064): a skill of the agent at the version it pins; the host downloads it from
     * {@code /api/runner/v1/runs/{runId}/skills/{skillId}/versions/{version}} and checks its size and hash.
     */
    public record AssignedSkill(UUID skillId, int version, String name, String sha256, long sizeBytes) {
    }

    /**
     * {@code run.assign.sessionBundle} (ADR-0097): the host uploads the run's session folders when it ends; with
     * {@code sha256} and {@code sizeBytes}, it first restores the conversation's bundle, which it downloads from
     * {@code /api/runner/v1/runs/{runId}/session-bundle} and checks.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionBundle(@Nullable String sha256, @Nullable Long sizeBytes) {

        public SessionBundle {
            if ((sha256 == null) != (sizeBytes == null)) {
                throw new IllegalArgumentException("A bundle to restore has its SHA-256 and its size");
            }
        }

        /** Upload only: nothing to restore. */
        public static SessionBundle uploadOnly() {
            return new SessionBundle(null, null);
        }
    }

    public record AssignedAttachment(UUID id, String fileName, long sizeBytes, String sha256) {
    }

    /**
     * One repository of the task, cloned into {@code /workspace/<dir>}; {@code git} is null for public repositories.
     * The host never pushes a {@code readOnly} repository (ADR-0017).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Workspace(UUID componentId, String dir, String name, String repoUrl, String defaultBranch,
            String branch, @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable GitCredentials git, String authorName,
            String authorEmail, boolean readOnly) {
    }

    /** A repository of the project that the agent may request; it is not cloned. */
    public record RequestableRepository(UUID componentId, String name, String repoUrl, boolean pendingRequest) {
    }

    /** HTTPS credentials for clone and push, used only by the runner host, never inside the sandbox. */
    public record GitCredentials(String username, String token) {

        @Override
        public String toString() {
            return "GitCredentials[username=" + username + ", token=***]";
        }
    }

    public record Limits(long maxWallClockSeconds, int maxTurns) {
    }

    // ---- run lifecycle -----------------------------------------------------------------------------------------

    public record RunAccepted(@NotNull UUID runId, @NotNull Long epoch) implements OneRunMessage {
    }

    /**
     * The host cannot take the run.
     *
     * @param reason text for humans
     * @param code   why, machine-readable (docs/archive/v1/CONVENTIONS.md §15.4 step 1), e.g. {@link #TASK_RUN_ACTIVE}; null from a host
     *               that sends none
     */
    public record RunRejected(@NotNull UUID runId, @NotNull Long epoch, @NotNull @Size(max = 2000) String reason,
            @Nullable String code) implements OneRunMessage {
    }

    /** One event; {@code seq} starts at 1 per run. {@code at} is an RFC 3339 timestamp. */
    public record RunEvent(@NotNull UUID runId, @NotNull Long epoch, @NotNull @Min(1) Long seq,
            @NotNull EventType eventType, @NotBlank String at, @NotNull JsonNode payload) implements OneRunMessage {
    }

    public record RunHeartbeat(@NotNull @Size(max = 1000) List<@Valid @NotNull HeartbeatRun> runs)
            implements RunMessage {
    }

    public record HeartbeatRun(@NotNull UUID runId, @NotNull Long epoch) {
    }

    /**
     * @param summary   the agent's last message
     * @param result    the agent's last {@code finish} ({@code finish_run} under v1) call, or null when it made none
     * @param git       one result per repository the host cloned, in workspace order; empty without repositories,
     *                  null when the host got no git result
     * @param artifacts v2: the files of {@code /output} the host uploaded as artifacts; null from a v1 host
     * @param resumed   v2: whether {@code session/load} of the run's {@code resumeSessionId} succeeded; null from a v1
     *                  host and from a v2 host older than the field, which the server stores as not known
     * @param resumeOutcome v2: why the run did not resume; null when it did, or when it ended before its host tried
     * @param sessionBundle v2, {@link #FEATURE_SESSION_BUNDLE}: what became of the run's bundle; null when none was
     *                      asked for
     */
    public record RunFinished(@NotNull UUID runId, @NotNull Long epoch, @NotNull Outcome outcome,
            @NotNull @Min(0) Long lastSeq, @Nullable FailureClass failureClass, @Nullable String failureMessage,
            @Nullable String summary, @Nullable @Valid RunResult result,
            @Nullable @Size(max = 255) String sessionId,
            @Nullable @Size(max = 20) List<@Valid @NotNull GitResult> git, @Nullable Usage usage,
            @Nullable @Size(max = 200) List<@Valid @NotNull ArtifactResult> artifacts, @Nullable Boolean resumed,
            @Nullable ResumeOutcome resumeOutcome, @Nullable SessionBundleOutcome sessionBundle) implements OneRunMessage {
    }

    /** {@code run.finished.sessionBundle} (ADR-0097): what became of the run's session bundle. */
    public enum SessionBundleOutcome {
        /** The server stored it. */
        STORED,
        /** Over the host's caps: not sent. */
        TOO_LARGE,
        /** The server refused it. */
        REFUSED,
        /** The upload failed. */
        FAILED,
        /** No session folders to pack. */
        NONE
    }

    /**
     * v2: one file of {@code /output} that the host uploaded with {@code artifact=true}.
     *
     * @param name the path relative to {@code /output}, e.g. {@code reports/q3.md}
     */
    public record ArtifactResult(@NotBlank @Size(max = 1024) String name, @NotNull UUID attachmentId,
            @NotNull @Min(0) Long sizeBytes) {
    }

    /**
     * The structured result of a run ({@code run.finished.result}). The host trims, redacts and cuts every text;
     * the server cuts them again rather than lose a run's end over a length, so the lengths are not constraints
     * here.
     *
     * @param nextSteps     null or empty for none
     * @param openQuestions null or empty for none
     */
    public record RunResult(@NotNull ResultOutcome outcome, @NotNull String summary,
            @Nullable List<String> nextSteps, @Nullable List<String> openQuestions) {
    }

    /** {@code run.finished.result.outcome}. */
    public enum ResultOutcome {
        /** The work is done and ready for review. */
        COMPLETED,
        /** The agent cannot continue without a person. */
        BLOCKED
    }

    /** The end state of one repository of a run. */
    public record GitResult(@NotNull UUID componentId, @NotBlank @Size(max = 255) String branch,
            @NotNull Boolean pushed, @Nullable @Size(max = 64) String headSha, @Nullable @Min(0) Integer commits) {
    }

    /**
     * Token usage ({@code EventPayloadUsage}).
     *
     * @param inputTokens      input neither read from nor written to the prompt cache; when {@code cacheWriteTokens}
     *                         is null, including the cache writes
     * @param cacheWriteTokens v2: input written to the prompt cache; null from a v1 host and a v2 host older than it
     */
    public record Usage(@Nullable Long inputTokens, @Nullable Long outputTokens, @Nullable Long cachedInputTokens,
            @Nullable Long cacheWriteTokens, @Nullable Long contextUsed, @Nullable Long contextSize,
            @Nullable Double costUsd) {
    }

    public record RunCancel(UUID runId, long epoch) implements Outbound {
    }

    // ---- steering (the protocol 2 feature run.steer) ---------------------------------------------------------------

    /**
     * A person's message for the running turn: the host gives {@code text} to the agent, which takes it after its
     * current step, and answers {@link RunSteerAck} for {@code steerId} (the message's id).
     */
    public record RunSteer(UUID runId, long epoch, UUID steerId, String text) implements Outbound {

        @Override
        public String toString() {
            return "RunSteer[runId=" + runId + ", epoch=" + epoch + ", steerId=" + steerId + ", text=("
                    + text.length() + " characters)]";
        }
    }

    /** {@code run.steer.ack.status}. */
    public enum SteerStatus {
        /** The agent has the message in the turn it runs, and reads it before the turn ends. */
        DELIVERED,
        /** It does not: the message waits for the next turn ({@code code} says why). */
        REFUSED
    }

    /**
     * The host's answer to one {@link RunSteer}, before the run's {@code run.finished}.
     *
     * @param code why a steer was refused: {@code NOT_SUPPORTED}, {@code NOT_RUNNING}, {@code FAILED}
     */
    public record RunSteerAck(@NotNull UUID runId, @NotNull Long epoch, @NotNull UUID steerId,
            @NotNull SteerStatus status, @Nullable @Size(max = 100) String code,
            @Nullable @Size(max = 2000) String message) implements OneRunMessage {
    }

    // ---- repository requests (v1 frames, docs/archive/v1/CONVENTIONS.md §14.7, §15.6; refused, CONVENTIONS §15.6) ---

    /**
     * The agent called the platform tool {@code request_repository}. Always answered with exactly one
     * {@link RunRepositoryRequestAck}, so a blank or missing {@code name} or {@code reason} is not a bad frame but an
     * {@code INVALID_REQUEST}.
     */
    public record RunRepositoryRequest(@NotNull UUID runId, @NotNull Long epoch, @NotNull UUID requestId,
            @Nullable UUID componentId, @Nullable String name, @Nullable String reason) implements OneRunMessage {
    }

    /** The answer to one {@link RunRepositoryRequest}; {@code message} is the tool result for the agent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunRepositoryRequestAck(UUID runId, UUID requestId, AckStatus status,
            @Nullable UUID repositoryRequestId, @Nullable String code, String message) implements Outbound {

        public static RunRepositoryRequestAck pending(UUID runId, UUID requestId, UUID repositoryRequestId,
                String message) {
            return new RunRepositoryRequestAck(runId, requestId, AckStatus.PENDING, repositoryRequestId, null, message);
        }

        public static RunRepositoryRequestAck rejected(UUID runId, UUID requestId, String code, String message) {
            return new RunRepositoryRequestAck(runId, requestId, AckStatus.REJECTED, null, code, message);
        }
    }

    public enum AckStatus {
        PENDING, REJECTED
    }

    // ---- tasks created by agents (v1, docs/archive/v1/CONVENTIONS.md §15.6; refused, CONVENTIONS §15.6) -------------

    /**
     * The agent called the platform tool {@code create_task}. Always answered with exactly one
     * {@link RunTaskCreateAck}, so a blank, missing or too long {@code title} is not a bad frame but an
     * {@code INVALID_REQUEST}.
     */
    public record RunTaskCreate(@NotNull UUID runId, @NotNull Long epoch, @NotNull UUID requestId,
            @Nullable String title, @Nullable String description, @Nullable List<String> repositories,
            @Nullable String assignee) implements OneRunMessage {
    }

    /** The answer to one {@link RunTaskCreate}; {@code message} is the tool result for the agent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunTaskCreateAck(UUID runId, UUID requestId, TaskCreateStatus status, @Nullable UUID taskId,
            @Nullable String taskKey, @Nullable String code, String message) implements Outbound {

        public static RunTaskCreateAck created(UUID runId, UUID requestId, UUID taskId, String taskKey,
                String message) {
            return new RunTaskCreateAck(runId, requestId, TaskCreateStatus.CREATED, taskId, taskKey, null, message);
        }

        public static RunTaskCreateAck rejected(UUID runId, UUID requestId, String code, String message) {
            return new RunTaskCreateAck(runId, requestId, TaskCreateStatus.REJECTED, null, null, code, message);
        }
    }

    public enum TaskCreateStatus {
        CREATED, REJECTED
    }

    // ---- asks (v1 frames, docs/archive/v1/CONVENTIONS.md §15.6; refused now, CONVENTIONS §15.6) ---------------------

    /**
     * The agent called the platform tool {@code ask_human}. Always answered with exactly one {@link RunAskAck}, so a
     * blank, missing or too long {@code question}, or a bad option, is not a bad frame but an
     * {@code INVALID_REQUEST}.
     *
     * @param options the answers a person can pick; null or empty for a free answer
     */
    public record RunAsk(@NotNull UUID runId, @NotNull Long epoch, @NotNull UUID requestId,
            @Nullable String question, @Nullable List<String> options) implements OneRunMessage {
    }

    /** The answer to one {@link RunAsk}; {@code message} is the tool result for the agent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunAskAck(UUID runId, UUID requestId, AckStatus status, @Nullable UUID askId,
            @Nullable String code, String message) implements Outbound {

        public static RunAskAck pending(UUID runId, UUID requestId, UUID askId, String message) {
            return new RunAskAck(runId, requestId, AckStatus.PENDING, askId, null, message);
        }

        public static RunAskAck rejected(UUID runId, UUID requestId, String code, String message) {
            return new RunAskAck(runId, requestId, AckStatus.REJECTED, null, code, message);
        }
    }

    // ---- platform requests under protocol v2 (PLATFORM.md §10, §11.1; CONVENTIONS §14.10) ------------------------

    /** The kinds of {@link RunRequest}. */
    public enum RequestKind {
        /** {@code ask}: a QUESTION decision. */
        ASK,
        /** {@code request_access}: an ACCESS decision of a repository or a connection's tools. */
        ACCESS,
        /** {@code save_artifact} with a link: a LINK artifact. */
        ARTIFACT,
        /** A server-executed tool of {@code run.assign.tools}. */
        TOOL,
        /** {@code request_network_access}: an ACCESS decision of a network host. */
        NETWORK
    }

    /**
     * v2: a platform request of the agent, one per tool call. Always answered with exactly one
     * {@link RunRequestAck} with the same {@code requestId}, so an unknown {@code kind} or a payload that does not
     * fit it is not a bad frame but an {@code INVALID_REQUEST}. A repeated {@code requestId} never creates a second
     * decision, artifact or tool call.
     *
     * @param kind    one of {@link RequestKind}, kept as text so that an unknown kind can still be answered
     * @param payload by kind: {@code {question, options?}}, {@code {repository? | tool?, reason}},
     *                {@code {url, title}}, {@code {tool, arguments}} or {@code {host, reason}}
     */
    public record RunRequest(@NotNull UUID runId, @NotNull Long epoch, @NotNull UUID requestId, @Nullable String kind,
            @Nullable JsonNode payload) implements OneRunMessage {
    }

    /** {@code run.request.ack.status}. */
    public enum RequestStatus {
        /** A decision was stored and waits for a person. */
        PENDING,
        /** The request was carried out. */
        DONE,
        /** Nothing was stored or done; {@code code} says why. */
        REJECTED
    }

    /**
     * v2: the answer to one {@link RunRequest}. {@code message} is for the agent; it is required when REJECTED and
     * optional otherwise (the host has texts of its own).
     *
     * @param payload {@link DecisionPayload} for PENDING, {@link ArtifactPayload} or {@link ToolResult} for DONE,
     *                null for REJECTED
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunRequestAck(UUID runId, UUID requestId, RequestStatus status, @Nullable AckPayload payload,
            @Nullable String code, @Nullable String message) implements Outbound {

        public RunRequestAck {
            if ((status == RequestStatus.REJECTED) != (code != null) || (status == RequestStatus.REJECTED
                    ? payload != null || message == null : payload == null)) {
                throw new IllegalArgumentException("PENDING and DONE carry a payload, REJECTED a code and message");
            }
        }

        public static RunRequestAck pending(UUID runId, UUID requestId, UUID decisionId, @Nullable String message) {
            return new RunRequestAck(runId, requestId, RequestStatus.PENDING, new DecisionPayload(decisionId), null,
                    message);
        }

        public static RunRequestAck done(UUID runId, UUID requestId, AckPayload payload, @Nullable String message) {
            return new RunRequestAck(runId, requestId, RequestStatus.DONE, payload, null, message);
        }

        public static RunRequestAck rejected(UUID runId, UUID requestId, String code, String message) {
            return new RunRequestAck(runId, requestId, RequestStatus.REJECTED, null, code, message);
        }
    }

    /** The payload of a {@link RunRequestAck}. */
    public sealed interface AckPayload permits DecisionPayload, ArtifactPayload, ToolResult {
    }

    /** PENDING: the decision the request stored. */
    public record DecisionPayload(UUID decisionId) implements AckPayload {
    }

    /** DONE of an ARTIFACT request: the LINK artifact it stored. */
    public record ArtifactPayload(UUID artifactId) implements AckPayload {
    }

    /**
     * DONE of a TOOL request: an MCP {@code tools/call} result, which the host returns to the agent as it is.
     *
     * @param structuredContent the result as JSON, or null
     * @param isError           the system answered with an error the agent should see; the content says what
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolResult(List<ToolContent> content, @Nullable JsonNode structuredContent,
            @JsonProperty("isError") boolean isError) implements AckPayload {

        public ToolResult {
            content = List.copyOf(content);
        }

        public static ToolResult text(String text, @Nullable JsonNode structuredContent, boolean isError) {
            return new ToolResult(List.of(new ToolContent("text", text)), structuredContent, isError);
        }
    }

    /** An MCP content block; the server sends text blocks. */
    public record ToolContent(String type, String text) {
    }

    // ---- capacity settings (docs/archive/v1/CONVENTIONS.md §14.8, §15.7) ----------------------------------------------

    /**
     * The desired capacity of the host and its credentials, always the full state. Sent right after {@code welcome}
     * and after every change; the host answers with {@link HostConfigApplied}.
     *
     * @param version                  the host's {@code config_version}; the host ignores an older one
     * @param desiredMaxConcurrentRuns null: none, the host uses its ceiling
     * @param credentials              the credentials with a desired pool size, each label once
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HostConfig(long version, @Nullable Integer desiredMaxConcurrentRuns,
            List<HostConfigCredential> credentials, @Nullable HostConfigPool pool) implements Outbound {

        public HostConfig {
            credentials = List.copyOf(credentials);
        }

        /** A {@code host.config} without a pool. */
        public HostConfig(long version, @Nullable Integer desiredMaxConcurrentRuns,
                List<HostConfigCredential> credentials) {
            this(version, desiredMaxConcurrentRuns, credentials, null);
        }
    }

    /**
     * {@code host.config.pool} (ADR-0099): Tinfirm's count of ready sandboxes now, from the pool's schedule, and its
     * next change in the next 7 days. Sent only to a host with {@link #FEATURE_SANDBOX_POOL}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HostConfigPool(int readyTarget, @Nullable PoolChange next) {
    }

    /** The next change of a pool's count: the count and when, RFC 3339 in UTC. */
    public record PoolChange(int readyTarget, String at) {
    }

    public record HostConfigCredential(String label, int desiredPoolSize) {
    }

    /**
     * v2, server to host: the Public Suffix List the server holds (ADR-0044), which the host uses to refuse a network
     * wildcard over a public suffix. Sent after {@code hello} when the host's version differs, and to every connected
     * v2 host when the list changes. The host sends no answer.
     *
     * @param sha256    the SHA-256 (lower-case hex) of the UNCOMPRESSED list bytes
     * @param fetchedAt RFC 3339 instant the server got this list
     * @param ruleCount the number of rules, for the host's log
     * @param content   base64 (standard, padded) of the gzip of the list's bytes (UTF-8 text)
     */
    public record PslUpdate(String sha256, String fetchedAt, int ruleCount, String content) implements Outbound {

        @Override
        public String toString() {
            return "PslUpdate[sha256=" + sha256 + ", ruleCount=" + ruleCount + "]";
        }
    }

    /**
     * The capacity the host uses now: the answer to every {@link HostConfig} it applies, and unprompted when it starts
     * to drain. {@code maxConcurrentRuns} is 0 exactly while it drains (checked by the codec, as the schema requires);
     * an effective value above its ceiling or a label listed twice is a bad message (archived v1 CONVENTIONS §14.8).
     */
    public record HostConfigApplied(@NotNull @Min(0) Long version, @NotNull Boolean draining,
            @NotNull @Min(0) @Max(MAX_HOST_CAPACITY) Integer maxConcurrentRuns,
            @NotNull @Min(1) @Max(MAX_HOST_CAPACITY) Integer maxConcurrentRunsCeiling,
            @NotNull @Size(max = 100) List<@Valid @NotNull AppliedCredential> credentials,
            @Nullable @Valid AppliedPool pool) implements Inbound {

        /** A {@code host.config.applied} without a pool. */
        public HostConfigApplied(Long version, Boolean draining, Integer maxConcurrentRuns,
                Integer maxConcurrentRunsCeiling, List<AppliedCredential> credentials) {
            this(version, draining, maxConcurrentRuns, maxConcurrentRunsCeiling, credentials, null);
        }
    }

    /** {@code host.config.applied.pool} (ADR-0099): the count the host aims at for Tinfirm, and its ceiling. */
    public record AppliedPool(@NotNull @Min(0) @Max(MAX_HOST_CAPACITY) Integer readyTarget,
            @NotNull @Min(0) @Max(MAX_HOST_CAPACITY) Integer maxReadyCeiling) {
    }

    /**
     * v2, the feature {@link #FEATURE_SANDBOX_POOL} (ADR-0099), host to server: the host's sandboxes now, on change
     * and at most every 10 seconds. The server stores the latest; it answers nothing.
     */
    public record HostPoolStatus(@NotNull @Min(0) @Max(1024) Integer ready, @NotNull @Min(0) @Max(1024) Integer starting,
            @NotNull @Min(0) @Max(1024) Integer busy, @Nullable @Min(0) Long coldStartP90Seconds,
            @NotNull @Min(0) Integer servedReady, @NotNull @Min(0) Integer servedCold) implements Inbound {
    }

    /** The effective pool of one credential of {@code hello}, with its ceiling. */
    public record AppliedCredential(@NotNull @Pattern(regexp = LABEL) String label,
            @NotNull @Min(1) @Max(MAX_CAPACITY) Integer poolSize,
            @NotNull @Min(1) @Max(MAX_CAPACITY) Integer poolSizeCeiling) {
    }

    // ---- credentials and errors ----------------------------------------------------------------------------------

    /** A credential's state changed on the host (usage limit hit, login expired, recovered). */
    public record CredentialStatus(@NotNull @Size(max = 63) String label, @NotNull CredentialState status,
            @Nullable String cooldownUntil, @Nullable @Size(max = 500) String detail) implements Inbound {
    }

    /** A frame could not be processed. Never contains secrets. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProtocolError(@NotNull String code, @NotNull String message, @Nullable UUID runId)
            implements Inbound, Outbound {

        public static ProtocolError of(String code, String message) {
            return new ProtocolError(code, message, null);
        }

        public static ProtocolError of(String code, String message, UUID runId) {
            return new ProtocolError(code, message, runId);
        }
    }

    public enum EventType {
        AGENT_MESSAGE, AGENT_THOUGHT, TOOL_CALL, TOOL_CALL_UPDATE, PLAN, USAGE, RATE_LIMIT, STATUS, GIT, LOG, ERROR
    }

    public enum Outcome {
        SUCCEEDED, FAILED, CANCELLED
    }

    /**
     * Why a run failed ({@code $defs/FailureClass}). {@code TIME_LIMIT} (v2 only) is the wall-clock guard at
     * {@code limits.maxWallClockSeconds}, whose session and work a retry goes on with; {@code GUARD_STOPPED} the host's
     * other guards, and the wall-clock guard of a v1 host.
     */
    public enum FailureClass {
        USAGE_LIMIT, SERVER_LIMITED, CONTEXT_OVERFLOW, LOGIN_EXPIRED, VENDOR_BLOCKED, CREDENTIAL_LEAK, PROTOCOL_ERROR,
        GUARD_STOPPED, TIME_LIMIT, GIT_ERROR, INFRA, RUNNER_UNAVAILABLE, MODEL_REFUSED
    }

    /** Why a run did not resume a session ({@code run.finished.resumeOutcome}, v2; CONVENTIONS §14.10). */
    public enum ResumeOutcome {
        /** {@code run.assign} had no {@code resumeSessionId}. */
        FIRST_RUN,
        /** The session files were not in the host's session home. */
        NO_SESSION,
        /** {@code session/load} failed otherwise; the message is in a LOG event. */
        LOAD_FAILED,
        /** The agent has no {@code session/load}. */
        NOT_SUPPORTED
    }
}
