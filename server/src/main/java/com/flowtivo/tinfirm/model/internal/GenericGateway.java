package com.flowtivo.tinfirm.model.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.model.SpendLogs;

import tools.jackson.databind.JsonNode;

/**
 * A gateway of no vendor Tinfirm knows, whose keys the company's gateway team provides (ADR-0094): Kong AI Gateway, an
 * Envoy AI Gateway, any that serves the model routes in OpenAI's or Anthropic's format with keys of its own. Tinfirm
 * holds no admin key there, so the key lifecycle, the team, spend logs and prices are none of this adapter's: they fail
 * as a gateway that refused them would, and the callers do not ask for them of a provided gateway. Its refusals are
 * read by their status and words: a spent budget is a {@code 402}, or a {@code 429} whose error speaks of a budget, a
 * quota or a spend limit and not of a rate limit (a provider's rate limit may name its quota too, and passes on, so
 * that the runtime waits and retries); a {@code 401} or {@code 403} refuses the key; a {@code 400} or {@code 404} that
 * says a model is unknown or not found is a model it does not serve. Its requests are tagged with
 * {@code x-tinfirm-tags}, which a gateway's own logs can record.
 */
@Component
class GenericGateway implements GatewayAdapter {

    private static final String WHO = "The gateway";

    private static final Pattern BUDGET = Pattern.compile("budget|quota|spend limit|credit|billing|insufficient funds");

    private static final Pattern RATE = Pattern.compile(
            "rate.?limit|per (minute|second|hour)|retry after|try again in");

    private static final Pattern NOT_SERVED = Pattern.compile(
            "model[^.]{0,80}(not found|does not exist|unknown|not supported|invalid)|(unknown|invalid) model");

    private final GatewayHttp http;

    GenericGateway(GatewayHttp http) {
        this.http = http;
    }

    @Override
    public GatewayVendor vendor() {
        return GatewayVendor.GENERIC;
    }

    @Override
    public Refused classify(Gateway gateway, int status, String body) {
        String text = body.toLowerCase(Locale.ROOT);
        if (status == 402 || status == 429 && BUDGET.matcher(text).find() && !RATE.matcher(text).find()) {
            return Refused.BUDGET_SPENT;
        }
        if (status == 401 || status == 403) {
            return Refused.KEY_REFUSED;
        }
        if ((status == 400 || status == 404) && NOT_SERVED.matcher(text).find()) {
            return Refused.MODEL_NOT_SERVED;
        }
        return Refused.OTHER;
    }

    @Override
    public String redact(Gateway gateway, String text) {
        return GatewayAnswers.redact(text);
    }

    @Override
    public Map<String, String> tagHeaders(Gateway gateway, String tags) {
        return Map.of("x-tinfirm-tags", tags);
    }

    @Override
    public JsonNode chat(Gateway gateway, String key, String tags, JsonNode body, Duration chatTimeout) {
        return GatewayAnswers.chat(http, gateway, key, tagHeaders(gateway, tags), body, chatTimeout, WHO);
    }

    // ---- what only a gateway Tinfirm manages answers ----------------------------------------------------------

    private static LiteLlmException notManaged() {
        return LiteLlmException.refused("Tinfirm does not manage the keys of this gateway: its gateway team does");
    }

    @Override
    public CreatedKey generate(Gateway gateway, KeySpec spec) {
        throw notManaged();
    }

    @Override
    public void update(Gateway gateway, String keyId, KeyUpdate update) {
        throw notManaged();
    }

    @Override
    public void setSpend(Gateway gateway, String keyId, BigDecimal spend) {
        throw notManaged();
    }

    @Override
    public void block(Gateway gateway, String keyId) {
        throw notManaged();
    }

    @Override
    public void unblock(Gateway gateway, String keyId) {
        throw notManaged();
    }

    @Override
    public boolean delete(Gateway gateway, String keyId) {
        throw notManaged();
    }

    @Override
    public boolean deleteByAlias(Gateway gateway, String alias) {
        throw notManaged();
    }

    @Override
    public Optional<KeyState> info(Gateway gateway, String keyId) {
        throw notManaged();
    }

    @Override
    public List<KeyState> teamKeys(Gateway gateway, String teamId) {
        throw notManaged();
    }

    @Override
    public void ensureTeam(Gateway gateway, String teamId, UUID companyId, String companyName) {
        throw notManaged();
    }

    @Override
    public List<String> models(Gateway gateway) {
        throw notManaged();
    }

    /** None: the gateway's records are its team's; spend is settled from the relay's own count. */
    @Override
    public List<SpendLogs.Entry> spendLogs(Gateway gateway, String keyId, LocalDate from, LocalDate to) {
        return List.of();
    }

    /** None known. */
    @Override
    public Map<String, SpendLogs.Price> prices(Gateway gateway) {
        return Map.of();
    }
}
