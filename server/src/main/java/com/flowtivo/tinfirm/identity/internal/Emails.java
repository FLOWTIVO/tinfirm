package com.flowtivo.tinfirm.identity.internal;

import java.util.Locale;

/** Emails are stored and compared in lower case. */
final class Emails {

    private Emails() {
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
