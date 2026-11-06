/**
 * Agents (the package 3 contract, docs/reviews/2026-10-12-package-3-contract.md, §3.1 to §3.7, §5.1, §5.2 and §11):
 * an agent's definition and its ceiling (the company systems it may use with each tool's mode, its repositories
 * and network hosts), private to its owner until shared with groups or with members on their own; every change a
 * version, and the widening each person accepts; each person's settings of an agent, which narrow it and add their own
 * MCP servers. An agent holds no credentials and names no principal it acts as (I1).
 * <p>
 * API: {@link com.flowtivo.tinfirm.agent.AgentReads} (the REST API's reads as the caller, the agents and their
 * readiness, shared by the app's controller and the MCP endpoint), {@link com.flowtivo.tinfirm.agent.Agents}
 * (lookups, {@code access}, the definition and the effective agent of an accepted version, additions for good from
 * approvals), {@link com.flowtivo.tinfirm.agent.Settings} (the settings in
 * effect, per turn), the pure rules {@link com.flowtivo.tinfirm.agent.AgentDefinition} (narrowing and widening),
 * {@link com.flowtivo.tinfirm.agent.ToolNames} (a tool's name in a run), {@link com.flowtivo.tinfirm.agent.NetworkHosts}
 * (the grammar of a network host) and {@link com.flowtivo.tinfirm.agent.Handles}, and the events of
 * {@link com.flowtivo.tinfirm.agent.AgentEvents}. Port: {@link com.flowtivo.tinfirm.agent.AgentRuns} (an agent's
 * runs), implemented by the run module. It implements the principal module's
 * {@link com.flowtivo.tinfirm.principal.PrincipalDependents} (a leaving member's settings and shares go, their agents
 * pass to the admins), the company module's {@link com.flowtivo.tinfirm.company.GroupDependents} (a deleted group's shares go) and
 * {@link com.flowtivo.tinfirm.shared.directory.AgentDisplayNames}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Agents")
package com.flowtivo.tinfirm.agent;
