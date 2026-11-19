# ADR-0116: Short-lived and revocable git tokens: the server revokes a run's git token on its host, and a GitHub App mints one per run

- Status: Proposed
- Date: 2026-11-19
- Decided by: the owner, 2026-11-19: the record is written as Jira TIN-42 proposes and then built, in the scope of [ADR-0112](0112-no-credential-on-a-sandbox-machine.md), whose decision 7 puts it before the split into a control host and sandbox nodes. The open questions at the end are still the owner's
- Sources: Jira TIN-42 ("Short-lived git credentials per run, and revoking them on the host", labelled `needs-adr`); [ADR-0007](0007-credentials-stay-in-deployment.md) (each installation's own GitHub App; git held outside the sandbox with a one-hour installation token); [ADR-0023](0023-code-host-interface-gitlab.md) (GitLab's project access tokens per job, planned); [ADR-0058](0058-authority-from-the-audience.md) (one account per system per conversation, checked at every use); [ADR-0059](0059-litellm-budgets-and-the-model-proxy.md) (the relay checks a run's token at every request); [ADR-0072](0072-system-credentials-lent-to-groups-and-service-accounts.md) (credentials lent to groups and service accounts); [ADR-0093](0093-jira-cloud-service-accounts-by-client-credentials.md) (a credential that mints its own tokens); [ADR-0101](0101-closing-the-elastic-runners-gaps.md) (the take-back records); [ADR-0105](0105-the-reveal-is-the-gate.md) (the reveal is the gate); [ADR-0109](0109-runner-version-is-the-release-version.md) (compatibility is the protocol and its features); [ADR-0112](0112-no-credential-on-a-sandbox-machine.md), decision 7 and its Consequences ("TIN-42 and Q41 stay worth doing, for the control host"); [ADR-0113](0113-levels-of-isolation-and-placement.md), decision 2 (the one exception to ADR-0109); [the security review of 2026-10-27](../reviews/2026-10-27-security-review.md), D7 and its row for R44; [CONVENTIONS.md](../CONVENTIONS.md) §14.10, §15.3, §15.6, §15.13, §15.23; the [threat model](../threat-model.md) A10, 6.20, 7.6, 8.2, 8.33, §6.6, R27, R44, Q11, Q41; [PLATFORM.md](../PLATFORM.md) §14 ("A GitHub App for installation and user tokens"); the code as of this date: the server's `TurnSourceImpl`, `ConversationBindings`, `BindingService`, `BindingEvents`, `ConversationSwitches`, `SystemSwitchesImpl`, `RunDelivery`, `RunCommandStore` and `RunnerMessages`, the runner host's `git_proxy.rs`, `git.rs`, `mirror.rs`, `run.rs` and `takeback.rs`, and `protocol/runner-v2.schema.json`
- Amends: none. It adds a credential kind to ADR-0072's credentials, as ADR-0093 did, and a second reveal of a run's git token to ADR-0105's gate, under the gate's rules
- Changes, when built: `protocol/runner-v2.schema.json` and the `tinfirm-protocol` crate (the frame `run.git.revoke`, the features `run.git.revoke` and `run.git.refresh`, the optional `expiresAt` and `mirrorKey` of `workspaces[].git`, the request kind `GIT_TOKEN` with its payload and answer; no protocol 3); the runner host (one holder of a run's git tokens, read by the git proxy, the run's git and the take-back record; the revocation; the refresh; the mirror key); the server's `run` module (`run_command` of type `REVOKE_GIT`, sent again at `hello`; the third phase of the assignment checks the bindings again; placement on `run.git.refresh`; the queue reason `NO_HOST_FOR_APP_TOKENS`; `GIT_TOKEN` handled outside any transaction), `conversation` module (the listener that revokes, a scope on `ConversationBindings.use`, the refresh), `account` module (the credential kind `APP`, the mint), `system` module (a GitHub App on a `GITHUB` system, the calls to GitHub), `runner` module (`RunnerMessages`), `audit` (`run.git_revoked`) and `vault` (a secret kind for the App's private key); a migration (the next free number, `V087` at the time of writing); `api/openapi.yaml` and the web app (the system's GitHub App, a credential of an installation, the queue reason); CONVENTIONS §8, §9, §14.10, §15.3, §15.6, §15.13, §15.23; the threat model (A10, 6.20, 7.6, 8.2, 8.33, §6.6, R27, R44, Q11, Q41); [operations/systems.md](../operations/systems.md), [operations/accounts.md](../operations/accounts.md) and [operations/runner-protocol-v2.md](../operations/runner-protocol-v2.md)

## Context

**How a run gets its git token today.**

- In the second phase of an assignment, outside any transaction, `TurnSourceImpl.content` reveals each workspace repository's token through `ConversationBindings.use(binding, "run:{runId}")`, the one gate of ADR-0105, audited as `secret.accessed`. The token is the bound account's: a member's own personal access token or OAuth grant, a credential lent to their group, or a trigger's author's or service account's (R27, A10).
- `run.assign` carries it in `workspaces[].git {username, token}`, with the user name `x-access-token` on GitHub, `oauth2` on GitLab and `git` otherwise. The schema says it is used only by the runner host and never passed into the sandbox.
- The host holds it until the run ends, up to the run's wall-clock limit: 60 minutes by default, up to 24 hours for an agent that sets it (§6.6).

**What the host does with the token.** More than the git proxy:

| Where | What | With the token |
|---|---|---|
| `mirror.rs`, before the clone | Creates or fetches the host's bare mirror of the repository, then clones the workspace from it | Yes. The mirror's name is SHA-256 over the URL and the `Authorization` header, so that two principals never share one (M15) |
| `git_proxy.rs`, while the agent runs | Forwards `git-upload-pack` from the sandbox to the code host, adding the host's `Authorization` | Yes, held in the route (`Route.authorization`) for the run's length. `git-receive-pack` is refused with 403: **the sandbox never pushes** |
| `run.rs`, before the push | Fetches the mirror again, to know the repository's current refs for the credential scan | Yes. A failure only warns, and the scan compares with the refs at clone time |
| `git.rs` `Git::push`, at the end | Pushes the run's commits to `agent/<key>` with `--force-with-lease` on the branch's tip at clone time | Yes. **A cancelled run pushes its work too**: the host takes the work back and pushes before it reports the cancel |
| `run.rs`, the redaction | Adds every token of the run to the secrets it redacts from events and scans for in outgoing commits | The value, for the run's length |
| `takeback.rs`, with the sandbox bridge | Writes `runs/<runId>.json` (0600), which holds the whole `RunAssign`, the tokens included, until the sandbox stops (ADR-0101, 6.20) | Yes, on disk |

Each holds its own copy: `Git.authorization`, `Route.authorization`, and the record's `assign`. Nothing can replace or remove a token once the run has started.

**What happens when a binding fails its check.**

- Package 1's events (`AccountDisconnected`, `AccountNeedsReconnect`, `CredentialChanged`, `LendingReplaced`, `GrantChanged`, `GrantDeleted`, `GroupMembersChanged`, `PrincipalSuspended`, `SystemDeleted` and others) reach `BindingEvents`. That class has `BindingService.reevaluate` look again at every binding of an open conversation that they may concern.
- A check that fails records the binding `WAITING` or `GONE` with its reason, and publishes `ConversationEvents.BindingWaiting` or `BindingGone`. A check at any other use, such as a tool call, does the same.
- A paused system is different: the check answers `Waiting` without recording anything, and the pause publishes `SystemEvents.SystemPaused`. A pause with `stopRunning` cancels the running turns of the conversations bound to the system (`ConversationSwitches`), and those turns then push their work with the token.
- None of this reaches a run that holds its token: "disconnecting the account, withdrawing the grant or disabling the credential stops only the next reveal" (R44, 8.33, §6.6).

**The model relay does this per request.** The server's relay checks a run's one-run token at every relayed request (`RunModelTokens.active`), and revokes it when the run ends (ADR-0059). Git cannot copy that shape without passing every clone and push through the server (R29). ADR-0112 rejects that shape again.

**The protocol has what this needs.**

- Optional capabilities are feature names in `hello.features`. The server sends a feature's frames only to a host that lists the feature (ADR-0109; CONVENTIONS §14.10). ADR-0113 made one exception, a change of `hello.isolationTiers`'s values that a server and its VM hosts take together, and called it the only one.
- A host asks the server with `run.request {kind, payload}` and gets exactly one `run.request.ack`. `TOOL` requests run outside any transaction (`RunRequests.call`), because they call other systems.
- The server's durable commands to a host are `run_command` rows. Today there is one type, `CANCEL`, which stays `PENDING` until the run ends and is sent again when the host says hello.

**No GitHub App exists.** ADR-0007 decided that each installation makes its own App. PLATFORM §14 lists one, and A10, 8.2, 7.6 and Q11 wait for it. Q11 asks for its permissions without `workflows`.

**ADR-0112** puts every token on a control host that runs no agent code. It also says that this shortens nothing. Decision 7 builds TIN-42 first, for every host and edition, and for the control host afterwards.

## Decision

**When a binding fails its check, the server tells the run's host to drop that repository's git token. This holds on every code host. The host's git proxy, its own git and its take-back record then forget the token. The proxy refuses the sandbox's next fetch, and the push of the run's commits at its end fails with a message that names the reason. On GitHub, a credential backed by the company's GitHub App gives each run installation tokens of its own. They are limited to the run's workspace repositories and to `contents` and `pull_requests`, never `workflows`, and they are valid for GitHub's hour. The host renews them through the server, and the server checks the binding again at each renewal. GitLab keeps its tokens and gets the revocation alone. Both parts are optional features of runner protocol 2.**

1. **The frame `run.git.revoke`** (server to host).

   | Field | Meaning |
   |---|---|
   | `type` | `run.git.revoke` |
   | `runId`, `epoch` | The run and its assignment, as in `run.cancel` |
   | `componentIds` | The repositories whose token goes (`workspaces[].componentId`): one to 20, always named. No "every repository" form, so a frame never means more than the server checked |
   | `reason` | A code: the binding's reason (`RECONNECT_YOURS`, `RECONNECT_COMPANY`, `SUSPENDED`, `NOT_ENTITLED`, `GRANT_REMOVED`, `LEFT_GROUP`, `IDENTITY_CHANGED`, `HOLDER_LEFT`, `SYSTEM_REMOVED`, …), or `SYSTEM_PAUSED`, `CREDENTIAL_REPLACED` or `REFRESH_REFUSED`. Like the request codes, the schema lists them as examples |
   | `message` | Text that the server writes for the run's log and the agent, at most 1000 characters, e.g. "Tinfirm revoked this run's access to acme/shop: the credential 'CI bot' was disabled." |

   - **The feature `run.git.revoke`.** A host that implements the frame lists it in `hello.features`. The server sends the frame only to such a host.
   - **Durable, without an ack.** The server writes a `run_command` row of the new type `REVOKE_GIT`, with the component ids, the reason and the message. It sends the frame after the commit. The row stays `PENDING` until the run ends, and goes again at every `hello` that lists the run, as `CANCEL` does. The host applies the frame once and ignores it after that. It reports the revocation in a run event (decision 3), so the frame needs no ack.
   - **A host without the feature** gets no frame. The run keeps its token until it ends, as today; the ticket keeps this behaviour for older hosts. The audit event says so (`sent: false`), and the conversation's note says that the run keeps its token until it ends.

2. **What sends it, and for which runs.** A listener of the conversation module (planned name `GitRevocations`) runs after the commit, and the run module's port writes the command, audits it and sends it. The causes:

   | Cause | Event | Runs and repositories |
   |---|---|---|
   | A bound binding fails its check, for any reason and on any path: an account disconnected or needing a new sign-in, a credential disabled, a grant or a lending changed or withdrawn, a person who left the grant's group, a holder suspended or gone, a system removed, an identity changed | `ConversationEvents.BindingWaiting`, `BindingGone` | The runs with a `run_repository` row of that `binding_id` |
   | A system paused, with or without `stopRunning` | `SystemEvents.SystemPaused` | The runs with a `run_repository` row of that `system_id`. With `stopRunning`, the revoke goes before the cancel, since a cancelled run pushes its work |
   | A trigger's binding that follows another credential after a replaced lending (ADR-0072) | `conversation.binding.bound` with `followed` | The runs of that binding: their token belongs to a credential that the service account no longer borrows (`CREDENTIAL_REPLACED`) |
   | A refresh that the gate refuses for good | Decision 9 | That run's repositories in the request |

   - **Which runs.** Runs that are `ASSIGNED` or `RUNNING`, and `LOST` runs within the resume window, whose command waits for the host's `hello`.
   - **The race with an assignment in progress.** `run_repository` rows are written in the third phase of an assignment, after the second phase revealed the tokens. So the third phase's transaction (`RunDelivery`) reads again the bindings of the rows it writes. Where one is no longer `BOUND`, or its system is paused, it sends nothing. It revokes the relay's token as for a stale assignment, and the run goes back to the queue, where the next pass says why. Once that phase has committed, the listener finds the run.
   - **What does not send it.**
     - The kill switches that hold (ADR-0105, decision 3): the company's stop, a paused agent, a paused trigger. They cancel runs or let them finish by their own rules (C§15.13). At a refresh they answer `GIT_HELD` (decision 9).
     - A repository that leaves the workspace because the agent no longer lists it. That takes effect at the next turn.
   - **The audit event `run.git_revoked`.** Its actor is the actor of the change where the event names one, else SYSTEM. Its target is the run, with `conversationId`, `bindingId` (or `systemId` for a pause), `componentIds`, `reason` and `sent`. There is one event per run and cause. It never carries a token.

3. **What the host does.** A run's tokens get one holder per run (planned name `RunGitTokens`). It keeps, for each repository, the token and its expiry, or why the token is gone. The proxy's route, the run's `Git`s and the take-back record read from it, in place of the three copies of today. For each repository that a `run.git.revoke` of the run's current epoch names, the host does the following. It ignores a frame for another epoch, as it does a `run.cancel`.

   | What | After the revocation |
   |---|---|
   | Memory | The holder drops the token and keeps the reason and the message |
   | The take-back record | The host writes the record again, whole and 0600 as today. The repository's `git` is null, and a new optional field `revoked` names the repository with the message. A host that restarts then neither uses the token nor pushes the repository. The record's format version stays 1 |
   | The git proxy | The route stays. Each new request gets 403 with the server's message as plain text, as `git-receive-pack` gets today |
   | A clone or mirror fetch not yet started | Fails with `cannot clone <repository>: <message>`, `GIT_ERROR`, as a failed clone does today |
   | The mirror fetch before the push | Is not attempted. The scan compares with the refs at clone time, as after a failed fetch today |
   | The push of the run's commits | Is not attempted. The repository's error is `<repository>: not pushed: <message>`, the run's `failureMessage` is "git failed in …", and its class is `GIT_ERROR`. The `git` result says `pushed: false` with the commits counted |
   | The redaction | The token stays on the run's list: what was revealed stays redacted |
   | The run's events | A `LOG` event at `warn` with the message, which the run's page and the conversation show |

   The repositories that the frame does not name go on as before.

4. **What the run sees, and how it ends.** As the ticket proposes, the run goes on without git for those repositories (open question 1).

   - The agent's next `git fetch` fails, and so does a blob that a partial clone fetches lazily. Both fail with the proxy's 403.
   - The sandbox's own `git push` was refused before the revocation as well. The push that fails is the host's push at the end, and it fails visibly. A revoked repository with commits ahead ends the run `FAILED`, `GIT_ERROR`, with a message that names the repository and the reason. A revoked repository without commits fails nothing. The other repositories are pushed as usual.
   - The commits that were not pushed stay only in the host's kept failed workspace (`keep_failed_workspaces`). With the bridge they are nowhere else.
   - The conversation's binding already waits or is gone, so its next turn waits for its card, as today.

5. **What is not revocable.**

   - **A request in flight** that already carried the token: a fetch through the proxy, a mirror fetch, or the host's push. Each finishes, and its result is reported as it is.
   - **What is in the sandbox already:** the clone, its objects, and what the agent read.
   - **The token at the code host.** A run's revocation does not revoke a member's personal access token or OAuth grant at GitHub or GitLab. A copy taken from the host's memory before the revocation keeps working: a personal access token until someone rotates it at the code host, an App's token until its hour ends. Whether Tinfirm also revokes an App's token at GitHub is to be verified during the build.
   - **A host without the feature**, and a host that is disconnected, until its `hello`. The run's lease (30 s) and the reconnect grace (2 minutes) bound how long that is.
   - **A host that does not obey**, such as a modified binary. A company host is trusted with what it reports (5.13).

6. **A GitHub App on a `GITHUB` system.**

   - **What the system holds.** A `GITHUB` system can hold one GitHub App: the App's id, and its private key as a managed company secret of the system (a new secret kind; planned name `SYSTEM_GITHUB_APP_KEY`). The key is never shown again; an admin rotates it by replacing it. The system's OAuth app for members' sign-in stays as it is.
   - **The API.** `PUT /systems/{systemId}/github-app` with `{appId, privateKey}` and `MANAGE_SYSTEMS`. The server checks the pair by asking GitHub for the App, with a JWT that the key signs. `DELETE` on the same path answers `409` while an `APP` credential remains. `GET …/github-app/installations` lists the App's installations: the account's login and type, and its repository selection. The audit actions are `system.github_app_set` and `system.github_app_removed`, beside the vault's `secret.*`.
   - **The web app.** On the system's page an admin sets the App's id and key and sees its installations. The **Add credential** dialog gains "GitHub App installation". The page names the permissions to give the App: Contents and Pull requests (read and write), Issues and whatever the system's tools need, and never Workflows. It says how many of the company's hosts lack `run.git.refresh` (decision 12).
   - **No vendor App.** The App is the company's own: each installation of Tinfirm registers its own App (ADR-0007). An App that the owner might publish is not part of this decision.
   - **Scope.** One App per system (open question 2). GitHub Enterprise Server is open question 3.

7. **Credentials backed by the App; members' own accounts.**

   - **A new kind `APP`.** A credential of kind `APP`, beside `TOKEN`, `OAUTH` and `CLIENT`, is one installation of the system's App. An admin chooses the installation from the list. It holds the installation's id and the account's login, and a system has at most one credential per installation. Its identity is the App's bot user (to verify). It is lent to groups and service accounts, disabled and removed like every credential (ADR-0072). Only such a credential is "backed by the App".
   - **The server's own uses.** Tool calls, change requests, the write-back, polls and the catalogue reveal the credential as `CLIENT` does (ADR-0093). The account module mints an installation token under the refresh lock when the last one expires within the margin, and keeps it in the credential's secret. It asks for the installation's permissions less `workflows`, so that no token Tinfirm mints carries `workflows`. This token never goes to a runner host.
   - **Members' own accounts.** They keep their OAuth or personal access tokens on GitHub and on GitLab, and get the revocation alone (decisions 1 to 5). A GitHub App's user tokens (PLATFORM §14) are not part of this decision.
   - **A repository outside the installation.** The App cannot mint for a repository that the installation does not cover. The content of the assignment then answers `NotReady` with `Cancel`, and a message that names the repository and the installation: an admin installs the App there, and the person retries.

8. **A token per run.**

   - **When.** In the assignment's second phase, for a binding on an `APP` credential, `TurnSourceImpl.content` calls `ConversationBindings.use` with the purpose `run:{runId}` and a scope: the run's workspace repositories of that binding, each with its access. The gate checks everything it checks for any reveal (ADR-0105). The account module then mints outside any transaction, audited as `secret.accessed` with the purpose `run:{runId}`.
   - **What it asks GitHub for.** One token per credential and access:

     | The repository in the run | Permissions | Repositories |
     |---|---|---|
     | `WRITE` | `contents: write`, `pull_requests: write` | The run's `WRITE` repositories of that installation |
     | Read-only (a `READ` grant or lending, or read-only for the cause `AGENT` or `REQUEST`) | `contents: read`, `pull_requests: read` | The run's read-only repositories of that installation |

     So a run has at most two tokens per credential. They never carry `workflows` or any other permission.
   - **Lifetime.** GitHub's hour from the mint. `workspaces[].git` gains the optional `expiresAt`, an RFC 3339 instant.
   - **The mirror key.** `workspaces[].git` also gains the optional `mirrorKey`: a stable key that the server derives from the credential's id. The host keys the mirror with it in place of the token's hash. Otherwise a new token every run would make a new mirror every run (`mirror.rs` keys by the `Authorization` header). The mirror still holds what one principal's credential fetched (M15).
   - **What the server keeps.** Neither the vault nor the database keeps a run's token. The server sends `expiresAt` and `mirrorKey` only to a host that lists `run.git.refresh`.
   - **Placement.** A run with a binding on an `APP` credential goes only to a host that lists `run.git.refresh`, as a run with skills goes only to a host with `run.skills`. Otherwise it waits with the new queue reason `NO_HOST_FOR_APP_TOKENS`.

9. **The refresh: `run.request` of kind `GIT_TOKEN`.**

   - **The feature `run.git.refresh`.** The host reads `git.expiresAt` and `git.mirrorKey`, and renews a token before it expires.
   - **When the host asks.**
     - When a token has 10 minutes or less left.
     - Before the push at the run's end, when it has 20 minutes or less left.
     - After it took a run back, once it is connected (decision 10).

     One request covers every repository whose token is due, and the host asks at most once a minute per repository.
   - **The request.** `payload {componentIds}`.
   - **The answers.**

     | Answer | `payload` or `code` | The host |
     |---|---|---|
     | `DONE` | `{git: [{componentId, username, token, expiresAt}]}` | Takes the new tokens into its holder, its redaction and the take-back record; keeps the old ones on the redaction list |
     | `REJECTED` `GIT_REVOKED`, with `message` | The binding waits or is gone, its system is paused, a `REVOKE_GIT` command names the repository, or the repository is not one of the run's on an `APP` credential | Applies it as a `run.git.revoke` with that message (decision 3) |
     | `REJECTED` `GIT_HELD` | The gate answered `Held`: a kill switch | Keeps the token until it expires, and asks again |
     | `REJECTED` `GIT_UNAVAILABLE` | GitHub could not be reached, or answered 5xx or 429 (`BindingUse.Failed`) | The same |
     | `REJECTED` with a protocol code | `RUN_NOT_ACTIVE`, `INVALID_REQUEST`, `TOO_MANY_REQUESTS` | As for any request |

   - **A token that expires without a new one** goes as at a revocation (decision 3), with the message "the git token expired and Tinfirm could not renew it". The server did not revoke it, so nothing is audited as `run.git_revoked`.
   - **The server's checks.**
     1. The run module checks what it checks for every request (the kind, the run `RUNNING` on this host with this epoch, the payload an object).
     2. It hands `GIT_TOKEN` to its implementation outside any transaction, on a virtual thread, as it does `TOOL`, since a mint calls GitHub. The answer comes within 30 seconds, which is how long the host waits, as for `ACCESS`.
     3. The implementation takes only the run's own repositories (`run_repository`) on an `APP` credential that no `REVOKE_GIT` command names.
     4. It calls `ConversationBindings.use(binding, "run:{runId}", scope)` again. So the binding's check, the kill switches and the personal-host rule are all checked again.
     5. The access is the lower of the assignment's and the current one. A grant lowered to `READ` therefore gives a read token, and the push fails visibly.
     6. Each mint is `secret.accessed` with the purpose `run:{runId}`.
   - **A refusal for good is a revocation.** A `GIT_REVOKED` answer also writes the `REVOKE_GIT` command and the audit event of decision 2 (`REFRESH_REFUSED`, or the binding's reason). So a repository once revoked stays revoked for the rest of the run, also when its binding holds again.

10. **The end of a long run, and a host that restarts.**

    - **The final push needs a valid token.** The host renews before the push (decision 9), and the mirror fetch and the push use the new token. If the server refuses, or the token has expired while the server could not be reached, the repository is not pushed. The run says so (`GIT_ERROR`), as after a revocation. A run that a person cancelled pushes its work, so the same holds for it.
    - **With the default limit of 60 minutes,** a run's first token barely covers the run, and the push at its end almost always renews it.
    - **A host that restarts** (ADR-0101). The take-back record keeps the latest token and its expiry. A restarted host takes its runs back before its `hello`, and can renew only once `welcome` has come. The host must push a taken-back run's work after it is connected, and renew first where the token is due. Whether today's order in `takeback` and `run.rs` already allows that is to be specified.

11. **GitLab: revocation alone.**

    - GitLab's accounts and credentials keep their tokens and get decisions 1 to 5. So does a `GIT` system's.
    - GitLab has no equivalent of an installation token for a member's own account. Minting project access tokens per run (ADR-0023's "project access tokens per job") is recorded as a follow-up, to be built once there is demand. It would fit decisions 8 and 9 as a second kind of credential that mints, with the same feature `run.git.refresh`.

12. **Order and compatibility.**

    - **Two features, no lock-step.** `run.git.revoke` and `run.git.refresh` are features in ADR-0109's sense. No existing field changes its values, there is no new protocol version, and server and hosts are upgraded in either order. ADR-0113's exception stays the only one.
    - **The new request kind.** `GIT_TOKEN` joins the schema's `run.request.kind`. A host sends it only for a token that has an `expiresAt`, which only a server of this decision sends.
    - **What ships first.** The revocation ships first, server and host, for every code host. The App follows. Both ship before ADR-0112's split is released (decision 7 there), and both hold for every kind of host: Docker, a VM per run, a single machine, and later the control host, which is the runner host the server already knows.
    - **Mixed releases.**

      | Server | Host | What happens |
      |---|---|---|
      | This decision | Older | No revocation frame: the run keeps its token, and the audit event says `sent: false`. A run on an `APP` credential never goes to this host (`NO_HOST_FOR_APP_TOKENS`) |
      | Older | This decision | The host lists both features, and the server ignores names it does not know. No frame comes, and no `expiresAt` or `mirrorKey`: the host works as today |
      | This decision | This decision | Everything above |

    - **Set up the App after the hosts.** An admin should set up the App once the company's hosts list `run.git.refresh`. Until then the App's runs wait, and the App's section of the system's page says how many hosts lack the feature.

## Consequences

- **R44 closes for a host with the feature.** A disabled credential, a withdrawn grant or lending, a suspended holder or a paused system stops the run's next fetch through the proxy, and the push at its end. The ticket's acceptance criterion is that this happens within a second of the change's commit, with an integration test against a fake host and an end-to-end test against the real runner host.
- **An App-backed run's token** works for at most an hour, only for the run's repositories, and only for contents and pull requests. A copy stolen from the host is worth that much (R27, 8.33 for credentials backed by the App).
- **Q11 closes.** No token that Tinfirm mints carries `workflows`, so GitHub refuses a push that changes workflow files with such a token (to verify), and 7.6 is built for those credentials.
- **Members' own tokens stay long-lived and broad.** They gain the revocation, not a shorter life. On a company host they remain the largest standing exposure that R27 names, with GitLab's tokens.
- **A revoked run's unpushed commits do not reach the code host.** They stay in the host's kept failed workspace, if the operator keeps one. That is what a revocation means. The alternative is open question 1.
- **A cancelled run whose binding failed no longer pushes.** Today, pausing a system with `stopRunning` cancels runs that then push with the paused system's tokens.
- **A new credential to protect: the App's private key.** Whoever holds it mints tokens for every installation of the App (8.2, no longer hypothetical). It is a vault secret, revealed only to mint, and rotated on the system's page.
- **GitHub is called more:** one mint per run, credential and access at the assignment, one an hour per long run, and the server's own mints as for `CLIENT`. GitHub's limits on minting are to verify.
- **A mirror per credential, not per token,** for App-backed repositories (decision 8). Mirrors keyed by the token stay as they are for every other account.
- **The refresh is a second reveal of a run's git token through the gate.** ADR-0105 holds without a change: the refresh inherits the binding's check, the switches and the personal-host rule.
- **A kill switch that holds does not revoke.** A paused agent or trigger without `stopRunning` lets its runs finish, as it does today. An App-backed run's token then stops being renewed and ends within the hour, while a personal access token's run keeps its token (open question 5).
- **Nothing new passes through the server** but small frames and requests. Clones, fetches and pushes stay between the host and the code host (R29).
- **ADR-0112 is unchanged.** On a separated host the tokens are on the control host only; with this decision they are also shorter-lived and revocable there.
- **The threat model changes** when this is built:
  - R27: the App's tokens, the revocation, and members' tokens as the remaining exposure;
  - R44: closed for hosts with the feature, with decision 5's limits;
  - 8.33: the revocation, and App-backed credentials;
  - §6.6: the git token's row (an hour for the App's, a revocation path for all), and a new row for the App's private key;
  - Q11: closed;
  - Q41: closed for GitHub and for revocation, with GitLab's follow-up;
  - 6.20: the record loses a revoked token and holds an App's token for at most an hour;
  - A10, 8.2 and 7.6: the App exists.

## Alternatives considered

- **A git relay in the server,** the shape of the gateway that inspired the ticket, where every request is checked and the token is injected on the server. Rejected in the ticket and again in ADR-0112:
  - every clone and push would use the server's bandwidth;
  - git would be bound to the one replica that holds the runner connections (R29);
  - it would duplicate the host's git proxy and push lease, which work;
  - the host's own token would still be on the sandbox's machine.

  Once ADR-0112's control host exists, the control host is that relay, outside the server.
- **Rotating personal access tokens.** Tinfirm cannot rotate a person's own token.
- **Shorter run time limits.** They do not fit agents that work for hours.
- **The host asks the server before each git operation,** as the relay checks each model request. Every blob that a partial clone fetches lazily would cost a round trip to the server, and a server that cannot be reached would stop git. A durable command, sent once and sent again at `hello`, gives the same effect within a second.
- **Cancel the run in place of revoking the token.** A cancelled run takes its work back and pushes it with the token, so a cancel alone would push exactly what the revocation is meant to stop. It also ends the agent's turn where the ticket lets it go on.
- **Cancel the runs of a host without the feature.** Not chosen: the ticket keeps today's behaviour for older hosts, and the cancel would still push.
- **A revocation without repositories, meaning "every token of the run"** (the ticket's optional `repository`). Rejected: the server always knows which bindings failed, and a frame should never mean more than the server checked.
- **One token per repository.** More mints, and no narrower token than one per credential and access.
- **The installation's full permissions for a run's token.** That is what the ticket's narrowing exists to avoid.
- **A second lock-step release, as ADR-0113 had.** Not needed: everything here is new and optional, so a feature carries it.

## To specify and verify during the build

These are parts of the work, not of the decision. Each is settled when its code exists.

- **GitHub's API,** checked against GitHub's documentation and a real App before the build depends on it. This record states only these facts: an installation access token expires after one hour, and it can be limited to repositories and permissions when it is created. To verify:
  - the mint's endpoint and body (the repositories by name or by id, the permissions);
  - the App's JWT (its algorithm and its longest life);
  - how the installations are listed, and how a repository's installation is found;
  - whether `metadata: read` comes with every token;
  - whether a token can be asked for a shorter life than the hour;
  - whether a push that changes `.github/workflows/` is refused without `workflows`;
  - whether a token can be revoked at GitHub before it expires, and whether Tinfirm should do that at a revocation;
  - the user name for git over HTTPS with such a token (`x-access-token`, as the server sends today);
  - the bot user that an installation's credential reports as its identity;
  - the limits on minting.
- **What git in the sandbox shows** of the proxy's 403 text, and whether the agent learns the reason from it or needs another path, such as a `run.steer` where the host lists it.
- **The holder of a run's tokens** in the runner host, and how the proxy's route reads it on each request.
- **The take-back order** (decision 10), and the record's new field `revoked`.
- **`run_command`** for `REVOKE_GIT`: the migration's columns (the component ids, the reason, the message), its replay at `hello`, and its end with the run.
- **The third phase's check of the bindings** in `RunDelivery`, and its test with a binding that fails between the reveal and the send.
- **The refresh's margins** (10 and 20 minutes) and its pacing, measured against real runs.
- **The App's setup:** whether the system's page also offers GitHub's manifest flow to create the App (ADR-0007); and the queue reason's text.
- **The tests of the ticket's acceptance criteria:** a credential disabled during a run stops its next push within a second (an integration test with a fake host, and an end-to-end test against the real runner host); a run on an App-backed credential never receives a token valid for longer than an hour or for a repository outside its workspace (a fake GitHub).
- **The documentation:** the operator's pages on systems (setting up the App), accounts, and the runner protocol's features, and the release notes.

## Open questions

1. **A revoked run: go on without git, or fail at once?** This record follows the ticket: the run goes on, its fetches fail, and it ends `GIT_ERROR` only where commits are left unpushed. Failing it at once would stop the agent from working on what it can no longer push, and would lose the same commits.
2. **One App per system, or per company?** This record puts the App on the `GITHUB` system, beside the system's OAuth app. A company with several `GITHUB` systems for one GitHub organisation would then register the App on each.
3. **GitHub Enterprise Server.** Does the App work on a system whose API is a company's own GitHub Enterprise Server in this scope, or on github.com alone at first?
4. **`pull_requests` on the run's token.** The ticket asks for it. The server opens change requests itself, with the credential's own token, so the host's git does not need it. Keep it, as the ticket says, or leave the run with `contents` alone?
5. **Kill switches that hold.** A paused agent or trigger without `stopRunning` lets its runs finish today, so this record does not revoke for it (decision 2). Should such a pause revoke the runs' git tokens as well?
