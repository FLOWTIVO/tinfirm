package com.flowtivo.tinfirm.run;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.access.CompanyAccess;

/**
 * Port: who sees a run and who cancels it (the package 3 contract, §5.10, §6, I17): a run follows its conversation.
 * Implemented by the conversation module (package 3c) in {@code ConversationsImpl}; runs in the caller's transaction
 * and tenant context. A run the caller may not see is {@code 404}.
 * <p>
 * Without the conversation module a run is the person's ({@code acting_principal_id}): its person and OWNER see and
 * cancel it, an ADMIN who is not OWNER and every other member do not (I17), as for a personal conversation.
 */
public interface RunVisibility {

    /** What of a run a read of the Runs API shows: the audit of OWNER's reads names it (CONVENTIONS §8). */
    enum Read {

        /** {@code GET /runs/{runId}}, and the run that {@code POST /runs/{runId}/cancel} answers. */
        RUN,

        /** {@code GET /runs/{runId}/events}. */
        EVENTS,

        /** {@code GET /runs/{runId}/events/stream}: once per connection. */
        STREAM
    }

    /** Whether the caller (a user with this access to the run's company) may see the run, its events and stream. */
    boolean maySee(CompanyAccess access, UUID userId, RunInfo run);

    /**
     * {@link #maySee}, for a read of the Runs API that shows the run: when the caller sees it as OWNER, or as an
     * instance admin acting in the company, and is not its conversation's person, the implementation audits the read
     * ({@code conversation.read}) in a transaction of its own. Called once per request, for the stream once per
     * connection, and before the caller's transaction locks a row or records an audit event (CONVENTIONS §10).
     */
    boolean read(CompanyAccess access, UUID userId, RunInfo run, Read what);

    /** Whether the caller may cancel the run; called only for a run the caller may see. */
    boolean mayCancel(CompanyAccess access, UUID userId, RunInfo run);
}
