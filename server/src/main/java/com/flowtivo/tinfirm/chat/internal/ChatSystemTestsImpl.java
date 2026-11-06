package com.flowtivo.tinfirm.chat.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystemTests;
import com.flowtivo.tinfirm.system.ChatSystems;

/**
 * A chat system's test (the package 5 contract, §5.1): the bot ({@code auth.test}; a token for the Teams app), the
 * transport (a Socket Mode connection opens) and the stored secrets. Outside any transaction; the system module
 * authorised the caller and stores the outcome as the system's status.
 */
@Component
class ChatSystemTestsImpl implements ChatSystemTests {

    private final ChatSystems chatSystems;

    private final ChatCalls calls;

    private final ChatBot bot;

    private final TransactionTemplate transactions;

    ChatSystemTestsImpl(ChatSystems chatSystems, ChatCalls calls, ChatBot bot,
            PlatformTransactionManager transactionManager) {
        this.chatSystems = chatSystems;
        this.calls = calls;
        this.bot = bot;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Outcome test(UUID companyId, UUID systemId) {
        ChatSystemInfo system = Objects.requireNonNull(transactions.execute(status -> chatSystems.find(companyId,
                systemId)
            .orElseThrow()));
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        boolean withBot = false;
        if (!system.hasBot()) {
            failed.add("no bot is set");
        }
        else {
            bot.forget(systemId);
            Optional<ChatBot.Credential> credential = bot.credential(companyId, systemId);
            if (credential.isEmpty()) {
                failed.add("the bot cannot be used (its account waits for a new token, or its service identity is "
                        + "suspended)");
            }
            else {
                withBot = true;
                CallResult<?> checked = system.slack()
                        ? calls.slackAuthTest(system.system(), credential.get().secret())
                        : calls.teamsCheck(system.system(), credential.get().teams());
                if (checked.failure() == null) {
                    passed.add("the bot's token is accepted");
                }
                else {
                    failed.add("the bot's token was refused: " + checked.failureMessage());
                }
            }
        }
        if (system.slack()) {
            if (system.socketMode()) {
                socket(system, passed, failed);
            }
            else if (system.signingSecretId() == null) {
                failed.add("no signing secret is stored: Slack's events cannot be verified");
            }
            else {
                passed.add("the signing secret is stored");
            }
        }
        String message = failed.isEmpty() ? "Passed: " + String.join("; ", passed)
                : "Failed: " + String.join("; ", failed) + (passed.isEmpty() ? "" : ". Passed: "
                        + String.join("; ", passed));
        return new Outcome(failed.isEmpty(), withBot, message);
    }

    /** Socket Mode: the app-level token opens a connection, which is closed at once. */
    private void socket(ChatSystemInfo system, List<String> passed, List<String> failed) {
        Optional<String> token = SystemContext.callInCompany(system.companyId(), () -> chatSystems.revealSecret(
                system.companyId(), system.id(), ChatSystems.ChatSecret.APP_TOKEN, "chat-socket:" + system.id()));
        if (token.isEmpty()) {
            failed.add("no app-level token is stored: Socket Mode cannot connect");
            return;
        }
        CallResult<String> url = calls.slackSocketUrl(system.system(), token.get());
        if (url.failure() != null) {
            failed.add("Slack did not open a Socket Mode connection: " + url.failureMessage());
            return;
        }
        // The test reads nothing, so it puts no message together (a limit of 0): an envelope is redelivered to the
        // system's own connections.
        CallResult<ChatCalls.ChatSocket> socket = calls.slackConnect(system.system(),
                Objects.requireNonNull(url.value()), 0, new ChatCalls.SocketListener() {

                    @Override
                    public void onText(String text) {
                        // Never called: every message is longer than 0 characters.
                    }

                    @Override
                    public void onClosed(int code, String reason) {
                        // Closed by the test.
                    }

                    @Override
                    public void onTooLong() {
                        // Every message: the test reads nothing.
                    }
                });
        if (socket.failure() != null) {
            failed.add("the Socket Mode connection failed: " + socket.failureMessage());
            return;
        }
        Objects.requireNonNull(socket.value()).close();
        passed.add("a Socket Mode connection opens");
    }
}
