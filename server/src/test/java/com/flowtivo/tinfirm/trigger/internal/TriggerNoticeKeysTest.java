package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;

/**
 * The keys of the triggers' notices (CONVENTIONS §15.7, §15.15): the trigger, the reason and the state that raised it,
 * so that a dismissed notice comes back when the trigger's state changes again; a notice the reader is the one to act
 * on (a widening to accept, a trigger to reassign, a broken account) is not dismissible.
 */
class TriggerNoticeKeysTest {

    private static final UUID TRIGGER = UUID.randomUUID();

    private static final Instant PAUSED_AT = Instant.parse("2026-10-15T09:00:00Z");

    private static InboxItem item(String reason, String detail, Object... condition) {
        return new TriggerInboxImpl.Need(PAUSED_AT, TRIGGER, "Triage new bugs", reason, detail, null,
                Arrays.asList(condition)).item().item();
    }

    @Test
    void aPauseIsKeyedByItsReasonAndItsTimeSoTheNextPauseComesBack() {
        InboxItem paused = item("PAUSED", "USER", "USER", PAUSED_AT);
        assertThat(paused.getKind()).isEqualTo(InboxItemKind.TRIGGER);
        assertThat(paused.getNoticeKey())
            .isEqualTo("TRIGGER:" + TRIGGER + ":PAUSED:USER:" + PAUSED_AT.toEpochMilli());
        assertThat(paused.getDismissible()).isTrue();
        assertThat(item("PAUSED", "USER", "USER", PAUSED_AT.plusSeconds(60)).getNoticeKey())
            .isNotEqualTo(paused.getNoticeKey());
        assertThat(item("PAUSED", "SYSTEM_PAUSED", "SYSTEM_PAUSED", PAUSED_AT).getNoticeKey())
            .isNotEqualTo(paused.getNoticeKey());
        assertThat(item("WAITING", "COMPANY_STOPPED", "COMPANY_STOPPED", PAUSED_AT).getNoticeKey())
            .isNotEqualTo(paused.getNoticeKey());
    }

    @Test
    void aDropOrASkipIsKeyedByTheLatestOneSoOnlyANewOneComesBack() {
        Instant latest = PAUSED_AT.plusSeconds(600);
        InboxItem dropped = new TriggerInboxImpl.Need(PAUSED_AT, TRIGGER, "Triage", "WRITE_BACK_DROPPED", null, 3,
                Arrays.asList((Object) latest)).item().item();
        InboxItem fewer = new TriggerInboxImpl.Need(PAUSED_AT.plusSeconds(3600), TRIGGER, "Triage",
                "WRITE_BACK_DROPPED", null, 2, Arrays.asList((Object) latest)).item().item();
        assertThat(dropped.getNoticeKey()).isEqualTo(fewer.getNoticeKey())
            .isEqualTo("TRIGGER:" + TRIGGER + ":WRITE_BACK_DROPPED:" + latest.toEpochMilli());
        assertThat(item("WRITE_BACK_DROPPED", null, latest.plusSeconds(1)).getNoticeKey())
            .isNotEqualTo(dropped.getNoticeKey());
        assertThat(item("FIRINGS_SKIPPED", null, latest).getDismissible()).isTrue();
    }

    @Test
    void whatTheReaderActsOnIsNotDismissible() {
        assertThat(item("WIDENING", "Version 6 of Analyst waits for your acceptance", 6).getDismissible()).isFalse();
        assertThat(item("TO_REASSIGN", "Its author left", PAUSED_AT).getDismissible()).isFalse();
        assertThat(item("PAUSED", "ACCOUNT_BROKEN", "ACCOUNT_BROKEN", PAUSED_AT).getDismissible()).isFalse();
        assertThat(item("WAITING", "RECONNECT_YOURS", "RECONNECT_YOURS", PAUSED_AT).getDismissible()).isFalse();
        for (String reason : List.of("CHANGED_BY_ADMIN", "POLL_WAITING", "FIRINGS_SKIPPED", "WRITE_BACK_DROPPED")) {
            assertThat(item(reason, null, PAUSED_AT).getDismissible()).as(reason).isTrue();
        }
        assertThat(item("WIDENING", "Version 6", 6).getNoticeKey()).isEqualTo("TRIGGER:" + TRIGGER + ":WIDENING:6");
        assertThat(InboxItems.KEY.matcher(item("POLL_WAITING", "Its poll waits", UUID.randomUUID(), PAUSED_AT)
            .getNoticeKey()).matches()).isTrue();
    }

    @Test
    void aReassignmentToAcceptIsTheNewAuthorsToActOnAndAnExposureOnlyInforms() {
        // H2: the new author accepts the trigger, which resumes it; the notice leaves once they have.
        InboxItem reassigned = item("TRIGGER_REASSIGNED_TO_YOU", "Ann reassigned the trigger to you", PAUSED_AT);
        assertThat(reassigned.getDismissible()).isFalse();
        assertThat(reassigned.getTrigger().getReason().getValue()).isEqualTo("TRIGGER_REASSIGNED_TO_YOU");
        // D2: the lender is told once per trigger and credential, keyed by both and when it was recorded.
        UUID credential = UUID.randomUUID();
        InboxItem exposed = item("CREDENTIAL_EXPOSED_TO_OPEN_SOURCE", "The trigger acts with the credential",
                credential, PAUSED_AT);
        assertThat(exposed.getDismissible()).isTrue();
        assertThat(exposed.getNoticeKey()).isEqualTo("TRIGGER:" + TRIGGER + ":CREDENTIAL_EXPOSED_TO_OPEN_SOURCE:"
                + credential + ":" + PAUSED_AT.toEpochMilli());
        assertThat(InboxItems.KEY.matcher(exposed.getNoticeKey()).matches()).isTrue();
    }
}
