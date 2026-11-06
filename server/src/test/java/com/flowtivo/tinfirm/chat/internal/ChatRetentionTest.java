package com.flowtivo.tinfirm.chat.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.chat.ChatTestBench;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.FakeSlack;

/**
 * The chat's records kept for a fixed time (the package 5 contract, §3.3, §3.7; the package 6 contract's seam
 * {@code RetainedRecords}): an event goes a day after it arrived, a chat user 90 days after they were last seen, each
 * swept in the company's tenant context and nothing earlier.
 */
class ChatRetentionTest extends ChatTestBench {

    @Autowired
    ChatEventRetention events;

    @Autowired
    ChatUserRetention users;

    @Autowired
    SystemTransactions transactions;

    @Test
    void sweepsEventsAfterADayAndChatUsersAfterNinetyDays() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        Map<String, Object> message = workspace.directMessage(workspace.member("Rhea"), "hello", null);
        workspace.deliver(port, slack.systemId(), message);
        awaitEvent(bench.id(), slack.systemId(), (String) message.get("event_id"), "AWAITING_LINK");
        await().atMost(WAIT).until(() -> !rows(bench.id(), "SELECT 1 FROM chat_user WHERE system_id = ?",
                slack.systemId()).isEmpty());
        Instant now = Instant.now();

        assertThat(events.kind()).isEqualTo("chatEvents");
        assertThat(users.kind()).isEqualTo("chatUsers");
        assertThat(transactions.inCompany(bench.id(), () -> events.sweep(bench.id(), now))).isZero();
        assertThat(transactions.inCompany(bench.id(), () -> users.sweep(bench.id(), now.plus(Duration.ofDays(89)))))
            .isZero();
        // Another company's sweep reaches nothing of this one.
        Bench other = bench(false);
        assertThat(transactions.inCompany(other.id(), () -> events.sweep(other.id(), now.plus(Duration.ofDays(2)))))
            .isZero();
        assertThat(events(bench.id(), slack.systemId())).hasSize(1);

        assertThat(transactions.inCompany(bench.id(), () -> events.sweep(bench.id(), now.plus(Duration.ofDays(2)))))
            .isEqualTo(1);
        assertThat(events(bench.id(), slack.systemId())).isEmpty();
        assertThat(transactions.inCompany(bench.id(), () -> users.sweep(bench.id(), now.plus(Duration.ofDays(91)))))
            .isEqualTo(1);
        assertThat(rows(bench.id(), "SELECT 1 FROM chat_user WHERE system_id = ?", slack.systemId())).isEmpty();
    }
}
