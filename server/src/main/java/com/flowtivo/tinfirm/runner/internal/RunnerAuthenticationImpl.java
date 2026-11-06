package com.flowtivo.tinfirm.runner.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerAuthentication;

@Component
class RunnerAuthenticationImpl implements RunnerAuthentication {

    private final RunnerHostSessions sessions;

    RunnerAuthenticationImpl(RunnerHostSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public Optional<ConnectedHost> authenticate(@Nullable String authorizationHeader) {
        return RunnerTokens.fromAuthorization(authorizationHeader).flatMap(sessions::authenticate);
    }

    @Override
    public Optional<ConnectedHost> authenticateToken(@Nullable String token) {
        return RunnerTokens.fromValue(token).flatMap(sessions::authenticate);
    }

    @Override
    public Optional<ConnectedHost> forRun(ConnectedHost host, UUID runId) {
        return sessions.placeForRun(host, runId);
    }
}
