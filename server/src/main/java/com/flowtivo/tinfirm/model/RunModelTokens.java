package com.flowtivo.tinfirm.model;

import java.util.UUID;

/**
 * Port: whether a run may still use its one-run token for the model relay (the package 2 contract, §6.1, M7),
 * implemented by the module that runs work (package 3). The relay accepts a token only while its run is
 * {@code RUNNING} on the runner host the token was made for, with the token's epoch; a run that ended, was reassigned
 * (a new epoch, perhaps another host) or is unknown answers false. Runs in the run's company's tenant context and a
 * transaction, once per relayed request, so it should be one indexed read. The run module implements it; without an
 * implementation the relay refuses every request with 401.
 */
public interface RunModelTokens {

    /** Whether the run is {@code RUNNING} on the runner host with the epoch. */
    boolean active(UUID companyId, UUID runId, long epoch, UUID runnerHostId);
}
