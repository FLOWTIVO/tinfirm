package com.flowtivo.tinfirm.model.internal;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The model relay's endpoints (the package 2 contract, §6.1), under the runner's path {@code /api/runner/v1/**},
 * whose security chain keeps no session and no CSRF: the run's one-run token is the only credential. Exactly these
 * six; any other path below {@code /api/runner/v1/model} is 404, another method 405. Not part of
 * {@code api/openapi.yaml}: the runtimes speak the providers' APIs here.
 */
@RestController
class ModelRelayController {

    private static final String ANTHROPIC = ModelRelayImpl.ROOT + "/anthropic";

    private static final String OPENAI = ModelRelayImpl.ROOT + "/openai";

    private final ModelRelayService relay;

    ModelRelayController(ModelRelayService relay) {
        this.relay = relay;
    }

    @PostMapping(ANTHROPIC + "/v1/messages")
    void anthropicMessages(HttpServletRequest request, HttpServletResponse response) throws IOException {
        relay.relay(ModelRelayService.Endpoint.ANTHROPIC_MESSAGES, request, response);
    }

    @PostMapping(ANTHROPIC + "/v1/messages/count_tokens")
    void anthropicCountTokens(HttpServletRequest request, HttpServletResponse response) throws IOException {
        relay.relay(ModelRelayService.Endpoint.ANTHROPIC_COUNT_TOKENS, request, response);
    }

    @GetMapping(ANTHROPIC + "/v1/models")
    void anthropicModels(HttpServletRequest request, HttpServletResponse response) throws IOException {
        relay.relay(ModelRelayService.Endpoint.ANTHROPIC_MODELS, request, response);
    }

    @PostMapping(OPENAI + "/v1/responses")
    void openAiResponses(HttpServletRequest request, HttpServletResponse response) throws IOException {
        relay.relay(ModelRelayService.Endpoint.OPENAI_RESPONSES, request, response);
    }

    @PostMapping(OPENAI + "/v1/chat/completions")
    void openAiChatCompletions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        relay.relay(ModelRelayService.Endpoint.OPENAI_CHAT_COMPLETIONS, request, response);
    }

    @GetMapping(OPENAI + "/v1/models")
    void openAiModels(HttpServletRequest request, HttpServletResponse response) throws IOException {
        relay.relay(ModelRelayService.Endpoint.OPENAI_MODELS, request, response);
    }
}
