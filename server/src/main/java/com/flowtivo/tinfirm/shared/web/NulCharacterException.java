package com.flowtivo.tinfirm.shared.web;

import java.io.IOException;
import java.io.Serial;

/**
 * A request's JSON body holds U+0000 in a string (the escape {@code \u0000}), or a raw NUL byte, found while the
 * body is read ({@link RequestInputFilter}). PostgreSQL refuses U+0000 in {@code text} and {@code jsonb}, so no
 * string of a request may hold it. Thrown from the body's {@code read}, so that the reader of the body fails as for
 * any other body it cannot read: Spring MVC's converters as {@code 400 VALIDATION_FAILED}.
 */
public final class NulCharacterException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The problem's detail, the same for a parameter, a part and a JSON body. */
    public static final String DETAIL = "A text of the request holds the character U+0000 (NUL), which no text may hold";

    NulCharacterException() {
        super(DETAIL, null);
    }
}
