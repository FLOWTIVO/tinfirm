package com.flowtivo.tinfirm.system.internal.http;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The fixed endpoints of the chat platforms (the package 5 contract, §2.2 and §3.1): the outbound profile of each chat
 * kind is these and nothing else. A {@code SLACK} system calls Slack's Web API, its file host and the Socket Mode
 * WebSocket hosts that {@code apps.connections.open} names; a {@code TEAMS} system calls Microsoft's login, the Bot
 * Framework's OpenID metadata and keys, and the connector at its base URL, to which an activity's {@code serviceUrl}
 * must belong. Teams' SharePoint links are not followed (open point 12).
 * <p>
 * Not configured in production: the properties exist so that the tests reach the stubs of Slack and the Bot
 * Framework on {@code localhost} ({@code tinfirm.chat.endpoints.*}, no variables), and the server refuses to start with
 * one changed outside the {@code test} and {@code e2e} profiles ({@link FixedEndpoints}).
 *
 * @param slackApi          Slack's Web API root, a {@code SLACK} system's fixed base URL
 * @param slackFiles        where Slack serves and takes files ({@code url_private_download}, the upload URL)
 * @param slackAvatars      where Slack serves people's profile pictures ({@code profile.image_72}), which the sign-in
 *                          link's page shows (the security review of 2026-10-27, L3); a picture elsewhere is not read
 * @param slackSocketHosts  the hosts, or suffixes starting with a dot, a Socket Mode URL may name
 * @param teamsService      the Bot Framework connector's origin, a {@code TEAMS} system's fixed base URL
 * @param teamsLogin        Microsoft's login, where the bot's {@code client_credentials} token is asked for
 * @param teamsOpenId       the Bot Framework's OpenID metadata, which names its signing keys
 * @param teamsScope        the scope of the bot's token
 */
@ConfigurationProperties("tinfirm.chat.endpoints")
public record ChatEndpoints(@DefaultValue("https://slack.com/api") String slackApi,
        @DefaultValue("https://files.slack.com") String slackFiles,
        @DefaultValue("https://avatars.slack-edge.com") String slackAvatars,
        @DefaultValue(".slack.com") List<String> slackSocketHosts,
        @DefaultValue("https://smba.trafficmanager.net") String teamsService,
        @DefaultValue("https://login.microsoftonline.com") String teamsLogin,
        @DefaultValue("https://login.botframework.com/v1/.well-known/openidconfiguration") String teamsOpenId,
        @DefaultValue("https://api.botframework.com/.default") String teamsScope) {

    /** Whether a Socket Mode URL's host is one Slack names: a configured host, or under a configured suffix. */
    public boolean socketHost(@Nullable String host) {
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        for (String allowed : slackSocketHosts) {
            String rule = allowed.toLowerCase(Locale.ROOT).strip();
            if (rule.startsWith(".") ? name.endsWith(rule) && name.length() > rule.length() : name.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    /** The origin of a URL ({@code scheme://host[:port]}), or null when it has none. */
    public static @Nullable String origin(@Nullable String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url.strip());
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            int standard = "http".equals(scheme) ? 80 : 443;
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (port == -1 || port == standard ? "" : ":" + port);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
