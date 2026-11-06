package com.flowtivo.tinfirm.model.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.model.SpendLogs;

import tools.jackson.databind.JsonNode;

/**
 * The adapter the model module's callers are given (ADR-0094): each call goes to the adapter of its gateway's
 * {@link GatewayVendor}, so a caller never chooses one.
 */
@Primary
@Component
class GatewayAdapters implements GatewayAdapter {

    private final LiteLlm litellm;

    private final GenericGateway generic;

    GatewayAdapters(LiteLlm litellm, GenericGateway generic) {
        this.litellm = litellm;
        this.generic = generic;
    }

    /** The adapter of the gateway's vendor. */
    GatewayAdapter of(Gateway gateway) {
        return switch (gateway.vendor()) {
            case LITELLM -> litellm;
            case GENERIC -> generic;
        };
    }

    /** Not one vendor's: answers for the vendor of each call's gateway. */
    @Override
    public GatewayVendor vendor() {
        throw new UnsupportedOperationException("GatewayAdapters speaks to every vendor; ask of(gateway)");
    }

    @Override
    public CreatedKey generate(Gateway gateway, KeySpec spec) {
        return of(gateway).generate(gateway, spec);
    }

    @Override
    public void update(Gateway gateway, String keyId, KeyUpdate update) {
        of(gateway).update(gateway, keyId, update);
    }

    @Override
    public void setSpend(Gateway gateway, String keyId, BigDecimal spend) {
        of(gateway).setSpend(gateway, keyId, spend);
    }

    @Override
    public void block(Gateway gateway, String keyId) {
        of(gateway).block(gateway, keyId);
    }

    @Override
    public void unblock(Gateway gateway, String keyId) {
        of(gateway).unblock(gateway, keyId);
    }

    @Override
    public boolean delete(Gateway gateway, String keyId) {
        return of(gateway).delete(gateway, keyId);
    }

    @Override
    public boolean deleteByAlias(Gateway gateway, String alias) {
        return of(gateway).deleteByAlias(gateway, alias);
    }

    @Override
    public Optional<KeyState> info(Gateway gateway, String keyId) {
        return of(gateway).info(gateway, keyId);
    }

    @Override
    public List<KeyState> teamKeys(Gateway gateway, String teamId) {
        return of(gateway).teamKeys(gateway, teamId);
    }

    @Override
    public void ensureTeam(Gateway gateway, String teamId, UUID companyId, String companyName) {
        of(gateway).ensureTeam(gateway, teamId, companyId, companyName);
    }

    @Override
    public List<String> models(Gateway gateway) {
        return of(gateway).models(gateway);
    }

    @Override
    public List<SpendLogs.Entry> spendLogs(Gateway gateway, String keyId, LocalDate from, LocalDate to) {
        return of(gateway).spendLogs(gateway, keyId, from, to);
    }

    @Override
    public Map<String, SpendLogs.Price> prices(Gateway gateway) {
        return of(gateway).prices(gateway);
    }

    @Override
    public Refused classify(Gateway gateway, int status, String body) {
        return of(gateway).classify(gateway, status, body);
    }

    @Override
    public String redact(Gateway gateway, String text) {
        return of(gateway).redact(gateway, text);
    }

    @Override
    public Map<String, String> tagHeaders(Gateway gateway, String tags) {
        return of(gateway).tagHeaders(gateway, tags);
    }

    @Override
    public JsonNode chat(Gateway gateway, String key, String tags, JsonNode body, Duration chatTimeout) {
        return of(gateway).chat(gateway, key, tags, body, chatTimeout);
    }
}
