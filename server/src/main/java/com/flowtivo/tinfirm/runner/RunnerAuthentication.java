package com.flowtivo.tinfirm.runner;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Authenticates runner host requests outside the WebSocket with the same {@code tfr_…} token as the connection: as
 * {@code Authorization: Bearer tfr_…} for the files of a run (ADR-0014), and bare in the model relay's
 * {@code X-Tinfirm-Runner-Token}, next to the run's one-run token (the package 2 contract, open point 6). The token is
 * looked up by its SHA-256 hash, never compared as text.
 */
public interface RunnerAuthentication {

    /** The host of the token in the header; empty for a missing, malformed, unknown or revoked token. */
    Optional<ConnectedHost> authenticate(@Nullable String authorizationHeader);

    /** The host of a bare token ({@code tfr_…}); empty for a missing, malformed, unknown or revoked token. */
    Optional<ConnectedHost> authenticateToken(@Nullable String token);

    /**
     * The host placed in the company of the run, for a request about that run (ADR-0092): a company host when the run
     * is its company's, a machine's presence in the run's company. Empty for an unknown run, or a run of a company the
     * host is not in.
     */
    Optional<ConnectedHost> forRun(ConnectedHost host, UUID runId);
}
