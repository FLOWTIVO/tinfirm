package com.flowtivo.tinfirm.audit;

/**
 * The audited actions of the instance and company core (CONVENTIONS §8), of runner hosts (ADR-0016), and of the
 * platform redesign's packages (the package 1 contract, §8).
 */
public enum AuditAction {

    AUTH_LOGIN_SUCCEEDED("auth.login.succeeded"),
    AUTH_LOGIN_FAILED("auth.login.failed"),
    AUTH_LOGOUT("auth.logout"),
    USER_CREATED("user.created"),
    USER_UPDATED("user.updated"),
    USER_PASSWORD_SET("user.password.set"),
    USER_PASSWORD_CHANGED("user.password.changed"),
    COMPANY_CREATED("company.created"),
    COMPANY_UPDATED("company.updated"),
    ADMIN_COMPANY_ACCESSED("admin.company.accessed"),
    MEMBER_ADDED("member.added"),
    MEMBER_ROLE_CHANGED("member.role.changed"),
    MEMBER_REMOVED("member.removed"),
    /**
     * Who decides a role changed while the role stayed (ADR-0088): in the company's chain, either one member's role
     * taken by a provider or SCIM, with {@code userId}, {@code role} and the sources before and after, or a provider or
     * SCIM that stopped deciding (decision 7), whose memberships became the app's, with their {@code count}.
     */
    MEMBER_ROLE_SOURCE_CHANGED("member.role_source.changed"),
    /**
     * An instance admin set the password of a member (the owner's decision Q7 of 2026-10-27): in the chain of every
     * company the user belongs to, actor the instance admin, target {@code user}, {@code userId}. The instance chain
     * keeps its {@code user.password.set}.
     */
    MEMBER_PASSWORD_RESET_BY_INSTANCE_ADMIN("member.password_reset_by_instance_admin"),
    /** A group of members was created (ADR-0049 §2): its name and its members by id. */
    GROUP_CREATED("group.created"),
    /** A group was replaced: what changed, its name, and the members added and removed by id. */
    GROUP_UPDATED("group.updated"),
    /** A group was deleted: its name. */
    GROUP_DELETED("group.deleted"),
    SECRET_CREATED("secret.created"),
    SECRET_UPDATED("secret.updated"),
    SECRET_DELETED("secret.deleted"),
    SECRET_ACCESSED("secret.accessed"),
    VAULT_REWRAPPED("vault.rewrapped"),
    /**
     * An instance admin applied a licence key (ADR-0077), in the instance chain: its licence id, revision, edition,
     * seats, companies and period, whether it is held as the next key, and the key it replaced; never the key.
     */
    LICENCE_KEY_APPLIED("licence.key.applied"),
    /** The next licence key took over on its start date (ADR-0077), by SYSTEM in the instance chain. */
    LICENCE_KEY_ACTIVATED("licence.key.activated"),
    /**
     * The company's OWNER issued a SCIM token (ADR-0087, decision 3), in the company's chain: its id, name and expiry,
     * never the token.
     */
    SCIM_TOKEN_CREATED("scim.token.created"),
    /** The company's OWNER revoked a SCIM token (ADR-0087), in the company's chain: its id and name. */
    SCIM_TOKEN_REVOKED("scim.token.revoked"),
    /**
     * A SCIM request was refused for its token (ADR-0087, decision 3), in the instance chain: the reason and the
     * client address, at most once a minute per address and reason.
     */
    SCIM_REFUSED("scim.refused"),
    /** The company's SCIM token created a member (ADR-0087, decision 9): the user's id, never their email. */
    SCIM_USER_CREATED("scim.user.created"),
    /** The company's SCIM token changed a member: the names of the attributes changed, never their values. */
    SCIM_USER_CHANGED("scim.user.changed"),
    /** The company's SCIM token deactivated a member: suspended at once, their content kept. */
    SCIM_USER_DEACTIVATED("scim.user.deactivated"),
    /** The company's SCIM token reactivated a member: SCIM's own suspension lifted, and only it. */
    SCIM_USER_REACTIVATED("scim.user.reactivated"),
    /** The company's SCIM token deleted a member: they left the company, their content kept (never an erasure). */
    SCIM_USER_DELETED("scim.user.deleted"),
    /**
     * A SCIM user's first sign-in was bound to them (ADR-0087, decision 6), in the company's chain: the provider, and
     * whether by {@code externalId} or by email.
     */
    USER_IDENTITY_BOUND("user.identity_bound"),
    /**
     * A read of a SAML provider's IdP metadata found other signing certificates (ADR-0086, decision 13), by SYSTEM in
     * the instance chain: the provider and the old and new certificates' SHA-256 fingerprints and end dates.
     */
    SAML_IDP_KEYS_CHANGED("saml.idp_keys.changed"),
    /**
     * A read of a SAML provider's IdP metadata was refused because the document names another IdP than the one the
     * provider is pinned to (H4 of the security review of 2026-10-27), by SYSTEM in the instance chain: the provider,
     * the pinned entity ID and the one the document named. The last good document stays in use.
     */
    SAML_IDP_ENTITY_ID_CHANGED("saml.idp_entity_id_changed"),
    /**
     * A read of a SAML provider's IdP metadata failed, the first time after a good one (ADR-0086, decision 13), by
     * SYSTEM in the instance chain: the provider and the reason (unreachable, invalid, no_idp, expired, too_large,
     * entity_id_in_use).
     */
    SAML_METADATA_FAILED("saml.metadata.failed"),
    /**
     * A user created a personal access token (ADR-0045), in the instance chain: its scope and expiry, never the token
     * or its name.
     */
    TOKEN_CREATED("token.created"),
    /**
     * A personal access token was revoked (ADR-0045), in the instance chain: by its user, or with {@code reason}
     * {@code PASSWORD_CHANGED}, {@code PASSWORD_RESET} or {@code LEFT_COMPANY} (with {@code companyId}) by the change
     * that revoked it (CONVENTIONS §5).
     */
    TOKEN_REVOKED("token.revoked"),
    /** A user renewed a personal access token for another 90 days, in the instance chain: its new expiry. */
    TOKEN_RENEWED("token.renewed"),
    /**
     * Tinfirm's authorization server resolved a client from its Client ID Metadata Document for the first time, or
     * after the document changed (ADR-0045): actor SYSTEM, target {@code oauth_client}, in the instance chain.
     */
    OAUTH_CLIENT_RESOLVED("oauth.client.resolved"),
    /**
     * A user consented to a client's access to a company (ADR-0045), in that company's chain: target
     * {@code oauth_grant}, the client's domain, the company, the scopes.
     */
    OAUTH_CONSENT_GRANTED("oauth.consent.granted"),
    /** A user revoked an OAuth grant on {@code /me} (ADR-0045), in the company's chain, as for a consent. */
    OAUTH_GRANT_REVOKED("oauth.grant.revoked"),
    /**
     * A runner host was registered: {@code name}, {@code scope} ({@code COMPANY} or {@code PERSONAL}) and, for a
     * personal host, {@code ownerPrincipalId} (the package 2 contract, §10).
     */
    RUNNER_HOST_REGISTERED("runner_host.registered"),
    /**
     * A runner host was removed, its token revoked: {@code name}, {@code scope}, and {@code cause} when nobody asked
     * for it ({@code principal.left}: its owner left the company).
     */
    RUNNER_HOST_REMOVED("runner_host.removed"),
    RUNNER_HOST_UPDATED("runner_host.updated"),
    /**
     * A turn's run was created QUEUED (the package 3 contract, §8): {@code conversationId}, {@code agentId},
     * {@code turn}, {@code turnReason}, {@code payerPrincipalId}, {@code modelAccess} ({@code BUDGET} or
     * {@code SUBSCRIPTION}).
     */
    RUN_STARTED("run.started"),
    /**
     * A run was sent to a runner host: {@code conversationId}, {@code runnerHostId}, {@code hostKind}
     * ({@code COMPANY}, {@code PERSONAL}), {@code epoch}, {@code payerPrincipalId}, {@code modelAccess}: the model
     * access that paid and the host that ran it. Actor SYSTEM.
     */
    RUN_ASSIGNED("run.assigned"),
    /**
     * A run ended: {@code conversationId}, {@code turn}, {@code status}, {@code failureClass}, {@code lostCause},
     * {@code pushedRepositoryIds}, {@code commits}, {@code resultOutcome}.
     */
    RUN_FINISHED("run.finished"),
    /** A person cancelled a run: {@code conversationId}, {@code previousStatus}. */
    RUN_CANCELLED("run.cancelled"),
    /**
     * A run lost by lease expiry runs again, its host still running it: {@code conversationId},
     * {@code runnerHostId}, {@code epoch}, {@code reason} (the frame), {@code lostAt}.
     */
    RUN_RESUMED("run.resumed"),
    /**
     * A conversation's tool call ran on its system (the package 3 contract, §8), by the agent: {@code conversationId},
     * {@code runId}, {@code systemId}, {@code tool}, {@code effect}, {@code mode}, {@code accountId},
     * {@code holderPrincipalId}, {@code grantId}, {@code decisionId} for an approved call, {@code bindingId} for a
     * parked one, {@code ok}, {@code code}. Never the arguments or the result.
     */
    TOOL_CALLED("tool.called"),
    /** A tool call was refused by the agent, the settings or the binding, once per run and tool: the tool and the step. */
    TOOL_DENIED("tool.denied"),
    /**
     * An {@code UNSAFE} tool call of a run that Tinfirm lost track of was settled without running it again (package R,
     * §3.2), by SYSTEM, target the conversation: {@code runId}, {@code requestId}, {@code systemId}, {@code tool},
     * {@code attempt}, {@code cause} ({@code TIMEOUT}: past the tool timeout; {@code RESTART}: its replica stopped).
     * The agent got {@code TOOL_INTERRUPTED}. Never the arguments or a result.
     */
    TOOL_INTERRUPTED("tool.interrupted"),
    /** A question or an approval was raised: {@code conversationId}, {@code runId}, {@code kind}, {@code subject}. */
    DECISION_CREATED("decision.created"),
    /** A decision was decided: {@code kind}, {@code subject}, {@code outcome}, {@code scope}, {@code access}. */
    DECISION_DECIDED("decision.decided"),
    /** An open decision was cancelled with its conversation's end or cancel: {@code kind}, {@code cause}. */
    DECISION_CANCELLED("decision.cancelled"),
    /**
     * The company's model settings changed (the package 2 contract, §10): {@code changed} (the names of the settings,
     * {@code adminKey} when a new admin key was stored, never the key), {@code gateway}, {@code relayEnabled}, and the
     * outcome of a test ({@code tested}, {@code ok}, {@code failure}).
     */
    MODEL_SETTINGS_CHANGED("model_settings.changed"),
    /** A principal's LiteLLM key was created in LiteLLM: {@code principalId}, {@code blocked}; never the key. */
    MODEL_KEY_CREATED("model_key.created"),
    /**
     * An admin set or cleared a principal's budget or models override: {@code principalId}, {@code budgetUsd} and
     * {@code models} (null for the company's default), and {@code changed}.
     */
    MODEL_KEY_BUDGET_CHANGED("model_key.budget_changed"),
    /** A principal's key was blocked in LiteLLM, its principal suspended (M6): {@code principalId}. */
    MODEL_KEY_BLOCKED("model_key.blocked"),
    /** A resumed principal's key was unblocked in LiteLLM: {@code principalId}. */
    MODEL_KEY_UNBLOCKED("model_key.unblocked"),
    /**
     * A principal's key was deleted, its secret with it: {@code principalId}, {@code cause} ({@code principal.left},
     * {@code principal.deleted}, {@code gateway.changed}). LiteLLM deletes it after the commit, best effort; the
     * reconciliation deletes what it missed.
     */
    MODEL_KEY_DELETED("model_key.deleted"),
    /**
     * A principal's key spent its budget for the period: LiteLLM refused a relayed request for it
     * ({@code budget_exceeded}), or a sync read its spend at the budget. {@code principalId}, and {@code resetsAt} when
     * known. Actor SYSTEM.
     */
    MODEL_KEY_BUDGET_SPENT("model_key.budget_spent"),
    /**
     * LiteLLM refused a principal's key (401, 403) on a relayed request, other than for a model the key may not use:
     * {@code principalId}, {@code status}, {@code reason} (LiteLLM's, without what it echoes of a key). The key is
     * {@code ERROR} until a sync repairs it. Actor SYSTEM.
     */
    MODEL_KEY_REFUSED("model_key.refused"),
    /**
     * An admin asked to rotate a principal's key at once, its previous key without a grace: {@code principalId},
     * {@code scope} ({@code principal}, or {@code company} for every key of the company). Actor USER.
     */
    MODEL_KEY_ROTATION_REQUESTED("model_key.rotation_requested"),
    /**
     * A principal's key was rotated: the next key, created in LiteLLM with the same team, budget, period, models and
     * spend, is the principal's, and the relay uses it. {@code principalId}, {@code cause} ({@code schedule},
     * {@code request}), {@code graceSeconds} (how long the previous key still serves requests that hold it). Never a
     * key or its id. Actor SYSTEM.
     */
    MODEL_KEY_ROTATED("model_key.rotated"),
    /**
     * The key a rotation replaced was blocked in LiteLLM once its grace ended (at once for a rotation an admin asked
     * for), and is deleted a minute later: {@code principalId}, {@code rotatedAt}. Actor SYSTEM.
     */
    MODEL_KEY_RETIRED("model_key.retired"),
    /**
     * An agent was created (the package 3 contract, §8): {@code name}, {@code handle}, {@code runtimes},
     * {@code ownerPrincipalId}, {@code version}; never the description or the instructions.
     */
    AGENT_CREATED("agent.created"),
    /** An agent was renamed, or its handle, description, instructions or runtimes changed: as created, with {@code changed}. */
    AGENT_CHANGED("agent.changed"),
    /** An agent was deleted (soft): {@code name}, {@code handle}. */
    AGENT_DELETED("agent.deleted"),
    /** An agent's systems and tools changed: the whole list after the change, {@code version}, {@code widens}. */
    AGENT_TOOLS_CHANGED("agent.tools_changed"),
    /** An agent's repositories changed: the whole list after the change, {@code version}, {@code decisionId}. */
    AGENT_REPOSITORIES_CHANGED("agent.repositories_changed"),
    /** An agent's network hosts changed: the whole list after the change, {@code version}, {@code decisionId}. */
    AGENT_HOSTS_CHANGED("agent.hosts_changed"),
    /** The groups that may use an agent changed: {@code groupIds}, {@code added}, {@code removed}, {@code cause}. */
    AGENT_SHARES_CHANGED("agent.shares_changed"),
    /** An agent was paused: {@code stopRunning}, {@code runsCancelled}. */
    AGENT_PAUSED("agent.paused"),
    /** A paused agent was resumed. */
    AGENT_RESUMED("agent.resumed"),
    /** An agent passed to the admins, its owner having left: {@code formerOwnerPrincipalId}. */
    AGENT_PASSED_TO_ADMINS("agent.passed_to_admins"),
    /** A new version widens the one before (contract §11): {@code version} and what widened, by id. */
    AGENT_WIDENED("agent.widened"),
    /** A person accepted an agent's widening up to a version: {@code principalId}, {@code version}, {@code previousVersion}. */
    AGENT_ACCEPTED("agent.accepted"),
    /**
     * An agent's skills changed (the skills contract, §5.1, §10): {@code added} and {@code removed}
     * ({@code [{skillId, name, version}]}), {@code moved} ({@code [{skillId, name, from, to}]}), {@code version}.
     */
    AGENT_SKILLS_CHANGED("agent.skills_changed"),
    /**
     * An agent's instructions or skills changed within a ceiling that cannot act, so the change widens nothing and is
     * announced in its conversations instead (the security review of 2026-10-27, D1; the owner's decision Q4):
     * {@code agentId}, {@code version}, {@code by} (the editing member's principal; null for an instance admin or a
     * system change), {@code changed} ({@code instructions}, {@code skills}). Never the text.
     */
    AGENT_INSTRUCTIONS_CHANGED("agent.instructions_changed"),
    /**
     * A skill was created (the skills contract, §10): {@code name}, {@code version}, {@code sha256}, {@code sizeBytes},
     * {@code fileCount}, {@code ownerPrincipalId}; never its content.
     */
    SKILL_CREATED("skill.created"),
    /**
     * A skill got a new version: {@code name}, {@code version}, {@code previousVersion}, {@code sha256},
     * {@code sizeBytes}, {@code fileCount}.
     */
    SKILL_VERSION_ADDED("skill.version_added"),
    /**
     * The groups or members a skill is shared with changed: {@code groupsAdded}, {@code groupsRemoved},
     * {@code principalsAdded}, {@code principalsRemoved}, {@code cause} ({@code principal.left},
     * {@code group.deleted}).
     */
    SKILL_SHARES_CHANGED("skill.shares_changed"),
    /** A skill was deleted (soft): {@code name}, {@code agentCount}, the live agents that keep it. */
    SKILL_DELETED("skill.deleted"),
    /**
     * A skill passed to the admins, its owner having left: {@code name}, {@code formerOwnerPrincipalId},
     * {@code cause}.
     */
    SKILL_PASSED_TO_ADMINS("skill.passed_to_admins"),
    /**
     * A person's settings of an agent changed: {@code agentId}, {@code principalId}, {@code changed}, the systems and
     * tools switched off or to {@code ASK}, the own servers added; never the instructions.
     */
    SETTINGS_CHANGED("settings.changed"),
    /**
     * The agent saved a note of a memory (ADR-0063), or proposed one for a trigger's: {@code agentId},
     * {@code principalId} or {@code triggerId}, {@code conversationId}, {@code runId}, {@code number}, {@code state},
     * {@code about}, {@code replaces}, {@code characters}; never the note.
     */
    MEMORY_SAVED("memory.saved"),
    /**
     * A note was refused because the vault found a credential in it: the ids of {@code memory.saved}, the vault
     * secrets' {@code names} and the {@code formats}; never the note.
     */
    MEMORY_REFUSED("memory.refused"),
    /** A note was deleted: the memory's ids, {@code number}, {@code state}, {@code by} ({@code AGENT}, {@code OWNER}). */
    MEMORY_DELETED("memory.deleted"),
    /** Every note of a memory was deleted by its owner: the memory's ids, {@code count}. */
    MEMORY_CLEARED("memory.cleared"),
    /** A trigger's audience accepted a proposed note: the memory's ids, {@code number}, {@code replaced}. */
    MEMORY_ACCEPTED("memory.accepted"),
    /**
     * A memory was compacted (every 30 days): the memory's ids, {@code before}, {@code after}, {@code duplicates},
     * {@code merged}, and for a merge on the company's own key its {@code model}, {@code inputTokens},
     * {@code outputTokens}.
     */
    MEMORY_COMPACTED("memory.compacted"),
    /**
     * The company's memory settings changed (ADR-0065): {@code changed}, and the {@code compactionModel} chosen, or
     * null for none.
     */
    MEMORY_SETTINGS_CHANGED("memory_settings.changed"),
    /**
     * A conversation started (the package 3 contract, §8): {@code agentId}, {@code personPrincipalId}, {@code place},
     * {@code origin}, {@code subjectSystemId}, {@code subjectKey}, {@code payerPrincipalId}, {@code modelAccess},
     * {@code runtime}, {@code key}.
     */
    CONVERSATION_STARTED("conversation.started"),
    /** A conversation ended: {@code cause} ({@code USER}, {@code AGENT_DELETED}, {@code PERSON_LEFT}), {@code turns}. */
    CONVERSATION_ENDED("conversation.ended"),
    /**
     * OWNER, or an instance admin acting in the company, who is not its person, read a personal conversation or what
     * belongs to it (the security review of the redesign, row 18): {@code conversationId} (absent for a list),
     * {@code personPrincipalId}, {@code what} ({@code conversation}, {@code conversations}, {@code runs},
     * {@code run-events}, {@code run-stream}, {@code attachments}, {@code attachment}, {@code decisions}). Every read
     * request is recorded, with no throttle (package 6's open point 9); a stream once, when it opens.
     */
    CONVERSATION_READ("conversation.read"),
    /**
     * A conversation's binding on a system was bound: {@code conversationId}, {@code systemId}, {@code how},
     * {@code accountId}, {@code holderPrincipalId}, {@code grantId} and its {@code access} then.
     */
    CONVERSATION_BINDING_BOUND("conversation.binding.bound"),
    /** A conversation's binding waits: {@code conversationId}, {@code systemId}, {@code reason}; once per wait. */
    CONVERSATION_BINDING_WAITING("conversation.binding.waiting"),
    /** A conversation's binding is gone for good: {@code conversationId}, {@code systemId}, {@code reason}. */
    CONVERSATION_BINDING_GONE("conversation.binding.gone"),
    /**
     * A principal was created (the package 1 contract, §8): a member's with the membership ({@code kind},
     * {@code userId}), or a service identity by an admin ({@code kind}, {@code name}).
     */
    PRINCIPAL_CREATED("principal.created"),
    /** A service identity was renamed or described anew: {@code changed} and the {@code name}. */
    PRINCIPAL_CHANGED("principal.changed"),
    /** A service identity was deleted with its accounts and grants: its {@code name}. */
    PRINCIPAL_DELETED("principal.deleted"),
    /**
     * A principal was suspended (I7; the package 6 contract, §8): {@code kind}, {@code cause} ({@code ADMIN},
     * {@code USER_DEACTIVATED}), a service identity's {@code name} or a member's {@code userId}, never a person's name.
     */
    PRINCIPAL_SUSPENDED("principal.suspended"),
    /** A suspended principal was resumed: as {@code principal.suspended}. */
    PRINCIPAL_RESUMED("principal.resumed"),
    /** The checklist of a member who left was closed: {@code force}, the counts of what stayed open. */
    PRINCIPAL_DEPARTURE_CLOSED("principal.departure_closed"),
    /** The company's runs were stopped (package 6, §5.4): {@code runsCancelled} (ids). Never the reason. */
    COMPANY_RUNS_STOPPED("company.runs_stopped"),
    /** The company's stop was lifted: {@code triggersArmed}, {@code heldRows}. */
    COMPANY_RUNS_RESUMED("company.runs_resumed"),
    /** A system was paused: {@code stopRunning}, {@code runsCancelled}, {@code triggersPaused}. Never the reason. */
    SYSTEM_PAUSED("system.paused"),
    /** A paused system was resumed: {@code resumeTriggers}, {@code triggersResumed}. */
    SYSTEM_RESUMED("system.resumed"),
    /** Admins gave an agent a new owner: {@code formerOwnerPrincipalId} (null when the admins held it), {@code ownerPrincipalId}. */
    AGENT_OWNER_CHANGED("agent.owner_changed"),
    /** A member left the company and their principal became LEFT (I8): {@code userId}. */
    PRINCIPAL_LEFT("principal.left"),
    /**
     * The groups that may use a service identity were replaced (contract §3.2): {@code name}, {@code groupIds} (the
     * whole list after the change), {@code added} and {@code removed}; a replace that changes nothing is not audited.
     */
    SERVICE_IDENTITY_USERS_CHANGED("service_identity.users_changed"),
    /**
     * A system was registered (the package 1 contract, §8): {@code kind}, {@code name}, {@code handle}, and
     * {@code ownerPrincipalId} for a personal MCP server.
     */
    SYSTEM_CREATED("system.created"),
    /** A system was renamed or its settings changed: {@code changed}, {@code name}, {@code handle}. */
    SYSTEM_CHANGED("system.changed"),
    /** A system was removed, after its accounts: {@code kind}, {@code name}, and {@code cause} when not asked for. */
    SYSTEM_DELETED("system.deleted"),
    /**
     * A system's app (its OAuth client) was set: {@code clientId}, whether a client secret is kept
     * ({@code clientSecret}), {@code scopes}, and {@code registered} when Tinfirm registered itself; never the secret.
     */
    SYSTEM_APP_CHANGED("system.app_changed"),
    /** An MCP server's catalogue was read again: the tools {@code added}, {@code removed} and {@code unconfirmed}. */
    SYSTEM_TOOLS_REFRESHED("system.tools_refreshed"),
    /** An MCP tool's effect was confirmed: {@code tool}, {@code effect}. */
    SYSTEM_TOOL_EFFECT_CONFIRMED("system.tool_effect_confirmed"),
    /** An MCP tool was switched on or off: {@code tool}, {@code enabled}. */
    SYSTEM_TOOL_SWITCHED("system.tool_switched"),
    /** Personal MCP servers were switched on or off for the company (contract §10): {@code personalMcpServers}. */
    /**
     * A chat system's bot was set or replaced (the package 5 contract, §8): {@code principalId}, {@code accountId},
     * {@code identity} (Slack's bot user id, Teams' app id), {@code teamId}.
     */
    SYSTEM_BOT_SET("system.bot_set"),
    /** A chat system's bot was removed: {@code principalId}, {@code accountId}, {@code identity}. */
    SYSTEM_BOT_REMOVED("system.bot_removed"),
    /** A webhook system's secret was rotated (package 5, §5.12): the previous one is accepted for 24 hours. */
    SYSTEM_WEBHOOK_SECRET_ROTATED("system.webhook_secret_rotated"),
    /**
     * Deliveries to a webhook system were refused (package 5, §5.12, §8): {@code reason} ({@code SIGNATURE},
     * {@code WINDOW}, {@code SIZE}, {@code RATE}, {@code SOURCE_RATE}), {@code count}; once a minute per system and
     * reason. Never a body.
     */
    WEBHOOK_REFUSED("webhook.refused"),
    /** A sign-in link was offered to a chat user (package 5, §8): {@code systemId}, {@code chatUserId}. */
    CHAT_LINK_OFFERED("chat.link_offered"),
    /** A sign-in link's confirmation was refused: {@code systemId}, {@code chatUserId}, {@code reason}. */
    CHAT_LINK_REFUSED("chat.link_refused"),
    /**
     * A member linked a Google account to themselves for the Gmail add-on (ADR-0083): {@code googleSubject}, the
     * account's id at Google; never its email, which the link keeps and its end deletes.
     */
    GMAIL_LINKED("gmail.linked"),
    /**
     * A Google account's link ended (ADR-0083): {@code googleSubject}, {@code reason} ({@code SIGN_OUT} in the
     * add-on, {@code DISCONNECTED} in Tinfirm).
     */
    GMAIL_UNLINKED("gmail.unlinked"),
    /**
     * A chat user was refused (package 5, §5.3, C6): {@code systemId}, {@code chatUserId}, {@code channelId},
     * {@code reason}; once per chat user and reason an hour. Never a message.
     */
    CHAT_REFUSED("chat.refused"),
    /** The company's chat rates changed: the values after the change. */
    CHAT_SETTINGS_CHANGED("chat.settings_changed"),
    SYSTEM_SETTINGS_CHANGED("system.settings_changed"),
    /** A repository was added to a code host: {@code systemId}, {@code url}. */
    REPOSITORY_ADDED("repository.added"),
    /** A repository was removed from a code host: {@code systemId}, {@code url}. */
    REPOSITORY_REMOVED("repository.removed"),
    /**
     * A member connected an account on a system (the package 1 contract, §8), or an admin added a credential to one
     * (ADR-0072): {@code systemId}, {@code principalId} and {@code userId} for a member's own account, {@code credential}
     * (its name) for a credential, {@code kind}; never the token.
     */
    ACCOUNT_CONNECTED("account.connected"),
    /** An account was signed in or stored again: as {@code account.connected}. */
    ACCOUNT_RECONNECTED("account.reconnected"),
    /**
     * An account or a credential went with its secret: {@code systemId}, {@code principalId} or {@code credential},
     * {@code revoked} when its grant was revoked at the system, and {@code cause} when nobody disconnected it
     * ({@code system.deleted}, {@code principal.left}, {@code system.bot_removed}).
     */
    ACCOUNT_DISCONNECTED("account.disconnected"),
    /**
     * The system refused a credential: a token or a sign-in being connected ({@code systemId}, {@code principalId} or
     * {@code credential}), or a stored account's refresh, which turns it {@code RECONNECT}; {@code reason} is the
     * system's, never a token.
     */
    ACCOUNT_REFUSED("account.refused"),
    /**
     * A credential was renamed, disabled or enabled (ADR-0072): {@code systemId}, {@code name}, what {@code changed}
     * ({@code name}, {@code enabled}) and {@code enabled}.
     */
    CREDENTIAL_CHANGED("credential.changed"),
    /**
     * A service account borrows a credential now, or its access changed (ADR-0072): {@code accountId},
     * {@code systemId}, {@code serviceAccountId}, {@code access}, and {@code replaced} the credential it borrowed there
     * before.
     */
    CREDENTIAL_LENT("credential.lent"),
    /** A service account no longer borrows a credential: as {@code credential.lent}, with {@code cause} when not asked. */
    CREDENTIAL_WITHDRAWN("credential.withdrawn"),
    /** A credential was lent to a group: {@code accountId}, {@code systemId}, {@code groupId}, {@code access}. */
    GRANT_CREATED("grant.created"),
    /** A grant's access changed: as {@code grant.created}, with {@code previousAccess}. */
    GRANT_CHANGED("grant.changed"),
    /** A grant was withdrawn: as {@code grant.created}, with {@code cause} when its account or group went. */
    GRANT_DELETED("grant.deleted"),
    /**
     * A file was added to a conversation (the package 3 contract, §8; ADR-0014, ADR-0021): by its person (actor USER),
     * or by the agent of a run (actor AGENT, with {@code runId}): {@code conversationId}, {@code sha256},
     * {@code sizeBytes}, {@code contentType}, {@code scanned}, and {@code acknowledgedCredentialFormats} when an owner
     * or admin stored a file that looks like a credential. Never the file's name or content.
     */
    ATTACHMENT_UPLOADED("attachment.uploaded"),
    /** The person deleted a file of their conversation: {@code conversationId}, {@code sha256}. */
    ATTACHMENT_DELETED("attachment.deleted"),
    /**
     * A conversation produced an output (contract §8): {@code conversationId}, {@code runId}, {@code kind}
     * ({@code CHANGE_REQUEST}, {@code DOCUMENT}, {@code LINK}) and its {@code attachmentId} or {@code url}, with
     * {@code repositoryId} and {@code number} for a change request. Actor AGENT for what the agent saved, SYSTEM for a
     * change request the server opened.
     */
    OUTPUT_CREATED("output.created"),
    /**
     * A range of the company's chain was exported (the package 6 contract, §5.8), once the export ended:
     * {@code fromSeq}, {@code toSeq}, {@code count}, and {@code from}, {@code to} when asked by time.
     */
    AUDIT_EXPORTED("audit.exported"),
    /** An OWNER acknowledged a failed daily check of the chain: {@code checkId}, {@code failure}; never the note. */
    AUDIT_CHECK_ACKNOWLEDGED("audit.check_acknowledged"),
    /**
     * The OWNER changed the retention periods (the package 6 contract, §5.9): {@code runEventsDays},
     * {@code chatContentDays}, {@code contentDays}, {@code filesDays} after the change, and {@code changed}.
     */
    RETENTION_CHANGED("retention.changed"),
    /**
     * The daily sweep purged content past its period (the package 6 contract, §5.9), once per company and sweep:
     * the conversations, messages, decisions, outputs, files and runs purged, per kind. Actor SYSTEM.
     */
    CONTENT_PURGED("content.purged"),
    /** A legal hold was set: {@code holdId}, {@code conversationId} or {@code principalId}; never the reason. */
    HOLD_SET("hold.set"),
    /** A legal hold was lifted: {@code holdId}, {@code conversationId} or {@code principalId}. */
    HOLD_LIFTED("hold.lifted"),
    /**
     * A person's content was erased on request (the package 6 contract, §5.9): the counts per kind, and what remains
     * by kind.
     */
    PRINCIPAL_ERASED("principal.erased"),
    /**
     * The words of someone outside Tinfirm were erased on request: {@code systemId}, {@code externalId}, the counts
     * per kind, and what remains by kind.
     */
    IDENTITY_ERASED("identity.erased"),
    /**
     * A member exported spend as CSV (the package 6 contract, §5.2, §8): {@code filters} (the ids and values asked
     * for), {@code groupBy}, {@code bucket}, {@code from}, {@code to}, {@code zone} and {@code rows}. Reading spend in
     * the app is not audited.
     */
    SPEND_EXPORTED("spend.exported"),
    /**
     * A trigger was created (the package 4 contract, §8): source, system, agent, events, subject, scope, repeat,
     * schedule, team, results; never the instruction.
     */
    TRIGGER_CREATED("trigger.created"),
    /** A trigger's definition changed: as created, with {@code changed} (the fields) and {@code editorPrincipalId}. */
    TRIGGER_CHANGED("trigger.changed"),
    /** A trigger was deleted (soft): its {@code cause} ({@code USER}, {@code agent.deleted}, {@code system.deleted}). */
    TRIGGER_DELETED("trigger.deleted"),
    /**
     * A trigger's whole account table and its payer with its model access, after the change: every choice of
     * authority.
     */
    TRIGGER_ACCOUNTS_CHANGED("trigger.accounts_changed"),
    /** A trigger's settings changed, as {@code settings.changed}. */
    TRIGGER_SETTINGS_CHANGED("trigger.settings_changed"),
    /** A trigger's author accepted the agent's widening: the version and the previous one. */
    TRIGGER_ACCEPTED("trigger.accepted"),
    /** The editor's warning acknowledged: the events, the ALLOW write tools and the accounts named in it. */
    TRIGGER_OPEN_EVENTS_ACKNOWLEDGED("trigger.open_events_acknowledged"),
    /** A trigger paused: the reason, {@code stopRunning} and the runs cancelled. */
    TRIGGER_PAUSED("trigger.paused"),
    /** A trigger resumed. */
    TRIGGER_RESUMED("trigger.resumed"),
    /** A trigger waits, once per wait: the reason. */
    TRIGGER_WAITING("trigger.waiting"),
    /** A trigger reassigned by an admin: the former and the new author, the rows and the payer that changed. */
    TRIGGER_REASSIGNED("trigger.reassigned"),
    /** A trigger fired, or was kept from firing for a reason worth showing (part b): trigger, subject, outcome. */
    TRIGGER_FIRED("trigger.fired"),
    /** A person ran a trigger now: the subject or the schedule, the outcome. */
    TRIGGER_RUN("trigger.run"),
    /** An open approval of a trigger's conversation moved to its new team, or to its author: the former and new one. */
    DECISION_APPROVER_CHANGED("decision.approver_changed"),
    /**
     * A reassigned trigger's waiting conversation took the trigger's new account table and payer
     * ({@code conversation_rebind}, §11): per system the old and new holder, account and grant; the old and new
     * author, payer and model access.
     */
    CONVERSATION_REBOUND("conversation.rebound"),
    /** Tinfirm wrote to a trigger's conversation's place through the outbox (part c). */
    SOURCE_WRITTEN("source.written"),
    /**
     * The company's settings changed: the firing limits of its triggers, the webhook's deliveries a minute and whether
     * triggers may pay with a subscription.
     */
    COMPANY_SETTINGS_CHANGED("company_settings.changed"),
    /** An instance admin chose whether error reports are delivered automatically (ADR-0070), in the instance chain. */
    ERROR_REPORTING_CHANGED("error_reporting.changed"),
    /** An instance admin deleted an error report (ADR-0070), in the instance chain: its id and source. */
    ERROR_REPORT_DELETED("error_report.deleted");

    private final String value;

    AuditAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
