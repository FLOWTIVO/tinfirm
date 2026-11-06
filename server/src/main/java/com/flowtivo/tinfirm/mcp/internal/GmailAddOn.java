package com.flowtivo.tinfirm.mcp.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.mcp.internal.GoogleIdTokens.GoogleAccount;
import com.flowtivo.tinfirm.shared.api.model.GmailDeployment;
import com.flowtivo.tinfirm.shared.api.model.GmailLink;
import com.flowtivo.tinfirm.shared.api.model.GmailLinkRequest;

import tools.jackson.databind.JsonNode;

/**
 * Tinfirm's Gmail add-on (ADR-0083), Pro's and Enterprise's (ADR-0077, decision 4, {@code GMAIL_ADDON}): a paid
 * feature's code, under {@code ee/} (decision 1), which checks the licence in force. The core finds it when used
 * ({@link GmailAddOnController}, {@link GmailController}) and never names its implementation: without {@code ee/} the
 * server answers as Community, Google's requests with a card that says so. What every edition does stays in the core:
 * Google's requests verified ({@link GoogleIdTokens}), the access check of the add-on's endpoints, and a person's links
 * listed and ended ({@link GmailLinks}).
 */
interface GmailAddOn {

    /** The event of a request, as Google sends it: the open message, its tokens, the button's parameters and inputs. */
    record Event(@Nullable String messageId, @Nullable String threadId, @Nullable String messageToken,
            @Nullable String userOAuthToken, @Nullable String userIdToken, @Nullable String systemIdToken,
            Map<String, String> parameters, Map<String, String> inputs) {

        static Event of(JsonNode body) {
            JsonNode gmail = body.path("gmail");
            JsonNode authorization = body.path("authorizationEventObject");
            JsonNode common = body.path("commonEventObject");
            Map<String, String> parameters = new LinkedHashMap<>();
            common.path("parameters").properties().forEach(entry -> {
                if (entry.getValue().isString()) {
                    parameters.put(entry.getKey(), entry.getValue().stringValue());
                }
            });
            Map<String, String> inputs = new LinkedHashMap<>();
            common.path("formInputs").properties().forEach(entry -> {
                JsonNode values = entry.getValue().path("stringInputs").path("value");
                if (values.isArray() && !values.isEmpty() && values.get(0).isString()) {
                    inputs.put(entry.getKey(), values.get(0).stringValue());
                }
            });
            return new Event(text(gmail, "messageId"), text(gmail, "threadId"), text(gmail, "accessToken"),
                    text(authorization, "userOAuthToken"), text(authorization, "userIdToken"),
                    text(authorization, "systemIdToken"), parameters, inputs);
        }

        private static @Nullable String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value != null && value.isString() ? value.stringValue() : null;
        }
    }

    /**
     * The answer to a verified request of the trigger ({@code home}, {@code open} or {@code act}) for the account of an
     * existing company: a pushed card for a trigger, the card in place for a button; {@link #unlicensed} unless the
     * licence holds the add-on.
     */
    Map<String, Object> handle(UUID companyId, String companyName, String trigger, Event event,
            GoogleAccount account);

    /** The company's deployment file, for any member: {@code 403 EDITION_REQUIRED} after the access check, unless licensed. */
    GmailDeployment deployment(UUID companyId);

    /** What a sign-in link links, for an active member: {@code 403 EDITION_REQUIRED} after it, unless licensed. */
    GmailLinkRequest readLink(UUID companyId, String token);

    /** Links the account of a sign-in link to the caller, as {@link #readLink}. */
    GmailLink confirmLink(UUID companyId, String token);

    /**
     * The answer to Google when the add-on is not in force (ADR-0077, decision 4): a card that says so, pushed for a
     * trigger and in place for a button.
     */
    static Map<String, Object> unlicensed(String trigger) {
        Map<String, Object> card = Map.of("header", Map.of("title", "Tinfirm"), "sections", List.of(Map.of("widgets",
                List.of(Map.of("textParagraph", Map.of("text", "The Gmail add-on needs Tinfirm's Pro edition. An "
                        + "instance admin adds a licence key under Licence in Tinfirm."))))));
        return Map.of("action", Map.of("navigations", List.of(Map.of("act".equals(trigger) ? "updateCard" : "pushCard",
                card))));
    }
}
