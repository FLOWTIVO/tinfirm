package com.flowtivo.tinfirm.ee.licence;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.shared.api.model.Edition;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.api.model.InboxLicence;
import com.flowtivo.tinfirm.shared.licence.LicenceState;
import com.flowtivo.tinfirm.shared.licence.LicenceStatus;

/**
 * The {@code LICENCE} item of the inbox of OWNER and ADMIN (ADR-0077, decision 7): from 30 days before the licence's
 * end, through its grace, and for the 90 days the audit's export stays after it expired. The instance admins read the
 * same on the licence page ({@code GET /api/licence}). Dismissible: an instance admin applies the key; the notice comes
 * back when the state changes.
 */
@Component
class LicenceInbox implements InboxItems {

    private final LicenceService licence;

    LicenceInbox(LicenceService licence) {
        this.licence = licence;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Item> itemsFor(Caller caller, int limit) {
        if (!caller.admin() || limit < 1) {
            return List.of();
        }
        LicenceStatus status = licence.status();
        LocalDate today = licence.today();
        boolean shown = status.state() == LicenceState.EXPIRING || status.state() == LicenceState.GRACE
                || status.state() == LicenceState.EXPIRED && status.auditExportUntil() != null
                        && !today.isAfter(status.auditExportUntil());
        if (!shown || status.endsAt() == null) {
            return List.of();
        }
        LocalDate endsAt = Objects.requireNonNull(status.endsAt());
        LocalDate since = switch (status.state()) {
            case EXPIRING -> endsAt.minusDays(Licences.NOTICE_DAYS);
            case GRACE -> endsAt.plusDays(1);
            default -> Objects.requireNonNull(status.graceEndsAt()).plusDays(1);
        };
        java.time.Instant at = since.atStartOfDay(ZoneOffset.UTC).toInstant();
        com.flowtivo.tinfirm.shared.licence.Edition edition = status.lapsedEdition() != null ? status.lapsedEdition()
                : status.edition();
        return List.of(new Item(at, new InboxItem(InboxItemKind.LICENCE, at.atOffset(ZoneOffset.UTC))
            .licence(new InboxLicence(com.flowtivo.tinfirm.shared.api.model.LicenceState.fromValue(
                    status.state().name()), endsAt, Objects.requireNonNull(status.graceEndsAt()))
                .edition(Edition.fromValue(edition.name()))
                .auditExportUntil(status.auditExportUntil()))
            .noticeKey(InboxItems.key(InboxItemKind.LICENCE, status.state().name(), endsAt))
            .dismissible(true)));
    }
}
