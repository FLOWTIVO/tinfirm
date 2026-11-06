package com.flowtivo.tinfirm.chat.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ChatManifest;
import com.flowtivo.tinfirm.shared.api.model.SystemKind;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.system.ChatSystemInfo;

/**
 * The app an admin installs (the package 5 contract, §5.1): Slack's app manifest, or Teams' app package.
 * <ul>
 * <li>Slack: the bot scopes of §5.1 and no more (no {@code channels:history}, {@code groups:history} or
 * {@code mpim:*} as the bot's, C7); the events of §5.1, the private channels' ({@code group_*}) and
 * {@code channel_unshared} among them, and no {@code message.channels}; interactivity; the App Home's
 * messages tab; the user scopes {@code channels:history} and {@code groups:history} with Tinfirm's OAuth callback
 * (open point 13). Socket Mode's manifest names no request URL.</li>
 * <li>Teams: the bot in the {@code personal} and {@code team} scopes, without resource-specific consent and without
 * files (open point 12).</li>
 * </ul>
 */
@Component
class ChatManifests {

    static final List<String> SLACK_BOT_SCOPES = List.of("app_mentions:read", "chat:write", "chat:write.customize",
            "im:history", "im:write", "users:read", "channels:read", "groups:read", "files:read", "files:write",
            "reactions:write");

    static final List<String> SLACK_USER_SCOPES = List.of("channels:history", "groups:history");

    static final List<String> SLACK_EVENTS = List.of("app_mention", "message.im", "member_joined_channel",
            "member_left_channel", "channel_archive", "channel_unarchive", "channel_rename", "channel_deleted",
            "channel_left", "group_archive", "group_unarchive", "group_rename", "group_deleted", "group_left",
            "channel_shared", "channel_unshared", "user_change", "app_uninstalled", "tokens_revoked");

    private final ChatAdmin admin;

    private final CompanyAccessGuard guard;

    private final TinfirmProperties tinfirm;

    ChatManifests(ChatAdmin admin, CompanyAccessGuard guard, TinfirmProperties tinfirm) {
        this.admin = admin;
        this.guard = guard;
        this.tinfirm = tinfirm;
    }

    ChatManifest manifest(UUID companyId, UUID systemId) {
        ChatSystemInfo system = admin.inTransaction(() -> {
            guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
            return admin.chatSystem(companyId, systemId);
        });
        if (system.slack()) {
            return new ChatManifest(SystemKind.SLACK, "tinfirm-slack-manifest.json").manifest(slack(system));
        }
        return new ChatManifest(SystemKind.TEAMS, "tinfirm-teams.zip").appPackage(Base64.getEncoder()
            .encodeToString(teams(system)));
    }

    Map<String, Object> slack(ChatSystemInfo system) {
        String base = base();
        boolean socket = system.socketMode();
        Map<String, Object> eventSubscriptions = new LinkedHashMap<>();
        if (!socket) {
            eventSubscriptions.put("request_url", base + "/api/chat/slack/" + system.id() + "/events");
        }
        eventSubscriptions.put("bot_events", SLACK_EVENTS);
        Map<String, Object> interactivity = new LinkedHashMap<>();
        interactivity.put("is_enabled", true);
        if (!socket) {
            interactivity.put("request_url", base + "/api/chat/slack/" + system.id() + "/interactions");
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("event_subscriptions", eventSubscriptions);
        settings.put("interactivity", interactivity);
        settings.put("org_deploy_enabled", false);
        settings.put("socket_mode_enabled", socket);
        settings.put("token_rotation_enabled", false);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("display_information", Map.of("name", "Tinfirm", "description",
                "Tinfirm's agents, in your direct messages and the channels you mention them in"));
        manifest.put("features", Map.of(
                "app_home", Map.of("home_tab_enabled", false, "messages_tab_enabled", true,
                        "messages_tab_read_only_enabled", false),
                "bot_user", Map.of("display_name", "Tinfirm", "always_online", true)));
        manifest.put("oauth_config", Map.of(
                "redirect_urls", List.of(base + "/api/oauth/callback"),
                "scopes", Map.of("bot", SLACK_BOT_SCOPES, "user", SLACK_USER_SCOPES)));
        manifest.put("settings", settings);
        return manifest;
    }

    byte[] teams(ChatSystemInfo system) {
        String base = base();
        String appId = String.valueOf(system.appId());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("$schema", "https://developer.microsoft.com/en-us/json-schemas/teams/v1.17/"
                + "MicrosoftTeams.schema.json");
        manifest.put("manifestVersion", "1.17");
        manifest.put("version", "1.0.0");
        manifest.put("id", appId);
        manifest.put("developer", Map.of("name", "Tinfirm", "websiteUrl", base, "privacyUrl", base,
                "termsOfUseUrl", base));
        manifest.put("name", Map.of("short", "Tinfirm", "full", "Tinfirm agents"));
        manifest.put("description", Map.of("short", "Tinfirm's agents in Teams",
                "full", "Work with Tinfirm's agents in a personal chat, and mention them in your team's channels."));
        manifest.put("icons", Map.of("color", "color.png", "outline", "outline.png"));
        manifest.put("accentColor", "#2F5BEA");
        manifest.put("bots", List.of(Map.of("botId", appId, "scopes", List.of("personal", "team"),
                "supportsFiles", false, "isNotificationOnly", false)));
        manifest.put("permissions", List.of("identity", "messageTeamMembers"));
        manifest.put("validDomains", List.of(URI.create(base).getHost()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(ChatStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("color.png"));
            zip.write(icon(192, new Color(0x2F, 0x5B, 0xEA), Color.WHITE));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("outline.png"));
            zip.write(icon(32, new Color(0, 0, 0, 0), Color.WHITE));
            zip.closeEntry();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }

    /** A square icon: a background, and a "T" in the foreground. */
    private static byte[] icon(int size, Color background, Color foreground) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(background);
            graphics.fillRect(0, 0, size, size);
            graphics.setColor(foreground);
            int bar = Math.max(2, size / 8);
            graphics.fillRect(size / 4, size / 4, size / 2, bar);
            graphics.fillRect(size / 2 - bar / 2, size / 4, bar, size / 2);
        }
        finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String base() {
        String url = tinfirm.publicUrl().toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
