package com.flowtivo.tinfirm.diagnostics;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.diagnostics.internal.Scrubber;

/**
 * Text a remote system sent, made fit for a record that is kept for good (the security review of 2026-10-27, I2): the
 * error reports' scrubber (ADR-0070, §2) masks what may be a token, a URL, an email, an address, a path or a number,
 * replaces NUL and other control characters, folds whitespace and cuts the text short. For a refusal's reason in the
 * audit chain, which is immutable once written.
 */
public final class RemoteText {

    private RemoteText() {
    }

    /** The text masked, folded and cut to 300 characters; empty for a text that is null or blank. */
    public static String scrubbed(@Nullable String text) {
        String masked = Scrubber.mask(text);
        return masked != null ? masked : "";
    }
}
