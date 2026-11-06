package com.flowtivo.tinfirm.identity.internal;

import java.util.List;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * The pre-registered public clients of Tinfirm's authorization server (ADR-0045), for clients that cannot present a
 * Client ID Metadata Document. They are written to {@code oauth2_registered_client} at startup; a client passes its
 * id with {@code --client-id}.
 * <ul>
 * <li>The MCP Inspector, so that developers can try the endpoint: its callback on its default port 6274, on loopback
 * only (a loopback redirect URI matches with any port).</li>
 * <li>Tinfirm's own Outlook add-in (ADR-0074): its callback is the web app's route {@code /outlook/callback} at
 * {@code TINFIRM_PUBLIC_URL}, matched exactly. A metadata document would not do: Tinfirm would fetch its own URL
 * through the outbound guard, which refuses the loopback and private addresses of development and of installations
 * on a private network.</li>
 * </ul>
 */
final class BuiltInOAuthClients {

    /** The MCP Inspector's client id; its own OAuth settings take it as "Client ID". */
    static final String MCP_INSPECTOR = "mcp-inspector";

    /** The Outlook add-in's client id, which its task pane sends (ADR-0074). */
    static final String OUTLOOK = "tinfirm-outlook";

    /** The Outlook add-in's callback, below the public URL: a route of the web app (CONVENTIONS §11). */
    static final String OUTLOOK_CALLBACK = "/outlook/callback";

    private BuiltInOAuthClients() {
    }

    /** The built-in clients of an instance whose public URL, without a trailing slash, is {@code publicBaseUrl}. */
    static List<RegisteredClient> all(String publicBaseUrl) {
        return List.of(
                OAuthClientDefaults
                    .publicClient("builtin-" + MCP_INSPECTOR, MCP_INSPECTOR, "MCP Inspector",
                            List.of("http://localhost:6274/oauth/callback", "http://localhost:6274/oauth/callback/debug"))
                    .build(),
                OAuthClientDefaults
                    .publicClient("builtin-" + OUTLOOK, OUTLOOK, "Tinfirm for Outlook",
                            List.of(publicBaseUrl + OUTLOOK_CALLBACK))
                    .build());
    }
}
