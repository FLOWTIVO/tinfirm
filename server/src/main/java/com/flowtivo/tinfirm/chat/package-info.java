/**
 * Chats (the package 5 contract, part a): Slack workspaces and Microsoft Teams tenants as systems, each with one
 * Tinfirm bot held as a service identity's account; what arrives from them, verified before it is read, acknowledged at
 * once and handled in each thread's order ({@code chat_event}); who is served (members of the workspace, never guests,
 * other organisations or bots); linking chat users to members (Entra ID on Teams, else a sign-in link bound to one
 * chat user); and direct messages to the bot as personal conversations that act exactly as the app's.
 * <p>
 * It reaches the {@code conversation} module only through its public API ({@code PersonalConversations},
 * {@code PlaceConversations}, {@code PlaceDecisions}, {@code PlaceFiles}, its events) and implements its ports
 * {@code PlacePrompts} and {@code InboxItems}; the platforms only through the {@code system} module's
 * {@code ChatCalls}, behind the outbound guard. Package 6's switches reach it as the company's stop
 * ({@code CompanySwitches}), a system's pause ({@code SystemInfo.paused}) and a principal's status.
 * <p>
 * Package 5b: every answer, card and notice of a chat thread is a row of the {@code trigger} module's outbox
 * ({@code PlaceOutbox}), written by the system's bot ({@code ChatPlaceWriter}, the {@code BOT} writer of
 * {@code CHAT_THREAD}); mentions in a channel fire its channel triggers ({@code ChannelMentions}, through
 * {@code ChannelTriggers}), and the module answers the trigger module's port {@code ChannelSources} (the bot's state, the
 * channels, the author's membership). Nothing depends on this module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Chats")
package com.flowtivo.tinfirm.chat;
