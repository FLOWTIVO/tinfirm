package com.flowtivo.tinfirm.system;

/**
 * Tinfirm's call to Google for the Gmail add-on (ADR-0083), for the {@code mcp} module: the keys Google signs its ID
 * tokens with, which the core reads to verify each of Google's requests, in every edition. The Gmail API's calls are
 * {@link GmailMessages}. The call goes through the outbound client and its guard, to Google's fixed endpoint only. It
 * runs outside any database transaction ({@link IllegalStateException} inside one) and answers a value, never an
 * exception.
 */
public interface GmailCalls {

    /** Google's JWK set of its ID tokens' keys, as JSON. */
    CallResult<String> googleKeys();
}
