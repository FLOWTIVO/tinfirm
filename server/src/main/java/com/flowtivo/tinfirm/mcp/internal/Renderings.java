package com.flowtivo.tinfirm.mcp.internal;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * The text renderings of the REST DTOs of conversations, decisions, the inbox and agents for the client's model
 * (ADR-0045; the package 6 contract, §5.7), from the same JSON as the result's {@code structuredContent}, so the two
 * never disagree. Tinfirm's own fields (ids, keys, states, times, counts) are plain lines; titles, messages, questions,
 * answers, notes, a call's arguments, results, failure causes and the names people chose stand in labelled blocks
 * ({@link ResultText}). A list ends with how to fetch its next page.
 */
final class Renderings {

    /** The messages a conversation's text shows by default: the newest. */
    static final int DEFAULT_MESSAGES = 20;

    private Renderings() {
    }

    // ---- conversations ---------------------------------------------------------------------------------------------

    static String conversations(JsonNode page) {
        ResultText text = new ResultText();
        List<JsonNode> items = items(page.get("items"));
        text.line(items.isEmpty() ? "No conversations match." : plural(items.size(), "conversation")
                + ", newest activity first. Read one with get_conversation.");
        if (!items.isEmpty()) {
            text.open("the listed conversations: their titles and agents");
            items.forEach(item -> conversationLine(text, item));
            text.close();
        }
        nextPage(text, page);
        return text.build();
    }

    /** A conversation in full, with its newest {@code messages} messages. */
    static String conversation(JsonNode conversation, int messages) {
        ResultText text = new ResultText();
        header(text, conversation);
        List<JsonNode> bindings = items(conversation.get("bindings"));
        if (!bindings.isEmpty()) {
            text.blank().line("## Accounts it uses");
            text.open("the conversation's accounts, as Tinfirm describes them");
            for (JsonNode binding : bindings) {
                text.line("- " + s(binding, "text") + " (" + s(binding, "state") + optional(", ", s(binding, "reason"))
                        + ")");
            }
            text.close();
        }
        JsonNode result = conversation.get("result");
        if (result != null && result.isObject()) {
            text.blank().line("## Latest result: " + s(result, "outcome"));
            text.open("the agent's result");
            text.line(nonNull(s(result, "summary")));
            List<String> next = strings(result.get("nextSteps"));
            if (!next.isEmpty()) {
                text.line("Next steps:");
                next.forEach(step -> text.line("- " + step));
            }
            List<String> open = strings(result.get("openQuestions"));
            if (!open.isEmpty()) {
                text.line("Open questions:");
                open.forEach(question -> text.line("- " + question));
            }
            text.close();
        }
        if (s(conversation, "failureClass") != null) {
            text.blank().line("## Latest turn failed: " + s(conversation, "failureClass"));
            text.content("the failure's cause", s(conversation, "failureCause"));
        }
        List<JsonNode> open = items(conversation.get("decisions")).stream()
            .filter(decision -> "OPEN".equals(s(decision, "status")))
            .toList();
        if (!open.isEmpty()) {
            text.blank().line("## Open decisions (" + open.size() + "): decide with decide and the decision's id");
            text.open("the open decisions");
            open.forEach(decision -> decisionLine(text, decision));
            text.close();
        }
        List<JsonNode> outputs = items(conversation.get("outputs"));
        if (!outputs.isEmpty()) {
            text.blank().line("## Outputs (" + outputs.size() + ")");
            text.open("the outputs' titles");
            for (JsonNode output : outputs) {
                text.line("- " + s(output, "kind") + " " + quoted(s(output, "title")) + optional(" · ", s(output, "url"))
                        + optional(" · ", s(output, "changeRequestState")));
            }
            text.close();
        }
        List<JsonNode> all = items(conversation.get("messages"));
        if (!all.isEmpty()) {
            int shown = Math.min(all.size(), Math.max(1, messages));
            text.blank().line("## Messages, oldest first" + (shown < all.size() ? " (the newest " + shown + " of "
                    + all.size() + "; ask for more with messages)" : " (" + all.size() + ")"));
            text.open("the conversation's messages");
            for (JsonNode message : all.subList(all.size() - shown, all.size())) {
                text.line("[" + s(message, "kind") + optional(" ", s(message, "authorName")) + ", "
                        + s(message, "createdAt") + (bool(message, "waiting") ? ", waiting" : "")
                        + steering(message) + "]");
                text.line(nonNull(s(message, "body")));
                text.line("");
            }
            text.close();
        }
        return text.build(ResultText.CAP, "The whole conversation is in structuredContent.");
    }

    /** What a write did, then the conversation's header. */
    static String written(String what, JsonNode conversation) {
        return written(what, conversation, false);
    }

    /** What a write did, that a turn follows when it does (it takes minutes), then the conversation's header. */
    static String written(String what, JsonNode conversation, boolean turnFollows) {
        ResultText text = new ResultText();
        text.line(what);
        if (turnFollows) {
            text.line("A turn takes minutes: follow the conversation with get_conversation.");
        }
        text.blank();
        header(text, conversation);
        return text.build();
    }

    /** A start: what happened, the Owner's notice, then the conversation. */
    static String started(JsonNode conversation, String notice) {
        ResultText text = new ResultText();
        text.line("Started the conversation " + s(conversation, "key") + " (id " + s(conversation, "id") + ") with "
                + "the agent; its first turn is queued. A turn takes minutes: follow it with get_conversation, and "
                + "continue it with send_message.");
        text.line(notice);
        text.blank();
        header(text, conversation);
        return text.build();
    }

    private static void header(ResultText text, JsonNode conversation) {
        text.line("Conversation " + s(conversation, "key") + " (id " + s(conversation, "id") + ")");
        text.field("State", s(conversation, "state") + optional(" (", s(conversation, "endedReason"), ")"))
            .field("Origin", s(conversation, "origin"))
            .field("Latest turn", s(conversation, "turnStatus"))
            .field("Waiting on", waiting(conversation))
            .field("Turns", s(conversation, "turnCount"))
            .field("Agent id", s(conversation, "agentId"))
            .field("Paid by", payer(conversation.get("payer")))
            .field("Cost", cost(conversation.get("cost")))
            .field("Tokens", tokens(conversation.get("cost")))
            .field("Branch", s(conversation, "branch"))
            .field("Created", s(conversation, "createdAt"))
            .field("Updated", s(conversation, "updatedAt"));
        JsonNode subject = conversation.get("subject");
        if (subject != null && subject.isObject()) {
            text.field("Subject", s(subject, "key") + optional(" · ", s(subject, "url")));
        }
        text.open("the conversation's title, agent and people");
        text.line("Title: " + nonNull(s(conversation, "title")));
        text.line("Agent: " + nonNull(s(conversation, "agentName")));
        text.line("Person: " + nonNull(s(conversation, "personName")));
        if (subject != null && subject.isObject() && s(subject, "title") != null) {
            text.line("Subject's title: " + s(subject, "title"));
        }
        text.close();
    }

    private static void conversationLine(ResultText text, JsonNode item) {
        text.line("- " + s(item, "key") + " (id " + s(item, "id") + ") " + s(item, "state")
                + optional(", turn ", s(item, "turnStatus")) + optional(", waiting on ", waiting(item))
                + (item.path("costUsd").asDouble(0) > 0 ? ", cost $" + s(item, "costUsd") : "")
                + (tokenTotal(item.get("tokens")) > 0 ? ", " + tokenTotal(item.get("tokens")) + " tokens" : "")
                + ", updated " + s(item, "updatedAt") + ": " + quoted(s(item, "title")) + " with "
                + s(item, "agentName"));
    }

    /**
     * "$1.24 for 3 settled turns", with a subscription's tokens; turns not settled yet are not in it, and nothing when
     * none is.
     */
    private static @Nullable String cost(@Nullable JsonNode cost) {
        int turns = cost != null && cost.isObject() ? items(cost.get("turns")).size() : 0;
        if (turns == 0) {
            return null;
        }
        long subscriptionTokens = cost.path("subscriptionTokens").asLong(0);
        return "$" + s(cost, "costUsd") + " for " + plural(turns, "settled turn")
                + (subscriptionTokens > 0 ? ", " + subscriptionTokens + " subscription tokens" : "");
    }

    /** "in 1200, cache write 300, cache read 12000, out 340" of its settled turns; nothing when none is. */
    private static @Nullable String tokens(@Nullable JsonNode cost) {
        JsonNode tokens = cost != null && cost.isObject() ? cost.get("tokens") : null;
        if (tokenTotal(tokens) == 0) {
            return null;
        }
        return "in " + s(tokens, "inputTokens") + ", cache write " + s(tokens, "cacheWriteTokens") + ", cache read "
                + s(tokens, "cachedInputTokens") + ", out " + s(tokens, "outputTokens");
    }

    /** The four token counts together; 0 without them. */
    private static long tokenTotal(@Nullable JsonNode tokens) {
        if (tokens == null || !tokens.isObject()) {
            return 0;
        }
        return tokens.path("inputTokens").asLong(0) + tokens.path("cacheWriteTokens").asLong(0)
                + tokens.path("cachedInputTokens").asLong(0) + tokens.path("outputTokens").asLong(0);
    }

    private static @Nullable String payer(@Nullable JsonNode payer) {
        if (payer == null || !payer.isObject()) {
            return null;
        }
        return s(payer, "modelAccess") + optional(" ", s(payer, "subscriptionLabel"));
    }

    // ---- decisions -------------------------------------------------------------------------------------------------

    static String decisions(JsonNode page) {
        ResultText text = new ResultText();
        List<JsonNode> items = items(page.get("items"));
        text.line(items.isEmpty() ? "No decisions match." : plural(items.size(), "decision") + ", newest first. "
                + "Decide one with decide, after the person said so.");
        if (!items.isEmpty()) {
            text.open("the listed decisions");
            items.forEach(decision -> decisionLine(text, decision));
            text.close();
        }
        nextPage(text, page);
        return text.build();
    }

    static String decision(JsonNode decision) {
        ResultText text = new ResultText();
        text.line("Decision " + s(decision, "id") + ": " + s(decision, "kind") + ", " + s(decision, "status")
                + optional(", ", s(decision, "outcome")));
        text.field("Conversation", s(decision, "conversationKey") + optional(" (id ", s(decision, "conversationId"),
                ")"))
            .field("You decide it", bool(decision, "mayDecide") ? "yes" : "no")
            .field("Created", s(decision, "createdAt"))
            .field("Decided", s(decision, "decidedAt"));
        text.open("the decision");
        decisionBody(text, decision);
        text.close();
        return text.build();
    }

    private static void decisionLine(ResultText text, JsonNode decision) {
        text.line("- " + s(decision, "kind") + " " + s(decision, "id") + " (" + s(decision, "status")
                + optional(", ", s(decision, "outcome")) + optional(", conversation ", s(decision, "conversationKey"))
                + (bool(decision, "mayDecide") ? ", you decide it" : "") + ")");
        decisionBody(text, decision);
    }

    private static void decisionBody(ResultText text, JsonNode decision) {
        if (s(decision, "question") != null) {
            text.line("  Question: " + s(decision, "question"));
        }
        List<String> options = strings(decision.get("options"));
        if (!options.isEmpty()) {
            text.line("  Options: " + String.join(" | ", options));
        }
        JsonNode subject = decision.get("subject");
        if (subject != null && subject.isObject()) {
            text.line("  Approval of " + s(subject, "type") + optional(": ", s(subject, "what"))
                    + optional(" on ", s(subject, "systemName")) + optional(", tool ", s(subject, "name"))
                    + optional(", repository ", s(subject, "repositoryName")) + optional(", host ", s(subject, "host")));
            if (subject.get("arguments") != null && subject.get("arguments").isObject()) {
                text.line("  Arguments: " + subject.get("arguments"));
            }
        }
        if (s(decision, "reason") != null) {
            text.line("  Reason: " + s(decision, "reason"));
        }
        if (s(decision, "answer") != null) {
            text.line("  Answer: " + s(decision, "answer"));
        }
        if (s(decision, "note") != null) {
            text.line("  Note: " + s(decision, "note"));
        }
        if (s(decision, "result") != null) {
            text.line("  Result: " + s(decision, "result"));
        }
    }

    // ---- inbox -----------------------------------------------------------------------------------------------------

    static String inbox(JsonNode inbox) {
        ResultText text = new ResultText();
        JsonNode counts = inbox.get("counts");
        text.line("Inbox: " + count(counts, "decisions", "decision") + " to decide, " + count(counts, "bindings",
                "account choice") + ", " + count(counts, "failed", "failed conversation") + ", "
                + count(counts, "waiting", "waiting conversation") + ", " + count(counts, "accounts",
                        "company account") + " to reconnect.");
        List<JsonNode> items = items(inbox.get("items"));
        if (items.isEmpty()) {
            text.line("Nothing waits for the person now.");
            return text.build();
        }
        text.line("Longest waiting first. Read a conversation with get_conversation, decide with decide; an account is "
                + "connected in Tinfirm, never through you.");
        text.open("the inbox's items");
        for (JsonNode item : items) {
            JsonNode conversation = item.get("conversation");
            String about = conversation != null && conversation.isObject() ? " in " + s(conversation, "key")
                    + " (id " + s(conversation, "id") + ") " + quoted(s(conversation, "title")) : "";
            text.line("- " + s(item, "kind") + " since " + s(item, "since") + about);
            JsonNode decision = item.get("decision");
            if (decision != null && decision.isObject()) {
                text.line("  Decision " + s(decision, "id") + ": " + s(decision, "kind"));
                decisionBody(text, decision);
            }
            JsonNode binding = item.get("binding");
            if (binding != null && binding.isObject()) {
                text.line("  Account on " + s(binding, "systemName") + ": " + s(binding, "reason"));
            }
            JsonNode turn = item.get("turn");
            if (turn != null && turn.isObject()) {
                text.line("  Turn " + s(turn, "status") + optional(": ", s(turn, "failureClass"))
                        + optional(" ", s(turn, "failureCause")) + optional(", waiting ", s(turn, "waitingReason"))
                        + optional(" until ", s(turn, "retryAt")));
            }
            JsonNode account = item.get("account");
            if (account != null && account.isObject()) {
                text.line("  Company account on " + s(account, "systemName") + ": " + s(account, "reason") + ", "
                        + s(account, "conversations") + " conversations wait");
            }
            JsonNode trigger = item.get("trigger");
            if (trigger != null && trigger.isObject()) {
                text.line("  Trigger " + quoted(s(trigger, "name")) + " (id " + s(trigger, "triggerId") + "): "
                        + s(trigger, "reason") + optional(", ", s(trigger, "count"), " notes")
                        + ("MEMORY_REVIEW".equals(s(trigger, "reason"))
                                ? "; read them with list_trigger_memory_notes, review them in Tinfirm" : ""));
            }
        }
        text.close();
        return text.build();
    }

    // ---- memories --------------------------------------------------------------------------------------------------

    /** The person's own memory of an agent. */
    static String memory(JsonNode memory) {
        ResultText text = new ResultText();
        List<JsonNode> notes = items(memory.get("notes"));
        text.line("Your memory of this agent: " + plural(notes.size(), "note") + ", " + s(memory, "characters")
                + " of " + s(memory, "maxCharacters") + " characters. Memory is "
                + (bool(memory, "inEffect") ? "on" : "off") + " for you" + optional(", last tidied ",
                        s(memory, "compactedAt")) + ".");
        if (notes.isEmpty()) {
            return text.build();
        }
        text.line("Delete a note with delete_memory_note and its number, only when the person asks.");
        memoryNotes(text, notes);
        return text.build();
    }

    /** A trigger's memory, with the link where its audience reviews its proposals. */
    static String triggerMemory(JsonNode memory, String link) {
        ResultText text = new ResultText();
        List<JsonNode> notes = items(memory.get("notes"));
        long proposed = notes.stream().filter(note -> "PROPOSED".equals(s(note, "state"))).count();
        text.line("The trigger's memory: " + plural(notes.size() - proposed, "accepted note") + " and "
                + plural(proposed, "proposal") + ", " + s(memory, "characters") + " of " + s(memory, "maxCharacters")
                + " characters. Memory is " + (bool(memory, "inEffect") ? "on" : "off") + " for the trigger.");
        if (proposed > 0) {
            text.line("No conversation reads a proposal until the trigger's author or team accepts it. Proposals "
                    + "are reviewed only in Tinfirm, never through you: " + link);
        }
        memoryNotes(text, notes);
        return text.build();
    }

    private static void memoryNotes(ResultText text, List<JsonNode> notes) {
        if (notes.isEmpty()) {
            return;
        }
        text.open("the memory's notes, as the agent wrote them");
        for (JsonNode note : notes) {
            String about = s(note, "about");
            String of = "SYSTEM".equals(about) ? " " + s(note, "systemId")
                    : "REPOSITORY".equals(about) ? " " + s(note, "repositoryId") : "";
            text.line("- " + s(note, "id") + " " + s(note, "state") + optional(" (replaces m", s(note, "replaces"),
                    ")") + ", about " + about + of + optional(", last used ", s(note, "usedAt")) + ": "
                    + oneLine(s(note, "text"), 400));
        }
        text.close();
    }

    // ---- agents ----------------------------------------------------------------------------------------------------

    static String agents(JsonNode page) {
        ResultText text = new ResultText();
        List<JsonNode> items = items(page.get("items"));
        text.line(items.isEmpty() ? "No agents match." : plural(items.size(), "agent") + ". Start a conversation "
                + "with start_conversation and an agent's id or @handle; get_agent says whether it is ready.");
        if (!items.isEmpty()) {
            text.open("the agents' names and descriptions");
            for (JsonNode agent : items) {
                text.line("- @" + s(agent, "handle") + " (id " + s(agent, "id") + ") " + s(agent, "status")
                        + (bool(agent, "usableByMe") ? ", you may use it" : ", not usable by you") + ": "
                        + s(agent, "name") + optional(" — ", oneLine(s(agent, "description"), 300)));
            }
            text.close();
        }
        nextPage(text, page);
        return text.build();
    }

    /** An agent, and its readiness for the person when they may use it ({@code readiness} null otherwise). */
    static String agent(JsonNode agent, @Nullable JsonNode readiness) {
        ResultText text = new ResultText();
        text.line("Agent @" + s(agent, "handle") + " (id " + s(agent, "id") + ")");
        text.field("Status", s(agent, "status"))
            .field("Version", s(agent, "version"))
            .field("You may use it", bool(agent, "usableByMe") ? "yes" : "no")
            .field("Runtimes", joined(agent.get("runtimes")));
        text.open("the agent's name, description and instructions");
        text.line("Name: " + s(agent, "name"));
        if (s(agent, "description") != null) {
            text.line("Description: " + s(agent, "description"));
        }
        text.line("Instructions:");
        text.line(nonNull(s(agent, "instructions")));
        text.close();
        if (readiness != null && readiness.isObject()) {
            text.blank().line("## Readiness for the person, if they start a conversation now");
            JsonNode payer = readiness.get("payer");
            text.field("Paid by", s(payer, "modelAccess") + ", " + s(payer, "state") + optional(" (", s(payer, "reason"),
                    ")") + optional(" until ", s(payer, "until")));
            JsonNode hosts = readiness.get("hosts");
            text.field("Runner hosts", bool(hosts, "runnable") ? s(hosts, "eligible") + " may run it"
                    : "none may run it" + optional(": ", s(hosts, "reason")));
            List<JsonNode> systems = items(readiness.get("systems"));
            if (!systems.isEmpty()) {
                text.line("Accounts at each system's first use (CONNECT and RECONNECT_* are done by the person in "
                        + "Tinfirm, under My accounts):");
                text.open("the systems' names");
                for (JsonNode system : systems) {
                    text.line("- " + s(system, "name") + " (" + s(system, "kind") + "): " + s(system, "binding")
                            + optional(", ", s(system, "access")));
                }
                text.close();
            }
        }
        return text.build();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static void nextPage(ResultText text, JsonNode page) {
        String next = s(page, "nextCursor");
        if (next != null) {
            text.line("More: call again with cursor=" + next);
        }
    }

    static @Nullable String s(@Nullable JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isValueNode() ? value.asString() : value.toString();
    }

    static List<JsonNode> items(@Nullable JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
        return items;
    }

    static List<String> strings(@Nullable JsonNode array) {
        return items(array).stream().filter(JsonNode::isValueNode).map(JsonNode::asString).toList();
    }

    static @Nullable String joined(@Nullable JsonNode array) {
        List<String> values = strings(array);
        return values.isEmpty() ? null : String.join(", ", values);
    }

    static boolean bool(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.asBoolean(false);
    }

    private static @Nullable String waiting(JsonNode item) {
        String on = s(item, "waitingOn");
        if (on == null || "NONE".equals(on)) {
            return null;
        }
        return on + optional(" ", s(item, "waitingReason")) + optional(" since ", s(item, "waitingSince"));
    }

    private static String count(@Nullable JsonNode node, String field, String noun) {
        JsonNode value = node != null ? node.get(field) : null;
        return plural(value != null ? value.asInt(0) : 0, noun);
    }

    static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    static String optional(String prefix, @Nullable String value) {
        return optional(prefix, value, "");
    }

    static String optional(String prefix, @Nullable String value, String suffix) {
        return value == null || value.isBlank() ? "" : prefix + value + suffix;
    }

    static String quoted(@Nullable String value) {
        return value == null ? "" : "\"" + oneLine(value, 200) + "\"";
    }

    /** Where a person's message written while a turn ran is (steering). */
    private static String steering(JsonNode message) {
        return switch (nonNull(s(message, "steering"))) {
            case "SENDING" -> ", on its way to the running turn";
            case "DELIVERED" -> ", taken by its turn while it worked";
            default -> "";
        };
    }

    static String nonNull(@Nullable String value) {
        return value != null ? value : "";
    }

    /** The value on one line, cut to {@code max} characters. */
    static @Nullable String oneLine(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        String line = value.strip().replaceAll("\\s+", " ");
        return line.length() <= max ? line : line.substring(0, max) + "…";
    }
}
