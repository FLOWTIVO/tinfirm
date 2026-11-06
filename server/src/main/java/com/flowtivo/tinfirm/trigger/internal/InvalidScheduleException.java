package com.flowtivo.tinfirm.trigger.internal;

import java.io.Serial;

/** A cron or time zone that cannot work; the message says why, for the person who typed it. */
public class InvalidScheduleException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    InvalidScheduleException(String message) {
        super(message);
    }
}
