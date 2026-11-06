package com.flowtivo.tinfirm.system.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ClientCredentials;
import com.flowtivo.tinfirm.system.OAuthTokens;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthClient;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthProfile;

/**
 * {@link SystemCalls} over the system's clients, in the caller's tenant context. A call to the system runs outside any
 * transaction: the system's row, and for an OAuth request the app's client secret, are read first in a short
 * transaction of their own (the secret revealed for that request only, audited as {@code secret.accessed} with the
 * purpose {@code system-app:{systemId}}), then the request is sent. The request goes where the caller's
 * {@link SystemInfo} snapshot has the system: a row whose kind or base URL changed since (an MCP server moved to another
 * endpoint, ADR-0079) is as gone, so that a credential prepared for one endpoint is never sent to another.
 */
@Component
class SystemCallsImpl implements SystemCalls {

    private final SystemStore store;

    private final SystemClients clients;

    private final TransactionTemplate transactions;

    private final TimeSource time;

    SystemCallsImpl(SystemStore store, SystemClients clients, PlatformTransactionManager transactionManager,
            TimeSource time) {
        this.store = store;
        this.clients = clients;
        this.transactions = new TransactionTemplate(transactionManager);
        this.time = time;
    }

    @Override
    public CallResult<Identity> whoAmI(SystemInfo system, Credential credential) {
        NoTransaction.require("Who am I");
        CallResult<SystemStore.SystemRow> row = current(system);
        return row.failure() != null ? CallResult.failed(row.failure())
                : clients.whoAmI(Objects.requireNonNull(row.value()), credential);
    }

    @Override
    public CallResult<OAuthTokens> clientToken(SystemInfo system, ClientCredentials client) {
        NoTransaction.require("A client credentials grant");
        CallResult<SystemStore.SystemRow> found = current(system);
        if (found.failure() != null) {
            return CallResult.failed(found.failure());
        }
        SystemStore.SystemRow row = Objects.requireNonNull(found.value());
        if (!row.kind().clientCredentials() || row.baseUrl() == null) {
            return CallResult.failed(CallResult.Kind.INVALID, row.name() + " (" + row.kind().label()
                    + ") takes no client credentials");
        }
        // Not the system's app: the client is the credential's own, an Atlassian service account's.
        OAuthProfile profile = OAuthProfile.tracker(row.kind(), Objects.requireNonNull(row.baseUrl()));
        return clients.oauth(row).clientCredentials(profile, new OAuthClient.Client(client.clientId(),
                client.clientSecret(), profile.secretAuth()), time.now());
    }

    @Override
    public CallResult<Identity> clientIdentity(SystemInfo system, String accessToken) {
        NoTransaction.require("Who a client is");
        CallResult<SystemStore.SystemRow> row = current(system);
        return row.failure() != null ? CallResult.failed(row.failure())
                : clients.clientIdentity(Objects.requireNonNull(row.value()), accessToken);
    }

    @Override
    public boolean oauthAvailable(SystemInfo system) {
        return read(system, clients::profile).isPresent();
    }

    @Override
    public Optional<String> authorizationUrl(SystemInfo system, String state, String codeChallenge) {
        return transactions.execute(status -> store.find(system.companyId(), system.id())).flatMap(row -> clients
            .profile(row)
            .map(profile -> {
                Map<String, String> parameters = new LinkedHashMap<>();
                parameters.put("response_type", "code");
                parameters.put("client_id", Objects.requireNonNull(row.oauthClientId()));
                parameters.put("redirect_uri", clients.redirectUri());
                String scopes = clients.scopes(row, profile);
                if (scopes != null && !scopes.isBlank()) {
                    // Slack's v2 names a user token's scopes user_scope; its scope is the bot's, never asked here.
                    parameters.put(profile.userScopes() ? "user_scope" : "scope", scopes);
                }
                parameters.put("state", state);
                parameters.put("code_challenge", codeChallenge);
                parameters.put("code_challenge_method", "S256");
                if (profile.resource() != null) {
                    parameters.put("resource", profile.resource());
                }
                parameters.putAll(profile.authorizeParameters());
                String query = parameters.entrySet()
                    .stream()
                    .map(entry -> Json.encode(entry.getKey()) + "=" + Json.encode(entry.getValue()))
                    .collect(Collectors.joining("&"));
                String endpoint = profile.authorizationEndpoint();
                return endpoint + (endpoint.contains("?") ? "&" : "?") + query;
            }));
    }

    @Override
    public CallResult<OAuthTokens> exchange(SystemInfo system, String code, String codeVerifier) {
        NoTransaction.require("A code exchange");
        App app = app(system);
        if (app.gone() != null) {
            return CallResult.failed(app.gone());
        }
        if (app.profile() == null || app.client() == null) {
            return noApp();
        }
        return clients.oauth(app.row()).exchange(app.profile(), app.client(), code, clients.redirectUri(),
                codeVerifier, time.now());
    }

    @Override
    public CallResult<OAuthTokens> refresh(SystemInfo system, String refreshToken) {
        NoTransaction.require("A token refresh");
        App app = app(system);
        if (app.gone() != null) {
            return CallResult.failed(app.gone());
        }
        if (app.profile() == null || app.client() == null) {
            return noApp();
        }
        return clients.oauth(app.row()).refresh(app.profile(), app.client(), refreshToken, time.now());
    }

    @Override
    public CallResult<Boolean> revoke(SystemInfo system, OAuthTokens tokens) {
        NoTransaction.require("A revocation");
        App app = app(system);
        if (app.row() == null || app.profile() == null || app.client() == null
                || app.profile().revocationEndpoint() == null) {
            return CallResult.ok(false);
        }
        boolean refresh = tokens.refreshToken() != null;
        return clients.oauth(app.row()).revoke(Objects.requireNonNull(app.profile().revocationEndpoint()),
                app.client(), refresh ? Objects.requireNonNull(tokens.refreshToken()) : tokens.accessToken(),
                refresh ? "refresh_token" : "access_token");
    }

    /**
     * The system's app for one request, read in a short transaction of its own: the row, its profile, and the client
     * with its secret revealed. A failure ({@code gone}, with a null row): the system is gone, or its endpoint changed
     * since the caller's snapshot; a null profile or client: it has no app.
     */
    private record App(SystemStore.@Nullable SystemRow row, @Nullable OAuthProfile profile,
            OAuthClient.@Nullable Client client, CallResult.@Nullable Failure gone) {

        @Override
        public String toString() {
            return "App[system=" + (row != null ? row.id() : null) + "]";
        }
    }

    private App app(SystemInfo system) {
        return Objects.requireNonNull(transactions.execute(status -> {
            CallResult<SystemStore.SystemRow> current = current(system, store.find(system.companyId(), system.id()));
            if (current.failure() != null) {
                return new App(null, null, null, current.failure());
            }
            SystemStore.SystemRow row = Objects.requireNonNull(current.value());
            OAuthProfile profile = clients.profile(row).orElse(null);
            return profile == null ? new App(row, null, null, null)
                    : new App(row, profile, clients.client(row, profile, "system-app:" + row.id()), null);
        }));
    }

    /**
     * The system's row as the caller's snapshot has it, read in a short transaction of its own; failed as gone when
     * the system is gone, or when its kind or base URL changed since the snapshot (ADR-0079, {@link EndpointPin}):
     * what the caller prepared for the snapshot's endpoint is not sent to another.
     */
    private CallResult<SystemStore.SystemRow> current(SystemInfo system) {
        return Objects.requireNonNull(transactions.execute(status -> current(system, store.find(system.companyId(),
                system.id()))));
    }

    private static CallResult<SystemStore.SystemRow> current(SystemInfo system,
            Optional<SystemStore.SystemRow> found) {
        if (found.isEmpty()) {
            return gone();
        }
        SystemStore.SystemRow row = found.get();
        CallResult.Failure moved = EndpointPin.moved(row, system.kind(), system.baseUrl());
        return moved != null ? CallResult.failed(moved) : CallResult.ok(row);
    }

    /** Something of the system's row as the caller's snapshot has it ({@link #current}); empty otherwise. */
    private <T> Optional<T> read(SystemInfo system, Function<SystemStore.SystemRow, Optional<T>> what) {
        CallResult<SystemStore.SystemRow> row = current(system);
        return row.failure() != null ? Optional.empty() : what.apply(Objects.requireNonNull(row.value()));
    }

    private static <T> CallResult<T> noApp() {
        return CallResult.failed(CallResult.Kind.INVALID, "The system has no app");
    }

    private static <T> CallResult<T> gone() {
        return CallResult.failed(CallResult.Kind.INVALID, "The system is gone");
    }
}
