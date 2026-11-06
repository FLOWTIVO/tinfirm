package com.flowtivo.tinfirm.trigger;

/**
 * What a row of the write-back outbox writes ({@code outbox.kind}, the package 4 contract, §3.13): a comment's
 * Markdown, a transition's or a label's name, the close, a document, a link. Package 5 adds the chat's kinds, which its
 * bot writes (the package 5 contract, §3.11): a post, the update of a card the outbox posted, a document, a reaction to
 * a message taken.
 */
public enum OutboxKind {
    COMMENT, TRANSITION, LABEL_ADD, LABEL_REMOVE, CLOSE, ATTACHMENT, REMOTE_LINK, CHAT_POST, CHAT_UPDATE, CHAT_FILE,
    CHAT_REACTION;

    /** Whether a chat's bot writes it (package 5). */
    public boolean chat() {
        return this == CHAT_POST || this == CHAT_UPDATE || this == CHAT_FILE || this == CHAT_REACTION;
    }
}
