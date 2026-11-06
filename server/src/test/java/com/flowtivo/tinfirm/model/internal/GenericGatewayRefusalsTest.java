package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.model.internal.GatewayAdapter.Refused;

/**
 * How the relay reads the refusals of a gateway of no vendor Tinfirm knows (ADR-0094), and that a refusal's text keeps
 * nothing of a key a gateway team provided, whatever its form.
 */
class GenericGatewayRefusalsTest {

    private final GenericGateway generic = new GenericGateway(mock(GatewayHttp.class));

    private final Gateway gateway = Gateway.provided("https://ai-gateway.example.com", GatewayVendor.GENERIC);

    @Test
    void aSpentBudgetIsA402OrA429ThatSpeaksOfABudget() {
        assertThat(generic.classify(gateway, 402, "{}")).isEqualTo(Refused.BUDGET_SPENT);
        assertThat(generic.classify(gateway, 429, """
                {"error":{"message":"Budget limit exceeded for this key"}}""")).isEqualTo(Refused.BUDGET_SPENT);
        assertThat(generic.classify(gateway, 429, """
                {"error":{"message":"You exceeded your current quota, please check your plan and billing details.",\
                "type":"insufficient_quota"}}""")).isEqualTo(Refused.BUDGET_SPENT);
    }

    /**
     * A provider's rate limit is no spent budget, even where its words name a quota: it passes on, so that the runtime
     * waits and retries, and the key does not wait for a budget.
     */
    @Test
    void aRateLimitIsNoSpentBudget() {
        assertThat(generic.classify(gateway, 429, """
                {"error":{"code":"429","message":"Requests to the ChatCompletions_Create Operation have exceeded \
                token rate limit of your current pricing tier. Please retry after 2 seconds. Please go here: \
                https://aka.ms/oai/quotaincrease if you would like to further increase the default rate limit."}}"""))
            .isEqualTo(Refused.OTHER);
        assertThat(generic.classify(gateway, 429, """
                {"error":{"message":"Quota exceeded for input tokens per minute.","status":"RESOURCE_EXHAUSTED"}}"""))
            .isEqualTo(Refused.OTHER);
        assertThat(generic.classify(gateway, 429, """
                {"message":"AI token rate limit exceeded for provider(s): openai"}""")).isEqualTo(Refused.OTHER);
    }

    @Test
    void aRefusedKeyAndAModelItDoesNotServe() {
        assertThat(generic.classify(gateway, 401, "{\"message\":\"Unauthorized\"}")).isEqualTo(Refused.KEY_REFUSED);
        assertThat(generic.classify(gateway, 403, "not json")).isEqualTo(Refused.KEY_REFUSED);
        assertThat(generic.classify(gateway, 404, """
                {"error":{"message":"The model `gpt-9` does not exist"}}""")).isEqualTo(Refused.MODEL_NOT_SERVED);
        assertThat(generic.classify(gateway, 400, """
                {"error":{"message":"max_tokens is too large"}}""")).isEqualTo(Refused.OTHER);
    }

    /** A provided key need not start with {@code sk-}: the key sent goes, as it came and as a JSON string has it. */
    @Test
    void aRefusalKeepsNoKeyOfAnyForm() {
        String key = "TEST-ONLY/provided+key\"7f3a";
        String echoed = "{\"error\":{\"message\":\"invalid key " + key.replace("\"", "\\\"") + "\"}}";
        assertThat(GatewayAnswers.withoutKey(echoed, key)).isEqualTo("{\"error\":{\"message\":\"invalid key ***\"}}");
        // Escaped as another JSON writer may escape it.
        String escaped = echoed.replace("/", "\\/").replace("+", "\\u002b");
        assertThat(GatewayAnswers.withoutKey(escaped, key)).isEqualTo("{\"error\":{\"message\":\"invalid key ***\"}}");
        assertThat(GatewayAnswers.withoutKey("invalid key " + key + ".", key)).isEqualTo("invalid key ***.");
        assertThat(generic.redact(gateway, GatewayAnswers.withoutKey("key " + key + " and sk-other", key)))
            .isEqualTo("key *** and sk-***");
    }
}
