package com.flowtivo.tinfirm.model.internal;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * {@link ModelRelay}: a run's tokens, made at its assignment and revoked when it ends, is reassigned (the tokens of the
 * earlier epochs), or its assignment turns out stale (that epoch's); revoked tokens are swept a day later, with the
 * reconciliation of the company's keys.
 */
@Component
class ModelRelayImpl implements ModelRelay, KeyReconciliation.CompanyCleanup {

    /** The relay's root under the public URL. */
    static final String ROOT = "/api/runner/v1/model";

    /** How long a revoked token's row, and its counters, are kept. */
    private static final Duration KEEP_REVOKED = Duration.ofDays(1);

    private final RelayTokenStore tokens;

    private final ModelRelayService relay;

    private final SystemTransactions transactions;

    private final TinfirmProperties tinfirm;

    private final TimeSource time;

    private final RelayUsageStore usage;

    ModelRelayImpl(RelayTokenStore tokens, ModelRelayService relay, SystemTransactions transactions,
            TinfirmProperties tinfirm, TimeSource time, RelayUsageStore usage) {
        this.tokens = tokens;
        this.usage = usage;
        this.relay = relay;
        this.transactions = transactions;
        this.tinfirm = tinfirm;
        this.time = time;
    }

    @Override
    @Transactional
    public RunnerMessages.ModelAccess open(RelayedRun run, RunnerMessages.ModelAccessFormat format) {
        RelayTokenStore.RelayFormat relayFormat = switch (format) {
            case ANTHROPIC -> RelayTokenStore.RelayFormat.ANTHROPIC;
            case OPENAI -> RelayTokenStore.RelayFormat.OPENAI;
            default -> throw new IllegalArgumentException("The model relay serves the anthropic and openai formats, "
                    + "not " + format);
        };
        tokens.revokeUpTo(run.companyId(), run.runId(), run.epoch(), time.now());
        String token = RelayTokenStore.generate();
        tokens.insert(RelayTokenStore.hash(token), run, relayFormat, time.now());
        String base = tinfirm.publicBaseUrl() + ROOT + (relayFormat == RelayTokenStore.RelayFormat.ANTHROPIC
                ? "/anthropic" : "/openai/v1");
        return new RunnerMessages.ModelAccess(format, base, token);
    }

    @Override
    @Transactional
    public void revoke(UUID companyId, UUID runId) {
        tokens.revokeRun(companyId, runId, time.now());
        relay.forgetRun(runId);
    }

    @Override
    @Transactional
    public void revoke(UUID companyId, UUID runId, long epoch) {
        // A stale assignment's token was never sent to a host, so no request used it, and the keys kept in memory
        // for the run stay for the tokens that answer.
        tokens.revokeEpoch(companyId, runId, epoch, time.now());
    }

    @Override
    @Transactional(readOnly = true)
    public RelayUsage usage(UUID companyId, UUID runId) {
        return tokens.usage(companyId, runId);
    }

    @Override
    @Transactional(readOnly = true)
    public long answersUnderWay(UUID companyId, UUID runId) {
        return tokens.answersUnderWay(companyId, runId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelUsage> usageByModel(UUID companyId, UUID runId) {
        return usage.ofRun(companyId, runId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> modelNotServed(UUID companyId, UUID runId, long epoch) {
        return tokens.modelNotServed(companyId, runId, epoch);
    }

    @Override
    public void cleanUp(UUID companyId) {
        transactions.inCompany(companyId, () -> tokens.sweep(companyId, time.now().minus(KEEP_REVOKED)));
    }
}
