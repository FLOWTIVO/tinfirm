# ADR-0065: The company's own model key, for Tinfirm's own requests

- Status: Accepted
- Date: 2026-10-15
- Decided by: the owner, 2026-10-15
- Sources: [PLATFORM.md](../PLATFORM.md) §2 (principles 3 and 5), §9; [CONVENTIONS.md](../CONVENTIONS.md) §15.4 (keys and their rotation), §15.18 (agent memory); the package 2 contract ([reviews/2026-10-12-package-2-contract.md](../reviews/2026-10-12-package-2-contract.md), §3.2 and §6); [threat-model.md](../threat-model.md)
- Supersedes: ADR-0063, decision 8's platform credential (a service identity and a model in the company's model settings)
- Changes: the server's model module (a key that is no principal's, its sync, rotation, reconciliation and forgetting), the agent module (the company's memory settings), `V045__company_model_key.sql`; the API (`getCompanyKeyModelAccess`, `updateCompanyKeyModelAccess`, `rotateCompanyKey`, `getMemorySettings`, `putMemorySettings`; `GET`/`PUT /model-settings/platform` and `PlatformCredential` go; `ModelAccessView.principalId` is absent for the company key); the audit (`model_key.*` with the target `company_model_key`, `memory_settings.changed`); the web app (the company key's row in Members' budgets, the Memory section of the company's settings; the card "Tinfirm's own requests" goes)

## Context

- ADR-0063 paid for a memory's compaction with a *platform credential*: a service identity an admin named in the model settings, whose LiteLLM key and budget paid, with a model chosen beside it. Admins had to create a service identity for something that is not one (it acts as nobody, holds no account, starts no conversation), the identity's budget mixed the platform's spend with whatever else it paid for, and the model of one feature sat in the gateway's settings.
- Every key of the company is a principal's (`principal_model_key`), with its budget and models, created, synced, rotated every 30 days, reconciled and forgotten by one machinery (`KeySync`, `KeyRotation`, `KeyReconciliation`, `KeyWork`). A key outside it would be a key nobody rotates or sweeps.
- More of Tinfirm's own requests are likely (summaries, classification), each wanting a small model and paid by the company, never by the person or trigger whose work it serves.

## Decision

1. **The company has a model key of its own**, no principal's: one LiteLLM virtual key in the company's team (`company_model_key`, one row a company), alias `tinfirm-{companyId}-company` (`-r{n}` after rotations), metadata `tinfirm_kind: COMPANY`. It is created by the first sync once the company's gateway passed its test, as the principals' keys are.
2. **It is one more holder of the same machinery** (`KeyHolder`): synced (budget, period and models from its own values or the company's default), rotated every 30 days and at once on an admin's request or with every key of the company, kept by the reconciliation's sweep, which deletes a stray key of its alias, and forgotten when the gateway changes. It is never blocked (no one suspends it), raises no budget alerts (they are a principal's), and wakes no run (no run is paid by it). Its secret is internal (`MODEL_KEY_COMPANY`), revealed for each request (`secret.accessed`, purpose `platform:{purpose}`), and never leaves the server.
3. **Its budget and models are set where every budget is**: a row of Members' budgets on Model access, *Company key*, with its spend, its status and the same dialog (OWNER and ADMIN, `MANAGE_MODEL_ACCESS`). Its audit is the keys' (`model_key.created`, `budget_changed`, `rotated`, …) with the target `company_model_key` (the company's id) and `holder: company`.
4. **It pays only for Tinfirm's own requests** (`PlatformModel`), never for a run, a person's or a trigger's work. Each use chooses its own model where the use is set, among the models the key allows: a memory's compaction in the company's settings, section Memory (`memory_settings.compaction_model`, `GET`/`PUT /memory-settings`, `MANAGE_MODEL_ACCESS`, `400 MODEL_NOT_ALLOWED` outside the key's models, audited as `memory_settings.changed`). A request is tagged `tinfirm-task:{purpose}` in LiteLLM's spend logs and syncs the key after it, so its spend shows.
5. **Without a model, or with one the key no longer allows, a compaction removes only duplicates**, as without a credential before; a key that does not answer (budget spent, gateway down) leaves the memory due for the next day.
6. **The platform credential goes**: its columns are dropped; the model a company chose for it carries over to its memory settings. The service identity it named stays, with its key, and pays for nothing of Tinfirm's.

## Consequences

- No service identity is needed for Tinfirm's own requests, and the company sees and caps their spend on one row, apart from its people's.
- Every company whose gateway passed its test has one more key, rotated and swept as the others; a company that never chooses a model spends nothing on it.
- `rotate-keys` rotates the company key with the principals' (its count includes it); changing the default budget, period or models reaches it like a principal on the default.
- New uses of the key add their own model setting where they are configured, and their own purpose tag.

## Alternatives considered

- **A hidden principal of a new kind** (`PLATFORM`) whose key is a principal's. Reuses the machinery as it is, but every listing, payer choice and permission that takes "a principal" would have to exclude it, and a principal that is nobody invites mistakes. Rejected for the explicit holder.
- **A separate, simpler key** without rotation or the sweep. Rejected: it would be the one key nobody rotates (§15.4) and that a lost write leaves alive in LiteLLM.
- **The model in the model settings**, as before. Rejected by the owner: the model belongs to the feature that uses it.

## Open points

- Budget alerts on the company key (today only principals' budgets alert).
