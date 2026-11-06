/**
 * Runner hosts, their registration and capacity settings, and the runner WebSocket endpoint
 * {@code /api/runner/v1/connect} (PLATFORM.md §11.1, ADR-0006, ADR-0016). A host is a company host or a member's
 * personal host (the package 2 contract, §2, {@link com.flowtivo.tinfirm.runner.HostScope}); a personal host's
 * credentials, reported in {@code hello}, are stored as its owner's model access, and a company host's are refused
 * (M5). Company and personal hosts speak the same protocol.
 * <p>
 * API: {@link com.flowtivo.tinfirm.runner.RunnerMessages} (the protocol types of v1 and v2, hand-written after
 * {@code protocol/runner-v1.schema.json} and {@code protocol/runner-v2.schema.json}; each connection speaks the version
 * of its host's {@code hello}, CONVENTIONS §14.10), {@link com.flowtivo.tinfirm.runner.RunnerDirectory} (hosts with
 * their scope, owner and version, and personal hosts' credentials), {@link com.flowtivo.tinfirm.runner.RunnerVersions}
 * (the minimum version of a gateway's runs), {@link com.flowtivo.tinfirm.runner.RunnerConnections} and
 * {@link com.flowtivo.tinfirm.runner.RunnerAuthentication} (runner host requests outside the WebSocket). Ports that the
 * run module implements (package 3 of the platform redesign): {@link com.flowtivo.tinfirm.runner.RunnerSessionListener}
 * (run messages of connected hosts, confirmed capacity, the liveness of a connection and its end) and
 * {@link com.flowtivo.tinfirm.runner.RunnerActivity} (active run counts); they are resolved when used, since the run
 * module depends on this one. It implements the
 * principal module's {@link com.flowtivo.tinfirm.principal.PrincipalDependents}: a leaving member's personal hosts go;
 * and the company module's {@link com.flowtivo.tinfirm.company.DeclaredRunnerHosts}: a declared company's runner host,
 * which the deployment runs itself (ADR-0101), is registered at every start.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Runner")
package com.flowtivo.tinfirm.runner;
