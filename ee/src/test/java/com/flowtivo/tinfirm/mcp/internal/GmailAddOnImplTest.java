package com.flowtivo.tinfirm.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.flowtivo.tinfirm.agent.AgentReads;
import com.flowtivo.tinfirm.conversation.ConversationCommands;
import com.flowtivo.tinfirm.conversation.ConversationCommands.Submitted;
import com.flowtivo.tinfirm.conversation.ConversationReads;
import com.flowtivo.tinfirm.conversation.StagedFiles;
import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.mcp.internal.GoogleIdTokens.GoogleAccount;
import com.flowtivo.tinfirm.shared.api.model.AgentPage;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationState;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.GmailMessages;

import tools.jackson.databind.json.JsonMapper;

/**
 * A start of the Gmail add-on with the email's attachments (ADR-0084), as a unit over the core's ports: a click
 * answered already is answered from its conversation before Gmail is read or anything held; a start the core answers
 * as a replay, or refuses, releases the files this click held; a start that took them releases nothing; and the peek
 * asks for the key the start then claims.
 */
class GmailAddOnImplTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID AGENT = UUID.randomUUID();

    private static final UUID LINK = UUID.randomUUID();

    private static final UUID USER = UUID.randomUUID();

    private static final UUID FILE = UUID.randomUUID();

    private static final UUID CONVERSATION = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private static final GoogleAccount ACCOUNT = new GoogleAccount("google-subject-1", "ann@example.test");

    /** The open email as Gmail answers {@code format=full}: a text body and one attachment read by its id. */
    private static final String EMAIL = """
            {"id": "msg-1", "threadId": "thread-1", "payload": {"mimeType": "multipart/mixed",
              "headers": [{"name": "Subject", "value": "Invoice wrong"},
                          {"name": "From", "value": "Ann Customer <ann@customer.test>"}],
              "parts": [{"mimeType": "text/plain", "filename": "", "body": {"data": "%s"}},
                        {"mimeType": "application/pdf", "filename": "invoice.pdf",
                         "body": {"attachmentId": "att-1", "size": 12}}]}}""".formatted(Base64.getUrlEncoder()
        .withoutPadding().encodeToString("Hello,\nthe invoice attached is wrong.".getBytes(StandardCharsets.UTF_8)));

    private final GmailLinking links = mock(GmailLinking.class);

    private final GmailStore store = mock(GmailStore.class);

    private final GmailMessages gmail = mock(GmailMessages.class);

    private final AgentReads agents = mock(AgentReads.class);

    private final ConversationCommands commands = mock(ConversationCommands.class);

    private final StagedFiles staged = mock(StagedFiles.class);

    private final OwnerNotices notices = mock(OwnerNotices.class);

    private final SystemTransactions system = mock(SystemTransactions.class);

    private final TimeSource time = mock(TimeSource.class);

    private final Licensing licensing = mock(Licensing.class);

    private final GmailAddOnImpl addOn = new GmailAddOnImpl(links, mock(GmailDeployments.class), store, gmail, agents,
            mock(ConversationReads.class), commands, staged, notices, mock(CompanyNames.class),
            new McpProperties(120, 30), new TinfirmProperties(URI.create("https://tinfirm.test"), Set.of("api")),
            system, time, licensing);

    private final GmailLinking.Active active = new GmailLinking.Active(new GmailStore.Link(LINK, COMPANY, USER,
            ACCOUNT.subject(), ACCOUNT.email(), null, NOW, NOW), new AuthenticatedUser(USER, "ann@example.test",
                    "Ann Example", false));

    private final Conversation conversation = new Conversation().id(CONVERSATION)
        .key("c-1")
        .title("Invoice wrong")
        .agentName("Support")
        .state(ConversationState.OPEN);

    @BeforeEach
    void linkedWithAnEmailOpen() {
        when(licensing.has(Feature.GMAIL_ADDON)).thenReturn(true);
        when(links.active(COMPANY, ACCOUNT)).thenReturn(Optional.of(active));
        when(time.now()).thenReturn(NOW);
        when(system.inCompany(any(), any())).thenAnswer(call -> call.<Supplier<Object>>getArgument(1).get());
        doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(system).runInCompany(any(), any());
        when(notices.of(any(), any())).thenReturn("");
        when(agents.list(any(), any(), any(), any())).thenReturn(new AgentPage(new ArrayList<>()));
        when(gmail.message("user-oauth", "message-token", "msg-1")).thenReturn(CallResult.ok(EMAIL));
        when(gmail.attachment(eq("user-oauth"), eq("message-token"), eq("msg-1"), eq("att-1"), anyLong()))
            .thenReturn(CallResult.ok("%PDF-1.4 one".getBytes(StandardCharsets.UTF_8)));
        when(staged.maxFileBytes()).thenReturn(25L * 1024 * 1024);
        when(staged.stage(eq(COMPANY), eq("invoice.pdf"), eq("application/pdf"), any()))
            .thenReturn(new StagedFiles.StagedFile(FILE, "invoice.pdf", "application/pdf", 12, NOW.plusSeconds(3600)));
    }

    /** The start card's click: its own id, the action, the agent, and the attachments sent. */
    private static GmailAddOn.Event click(boolean withAttachments) {
        return new GmailAddOn.Event("msg-1", "thread-1", "message-token", "user-oauth", null, null,
                Map.of("do", "start", "action", "SUMMARISE", "card", "6d6d0d1e-4b0e-4d4a-9a4b-4f0f4f0f4f0f"),
                Map.of("agent", AGENT.toString(), "attachments", withAttachments ? "yes" : "no"));
    }

    private Map<String, Object> start(boolean withAttachments) {
        return addOn.handle(COMPANY, "Acme", "act", click(withAttachments), ACCOUNT);
    }

    private Map<String, Object> conversationCard() {
        return GmailCards.updated(GmailCards.conversation(conversation, false, addOn.urls(COMPANY), null));
    }

    @Test
    void aClickAnsweredAlreadyIsAnsweredFromItsConversationBeforeGmailIsReadOrAnythingHeld() {
        when(commands.startedWith(eq(COMPANY), anyString())).thenReturn(Optional.of(conversation));

        assertThat(start(true)).isEqualTo(conversationCard());

        verifyNoInteractions(gmail);
        verify(staged, never()).stage(any(), any(), any(), any());
        verify(staged, never()).release(any(), any());
        verify(commands, never()).startFromClient(any(), any(), anyInt(), any(), any());
        // Remembered for the email, as the first answer was, so that the open email shows it.
        verify(store).remember(eq(COMPANY), eq(USER), argThat(remembered -> remembered.conversationId()
            .equals(CONVERSATION)));
        verify(links).lastAgent(COMPANY, LINK, AGENT);
    }

    @Test
    void aStartTheCoreAnswersAsAReplayReleasesTheFilesThisClickHeld() {
        when(commands.startFromClient(eq(COMPANY), any(), eq(30), anyString(), eq(List.of(FILE))))
            .thenReturn(new Submitted<>(conversation, true));

        assertThat(start(true)).isEqualTo(conversationCard());

        verify(staged).stage(eq(COMPANY), eq("invoice.pdf"), eq("application/pdf"), any());
        verify(staged).release(COMPANY, List.of(FILE));
    }

    @Test
    void aStartTheCoreRefusesReleasesTheFilesThisClickHeld() {
        when(commands.startFromClient(eq(COMPANY), any(), eq(30), anyString(), eq(List.of(FILE))))
            .thenThrow(ApiException.conflict(ProblemCode.TOO_MANY_ATTACHMENTS,
                    "A conversation can have at most 50 files"));

        Map<String, Object> card = start(true);

        assertThat(JSON.writeValueAsString(card)).contains("A conversation can have at most 50 files")
            .doesNotContain("Refresh");
        verify(staged).release(COMPANY, List.of(FILE));
        verify(store, never()).remember(any(), any(), any());
    }

    @Test
    void aStartThatFailsOutrightReleasesTheFilesThisClickHeldAndFails() {
        when(commands.startFromClient(eq(COMPANY), any(), eq(30), anyString(), eq(List.of(FILE))))
            .thenThrow(new IllegalStateException("The database is not reachable"));

        assertThatThrownBy(() -> start(true)).isInstanceOf(IllegalStateException.class);

        verify(staged).release(COMPANY, List.of(FILE));
    }

    @Test
    void aStartThatTookTheFilesReleasesNothingAndThePeekAskedForTheKeyItClaimed() {
        when(commands.startFromClient(eq(COMPANY), any(), eq(30), anyString(), eq(List.of(FILE))))
            .thenReturn(new Submitted<>(conversation, false));

        assertThat(start(true)).isEqualTo(conversationCard());

        verify(staged, never()).release(any(), any());
        ArgumentCaptor<String> peeked = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> claimed = ArgumentCaptor.forClass(String.class);
        verify(commands).startedWith(eq(COMPANY), peeked.capture());
        verify(commands).startFromClient(eq(COMPANY), any(), eq(30), claimed.capture(), eq(List.of(FILE)));
        assertThat(peeked.getValue()).isEqualTo(claimed.getValue());
        // The request names the held file, and its fence's tag comes from the key.
        verify(commands).startFromClient(eq(COMPANY), argThat(request -> request.getMessage()
            .contains("Attachments (files of this conversation): invoice.pdf")), eq(30), anyString(),
                eq(List.of(FILE)));
    }

    @Test
    void aReplayOfAClickThatHeldNothingReleasesNothing() {
        when(commands.startFromClient(eq(COMPANY), any(), eq(30), anyString(), eq(List.of())))
            .thenReturn(new Submitted<>(conversation, true));

        assertThat(start(false)).isEqualTo(conversationCard());

        verify(gmail, never()).attachment(any(), any(), any(), any(), anyLong());
        verify(staged, never()).stage(any(), any(), any(), any());
        verify(staged, never()).release(any(), any());
    }
}
