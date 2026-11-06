package com.flowtivo.tinfirm.shared.pagination;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * A keyset page request: the page size and the sort key of the last row of the previous page.
 * Queries fetch {@link #fetchSize()} rows (one extra) to learn whether another page exists.
 *
 * @param limit the page size (1..200, default 50)
 * @param after the decoded cursor keys; empty for the first page
 */
public record PageRequest(int limit, List<String> after) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public PageRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validation("limit must be between 1 and " + MAX_LIMIT);
        }
        after = List.copyOf(after);
    }

    public static PageRequest of(@Nullable Integer limit, @Nullable String cursor) {
        return new PageRequest(limit != null ? limit : DEFAULT_LIMIT,
                cursor == null || cursor.isEmpty() ? List.of() : CursorCodec.decode(cursor));
    }

    public boolean firstPage() {
        return after.isEmpty();
    }

    public int fetchSize() {
        return limit + 1;
    }

    /** The cursor key at {@code index}, for a cursor produced by a query with {@code expectedKeys} keys. */
    public String key(int index, int expectedKeys) {
        if (after.size() != expectedKeys) {
            throw ApiException.validation("Invalid cursor");
        }
        return after.get(index);
    }

    public UUID uuidKey(int index, int expectedKeys) {
        try {
            return UUID.fromString(key(index, expectedKeys));
        }
        catch (IllegalArgumentException ex) {
            throw ApiException.validation("Invalid cursor");
        }
    }

    public long longKey(int index, int expectedKeys) {
        try {
            return Long.parseLong(key(index, expectedKeys));
        }
        catch (NumberFormatException ex) {
            throw ApiException.validation("Invalid cursor");
        }
    }

    public Instant instantKey(int index, int expectedKeys) {
        try {
            return Instant.parse(key(index, expectedKeys));
        }
        catch (DateTimeParseException ex) {
            throw ApiException.validation("Invalid cursor");
        }
    }
}
