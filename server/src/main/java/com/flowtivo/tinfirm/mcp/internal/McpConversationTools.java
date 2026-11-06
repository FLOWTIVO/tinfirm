package com.flowtivo.tinfirm.mcp.internal;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationCommands;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.ConversationReads;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationStartRequest;
import com.flowtivo.tinfirm.shared.api.model.ConversationSubjectInput;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.web.ApiException;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.JsonNode;

/**
 * The tools of conversations (the package 6 contract, §5.7), as the credential's member in their personal space,
 * through {@link ConversationReads} and {@link ConversationCommands} in the {@code CLIENT} view: only the member's
 * own conversations are seen (OWNER's included, open point 11), a start has the origin {@code CLIENT} and counts
 * against the hourly limit of {@link McpProperties#startsPerHour()} (open point 14), and every start's answer carries
 * the Owner's notice (row 18). A conversation is named by its id or its key; an agent by its id or its handle.
 * <p>
 * No tool uploads a file, connects an account or changes an agent, its settings or a system. Ending and cancelling
 * are destructive; ending is idempotent. The client asks the person before each write.
 */
@Component
class McpConversationTools implements McpToolSet {

    private static final ConversationView CLIENT = ConversationView.CLIENT;

    private final ConversationReads reads;

    private final ConversationCommands commands;

    private final McpReferences references;

    private final OwnerNotices notices;

    private final McpProperties properties;

    private final ToolInvoker invoker;

    McpConversationTools(ConversationReads reads, ConversationCommands commands, McpReferences references,
            OwnerNotices notices, McpProperties properties, ToolInvoker invoker) {
        this.reads = reads;
        this.commands = commands;
        this.references = references;
        this.notices = notices;
        this.properties = properties;
        this.invoker = invoker;
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    @McpTool(name = "list_conversations", title = "List the person's conversations",
            description = """
                    Lists the person's own conversations with the company's agents, newest activity first: each \
                    with its key and id, state (OPEN or ENDED), latest turn's status, whom it waits on, title and \
                    agent. Another person's conversations are never listed here, the Owner's view included. Filter \
                    by state (OPEN, ENDED) or agentId. Page with cursor and nextCursor.""",
            annotations = @McpAnnotations(title = "List conversations", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult listConversations(McpTransportContext context,
            @McpToolParam(required = false, description = "OPEN or ENDED; both when left out") @Nullable String state,
            @McpToolParam(required = false, description = "Only the conversations with this agent (its id)") @Nullable UUID agentId,
            @McpToolParam(required = false, description = "The nextCursor of the previous page; leave out for the first page") @Nullable String cursor,
            @McpToolParam(required = false, description = "Conversations per page, 1 to 200; 50 when left out") @Nullable Integer limit) {
        return invoker.read(context, "list_conversations", companyId -> reads.list(companyId, CLIENT,
                new ConversationReads.Filter(agentId, null, null, state(state), null, null), limit, cursor),
                Renderings::conversations);
    }

    @McpTool(name = "get_conversation", title = "Read a conversation",
            description = """
                    One of the person's conversations: its header (agent, subject, who pays, the accounts it uses in \
                    words, the latest turn's status and what it waits on), the latest result or failure, the open \
                    decisions (decide them with decide, after the person said so), the outputs, and the newest \
                    messages (20 unless messages says more). Name it by its id or its key. A turn takes minutes: \
                    call this again to follow it.""",
            annotations = @McpAnnotations(title = "Read a conversation", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult getConversation(McpTransportContext context,
            @McpToolParam(description = "The conversation's id (a UUID) or key (e.g. shop-123-dev-1)") String conversation,
            @McpToolParam(required = false, description = "How many of the newest messages the text shows, 1 to 200; 20 when left out") @Nullable Integer messages) {
        int shown = messages == null ? Renderings.DEFAULT_MESSAGES : Math.clamp(messages, 1, 200);
        return invoker.read(context, "get_conversation",
                companyId -> reads.get(companyId, CLIENT, references.conversation(companyId, conversation)),
                json -> Renderings.conversation(json, shown));
    }

    // ---- writes ----------------------------------------------------------------------------------------------------

    @McpTool(name = "start_conversation", title = "Start a conversation with an agent",
            description = """
                    Starts a conversation with one of the company's agents, as the person you act for: it is \
                    theirs, it is paid by them, and the agent acts with their own accounts, as if they had started \
                    it in Tinfirm, where it shows with the badge "AI client". Name the agent by its id or its \
                    @handle (list_agents; get_agent says whether it is ready for the person). The message is the \
                    first request. Optionally a subject: a company system's id and the key people know (SHOP-123, \
                    owner/repo#57). Files: the ids of files the person's client uploaded to this endpoint's /files \
                    in the past hour (fileIds), which the conversation gets before its first turn. The agent works on \
                    a runner host and a turn takes minutes: follow it with get_conversation. The answer carries a \
                    notice for the person: read it to them. Send a new requestId for each conversation the person \
                    asks for, and the same one when you retry: a retry then answers the conversation already \
                    started, never a second one. Errors: AGENT_NOT_USABLE, MODEL_ACCESS_UNAVAILABLE, \
                    NO_HOST_FOR_CONVERSATION, RATE_LIMITED (at most a set number of starts an hour through AI \
                    clients: wait retryAfter seconds), VALIDATION_FAILED (also a fileId that is not the person's or \
                    expired), TOO_MANY_ATTACHMENTS, DUPLICATE_ATTACHMENT_NAME, IDEMPOTENCY_KEY_REUSED, NOT_FOUND. An account a system needs is connected by the person in \
                    Tinfirm, never through you.""",
            annotations = @McpAnnotations(title = "Start a conversation", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    @ToolScope(ApiScope.WRITE)
    CallToolResult startConversation(McpTransportContext context,
            @McpToolParam(description = "The agent's id (a UUID) or its handle (dev or @dev)") String agent,
            @McpToolParam(description = "The first message to the agent, Markdown, 1 to 100,000 characters") String message,
            @McpToolParam(required = false, description = "The subject's company system (its id), with subjectKey") @Nullable UUID subjectSystemId,
            @McpToolParam(required = false, description = "The subject's key people know, e.g. SHOP-123, with subjectSystemId") @Nullable String subjectKey,
            @McpToolParam(required = false, description = "The repositories of the workspace (ids of the agent's repositories); an empty list for none; the person's defaults when left out") @Nullable List<UUID> repositoryIds,
            @McpToolParam(required = false, description = "Files for the conversation: the ids that this endpoint's /files (a POST of one multipart file) answered in the past hour, which move into the conversation before its first turn") @Nullable List<UUID> fileIds,
            @McpToolParam(required = false, description = "A key of this request, 1 to 200 visible ASCII characters: a new one (a UUID) for each thing the person asks, and the same one when you retry this request after an error or a lost answer. The retry then answers the first request again and does nothing twice; the same key with other arguments is IDEMPOTENCY_KEY_REUSED.") @Nullable String requestId) {
        return invoker.call(context, "start_conversation", companyId -> {
            ConversationStartRequest request = new ConversationStartRequest(references.agent(companyId, agent),
                    message);
            if (subjectSystemId != null || subjectKey != null) {
                if (subjectSystemId == null || subjectKey == null) {
                    throw ApiException.validation("A subject needs both subjectSystemId and subjectKey");
                }
                request.subject(new ConversationSubjectInput(subjectSystemId, subjectKey));
            }
            if (repositoryIds != null) {
                request.repositoryIds(repositoryIds);
            }
            ConversationCommands.Submitted<Conversation> started = commands.startFromClient(companyId, request,
                    properties.startsPerHour(), requestId, fileIds != null ? fileIds : List.of());
            JsonNode structured = invoker.structured(started.body());
            String text = Renderings.started(structured, notices.of(companyId));
            return ToolInvoker.result(structured, started.replayed() ? ToolInvoker.REPLAYED + "\n\n" + text : text);
        });
    }

    @McpTool(name = "send_message", title = "Send a message in a conversation",
            description = """
                    The person's message in one of their conversations. It starts a turn when no turn is active and \
                    no decision is open. While a turn runs, the agent takes it after its current step when its \
                    runtime can (steering), else it waits for the next turn. With answersOpenQuestions=true it \
                    answers every open question of the conversation (each recorded as answered with the message). \
                    A turn takes \
                    minutes: follow it with get_conversation. Send a new requestId for each message and the same \
                    one when you retry: a retry never posts the message twice. Errors: CONVERSATION_ENDED (start a \
                    new one), VALIDATION_FAILED, IDEMPOTENCY_KEY_REUSED, NOT_FOUND.""",
            annotations = @McpAnnotations(title = "Send a message", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    @ToolScope(ApiScope.WRITE)
    CallToolResult sendMessage(McpTransportContext context,
            @McpToolParam(description = "The conversation's id (a UUID) or key") String conversation,
            @McpToolParam(description = "The message, Markdown, 1 to 100,000 characters") String body,
            @McpToolParam(required = false, description = "true to answer every open question with this message") @Nullable Boolean answersOpenQuestions,
            @McpToolParam(required = false, description = "A key of this request, 1 to 200 visible ASCII characters: a new one (a UUID) for each thing the person asks, and the same one when you retry this request after an error or a lost answer. The retry then answers the first request again and does nothing twice; the same key with other arguments is IDEMPOTENCY_KEY_REUSED.") @Nullable String requestId) {
        return invoker.submit(context, "send_message",
                companyId -> commands.post(companyId, CLIENT, references.conversation(companyId, conversation), body,
                        Boolean.TRUE.equals(answersOpenQuestions), requestId),
                json -> Renderings.written("Sent the message.", json, true));
    }

    @McpTool(name = "choose_account", title = "Choose an account for a conversation",
            description = """
                    For a conversation waiting on an account (get_conversation lists its accounts; the inbox shows \
                    CHOOSE and PICK_GRANT), picks one of the grants the binding offers on that system. Connecting \
                    the person's own account is done in Tinfirm, never here. Errors: BINDING_NOT_WAITING, \
                    GRANT_NOT_OFFERED, CONVERSATION_ENDED, NOT_FOUND.""",
            annotations = @McpAnnotations(title = "Choose an account", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    @ToolScope(ApiScope.WRITE)
    CallToolResult chooseAccount(McpTransportContext context,
            @McpToolParam(description = "The conversation's id (a UUID) or key") String conversation,
            @McpToolParam(description = "The system's id (a UUID)") UUID systemId,
            @McpToolParam(description = "The grant's id, one of the binding's choices") UUID grantId) {
        return invoker.write(context, "choose_account",
                companyId -> commands.choose(companyId, CLIENT, references.conversation(companyId, conversation),
                        systemId, grantId, false),
                json -> new ResultText().line("Chose the account on " + Renderings.s(json, "systemName") + ": "
                        + Renderings.s(json, "state")).build());
    }

    @McpTool(name = "cancel_turn", title = "Cancel a conversation's turn",
            description = """
                    Cancels the conversation's queued or running turn, its open decisions and the calls waiting on \
                    them; waiting messages stay unanswered. The conversation stays open. Ask the person first.""",
            annotations = @McpAnnotations(title = "Cancel a turn", readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    @ToolScope(ApiScope.WRITE)
    CallToolResult cancelTurn(McpTransportContext context,
            @McpToolParam(description = "The conversation's id (a UUID) or key") String conversation) {
        return invoker.write(context, "cancel_turn",
                companyId -> commands.cancel(companyId, CLIENT, references.conversation(companyId, conversation)),
                json -> Renderings.written("Cancelled the turn.", json));
    }

    @McpTool(name = "retry_turn", title = "Retry a conversation's turn",
            description = """
                    Runs the conversation's last turn again, when it failed or was cancelled. Send a new requestId \
                    for each retry the person asks for, and the same one when you send this request again: it then \
                    starts no second turn. Errors: TURN_ACTIVE, NOTHING_TO_RETRY, CONVERSATION_ENDED, \
                    IDEMPOTENCY_KEY_REUSED.""",
            annotations = @McpAnnotations(title = "Retry a turn", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    @ToolScope(ApiScope.WRITE)
    CallToolResult retryTurn(McpTransportContext context,
            @McpToolParam(description = "The conversation's id (a UUID) or key") String conversation,
            @McpToolParam(required = false, description = "A key of this request, 1 to 200 visible ASCII characters: a new one (a UUID) for each thing the person asks, and the same one when you retry this request after an error or a lost answer. The retry then answers the first request again and does nothing twice; the same key with other arguments is IDEMPOTENCY_KEY_REUSED.") @Nullable String requestId) {
        return invoker.submit(context, "retry_turn",
                companyId -> commands.retry(companyId, CLIENT, references.conversation(companyId, conversation),
                        requestId),
                json -> Renderings.written("Asked for the turn again.", json, true));
    }

    @McpTool(name = "end_conversation", title = "End a conversation",
            description = """
                    Ends the conversation for good: a running turn finishes and its result is recorded, open \
                    decisions are cancelled, waiting messages stay unanswered, and it takes nothing more. Idempotent. \
                    Ask the person first.""",
            annotations = @McpAnnotations(title = "End a conversation", readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.WRITE)
    CallToolResult endConversation(McpTransportContext context,
            @McpToolParam(description = "The conversation's id (a UUID) or key") String conversation) {
        return invoker.write(context, "end_conversation",
                companyId -> commands.end(companyId, CLIENT, references.conversation(companyId, conversation)),
                json -> Renderings.written("The conversation is ended.", json));
    }

    private static ConversationInfo.@Nullable State state(@Nullable String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return ConversationInfo.State.valueOf(state.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw ApiException.validation("state is OPEN or ENDED");
        }
    }
}
