package com.flowtivo.tinfirm.shared.pagination;

import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * One page of a keyset-paginated list.
 *
 * @param items      the rows of this page
 * @param nextCursor opaque cursor for the next page, or null on the last page
 */
public record Page<T>(List<T> items, @Nullable String nextCursor) {

    /**
     * Builds a page from rows fetched with {@link PageRequest#fetchSize()}: the extra row only signals that
     * another page exists and is dropped.
     *
     * @param keyOf the sort key of a row, in ORDER BY order
     */
    public static <T> Page<T> of(List<T> rows, PageRequest request, Function<T, List<String>> keyOf) {
        if (rows.size() <= request.limit()) {
            return new Page<>(List.copyOf(rows), null);
        }
        List<T> items = List.copyOf(rows.subList(0, request.limit()));
        return new Page<>(items, CursorCodec.encode(keyOf.apply(items.getLast())));
    }

    /**
     * The cursor that continues after a row with this sort key, in ORDER BY order: for a page that is not built by
     * {@link #of}, such as one filled by more than one fetch.
     */
    public static String cursorAfter(List<String> key) {
        return CursorCodec.encode(key);
    }

    public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
        return new Page<>(items.stream().<R>map(mapper).toList(), nextCursor);
    }
}
