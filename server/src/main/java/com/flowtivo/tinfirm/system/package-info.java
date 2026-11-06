/**
 * Systems (the package 1 contract, §3.3 to §3.5 and §10; the platform redesign, Part 3): the external services a
 * company registers once, each with one OAuth app and no account of its own; code hosts' repositories; trackers' fixed
 * tool catalogues and MCP servers' read catalogues with confirmed effects; members' personal MCP servers, and the
 * company's switch of them; and executing the tools of systems with the credential of a conversation's account (the
 * package 3 contract, §5.4). Every call to a system goes through this module's outbound client and its guard; a
 * personal server's never reach private addresses.
 * <p>
 * API: {@link com.flowtivo.tinfirm.system.Systems} (lookups, tools, repositories, the personal servers' switch),
 * {@link com.flowtivo.tinfirm.system.SystemCalls} (who a credential is on a system, the app's OAuth flows),
 * {@link com.flowtivo.tinfirm.system.SystemTools} (a tool's call with a
 * {@link com.flowtivo.tinfirm.system.CallCredential}, answered as a {@link com.flowtivo.tinfirm.system.ToolOutcome};
 * the check of its arguments; opening a change request and reading its state,
 * {@link com.flowtivo.tinfirm.system.ChangeRequests}), the fixed catalogue
 * {@link com.flowtivo.tinfirm.system.FixedTools}, and the events of {@link com.flowtivo.tinfirm.system.SystemEvents}.
 * It implements the principal module's port {@link com.flowtivo.tinfirm.principal.PrincipalDependents} (a leaving
 * member's personal servers go) and the vault's {@link com.flowtivo.tinfirm.vault.SecretKeeper} (an app's client secret
 * is not deleted on the Secrets page). Port: {@link com.flowtivo.tinfirm.system.SystemAccounts} (the accounts on a
 * system), implemented by the account module. No call to a system runs inside a database transaction.
 * <p>
 * Package 6: a company system's pause, a kill switch ({@link com.flowtivo.tinfirm.system.SystemSwitches},
 * {@code SystemInfo.paused}), whose effects above the module the port
 * {@link com.flowtivo.tinfirm.system.SystemSwitchDependents} carries (the conversation module; package 4's triggers).
 * <p>
 * For people's AI clients (the {@code mcp} module): {@link com.flowtivo.tinfirm.system.SystemReads}, the Systems API's
 * reads and its test, which records a system's last test and changes nothing else.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Systems")
package com.flowtivo.tinfirm.system;
