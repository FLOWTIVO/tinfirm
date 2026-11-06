package com.flowtivo.tinfirm.governance.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.RetentionPeriods;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * A company's retention periods in days (the package 6 contract, §3.7; open point 16), with the ranges the database
 * also checks ({@code retention_*_ck}).
 *
 * @param runEventsDays   null: run events are kept (Community, ADR-0077 decision 8, or the OWNER's choice)
 * @param chatContentDays null: chats' content is kept, only while all content is
 * @param contentDays null: content is kept
 * @param filesDays   null: files are kept
 * @param memoryDays  how long agents' memory notes not used are kept (ADR-0063); null: until they are deleted
 */
record Periods(@Nullable Integer runEventsDays, @Nullable Integer chatContentDays, @Nullable Integer contentDays,
        @Nullable Integer filesDays, @Nullable Integer memoryDays) {

    /** Today's periods: the starting values under a Pro or Enterprise licence (ADR-0077, decision 8). */
    static final Periods DEFAULTS = new Periods(90, 30, 365, 365, 730);

    /**
     * An instant before any record: a period that keeps everything selects what ended before it, nothing. The purge
     * APIs take an instant for the chats' and the run events' periods, so "keep" is this one.
     */
    static final Instant NOTHING_ENDED_BEFORE = Instant.EPOCH;

    /**
     * Community's retention (ADR-0077, decision 8): conversations, transcripts, files and run events are kept; the
     * memory notes' own period stays.
     */
    static Periods keepEverything(@Nullable Integer memoryDays) {
        return new Periods(null, null, null, null, memoryDays);
    }

    /**
     * The periods a request names, not checked yet ({@link #validated}): {@code memoryDays} absent keeps the stored
     * period (a client from before it sends none), and 0 keeps notes until they are deleted.
     */
    static Periods of(RetentionPeriods request, Periods stored) {
        Integer memoryDays = request.getMemoryDays() == null ? stored.memoryDays()
                : request.getMemoryDays() == 0 ? null : request.getMemoryDays();
        return new Periods(request.getRunEventsDays(), request.getChatContentDays(), request.getContentDays(),
                request.getFilesDays(), memoryDays);
    }

    /** The periods, or {@code 400 RETENTION_INVALID} naming what is out of range. */
    Periods validated() {
        if (runEventsDays != null && (runEventsDays < 7 || runEventsDays > 3650)) {
            throw invalid("runEventsDays must be 7 to 3650 days, or null to keep run events");
        }
        if (chatContentDays != null && (chatContentDays < 1 || chatContentDays > 3650)) {
            throw invalid("chatContentDays must be 1 to 3650 days, or null to keep chats' content");
        }
        if (chatContentDays == null && contentDays != null) {
            throw invalid("chatContentDays keeps chats' content only while contentDays keeps all content: chats' "
                    + "content is never kept longer than other content");
        }
        if (contentDays != null && (contentDays < 30 || contentDays > 3650)) {
            throw invalid("contentDays must be 30 to 3650 days, or null to keep content");
        }
        if (filesDays != null && (filesDays < 7 || filesDays > 3650)) {
            throw invalid("filesDays must be 7 to 3650 days, or null to keep files");
        }
        if (memoryDays != null && (memoryDays < 30 || memoryDays > 3650)) {
            throw invalid("memoryDays must be 30 to 3650 days, or 0 to keep memory notes until they are deleted");
        }
        if (contentDays != null && chatContentDays != null && chatContentDays > contentDays) {
            throw invalid("chatContentDays must not be longer than contentDays: chats' content is never kept longer"
                    + " than other content");
        }
        return this;
    }

    Instant runEventsBefore(Instant now) {
        return runEventsDays != null ? now.minus(Duration.ofDays(runEventsDays)) : NOTHING_ENDED_BEFORE;
    }

    Instant chatBefore(Instant now) {
        return chatContentDays != null ? now.minus(Duration.ofDays(chatContentDays)) : NOTHING_ENDED_BEFORE;
    }

    @Nullable Instant contentBefore(Instant now) {
        return contentDays != null ? now.minus(Duration.ofDays(contentDays)) : null;
    }

    @Nullable Instant filesBefore(Instant now) {
        return filesDays != null ? now.minus(Duration.ofDays(filesDays)) : null;
    }

    @Nullable Instant memoryBefore(Instant now) {
        return memoryDays != null ? now.minus(Duration.ofDays(memoryDays)) : null;
    }

    /** For the audit: the five periods ({@code null} keeps). */
    Map<String, @Nullable Object> audit() {
        Map<String, @Nullable Object> details = new LinkedHashMap<>();
        details.put("runEventsDays", runEventsDays);
        details.put("chatContentDays", chatContentDays);
        details.put("contentDays", contentDays);
        details.put("filesDays", filesDays);
        details.put("memoryDays", memoryDays);
        return details;
    }

    private static ApiException invalid(String detail) {
        return ApiException.badRequest(ProblemCode.RETENTION_INVALID, detail);
    }
}
