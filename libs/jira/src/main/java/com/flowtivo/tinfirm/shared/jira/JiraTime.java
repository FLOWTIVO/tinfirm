package com.flowtivo.tinfirm.shared.jira;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/** Jira's timestamps, the same in Data Center and Cloud: {@code 2026-10-03T10:15:30.000+0200}. */
public final class JiraTime {

    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxx",
            Locale.ROOT);

    private JiraTime() {
    }

    /** Jira's form, or an ISO offset date-time such as {@code 2026-10-03T08:15:30Z}; null when neither. */
    public static @Nullable Instant parse(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, JIRA_TIME).toInstant();
        }
        catch (DateTimeParseException ex) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            }
            catch (DateTimeParseException again) {
                return null;
            }
        }
    }
}
