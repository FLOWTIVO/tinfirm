package com.flowtivo.tinfirm.mcp.internal;

import static com.flowtivo.tinfirm.mcp.internal.Renderings.bool;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.items;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.joined;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.nextPage;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.nonNull;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.oneLine;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.optional;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.plural;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.quoted;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.s;
import static com.flowtivo.tinfirm.mcp.internal.Renderings.strings;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * The text renderings of how the company's automation is set up and whether it fires (triggers, their settings and
 * firings; systems, their catalogues and tests; a webhook system's deliveries and the try of its mapping; spend), for
 * the client's model, from the same JSON as the result's {@code structuredContent}, as {@link Renderings} does for
 * conversations. Tinfirm's own fields (ids, kinds, states, outcomes, times, counts) are plain lines; every field people
 * or other systems wrote (names, a trigger's instruction, scope, events' values and conditions, a system's address and
 * its errors, an MCP server's tool descriptions, a webhook's bodies and what its mapping read, subjects' keys and
 * titles, accounts' logins) stands in a labelled block ({@link ResultText}). No DTO rendered here holds a secret, and
 * none is rendered: a webhook's secret, a token or an app's client secret is said to be set, never shown.
 */
final class SetupRenderings {

    /** The characters of a delivery's body a text shows; the whole body is in {@code structuredContent}. */
    static final int BODY = 2_000;

    private SetupRenderings() {
    }

    // ---- triggers --------------------------------------------------------------------------------------------------

    static String triggers(JsonNode page) {
        ResultText text = new ResultText();
        List<JsonNode> items = items(page.get("items"));
        text.line(items.isEmpty() ? "No triggers match: the person sees the triggers whose author or team they are, "
                + "and the company's owners and admins every one."
                : plural(items.size(), "trigger") + ", by name. Read one with get_trigger and its id, its firings "
                        + "with list_trigger_firings.");
        for (JsonNode trigger : items) {
            text.line("- Trigger " + s(trigger, "id") + ": " + s(trigger, "source") + ", " + state(trigger)
                    + optional(", system ", s(trigger, "systemId")) + ", agent " + s(trigger, "agentId")
                    + lastFiring(trigger.get("lastFiring")));
        }
        if (!items.isEmpty()) {
            text.open("the listed triggers' names and sentences, and the names of their systems, agents and people");
            for (JsonNode trigger : items) {
                text.line("- " + s(trigger, "id") + ": " + quoted(s(trigger, "name")) + " — "
                        + oneLine(s(trigger, "sentence"), 300) + optional("; system ", s(trigger, "systemName"))
                        + optional("; agent ", s(trigger, "agentName")) + optional("; author ", s(trigger,
                                "authorName")) + optional("; team ", s(trigger, "teamName")) + optional("; paid by ",
                                        s(trigger, "payerName")));
            }
            text.close();
        }
        nextPage(text, page);
        return text.build();
    }

    /** A trigger in full, and its settings of its agent ({@code settings} null when they could not be read). */
    static String trigger(JsonNode trigger, @Nullable JsonNode settings) {
        ResultText text = new ResultText();
        text.line("Trigger " + s(trigger, "id"));
        text.field("Source", s(trigger, "source"))
            .field("Status", state(trigger) + optional(" since ", s(trigger, "pausedAt"))
                    + optional(", waiting since ", s(trigger, "waitingSince")))
            .field("System id", s(trigger, "systemId"))
            .field("Agent id", s(trigger, "agentId") + ", version " + s(trigger, "acceptedVersion") + " accepted of "
                    + s(trigger, "currentVersion") + (bool(trigger, "wideningPending")
                            ? " (a wider version waits for the author's acceptance)" : ""))
            .field("Subject", s(trigger, "subject"))
            .field("Repeat", s(trigger, "repeat"))
            .field("Max firings per hour", s(trigger, "maxFiringsPerHour"))
            .field("Skipped for the rate", s(trigger, "rateSkippedCount"))
            .field("Missed", s(trigger, "missedCount"))
            .field("Armed at (a change before it fires nothing)", s(trigger, "armedAt"))
            .field("Open conversations", s(trigger, "openConversations"))
            .field("Author id", s(trigger, "authorPrincipalId"))
            .field("Team id", s(trigger, "teamGroupId"));
        JsonNode results = trigger.get("results");
        if (results != null && results.isObject()) {
            text.field("Results", "comments on the subject " + yesNo(results, "comment") + ", reviewed by the team "
                    + "first " + yesNo(results, "review") + optional(", on success ", actions(results.get(
                            "onSuccess"))) + optional(", on failure ", actions(results.get("onFailure"))));
        }
        JsonNode payer = trigger.get("payer");
        if (payer != null && payer.isObject()) {
            text.field("Paid by", s(payer, "principalId") + " (" + s(payer, "kind") + "), " + s(payer, "modelAccess")
                    + ", " + s(payer, "state") + optional(": ", s(payer, "reason")) + optional(" until ", s(payer,
                            "until")));
        }
        if (settings != null && settings.isObject()) {
            JsonNode subscription = settings.get("subscription");
            text.field("Model access", s(settings, "modelAccess") + optional(", runtime ", s(settings, "runtime"))
                    + ", model " + (s(settings, "model") != null ? s(settings, "model") : "the runtime's default")
                    + optional(", effort ", s(settings, "effort")) + (subscription != null && subscription.isObject()
                            ? optional(", subscription on the runner host ", s(subscription, "runnerHostId")) : ""))
                .field("Memory", bool(settings, "memory") ? "on" : "off")
                .field("Automatic retries", bool(settings, "autoRetry") ? "on" : "off")
                .field("The agent's systems switched off for the trigger", joined(settings.get("systemsOff")));
        }
        else {
            text.line("Settings: could not be read (the agent may be gone).");
        }
        JsonNode schedule = trigger.get("schedule");
        if (schedule != null && schedule.isObject()) {
            text.field("Schedule", "its cron (below) in " + s(schedule, "zone") + ", overlap "
                    + s(schedule, "overlap") + ", catch up " + s(schedule, "catchUp"))
                .field("Next firings", joined(schedule.get("nextFirings")))
                .field("Last run", s(schedule, "lastRunAt") + optional(": ", s(schedule, "lastOutcome")));
        }
        JsonNode last = trigger.get("lastFiring");
        if (last != null && last.isObject()) {
            text.field("Last firing", s(last, "createdAt") + ": " + s(last, "event") + " → " + s(last, "outcome")
                    + optional(", conversation ", s(last, "conversationId")));
        }
        JsonNode rights = trigger.get("rights");
        if (rights != null && rights.isObject()) {
            List<String> may = new ArrayList<>();
            for (String right : List.of("edits", "runs", "pauses", "resumes", "deletes", "reassigns")) {
                if (bool(rights, right)) {
                    may.add(right);
                }
            }
            text.field("The person, in Tinfirm", may.isEmpty() ? "reads it only" : String.join(", ", may) + " it");
        }
        text.line("Triggers are changed, paused and run in Tinfirm only, never through you.");

        text.blank().open("the trigger's name, instruction, events and conditions, as its author wrote them, and the "
                + "names of its system, agent and people");
        text.line("Name: " + nonNull(s(trigger, "name")));
        text.line("Sentence: " + nonNull(s(trigger, "sentence")));
        text.line("System: " + nonNull(s(trigger, "systemName")));
        text.line("Agent: " + nonNull(s(trigger, "agentName")));
        text.line("Author: " + nonNull(s(trigger, "authorName")));
        if (s(trigger, "teamName") != null) {
            text.line("Team: " + s(trigger, "teamName"));
        }
        if (payer != null && payer.isObject() && s(payer, "name") != null) {
            text.line("Payer: " + s(payer, "name"));
        }
        if (s(trigger, "scope") != null) {
            text.line("Scope: " + s(trigger, "scope"));
        }
        List<String> events = new ArrayList<>();
        for (JsonNode event : items(trigger.get("events"))) {
            events.add(s(event, "event") + optional(" ", s(event, "value")));
        }
        text.line("Events (any): " + (events.isEmpty() ? "every event of the source" : String.join(" | ", events)));
        List<JsonNode> groups = items(trigger.get("conditions"));
        if (!groups.isEmpty()) {
            text.line("Conditions (it fires when any group holds; a group holds when all its clauses do):");
            int number = 1;
            for (JsonNode group : groups) {
                text.line("  " + number++ + ". " + group(group));
            }
        }
        List<JsonNode> webhook = items(trigger.get("webhookConditions"));
        if (!webhook.isEmpty()) {
            text.line("Conditions on the delivery's body (all hold):");
            int number = 1;
            for (JsonNode condition : webhook) {
                text.line("  " + number++ + ". " + condition(condition));
            }
        }
        List<JsonNode> channels = items(trigger.get("channels"));
        if (!channels.isEmpty()) {
            text.line("Channels: " + String.join(" | ", channels.stream()
                .map(channel -> s(channel, "channelId") + optional(" ", s(channel, "name")) + " " + s(channel,
                        "state") + (bool(channel, "default") ? " default" : ""))
                .toList()));
        }
        if (schedule != null && schedule.isObject()) {
            text.line("Cron: " + s(schedule, "cron"));
        }
        text.line("Instruction:");
        text.line(nonNull(s(trigger, "instruction")));
        text.close();

        List<JsonNode> accounts = items(trigger.get("accounts"));
        text.blank().line("## Accounts: one row per system of the agent, the account its runs use there"
                + (accounts.isEmpty() ? " (none)" : ""));
        for (JsonNode account : accounts) {
            text.line("- System " + s(account, "systemId") + ": " + s(account, "status") + ", account "
                    + (s(account, "accountId") != null ? s(account, "accountId") : "gone") + " of "
                    + s(account, "holderKind") + " " + s(account, "holderPrincipalId") + ", filled from "
                    + s(account, "filledFrom"));
        }
        if (!accounts.isEmpty()) {
            text.open("the accounts' systems, holders and logins");
            for (JsonNode account : accounts) {
                text.line("- " + s(account, "systemId") + ": " + nonNull(s(account, "systemName")) + ", through "
                        + nonNull(s(account, "holderName")) + optional(", the credential ", s(account, "credentialName"))
                        + optional(", login ", s(account, "externalLogin")));
            }
            text.close();
        }
        List<JsonNode> unavailable = items(trigger.get("unavailableSystems"));
        if (!unavailable.isEmpty()) {
            text.blank().line("## The agent's systems without an account row: its runs cannot use them ("
                    + unavailable.size() + ")");
            text.open("the unavailable systems' names");
            for (JsonNode system : unavailable) {
                text.line("- " + s(system, "systemId") + ": " + nonNull(s(system, "systemName")));
            }
            text.close();
        }
        List<JsonNode> noAccount = items(trigger.get("noAccountSystems"));
        if (!noAccount.isEmpty()) {
            text.blank().line("## The agent's MCP servers that need no account: its runs call them without a "
                    + "credential, with no row (" + noAccount.size() + ")");
            text.open("the names of the systems that need no account");
            for (JsonNode system : noAccount) {
                text.line("- " + s(system, "systemId") + ": " + nonNull(s(system, "systemName")));
            }
            text.close();
        }
        return text.build();
    }

    static String firings(JsonNode page) {
        ResultText text = new ResultText();
        List<JsonNode> items = items(page.get("items"));
        text.line(items.isEmpty() ? "The trigger has not fired yet."
                : plural(items.size(), "firing") + ", newest first. STARTED and CONTINUED gave a conversation a turn; "
                        + "SKIPPED_* and FAILED did not, and say why.");
        for (JsonNode firing : items) {
            text.line("- " + s(firing, "createdAt") + " " + s(firing, "event") + " → " + s(firing, "outcome")
                    + optional(", conversation ", s(firing, "conversationId")) + optional(", run ", s(firing, "runId"))
                    + (s(firing, "manualBy") != null ? ", run now by user " + s(firing, "manualBy") : "")
                    + (firing.path("chainDepth").asInt(0) > 0 ? ", chain depth " + s(firing, "chainDepth") : "")
                    + " (firing " + s(firing, "id") + ")");
        }
        if (!items.isEmpty()) {
            text.open("the firings' subjects, the values of their events and their reasons");
            for (JsonNode firing : items) {
                text.line("- " + s(firing, "id") + ":" + optional(" subject ", s(firing, "subjectKey"))
                        + optional(", value ", s(firing, "value")) + optional(", reason ", s(firing, "reason")));
            }
            text.close();
        }
        nextPage(text, page);
        return text.build();
    }

    private static String state(JsonNode trigger) {
        String reason = s(trigger, "pausedReason");
        String waiting = s(trigger, "waitingReason");
        return s(trigger, "status") + optional(" (", reason, ")") + optional(", waiting on ", waiting);
    }

    private static String lastFiring(@Nullable JsonNode firing) {
        if (firing == null || !firing.isObject()) {
            return ", never fired";
        }
        return ", last fired " + s(firing, "createdAt") + ": " + s(firing, "outcome");
    }

    private static String deliveries(long count) {
        return count + (count == 1 ? " delivery" : " deliveries");
    }

    private static String yesNo(JsonNode node, String field) {
        return bool(node, field) ? "yes" : "no";
    }

    private static @Nullable String actions(@Nullable JsonNode actions) {
        List<String> kinds = items(actions).stream().map(action -> s(action, "kind")).toList();
        return kinds.isEmpty() ? null : String.join(", ", kinds);
    }

    private static String group(JsonNode group) {
        List<String> clauses = new ArrayList<>();
        for (String field : List.of("statuses", "notStatuses", "labels", "notLabels", "types", "notTypes")) {
            List<String> values = strings(group.get(field));
            if (!values.isEmpty()) {
                clauses.add(field + " " + String.join(", ", values));
            }
        }
        if (s(group, "authors") != null) {
            clauses.add("made by " + s(group, "authors"));
        }
        return clauses.isEmpty() ? "always" : String.join("; ", clauses);
    }

    private static String condition(JsonNode condition) {
        List<String> values = strings(condition.get("values"));
        return s(condition, "pointer") + " " + s(condition, "op") + (values.isEmpty() ? "" : " " + values);
    }

    // ---- systems ---------------------------------------------------------------------------------------------------

    static String systems(JsonNode page) {
        ResultText text = new ResultText();
        List<JsonNode> items = items(page.get("items"));
        text.line(items.isEmpty() ? "No systems: the company registered none the person sees."
                : plural(items.size(), "system") + ", by name. Read one with get_system, its tools with "
                        + "list_system_tools.");
        for (JsonNode system : items) {
            text.line("- System " + s(system, "id") + " @" + s(system, "handle") + ": " + s(system, "kind") + ", "
                    + s(system, "status") + (bool(system, "paused") ? ", PAUSED by an admin" : "")
                    + (bool(system, "personal") ? ", the person's own" : "")
                    + (system.has("accountsRequired") && !bool(system, "accountsRequired") ? ", needs no account" : "")
                    + lastTest(system.get("lastTest")));
        }
        if (!items.isEmpty()) {
            text.open("the systems' names, addresses and errors");
            for (JsonNode system : items) {
                text.line("- " + s(system, "id") + ": " + quoted(s(system, "name")) + optional(" at ", s(system,
                        "baseUrl")) + optional("; last error: ", oneLine(s(system, "lastError"), 300)));
            }
            text.close();
        }
        nextPage(text, page);
        return text.build();
    }

    static String system(JsonNode system) {
        ResultText text = new ResultText();
        text.line("System " + s(system, "id") + " @" + s(system, "handle") + " (its tools are named "
                + s(system, "handle") + "_{tool} in a run)");
        text.field("Kind", s(system, "kind"))
            .field("Status", s(system, "status") + (bool(system, "paused") ? ", PAUSED by an admin: its accounts "
                    + "are used for nothing" : ""))
            .field("Personal", bool(system, "personal") ? "yes, the person's own MCP server" : null)
            .field("Accounts sign in with OAuth", bool(system, "oauthAvailable") ? "yes" : "no")
            .field("Poll interval", optional("", s(system, "pollIntervalSeconds"), " s"))
            .field("Created", s(system, "createdAt"))
            .field("Updated", s(system, "updatedAt"));
        if ("MCP".equals(s(system, "kind"))) {
            text.field("Accounts", bool(system, "accountsRequired") ? "needed: runs call it with an account's token"
                    : "not needed: runs call it without a credential, and a trigger needs no row for it");
            text.field("Token header", s(system, "authHeader") != null ? s(system, "authHeader")
                    : "Authorization: Bearer");
        }
        JsonNode test = system.get("lastTest");
        if (!"WEBHOOK".equals(s(system, "kind"))) {
            text.field("Last test", test != null && test.isObject() ? (bool(test, "ok") ? "passed" : "failed")
                    + " at " + s(test, "at") + (bool(test, "withAccount") ? ", with an account"
                            : ", without a credential") + optional(", by ", s(test, "byPrincipalId"))
                    : "never (test it with test_system)");
        }
        JsonNode app = system.get("app");
        if (app != null && app.isObject()) {
            text.field("OAuth app", "client id " + s(app, "clientId") + ", client secret "
                    + (bool(app, "clientSecretSet") ? "kept (never shown)" : "none") + optional(", scopes ", s(app,
                            "scopes")) + optional(", authorization endpoint ", s(app, "authorizationEndpoint")))
                .field("OAuth redirect URI", s(app, "callbackUrl"));
        }
        else {
            text.field("OAuth redirect URI to register", s(system, "oauthCallbackUrl"));
        }
        JsonNode webhook = system.get("webhook");
        if (webhook != null && webhook.isObject()) {
            text.field("Delivery URL", s(webhook, "url"))
                .field("Scheme", s(webhook, "scheme") + optional(", secret in the header ", s(webhook,
                        "secretHeader")))
                // Its rate is its admins' to see; anyone else's answer holds 0.
                .field("Deliveries a minute", webhook.path("perMinute").asInt(0) <= 0 ? null
                        : s(webhook, "perMinute") + (s(webhook, "maxPerMinute") != null ? " (its own maximum)"
                                : " (the company's default)"))
                .field("Secret", s(webhook, "secretSetAt") != null ? "set " + s(webhook, "secretSetAt")
                        + optional(", ", age(s(webhook, "secretSetAt"))) + " (rotate every 90 days; never shown)"
                        : null)
                .field("Previous secret accepted until", s(webhook, "previousUntil"));
        }
        JsonNode chat = system.get("chat");
        if (chat != null && chat.isObject()) {
            text.field("Transport", s(chat, "transport"))
                .field("Bot", bool(chat, "botSet") ? "set" + optional(", user ", s(chat, "botUserId"))
                        + optional(", borrowed by the service account ", s(chat, "botPrincipalId")) : "not set")
                .field("Signing secret", bool(chat, "signingSecretSet") ? "kept (never shown)" : null)
                .field("App token", bool(chat, "appTokenSet") ? "kept (never shown)" : null)
                .field("Events URL", s(chat, "eventsUrl"))
                .field("Interactions URL", s(chat, "interactionsUrl"))
                .field("Messaging URL", s(chat, "messagingUrl"));
        }
        text.line("Systems are changed in Tinfirm only, never through you.");
        text.blank().open("the system's name, address and errors, and what its admins named");
        text.line("Name: " + nonNull(s(system, "name")));
        if (s(system, "baseUrl") != null) {
            text.line("Base URL: " + s(system, "baseUrl"));
        }
        if (s(system, "lastError") != null) {
            text.line("Last error: " + s(system, "lastError"));
        }
        if (test != null && test.isObject() && s(test, "error") != null) {
            text.line("Last test's error: " + s(test, "error"));
        }
        if (test != null && test.isObject() && s(test, "byName") != null) {
            text.line("Last tested by: " + s(test, "byName"));
        }
        JsonNode mapping = webhook != null ? webhook.get("mapping") : null;
        if (mapping != null && mapping.isObject() && !mapping.isEmpty()) {
            text.line("Mapping (JSON Pointers into a delivery's body):");
            for (String field : List.of("event", "subjectKey", "subjectTitle", "subjectUrl", "actorId", "actorName",
                    "text", "deliveryId")) {
                if (s(mapping, field) != null) {
                    text.line("  " + field + ": " + s(mapping, field));
                }
            }
        }
        if (chat != null && chat.isObject()) {
            if (s(chat, "teamName") != null) {
                text.line("Workspace: " + s(chat, "teamName"));
            }
            if (s(chat, "botPrincipalName") != null) {
                text.line("Bot held by: " + s(chat, "botPrincipalName"));
            }
        }
        text.close();
        return text.build();
    }

    private static String lastTest(@Nullable JsonNode test) {
        if (test == null || !test.isObject()) {
            return ", never tested";
        }
        return ", last test " + (bool(test, "ok") ? "passed" : "failed") + " at " + s(test, "at");
    }

    /** How long ago an instant was, in whole days. */
    private static @Nullable String age(@Nullable String at) {
        if (at == null) {
            return null;
        }
        try {
            long days = Duration.between(Instant.parse(at), Instant.now()).toDays();
            return days <= 0 ? "today" : plural(days, "day") + " ago";
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    static String tools(JsonNode list) {
        ResultText text = new ResultText();
        List<JsonNode> items = items(list.get("items"));
        if (items.isEmpty()) {
            text.line("No tools: an MCP server's catalogue is read when it is registered and refreshed by its admins "
                    + "in Tinfirm.");
            return text.build();
        }
        text.line(plural(items.size(), "tool") + ". Each tool's effect: READ or WRITE once confirmed (a fixed tool's "
                + "is Tinfirm's); UNKNOWN until an admin confirms it, which counts as WRITE (an agent's WRITE tools "
                + "ask the person first or are denied, as its settings say). A disabled tool is denied to every "
                + "agent.");
        for (JsonNode tool : items) {
            String effect = s(tool, "effect");
            String suggested = s(tool, "suggestedEffect");
            String confirmed = bool(tool, "fixed") ? "fixed" : !"UNKNOWN".equals(effect) ? "confirmed"
                    : suggested == null || "UNKNOWN".equals(suggested) ? "not confirmed, without hints"
                            : "not confirmed, its hints suggest " + suggested;
            text.line("- " + effect + " (" + confirmed + "), " + (bool(tool, "enabled") ? "enabled" : "disabled"));
            text.open("the tool's name, title and description, as the system describes it");
            text.line("Name: " + nonNull(s(tool, "name")) + optional(" — ", s(tool, "title")));
            if (s(tool, "description") != null) {
                text.line(oneLine(s(tool, "description"), 500));
            }
            text.close();
        }
        return text.build();
    }

    static String tested(JsonNode result) {
        ResultText text = new ResultText();
        text.line("The test " + (bool(result, "ok") ? "passed" : "failed") + ", "
                + (bool(result, "withAccount") ? "with the person's own account" : "without a credential")
                + ". It is the system's last test now (get_system shows it); nothing else changed.");
        text.open("the test's answer, as Tinfirm and the system gave it");
        text.line(nonNull(s(result, "message")));
        if (s(result, "login") != null) {
            text.line("Login: " + s(result, "login"));
        }
        text.close();
        return text.build();
    }

    // ---- webhooks --------------------------------------------------------------------------------------------------

    static String deliveries(JsonNode page) {
        ResultText text = new ResultText();
        JsonNode counts = page.get("lastSevenDays");
        if (counts != null && counts.isObject()) {
            text.line("Since " + s(counts, "since") + ": " + deliveries(counts.path("deliveries").asInt(0))
                    + " received, " + s(counts, "failed") + " the mapping "
                    + "could not read, " + s(counts, "refused") + " refused before they were stored (a signature, "
                    + "the time window, the size or a rate; the audit log's webhook.refused).");
        }
        List<JsonNode> items = items(page.get("items"));
        text.line(items.isEmpty() ? "No deliveries are kept (they are kept 7 days, their bodies 24 hours)."
                : deliveries(items.size()) + ", newest first. FIRED: "
                        + "a trigger took it; NO_TRIGGER: none matched (try_webhook_delivery says why); "
                        + "MAPPING_FAILED: the mapping could not read it.");
        for (JsonNode delivery : items) {
            text.line("- " + s(delivery, "receivedAt") + " " + s(delivery, "status") + ", " + plural(
                    delivery.path("firings").asInt(0), "firing") + ", " + s(delivery, "bodyBytes") + " bytes"
                    + optional(", signed at ", s(delivery, "signedAt")) + " (delivery " + s(delivery, "id") + ")");
        }
        if (!items.isEmpty()) {
            text.open("the deliveries as their sender sent them: their ids, events, subjects, failures and bodies");
            for (JsonNode delivery : items) {
                text.line("- " + s(delivery, "id") + ": delivery id " + s(delivery, "deliveryId")
                        + optional(", event ", s(delivery, "event")) + optional(", subject ", s(delivery,
                                "subjectKey")) + optional(", failure: ", s(delivery, "failure")));
                JsonNode body = delivery.get("body");
                if (body != null && body.isObject()) {
                    String json = body.toString();
                    text.line("  Body" + (json.length() > BODY ? " (the first " + BODY + " of " + json.length()
                            + " characters; the whole one is in structuredContent)" : "") + ": "
                            + (json.length() > BODY ? json.substring(0, BODY) + "…" : json));
                }
            }
            text.close();
        }
        nextPage(text, page);
        return text.build();
    }

    static String mapped(JsonNode mapped) {
        ResultText text = new ResultText();
        text.line("Tried without storing or firing anything.");
        if (s(mapped, "failure") != null) {
            text.line("The mapping cannot read this body: a delivery of it would be MAPPING_FAILED and fire nothing.");
            text.content("the mapping's failure", s(mapped, "failure"));
            return text.build();
        }
        text.open("what the mapping read from the body");
        for (String field : List.of("event", "subjectKey", "subjectTitle", "subjectUrl", "actorId", "actorName",
                "deliveryId")) {
            text.line(field + ": " + (s(mapped, field) != null ? s(mapped, field) : "(nothing)"));
        }
        if (s(mapped, "text") != null) {
            text.line("text: " + oneLine(s(mapped, "text"), 1000));
        }
        text.close();
        List<JsonNode> triggers = items(mapped.get("triggers"));
        text.blank().line(triggers.isEmpty() ? "The system has no webhook triggers: a delivery would be NO_TRIGGER."
                : "## The system's webhook triggers (" + triggers.size() + "): a delivery fires those that match, "
                        + "are ACTIVE and wait on nothing, within their max firings per hour and unless their agent "
                        + "is paused");
        for (JsonNode trigger : triggers) {
            String verdict;
            if (!bool(trigger, "matches")) {
                verdict = "would not fire: does not match";
            }
            else if ("PAUSED".equals(s(trigger, "status"))) {
                verdict = "matches, but is PAUSED" + optional(" (", s(trigger, "pausedReason"), ")")
                        + ": would not fire";
            }
            else if (s(trigger, "waitingReason") != null) {
                verdict = "matches, but waits on " + s(trigger, "waitingReason") + ": would not fire now";
            }
            else {
                verdict = "would fire";
            }
            text.line("- Trigger " + s(trigger, "triggerId") + ": " + verdict);
            JsonNode mismatch = trigger.get("mismatch");
            if (mismatch != null && mismatch.isObject()) {
                text.line("  " + s(mismatch, "kind") + ": " + s(mismatch, "detail"));
            }
            text.open("the trigger's name" + (mismatch != null && mismatch.isObject()
                    ? ", the test it fails as its author wrote it, and what the body holds" : ""));
            text.line("Name: " + nonNull(s(trigger, "name")));
            if (mismatch != null && mismatch.isObject()) {
                if ("EVENT".equals(s(mismatch, "kind"))) {
                    text.line("It takes the events: " + String.join(" | ", strings(mismatch.get("events"))));
                    text.line("The mapped event: " + (s(mismatch, "event") != null ? s(mismatch, "event")
                            : "(none)"));
                }
                else {
                    List<String> values = strings(mismatch.get("values"));
                    text.line("Condition " + (mismatch.path("condition").asInt(0) + 1) + ": " + s(mismatch, "pointer")
                            + " " + s(mismatch, "op") + (values.isEmpty() ? "" : " " + values));
                    text.line("The body holds there: " + (s(mismatch, "actual") != null ? s(mismatch, "actual")
                            : "(nothing it reads as text)"));
                }
            }
            text.close();
        }
        return text.build();
    }

    // ---- spend -----------------------------------------------------------------------------------------------------

    static String spend(JsonNode report) {
        ResultText text = new ResultText();
        List<String> groupBy = strings(report.get("groupBy"));
        text.line("Spend of runs that ended from " + s(report, "from") + " to " + s(report, "to") + " (exclusive), in "
                + s(report, "zone") + (groupBy.isEmpty() ? "" : ", by " + String.join(" and ", groupBy))
                + (groupBy.contains("period") ? " (periods of a " + s(report, "bucket") + ")" : "")
                + ". Only what the person may see is counted: what they paid and their triggers' runs; every run "
                + "for the company's owners and admins.");
        text.line("Totals: " + amounts(report.get("totals")));
        List<JsonNode> rows = items(report.get("rows"));
        if (!rows.isEmpty() && !groupBy.isEmpty()) {
            text.open("the rows' names, keys and titles");
            for (JsonNode row : rows) {
                List<String> keys = new ArrayList<>();
                for (JsonNode key : items(row.get("keys"))) {
                    keys.add(s(key, "dimension") + " " + (bool(key, "folded") ? "(another person's conversations"
                            + optional(", person ", s(key, "personPrincipalId")) + ")" : nonNull(s(key, "label"))
                                    + optional(" ", s(key, "key")) + optional(" ", quoted(s(key, "title")))
                                    + optional(" [", s(key, "id"), "]")));
                }
                text.line("- " + String.join(" · ", keys) + ": " + amounts(row.get("amounts")));
            }
            text.close();
        }
        return text.build();
    }

    private static String amounts(@Nullable JsonNode amounts) {
        if (amounts == null || !amounts.isObject()) {
            return "none";
        }
        return plural(amounts.path("runs").asLong(0), "run") + ", " + s(amounts, "requests") + " requests, $"
                + s(amounts, "costUsd") + optional(" (of which the relay's count $", s(amounts, "relayCostUsd"), ")")
                + ", " + s(amounts, "inputTokens") + " input and " + s(amounts, "outputTokens") + " output tokens"
                + (amounts.path("subscriptionTokens").asLong(0) > 0 ? ", " + s(amounts, "subscriptionTokens")
                        + " subscription tokens" : "");
    }
}
