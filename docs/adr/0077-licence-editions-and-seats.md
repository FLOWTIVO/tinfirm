# ADR-0077: An MIT core with a commercial `ee/`, three editions unlocked by an offline licence key, and seats that follow the role

- Status: Accepted
- Date: 2026-10-20
- Decided by: the owner, 2026-10-20
- Amended: 2026-10-21, in decision 6 and the alternatives: a key covers one production install, the customer's staging and test installs allowed, enforced by the contract rather than by a usage report or a key bound to the install; the server trusts several public keys so that the signing key can be rotated. Signed official images, verified offline, are [ADR-0081](0081-signed-release-images.md).
- Amended again: 2026-10-21, in decision 1 and the consequences, because `ee/` held only the licence and the paid features' code was in the MIT core: the server code that only a paid feature runs is under `ee/` too, and the core reaches it only through interfaces of its own.
- Sources: [PRODUCT.md](../../PRODUCT.md) ("Undecided: … the license"); [PLATFORM.md](../PLATFORM.md) §4 (roles), §9 (governance); [CONVENTIONS.md](../CONVENTIONS.md) §5 (OIDC providers), §6 (authorization matrix), §7 (`retention_settings`), §15.11 (audit, retention, holds and erasure); [ADR-0010](0010-hash-chained-audit-log.md); [ADR-0058](0058-authority-from-the-audience.md) (the three roles); [ADR-0074](0074-outlook-add-in.md); [ADR-0075](0075-companies-and-sign-in-from-the-deployment.md) (a provider's role)
- Amended again: 2026-10-21, in `ee/LICENSE` (decision 1): the sentence that let the Software run in production as Community without a key now says that every paid feature is then inactive, forbids moving, changing, disabling, working around or circumventing the licence key functionality and removing or obscuring a check that keeps a paid feature inactive, and ties a paid feature in force to a valid key that names it.
- Amended by: [ADR-0082](0082-licence-notices-and-dependency-licences.md), under which the root `LICENSE` and `ee/LICENSE` exist since 2026-10-21 (FLOWTIVO s.r.o. holds the copyright), every artefact carries them with its dependencies' notices, and CI checks the dependencies' licences against one allow list, which keeps decision 1's core free of BSL and SSPL components
- Amended by: [ADR-0083](0083-gmail-add-on.md), under which decision 4's table gains the Gmail add-on, in Pro and Enterprise as the Outlook add-in (`Feature.GMAIL_ADDON`); as decision 1 says, its code is under `ee/` (the cards, the deployment file, linking and the Gmail API's calls), and the core keeps the verification of Google's requests and a person's links, listed and ended in every edition
- Amended by: [ADR-0100](0100-elastic-runners-in-pro.md), under which decision 4's table gains elastic runners, in Pro and Enterprise (`Feature.ELASTIC_RUNNERS`: the sandbox bridge, a VM per run, ready VMs on Tinfirm's schedule, session bundles), and decision 1's `ee/` holds the runner host's code that only they run, under `ee/runner/`; Community keeps runner hosts with Docker, always on
- Amended by: [ADR-0110](0110-no-model-port.md), under which the model port is gone from decision 4's row "Model access"
- Amended by: [ADR-0111](0111-audit-chain-heads-anchored-outside-the-database.md), under which decision 4's table gains the audit chains' heads anchored outside the database, in Pro and Enterprise (`Feature.AUDIT_ANCHOR`), its code under `ee/`
- Changes: the root `LICENSE` and `ee/LICENSE` (ADR-0082) and the paid features' code under `ee/` (decision 1 as amended), both built 2026-10-21; and, as decided: `CONTRIBUTING.md` (the DCO); ADR-0058's three roles, which gain VIEWER; ADR-0075's provider role, which may be VIEWER; CONVENTIONS §6 (a VIEWER column), §7 (Community's retention); the server (a licence module, the seat check, the gates of decision 4); the web app (the licence page, the notices); the API (the licence keys)

## Context

- Tinfirm is self-hosted and bring-your-own-model: the customer pays its model provider through LiteLLM and runs the sandboxes on its own runner hosts. A run needs nothing from the vendor.
- What an organisation needs beyond a team's use is governance: SSO beyond the basics, SCIM, audit export, retention, legal holds, high availability. Tinfirm has built most of that already (the hash-chained audit with its filters, export and daily check; retention; legal holds; erasure).
- The product cannot be released without a licence. A licence is cheapest to choose before the first release and the first outside contribution.
- Whatever is chosen must be one repository, one build and one image, and must work for customers without any route to the internet.
- Many people use agents without signing in to Tinfirm: anyone in a channel trigger's channel drives its agent, and anyone who replies on an issue or in a thread answers its questions (PLATFORM §7.3, §8.2). Approvals need a linked member who may decide them.

## Decision

**The core is MIT and `ee/` is under a commercial licence, both public in one repository. One image runs as Community, Pro or Enterprise by the licence key it holds. Sandboxes, agents, triggers and runs are never limited; Pro limits seats, and a seat belongs to a role.**

1. **The licence of the code.** The root `LICENSE` splits the repository as GitLab's does:
   - everything outside `ee/`: **MIT** ("MIT Expat");
   - `ee/`: the **Tinfirm Enterprise licence**, modelled on GitLab's `ee/LICENSE` and BerriAI's `enterprise/LICENSE` (nearly one text): production use needs a valid subscription for the correct number of seats; customers may modify the code and publish patches, and agree by this licence that FLOWTIVO owns those modifications and that they are usable only with a subscription (a term of the licence a customer accepts, as at GitLab; a contribution by pull request is not covered by it, decision 2); copying and modifying for development and testing is free; copying, distributing, sublicensing and selling it are forbidden;
   - code served to the browser, its `ee` parts included: **MIT**, as GitLab's client-side code is. What a browser receives anyone can read, so every gate of decision 4 is checked by the server, never by the web app alone;
   - `docs-site/` and every other document in the repository: **MIT**, with the code. Documenting an `ee/` feature grants no right to use it;
   - third-party components: their own licences. The core stays free of BSL and SSPL components.

   `ee/` is public, beside the core: customers' security teams audit everything they run. The licence and the contract protect it, not secrecy.

   **What is under `ee/`** (amended 2026-10-21): the licence, and the server code that only a paid feature of decision 4 runs, each in the package of the core module it belongs to. The core reaches that code only through an interface of its own, found when used, and no class of the core names a class under `ee/` (`ModularityTest`): without `ee/` the core answers as Community, `403 EDITION_REQUIRED`. A paid feature that is a limit on what the core does in every edition stays a check in the core, since the code it limits serves Community too: the audit's history beyond 90 days, the declared companies beyond the first, the Outlook add-in's calls to the MCP endpoint, and the retention periods in force. CONVENTIONS §15.24 lists both. The runner host's code that only a paid feature runs is under `ee/runner/` (ADR-0100), built into the one binary as `ee/` is into every image.
2. **Contributions: the DCO.** Every commit carries `Signed-off-by:` (`git commit -s`), the Developer Certificate of Origin; a CI check refuses a pull request with a commit without it. `CONTRIBUTING.md` states that every contribution, to `ee/` too, is licensed to FLOWTIVO under MIT. The contributor keeps the copyright; no assignment is asked. No CLA: MIT already lets FLOWTIVO use a contribution in `ee/` and under any later licence, which is all the editions need.
3. **One image, three editions.** `ee/` is in every image. Without a key the server runs as **Community**; a valid key unlocks **Pro** or **Enterprise**, with no reinstall. Sandboxes, agents, triggers and runs are unlimited in every edition.
4. **What each edition holds.** Everything is recorded in the audit in every edition; proving it is paid.

   | | Community | Pro | Enterprise |
   |---|---|---|---|
   | Agents, runtimes, skills, memory, conversations, steering, inbox, questions, approvals, outputs, retries | ✓ | ✓ | ✓ |
   | Every system and trigger: Jira Data Center and Cloud, GitHub, GitLab, git hosts, MCP servers, Slack, Teams, webhooks, schedules | ✓ | ✓ | ✓ |
   | The MCP endpoint for people's AI clients, personal access tokens | ✓ | ✓ | ✓ |
   | The Outlook add-in (ADR-0074) | – | ✓ | ✓ |
   | The Gmail add-on (ADR-0083) | – | ✓ | ✓ |
   | Model access: LiteLLM, per-person budgets, spend views and alerts, the company's key, the model port | ✓ | ✓ | ✓ |
   | Safety: sandboxes, the outbound guard, kill switches, suspension | ✓ | ✓ | ✓ |
   | Runner hosts with Docker, always on, company and personal | ✓ | ✓ | ✓ |
   | Elastic runners (ADR-0100): the sandbox bridge, a VM per run, ready VMs on Tinfirm's schedule, session bundles | – | ✓ | ✓ |
   | Groups, service accounts, credentials lent to them | ✓ | ✓ | ✓ |
   | Local sign-in, OIDC | ✓ | ✓ | ✓ |
   | SAML, SCIM (built: ADR-0086, ADR-0087) | – | – | ✓ |
   | The audit recorded, hash-chained | ✓ | ✓ | ✓ |
   | The audit shown in the app | the last 90 days | all | all |
   | The audit's filters, export, chain verification and daily checks | – | ✓ | ✓ |
   | The audit streamed to a SIEM (planned) | – | – | ✓ |
   | Erasure of a person's content | ✓ | ✓ | ✓ |
   | Retention | everything kept (decision 8) | the OWNER's periods | the OWNER's periods |
   | Legal holds | – | – | ✓ |
   | Several declared companies in one instance (ADR-0075) | – | – | ✓ |
   | High availability (planned: state shared across replicas, PLATFORM §14) | – | – | ✓ |
   | Air-gapped installation: an offline bundle of every image and update, the runner images prebuilt, renewal by pasting a new key (planned) | – | – | ✓ |
   | Seats | unlimited | 5 to 150, as licensed | as the contract says |

   Erasure stays free so that no company pays to meet the GDPR. The budgets and kill switches stay free because nobody should pay to stop an agent.
5. **Seats follow the role.** A fourth company role, **VIEWER**, joins OWNER, ADMIN and MEMBER (ADR-0058):

   | Role | Seat in Pro | May |
   |---|---|---|
   | OWNER | ✓ | as today |
   | ADMIN | ✓ | as today |
   | MEMBER | ✓ | as today: talk to agents, define and share them, author triggers, answer and decide |
   | VIEWER | – | sign in; read the conversations and results shared with their groups; see their inbox. Never start or continue a conversation, own or edit an agent, author a trigger, or answer or decide a request |

   - **The cap is checked when a role is given.** Under a Pro key, making someone OWNER, ADMIN or MEMBER while every seat is taken is refused, `409 SEATS_EXHAUSTED`. No right is taken away in the middle of work, and no action is refused for want of a seat.
   - **A provider's new user.** ADR-0075's `_ROLE` may also be `VIEWER`. A new user whose provider's role holds a seat joins as VIEWER when no seat is free, and the company's Admins are told in their inbox. Signing in never fails for want of a seat.
   - **Free without a seat:** Viewers, service accounts, members who left, and the people who never sign in: whoever mentions the bot in a channel trigger's channel or answers a question on an issue or in a thread. Their runs are paid from the trigger's budget, and a trigger's author always holds a seat.
   - **A smaller renewal.** A key with fewer seats than the roles that hold one is not applied; the OWNER first makes Members Viewers. The key in force works until its end date meanwhile.
   - **Questions only for seats.** An agent never routes a question or an approval to a Viewer. When a request's only candidates are Viewers, it goes to the company's Owners and Admins instead, so it never waits on someone who may not answer. Viewers still read the request and its outcome where they read the conversation.
   - **Seats count people, across the instance.** Under Enterprise with several companies, the seats are one pool for the instance, and a person who holds a seat role in two companies takes one seat.
   - In Community and under an Enterprise key without a seat count, the roles are the same and nothing is counted.
6. **The licence key.** A text string signed by FLOWTIVO (Ed25519), verified by the server, offline, at its start and every hour. It names the licence's id, its revision, the customer, the edition, the seats, the start and the end, and the id of the FLOWTIVO key that signed it. **One key covers the instance**, not a company: it is not bound to any id the install generates, so it can be issued before the install, and HA, air-gapped bundles and the count of companies are the instance's.
   - **One production install per key** (amended 2026-10-21). A key covers one production install of the customer. It may also run on the customer's staging and test installs, as the `ee/` licence already allows copying for development and testing (decision 1). Nothing technical binds a key to an install, and nothing is reported. What keeps it visible: the customer's name stays shown in the app, and the licence's id stays in the audit (`licence.key.applied`).
   - **The signing keys** (amended 2026-10-21). The server trusts several FLOWTIVO public keys, bundled with it (`ee/licence-public-keys/<key id>.pub`), so that the signing key can be rotated; each licence key names its signer's key id in the signed payload, and a key whose id the server does not trust is refused. A new signing key ships in a release before any licence key is signed with it; a compromised one is removed in a release, after its customers received keys signed by another. An Enterprise key may cap the number of companies. **An instance admin enters it**, on the instance's licence page; each company's Owners and Admins see the edition, the seats used of those licensed and the end date in their company's settings, read-only. It is entered in the app: pasted on the licence page, sent by email or carried into an air-gapped network as text, never a file the deployment mounts. **One key is in force at a time.** Seats bought during the year come as a new key that replaces it: the same licence id, the next revision, the new total of seats and the same end date. A key of the same licence with a lower or equal revision is refused, so an older key cannot be pasted back. A renewal, a key whose start is later than today, is held as the next key and takes over on its start date; at most one is held. The server keeps the key in force and the next one in its database and audits each key applied (`licence.key.applied`, a replacement or the next key) and each next key that takes over (`licence.key.activated`). No call leaves the instance. A server clock set back is not defended against: the contract enforces the licence, and the key makes it visible.
7. **Expiry.** Paid features go; work in progress, data and the customer's compliance stay.
   1. From 30 days before the end: a notice to the instance's and the companies' Admins, and an inbox item to each Owner.
   2. For 30 days after the end: everything keeps working, with the notice.
   3. Then the instance runs as Community, with these exceptions: the audit's export stays for 90 days more; the retention periods in force keep running and cannot be changed (stopping them would keep data past the agreed periods); open legal holds stay in force and none can be set; SAML sign-in keeps working while SCIM stops; declared companies beyond the first keep working; the roles stay as they are and no seat check applies.
   4. A new key restores everything at once: nothing was removed.
8. **Retention in Community keeps everything.** Without a licence, conversations, transcripts, files and run events are never purged by retention: without retention periods of its own and without legal holds, a company could not stop a purge it did not want. Today's periods (run events 90 days, chat and webhook content 30 days, other content and files 365 days) become the starting values under a Pro or Enterprise key. The operational purges of every edition stay (chat events, webhook bodies, revoked relay tokens and the rest of §15.11's fixed list), and so does the memory notes' own period (§15.18).

## Consequences

- The product can be released once the two licence files exist.
- Anyone, a hosting provider included, may sell a product built on the MIT core. Tightening the core's licence later would apply only to new versions; the released ones stay MIT.
- One build, one image and one repository serve every edition. A customer upgrades by pasting a key.
- The server gains a licence module (keys, edition, seats, expiry), the VIEWER role in every permission check that starts or continues work, and a gate on each paid feature of decision 4. Each gate is a server check; the web app only hides what the server refuses.
- Community's database grows without bound unless the Owner upgrades or erases. That is the price of never deleting a company's data by default. `retention_settings` needs a value that keeps run events, which it lacks today (7 to 3650 days).
- People who drive agents in channels and on issues hold no seat. A company whose work is mostly channel triggers needs few seats. Every such run is paid from a seated author's budget.
- The bundled LiteLLM must never need its own enterprise licence for Tinfirm to work; key rotation already avoids `/key/regenerate` for that reason (CONVENTIONS §15.4).
- The threat model gains an asset when this is built: the licence signing key, held by FLOWTIVO outside every instance. A forged key needs that key. A modified server that puts a paid feature in force without a key runs that feature's code under `ee/` without a subscription: a breach of the licence, which is accepted. A modified core that drops the limits that stay in it (decision 1: the audit's history, the declared companies, the add-in's calls, the retention periods in force) breaches nothing, since that code is MIT; that is accepted too.
- The deployment's declarations are not held to the seats (amended 2026-10-27, I3 of [the security review of that day](../reviews/2026-10-27-security-review.md)): a declared company is created and its declared owner becomes OWNER even when no seat is free, because the operator who declares them also holds the licence (threat model R51); the seat count then shows more seats used than licensed until a member leaves or the licence grows.

## Alternatives considered

- **Limits on concurrent sandboxes or runs.** They punish using the product and need metering. Rejected.
- **A licence counted per agent, per run or per outcome.** Per agent punishes many narrow specialists, which is how Tinfirm is meant to be used; per run needs metering; an outcome cannot be counted across development, analysis and finance. Not chosen.
- **Counting seats by what people do** (talking to agents, owning one, deciding), with a soft count reported at renewal. Rejected for the role: a count by role can be checked on the members page, is refused only when a role is given, and needs no usage report.
- **Another licence for the core:** the Functional Source License (FSL), Apache-2.0, AGPL, the Elastic License 2.0, the BSL or n8n's Sustainable Use License. Not chosen.
- **A private `ee/`.** Two repositories or a merge at build time, two images, and customers who cannot audit what they run. Rejected.
- **A CLA.** Needed with a restrictive core, not with MIT. Rejected for the DCO.
- **Several keys in force, their seats added up and sharing one end date**, seats bought during the year as a further key. Replaced before it was built: a customer reads one key and one total, and the server needs no rule for keys that end apart, no removal of one key and no sum.
- **A usage report sent at renewal**, with which the customer would declare the installs a key runs on, so that a key used on several production installs shows. Rejected by the owner, 2026-10-21, for the contract alone (decision 6).
- **A key bound to the instance's id**, a value the install generates and the key would name, so that a key works on one install only. Rejected, 2026-10-21: every install and every reinstall, staging and test included, would need a new key from FLOWTIVO, and the key could no longer be issued before the install or carried into an air-gapped network ahead of it.
- **A licence that is checked online.** It fails air-gapped customers and adds a call home to a product that promises none. Not chosen.
- **Legal holds and retention free in Community, with the periods of today.** A company could not keep what it must keep, and a fixed purge would delete its data by default. Replaced by decision 8.
