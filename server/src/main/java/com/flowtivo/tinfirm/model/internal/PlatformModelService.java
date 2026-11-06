package com.flowtivo.tinfirm.model.internal;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import com.flowtivo.tinfirm.model.PlatformModel;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.VaultService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tinfirm's own requests on the company's own key (ADR-0065; {@link PlatformModel}). The key is the company's
 * ({@link KeyHolder#company}), created, synced, rotated and reconciled with its principals' keys, with a budget and
 * models of its own, and no principal's: no service identity or member pays. It is revealed for each request
 * ({@code secret.accessed}, purpose {@code platform:{purpose}}) and never leaves the server; a request is tagged
 * {@code tinfirm-task:{purpose}} in LiteLLM's spend logs, and the key is synced after it, so that its spend shows.
 */
@Service
class PlatformModelService implements PlatformModel {

    /** The longest a platform request may take. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@+\\[\\]-]{0,199}$");

    private static final Pattern PURPOSE = Pattern.compile("^[a-z][a-z0-9-]{0,40}$");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ModelSettingsStore settings;

    private final ModelKeyStore keys;

    private final Gateways gateways;

    private final LiteLlm liteLlm;

    private final VaultService vault;

    private final KeyWork work;

    private final SystemTransactions transactions;

    PlatformModelService(ModelSettingsStore settings, ModelKeyStore keys, Gateways gateways, LiteLlm liteLlm,
            VaultService vault, KeyWork work, SystemTransactions transactions) {
        this.settings = settings;
        this.keys = keys;
        this.gateways = gateways;
        this.liteLlm = liteLlm;
        this.vault = vault;
        this.work = work;
        this.transactions = transactions;
    }

    @Override
    public boolean allows(UUID companyId, String model) {
        return MODEL.matcher(model).matches() && RunModelAccess.allows(models(companyId), model);
    }

    /** The models the company's own key allows: its own, else the company's default ones. */
    private List<String> models(UUID companyId) {
        List<String> own = keys.find(KeyHolder.company(companyId)).map(ModelKeyStore.KeyRow::models).orElse(null);
        return own != null ? own
                : settings.find(companyId).map(ModelSettingsStore.SettingsRow::defaultModels).orElse(List.of());
    }

    /** What a request needs, read in one short transaction. */
    private record Plan(Gateway gateway, UUID secretId) {
    }

    @Override
    public Completion complete(UUID companyId, String purpose, String model, String system, String user,
            int maxTokens) {
        NoTransaction.require("A request on the company's own key");
        if (!PURPOSE.matcher(purpose).matches()) {
            throw new IllegalArgumentException("Not a purpose: " + purpose);
        }
        Object planned = transactions.inCompany(companyId, () -> plan(companyId, model));
        if (planned instanceof Completion.Unavailable unavailable) {
            return unavailable;
        }
        Plan plan = (Plan) planned;
        ObjectNode body = JSON.createObjectNode()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("temperature", 0);
        body.putArray("messages")
            .add(JSON.createObjectNode().put("role", "system").put("content", system))
            .add(JSON.createObjectNode().put("role", "user").put("content", user));
        JsonNode answer;
        try (RevealedSecret key = vault.reveal(companyId, plan.secretId(), "platform:" + purpose)) {
            answer = liteLlm.chat(plan.gateway(), key.asString(), "tinfirm-task:" + purpose, body, REQUEST_TIMEOUT);
        }
        catch (LiteLlmException ex) {
            return new Completion.Unavailable("FAILED", ex.getMessage());
        }
        finally {
            work.syncAfterCommit(KeyHolder.company(companyId));
        }
        String text = answer.path("choices").path(0).path("message").path("content").asString("");
        JsonNode usage = answer.path("usage");
        return new Completion.Answer(text, answer.path("model").asString(model),
                usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
    }

    private Object plan(UUID companyId, String model) {
        ModelSettingsStore.SettingsRow row = settings.find(companyId).orElse(null);
        if (row == null || !row.relayEnabled()) {
            return unavailable("GATEWAY_NOT_CONFIGURED", "The company's model gateway has not passed its test");
        }
        if (!allows(companyId, model)) {
            return unavailable("MODEL_NOT_ALLOWED", "The company's own key does not allow " + model);
        }
        ModelKeyStore.KeyRow key = keys.find(KeyHolder.company(companyId)).orElse(null);
        if (key == null || !key.hasKey() || key.status() == ModelKeyStore.KeyStatus.ERROR
                || key.status() == ModelKeyStore.KeyStatus.BLOCKED) {
            return unavailable("KEY_ERROR", "The company's own key is not usable yet");
        }
        if (key.status() == ModelKeyStore.KeyStatus.BUDGET_SPENT) {
            return unavailable("BUDGET_SPENT", "The company's own key's budget is spent");
        }
        Gateway gateway = gateways.of(row).orElse(null);
        if (gateway == null) {
            return unavailable("GATEWAY_NOT_CONFIGURED", "The company's model gateway is not reachable");
        }
        return new Plan(gateway, Objects.requireNonNull(key.secretId()));
    }

    private static Completion.Unavailable unavailable(String reason, @Nullable String detail) {
        return new Completion.Unavailable(reason, detail != null ? detail : reason);
    }
}
