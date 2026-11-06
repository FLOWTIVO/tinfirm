package com.flowtivo.tinfirm.shared.web;

/**
 * Stable, machine-readable problem codes (CONVENTIONS §6), sent as the {@code code} property: those of the instance
 * and company core, of runner hosts and of the MCP endpoint.
 */
public enum ProblemCode {
    VALIDATION_FAILED,
    RATE_LIMITED,
    DUPLICATE_EMAIL,
    DUPLICATE_SECRET_NAME,
    /**
     * 409: the Secrets API deletes no managed secret that a service identity's account or a system's app keeps; it
     * goes when the account is disconnected or the app removed, on their pages (the package 1 contract, I3). Nor does
     * it replace or delete the admin key of a company's own LiteLLM while the model settings keep it: a new key is
     * entered there, which turns the relay off until the gateway passes its test again (the package 2 contract, §3.1).
     */
    SECRET_MANAGED,
    DUPLICATE_MEMBER,
    USER_NOT_FOUND,
    LAST_OWNER,
    /**
     * 409: the member's role is decided by an identity provider at every sign-in, or by SCIM (ADR-0088 decision 3);
     * {@code details.provider} names it (the registration id, or {@code scim}). Making them OWNER is allowed.
     */
    ROLE_MANAGED,
    LAST_INSTANCE_ADMIN,
    NOT_LOCAL_USER,
    WRONG_PASSWORD,
    EMAIL_IN_USE,
    /** 409: the company has a live agent with this name, ignoring case (the package 3 contract, §7). */
    DUPLICATE_AGENT_NAME,
    /** 409: the company has a live agent with this handle (contract §7, ADR-0034). */
    DUPLICATE_AGENT_HANDLE,
    /** 400: a tool, repository, default or switch of a system the agent does not list (contract §7). */
    SYSTEM_NOT_ON_AGENT,
    /** 400: a tool its system does not serve, or that the agent does not list (contract §7). */
    UNKNOWN_TOOL,
    /** 400: settings name a runtime the agent does not list (contract §5.2). */
    RUNTIME_NOT_LISTED,
    /** 400: settings name a model the person's model access does not allow (contract §5.2). */
    MODEL_NOT_ALLOWED,
    /** 400: settings paid by a budget name no model; the company's gateway serves only the models it lists. */
    MODEL_REQUIRED,
    /** 400: settings name a subscription that is not on the person's own runner host or does not serve the runtime. */
    SUBSCRIPTION_NOT_SERVING,
    /** 400: settings would widen the agent instead of narrowing it (I4). */
    SETTINGS_WIDEN,
    /** 403: the caller sees the agent but may not use it (contract §5.1). */
    AGENT_NOT_USABLE,
    /** 409: a memory has no room for a note a trigger's audience accepts (ADR-0063): delete a note first. */
    MEMORY_FULL,
    /** 403: the caller sees the agent but may not edit it (contract §5.1). */
    AGENT_NOT_EDITABLE,
    /** 409: a run of the agent is queued or running, so it is not deleted (contract §5.1). */
    AGENT_HAS_ACTIVE_RUNS,
    /** 409: the agent may reach at most 100 network hosts (contract §3.5). */
    NETWORK_HOST_LIMIT,
    /** 409: approving while the agent is paused; a paused agent runs no approved call (contract §5.8, I18). */
    AGENT_PAUSED,
    /** 409: the decision was decided or cancelled already (I16). */
    DECISION_DECIDED,
    /** 409: the company has a runner host with this name. */
    DUPLICATE_RUNNER_HOST_NAME,
    /** 409: a runner host cannot be deleted while runs use it. */
    RUNNER_HOST_HAS_ACTIVE_RUNS,
    /**
     * 409: pool settings for a runner host that keeps no ready sandboxes: a personal host, or one that has not listed
     * {@code sandbox.pool} (ADR-0099).
     */
    RUNNER_HOST_POOL_UNSUPPORTED,
    /**
     * 409: the company has no LiteLLM that passed its admin test, so no conversation can start until an admin
     * configures one (the package 2 contract, §9).
     */
    GATEWAY_NOT_CONFIGURED,
    /** 502: the model settings' test did not reach LiteLLM's admin API, or LiteLLM refused the admin key (§9). */
    GATEWAY_UNREACHABLE,
    /**
     * 409: a key is entered only at a gateway whose keys its team provides (ADR-0094); Tinfirm makes, rotates and
     * deletes the keys of the others. Also a member's own key where the company does not let members enter theirs, or
     * where an admin entered it.
     */
    MODEL_KEYS_MANAGED,
    /** 400: the company's gateway refused the key entered (ADR-0094). */
    MODEL_KEY_REFUSED,
    /**
     * 409: the run's model access and accounts fit no runner host: a subscription on a member's personal host with a
     * workspace account that is not that member's (§5.2, §9).
     */
    CANNOT_RUN_ANYWHERE,
    /** 400: a host credential named on a company runner host; only a personal host holds credentials (M5, §9). */
    PERSONAL_HOST_CREDENTIAL_ONLY,
    /** 409: the subscription a run names no longer exists on its owner's personal host (§5.1, §9). */
    SUBSCRIPTION_GONE,
    /** 409: the user has as many active personal access tokens as allowed (25, ADR-0045). */
    TOKEN_LIMIT,
    /** 409: an expired personal access token is not renewed; the user creates a new one (CONVENTIONS §5). */
    TOKEN_EXPIRED,
    /**
     * 403: creating a personal access token needs a recent sign-in for a user without a password (an identity
     * provider's): a session that signed in less than 10 minutes ago (CONVENTIONS §5).
     */
    RECENT_SIGN_IN_REQUIRED,
    /**
     * 401 on the MCP endpoint: the bearer token is unknown, malformed, revoked or expired, or its user is
     * deactivated (ADR-0045). The response carries {@code WWW-Authenticate: Bearer error="invalid_token"}.
     */
    TOKEN_INVALID,
    /**
     * 403 on the MCP endpoint: the tool needs a scope the bearer credential lacks (ADR-0045). The response carries
     * {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="..."}; its body is a JSON-RPC error whose
     * {@code data.code} is this code.
     */
    INSUFFICIENT_SCOPE,
    /** 409: the company has a group with this name, ignoring case (ADR-0049 §2). */
    DUPLICATE_GROUP_NAME,
    /** 409: the company has a service identity with this name, ignoring case (the package 1 contract, §7). */
    DUPLICATE_SERVICE_IDENTITY_NAME,
    /** 409: the principal is suspended, and what was asked would use it (contract §7, I7). */
    PRINCIPAL_SUSPENDED,
    /** 409: the company has a system with this name, ignoring case (contract §7). */
    DUPLICATE_SYSTEM_NAME,
    /** 400: the base URL is not usable, or the outbound guard refuses it (contract §7). */
    INVALID_BASE_URL,
    /** 400: a system of this kind cannot be registered (yet), or does not offer what was asked (contract §7). */
    SYSTEM_KIND_INVALID,
    /** 400: a personal system is an MCP server, held and used by its owner only, never granted (I6). */
    PERSONAL_SYSTEM,
    /** 409: the company switched personal MCP servers off (contract §10). */
    PERSONAL_SYSTEMS_OFF,
    /** 409: the system has no app, so accounts on it cannot sign in with OAuth (contract §7). */
    OAUTH_NOT_AVAILABLE,
    /**
     * 400: the MCP server did not answer {@code initialize} and {@code tools/list} without a credential, so it cannot
     * be set to need no account (ADR-0071).
     */
    CREDENTIAL_REQUIRED,
    /**
     * 409: the MCP server moved to another endpoint while the account or credential was checked at the one before
     * (ADR-0079): nothing is stored, since the move removed every account and credential on it; connect it again at
     * the new one.
     */
    SYSTEM_MOVED,
    /**
     * 409: the MCP server's discovery names another token or revocation endpoint than its app's, while OAuth accounts
     * or credentials are on it, whose tokens would go there (the security review of 2026-10-27, M8): {@code details}
     * lists what a confirmed save ({@code confirmEndpointsChange}) removes.
     */
    SYSTEM_APP_ENDPOINTS_CHANGED,
    /** 409: the code host has a repository with this URL. */
    DUPLICATE_REPOSITORY,
    /** 400: the system refused the token (contract §7: {@code account.refused}). */
    ACCOUNT_REFUSED,
    /** 409: another member's own account, or a credential of the system, is this identity there (I2). */
    IDENTITY_IN_USE,
    /** 400: a grant lends only a system's credential, never a member's own account (I5; ADR-0072). */
    GRANT_NOT_SERVICE,
    /** 409: the credential is lent to this group already. */
    DUPLICATE_GRANT,
    /** 409: the system has a credential of this name, ignoring case (ADR-0072). */
    DUPLICATE_CREDENTIAL_NAME,
    /**
     * 409: the conversation can never run as its person's settings stand: no gateway, an inactive payer, a subscription
     * gone ({@code details.reason}, package 2's refusal; the package 3 contract, §5.7).
     */
    MODEL_ACCESS_UNAVAILABLE,
    /**
     * 409: no registered runner host, connected or not, may run the conversation's credentials ({@code details.reason}:
     * {@code NO_RUNNER_HOST}, {@code NO_HOST_FOR_CREDENTIALS}, {@code CANNOT_RUN_ANYWHERE}; contract §5.7).
     */
    NO_HOST_FOR_CONVERSATION,
    /** 409: more than 20 repositories would start the workspace without a choice of them (contract §5.6). */
    TOO_MANY_REPOSITORIES,
    /** 409: the conversation ended and takes nothing more (contract §5.7). */
    CONVERSATION_ENDED,
    /**
     * 409: only the conversation's person replies, answers, approves, renames, binds in it, and adds and deletes its
     * files (I15).
     */
    CONVERSATION_BOUND,
    /** 409: the conversation has a queued or running turn (contract §5.7). */
    TURN_ACTIVE,
    /** 409: the conversation's last turn neither failed nor was cancelled (contract §5.7). */
    NOTHING_TO_RETRY,
    /** 409: the binding does not wait for what was asked (contract §5.3). */
    BINDING_NOT_WAITING,
    /** 400: the grant is not one the binding offers (contract §5.3). */
    GRANT_NOT_OFFERED,
    /** 413: the file is larger than the server allows (25 MB by default; ADR-0014, contract §3.13). */
    ATTACHMENT_TOO_LARGE,
    /**
     * 409: the conversation has as many files, or as many bytes of them, as the server allows (50 files and 250 MB by
     * default; ADR-0014, contract §3.13).
     */
    TOO_MANY_ATTACHMENTS,
    /** 409: the conversation already has a file with this name (ADR-0014). */
    DUPLICATE_ATTACHMENT_NAME,
    /**
     * 422: the file holds the value of a vault secret (never stored) or looks like a credential (stored only when an
     * OWNER or ADMIN who is the conversation's person acknowledges it); the problem names the match, never the value
     * (ADR-0014).
     */
    CREDENTIAL_FOUND,
    /** 422: the file cannot be scanned for credentials completely (an encrypted or damaged archive, ADR-0014). */
    ATTACHMENT_NOT_SCANNABLE,
    /** 409: the file is the document of an output of the conversation, which keeps it (contract §3.13). */
    ATTACHMENT_IS_OUTPUT,
    /**
     * 409, the MCP endpoint's tool error: a team's approval is decided in the app or the chat, where Tinfirm shows the
     * call and its arguments, never through a person's AI client (the package 6 contract, open point 15).
     */
    DECIDE_IN_APP,
    /** 400: an export of the audit chain would hold more than 1 000 000 events (the package 6 contract, §5.8). */
    AUDIT_EXPORT_TOO_LARGE,
    /**
     * 400: a retention period out of its range, or chats' content kept longer than other content (the package 6
     * contract, §3.7).
     */
    RETENTION_INVALID,
    /**
     * 409: the erasure is not allowed: an open personal conversation of a member still in the company, or a principal
     * that is not a member's (the package 6 contract, §5.9).
     */
    ERASURE_NOT_ALLOWED,
    /**
     * 409: an open legal hold covers what the erasure would purge; {@code holdIds} names the holds (the package 6
     * contract, §5.9, G16).
     */
    ON_HOLD,
    /** 410: the content was removed under retention or by an erasure; what describes it stays (contract §3.7). */
    CONTENT_PURGED,
    /**
     * 400: a spend query over more than 366 days, with {@code to} not after {@code from}, more than two groupings or
     * an unknown zone (the package 6 contract, §5.2).
     */
    SPEND_RANGE_INVALID,
    /** 409: the company has a live trigger with this name, ignoring case (the package 4 contract, §7). */
    DUPLICATE_TRIGGER_NAME,
    /**
     * 400: the trigger cannot work: an event, value, subject, scope, condition, action, cron or zone that its source
     * does not take, or an event that could never fire (ADR-0043; the package 4 contract, §5.2 step 2).
     */
    INVALID_TRIGGER,
    /** 400: a tracker trigger needs an account with an identity on its source system (§5.2 step 3, T2). */
    SOURCE_ACCOUNT_REQUIRED,
    /**
     * 400: the trigger's author is not entitled to the account named for a system ({@code details.systemId};
     * {@code Accounts.bind(Trigger)} answered {@code NotEntitled}, §5.2 step 3, T1).
     */
    ACCOUNT_NOT_ENTITLED,
    /**
     * 400: the row on the trigger's source names a credential lent read-only to the service account it acts through
     * ({@code details.systemId}), while the trigger's results write there (the comment, or an action; I9, ADR-0072):
     * name a credential lent {@code WRITE}, or turn the results' writes off.
     */
    ACCOUNT_READ_ONLY,
    /** 400: the payer is neither the author nor a service identity the author may use (§5.2 step 4, T1). */
    MODEL_ACCESS_NOT_ENTITLED,
    /**
     * 400: the trigger's events are open to anyone who may comment while a row holds the author's own account and a
     * write tool runs without approval; saving needs {@code acknowledgeOpenEvents} (§5.8).
     */
    OPEN_EVENTS_NOT_ACKNOWLEDGED,
    /** 403: the caller sees the trigger but may not edit it: only its author and admins do (§5.1, §11). */
    TRIGGER_NOT_EDITABLE,
    /**
     * 403: an admin's save on a trigger's author's behalf (an edit, a reassignment) names the author's own account or
     * adds the author's own MCP server where the trigger had none: only the author chooses those (the security review
     * of 2026-10-27, H2).
     */
    AUTHOR_ACCOUNT_NOT_ADMINS,
    /** 409: a system has 100 live triggers already, or the company 50 schedules (§3.1). */
    TOO_MANY_TRIGGERS,
    /** 409: the trigger is paused: it fires nothing until it is resumed (§5.6, §5.11). */
    TRIGGER_PAUSED,
    /** 409: the trigger's source account cannot read the subject (§5.6, run now). */
    SUBJECT_NOT_VISIBLE,
    /** 409: the subject is outside the trigger's scope, subject kind or conditions; the detail names the clauses. */
    TRIGGER_CONDITIONS_FAIL,
    /** 409: a conversation on a tracker issue takes answers only; its place is the issue (§5.8, open point 4). */
    PLACE_IS_EXTERNAL,
    /**
     * 409: a live trigger's account table or payer names the service identity (§5.11; the package 1 contract), or it
     * holds a chat system's bot (the package 5 contract, §3.2).
     */
    SERVICE_IDENTITY_IN_USE,
    /** 400: a trigger's firing limit above the company's maximum (§11, open point 15). */
    TRIGGER_LIMIT_TOO_HIGH,
    /**
     * 409: the company's settings changed since the version the change named ({@code PUT /settings},
     * {@code PUT /chat-settings}): the caller reads them again and changes the newer ones.
     */
    SETTINGS_CHANGED,
    /**
     * 400: the bot's token names another workspace or bot user than the chat system's (the package 5 contract, §3.1,
     * §7).
     */
    BOT_IDENTITY_MISMATCH,
    /** 400: a chat system's bot account named in a grant, a binding or a trigger's table (C9). */
    BOT_ACCOUNT,
    /**
     * 409: the bot's account changes and goes only through {@code PUT} and {@code DELETE /systems/{id}/bot}, not on
     * its service identity's page (C9, §3.2).
     */
    BOT_ACCOUNT_IN_USE,
    /** 409: the chat system has no bot (package 5, §7). */
    BOT_NOT_SET,
    /**
     * 400: a channel of a channel trigger the bot is not in, or that is archived, deleted or shared with another
     * organisation (package 5, §5.7).
     */
    CHANNEL_UNAVAILABLE,
    /** 400: the trigger's author is not linked on the chat system, or not a member of a channel (package 5, §5.7). */
    CHANNEL_NOT_JOINED,
    /** 409: the channel has a trigger of the agent, or a default trigger, already (package 5, §5.7). */
    CHANNEL_TAKEN,
    /** 400: a webhook's mapping names a pointer that is not RFC 6901, or exceeds a limit (package 5, §5.12). */
    MAPPING_INVALID,
    /** 400: a webhook's deliveries a minute above the company's maximum (package 5, §5.12). */
    WEBHOOK_LIMIT_TOO_HIGH,
    /** 409: the company has a chat system of this workspace or tenant already (package 5, §3.1). */
    CHAT_WORKSPACE_TAKEN,
    /** 410: the sign-in link expired (package 5, §5.4). */
    CHAT_LINK_EXPIRED,
    /** 409: the sign-in link was used (package 5, §5.4). */
    CHAT_LINK_USED,
    /** 409: the member holds another identity on the chat system (package 5, §5.4; I1). */
    ALREADY_LINKED,
    /** 410: the Gmail add-on's sign-in link expired (ADR-0083). */
    GMAIL_LINK_EXPIRED,
    /** 409: the Gmail add-on's sign-in link was used (ADR-0083). */
    GMAIL_LINK_USED,
    /** 400: the system is not a chat system (package 5, open point 16). */
    NOT_A_CHAT_ACCOUNT,
    /**
     * 409: the company's runs are stopped (the package 6 contract, §5.4): a conversation is not started and an approval
     * is not decided until the stop is lifted; answers, denials and messages are taken, and their turns wait.
     */
    COMPANY_STOPPED,
    /** 409: the system is paused; testing it, refreshing its tools and connecting accounts on it wait (§5.4). */
    SYSTEM_PAUSED,
    /** 409: nobody suspends themselves (§5.4). */
    CANNOT_SUSPEND_SELF,
    /**
     * 409: the checklist of a member who left still has open items (paused triggers, emptied groups, accounts they
     * connected) and was not closed with {@code force} (§5.5).
     */
    DEPARTURE_OPEN,
    /**
     * 422: the caller sent this {@code Idempotency-Key} (REST) or {@code requestId} (MCP) before with another operation
     * or another request; a key names one submission (package R, §3.5).
     */
    IDEMPOTENCY_KEY_REUSED,
    /**
     * 409: the inbox's notice asks the caller to act (a departure's checklist, a silent chat subscription, a trigger's
     * widening, reassignment or broken account): it is not dismissed, and leaves once handled (CONVENTIONS §15.7).
     */
    NOTICE_NOT_DISMISSIBLE,
    /**
     * 400: a skill's upload breaks a rule of its format (the skills contract, §2 and §4.2): {@code details.rule}
     * ({@code NOT_A_ZIP}, {@code PATH}, {@code NO_SKILL_MD}, {@code FRONT_MATTER}, {@code NAME}, {@code DESCRIPTION},
     * {@code KEY}) and {@code details.path} where one applies.
     */
    SKILL_INVALID,
    /**
     * 400: a skill's upload passes a limit (§4.2): {@code details.limit} ({@code FILES}, {@code BYTES},
     * {@code UPLOAD}).
     */
    SKILL_TOO_LARGE,
    /** 400: a new version of a skill names another skill in its front matter (§4.2 step 3). */
    SKILL_NAME_CHANGED,
    /** 409: a live skill of the company has the name already (§4.2 step 3). */
    DUPLICATE_SKILL_NAME,
    /**
     * 422: a skill's files hold the value of a vault secret, a known credential format or content that cannot be
     * scanned; nothing is stored and no match can be acknowledged (§4.2 step 5), {@code credentialMatches}.
     */
    SKILL_CONTAINS_CREDENTIAL,
    /** 403: the caller sees the skill but does not edit it: its owner, OWNER and ADMIN do (§4.1). */
    SKILL_NOT_EDITABLE,
    /** 409: a skill has 100 versions, the most it keeps (§4.2 step 6). */
    SKILL_VERSION_LIMIT,
    /** 400: a deleted skill is neither added to an agent nor moved to another version (§5.1). */
    SKILL_DELETED,
    /** 400: two skills of one name on an agent: their folders would be one (§5.1). */
    SKILL_NAME_CONFLICT,
    /** 400: a settings switch names a skill the agent does not have (§5.3). */
    SKILL_NOT_ON_AGENT,
    /** 409: {@code TINFIRM_ERROR_REPORTS} sets the mode of error reporting, which the page does not change (ADR-0070). */
    ERROR_REPORTING_SET_BY_OPERATOR,
    /** 409: automatic delivery of error reports needs {@code TINFIRM_ERROR_REPORTS_DSN} (ADR-0070). */
    ERROR_REPORTING_NO_DESTINATION,
    /**
     * 409: OWNER, ADMIN or MEMBER would go to a person without a seat while every seat the licence holds is taken
     * (ADR-0077, decision 5): {@code details.licensed}, {@code details.used}.
     */
    SEATS_EXHAUSTED,
    /**
     * 403: the edition in force does not hold the feature (ADR-0077, decision 4): {@code details.feature},
     * {@code details.edition}.
     */
    EDITION_REQUIRED,
    /** 403: a Viewer starts or continues no work, and decides nothing (ADR-0077, decision 5). */
    VIEWER_READ_ONLY,
    /** 409: the deployment does not allow SCIM for the company ({@code companies[].scim.enabled}, ADR-0087). */
    SCIM_NOT_ENABLED,
    /** 409: the company holds two active SCIM tokens already (ADR-0087, decision 3): revoke the old one first. */
    SCIM_TOKEN_LIMIT,
    /** 400: not a licence key, or one not signed by FLOWTIVO (ADR-0077, decision 6). */
    LICENCE_KEY_INVALID,
    /** 409: the key ended. */
    LICENCE_KEY_EXPIRED,
    /** 409: the instance holds a key of this licence with the same or a higher revision (decision 6). */
    LICENCE_KEY_SUPERSEDED,
    /**
     * 409: the key would hold fewer seats than the roles that hold one (decision 5, a smaller renewal):
     * {@code details.required}, {@code details.licensed}.
     */
    LICENCE_SEATS_TOO_FEW
}
