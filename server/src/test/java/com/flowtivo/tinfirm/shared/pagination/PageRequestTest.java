package com.flowtivo.tinfirm.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

class PageRequestTest {

    @Test
    void cursorsRoundTripAnyKeys() {
        UUID id = UUID.randomUUID();
        List<String> keys = List.of("Acme. Inc | \"weird\" ☃", id.toString());
        Page<String> page = Page.of(List.of("a", "b", "c"), new PageRequest(2, List.of()), row -> keys);

        assertThat(page.items()).containsExactly("a", "b");
        PageRequest next = PageRequest.of(2, page.nextCursor());
        assertThat(next.key(0, 2)).isEqualTo(keys.getFirst());
        assertThat(next.uuidKey(1, 2)).isEqualTo(id);
        assertThat(Page.of(List.of("a", "b"), new PageRequest(2, List.of()), row -> keys).nextCursor()).isNull();
    }

    @Test
    void defaultsAndLimits() {
        assertThat(PageRequest.of(null, null).limit()).isEqualTo(50);
        assertThat(PageRequest.of(null, "").firstPage()).isTrue();
        assertThat(PageRequest.of(10, null).fetchSize()).isEqualTo(11);
        assertThatThrownBy(() -> PageRequest.of(0, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PageRequest.of(201, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void invalidCursorsAreValidationErrors() {
        for (String cursor : new String[] { "not base64!", "x".repeat(3000) }) {
            assertThatThrownBy(() -> PageRequest.of(10, cursor)).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.code()).isEqualTo(ProblemCode.VALIDATION_FAILED));
        }
        PageRequest wrongShape = PageRequest.of(10, CursorCodec.encode(List.of("only-one")));
        assertThatThrownBy(() -> wrongShape.key(0, 2)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> wrongShape.uuidKey(0, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> wrongShape.longKey(0, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> wrongShape.instantKey(0, 1)).isInstanceOf(ApiException.class);
    }
}
