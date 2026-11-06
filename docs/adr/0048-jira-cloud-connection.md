# ADR-0048: Jira Cloud as a second Jira kind of connection

- Status: Accepted
- Date: 2026-10-08
- Sources: the owner's request of 2026-10-08 for Jira Cloud; PLATFORM.md §4.2, §4.5, §5.1, §7.2, §8, §14; CONVENTIONS §14.5, §14.9; [ADR-0024](0024-jira-tracker-connector.md), which chose Jira Data Center first and named Cloud "a second `kind` behind the same rules, links and write-back"; [ADR-0046](0046-agent-tool-permissions.md)
- Changes: PLATFORM.md §4.2, where Jira Cloud was a "later" row of the kinds, and §14, where it was later work; CONVENTIONS §14.5, whose table of kinds gains the column.
- Amended by: [ADR-0049](0049-agent-kinds-groups-and-personal-accounts.md), under which the later "3LO as a second credential" is a person's account of kind `OAUTH`

## Context

- Companies on Atlassian's hosted Jira keep their tracker as the companies on Data Center do (ADR-0024): the issue is the brief, and the result must show on the issue.
- Jira Cloud differs from Data Center in four ways that reach the code; everything else (issue keys and projects, statuses and transitions by name, labels, remote links, attachments, JQL with minute precision in the account's time zone, `resolutiondate` as closed) is the same:
  - **Credential.** There are no personal access tokens of Atlassian accounts. A REST call authenticates with Basic authentication: the account's email and an API token the person creates at id.atlassian.com. OAuth 2.0 (3LO) exists beside it, with an Atlassian app, a consent per site and the `api.atlassian.com` gateway.
  - **Identity.** Accounts have no username. An account is its Atlassian account id (`5b10ac8d82e05b22cc7d4ef5`, `712020:…`); `assignee`, `reporter` and a comment's `author` carry `accountId` and `displayName`, and the email only when the person shows it. A mention is a `mention` node with the id (`[~accountid:…]` in wiki markup).
  - **Texts.** REST API v3 returns and takes descriptions and comments in the Atlassian Document Format (ADF), a JSON tree of blocks, inline nodes and marks. API v2 still shows them as wiki markup, as a conversion of the stored ADF, which loses what wiki markup cannot say.
  - **Search.** The `search` endpoint of both API versions was removed in 2025. Its replacement, `search/jql`, pages by an opaque `nextPageToken`, has no `startAt` and reports no `total`; the poll's window (`PollWindow`) continues a cut listing at an offset.

## Decision

**Jira Cloud is a second kind of connection, `JIRA_CLOUD`, behind the rules of `JIRA_DC`: the same subjects, events, conditions, actions, accounts, write-back and tools, on REST API v3.**

- **Kind.** `ConnectionKind.JIRA_CLOUD` has an API, is polled, takes no repositories and has the fixed tools `get` and `search`. `ConnectionKind.isJira()` names what the two Jira kinds share, and the rules that said `JIRA_DC` (the events, the actions `STATUS_TO` and `ATTACH_ARTIFACTS`, statuses in conditions, a project key as the scope, issues as the only subject, an account token per agent, a remote link for a change request, "Jira" as the source's name) say `isJira()`.
- **Credential.** The connection's secret, and each agent account's secret, holds the Atlassian account's email and API token as `email:token`, which the client sends as `Authorization: Basic`. A secret without the colon, or with whitespace, is refused before anything is sent, with a message that says what the secret must hold and never shows it. The base URL is the site, `https://<site>.atlassian.net`.
- **Identity.** `SourceAccount.name`, `SourceUser.name` and an agent's `accountName` are account ids; `myself` gives the id, the display name and the time zone the JQL dates are read in. `mentions` finds a `mention` node with the id, or `[~accountid:…]` in a text, ignoring case. The connection's test shows `Display Name (accountId)`, so an owner can tell whose token it is and copy the id for an agent's account.
- **Texts.** A body in the system's markup is the ADF document as JSON text, as a Data Center body is wiki markup. `Adf` converts it to Markdown for briefs, comments and tool results (headings, emphasis, code, lists, task lists, links, quotes, rules, tables; mentions as `@Name`, emoji, dates, statuses, panels and expands as text, attached media by name; unknown nodes keep their text) and Markdown to ADF for the write-back (the same subset; a checklist becomes a task list; a label such as `Tinfirm:` goes in front of the first paragraph). The text hash of `UPDATED` is over the JSON, so a change of formatting alone counts as an update, as it does with wiki markup.
- **Search.** `JiraCloudClient.search` calls `search/jql` with `fields`, `maxResults` and the cursor. The poll's offset is served from it: one `JiraCloudSourceSystem` serves one poll pass and continues from the cursor its last page ended at when the next read asks for the offset right after it (the poller does); a new pass at an offset walks the pages before it with requests of up to 100 issues. Past 100 pages the read fails with a message that names the cause (more than 10 000 issues changed within one minute), instead of reading forever. `more` is whether a cursor came back. When the listing leaves an issue's comments out, or cuts them, the newest are read from `issue/{key}/comment?orderBy=-created`.
- **Writes.** Comments go to `issue/{key}/comment` as `{"body": <ADF>}`; transitions, remote links and attachments to the v3 paths with the same bodies as v2. Labels are not written, as on Data Center.
- **Storage.** `V021__jira_cloud.sql` adds the kind to `connection_kind_ck` and to the polled kinds of `connection_poll_due`. No column is added.
- **API and web.** `ConnectionKind` gains `JIRA_CLOUD`; no other shape changes. The web app's kind entry asks for the site URL, says what the secret holds, and names the account as an Atlassian account ID with where to find it.

## Consequences

- A company on Jira Cloud gets what a company on Data Center has: triggers on its issues, the write-back of comments, transitions, links and documents, the `get` and `search` tools; the three-specialist flow of PLATFORM.md §5.5 runs on either.
- The email of the service account lives in the vault with the token, since Basic authentication needs both; it is revealed for one call, never logged, and never part of a failure message. Rotating the token means storing a new `email:token` value of the same secret. The operator's guide asks for an account people do not use.
- Walking the pages before a large offset spends requests at the start of a pass; within a pass the cursor is reused. The cap of 100 pages is a clear failure for a bulk edit of more than 10 000 issues in one minute, which the guide names.
- Cloud's rate limits (429 with `Retry-After`) are handled by the outbound client for reads and by the outbox for writes, as Data Center's.
- Not in this decision: OAuth 2.0 (3LO) as a second credential; Jira Cloud webhooks as poll hints (PLATFORM.md §14); Jira Service Management's requests; embedding media (attached files are named in the Markdown, not shown); the richer ADF nodes (layouts, extensions) beyond keeping their text.

## Alternatives considered

- **REST API v2 on Cloud, with wiki markup, reusing `JiraMarkup`.** Less code, but a lossy view of what people wrote and read (Atlassian converts ADF to wiki markup on the way out and back on the way in), and Atlassian steers to v3; v2's `search` is gone as well, so the cursor paging was needed either way. Rejected.
- **OAuth 2.0 (3LO) first.** Needs an Atlassian app registered by the company, a consent flow per site through the browser, refresh tokens and the `api.atlassian.com/ex/jira/{cloudId}` gateway. API tokens fit the service-account model of ADR-0024 and the vault; OAuth can come later as a second way to hold the credential, behind the same kind.
- **An email column beside the secret.** Rejected: Basic authentication needs the two together, the secret is the place for a credential, and a column would add a field to the API, the UI and the audit for one kind.
- **One `JIRA` kind with a Cloud flag.** Rejected: the kind is how the rules, the API and the pages tell systems apart, and ADR-0024 planned Cloud as a second kind.
