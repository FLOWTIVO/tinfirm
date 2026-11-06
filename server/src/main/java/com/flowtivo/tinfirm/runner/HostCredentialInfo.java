package com.flowtivo.tinfirm.runner;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A credential a personal runner host reports by label (the package 2 contract, §2, M5): its owner's subscription or
 * provider key on that host, never its value. A company host holds none.
 *
 * @param state    as the host last reported it; whether the host is connected is {@link RunnerConnections}'s
 * @param reported false when the host's latest {@code hello} no longer listed it: the credential is gone
 */
public record HostCredentialInfo(UUID runnerHostId, String label, CredentialKind kind, Set<RuntimeKind> runtimes,
        int poolSize, CredentialState state, @Nullable Instant cooldownUntil, boolean reported) {

    public HostCredentialInfo {
        runtimes = Set.copyOf(runtimes);
    }
}
