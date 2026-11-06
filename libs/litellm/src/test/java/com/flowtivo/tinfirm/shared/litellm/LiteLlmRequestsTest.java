package com.flowtivo.tinfirm.shared.litellm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * LiteLLM's own fields never pass, by name, prefix and shape; the model's API passes as it came; {@code drop_params}
 * passes or is forced, as the caller asks; a body that is not one JSON object is refused. The provider is never a relay
 * the agent directs and never runs a tool itself (M1 and D8 of the security review of 2026-10-27; ADR-0108): the MCP
 * connector, the code execution container, every web search and every tool the provider would run refuse the request,
 * wherever the body asks for them, and only the beta values on the caller's list pass.
 */
class LiteLlmRequestsTest {

    private static final JsonMapper JSON = LiteLlmRequests.mapper(64 * 1024);

    /** Every field the pinned LiteLLM takes as its own from a request, as the test resource lists them. */
    @Test
    void everyFieldLiteLlmTakesAsItsOwnIsRemoved() throws IOException {
        List<String> names;
        try (InputStream in = LiteLlmRequestsTest.class.getResourceAsStream("/litellm/all-litellm-params.txt")) {
            assertThat(in).isNotNull();
            names = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
        }
        assertThat(names).hasSizeGreaterThan(300).contains("api_base", "signoz_ingestion_endpoint", "cost_per_second");
        assertThat(names).allSatisfy(name -> assertThat(LiteLlmRequests.liteLlmField(name)).as(name).isTrue());
        assertThat(List.of("API_BASE", "Guardrail_Config", "policies", "include_guardrail_response", "deployment_id",
                "access_token", "litellm_anything", "langfuse_host", "gpt_cost_per_token", "input_cost_x"))
            .allSatisfy(name -> assertThat(LiteLlmRequests.liteLlmField(name)).as(name).isTrue());
        // What the model's API takes passes: chat, messages, responses and embeddings alike.
        assertThat(List.of("model", "messages", "max_tokens", "temperature", "stream", "tools", "reasoning_effort",
                "input", "encoding_format", "dimensions", "system", "instructions"))
            .noneMatch(LiteLlmRequests::liteLlmField);
    }

    @Test
    void theModelPortPassesTheCallersDropParamsAndTheRelayForcesThem() throws LiteLlmRequests.Refused {
        byte[] body = ("{\"model\":\"text-embedding-3-small\",\"input\":[\"a\"],\"api_base\":\"https://evil.example\","
                + "\"drop_params\":false,\"metadata\":{\"x\":1}}").getBytes(StandardCharsets.UTF_8);

        LiteLlmRequests.Cleaned port = LiteLlmRequests.clean(JSON, body, false);
        assertThat(new String(port.bytes(), StandardCharsets.UTF_8))
            .isEqualTo("{\"model\":\"text-embedding-3-small\",\"input\":[\"a\"],\"drop_params\":false}");
        assertThat(port.model()).isEqualTo("text-embedding-3-small");

        LiteLlmRequests.Cleaned relay = LiteLlmRequests.clean(JSON, body, true);
        assertThat(new String(relay.bytes(), StandardCharsets.UTF_8))
            .isEqualTo("{\"model\":\"text-embedding-3-small\",\"input\":[\"a\"],\"drop_params\":true}");
    }

    @Test
    void numbersAndNestedValuesPassExactly() throws LiteLlmRequests.Refused {
        String body = "{\"model\":\"m\",\"temperature\":0.10000000000000000555,\"messages\":[{\"role\":\"user\","
                + "\"content\":\"hi\",\"metadata\":{\"kept\":true}}]}";
        assertThat(new String(LiteLlmRequests.clean(JSON, body.getBytes(StandardCharsets.UTF_8), false).bytes(),
                StandardCharsets.UTF_8)).isEqualTo(body);
    }

    @Test
    void aBodyThatIsNotOneObjectIsRefused() {
        for (String body : new String[] { "[1]", "{\"model\":\"a\",\"model\":\"b\"}", "{\"model\":1}", "{} {}",
                "{\"a\":" }) {
            assertThatThrownBy(() -> LiteLlmRequests.clean(JSON, body.getBytes(StandardCharsets.UTF_8), false))
                .as(body)
                .isInstanceOf(LiteLlmRequests.Refused.class);
        }
    }
    /**
     * The MCP shapes of every provider refuse the request, never silently removed: Anthropic's {@code mcp_servers} with
     * or without an {@code mcp_toolset}, OpenAI Responses' {@code mcp} tool to a server or to a connector, and
     * Anthropic's code execution {@code container}. No pinned runtime sends any of them (it runs MCP itself).
     */
    @Test
    void everyMcpShapeAndTheContainerAreRefused() {
        String[][] cases = {
            { "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,\"mcp_servers\":[{\"type\":\"url\","
                    + "\"url\":\"https://evil.example/mcp\",\"name\":\"x\"}],"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "`mcp_servers`" },
            { "{\"model\":\"m\",\"MCP_Servers\":[],\"tools\":[{\"name\":\"ok\"}]}", "`MCP_Servers`" },
            { "{\"model\":\"m\",\"mcp_servers\":[{\"type\":\"url\",\"url\":\"https://e.example\",\"name\":\"x\"}],"
                    + "\"tools\":[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"x\"}]}", "`mcp_servers`" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"x\"}]}",
                "tool type `mcp_toolset`" },
            { "{\"model\":\"m\",\"input\":\"hi\",\"tools\":[{\"type\":\"mcp\",\"server_label\":\"x\","
                    + "\"server_url\":\"https://evil.example/mcp\",\"require_approval\":\"never\"}]}",
                "tool type `mcp`" },
            { "{\"model\":\"m\",\"input\":\"hi\",\"tools\":[{\"type\":\"mcp\",\"server_label\":\"gmail\","
                    + "\"connector_id\":\"connector_gmail\",\"authorization\":\"t\"}]}", "tool type `mcp`" },
            { "{\"model\":\"m\",\"Container\":{\"skills\":[]}}", "`Container`" },
            { "{\"model\":\"m\",\"container\":\"container_011\"}", "`container`" } };
        for (String[] refused : cases) {
            byte[] body = refused[0].getBytes(StandardCharsets.UTF_8);
            for (boolean dropParams : new boolean[] { false, true }) {
                assertThatThrownBy(() -> LiteLlmRequests.clean(JSON, body, dropParams)).as(refused[0])
                    .isInstanceOf(LiteLlmRequests.Refused.class)
                    .hasMessageContaining(refused[1]);
            }
        }
        assertThat(List.of("mcp_servers", "MCP_SERVERS", "container", "web_search_options", "search_parameters"))
            .allMatch(LiteLlmRequests::providerAction);
        assertThat(List.of("tools", "messages", "input", "containers", "prompt")).noneMatch(
                LiteLlmRequests::providerAction);
    }

    /** A provider action set to null asks for nothing, and passes as it came. */
    @Test
    void aProviderActionThatIsNullPasses() throws LiteLlmRequests.Refused {
        String body = "{\"model\":\"m\",\"mcp_servers\":null,\"container\":null,\"web_search_options\":null}";
        assertThat(new String(LiteLlmRequests.clean(JSON, body.getBytes(StandardCharsets.UTF_8), false).bytes(),
                StandardCharsets.UTF_8)).isEqualTo(body);
    }

    /** What the pinned runtimes send passes. */
    @Test
    void theToolsTheRuntimeRunsPass() throws LiteLlmRequests.Refused {
        String[] bodies = {
            // Claude Code and pi: Anthropic custom tools, a deferred placeholder, a tool defined mid-conversation.
            "{\"model\":\"m\",\"tools\":[{\"name\":\"Bash\",\"input_schema\":{\"type\":\"object\",\"properties\":"
                    + "{\"tools\":{\"type\":\"array\"},\"x\":{\"type\":\"string\"}}}},{\"type\":\"custom\",\"name\":"
                    + "\"later\",\"defer_loading\":true,\"input_schema\":{\"type\":\"object\"}}],"
                    + "\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"tool_addition\",\"tool\":"
                    + "{\"type\":\"tool_definition\","
                    + "\"definition\":{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}}},{\"type\":"
                    + "\"tool_removal\",\"tool\":{\"type\":\"tool_reference\",\"name\":\"Old\"}}]}]}",
            // Codex: function, free-form custom, local shell, a namespace of functions, tool search.
            "{\"model\":\"m\",\"input\":\"hi\",\"tools\":[{\"type\":\"function\",\"name\":\"shell\","
                    + "\"parameters\":{\"type\":\"object\"}},{\"type\":\"custom\",\"name\":\"apply_patch\","
                    + "\"format\":{\"type\":\"grammar\",\"syntax\":\"lark\",\"definition\":\"start: x\"}},"
                    + "{\"type\":\"local_shell\"},{\"type\":\"namespace\",\"name\":\"mcp__wiki\",\"tools\":"
                    + "[{\"type\":\"function\",\"name\":\"search\"}]},{\"type\":\"tool_search\",\"execution\":"
                    + "\"client\"}],\"tool_choice\":\"auto\"}",
            // Chat Completions with function tools; Anthropic's client tools of a fixed schema.
            "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"tools\":[{\"type\":"
                    + "\"function\",\"function\":{\"name\":\"f\",\"parameters\":{\"type\":\"object\"}}}]}",
            "{\"model\":\"m\",\"tools\":[{\"type\":\"bash_20250124\",\"name\":\"bash\"},{\"type\":"
                    + "\"text_editor_20250728\",\"name\":\"str_replace_based_edit_tool\"},{\"type\":"
                    + "\"memory_20250818\",\"name\":\"memory\"},{\"type\":\"tool_search_tool_regex_20251119\","
                    + "\"name\":\"tool_search\"}]}",
            // A tool the model calls directly, said so; a text completion's prompt, a string.
            "{\"model\":\"m\",\"tools\":[{\"name\":\"f\",\"allowed_callers\":[\"direct\"],\"input_schema\":"
                    + "{\"type\":\"object\"}}]}",
            "{\"model\":\"m\",\"prompt\":\"Once upon a time\"}" };
        for (String body : bodies) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            assertThat(new String(LiteLlmRequests.clean(JSON, bytes, false).bytes(), StandardCharsets.UTF_8)).as(body)
                .isEqualTo(body);
        }
    }

    /** A tool the provider runs refuses the request, with its type named, wherever the body defines it. */
    @Test
    void aToolTheProviderRunsIsRefusedWithItsType() {
        String[][] cases = {
            // Anthropic's server tools: web search and fetch, code execution, the MCP connector's toolset, the advisor.
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}]}",
                "web_search_20250305" },
            { "{\"model\":\"m\",\"tools\":[{\"name\":\"ok\"},{\"type\":\"web_fetch_20250910\",\"name\":\"f\"}]}",
                "web_fetch_20250910" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"code_execution_20250825\",\"name\":\"c\"}]}",
                "code_execution_20250825" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"x\"}]}",
                "mcp_toolset" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"advisor_20260301\",\"name\":\"advisor\"}]}",
                "advisor_20260301" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"computer_20250124\",\"name\":\"computer\"}]}",
                "computer_20250124" },
            // OpenAI's hosted tools, LiteLLM's MCP tool among them.
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"mcp\",\"server_label\":\"x\",\"server_url\":"
                    + "\"https://evil.example/mcp\",\"require_approval\":\"never\"}]}", "mcp" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search\"}]}", "web_search" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_preview_2025_03_11\"}]}",
                "web_search_preview_2025_03_11" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"code_interpreter\",\"container\":{\"type\":\"auto\"}}]}",
                "code_interpreter" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"file_search\",\"vector_store_ids\":[\"vs\"]}]}",
                "file_search" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"image_generation\"}]}", "image_generation" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"shell\",\"environment\":{\"type\":\"container_auto\"}}]}",
                "shell" },
            // Hidden in a namespace, in a tool search's output, in a tool defined mid-conversation.
            { "{\"model\":\"m\",\"tools\":[{\"tools\":[{\"type\":\"mcp\",\"server_url\":\"https://e.example\"}],"
                    + "\"type\":\"namespace\",\"name\":\"n\"}]}", "mcp" },
            { "{\"model\":\"m\",\"input\":[{\"type\":\"tool_search_output\",\"tools\":[{\"type\":\"web_search\"}]}]}",
                "web_search" },
            { "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"tool\":{\"definition\":"
                    + "{\"name\":\"f\",\"type\":\"web_fetch_20250910\"},\"type\":\"tool_definition\"},"
                    + "\"type\":\"tool_addition\"}]}]}", "web_fetch_20250910" } };
        for (String[] refused : cases) {
            byte[] body = refused[0].getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> LiteLlmRequests.clean(JSON, body, true)).as(refused[0])
                .isInstanceOf(LiteLlmRequests.Refused.class)
                .hasMessageContaining("tool type `" + refused[1] + "`");
        }
        // A tool's type that is not a string.
        assertThatThrownBy(() -> LiteLlmRequests.clean(JSON,
                "{\"model\":\"m\",\"tools\":[{\"type\":1}]}".getBytes(StandardCharsets.UTF_8), false))
            .isInstanceOf(LiteLlmRequests.Refused.class);
    }

    /**
     * Every web search shape of every provider is refused: there is no setting that lets one through. Anthropic's
     * versions, the dynamic-filtering ones ({@code web_search_20260209} and later, whose default
     * {@code allowed_callers} provisions code execution) with or without {@code allowed_callers}; OpenAI's
     * {@code web_search} and {@code web_search_preview}; Chat Completions' {@code web_search_options}; xAI's live
     * search; Gemini's grounding.
     */
    @Test
    void everyWebSearchShapeIsRefused() {
        String[][] cases = {
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\","
                    + "\"max_uses\":8}]}", "tool type `web_search_20250305`" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_20260209\",\"name\":\"web_search\"}]}",
                "tool type `web_search_20260209`" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_20260209\",\"name\":\"web_search\","
                    + "\"allowed_callers\":[\"direct\"]}]}", "tool type `web_search_20260209`" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_20260318\",\"name\":\"web_search\","
                    + "\"allowed_callers\":[\"code_execution_20260120\"]}]}", "code_execution_20260120" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_fetch_20260209\",\"name\":\"web_fetch\"}]}",
                "tool type `web_fetch_20260209`" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search\",\"external_web_access\":false}]}",
                "tool type `web_search`" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_2025_08_26\"}]}",
                "tool type `web_search_2025_08_26`" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_preview\"}]}", "tool type `web_search_preview`" },
            { "{\"model\":\"m\",\"tools\":[{\"type\":\"x_search\"}]}", "tool type `x_search`" },
            { "{\"model\":\"gpt-4o-search-preview\",\"web_search_options\":{\"search_context_size\":\"low\"}}",
                "`web_search_options`" },
            { "{\"model\":\"m\",\"web_search_options\":{}}", "`web_search_options`" },
            { "{\"model\":\"grok-4\",\"search_parameters\":{\"mode\":\"auto\"}}", "`search_parameters`" },
            { "{\"model\":\"gemini-2.5-pro\",\"tools\":[{\"googleSearch\":{}}]}", "`googleSearch`" },
            { "{\"model\":\"gemini-2.5-pro\",\"tools\":[{\"google_search_retrieval\":{}}]}",
                "`google_search_retrieval`" },
            { "{\"model\":\"gemini-2.5-pro\",\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"},"
                    + "\"url_context\":{}}]}", "`url_context`" },
            { "{\"model\":\"gemini-2.5-pro\",\"tools\":[{\"functionDeclarations\":[{\"name\":\"f\"}]},"
                    + "{\"enterpriseWebSearch\":{}}]}", "`enterpriseWebSearch`" } };
        for (String[] refused : cases) {
            byte[] body = refused[0].getBytes(StandardCharsets.UTF_8);
            for (boolean dropParams : new boolean[] { false, true }) {
                assertThatThrownBy(() -> LiteLlmRequests.clean(JSON, body, dropParams)).as(refused[0])
                    .isInstanceOf(LiteLlmRequests.Refused.class)
                    .hasMessageContaining(refused[1]);
            }
        }
        assertThat(LiteLlmRequests.toolAllowed(null)).isTrue();
        assertThat(List.of("web_search_20260209", "web_search_20260318", "web_search_2025_08_26",
                "web_search_preview", "web_searcher")).noneMatch(LiteLlmRequests::toolAllowed);
        assertThat(List.of("googleSearch", "google_search", "codeExecution", "URL_CONTEXT"))
            .allMatch(LiteLlmRequests::providerToolKey);
        assertThat(List.of("name", "input_schema", "function", "description")).noneMatch(
                LiteLlmRequests::providerToolKey);
    }

    /**
     * A custom tool that the provider's own code execution may call ({@code allowed_callers}), and a prompt stored at
     * the provider (OpenAI Responses' {@code prompt} object, which can carry its own tools), are refused.
     */
    @Test
    void theProvidersCodeCallingAToolAndAStoredPromptAreRefused() {
        String[][] cases = {
            { "{\"model\":\"m\",\"tools\":[{\"name\":\"f\",\"allowed_callers\":[\"direct\","
                    + "\"code_execution_20250825\"]}]}", "`code_execution_20250825`" },
            { "{\"model\":\"m\",\"tools\":[{\"name\":\"f\",\"allowed_callers\":[{\"x\":1}]}]}",
                "not a string" },
            { "{\"model\":\"m\",\"input\":\"hi\",\"prompt\":{\"id\":\"pmpt_1\",\"version\":\"2\"}}",
                "`prompt`" } };
        for (String[] refused : cases) {
            byte[] body = refused[0].getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> LiteLlmRequests.clean(JSON, body, false)).as(refused[0])
                .isInstanceOf(LiteLlmRequests.Refused.class)
                .hasMessageContaining(refused[1]);
        }
    }

    /** Only the beta values on the caller's list pass, from every line of the header, each once; never refused. */
    @Test
    void onlyTheAllowedBetasPass() {
        List<String> allowed = List.of("interleaved-thinking-2025-05-14", "fine-grained-tool-streaming-2025-05-14");
        assertThat(LiteLlmRequests.allowedBetas(List.of(
                "fine-grained-tool-streaming-2025-05-14, mcp-client-2025-04-04",
                "code-execution-2025-08-25,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14"),
                allowed))
            .isEqualTo("fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14");
        assertThat(LiteLlmRequests.allowedBetas(List.of("mcp-client-2025-04-04", " , "), allowed)).isNull();
        assertThat(LiteLlmRequests.allowedBetas(List.of(), allowed)).isNull();
    }
}
