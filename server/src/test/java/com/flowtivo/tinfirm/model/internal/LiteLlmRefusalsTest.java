package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.model.internal.GatewayAdapter.Refused;

/** How the relay reads LiteLLM's refusals of a model request (ADR-0094), as LiteLLM v1.105.1 words them. */
class LiteLlmRefusalsTest {

    private final LiteLlm litellm = new LiteLlm(mock(GatewayHttp.class), mock(ModelProperties.class));

    private final Gateway gateway = new Gateway("http://litellm:4000", "sk-TEST-ONLY-admin-key", true);

    @Test
    void aSpentBudgetIsA429OfItsType() {
        assertThat(litellm.classify(gateway, 429, """
                {"error":{"message":"Budget has been exceeded! Current cost: 5.01, Max budget: 5.0",\
                "type":"budget_exceeded","param":null,"code":"429"}}""")).isEqualTo(Refused.BUDGET_SPENT);
        // LiteLLM's default status for it since v1.105.1, which the bundled configuration replaces with 429.
        assertThat(litellm.classify(gateway, 422, """
                {"error":{"message":"Budget has been exceeded!","type":"budget_exceeded"}}"""))
            .isEqualTo(Refused.OTHER);
        assertThat(litellm.classify(gateway, 429, """
                {"error":{"message":"Rate limit","type":"rate_limit_error"}}""")).isEqualTo(Refused.OTHER);
    }

    @Test
    void aRefusedKeyButNotAModelItMayNotUse() {
        assertThat(litellm.classify(gateway, 401, """
                {"error":{"message":"Authentication Error, Invalid proxy server token passed. \
                Received API Key = sk-***","type":"auth_error"}}""")).isEqualTo(Refused.KEY_REFUSED);
        assertThat(litellm.classify(gateway, 403, "not json")).isEqualTo(Refused.KEY_REFUSED);
        assertThat(litellm.classify(gateway, 401, """
                {"error":{"message":"key not allowed to access model","type":"key_model_access_denied"}}"""))
            .isEqualTo(Refused.OTHER);
    }

    @Test
    void aModelItDoesNotServe() {
        assertThat(litellm.classify(gateway, 400, """
                {"error":{"message":"400: {'error': '/chat/completions: Invalid model name passed in model=gpt-9'}",\
                "type":"None"}}""")).isEqualTo(Refused.MODEL_NOT_SERVED);
        assertThat(litellm.classify(gateway, 400, """
                {"error":{"message":"max_tokens is too large","type":"invalid_request_error"}}"""))
            .isEqualTo(Refused.OTHER);
    }
}
