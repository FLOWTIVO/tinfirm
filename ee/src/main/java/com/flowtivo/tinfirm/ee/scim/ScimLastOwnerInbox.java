package com.flowtivo.tinfirm.ee.scim;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.scim.ScimMembers;
import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The OWNER's {@code SCIM_LAST_OWNER} item (ADR-0087, decision 7): the company's identity provider tried, through
 * SCIM, to deactivate or delete them, its last active OWNER, and was refused. For 30 days, dismissible, and back at the
 * next refusal.
 */
@Component
class ScimLastOwnerInbox implements InboxItems {

    static final Duration SHOWN = Duration.ofDays(30);

    private final ScimMembers members;

    private final TimeSource time;

    ScimLastOwnerInbox(ScimMembers members, TimeSource time) {
        this.members = members;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Item> itemsFor(Caller caller, int limit) {
        if (!caller.admin() || caller.principalId() == null || limit < 1) {
            return List.of();
        }
        return members.lastOwnerRefusedSince(caller.companyId(), caller.userId(), time.now().minus(SHOWN))
            .map(at -> new Item(at, new InboxItem(InboxItemKind.SCIM_LAST_OWNER, at.atOffset(ZoneOffset.UTC))
                .noticeKey(InboxItems.key(InboxItemKind.SCIM_LAST_OWNER, at))
                .dismissible(true)))
            .stream()
            .toList();
    }
}
