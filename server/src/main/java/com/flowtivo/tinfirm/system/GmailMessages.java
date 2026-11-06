package com.flowtivo.tinfirm.system;

/**
 * Tinfirm's calls to the Gmail API for the Gmail add-on (ADR-0083, ADR-0084), for the {@code mcp} module: the message
 * the add-on was opened on, its content, its attachments and its reply headers, and a draft reply in its thread. Only
 * the add-on, a paid feature (ADR-0077, decision 4, {@code GMAIL_ADDON}), calls them, so their implementation is under
 * {@code ee/} (decision 1): without it, no bean implements this. Every call goes through the outbound client and its
 * guard, to the Gmail API's fixed endpoint only. The tokens are the caller's, revealed for this call only; they are
 * never logged, and never in an answer. A call runs outside any database transaction ({@link IllegalStateException}
 * inside one) and answers a value, never an exception.
 */
public interface GmailMessages {

    /**
     * One message, as the Gmail API answers {@code users.messages.get} with {@code format=full}, as JSON: with the
     * person's OAuth token of the add-on and the event's token of that message ({@code X-Goog-Gmail-Access-Token}),
     * which reach that message only.
     *
     * @param messageId Gmail's id of the message: letters, digits, {@code -} and {@code _}, at most 64 ({@code INVALID}
     *                  otherwise)
     */
    CallResult<String> message(String userToken, String messageToken, String messageId);

    /**
     * The headers a reply needs of the message ({@code format=metadata}: {@code Subject}, {@code From},
     * {@code Reply-To}, {@code To}, {@code Cc}, {@code Message-ID}, {@code References}), as JSON.
     */
    CallResult<String> messageHeaders(String userToken, String messageToken, String messageId);

    /**
     * One attachment of the message, its bytes ({@code users.messages.attachments.get}); {@code INVALID} when it is
     * larger than {@code maxBytes}.
     *
     * @param attachmentId Gmail's id of the attachment: letters, digits, {@code -} and {@code _}
     */
    CallResult<byte[]> attachment(String userToken, String messageToken, String messageId, String attachmentId,
            long maxBytes);

    /** A draft Gmail created: its id ({@code r…}), its message's id and its thread's. */
    record Draft(String id, String messageId, String threadId) {
    }

    /**
     * A draft in the thread ({@code users.drafts.create}), from a whole RFC 2822 message, with the person's token and
     * the message's, under the scope {@code gmail.addons.current.action.compose}. Never sends it.
     */
    CallResult<Draft> createDraft(String userToken, String messageToken, String threadId, byte[] rfc2822);
}
