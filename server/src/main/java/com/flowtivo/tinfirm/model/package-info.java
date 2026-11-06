/**
 * Model access (the package 2 contract, docs/reviews/2026-10-12-package-2-contract.md; ADR-0059): who pays for a run,
 * how Tinfirm manages LiteLLM, where a run may execute, and the server's model relay.
 * <ul>
 * <li>The company's LiteLLM and its defaults ({@code model_settings}): the one bundled with the deployment or the
 * company's own; each company is a LiteLLM team.</li>
 * <li>One LiteLLM key per principal ({@code principal_model_key}, M1), with its budget and models, created when the
 * principal is, blocked when it is suspended, unblocked when resumed, deleted when it leaves or is deleted (M6), and
 * kept in line by a reconciliation job. The key is an internal vault secret and never leaves the server (M7).</li>
 * <li>The two rules package 3 asks before it dispatches: {@link com.flowtivo.tinfirm.model.ModelAccess#forRun} (§5.1)
 * and {@link com.flowtivo.tinfirm.model.Hosts#eligible} (§5.2), over a
 * {@link com.flowtivo.tinfirm.model.RunModelContext}.</li>
 * <li>The model relay (§6.1, M7) under the runner's path {@code /api/runner/v1/model/…}: a run's one-run token
 * ({@link com.flowtivo.tinfirm.model.ModelRelay#open}) is accepted only while the run is active on its host, which the
 * port {@link com.flowtivo.tinfirm.model.RunModelTokens} answers, and the request goes to LiteLLM with the paying
 * principal's key. The run module implements the port.</li>
 * </ul>
 * It implements the principal module's {@link com.flowtivo.tinfirm.principal.PrincipalDependents} (a leaving member's
 * or a deleted service identity's key goes) and listens to its events, and the vault's
 * {@link com.flowtivo.tinfirm.vault.SecretKeeper} (the admin key of a company's own LiteLLM). Events:
 * {@link com.flowtivo.tinfirm.model.ModelEvents}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Model access")
package com.flowtivo.tinfirm.model;
