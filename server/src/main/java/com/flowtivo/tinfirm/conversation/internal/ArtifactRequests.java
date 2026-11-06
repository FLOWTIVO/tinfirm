package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.runner.RunnerMessages;

import tools.jackson.databind.JsonNode;

/**
 * Seam between parts 2 and 3: the {@code ARTIFACT} request of a run ({@code save_artifact}, the design's
 * {@code save_output}; the package 3 contract, §5.5). Part 2's {@code RunRequests} hands it over with the same
 * transaction and tenant context it was given; part 3 implements it. Without an implementation, the request is answered
 * {@code REJECTED} with {@code REQUEST_UNAVAILABLE}.
 */
public interface ArtifactRequests {

    RunnerMessages.RunRequestAck request(ActiveRun run, UUID requestId, JsonNode payload);
}
