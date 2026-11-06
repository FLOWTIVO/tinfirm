package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolReplay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tool-call journal's match of a request sent again (the security review of 2026-10-27, I4): a request id the
 * journal holds is the same call only with the same tool and the same arguments; another tool under that id, with
 * arguments of the same hash, is never answered with the first call's result. And the cap on a call's arguments
 * (D3, accepted with the cap): 64 KiB as JSON in UTF-8 pass, one byte more is refused, counted in bytes, not
 * characters. The refusal end to end ({@code INVALID_REQUEST}, no journal row, no call) is
 * {@link ToolCallJournalIntegrationTest}'s.
 */
class ToolCallsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String arguments = ToolJournal.sha256(JSON.createObjectNode().put("key", "SHOP-1"));

    private final ToolCallStore.Row first = new ToolCallStore.Row(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "get_issue", "jira_get_issue", ToolEffect.READ,
            ToolReplay.SAFE, arguments, ToolCallStore.State.DONE, 1, Instant.parse("2026-10-27T12:00:00Z"), null,
            null, null);

    @Test
    void theSameToolWithTheSameArgumentsIsTheSameCall() {
        assertThat(ToolCalls.sameCall(first, "jira_get_issue", arguments)).isTrue();
    }

    @Test
    void anotherToolUnderTheSameRequestIdIsAnotherCall() {
        assertThat(ToolCalls.sameCall(first, "jira_delete_issue", arguments)).isFalse();
    }

    @Test
    void otherArgumentsAreAnotherCall() {
        String other = ToolJournal.sha256(JSON.createObjectNode().put("key", "SHOP-2"));
        assertThat(ToolCalls.sameCall(first, "jira_get_issue", other)).isFalse();
    }

    /** {@code {"a":"…"}} with {@code size} bytes as JSON in UTF-8, the value made of {@code unit}. */
    private static JsonNode argumentsOf(int size, String unit) {
        int padding = size - "{\"a\":\"\"}".length();
        int bytesPerUnit = unit.getBytes(StandardCharsets.UTF_8).length;
        assertThat(padding % bytesPerUnit).isZero();
        JsonNode arguments = JSON.createObjectNode().put("a", unit.repeat(padding / bytesPerUnit));
        assertThat(arguments.toString().getBytes(StandardCharsets.UTF_8)).hasSize(size);
        return arguments;
    }

    @Test
    void argumentsOf64KiBPassAndOneByteMoreIsRefused() {
        assertThat(SystemTools.MAX_ARGUMENT_BYTES).isEqualTo(64 * 1024);
        assertThat(ToolCalls.tooLarge(argumentsOf(SystemTools.MAX_ARGUMENT_BYTES, "x"))).isFalse();
        assertThat(ToolCalls.tooLarge(argumentsOf(SystemTools.MAX_ARGUMENT_BYTES + 1, "x"))).isTrue();
        assertThat(ToolCalls.tooLarge(JSON.createObjectNode())).isFalse();
    }

    @Test
    void theCapCountsBytesInUtf8NotCharacters() {
        // "é" is two bytes in UTF-8: 64 KiB + 2 bytes are fewer than 64 Ki characters, and refused.
        JsonNode wide = argumentsOf(SystemTools.MAX_ARGUMENT_BYTES + 2, "é");
        assertThat(wide.toString()).hasSizeLessThan(SystemTools.MAX_ARGUMENT_BYTES);
        assertThat(ToolCalls.tooLarge(wide)).isTrue();
        assertThat(ToolCalls.tooLarge(argumentsOf(SystemTools.MAX_ARGUMENT_BYTES, "é"))).isFalse();
    }
}
