/**
 * The MCP endpoint of each company for people's own AI clients (ADR-0045; CONVENTIONS §14.11; the package 6 contract,
 * §5.7): {@code POST /api/companies/{companyId}/mcp}, MCP Streamable HTTP in its stateless mode on Spring AI's server
 * starter, behind the identity module's bearer filter chain (personal access tokens and the authorization server's
 * access tokens).
 * <p>
 * An AI client is a personal space acting as its person (the design record, Part 1): its tools start, read and answer
 * the member's own conversations, decide the member's own questions and approvals, list the agents they may use, read
 * and delete the member's own memory notes and read a trigger's memory for its audience (ADR-0063), through the public
 * APIs of the modules that own them ({@code conversation}'s
 * {@link com.flowtivo.tinfirm.conversation.ConversationReads} and
 * {@link com.flowtivo.tinfirm.conversation.ConversationCommands} in the {@code CLIENT} view, {@code agent}'s
 * {@link com.flowtivo.tinfirm.agent.AgentReads} and {@link com.flowtivo.tinfirm.agent.PersonalMemory},
 * {@code trigger}'s {@link com.flowtivo.tinfirm.trigger.TriggerMemoryReads}); no tool reaches an {@code internal}
 * class, a repository or a table.
 * <p>
 * Its client also reads how the company's automation is set up and whether it fires, as the member sees it: triggers,
 * their settings and firings ({@link com.flowtivo.tinfirm.trigger.TriggerReads}), systems, their catalogues and their
 * test ({@link com.flowtivo.tinfirm.system.SystemReads}), a webhook system's deliveries and the try of its mapping
 * ({@link com.flowtivo.tinfirm.trigger.WebhookReads}), and spend ({@link com.flowtivo.tinfirm.governance.Spend}). Of
 * those, only a system's test does anything, and it changes nothing but the system's last test: no tool creates,
 * changes, pauses or runs a trigger, or changes an agent, a system, an account or a secret. The module holds the endpoint, the credential's rate limit, the scope check on the
 * HTTP request (with the challenge the identity module formats, {@link com.flowtivo.tinfirm.identity.McpChallenges}),
 * the invocation of a tool as the member the credential belongs to (an instance admin who is not a member gets
 * nothing), the Owner's notice and the renderings of results for the client's model.
 * <p>
 * It serves people's other clients of the same tools too: the Outlook add-in's manifest (ADR-0074), and the Gmail
 * add-on (ADR-0083), whose endpoints Google calls ({@code /api/gmail/**}): it verifies Google's ID tokens with the keys
 * of the {@code system} module's {@link com.flowtivo.tinfirm.system.GmailCalls}, and lists and ends a person's links.
 * Both add-ins are paid features (ADR-0077, decision 4), whose code is under {@code ee/} in this module's package and
 * which the core finds when used: the manifest, and the Gmail add-on's cards, which link a Google account to its person
 * through a sign-in link (audited), read the open email through
 * {@link com.flowtivo.tinfirm.system.GmailMessages}, and act as the person through the same services, in the
 * {@code CLIENT} view, with the link as the bearer grant.
 * <p>
 * At the top of the module graph: nothing depends on it. It owns the Gmail add-on's tables ({@code gmail_link},
 * {@code gmail_link_request}, {@code gmail_email_conversation}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "MCP", allowedDependencies = { "identity", "shared",
        "conversation", "agent", "trigger", "system", "governance", "audit" })
package com.flowtivo.tinfirm.mcp;
