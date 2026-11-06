package com.flowtivo.tinfirm.conversation.internal;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolInfo;

/**
 * The tools of a run as a function (the package 3 contract, §5.4, I3, I9, I19): steps 1 to 5 of
 * {@code ConversationTools.modeOf} for one tool of one system, each step only narrowing the one before. Step 6 is
 * the system's own answer for the bound account, which only the call learns. A tool is listed when it is {@code ALLOW}
 * or {@code ASK}; a call of any other answers the refusal's code and message. Pure: the caller reads what it needs.
 */
final class ToolRules {

    /** The longest description the runner protocol carries ({@code ToolSpec.description}). */
    static final int MAX_DESCRIPTION = 4000;

    static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";

    static final String TOOL_DENIED = "TOOL_DENIED";

    static final String ACCOUNT_GONE = "ACCOUNT_GONE";

    private ToolRules() {
    }

    /** The step that refused a tool (§5.4); {@code tool.denied} names it. */
    enum Step {
        /** 1: not a tool its system serves to this person. */
        SERVED,
        /** 3: not one of the agent's tools (nor of the person's own server). */
        AGENT,
        /** 4: switched off in the person's settings. */
        SETTINGS,
        /** 5: the binding is gone, or acts through a read-only grant and the tool writes. */
        BINDING
    }

    /** The conversation's binding on the tool's system, as step 5 sees it. */
    record BindingView(State state, GrantInfo.@Nullable Access access, @Nullable String holderName,
            @Nullable String groupName, BindingInfo.@Nullable Reason reason) {

        enum State {
            /** Not resolved yet: the first call binds it (§5.3). */
            UNBOUND,
            /** Waits for its card; a call is parked. */
            WAITING,
            BOUND,
            GONE,
            /** A trigger's conversation without a binding on the system: its trigger has no row for it (§5.7). */
            NO_ROW,
            /**
             * An MCP server that needs no account (ADR-0071), bound so or not yet: calls go without a credential, as
             * {@code WRITE}.
             */
            OPEN
        }

        static BindingView noRow() {
            return new BindingView(State.NO_ROW, null, null, null, null);
        }

        static BindingView unbound() {
            return new BindingView(State.UNBOUND, null, null, null, null);
        }

        static BindingView waiting(BindingInfo.Reason reason) {
            return new BindingView(State.WAITING, null, null, null, reason);
        }

        static BindingView gone(BindingInfo.@Nullable Reason reason) {
            return new BindingView(State.GONE, null, null, null, reason);
        }

        /** No account, on an MCP server that needs none (ADR-0071): {@code WRITE}. */
        static BindingView open() {
            return new BindingView(State.OPEN, GrantInfo.Access.WRITE, null, null, null);
        }

        /** The person's own account: {@code WRITE}. */
        static BindingView own() {
            return new BindingView(State.BOUND, GrantInfo.Access.WRITE, null, null, null);
        }

        /**
         * A trigger's conversation's account, chosen by its trigger (package 4, T5): {@code WRITE} for its author's own,
         * a service account's credential at the access it is lent with (ADR-0072).
         */
        static BindingView fixed(String holderName, GrantInfo.Access access) {
            return new BindingView(State.BOUND, access, holderName, null, null);
        }

        /** Through a grant, with its access now (open point 9). */
        static BindingView granted(GrantInfo.Access access, String holderName, String groupName) {
            return new BindingView(State.BOUND, access, holderName, groupName, null);
        }

        boolean viaGrant() {
            return state == State.BOUND && groupName != null;
        }
    }

    /**
     * The names the texts use.
     *
     * @param person  the conversation's person; a trigger's conversation's author
     * @param trigger a trigger's conversation's trigger; null for a personal one
     */
    record Names(String agent, String person, String system, @Nullable String trigger) {

        Names(String agent, String person, String system) {
            this(agent, person, system, null);
        }
    }

    /** What steps 1 to 5 answer for one tool. */
    sealed interface Decision {

        /** Listed; a call runs. */
        record Allow(ToolInfo tool, ToolEffect effect) implements Decision {
        }

        /** Listed; each call is an approval of the conversation's person. */
        record Ask(ToolInfo tool, ToolEffect effect) implements Decision {
        }

        /** Not listed; a call answers the code and the message. */
        record Refused(Step step, String code, String message) implements Decision {
        }

        default boolean listed() {
            return !(this instanceof Refused);
        }
    }

    /**
     * Steps 1 to 5 for the tool {@code tool} of {@code system}, which the run lists (or the agent called) by
     * {@code name}.
     *
     * @param served the tool as its system serves it now; null when it serves none of that name
     */
    static Decision decide(SystemInfo system, String name, String tool, @Nullable ToolInfo served,
            UUID personPrincipalId, EffectiveSettings settings, BindingView binding, Names names) {
        // 1. Served: a fixed tool the agent calls, or an MCP tool present and enabled; another member's server is none.
        if (served == null || !served.enabled() || served.fixed() && !FixedTools.calledByAgent(tool)
                || system.personal() && !system.ownedBy(personPrincipalId)) {
            return new Decision.Refused(Step.SERVED, UNKNOWN_TOOL, "There is no tool " + quoted(name) + " in this "
                    + "conversation. The tools you can call are those your tool list shows.");
        }
        // 2. Effect: UNKNOWN counts as WRITE (I19).
        ToolEffect effect = served.effect().effective();
        // A paused system is one without an account for the conversation (package 6, §5.4): its tools are not listed,
        // and a call from a list made before the pause is refused.
        if (system.paused()) {
            return new Decision.Refused(Step.BINDING, TOOL_DENIED, names.system() + " is paused by an admin. Tell "
                    + names.person() + " in your summary when your work needs it.");
        }
        // 3. and 4. The agent (or the person's own server), then the settings.
        Decision mode = switch (settings.modeOf(system.id(), tool, served.effect())) {
            case NOT_ON_AGENT -> new Decision.Refused(Step.AGENT, TOOL_DENIED, quoted(name) + " is not one of "
                    + names.agent() + "'s tools; whoever edits the agent adds tools. Tell " + names.person()
                    + " in your summary when your work needs it.");
            case SWITCHED_OFF -> new Decision.Refused(Step.SETTINGS, TOOL_DENIED, quoted(name) + " is switched off "
                    + "in " + names.person() + "'s settings of " + names.agent() + ". Tell " + names.person()
                    + " in your summary when your work needs it.");
            case ASK -> new Decision.Ask(served, effect);
            case ALLOW -> new Decision.Allow(served, effect);
        };
        if (!mode.listed()) {
            return mode;
        }
        // 5. The binding: gone removes the system's tools; a read-only grant its writes (I9). A trigger's conversation
        // has the bindings of its trigger's table only (the package 4 contract, §5.7).
        if (binding.state() == BindingView.State.NO_ROW) {
            return new Decision.Refused(Step.BINDING, TOOL_DENIED, names.system() + " is not available to the trigger "
                    + Objects.requireNonNullElse(names.trigger(), "of this conversation") + ": it names no account "
                    + "there. Tell " + names.person() + " in your summary when your work needs it.");
        }
        if (binding.state() == BindingView.State.GONE) {
            return new Decision.Refused(Step.BINDING, ACCOUNT_GONE, names.system() + " can no longer be used in this "
                    + "conversation (" + goneReason(binding.reason(), names) + "). " + names.person()
                    + " starts a new conversation to use " + names.system() + "; tell them in your summary.");
        }
        if (binding.state() == BindingView.State.BOUND && binding.access() == GrantInfo.Access.READ
                && effect == ToolEffect.WRITE) {
            String through = binding.groupName() != null ? "the read-only grant of " + binding.groupName()
                    : "the credential lent read-only to " + Objects.requireNonNullElse(binding.holderName(),
                            "a service account");
            return new Decision.Refused(Step.BINDING, TOOL_DENIED, quoted(name) + " writes, and you act through "
                    + through + " on " + names.system() + ". Tell " + names.person() + " in your summary when your "
                    + "work needs it.");
        }
        return mode;
    }

    /** A channel thread's place: its chat system, channel and thread (the package 5 contract, §5.7). */
    record ThreadPlace(UUID systemId, String channelId, String threadId) {
    }

    /**
     * A call that reaches outside its channel's thread, refused (the package 5 contract, open point 13): anyone in the
     * channel may mention the bot, so Slack's {@code history} reads the thread the agent was asked in only, never
     * another thread, the channel's own messages, another channel nor another chat system, with the account its
     * trigger lends. Null when the call reads that thread, or the conversation is not a channel's thread. Audited as
     * step 5, the binding's: what the trigger's account may do there.
     *
     * @param thread  the conversation's channel thread; null for any other place
     * @param channel the channel the call names, as {@code SystemTools.check} normalized it
     * @param read    the thread the call names; null for the channel's own messages
     */
    static Decision.@Nullable Refused outsideThread(@Nullable ThreadPlace thread, SystemInfo system, String name,
            String tool, @Nullable String channel, @Nullable String read, Names names) {
        if (thread == null || system.kind() != SystemKind.SLACK || !FixedTools.HISTORY.equals(tool)
                || system.id().equals(thread.systemId()) && thread.channelId().equals(channel)
                        && thread.threadId().equals(read)) {
            return null;
        }
        String why = "this conversation started in a channel, where anyone may ask, so the account the trigger lends "
                + "reads no other thread nor channel";
        if (!system.id().equals(thread.systemId())) {
            return new Decision.Refused(Step.BINDING, TOOL_DENIED, quoted(name) + " reads nothing here: " + why
                    + ", and this thread is not on " + names.system() + ". Tell " + names.person() + " in your "
                    + "summary when your work needs it.");
        }
        return new Decision.Refused(Step.BINDING, TOOL_DENIED, quoted(name) + " reads only the thread you were asked "
                + "in, " + thread.threadId() + " of " + thread.channelId() + ": " + why + ". Call it with \"channel\": \""
                + thread.channelId() + "\" and \"thread\": \"" + thread.threadId() + "\", and tell " + names.person()
                + " in your summary when your work needs more.");
    }

    /**
     * The description a run lists the tool with: the system's, then whether each call waits for an approval, then whom
     * it runs as once bound (§5.4), within the protocol's 4000 characters.
     */
    static String description(ToolInfo tool, Decision decision, BindingView binding, Names names) {
        String whom = switch (binding.state()) {
            case UNBOUND -> "Not connected yet: the first call uses " + names.person() + "'s account on "
                    + names.system() + ", or asks " + names.person() + " to connect one.";
            case WAITING -> names.system() + " is not connected for this conversation yet: a call waits until "
                    + names.person() + " connects it.";
            case GONE, NO_ROW -> "";
            case OPEN -> names.system() + " needs no account: calls run without a credential.";
            case BOUND -> binding.groupName() == null && binding.holderName() != null
                    ? "Runs as " + binding.holderName() + "'s account, chosen by the trigger"
                            + (binding.access() == GrantInfo.Access.READ ? ", read-only." : ".")
                    : binding.viaGrant()
                    ? "Runs through " + binding.holderName() + " via " + binding.groupName()
                            + (binding.access() == GrantInfo.Access.READ ? ", read-only." : ".")
                    : "Runs as " + names.person() + "'s own account.";
        };
        String ask = decision instanceof Decision.Ask ? "Each call waits for " + names.person() + "'s approval. " : "";
        String tail = ask + whom;
        String own = tool.description() != null && !tool.description().isBlank() ? tool.description().strip()
                : tool.title() != null ? tool.title().strip() : "";
        int room = MAX_DESCRIPTION - tail.length() - 2;
        if (own.length() > room) {
            own = own.substring(0, Math.max(0, room - 1)).stripTrailing() + "…";
        }
        return own.isEmpty() ? tail : own + "\n\n" + tail;
    }

    /** Why a binding is gone, in words. */
    static String goneReason(BindingInfo.@Nullable Reason reason, Names names) {
        if (reason == null) {
            return "its account is gone";
        }
        return switch (reason) {
            case GRANT_REMOVED -> "the grant it used was withdrawn";
            case LEFT_GROUP -> names.person() + " left the group of the grant it used";
            case IDENTITY_CHANGED -> "its account is another identity on " + names.system() + " now";
            case HOLDER_LEFT -> "the holder of its account left";
            case SYSTEM_REMOVED -> "the system was removed";
            default -> "its account is gone";
        };
    }

    /** Why a binding waits, in words. */
    static String waitingReason(BindingInfo.@Nullable Reason reason, Names names) {
        if (reason == null) {
            return names.person() + " connects it";
        }
        return switch (reason) {
            case NONE -> names.person() + " connects an account";
            case RECONNECT_YOURS -> names.person() + " signs their account in again";
            case RECONNECT_COMPANY -> "an admin signs the granted account in again";
            case CHOOSE -> names.person() + " reconnects their own account or uses a grant";
            case PICK_GRANT -> names.person() + " picks a grant";
            case SUSPENDED -> "an admin resumes the account's holder";
            case SYSTEM_OFF -> "an admin switches personal MCP servers on";
            default -> names.person() + " connects it";
        };
    }

    static String quoted(String name) {
        return "`" + name + "`";
    }
}
