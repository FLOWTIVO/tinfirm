package com.flowtivo.tinfirm.chat.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The order of the inbound rates (the security review of 2026-10-27, L7): a message counts against its sender first,
 * and only one within that rate against the system, so one chat user cannot starve the bot for the company; guests and
 * other organisations' users count in a small bucket of their own, never in the system's.
 */
class ChatReceiverTest {

    private static final String WORKSPACE = "T-ACME";

    private final ChatSystemInfo system = new ChatSystemInfo(new SystemInfo(UUID.randomUUID(), UUID.randomUUID(),
            "Slack", "slack", SystemKind.SLACK, "https://slack.com/api", null, null, false, false), null, WORKSPACE,
            "Acme", null, "app", "UBOT", null, null, null, "OK");

    private final ChatStore store = mock(ChatStore.class);

    private final ChatInbox inbox = mock(ChatInbox.class);

    private final List<Inbound> stored = new ArrayList<>();

    private ChatReceiver receiver;

    private int events;

    @BeforeEach
    void setUp() {
        // 5 messages a minute per user, 30 events a minute per system: the outsiders' bucket is 3.
        when(store.rates(system.companyId())).thenReturn(Optional.of(new ChatStore.Rates(5, 200, 30, 0)));
        SystemTransactions transactions = mock(SystemTransactions.class);
        when(transactions.inCompany(any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(inbox.store(any(), any())).thenAnswer(call -> {
            stored.add(call.getArgument(1));
            return ChatInbox.Stored.STORED;
        });
        ChatProperties properties = new ChatProperties(java.time.Duration.ofSeconds(1), java.time.Duration.ofMinutes(30),
                DataSize.ofMegabytes(1), 5, 200, 30, java.time.Duration.ofMinutes(10), java.time.Duration.ofHours(1),
                java.time.Duration.ofSeconds(5), 2, java.time.Duration.ofSeconds(30));
        // Mid-minute, so that every count of a test falls in one window.
        TimeSource time = new TimeSource(Clock.fixed(Instant.parse("2026-10-27T10:00:30Z"), ZoneOffset.UTC));
        InboundLimits limits = new InboundLimits(store, properties, transactions, time);
        receiver = new ChatReceiver(inbox, limits, mock(ChatSilence.class), new SimpleMeterRegistry());
    }

    private Inbound message(String user, Map<String, Object> payload) {
        events++;
        return new Inbound(Inbound.MESSAGE, "Ev" + events, user, "D1:" + events, BigDecimal.valueOf(events), 0,
                Inbound.RECEIVED, null, payload, WORKSPACE);
    }

    private Inbound last() {
        return stored.getLast();
    }

    @Test
    void oneChatUserCannotStarveTheSystem() {
        // One user sends far more than the system's 30 a minute: 5 pass, the rest are refused as theirs alone.
        for (int i = 0; i < 100; i++) {
            receiver.receive(system, message("U-LOUD", Map.of()), true);
        }
        assertThat(stored.stream().filter(event -> event.received() && !event.payload().containsKey("refusal"))
            .count()).isEqualTo(5);
        assertThat(stored.stream().filter(event -> "RATE_LIMITED".equals(event.reason())).count()).isEqualTo(94);
        // The first refusal is told to the sender (once a minute): handled, with its refusal in the payload.
        assertThat(stored.stream().filter(event -> "RATE_LIMITED".equals(event.payload().get("refusal"))).count())
            .isEqualTo(1);

        // Everyone else still reaches the bot: the system counted only the 5.
        for (int user = 0; user < 25; user++) {
            receiver.receive(system, message("U-" + user, Map.of()), true);
            assertThat(last().received()).as("user %s", user).isTrue();
        }
        // The system's own rate still holds: 5 + 25 = 30, the next member is refused.
        receiver.receive(system, message("U-LATE", Map.of()), true);
        assertThat(last().status()).isEqualTo(Inbound.REFUSED);
        assertThat(last().reason()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void anotherOrganisationsUsersHaveASmallBucketOfTheirOwn() {
        for (int i = 0; i < 5; i++) {
            receiver.receive(system, message("U-EXT-" + i, Map.of("userTeam", "T-OTHER")), true);
        }
        assertThat(stored.stream().filter(Inbound::received).count()).isEqualTo(3);
        assertThat(stored.stream().filter(event -> Inbound.REFUSED.equals(event.status())).count()).isEqualTo(2);

        // They spent none of the system's 30.
        for (int user = 0; user < 30; user++) {
            receiver.receive(system, message("U-" + user, Map.of("userTeam", WORKSPACE)), true);
            assertThat(last().received()).as("user %s", user).isTrue();
        }
    }

    @Test
    void aGuestTheWorkerCheckedCountsAsAnOutsider() {
        when(store.user(system.id(), "U-GUEST")).thenReturn(Optional.of(new ChatStore.ChatUser(system.companyId(),
                system.id(), "U-GUEST", null, WORKSPACE, "GUEST", "Guest", Map.of(), Instant.EPOCH, Instant.EPOCH,
                null, null, null)));
        for (int i = 0; i < 4; i++) {
            receiver.receive(system, message("U-GUEST", Map.of()), true);
        }
        assertThat(stored.stream().filter(Inbound::received).count()).isEqualTo(3);
    }
}
