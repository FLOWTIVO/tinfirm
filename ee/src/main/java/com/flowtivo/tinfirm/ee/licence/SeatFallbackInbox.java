package com.flowtivo.tinfirm.ee.licence;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.SeatFallbacks;
import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.api.model.InboxSeatFallback;
import com.flowtivo.tinfirm.shared.api.model.Role;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The admins' {@code SEAT_FALLBACK} items (ADR-0077, decision 5): a new user of an identity provider who joined as
 * VIEWER because every seat was taken, while they are a Viewer still and for 30 days at most. The admins may dismiss
 * it: giving them a seat role, once a seat is free, is theirs to choose.
 */
@Component
class SeatFallbackInbox implements InboxItems {

    static final Duration SHOWN = Duration.ofDays(30);

    private final SeatFallbacks fallbacks;

    private final UserDirectory users;

    private final TimeSource time;

    SeatFallbackInbox(SeatFallbacks fallbacks, UserDirectory users, TimeSource time) {
        this.fallbacks = fallbacks;
        this.users = users;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Item> itemsFor(Caller caller, int limit) {
        if (!caller.admin()) {
            return List.of();
        }
        List<SeatFallbacks.Fallback> joined = fallbacks.since(caller.companyId(), time.now().minus(SHOWN), limit);
        Map<java.util.UUID, UserSummary> names = users.findAllById(joined.stream()
            .map(SeatFallbacks.Fallback::userId)
            .toList());
        return joined.stream().map(fallback -> {
            UserSummary user = names.get(fallback.userId());
            return new Item(fallback.joinedAt(), new InboxItem(InboxItemKind.SEAT_FALLBACK,
                    fallback.joinedAt().atOffset(ZoneOffset.UTC))
                .seatFallback(new InboxSeatFallback(fallback.userId(),
                        user != null ? user.displayName() : "deleted user", Role.fromValue(fallback.role().name()))
                    .provider(fallback.provider()))
                .noticeKey(InboxItems.key(InboxItemKind.SEAT_FALLBACK, fallback.userId(), fallback.joinedAt()))
                .dismissible(true));
        }).toList();
    }
}
