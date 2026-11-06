package com.flowtivo.tinfirm.shared.litellm;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * A model request's body as LiteLLM gets it (the package 2 contract, §6.1; ADR-0069): the JSON object of the format's
 * API, written anew, without LiteLLM's own fields at its top level, which would let whoever holds a key choose where
 * LiteLLM sends the request and with which credentials (an {@code api_base} of their own receives the company's
 * provider key), what it logs and where, what the request costs, or a canned answer. Shared by the server's model
 * relay and the gateway's model port ({@code model-api}), which pass the same requests on. Removed:
 * <ul>
 * <li>the contract's list: {@code api_base}, {@code base_url}, {@code api_key}, {@code api_version},
 * {@code custom_llm_provider}, {@code organization}, {@code metadata}, {@code tags}, {@code user},
 * {@code mock_response}, {@code litellm_*}, {@code user_config}, {@code extra_headers}, {@code extra_body};</li>
 * <li>{@code guardrail_config}, {@code policies} and {@code include_guardrail_response}, which LiteLLM's proxy takes
 * from the body for the request's guardrails;</li>
 * <li>the other parameters LiteLLM v1.106.6 takes as its own from a request ({@code all_litellm_params} in
 * {@code litellm/types/utils.py}: routing, retries, fallbacks, caching, credentials of Azure, AWS and Vertex, the
 * workload identity federation of Anthropic and OpenAI, the callbacks' keys and hosts, the custom prices), by name
 * and by their prefixes ({@code _}, {@code aws_}, {@code azure_}, {@code vertex_}, {@code langfuse_}, ...) and the
 * prices' shapes ({@code *_cost_per_*}, {@code *_cost}); the test resource {@code litellm/all-litellm-params.txt}
 * lists them all;</li>
 * <li>LiteLLM's other controls of a request: {@code disable_fallbacks}, {@code deployment_id}, {@code api_type},
 * {@code timeout}, {@code secret_key}, {@code access_token}.</li>
 * </ul>
 * {@code drop_params} and {@code additional_drop_params} only let LiteLLM drop a parameter the model's provider does
 * not take instead of refusing the request: the model port passes them as they came; the relay sets
 * {@code "drop_params": true} itself ({@link #clean} with {@code dropParams}). Everything else passes as it came,
 * numbers exactly. The body must be one JSON object without a repeated top-level name (LiteLLM would take the last
 * {@code model}, a caller checks one); its {@code model} is read for the caller's check.
 * <p>
 * The provider is a channel for what the model emits, never a relay the agent directs (threat model 7.2, R13; the
 * security review of 2026-10-27, M1 and D8): a request may not ask the model's provider to run a tool itself, which
 * would carry what the agent writes to any host or run code outside the sandbox. So {@code mcp_servers} and
 * {@code container} (Anthropic's MCP connector and code execution container) are removed, like LiteLLM's fields, and
 * a tool whose {@code type} is not one the runtime runs ({@link #CLIENT_TOOL_TYPES}) refuses the request, wherever
 * the body defines one (an array named {@code tools} at any depth, an Anthropic {@code tool_addition} block of a
 * message); so does Chat Completions' {@code web_search_options}. {@link ProviderTools#WEB_SEARCH}, a company's
 * choice, lets the providers' web search through ({@link #WEB_SEARCH_TOOL_TYPES}) and nothing else.
 */
public final class LiteLlmRequests {

    /** Exact names removed, lower case. */
    private static final Set<String> REMOVED = Set.of(
            // The contract's list.
            "api_base", "base_url", "api_key", "api_version", "custom_llm_provider", "organization", "metadata",
            "tags", "user", "mock_response", "user_config", "extra_headers", "extra_body",
            // all_litellm_params of v1.106.6 that no prefix below covers.
            "max_agentic_loops", "keepalive_seconds", "allow_client_keepalive_override", "guardrails", "acompletion",
            // A timeout per service tier, for the request and for its stream.
            "timeout_by_service_tier", "stream_timeout_by_service_tier",
            // Not in all_litellm_params, but v1.106.6's proxy takes them from the body for the request's guardrails
            // (litellm_pre_call_utils.py): their configuration, the policies that choose them, and whether the answer
            // carries their findings.
            "guardrail_config", "policies", "include_guardrail_response",
            "aimg_generation", "atext_completion", "text_completion", "caching", "mock_timeout",
            "disable_add_transform_inline_image_block", "prompt_id", "prompt_variables", "provider_specific_header",
            "prompt_version", "prompt_environment", "force_timeout", "logger_fn", "verbose", "model_file_id_mapping",
            "completion_call_id", "model_alias_map", "custom_prompt_dict", "stream_response", "cost_per_query",
            "ssl_verify", "data_residency", "async_call", "aembedding", "allm_passthrough_route", "use_client", "id",
            "fallbacks", "routing_strategy", "azure", "headers", "model_list", "num_retries",
            "context_window_fallback_dict", "retry_policy", "retry_strategy", "roles", "final_prompt_value",
            "bos_token", "eos_token", "request_timeout", "client_side_timeout", "complete_response", "self", "client",
            "rpm", "tpm", "default_api_key_rpm_limit", "default_api_key_tpm_limit", "itpm", "otpm",
            "max_parallel_requests", "hf_model_name", "model_info", "proxy_server_request", "secret_fields",
            "preset_cache_key", "caching_groups", "ttl", "cache", "enable_prompt_caching", "no-log", "no_log",
            "base_model", "stream_timeout", "supports_system_message", "region_name", "allowed_model_region",
            "model_config", "fastest_response", "cooldown_time", "cache_key", "max_retries", "tenant_id",
            "client_id", "client_secret", "user_continue_message", "configurable_clientside_auth_params", "weight",
            "ensure_alternating_roles", "assistant_continue_message", "fallback_depth", "max_fallbacks",
            "attempted_targets", "max_budget", "budget_duration", "use_in_pass_through",
            "merge_reasoning_content_in_choices", "allowed_openai_params", "use_litellm_proxy",
            "use_chat_completions_api", "rust", "prompt_label", "shared_session", "search_tool_name", "order",
            "enable_tag_filtering", "enable_json_schema_validation", "use_xai_oauth", "bedrock_tags",
            "turn_off_message_logging", "tiered_pricing", "output_vector_size", "regional_endpoint_uplift_multiplier",
            "regional_processing_uplift_multiplier_eu", "regional_processing_uplift_multiplier_us",
            "cost_per_second", "log_client_error_tracebacks", "max_file_size_mb", "provider_affinity_header",
            "silent_model", "stream_chunk_size", "tag_regex",
            // The workload identity federation of Anthropic and OpenAI (v1.106.6), and Fireworks' user forwarding.
            // LiteLLM's proxy refuses them in a body too (auth_utils.py; Fireworks' one unless an admin opts in), but
            // not every name of these providers is LiteLLM's own: Bedrock and Vertex take an anthropic_version, so no
            // prefix for them.
            "anthropic_federation_rule_id", "anthropic_organization_id", "anthropic_service_account_id",
            "anthropic_federation_workspace_id", "anthropic_identity_token_file", "anthropic_identity_token",
            "anthropic_identity_source", "anthropic_issuer_url", "anthropic_issuer_subject",
            "anthropic_issuer_audience", "anthropic_issuer_ttl_seconds", "anthropic_issuer_signing_key_ref",
            "anthropic_keycloak_token_url", "anthropic_keycloak_client_id", "anthropic_keycloak_auth_method",
            "anthropic_keycloak_client_secret_ref", "anthropic_keycloak_scope",
            "anthropic_disable_workload_identity_federation", "openai_identity_provider_id",
            "openai_service_account_id", "openai_identity_token_file", "fireworks_forward_user_id",
            // LiteLLM's other request controls.
            "disable_fallbacks", "deployment_id", "api_type", "timeout", "secret_key", "access_token");

    /**
     * Fields of the model's API, not LiteLLM's, that make the provider act on its own: removed (M1). Lower case.
     */
    private static final Set<String> PROVIDER_ACTIONS = Set.of(
            // Anthropic's MCP connector: the provider calls the MCP servers the request names, at any URL.
            "mcp_servers",
            // Anthropic's code execution container (and the skills in it): the provider runs code for the request.
            "container");

    /**
     * The {@code tools[].type} values that pass whatever the company's setting: tools the runtime itself runs, the
     * model only asking for a call. A versioned type ({@code bash_20250124}) counts by its name before the date. Every
     * other type refuses the request: a tool the provider runs (web search and fetch, code execution, MCP, file search,
     * image generation, an advisor model) or one no pinned runtime drives (computer use). What the pinned runtimes send
     * (runner/images): Claude Code and pi Anthropic custom tools; OpenCode function or custom tools through its SDK;
     * Codex function, custom, {@code local_shell}, {@code namespace} and {@code tool_search}.
     */
    static final Set<String> CLIENT_TOOL_TYPES = Set.of(
            // OpenAI's tools the client defines and runs (Chat Completions, Responses), and LiteLLM's own default.
            "function",
            // Anthropic's custom tools (a tool without a type is one), and OpenAI's free-form ones (Codex's
            // apply_patch): the client defines and runs them.
            "custom",
            // OpenAI Responses: a named group of the request's own tools (Codex); its tools are checked as well.
            "namespace",
            // OpenAI Responses: a shell the client runs on its own machine (Codex): the model emits a local_shell_call.
            "local_shell",
            // OpenAI Responses: a search over the request's own deferred tools (Codex). Run by the provider or the
            // client, it reaches no host and runs no code: it only picks among the tools the request sent.
            "tool_search",
            // Anthropic's tool search over the request's own deferred tools, regex and BM25 (Claude Code turns it off
            // behind a gateway): as OpenAI's tool_search, it only picks among the request's tools.
            "tool_search_tool_regex", "tool_search_tool_bm25",
            // Anthropic's client tools of a fixed schema: the model emits a tool_use, the client runs the command,
            // the edit or the memory operation in its own sandbox. No pinned runtime sends them today.
            "bash", "text_editor", "memory");

    /**
     * The providers' web search, which only {@link ProviderTools#WEB_SEARCH} lets through: Anthropic's versioned
     * {@code web_search_*} and OpenAI's {@code web_search} and {@code web_search_preview} (with or without a date).
     * Never web fetch: a search sends the agent's query to the provider's search; a fetch sends the provider to any
     * URL.
     */
    static final Set<String> WEB_SEARCH_TOOL_TYPES = Set.of("web_search", "web_search_preview");

    /** Chat Completions' parameter of a search model's own web search: as a web search tool. */
    private static final String WEB_SEARCH_OPTIONS = "web_search_options";

    /** A versioned tool type: a name, then an underscore and the date (or a date with underscores, OpenAI's). */
    private static final Pattern VERSIONED_TYPE = Pattern.compile("([a-z_]+?)_(\\d{8}|\\d{4}_\\d{2}_\\d{2})");

    /** Fields whose value is a JSON schema or an output format: data the tool check does not read. */
    private static final Set<String> SCHEMAS = Set.of("input_schema", "parameters", "schema", "json_schema",
            "output_schema", "format", "response_format", "input_examples");

    /** The names that only let LiteLLM drop what the model's provider does not take, lower case. */
    private static final Set<String> DROP_CONTROLS = Set.of("drop_params", "additional_drop_params");

    /** Prefixes of removed names, lower case. */
    private static final List<String> REMOVED_PREFIXES = List.of("_", "litellm_", "aws_", "azure_", "vertex_",
            "watsonx_", "s3_", "gcs_", "langfuse_", "langsmith_", "humanloop_", "arize_", "posthog_", "wandb_",
            "weave_", "dd_", "newrelic_", "signoz_", "mock_", "input_cost_", "output_cost_", "auto_router_",
            "complexity_router_", "adaptive_router_", "quality_router_");

    private LiteLlmRequests() {
    }

    /** The body to pass on and the model it names (null when it names none). */
    public record Cleaned(byte[] bytes, @Nullable String model) {
    }

    /**
     * Which tools the model's provider may run itself for a request: {@code OFF}, none; {@code WEB_SEARCH}, the
     * providers' web search ({@link #WEB_SEARCH_TOOL_TYPES}). A company's {@code modelSettings.providerTools} at the
     * relay, {@code MODEL_API_PROVIDER_TOOLS} at the model port.
     */
    public enum ProviderTools {
        OFF, WEB_SEARCH
    }

    /** Why a body cannot be passed on; the message is for the caller. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    /** A JSON reader for bodies of up to {@code maxBytes}, whose strings may be as long as the body. */
    public static JsonMapper mapper(long maxBytes) {
        int max = (int) Math.min(Integer.MAX_VALUE, maxBytes);
        JsonFactory factory = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(max).maxDocumentLength(maxBytes)
                .build())
            .build();
        return JsonMapper.builder(factory).build();
    }

    /** Whether a top-level name is one of LiteLLM's own fields, which never pass. */
    public static boolean liteLlmField(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (REMOVED.contains(lower)) {
            return true;
        }
        for (String prefix : REMOVED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return lower.contains("_cost_per_") || lower.endsWith("_cost") || lower.contains("_token_cost");
    }

    /** Whether a top-level name makes the provider act on its own ({@code mcp_servers}, {@code container}). */
    public static boolean providerAction(String name) {
        return PROVIDER_ACTIONS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Whether a tool of this {@code type} passes under {@code allowed}; a tool without a type is a custom one. */
    public static boolean toolAllowed(@Nullable String type, ProviderTools allowed) {
        if (type == null) {
            return true;
        }
        String name = type;
        Matcher versioned = VERSIONED_TYPE.matcher(type);
        if (versioned.matches()) {
            name = versioned.group(1);
        }
        if (CLIENT_TOOL_TYPES.contains(name)) {
            return true;
        }
        return allowed == ProviderTools.WEB_SEARCH && WEB_SEARCH_TOOL_TYPES.contains(name);
    }

    /**
     * The values of a beta header ({@code anthropic-beta}, {@code OpenAI-Beta}) that are in {@code allowed}, as one
     * comma-separated value in their order, each once; null when none is. A value not in the list is dropped, never
     * refused: a beta the relay does not know turns a feature off, and a body that needs it is the provider's 400.
     */
    public static @Nullable String allowedBetas(List<String> values, Collection<String> allowed) {
        Set<String> kept = new LinkedHashSet<>();
        for (String value : values) {
            for (String beta : value.split(",")) {
                String trimmed = beta.strip();
                if (!trimmed.isEmpty() && allowed.contains(trimmed)) {
                    kept.add(trimmed);
                }
            }
        }
        return kept.isEmpty() ? null : String.join(",", kept);
    }

    /** Whether a top-level name only lets LiteLLM drop what the provider does not take. */
    public static boolean dropControl(String name) {
        return DROP_CONTROLS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** The body without LiteLLM's fields, no tool of the provider's allowed ({@link ProviderTools#OFF}). */
    public static Cleaned clean(JsonMapper mapper, byte[] body, boolean dropParams) throws Refused {
        return clean(mapper, body, dropParams, ProviderTools.OFF);
    }

    /**
     * The body without LiteLLM's fields and without {@code mcp_servers} and {@code container}.
     *
     * @param dropParams    whether LiteLLM is to drop what the model's provider does not take, whatever the request
     *                      asked: the caller's own {@code drop_params} and {@code additional_drop_params} go and
     *                      {@code "drop_params": true} is added last; else they pass as they came
     * @param providerTools the tools the provider may run itself for the request
     * @throws Refused when the body is not one JSON object, names a top-level field twice, or names a tool the provider
     *                 would run that {@code providerTools} does not allow (the message names its type)
     */
    public static Cleaned clean(JsonMapper mapper, byte[] body, boolean dropParams, ProviderTools providerTools)
            throws Refused {
        Cleaned cleaned = copy(mapper, body, dropParams);
        checkTools(mapper, body, providerTools);
        return cleaned;
    }

    private static Cleaned copy(JsonMapper mapper, byte[] body, boolean dropParams) throws Refused {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
        String model = null;
        Set<String> seen = new HashSet<>();
        try (JsonParser parser = mapper.createParser(body); JsonGenerator generator = mapper.createGenerator(out)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new Refused("The request body must be a JSON object");
            }
            generator.writeStartObject();
            for (JsonToken token = parser.nextToken(); token != JsonToken.END_OBJECT; token = parser.nextToken()) {
                if (token != JsonToken.PROPERTY_NAME) {
                    throw new Refused("The request body is not valid JSON");
                }
                String name = parser.currentName();
                if (!seen.add(name)) {
                    throw new Refused("The request body names `" + shortName(name) + "` twice");
                }
                JsonToken value = parser.nextToken();
                if (name.equals("model")) {
                    if (value != JsonToken.VALUE_STRING) {
                        throw new Refused("The request's model must be a string");
                    }
                    model = parser.getString();
                }
                if (liteLlmField(name) || providerAction(name) || dropParams && dropControl(name)) {
                    parser.skipChildren();
                    continue;
                }
                generator.writeName(name);
                generator.copyCurrentStructureExact(parser);
            }
            if (dropParams) {
                generator.writeName("drop_params");
                generator.writeBoolean(true);
            }
            generator.writeEndObject();
            if (parser.nextToken() != null) {
                throw new Refused("The request body has content after its JSON object");
            }
        }
        catch (JacksonException ex) {
            throw new Refused("The request body is not valid JSON");
        }
        return new Cleaned(out.toByteArray(), model);
    }

    /**
     * Refuses a tool the provider would run that {@code allowed} does not let through, wherever the body defines or
     * names one: each element of an array named {@code tools} at any depth ({@code tools} itself, a
     * {@code namespace}'s, a {@code tool_search_output}'s, {@code tool_choice}'s allowed tools), and the
     * {@code definition} of a content block's {@code tool} (Anthropic's {@code tool_addition}, the {@code inline-tools}
     * beta); and Chat Completions' {@code web_search_options}. JSON schemas ({@code input_schema},
     * {@code parameters}, ...) are data and are not read. The body is already known to be one JSON object; it is read
     * again as a stream, nothing kept.
     */
    private static void checkTools(JsonMapper mapper, byte[] body, ProviderTools allowed) throws Refused {
        try (JsonParser parser = mapper.createParser(body)) {
            parser.nextToken();
            for (JsonToken token = parser.nextToken(); token == JsonToken.PROPERTY_NAME; token = parser.nextToken()) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (name.equals(WEB_SEARCH_OPTIONS) && value != JsonToken.VALUE_NULL
                        && allowed != ProviderTools.WEB_SEARCH) {
                    throw new Refused("The request asks the model's provider to search the web itself (`"
                            + WEB_SEARCH_OPTIONS + "`), which this company's model settings do not allow");
                }
                field(parser, name, value, null, allowed);
            }
        }
        catch (JacksonException ex) {
            throw new Refused("The request body is not valid JSON");
        }
    }

    /**
     * A field's value, the parser on its first token: a {@code tools} array's elements are tools, a {@code tool}'s
     * {@code definition} is one, a schema is skipped, anything else is searched. Leaves the parser on its last token.
     */
    private static void field(JsonParser parser, String name, JsonToken value, @Nullable String parent,
            ProviderTools allowed) throws Refused {
        if (SCHEMAS.contains(name)) {
            parser.skipChildren();
        }
        else if (name.equals("tools") && value == JsonToken.START_ARRAY) {
            for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                if (token == JsonToken.START_OBJECT) {
                    tool(parser, allowed);
                }
                else {
                    parser.skipChildren();
                }
            }
        }
        else if (name.equals("definition") && "tool".equals(parent) && value == JsonToken.START_OBJECT) {
            tool(parser, allowed);
        }
        else if (value == JsonToken.START_OBJECT) {
            object(parser, name, allowed);
        }
        else if (value == JsonToken.START_ARRAY) {
            for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                if (token == JsonToken.START_OBJECT) {
                    object(parser, null, allowed);
                }
                else if (token == JsonToken.START_ARRAY) {
                    field(parser, "", token, null, allowed);
                }
            }
        }
    }

    /** An object's fields, searched, the parser on its start; leaves the parser on its end. */
    private static void object(JsonParser parser, @Nullable String name, ProviderTools allowed) throws Refused {
        for (JsonToken token = parser.nextToken(); token == JsonToken.PROPERTY_NAME; token = parser.nextToken()) {
            String child = parser.currentName();
            field(parser, child, parser.nextToken(), name, allowed);
        }
    }

    /** One tool's definition, the parser on its start, its other fields searched; leaves the parser on its end. */
    private static void tool(JsonParser parser, ProviderTools allowed) throws Refused {
        @Nullable String type = null;
        for (JsonToken token = parser.nextToken(); token == JsonToken.PROPERTY_NAME; token = parser.nextToken()) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            if (name.equals("type")) {
                if (value != JsonToken.VALUE_STRING) {
                    throw new Refused("A tool's type must be a string");
                }
                type = parser.getString();
            }
            else {
                field(parser, name, value, null, allowed);
            }
        }
        if (type != null && !toolAllowed(type, allowed)) {
            throw new Refused("The request asks the model's provider to run the tool type `" + shortName(type)
                    + "` itself, which this company's model settings do not allow: only tools the runtime runs pass"
                    + (allowed == ProviderTools.WEB_SEARCH ? ", and the provider's web search" : ""));
        }
    }

    private static String shortName(String name) {
        return name.length() <= 64 ? name : name.substring(0, 64) + "…";
    }
}
