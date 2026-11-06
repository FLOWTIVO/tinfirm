package com.flowtivo.tinfirm.system.internal;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.internal.http.ChatEndpoints;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.mcp.McpClient;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthClient;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthDiscovery;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthDiscoveryProperties;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthProfile;
import com.flowtivo.tinfirm.system.internal.slack.SlackApi;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.VaultService;

import tools.jackson.databind.JsonNode;

/**
 * The clients of one system: its outbound client (a personal MCP server's never reaches private addresses), its MCP
 * client, its app's OAuth profile and client, and its "who am I" per kind (the package 1 contract, §3.6: the identity
 * on the system). The app's client secret is revealed here only, for the one request that needs it: a company
 * system's as the company's secret, a personal server's as its owner's own (I3), and is never returned.
 */
@Component
class SystemClients {

    /** The one OAuth callback of accounts on systems, the account module's ({@code GET /api/oauth/callback}). */
    static final String CALLBACK_PATH = "/api/oauth/callback";

    /** Tinfirm's Client ID Metadata Document, the identity module's, its client id where a server takes one. */
    static final String CLIENT_METADATA_PATH = "/oauth/client-metadata.json";

    /** Where a Jira Cloud site names its cloud id, without a credential. */
    static final String TENANT_INFO = "/_edge/tenant_info";

    /** A cloud id after Atlassian's gateway. */
    private static final Pattern CLOUD_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private final OutboundHttp http;

    private final VaultService vault;

    private final Principals principals;

    private final TinfirmProperties tinfirm;

    private final ChatEndpoints chatEndpoints;

    private final OAuthDiscoveryProperties discoveryProperties;

    SystemClients(OutboundHttp http, VaultService vault, Principals principals, TinfirmProperties tinfirm,
            ChatEndpoints chatEndpoints, OAuthDiscoveryProperties discoveryProperties) {
        this.http = http;
        this.vault = vault;
        this.principals = principals;
        this.tinfirm = tinfirm;
        this.chatEndpoints = chatEndpoints;
        this.discoveryProperties = discoveryProperties;
    }

    /** The system's outbound client: a personal server's never reaches private addresses. */
    OutboundHttp http(SystemStore.SystemRow system) {
        return http.of(system.personal());
    }

    /** The outbound client of a system of this ownership that does not exist yet. */
    OutboundHttp http(boolean personal) {
        return http.of(personal);
    }

    /** Slack's Web API at a {@code SLACK} system's base URL, for its user accounts (open point 13). */
    SlackApi slack(SystemStore.SystemRow system) {
        return new SlackApi(http(system), chatEndpoints, Objects.requireNonNull(system.baseUrl()));
    }

    McpClient mcp(SystemStore.SystemRow system) {
        return new McpClient(http(system));
    }

    OAuthDiscovery discovery(boolean personal) {
        return new OAuthDiscovery(http(personal), discoveryProperties.origins());
    }

    OAuthClient oauth(SystemStore.SystemRow system) {
        return new OAuthClient(http(system));
    }

    String redirectUri() {
        return tinfirm.publicBaseUrl() + CALLBACK_PATH;
    }

    String clientMetadataUrl() {
        return tinfirm.publicBaseUrl() + CLIENT_METADATA_PATH;
    }

    // ---- The app ---------------------------------------------------------------------------------------------------

    /**
     * The app's OAuth profile: a tracker's fixed one, Slack's for its app's user scopes, an MCP server's discovered
     * one; empty without an app.
     */
    Optional<OAuthProfile> profile(SystemStore.SystemRow system) {
        if (system.oauthClientId() == null || system.baseUrl() == null) {
            return Optional.empty();
        }
        if (system.kind() == SystemKind.SLACK) {
            return Optional.of(OAuthProfile.slack(system.baseUrl()));
        }
        if (system.kind() != SystemKind.MCP) {
            return system.kind().oauth() ? Optional.of(OAuthProfile.tracker(system.kind(), system.baseUrl()))
                    : Optional.empty();
        }
        JsonNode metadata = system.oauthMetadata();
        String authorization = Json.text(metadata, "authorizationEndpoint");
        String token = Json.text(metadata, "tokenEndpoint");
        if (authorization == null || token == null) {
            return Optional.empty();
        }
        return Optional.of(OAuthProfile.mcp(system.baseUrl(), authorization, token,
                Json.text(metadata, "revocationEndpoint"), Json.text(metadata, "scopesSupported")));
    }

    /**
     * The scopes a sign-in asks for: the app's, else the profile's. Slack's are fixed, its app's user scopes
     * {@code channels:history} and {@code groups:history} (open point 13).
     */
    @Nullable String scopes(SystemStore.SystemRow system, OAuthProfile profile) {
        if (system.kind() == SystemKind.SLACK) {
            return profile.defaultScopes();
        }
        return system.oauthScopes() != null ? system.oauthScopes() : profile.defaultScopes();
    }

    /**
     * The app's client for one request, with its secret revealed (audited as {@code secret.accessed} with the
     * purpose); the caller drops it after the request.
     */
    OAuthClient.Client client(SystemStore.SystemRow system, OAuthProfile profile, String purpose) {
        String clientId = Objects.requireNonNull(system.oauthClientId());
        UUID secretId = system.oauthClientSecretId();
        if (secretId == null) {
            return new OAuthClient.Client(clientId, null, profile.secretAuth());
        }
        try (RevealedSecret secret = reveal(system, secretId, purpose)) {
            return new OAuthClient.Client(clientId, secret.asString(), profile.secretAuth());
        }
    }

    private RevealedSecret reveal(SystemStore.SystemRow system, UUID secretId, String purpose) {
        UUID owner = system.ownerPrincipalId();
        if (owner == null) {
            return vault.reveal(system.companyId(), secretId, purpose);
        }
        PrincipalInfo principal = principals.find(system.companyId(), owner)
            .orElseThrow(() -> new IllegalStateException("A personal system without its owner's principal"));
        return vault.revealOwned(system.companyId(), secretId, Objects.requireNonNull(principal.userId()), purpose);
    }

    // ---- Who am I --------------------------------------------------------------------------------------------------

    /** Who the credential is on the system, or why the system refused it (see {@link SystemCalls#whoAmI}). */
    CallResult<SystemCalls.Identity> whoAmI(SystemStore.SystemRow system, SystemCalls.Credential credential) {
        return whoAmI(system, credential, null);
    }

    /**
     * Who a credential an account revealed is on the system: on Jira Cloud, a bearer token that names Atlassian's
     * gateway for the site (a sign-in's grant, a client's token) is asked at the gateway's {@code myself}, which a
     * client's token of an Atlassian service account answers where Atlassian's {@code /me} may not; anything else as
     * {@link #whoAmI(SystemStore.SystemRow, SystemCalls.Credential)}.
     */
    CallResult<SystemCalls.Identity> whoAmI(SystemStore.SystemRow system, SystemCalls.Credential credential,
            @Nullable String apiBaseUrl) {
        if (system.kind() == SystemKind.JIRA_CLOUD && credential.oauth() && apiBaseUrl != null
                && gatewayCloudId(apiBaseUrl) != null) {
            return gatewayMyself(system, apiBaseUrl, credential.token());
        }
        String base = system.baseUrl();
        if (system.kind() == SystemKind.GIT || base == null) {
            return CallResult.ok(new SystemCalls.Identity(null, null, null));
        }
        return switch (system.kind()) {
            case JIRA_DC -> identity(system, base + "/rest/api/2/myself", "Bearer " + credential.token(), "key", "name");
            case JIRA_CLOUD -> credential.oauth() ? atlassian(system, credential.token())
                    : identity(system, base + "/rest/api/3/myself", "Basic " + Base64.getEncoder()
                        .encodeToString(credential.token().strip().getBytes(StandardCharsets.UTF_8)), "accountId",
                            "displayName");
            case GITHUB -> identity(system, base + "/user", "Bearer " + credential.token(), "id", "login");
            case GITLAB -> gitlab(system, base, credential);
            case MCP -> mcpProbe(system, credential);
            case SLACK -> slackUser(system, credential);
            default -> CallResult.failed(CallResult.Kind.INVALID, system.name() + " (" + system.kind().label()
                    + ") has no identity to read yet");
        };
    }

    private CallResult<SystemCalls.Identity> gitlab(SystemStore.SystemRow system, String base,
            SystemCalls.Credential credential) {
        String api = base.endsWith("/api/v4") ? base : base + "/api/v4";
        HttpRequest.Builder request = http(system).request(api + "/user").header("Accept", "application/json");
        if (credential.oauth()) {
            request.header("Authorization", "Bearer " + credential.token());
        }
        else {
            request.header("PRIVATE-TOKEN", credential.token());
        }
        return read(system, base, request.GET().build(), "id", "username", null);
    }

    private CallResult<SystemCalls.Identity> identity(SystemStore.SystemRow system, String url, String authorization,
            String idField, String loginField) {
        HttpRequest request = http(system).request(url)
            .header("Accept", "application/json")
            .header("Authorization", authorization)
            .GET()
            .build();
        return read(system, Objects.requireNonNull(system.baseUrl()), request, idField, loginField, null);
    }

    private CallResult<SystemCalls.Identity> read(SystemStore.SystemRow system, String origin, HttpRequest request,
            String idField, String loginField, @Nullable String apiBaseUrl) {
        CallResult<OutboundHttp.Response> sent = http(system).send(origin, request);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        if (!response.success()) {
            return CallResult.failed(OutboundHttp.kindOf(response.status()), system.name() + " answered "
                    + response.status() + " when asked whose the token is");
        }
        JsonNode body = Json.parse(response.body());
        String id = Json.textOrNumber(body, idField);
        if (id == null || id.isBlank()) {
            return CallResult.failed(CallResult.Kind.INVALID, system.name() + " named no " + idField
                    + " for the token");
        }
        return CallResult.ok(new SystemCalls.Identity(id, Json.textOrNumber(body, loginField), apiBaseUrl));
    }

    /**
     * An Atlassian access token: the account at Atlassian's {@code /me}, and the system's site among the sites the
     * grant may call, whose gateway URL the account calls Jira at.
     */
    private CallResult<SystemCalls.Identity> atlassian(SystemStore.SystemRow system, String accessToken) {
        HttpRequest me = http(system).request(OAuthProfile.ATLASSIAN_ME)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        CallResult<OutboundHttp.Response> sent = http(system).send(OAuthProfile.ATLASSIAN_ME, me);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        if (!response.success()) {
            return CallResult.failed(OutboundHttp.kindOf(response.status()), "Atlassian answered "
                    + response.status() + " to who am I");
        }
        JsonNode body = Json.parse(response.body());
        String accountId = Json.text(body, "account_id");
        if (accountId == null || accountId.isBlank()) {
            return CallResult.failed(CallResult.Kind.INVALID, "Atlassian named no account_id");
        }
        String login = Json.text(body, "nickname") != null ? Json.text(body, "nickname") : Json.text(body, "name");
        CallResult<List<OAuthClient.Site>> sites = oauth(system).accessibleResources(OAuthProfile.ATLASSIAN_RESOURCES,
                accessToken);
        if (sites.failure() != null) {
            return sites.failedAs();
        }
        String site = normalized(Objects.requireNonNull(system.baseUrl()));
        for (OAuthClient.Site granted : Objects.requireNonNull(sites.value())) {
            if (normalized(granted.url()).equals(site)) {
                return CallResult.ok(new SystemCalls.Identity(accountId, login,
                        OAuthProfile.ATLASSIAN_GATEWAY + granted.cloudId()));
            }
        }
        return CallResult.failed(CallResult.Kind.NOT_FOUND, "The grant does not include the site " + site);
    }

    /**
     * A client's access token on Jira Cloud ({@link SystemCalls#clientIdentity}): the site's cloud id from its public
     * {@code /_edge/tenant_info}, then {@code myself} through Atlassian's gateway for that cloud id.
     */
    CallResult<SystemCalls.Identity> clientIdentity(SystemStore.SystemRow system, String accessToken) {
        String site = system.baseUrl();
        if (system.kind() != SystemKind.JIRA_CLOUD || site == null) {
            return CallResult.failed(CallResult.Kind.INVALID, system.name() + " (" + system.kind().label()
                    + ") takes no client credentials");
        }
        String base = normalized(site);
        HttpRequest tenant = http(system).request(base + TENANT_INFO)
            .header("Accept", "application/json")
            .GET()
            .build();
        CallResult<OutboundHttp.Response> sent = http(system).send(base, tenant);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        if (!response.success()) {
            // The site asks for no credential here: its refusal is no refusal of the client's.
            CallResult.Kind kind = OutboundHttp.kindOf(response.status());
            return CallResult.failed(kind == CallResult.Kind.UNAUTHORIZED ? CallResult.Kind.INVALID : kind,
                    system.name() + " answered " + response.status() + " when asked for its cloud id");
        }
        String cloudId = Json.text(Json.parse(response.body()), "cloudId");
        if (cloudId == null || !CLOUD_ID.matcher(cloudId.strip()).matches()) {
            return CallResult.failed(CallResult.Kind.INVALID, system.name() + " named no cloud id: is it a Jira "
                    + "Cloud site?");
        }
        return gatewayMyself(system, OAuthProfile.ATLASSIAN_GATEWAY + cloudId.strip(), accessToken);
    }

    /** Jira Cloud's {@code myself} through Atlassian's gateway, with a bearer token; the identity names the gateway. */
    private CallResult<SystemCalls.Identity> gatewayMyself(SystemStore.SystemRow system, String gateway,
            String accessToken) {
        HttpRequest request = http(system).request(gateway + "/rest/api/3/myself")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        return read(system, gateway, request, "accountId", "displayName", gateway);
    }

    /** The cloud id of a URL of Atlassian's gateway, {@code https://api.atlassian.com/ex/jira/{cloudId}}; else null. */
    static @Nullable String gatewayCloudId(String url) {
        if (!url.startsWith(OAuthProfile.ATLASSIAN_GATEWAY)) {
            return null;
        }
        String cloudId = url.substring(OAuthProfile.ATLASSIAN_GATEWAY.length());
        return CLOUD_ID.matcher(cloudId).matches() ? cloudId : null;
    }

    /**
     * A Slack user token of the app's user scopes (open point 13): {@code auth.test} names the user and the workspace,
     * which must be the system's, as its bot named it; a bot's token, or any before the bot named the workspace, is
     * refused.
     */
    private CallResult<SystemCalls.Identity> slackUser(SystemStore.SystemRow system,
            SystemCalls.Credential credential) {
        String teamId = Json.text(system.settings(), "teamId");
        if (teamId == null || teamId.isBlank()) {
            return CallResult.failed(CallResult.Kind.INVALID, system.name() + " names no Slack workspace yet: its "
                    + "bot names it when it is set");
        }
        CallResult<SlackApi.TokenOwner> owner = slack(system).owner(credential.token());
        if (owner.failure() != null) {
            return owner.failedAs();
        }
        SlackApi.TokenOwner who = Objects.requireNonNull(owner.value());
        if (who.botId() != null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The token is a bot's, not a Slack user's");
        }
        if (!teamId.equals(who.teamId())) {
            return CallResult.failed(CallResult.Kind.INVALID, "The Slack user is of another workspace than "
                    + system.name() + "'s");
        }
        if (who.userId().isBlank()) {
            return CallResult.failed(CallResult.Kind.INVALID, "Slack named no user for the token");
        }
        return CallResult.ok(new SystemCalls.Identity(who.userId(), who.userName(), null));
    }

    /** An MCP server has no "who am I": an {@code initialize} with the credential tells whether it takes it. */
    private CallResult<SystemCalls.Identity> mcpProbe(SystemStore.SystemRow system, SystemCalls.Credential credential) {
        McpClient.Target target = new McpClient.Target(Objects.requireNonNull(system.baseUrl()),
                credential.oauth() ? null : system.authHeader(), credential.token());
        CallResult<Boolean> probed = mcp(system).probe(target);
        return probed.failure() != null ? probed.failedAs() : CallResult.ok(new SystemCalls.Identity(null, null, null));
    }

    private static String normalized(String url) {
        String value = url.strip().toLowerCase(Locale.ROOT);
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
