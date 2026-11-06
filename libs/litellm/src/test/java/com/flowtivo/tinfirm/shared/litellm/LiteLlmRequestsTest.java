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
 * the agent directs (M1 and D8 of the security review of 2026-10-27): {@code mcp_servers} and {@code container} are
 * removed, a tool the provider would run is refused wherever the body defines one, unless it is the providers' web
 * search and the caller allows it, and only the beta values on the caller's list pass.
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
    @Test
    void theProvidersMcpConnectorAndContainerAreRemoved() throws LiteLlmRequests.Refused {
        byte[] body = ("{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,\"mcp_servers\":[{\"type\":\"url\","
                + "\"url\":\"https://evil.example/mcp\",\"name\":\"x\"}],\"Container\":{\"skills\":[]},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").getBytes(StandardCharsets.UTF_8);
        for (LiteLlmRequests.ProviderTools tools : LiteLlmRequests.ProviderTools.values()) {
            assertThat(new String(LiteLlmRequests.clean(JSON, body, false, tools).bytes(), StandardCharsets.UTF_8))
                .isEqualTo("{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        }
        assertThat(List.of("mcp_servers", "MCP_SERVERS", "container")).allMatch(LiteLlmRequests::providerAction);
        assertThat(List.of("tools", "messages", "input", "containers")).noneMatch(LiteLlmRequests::providerAction);
    }

    /** What the pinned runtimes send passes, whatever the company chose. */
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
                    + "\"name\":\"tool_search\"}]}" };
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
        // Chat Completions' own web search, and a tool's type that is not a string.
        assertThatThrownBy(() -> LiteLlmRequests.clean(JSON,
                "{\"model\":\"gpt-4o-search-preview\",\"web_search_options\":{}}".getBytes(StandardCharsets.UTF_8),
                false))
            .hasMessageContaining("web_search_options");
        assertThatThrownBy(() -> LiteLlmRequests.clean(JSON,
                "{\"model\":\"m\",\"tools\":[{\"type\":1}]}".getBytes(StandardCharsets.UTF_8), false))
            .isInstanceOf(LiteLlmRequests.Refused.class);
    }

    /** {@code WEB_SEARCH} lets the providers' web search through, and nothing else. */
    @Test
    void webSearchPassesOnlyWhenAllowed() throws LiteLlmRequests.Refused {
        LiteLlmRequests.ProviderTools search = LiteLlmRequests.ProviderTools.WEB_SEARCH;
        for (String body : new String[] {
            "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\",\"max_uses\":8}]}",
            "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search\",\"external_web_access\":false}]}",
            "{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_preview\"}]}",
            "{\"model\":\"m\",\"web_search_options\":{\"search_context_size\":\"low\"}}" }) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            assertThat(new String(LiteLlmRequests.clean(JSON, bytes, false, search).bytes(), StandardCharsets.UTF_8))
                .isEqualTo(body);
            assertThatThrownBy(() -> LiteLlmRequests.clean(JSON, bytes, false, LiteLlmRequests.ProviderTools.OFF))
                .isInstanceOf(LiteLlmRequests.Refused.class);
        }
        for (String other : new String[] { "web_fetch_20250910", "code_execution_20250825", "mcp", "file_search" }) {
            byte[] bytes = ("{\"model\":\"m\",\"tools\":[{\"type\":\"" + other + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> LiteLlmRequests.clean(JSON, bytes, false, search)).as(other)
                .hasMessageContaining("`" + other + "`");
        }
        assertThat(LiteLlmRequests.toolAllowed(null, LiteLlmRequests.ProviderTools.OFF)).isTrue();
        assertThat(LiteLlmRequests.toolAllowed("web_search_2025_08_26", search)).isTrue();
        assertThat(LiteLlmRequests.toolAllowed("web_search_2025_08_26", LiteLlmRequests.ProviderTools.OFF)).isFalse();
        assertThat(LiteLlmRequests.toolAllowed("web_searcher", search)).isFalse();
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
