package com.flowtivo.tinfirm.conversation;

/**
 * Through which surface a person reads and acts on conversations (the package 6 contract, §5.7):
 * <ul>
 * <li>{@code APP}: the web app and its REST API. The caller sees what {@link Conversations#maySee} allows: their own
 * conversations, and as OWNER every personal conversation in full.</li>
 * <li>{@code CLIENT}: a person's AI client through the MCP endpoint, a personal space acting as the person (the design
 * record, Part 1). The caller sees only the conversations they are the person of, OWNER included (open point 11),
 * and a trigger's conversations when they are the trigger's author or in its team (open point 15). Another person's
 * conversation is {@code 404}, never {@code CONVERSATION_BOUND}. A conversation it starts has the origin
 * {@code CLIENT}; a team's approval is not decided through it ({@code DECIDE_IN_APP}).</li>
 * </ul>
 */
public enum ConversationView {
    APP, CLIENT
}
