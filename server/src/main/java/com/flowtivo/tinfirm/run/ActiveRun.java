package com.flowtivo.tinfirm.run;

import java.util.UUID;

/**
 * A run that is RUNNING on its runner host with its epoch: what a platform request ({@link RunRequests}) or an upload
 * of the host is about, once the run module checked it. Everything a request may touch is the conversation's.
 */
public record ActiveRun(UUID companyId, UUID runId, long epoch, UUID runnerHostId, UUID conversationId, UUID agentId,
        UUID actingPrincipalId, UUID payerPrincipalId, int turnNumber) {
}
