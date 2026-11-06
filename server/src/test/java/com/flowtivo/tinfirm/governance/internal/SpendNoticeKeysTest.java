package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.model.BudgetAlerts;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The keys of spend's notices (CONVENTIONS §15.7, §15.12): a budget's is its alert, so that a dismissed budget notice
 * comes back at the next threshold and in the next period and stays dismissed while only the spend moves; the
 * company's spend's is its month; the sync's is since when it fell back. Each only informs, so each is dismissible.
 */
class SpendNoticeKeysTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID USER = UUID.randomUUID();

    private static final UUID MEMBER = UUID.randomUUID();

    private static final Instant OCTOBER = Instant.parse("2026-10-15T09:00:00Z");

    private final BudgetAlerts alerts = mock(BudgetAlerts.class);

    private final Principals principals = mock(Principals.class);

    private final SpendStore store = mock(SpendStore.class);

    private final InboxItems.Caller member = new InboxItems.Caller(COMPANY, USER, MEMBER, false);

    private final InboxItems.Caller admin = new InboxItems.Caller(COMPANY, UUID.randomUUID(), UUID.randomUUID(), true);

    private SpendInboxItems at(Instant now) {
        return new SpendInboxItems(alerts, principals, mock(RunReads.class), mock(TriggerSpend.class), store,
                new TimeSource(Clock.fixed(now, ZoneOffset.UTC)));
    }

    private void standing(BudgetAlerts.Alert alert) {
        when(alerts.open(COMPANY)).thenReturn(List.of(alert));
        when(principals.findAll(eq(COMPANY), anyCollection())).thenReturn(Map.of(MEMBER,
                new PrincipalInfo(MEMBER, COMPANY, PrincipalKind.MEMBER, USER, null, PrincipalStatus.ACTIVE)));
        when(principals.displayNames(eq(COMPANY), anyCollection())).thenReturn(Map.of(MEMBER, "Alice Novak"));
    }

    private static BudgetAlerts.Alert alert(int threshold, String spent, Instant crossedAt) {
        return new BudgetAlerts.Alert(MEMBER, threshold, new BigDecimal(spent), new BigDecimal("25"),
                crossedAt.plus(Duration.ofDays(20)), crossedAt);
    }

    private InboxItem only(SpendInboxItems items, InboxItems.Caller caller) {
        List<InboxItems.Item> found = items.itemsFor(caller, 100);
        assertThat(found).hasSize(1);
        return found.getFirst().item();
    }

    @Test
    void aBudgetsKeyIsItsAlertSoTheNextThresholdAndTheNextPeriodComeBack() {
        Instant crossed = OCTOBER.minus(Duration.ofHours(3));
        standing(alert(80, "20.5", crossed));
        InboxItem at80 = only(at(OCTOBER), member);
        assertThat(at80.getKind()).isEqualTo(InboxItemKind.BUDGET);
        assertThat(at80.getNoticeKey()).isEqualTo("BUDGET:" + MEMBER + ":80:" + crossed.toEpochMilli());
        assertThat(at80.getDismissible()).isTrue();
        assertThat(InboxItems.KEY.matcher(at80.getNoticeKey()).matches()).isTrue();

        // More spend under the same threshold: the same notice, the same key.
        standing(alert(80, "23", crossed));
        assertThat(only(at(OCTOBER.plus(Duration.ofHours(1))), member).getNoticeKey()).isEqualTo(at80.getNoticeKey());

        // 100 % crossed: another key.
        standing(alert(100, "25", OCTOBER));
        assertThat(only(at(OCTOBER), member).getNoticeKey()).isNotEqualTo(at80.getNoticeKey())
            .isEqualTo("BUDGET:" + MEMBER + ":100:" + OCTOBER.toEpochMilli());

        // The next period's 80 %: another alert, another key.
        Instant nextPeriod = crossed.plus(Duration.ofDays(30));
        standing(alert(80, "20", nextPeriod));
        assertThat(only(at(nextPeriod), member).getNoticeKey()).isNotEqualTo(at80.getNoticeKey());
    }

    @Test
    void theCompanysSpendIsKeyedByItsMonth() {
        when(store.companyAlert(COMPANY, LocalDate.parse("2026-10-01"))).thenReturn(Optional.of(
                new SpendStore.CompanyAlert(LocalDate.parse("2026-10-01"), OCTOBER, new BigDecimal("1250"),
                        new BigDecimal("1000"))));
        when(store.companyAlert(COMPANY, LocalDate.parse("2026-11-01"))).thenReturn(Optional.of(
                new SpendStore.CompanyAlert(LocalDate.parse("2026-11-01"), Instant.parse("2026-11-03T08:00:00Z"),
                        new BigDecimal("1010"), new BigDecimal("1000"))));

        InboxItem october = only(at(OCTOBER), admin);
        assertThat(october.getNoticeKey()).isEqualTo("COMPANY_SPEND:2026-10");
        assertThat(october.getDismissible()).isTrue();
        assertThat(only(at(Instant.parse("2026-10-31T23:00:00Z")), admin).getNoticeKey())
            .isEqualTo("COMPANY_SPEND:2026-10");
        assertThat(only(at(Instant.parse("2026-11-04T08:00:00Z")), admin).getNoticeKey())
            .isEqualTo("COMPANY_SPEND:2026-11");
        // A member reads none of it.
        assertThat(at(OCTOBER).itemsFor(member, 100)).isEmpty();
    }

    @Test
    void theSyncIsKeyedBySinceWhenItFellBack() {
        when(store.relayRuns(any(), any())).thenReturn(11);
        InboxItem today = only(at(OCTOBER), admin);
        assertThat(today.getKind()).isEqualTo(InboxItemKind.SPEND_SYNC);
        assertThat(today.getNoticeKey())
            .isEqualTo("SPEND_SYNC:" + Instant.parse("2026-10-15T00:00:00Z").toEpochMilli());
        assertThat(today.getDismissible()).isTrue();
        // The next day's count is another notice.
        assertThat(only(at(OCTOBER.plus(Duration.ofDays(1))), admin).getNoticeKey())
            .isNotEqualTo(today.getNoticeKey());
    }

    @Test
    void aKeyKeepsToItsPattern() {
        UUID trigger = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-15T09:00:00.123456Z");
        String key = InboxItems.key(InboxItemKind.TRIGGER, trigger, "PAUSED", null, at, "a key/with:odd chars");
        assertThat(key).isEqualTo("TRIGGER:" + trigger + ":PAUSED:-:" + at.toEpochMilli() + ":a-key-with-odd-chars");
        assertThat(InboxItems.KEY.matcher(key).matches()).isTrue();
        assertThat(InboxItems.key(InboxItemKind.BUDGET, "x".repeat(300))).hasSize(200);
    }
}
