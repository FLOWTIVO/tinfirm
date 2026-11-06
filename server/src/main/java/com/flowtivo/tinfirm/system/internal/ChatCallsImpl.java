package com.flowtivo.tinfirm.system.internal;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.internal.http.ChatEndpoints;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.slack.SlackApi;
import com.flowtivo.tinfirm.system.internal.teams.BotFramework;

/**
 * {@link ChatCalls}: Slack's Web API and Socket Mode, the Bot Framework. A call with a system of the wrong kind, or
 * inside a database transaction, is refused.
 */
@Component
class ChatCallsImpl implements ChatCalls {

    private static final Logger log = LoggerFactory.getLogger(ChatCallsImpl.class);

    private final OutboundHttp http;

    private final ChatEndpoints endpoints;

    ChatCallsImpl(OutboundHttp http, ChatEndpoints endpoints) {
        this.http = http;
        this.endpoints = endpoints;
    }

    private SlackApi slack(SystemInfo system) {
        NoTransaction.require("A call to a chat system");
        if (system.kind() != SystemKind.SLACK || system.baseUrl() == null) {
            throw new IllegalArgumentException("Not a Slack system: " + system.id());
        }
        return new SlackApi(http, endpoints, system.baseUrl());
    }

    private BotFramework teams(SystemInfo system) {
        NoTransaction.require("A call to a chat system");
        if (system.kind() != SystemKind.TEAMS || system.baseUrl() == null) {
            throw new IllegalArgumentException("Not a Teams system: " + system.id());
        }
        return new BotFramework(http, endpoints, system.baseUrl(), system.id().toString());
    }

    @Override
    public CallResult<SlackBot> slackAuthTest(SystemInfo system, String botToken) {
        return slack(system).authTest(botToken);
    }

    @Override
    public CallResult<SlackUser> slackUser(SystemInfo system, String botToken, String userId) {
        return slack(system).user(botToken, userId);
    }

    @Override
    public CallResult<String> slackAvatar(SystemInfo system, String url) {
        return slack(system).avatar(url);
    }

    @Override
    public CallResult<List<SlackChannel>> slackChannels(SystemInfo system, String botToken,
            @Nullable String userId) {
        return slack(system).channels(botToken, userId);
    }

    @Override
    public CallResult<String> slackOpenDirect(SystemInfo system, String botToken, String userId) {
        return slack(system).openDirect(botToken, userId);
    }

    @Override
    public CallResult<String> slackPost(SystemInfo system, String botToken, SlackMessage message) {
        return slack(system).post(botToken, message);
    }

    @Override
    public CallResult<Boolean> slackUpdate(SystemInfo system, String botToken, String channel, String ts, String text,
            List<Map<String, Object>> blocks) {
        return slack(system).update(botToken, channel, ts, text, blocks);
    }

    @Override
    public CallResult<Boolean> slackEphemeral(SystemInfo system, String botToken, String channel, String userId,
            String text) {
        return slack(system).ephemeral(botToken, channel, userId, text);
    }

    @Override
    public CallResult<Boolean> slackReact(SystemInfo system, String botToken, String channel, String ts,
            String name) {
        return slack(system).react(botToken, channel, ts, name);
    }

    @Override
    public CallResult<byte[]> slackDownload(SystemInfo system, String botToken, String url, long maxBytes) {
        return slack(system).download(botToken, url, maxBytes);
    }

    @Override
    public CallResult<String> slackUpload(SystemInfo system, String botToken, String channel,
            @Nullable String threadTs, String fileName, byte[] content) {
        return slack(system).upload(botToken, channel, threadTs, fileName, content);
    }

    @Override
    public CallResult<String> slackSocketUrl(SystemInfo system, String appToken) {
        return slack(system).socketUrl(appToken);
    }

    @Override
    public CallResult<ChatSocket> slackConnect(SystemInfo system, String url, int maxChars,
            SocketListener listener) {
        slack(system);
        // This class's own Socket (a WebSocket.Listener), not java.net.Socket: the connection is http's wss WebSocket.
        Socket socket = new Socket(listener, maxChars); // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        CallResult<WebSocket> opened = http.webSocket(url, endpoints::socketHost, socket);
        if (opened.failure() != null) {
            return opened.failedAs();
        }
        socket.attach(Objects.requireNonNull(opened.value()));
        return CallResult.ok(socket);
    }

    @Override
    public CallResult<Instant> teamsCheck(SystemInfo system, TeamsBot bot) {
        return teams(system).check(bot);
    }

    @Override
    public CallResult<String> teamsPost(SystemInfo system, TeamsBot bot, String serviceUrl, String conversationId,
            Map<String, Object> activity) {
        return teams(system).post(bot, serviceUrl, conversationId, activity);
    }

    @Override
    public CallResult<Boolean> teamsUpdate(SystemInfo system, TeamsBot bot, String serviceUrl, String conversationId,
            String activityId, Map<String, Object> activity) {
        return teams(system).update(bot, serviceUrl, conversationId, activityId, activity);
    }

    @Override
    public CallResult<TeamsMember> teamsMember(SystemInfo system, TeamsBot bot, String serviceUrl,
            String conversationId, String userId) {
        return teams(system).member(bot, serviceUrl, conversationId, userId);
    }

    @Override
    public CallResult<String> teamsOpenDirect(SystemInfo system, TeamsBot bot, String serviceUrl, String userId) {
        return teams(system).openDirect(bot, serviceUrl, userId);
    }

    @Override
    public CallResult<String> teamsKeys(SystemInfo system) {
        return teams(system).keys();
    }

    /**
     * A Socket Mode socket: text messages, put together from their parts up to {@code maxChars}, to the listener; its
     * close, once. The JDK's WebSocket hands a message over in parts as they arrive and has no limit of its own, so a
     * longer message is dropped here, part by part, and never held whole.
     */
    private static final class Socket implements WebSocket.Listener, ChatSocket {

        private final SocketListener listener;

        private final int maxChars;

        private final StringBuilder partial = new StringBuilder();

        /** Whether the parts of the current message are dropped: it grew past {@link #maxChars}. */
        private boolean dropping;

        private final AtomicBoolean closed = new AtomicBoolean();

        private volatile @Nullable WebSocket socket;

        Socket(SocketListener listener, int maxChars) {
            this.listener = listener;
            this.maxChars = maxChars;
        }

        void attach(WebSocket webSocket) {
            this.socket = webSocket;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!dropping && partial.length() + data.length() > maxChars) {
                dropping = true;
                partial.setLength(0);
                partial.trimToSize();
            }
            if (!dropping) {
                partial.append(data);
            }
            if (last) {
                boolean dropped = dropping;
                String text = partial.toString();
                partial.setLength(0);
                dropping = false;
                try {
                    if (dropped) {
                        listener.onTooLong();
                    }
                    else {
                        listener.onText(text);
                    }
                }
                catch (RuntimeException ex) {
                    log.warn("A Socket Mode frame was not handled ({})", ex.getClass().getSimpleName());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (closed.compareAndSet(false, true)) {
                listener.onClosed(statusCode, reason);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (closed.compareAndSet(false, true)) {
                listener.onClosed(1006, error.getClass().getSimpleName());
            }
        }

        @Override
        public boolean send(String text) {
            WebSocket current = socket;
            if (current == null || closed.get() || current.isOutputClosed()) {
                return false;
            }
            try {
                current.sendText(text, true).get(10, java.util.concurrent.TimeUnit.SECONDS);
                return true;
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
                return false;
            }
        }

        @Override
        public void close() {
            WebSocket current = socket;
            if (current != null && !current.isOutputClosed()) {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "closing").exceptionally(error -> null);
            }
            if (current != null) {
                current.abort();
            }
            if (closed.compareAndSet(false, true)) {
                listener.onClosed(WebSocket.NORMAL_CLOSURE, "closed by Tinfirm");
            }
        }

        @Override
        public boolean open() {
            WebSocket current = socket;
            return current != null && !closed.get() && !current.isInputClosed();
        }
    }
}
