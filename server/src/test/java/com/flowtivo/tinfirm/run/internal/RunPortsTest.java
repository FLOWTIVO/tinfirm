package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.QueueReasonCode;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyRole;

import tools.jackson.databind.json.JsonMapper;

/**
 * What stands in for a port the conversation module (package 3c) does not implement: every platform request
 * {@code REJECTED} with a clear code, and a run seen and cancelled by its person and OWNER only (I17); and the queue
 * reason's stored form.
 */
class RunPortsTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private final ActiveRun run = new ActiveRun(COMPANY, UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);

    @Test
    void untilTheConversationModuleTakesThemRequestsAreRejected() {
        RunPorts.RejectingRequests requests = new RunPorts.RejectingRequests();
        UUID requestId = UUID.randomUUID();
        RunnerMessages.RunRequestAck ask = requests.request(run, RunnerMessages.RequestKind.ASK, requestId,
                JsonMapper.builder().build().createObjectNode());
        assertThat(ask.status()).isEqualTo(RunnerMessages.RequestStatus.REJECTED);
        assertThat(ask.code()).isEqualTo("REQUEST_UNAVAILABLE");
        assertThat(ask.requestId()).isEqualTo(requestId);
        assertThat(ask.message()).contains("ASK");
        RunnerMessages.RunRequestAck tool = requests.call(run, requestId, JsonMapper.builder().build()
            .createObjectNode());
        assertThat(tool.code()).isEqualTo("UNKNOWN_TOOL");
        assertThat(new RunPorts.NobodyTold().turnResumed(run)).isTrue();
    }

    @Test
    void aRunIsSeenByItsPersonAndOwnerOnly() {
        UUID person = UUID.randomUUID();
        UUID personUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        Principals principals = mock(Principals.class);
        when(principals.ofMember(COMPANY, personUser)).thenReturn(Optional.of(new PrincipalInfo(person, COMPANY,
                PrincipalKind.MEMBER, personUser, null, PrincipalStatus.ACTIVE)));
        when(principals.ofMember(COMPANY, otherUser)).thenReturn(Optional.of(new PrincipalInfo(UUID.randomUUID(),
                COMPANY, PrincipalKind.MEMBER, otherUser, null, PrincipalStatus.ACTIVE)));
        RunPorts.PersonalRuns visibility = new RunPorts.PersonalRuns(principals);
        RunInfo info = new RunInfo(UUID.randomUUID(), COMPANY, UUID.randomUUID(), "conv-1", UUID.randomUUID(),
                RuntimeKind.CLAUDE_CODE, TurnReason.FIRST, 1, person, person, ModelAccessChoice.BUDGET,
                RunStatus.QUEUED, null, null, null, null, null, null, null, null, null, 0, Instant.now(), null, null);

        assertThat(visibility.maySee(new CompanyAccess(COMPANY, CompanyRole.MEMBER, false), personUser, info)).isTrue();
        assertThat(visibility.mayCancel(new CompanyAccess(COMPANY, CompanyRole.MEMBER, false), personUser, info))
            .isTrue();
        assertThat(visibility.maySee(new CompanyAccess(COMPANY, CompanyRole.OWNER, false), otherUser, info)).isTrue();
        assertThat(visibility.maySee(new CompanyAccess(COMPANY, CompanyRole.ADMIN, false), otherUser, info)).isFalse();
        assertThat(visibility.maySee(new CompanyAccess(COMPANY, CompanyRole.MEMBER, false), otherUser, info))
            .isFalse();
        // An instance admin under break-glass has ADMIN's view (the package 6 contract, §5.10).
        assertThat(visibility.maySee(new CompanyAccess(COMPANY, null, true), otherUser, info)).isFalse();
    }

    @Test
    void aQueueReasonIsStoredWithItsFactsAndSaidInWords() {
        Instant reset = Instant.parse("2026-11-01T00:00:00Z");
        QueueReason spent = QueueReason.budgetSpent(reset, "The principal's budget is spent until it resets");
        QueueReason stored = QueueReason.fromColumns(spent.code().name(), spent.detailsJson());
        assertThat(stored).isEqualTo(spent);
        // The reset is a fact (retryAt) the reader's clock says, never an instant in the words.
        assertThat(stored.retryAt()).isEqualTo(reset);
        assertThat(stored.text()).isEqualTo("The budget that pays for this conversation is spent; the turn goes on "
                + "once it resets.");
        assertThat(QueueReason.budgetSpent(null, "spent").text())
            .isEqualTo("The budget that pays for this conversation is spent.");
        QueueReason refused = QueueReason.modelAccessUnavailable("PRINCIPAL_INACTIVE", "The payer is suspended.");
        assertThat(QueueReason.fromColumns(refused.code().name(), refused.detailsJson())).isEqualTo(refused);
        assertThat(refused.code()).isEqualTo(QueueReasonCode.MODEL_ACCESS_UNAVAILABLE);
        QueueReason rejected = QueueReason.rejected(UUID.randomUUID(), "builder", RunnerMessages.TASK_RUN_ACTIVE,
                "busy", reset);
        assertThat(rejected.code()).isEqualTo(QueueReasonCode.PREVIOUS_RUN_ACTIVE);
        assertThat(rejected.text()).contains("builder");
        assertThat(QueueReason.fromColumns(null, null)).isNull();
    }
}
