package com.flowtivo.tinfirm.conversation;

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;

/**
 * Port: items of the inbox that a higher module keeps (package 5's port; the package 6 contract, §5.11), which
 * {@code GET /inbox} shows beside the conversation module's own, longest waiting first. Looked up when used, so that
 * the conversation module depends on none of its implementations; package 6's governance module serves the kinds
 * {@code BUDGET}, {@code COMPANY_SPEND}, {@code SPEND_SYNC} and {@code DEPARTURE}, package 4's trigger module
 * {@code TRIGGER}, package 5's chat module {@code CHAT_LINKED} and {@code CHAT_SUBSCRIPTION_SILENT}. Runs in the caller's
 * tenant context and transaction (read-only for {@code GET /inbox}, the dismissal's otherwise); an implementation shows
 * the caller only what is theirs to see.
 * <p>
 * Every item is a notice and carries its {@code noticeKey} ({@link #key}): its kind, what it is about and the condition
 * that raised it, so that the key stays while the condition holds and changes with it, and a member's dismissal of it
 * (CONVENTIONS §15.7) hides the notice until then. Each item says whether the caller may dismiss it
 * ({@code dismissible}): a notice that only informs the caller, never one the caller is the one to act on.
 */
public interface InboxItems {

    /** What a notice's key may hold: the API's pattern of {@code noticeKey}. */
    Pattern KEY = Pattern.compile("^[A-Za-z0-9_.:-]{1,200}$");

    /** The caller's items, at most {@code limit} of each kind. */
    List<Item> itemsFor(Caller caller, int limit);

    /**
     * A notice's key: its kind and the parts of its condition, joined by {@code :}. An instant is written as its epoch
     * milliseconds, a temporal value (a month) as its text; any character outside the key's pattern becomes {@code -}.
     */
    static String key(InboxItemKind kind, @Nullable Object... parts) {
        StringJoiner key = new StringJoiner(":");
        key.add(kind.getValue());
        for (Object part : parts) {
            String text = part == null ? "-"
                    : part instanceof Instant instant ? Long.toString(instant.toEpochMilli())
                    : part instanceof TemporalAccessor temporal ? temporal.toString()
                    : part instanceof UUID id ? id.toString()
                    : part.toString();
            key.add(text.isEmpty() ? "-" : text.replaceAll("[^A-Za-z0-9_.-]", "-"));
        }
        String joined = key.toString();
        return joined.length() > 200 ? joined.substring(0, 200) : joined;
    }

    /**
     * Who reads the inbox.
     *
     * @param principalId the caller's principal in the company; null for an instance admin who is not a member
     * @param admin       OWNER, ADMIN, or an instance admin acting in the company
     */
    record Caller(UUID companyId, UUID userId, @Nullable UUID principalId, boolean admin) {
    }

    /** An item and since when it waits. */
    record Item(Instant since, InboxItem item) {
    }
}
