package com.flowtivo.tinfirm.system;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * Executes the tools of systems on the server (PLATFORM.md §10, ADR-0039; the package 3 contract, §5.4): a tracker's
 * fixed tools ({@link FixedTools}) and an MCP server's own, with the credential of the account the caller's
 * conversation is bound to on the system, revealed by the caller for this call ({@code Accounts.reveal}). Which tools a
 * conversation may call, under which mode, and with which account is the caller's (§5.3, §5.4 steps 1 to 5); this API
 * is step 6, the system's own answer for that account.
 * <p>
 * A call runs <b>outside any database transaction</b> (CONVENTIONS §10; {@link IllegalStateException} inside one): the
 * system's row, a repository's and an MCP tool's catalogue entry are read in a short transaction of its own in the
 * caller's tenant context, then the system is called, through the outbound client and its guard, only at the system's
 * origin (a personal MCP server's never at a private address; Jira Cloud's OAuth grants at Atlassian's gateway for the
 * site), within the tool timeout ({@code TINFIRM_RUN_TOOL_TIMEOUT}, at most 60 s), and only at the endpoint the
 * credential was revealed for ({@link CallCredential#endpoint()}): a system whose kind or base URL changed since (an
 * MCP server moved, ADR-0079) is as gone, and nothing is sent to the new one. Texts are Markdown both ways: Jira
 * Data Center's wiki markup and Jira Cloud's document format are converted, GitHub's and GitLab's Markdown stays.
 * Failures are values, never exceptions, and never hold a token.
 */
public interface SystemTools {

    /**
     * A result whose JSON is larger than this is refused with {@code RESULT_TOO_LARGE}: the runner protocol's frame
     * stays under its 1 MiB.
     */
    int MAX_RESULT_BYTES = 800 * 1024;

    /** A call's arguments, as JSON, have at most this many bytes (as a decision and a parked call keep them, §3.9). */
    int MAX_ARGUMENT_BYTES = 64 * 1024;

    /**
     * Checks a call's arguments without calling anything, before the call runs or waits for an approval: an object of
     * at most {@link #MAX_ARGUMENT_BYTES}; for a fixed tool, the fields its schema names, trimmed (a {@code key} in
     * the system's format, a {@code query}, a {@code limit} clamped to 1 to 50, a {@code body}, a {@code transition},
     * the {@code label} lists); an MCP tool's are the server's to validate. {@code UNKNOWN_TOOL} for a tool the kind
     * has no fixed one of, and for {@code open_change_request}, which the agent does not call. Runs anywhere.
     */
    ToolCheck check(SystemKind kind, String tool, JsonNode arguments);

    /** {@link #call(UUID, UUID, CallCredential, String, JsonNode, String)} without a label for a comment. */
    ToolOutcome call(UUID companyId, UUID systemId, CallCredential credential, String tool, JsonNode arguments);

    /**
     * Runs one tool of the system with the credential, outside any transaction, within the tool timeout.
     * <ul>
     * <li>A fixed tool: the arguments checked ({@link #check}); {@code get} reads one subject with its newest 20
     * comments, {@code search} at most {@code limit} subjects (JQL, GitHub's issue search, GitLab's search terms),
     * each as Markdown and the same as structured content; {@code comment} writes the Markdown converted to the
     * system's markup, with the label in front; {@code transition} applies the transition the issue's status offers by
     * that name or leading to that status; {@code label} adds, then removes; {@code close} closes.</li>
     * <li>An MCP server's tool, present and enabled in its catalogue: {@code initialize},
     * {@code notifications/initialized}, {@code tools/call} and the session's end; the server's result as it sent it
     * (its text blocks, any other block named as text, its structured content, its error flag), a JSON-RPC error as
     * a result with {@code isError}.</li>
     * </ul>
     *
     * @param tool         the tool as its system names it ({@code get}, an MCP server's own name), not the name a
     *                     run lists it by
     * @param commentLabel the bold label a {@code comment} starts with, e.g. {@code Analyst (Tinfirm agent):} or
     *                     {@code Analyst (Tinfirm agent) for Alice:} (§5.4); null for none
     * @throws IllegalStateException inside a database transaction
     */
    ToolOutcome call(UUID companyId, UUID systemId, CallCredential credential, String tool, JsonNode arguments,
            @Nullable String commentLabel);

    /**
     * Opens a change request of a repository of a GitHub or GitLab system with the credential, outside any transaction,
     * within the tool timeout (the package 3 contract, open point 5): a pull request, or a merge request that keeps its
     * source branch. When the code host answers that one is open for the head branch already, that one is answered
     * with {@code created} false. A repository of a {@code GIT} system has none ({@code INVALID}); a refused credential
     * is {@code UNAUTHORIZED}, a code host that does not let the account open one {@code FORBIDDEN}.
     *
     * @throws IllegalStateException inside a database transaction
     */
    CallResult<ChangeRequests.Opened> openChangeRequest(UUID companyId, UUID repositoryId, CallCredential credential,
            ChangeRequests.New request);

    /**
     * The state and head commit of a change request of the repository, by its number (a pull request's, a merge
     * request's iid), for watching what became of it; outside any transaction.
     *
     * @throws IllegalStateException inside a database transaction
     */
    CallResult<ChangeRequests.Read> changeRequestState(UUID companyId, UUID repositoryId, CallCredential credential,
            int number);

    /**
     * The body (Markdown) of a change request of the repository, by its number, outside any transaction; empty when it
     * has none. With {@link #updateChangeRequestBody}, for the block of the conversation's other change requests that
     * each body keeps up to date (the conversation module).
     *
     * @throws IllegalStateException inside a database transaction
     */
    CallResult<String> changeRequestBody(UUID companyId, UUID repositoryId, CallCredential credential, int number);

    /**
     * Replaces the body of a change request of the repository, by its number, outside any transaction.
     *
     * @throws IllegalStateException inside a database transaction
     */
    CallResult<Boolean> updateChangeRequestBody(UUID companyId, UUID repositoryId, CallCredential credential,
            int number, String body);
}
