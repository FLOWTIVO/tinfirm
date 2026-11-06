package com.flowtivo.tinfirm.model.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.model.SpendLogs;
import com.flowtivo.tinfirm.shared.net.BoundedBodies;
import com.flowtivo.tinfirm.shared.net.GuardedHosts;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * LiteLLM's admin API, as LiteLLM serves it (verified against the source of v1.105.1,
 * {@code litellm/proxy/management_endpoints/key_management_endpoints.py} and {@code team_endpoints.py},
 * {@code litellm/proxy/_types.py} and {@code litellm/models/team.py}, and {@code proxy_server.py}):
 * <ul>
 * <li>{@code POST /key/generate}: {@code key_alias}, {@code team_id}, {@code max_budget}, {@code budget_duration},
 * {@code models}, {@code metadata}, {@code blocked}, {@code key_type} ({@code llm_api}: the key may call the model
 * routes only), and {@code spend} for a rotation's next key, which starts with the spend of the key it replaces; the
 * answer's {@code key} is the virtual key and {@code token} its SHA-256, LiteLLM's id of it. The alias is unique among
 * all keys (400 for a taken one). The budget's reset time is LiteLLM's own, from the period and the calendar
 * ({@code get_budget_reset_time}: {@code 30d} and {@code 1mo} on the 1st of the month, {@code 7d} on Mondays,
 * {@code 1d} at midnight, hours and minutes on their boundaries, other day counts from today's midnight), and cannot
 * be set. Key {@code tags} are an enterprise feature and are not used: requests are tagged instead.
 * {@code /key/regenerate} is an enterprise feature as well, so Tinfirm rotates a key with a new one.</li>
 * <li>{@code POST /key/update} by {@code key} (the id is accepted): a merge patch; {@code metadata} would replace the
 * stored one, so it is never sent, and {@code budget_duration} restarts the period, so it is sent only when it
 * changed. {@code spend} sets the key's spend (and LiteLLM's spend counter of the key).</li>
 * <li>{@code POST /key/block} and {@code /key/unblock} with {@code key} (the id is accepted).</li>
 * <li>{@code POST /key/delete} with {@code keys} (ids are accepted) or {@code key_aliases}; 404 when none exists.</li>
 * <li>{@code GET /key/info?key=<id>}: {@code info} with {@code key_alias}, {@code spend} (of the current period),
 * {@code max_budget}, {@code budget_duration}, {@code budget_reset_at}, {@code models}, {@code blocked} and a derived
 * {@code status} ({@code deleted} for a key LiteLLM archived); 404 for an unknown key.</li>
 * <li>{@code GET /key/list?team_id=&return_full_object=true&page=&size=} (at most 100 per page): the team's keys with
 * {@code token} and {@code key_alias}.</li>
 * <li>{@code GET /team/info?team_id=} (404 for an unknown team; {@code team_info.team_alias} is the team's alias),
 * {@code POST /team/new} with {@code team_id}, {@code team_alias} (any text, not unique: the company's name, which
 * LiteLLM's admin shows for every key of the team) and {@code metadata}, and {@code POST /team/update} by
 * {@code team_id}, a merge patch as {@code /key/update} is, with {@code team_alias} alone: {@code metadata} would
 * replace the stored one, and {@code budget_duration} restart the period.</li>
 * <li>{@code GET /v1/models} with the admin key lists every model the gateway serves ({@code data[].id}), also when it
 * serves none ({@code GET /model/info} answers 500 then).</li>
 * <li>From package 6: {@code GET /spend/logs?api_key=&start_date=&end_date=&summarize=false}, a key's spend logs one
 * row per request ({@link #spendLogs}), and {@code GET /model/info}, the model prices ({@link #prices}).</li>
 * </ul>
 * Errors come back as {@code {"error": {"message", "type", "code"}}} or {@code {"detail": ...}}; their message is
 * passed on, cut short, without what it echoes of a key ({@link #redact}). Keys are never logged. An answer is read
 * within a limit ({@link #MAX_ADMIN_ANSWER_BYTES}, {@link #MAX_CHAT_ANSWER_BYTES}; {@link BoundedBodies}) and within
 * the call's timeout, body included: a company's own gateway is an address an admin entered, and one that answers
 * more, or does not end its answer in time, fails the call as LiteLLM's errors do.
 */
@Component
class LiteLlm {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int MAX_REASON = 300;

    private static final int PAGE_SIZE = 100;

    /**
     * The most bytes of an admin API answer the server reads: 64 MiB. The largest is a key's spend logs
     * ({@link #spendLogs}): whole UTC days of one key's requests, which v1.105.1 does not page, a few KiB a request (its
     * metadata holds the usage object and the model's cost map), so some 15,000 requests of a busy key over two days. A
     * page of keys ({@code /key/list}, 100) is under 1 MiB, the model prices of a gateway with a thousand deployments
     * ({@code /model/info}) some 5 MiB, every other answer a few KiB. A gateway that answers more (a misconfigured or
     * hostile company gateway, or one that keeps prompts in its spend logs) fails the call ({@link LiteLlmException}),
     * as LiteLLM's errors do, before it exhausts the server's memory. The relay's limit of a request is the same.
     */
    static final int MAX_ADMIN_ANSWER_BYTES = 64 * 1024 * 1024;

    /**
     * The most bytes of a chat completion's answer the server reads ({@link #chat}): 1 MiB. The platform's requests ask
     * for a few thousand tokens at most (a memory's compaction 4000, some 16 KiB of text), and LiteLLM's answer of that
     * many, with its envelope and a reasoning model's thinking, stays under 100 KiB.
     */
    static final int MAX_CHAT_ANSWER_BYTES = 1024 * 1024;

    private static final Pattern KEY = Pattern.compile("sk-[^\\s,;\"'`)\\]}]*");

    private static final Pattern HEX_64 = Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");

    private final GatewayHttp http;

    private final Duration timeout;

    LiteLlm(GatewayHttp http, ModelProperties properties) {
        this.http = http;
        this.timeout = properties.adminTimeout();
    }

    /** What a new key should be; {@code spend} what it starts with (null: nothing, a principal's first key). */
    record KeySpec(String alias, String teamId, BigDecimal maxBudget, String budgetDuration, List<String> models,
            Map<String, String> metadata, boolean blocked, @Nullable BigDecimal spend) {
    }

    /** A key LiteLLM created; {@link #toString()} never shows it. */
    record CreatedKey(String key, String keyId) {

        @Override
        public String toString() {
            return "CreatedKey[keyId=" + keyId + ", key=***]";
        }
    }

    /** A key as LiteLLM knows it. */
    record KeyState(String keyId, @Nullable String alias, boolean blocked, double spend, @Nullable Double maxBudget,
            @Nullable String budgetDuration, @Nullable Instant budgetResetAt, List<String> models) {
    }

    /** A change of a key; null fields stay as they are. */
    record KeyUpdate(@Nullable BigDecimal maxBudget, @Nullable String budgetDuration, @Nullable List<String> models) {

        boolean empty() {
            return maxBudget == null && budgetDuration == null && models == null;
        }
    }

    CreatedKey generate(Gateway gateway, KeySpec spec) {
        ObjectNode body = JSON.createObjectNode();
        body.put("key_alias", spec.alias());
        body.put("team_id", spec.teamId());
        body.put("max_budget", spec.maxBudget());
        body.put("budget_duration", spec.budgetDuration());
        ArrayNode models = body.putArray("models");
        spec.models().forEach(models::add);
        ObjectNode metadata = body.putObject("metadata");
        spec.metadata().forEach(metadata::put);
        body.put("blocked", spec.blocked());
        body.put("key_type", "llm_api");
        if (spec.spend() != null) {
            body.put("spend", spec.spend());
        }
        JsonNode answer = call(gateway, "POST", "/key/generate", body);
        String key = text(answer, "key");
        String keyId = text(answer, "token");
        if (key == null || keyId == null || !key.startsWith("sk-")) {
            throw new LiteLlmException(200, "LiteLLM created a key but did not return it as expected");
        }
        return new CreatedKey(key, keyId);
    }

    void update(Gateway gateway, String keyId, KeyUpdate update) {
        if (update.empty()) {
            return;
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("key", keyId);
        if (update.maxBudget() != null) {
            body.put("max_budget", update.maxBudget());
        }
        if (update.budgetDuration() != null) {
            body.put("budget_duration", update.budgetDuration());
        }
        if (update.models() != null) {
            ArrayNode models = body.putArray("models");
            update.models().forEach(models::add);
        }
        call(gateway, "POST", "/key/update", body);
    }

    /** Sets the key's spend in its current period. */
    void setSpend(Gateway gateway, String keyId, BigDecimal spend) {
        ObjectNode body = JSON.createObjectNode();
        body.put("key", keyId);
        body.put("spend", spend);
        call(gateway, "POST", "/key/update", body);
    }

    void block(Gateway gateway, String keyId) {
        call(gateway, "POST", "/key/block", JSON.createObjectNode().put("key", keyId));
    }

    void unblock(Gateway gateway, String keyId) {
        call(gateway, "POST", "/key/unblock", JSON.createObjectNode().put("key", keyId));
    }

    /** Deletes the key by its id; false when LiteLLM knows no such key. */
    boolean delete(Gateway gateway, String keyId) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("keys").add(keyId);
        return deleteKeys(gateway, body);
    }

    /** Deletes the key of the alias; false when LiteLLM knows none. */
    boolean deleteByAlias(Gateway gateway, String alias) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("key_aliases").add(alias);
        return deleteKeys(gateway, body);
    }

    private boolean deleteKeys(Gateway gateway, ObjectNode body) {
        try {
            call(gateway, "POST", "/key/delete", body);
            return true;
        }
        catch (LiteLlmException ex) {
            if (ex.notFound()) {
                return false;
            }
            throw ex;
        }
    }

    /** The key as LiteLLM knows it; empty when it knows no such key, or deleted it. */
    Optional<KeyState> info(Gateway gateway, String keyId) {
        JsonNode answer;
        try {
            answer = call(gateway, "GET", "/key/info?key=" + encode(keyId), null);
        }
        catch (LiteLlmException ex) {
            if (ex.notFound()) {
                return Optional.empty();
            }
            throw ex;
        }
        JsonNode info = answer.get("info");
        if (info == null || !info.isObject() || "deleted".equals(text(info, "status"))) {
            return Optional.empty();
        }
        return Optional.of(state(keyId, info));
    }

    /** Every key of the team, page by page. */
    List<KeyState> teamKeys(Gateway gateway, String teamId) {
        List<KeyState> keys = new ArrayList<>();
        for (int page = 1;; page++) {
            JsonNode answer = call(gateway, "GET", "/key/list?team_id=" + encode(teamId)
                    + "&return_full_object=true&size=" + PAGE_SIZE + "&page=" + page, null);
            JsonNode items = answer.get("keys");
            if (items == null || !items.isArray() || items.isEmpty()) {
                return keys;
            }
            for (JsonNode item : items) {
                String keyId = text(item, "token");
                if (keyId != null) {
                    keys.add(state(keyId, item));
                }
            }
            JsonNode totalPages = answer.get("total_pages");
            if (totalPages == null || !totalPages.isNumber() || page >= totalPages.asInt() || page >= 1000) {
                return keys;
            }
        }
    }

    /**
     * Creates the company's team unless LiteLLM knows it, with the company's name as its alias (its metadata holds the
     * company's id alone, {@code tinfirm_company}: the alias is what follows the name): LiteLLM's admin shows the
     * alias beside every key of the team, where an id alone would not say whose keys they are. A known team whose
     * alias is not the company's name
     * (it was renamed, the team is from before Tinfirm named them, or another replica created it at the same moment
     * with the name it had planned with) is given the name.
     */
    void ensureTeam(Gateway gateway, String teamId, UUID companyId, String companyName) {
        Optional<String> alias = teamAlias(gateway, teamId);
        if (alias.isEmpty()) {
            ObjectNode body = JSON.createObjectNode();
            body.put("team_id", teamId);
            body.put("team_alias", companyName);
            body.putObject("metadata").put("tinfirm_company", companyId.toString());
            try {

                call(gateway, "POST", "/team/new", body);
                return;
            }
            catch (LiteLlmException ex) {
                // Another replica may have created it at the same moment, with the name it had planned with.
                alias = teamAlias(gateway, teamId);
                if (alias.isEmpty()) {
                    throw ex;
                }
            }
        }
        if (!companyName.equals(alias.get())) {
            ObjectNode body = JSON.createObjectNode();
            body.put("team_id", teamId);
            body.put("team_alias", companyName);
            call(gateway, "POST", "/team/update", body);
        }
    }


    /**
     * The team's alias as LiteLLM knows it (empty text for a team without one); empty when LiteLLM knows no such
     * team.
     */
    private Optional<String> teamAlias(Gateway gateway, String teamId) {
        JsonNode answer;
        try {
            answer = call(gateway, "GET", "/team/info?team_id=" + encode(teamId) + "&key_limit=1", null);
        }
        catch (LiteLlmException ex) {
            if (ex.notFound()) {
                return Optional.empty();
            }
            throw ex;
        }
        String alias = text(answer.get("team_info"), "team_alias");
        return Optional.of(alias != null ? alias : "");
    }

    /** The model names the gateway serves, sorted, without duplicates. */
    List<String> models(Gateway gateway) {
        JsonNode answer = call(gateway, "GET", "/v1/models", null);
        JsonNode data = answer.get("data");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (data != null && data.isArray()) {
            for (JsonNode model : data) {
                String id = text(model, "id");
                if (id != null && !id.isBlank()) {
                    names.add(id);
                }
            }
        }
        return names.stream().sorted().toList();
    }

    // ---- spend (package 6) ---------------------------------------------------------------------------------------

    /**
     * The spend logs of one key from {@code from}'s day to {@code to}'s day (UTC), as v1.105.1's {@code GET
     * /spend/logs?api_key=&start_date=&end_date=&summarize=false} answers them: one row per request, with
     * {@code request_id}, {@code api_key} (the key's id), {@code spend}, {@code prompt_tokens},
     * {@code completion_tokens}, {@code startTime}, {@code model}, {@code model_group}, {@code request_tags} (a list,
     * or its JSON text) and {@code metadata.usage_object} (the cache's tokens). The dates are whole days, the end day
     * included, so the caller narrows by time and tag. Prompts are not read.
     */
    List<SpendLogs.Entry> spendLogs(Gateway gateway, String keyId, LocalDate from, LocalDate to) {
        JsonNode answer = call(gateway, "GET", "/spend/logs?api_key=" + encode(keyId) + "&start_date="
                + from + "&end_date=" + to.plusDays(1) + "&summarize=false", null);
        JsonNode rows = answer.isArray() ? answer : answer.get("data");
        List<SpendLogs.Entry> entries = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return entries;
        }
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                continue;
            }
            String group = text(row, "model_group");
            String model = group != null && !group.isBlank() ? group : text(row, "model");
            JsonNode usage = row.path("metadata").path("usage_object");
            long cacheRead = number(usage, "cache_read_input_tokens");
            if (cacheRead == 0) {
                cacheRead = number(usage.path("prompt_tokens_details"), "cached_tokens");
            }
            long cacheWrite = number(usage, "cache_creation_input_tokens");
            long prompt = number(row, "prompt_tokens");
            String rowKey = text(row, "api_key");
            JsonNode spend = row.get("spend");
            entries.add(new SpendLogs.Entry(String.valueOf(text(row, "request_id")),
                    rowKey != null ? rowKey : keyId, model != null && !model.isBlank() ? model : "unknown",
                    tags(row.get("request_tags")), instant(text(row, "startTime")),
                    spend != null && spend.isNumber() ? spend.decimalValue() : BigDecimal.ZERO,
                    Math.max(0, prompt - cacheRead - cacheWrite), number(row, "completion_tokens"), cacheRead,
                    cacheWrite));
        }
        return entries;
    }

    /**
     * The gateway's model prices from {@code GET /model/info} ({@code data[].model_name},
     * {@code litellm_params.model}, {@code model_info.input_cost_per_token}, {@code output_cost_per_token},
     * {@code cache_read_input_token_cost}, {@code cache_creation_input_token_cost}), by the model's name in the gateway,
     * its provider's name, and that name without the provider's prefix.
     */
    Map<String, SpendLogs.Price> prices(Gateway gateway) {
        JsonNode data = call(gateway, "GET", "/model/info", null).get("data");
        Map<String, SpendLogs.Price> prices = new java.util.HashMap<>();
        if (data == null || !data.isArray()) {
            return prices;
        }
        for (JsonNode model : data) {
            JsonNode info = model.path("model_info");
            BigDecimal input = decimal(info, "input_cost_per_token");
            BigDecimal output = decimal(info, "output_cost_per_token");
            if (input == null || output == null) {
                continue;
            }
            SpendLogs.Price price = new SpendLogs.Price(input, output, decimal(info, "cache_read_input_token_cost"),
                    decimal(info, "cache_creation_input_token_cost"));
            String name = text(model, "model_name");
            String provider = text(model.path("litellm_params"), "model");
            if (provider != null) {
                prices.putIfAbsent(provider, price);
                int slash = provider.indexOf('/');
                if (slash >= 0) {
                    prices.putIfAbsent(provider.substring(slash + 1), price);
                }
            }
            if (name != null) {
                prices.put(name, price);
            }
        }
        return prices;
    }

    private static List<String> tags(@Nullable JsonNode node) {
        JsonNode list = node;
        if (node != null && node.isString()) {
            list = parse(node.stringValue());
        }
        List<String> tags = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode tag : list) {
                if (tag.isString()) {
                    tags.add(tag.stringValue());
                }
            }
        }
        return tags;
    }

    private static long number(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isNumber() ? Math.max(0, value.asLong()) : 0;
    }

    private static @Nullable BigDecimal decimal(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    /**
     * One chat completion with a key (OpenAI's format, which LiteLLM serves for every model), for the server's own
     * requests on the company's own key ({@link PlatformModelService}, ADR-0065): never a run's. The key
     * goes only in the request's header; the answer's errors are redacted as the admin API's are; an answer larger than
     * {@link #MAX_CHAT_ANSWER_BYTES} fails as they do.
     */
    JsonNode chat(Gateway gateway, String key, String tags, JsonNode body, Duration chatTimeout) {
        NoTransaction.require("A model request to LiteLLM");
        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway.modelUrl() + "/v1/chat/completions"))
            .timeout(chatTimeout)
            .header("Authorization", "Bearer " + key)
            .header("Accept", "application/json")
            .header("User-Agent", "Tinfirm")
            .header("x-litellm-tags", tags)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
        HttpResponse<@Nullable String> response;
        try (GuardedHosts.Registration guard = http.guard(gateway)) {
            response = http.client().send(request, BoundedBodies.ofString(MAX_CHAT_ANSWER_BYTES, chatTimeout));
        }
        catch (HttpTimeoutException ex) {
            throw LiteLlmException.unreachable("LiteLLM did not answer in time");
        }
        catch (IOException ex) {
            throw LiteLlmException.unreachable("LiteLLM cannot be reached");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw LiteLlmException.unreachable("Interrupted while calling LiteLLM");
        }
        JsonNode answer = parse(within(response, MAX_CHAT_ANSWER_BYTES));
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new LiteLlmException(response.statusCode(), redact("LiteLLM answered " + response.statusCode() + ": "
                    + reason(answer)));
        }
        if (answer == null) {
            throw new LiteLlmException(response.statusCode(), "LiteLLM's answer is not JSON");
        }
        return answer;
    }

    private JsonNode call(Gateway gateway, String method, String pathAndQuery, @Nullable JsonNode body) {
        NoTransaction.require("A call to LiteLLM");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gateway.baseUrl() + pathAndQuery))
            .timeout(timeout)
            .header("Authorization", "Bearer " + gateway.adminKey())
            .header("Accept", "application/json")
            .header("User-Agent", "Tinfirm");
        if (body != null) {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<@Nullable String> response;
        try (GuardedHosts.Registration guard = http.guard(gateway)) {
            response = http.client().send(request.build(), BoundedBodies.ofString(MAX_ADMIN_ANSWER_BYTES, timeout));
        }
        catch (HttpTimeoutException ex) {
            throw LiteLlmException.unreachable("LiteLLM did not answer in time");
        }
        catch (IOException ex) {
            throw LiteLlmException.unreachable("LiteLLM cannot be reached");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw LiteLlmException.unreachable("Interrupted while calling LiteLLM");
        }
        JsonNode answer = parse(within(response, MAX_ADMIN_ANSWER_BYTES));
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new LiteLlmException(response.statusCode(), "LiteLLM answered " + response.statusCode() + ": "
                    + reason(answer));
        }
        if (answer == null) {
            throw new LiteLlmException(response.statusCode(), "LiteLLM's answer is not JSON");
        }
        return answer;
    }

    /**
     * The answer's body, read within its limit ({@link BoundedBodies}); a larger one fails the call as an error of
     * LiteLLM's, with its status.
     */
    private static String within(HttpResponse<@Nullable String> response, int maxBytes) {
        String body = response.body();
        if (body == null) {
            throw new LiteLlmException(response.statusCode(), "LiteLLM's answer (" + response.statusCode()
                    + ") is larger than the " + maxBytes / (1024 * 1024) + " MiB Tinfirm reads");
        }
        return body;
    }

    private static @Nullable JsonNode parse(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(body);
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    /** LiteLLM's reason for an error: {@code error.message}, {@code detail.error} or {@code detail}, cut short. */
    private static String reason(@Nullable JsonNode answer) {
        if (answer == null) {
            return "no reason given";
        }
        JsonNode error = answer.get("error");
        String reason = null;
        if (error != null && error.isObject()) {
            reason = text(error, "message");
        }
        else if (error != null && error.isString()) {
            reason = error.stringValue();
        }
        JsonNode detail = answer.get("detail");
        if (reason == null && detail != null) {
            reason = detail.isString() ? detail.stringValue()
                    : detail.isObject() && text(detail, "error") != null ? text(detail, "error")
                            : detail.isObject() && text(detail, "message") != null ? text(detail, "message") : null;
        }
        if (reason == null) {
            return "no reason given";
        }
        String oneLine = redact(reason).replaceAll("\\s+", " ").strip();
        return oneLine.length() <= MAX_REASON ? oneLine : oneLine.substring(0, MAX_REASON - 1) + "…";
    }

    /**
     * A reason of LiteLLM's without what it echoes of a key: its 401 names the key it refused, abbreviated
     * ({@code sk-...} and its last four characters), and the key's SHA-256 ({@code user_api_key_auth.py},
     * {@code auth_utils.abbreviate_api_key}).
     */
    static String redact(String reason) {
        return HEX_64.matcher(KEY.matcher(reason).replaceAll("sk-***")).replaceAll("***");
    }

    private static KeyState state(String keyId, JsonNode info) {
        JsonNode blocked = info.get("blocked");
        JsonNode spend = info.get("spend");
        JsonNode maxBudget = info.get("max_budget");
        List<String> models = new ArrayList<>();
        JsonNode modelList = info.get("models");
        if (modelList != null && modelList.isArray()) {
            for (JsonNode model : modelList) {
                if (model.isString()) {
                    models.add(model.stringValue());
                }
            }
        }
        return new KeyState(keyId, text(info, "key_alias"),
                blocked != null && blocked.isBoolean() && blocked.booleanValue(),
                spend != null && spend.isNumber() ? spend.asDouble() : 0.0,
                maxBudget != null && maxBudget.isNumber() ? maxBudget.asDouble() : null, text(info, "budget_duration"),
                instant(text(info, "budget_reset_at")), List.copyOf(models));
    }

    private static @Nullable Instant instant(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        }
        catch (DateTimeParseException ex) {
            try {
                // LiteLLM may write a time without an offset; it is UTC.
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            }
            catch (DateTimeParseException again) {
                return null;
            }
        }
    }

    private static @Nullable String text(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
