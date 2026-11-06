package com.flowtivo.tinfirm.system.internal.http;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The chat platforms' and Google's endpoints ({@link ChatEndpoints}, {@link GmailEndpoints}) are fixed: the server
 * refuses to start with any of them changed outside the profiles of the tests, {@code test} (the server's) and
 * {@code e2e} (the web app's end-to-end harness, which runs the real jar against its Slack stub). Changed, Google's keys
 * or the Bot Framework's OpenID metadata would make Tinfirm trust tokens another party signs, and an API's root would
 * receive people's and bots' tokens. Spring's relaxed binding takes a change from a variable too
 * ({@code TINFIRM_GMAIL_ENDPOINTS_GOOGLEKEYS}), so the bound values are compared with the defaults, whatever set them.
 */
public final class FixedEndpoints {

    /** The profiles under which the tests point the endpoints at their stubs. */
    static final String TEST_PROFILES = "test | e2e";

    private FixedEndpoints() {
    }

    /**
     * Refuses the endpoints changed outside the tests' profiles.
     *
     * @throws IllegalStateException naming the settings that differ from their defaults
     */
    public static void refuseChangesOutsideTests(Environment environment, ChatEndpoints chat, GmailEndpoints gmail) {
        if (environment.matchesProfiles(TEST_PROFILES)) {
            return;
        }
        // Bound from no source, each record is its defaults.
        Binder none = new Binder();
        ChatEndpoints fixedChat = none.bindOrCreate("tinfirm.chat.endpoints", ChatEndpoints.class);
        GmailEndpoints fixedGmail = none.bindOrCreate("tinfirm.gmail.endpoints", GmailEndpoints.class);
        List<String> changed = new ArrayList<>();
        changed(changed, "tinfirm.chat.endpoints.slack-api", chat.slackApi(), fixedChat.slackApi());
        changed(changed, "tinfirm.chat.endpoints.slack-files", chat.slackFiles(), fixedChat.slackFiles());
        changed(changed, "tinfirm.chat.endpoints.slack-avatars", chat.slackAvatars(), fixedChat.slackAvatars());
        changed(changed, "tinfirm.chat.endpoints.slack-socket-hosts", chat.slackSocketHosts(),
                fixedChat.slackSocketHosts());
        changed(changed, "tinfirm.chat.endpoints.teams-service", chat.teamsService(), fixedChat.teamsService());
        changed(changed, "tinfirm.chat.endpoints.teams-login", chat.teamsLogin(), fixedChat.teamsLogin());
        changed(changed, "tinfirm.chat.endpoints.teams-open-id", chat.teamsOpenId(), fixedChat.teamsOpenId());
        changed(changed, "tinfirm.chat.endpoints.teams-scope", chat.teamsScope(), fixedChat.teamsScope());
        changed(changed, "tinfirm.gmail.endpoints.google-keys", gmail.googleKeys(), fixedGmail.googleKeys());
        changed(changed, "tinfirm.gmail.endpoints.gmail-api", gmail.gmailApi(), fixedGmail.gmailApi());
        if (!changed.isEmpty()) {
            throw new IllegalStateException(String.join(", ", changed) + " (or a variable such as "
                    + "TINFIRM_GMAIL_ENDPOINTS_GOOGLEKEYS) is set, but the chat platforms' and Google's endpoints are "
                    + "fixed: only the tests point them at their stubs. Changed, Tinfirm would trust tokens another "
                    + "party signs, or send people's tokens to another host: remove it.");
        }
    }

    private static void changed(List<String> changed, String name, Object value, Object fixed) {
        if (!value.equals(fixed)) {
            changed.add(name);
        }
    }
}
