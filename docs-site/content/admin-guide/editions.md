# Editions and prices

Tinfirm comes in three editions: **Community**, **Pro** and **Enterprise**. They are one product in one container image. Without a licence key, an instance runs as Community. A key unlocks Pro or Enterprise at once, with no reinstall and no downtime. An instance admin enters it: [Enter and renew a licence key](../operator-guide/licence.md) says how.

No edition limits what your agents do. Sandboxes, agents, triggers and runs are unlimited in all three, and every run is still yours to pay for at your model provider. Pro limits only the number of [seats](roles.md#seats).

## What each edition includes

Everything is recorded in the audit log in every edition. Proving it to an auditor, with filters, an export and the chain's verification, is paid.

| | Community | Pro | Enterprise |
|---|---|---|---|
| Agents, runtimes, skills, memory, conversations, steering, inbox, questions, approvals, outputs, retries | :lucide-check: | :lucide-check: | :lucide-check: |
| Every system and trigger: Jira Data Center and Cloud, GitHub, GitLab, git hosts, MCP servers, Slack, Teams, webhooks, schedules | :lucide-check: | :lucide-check: | :lucide-check: |
| The MCP endpoint for people's AI clients, personal access tokens | :lucide-check: | :lucide-check: | :lucide-check: |
| The Outlook add-in and the Gmail add-on | – | :lucide-check: | :lucide-check: |
| Model access: LiteLLM, per-person budgets, spend views and alerts, the company's key | :lucide-check: | :lucide-check: | :lucide-check: |
| Safety: sandboxes, the outbound guard, kill switches, suspension | :lucide-check: | :lucide-check: | :lucide-check: |
| Elastic runners: a fresh VM per run, ready VMs on a schedule, sessions that resume on any company host | – | :lucide-check: | :lucide-check: |
| Groups, service accounts, credentials lent to them | :lucide-check: | :lucide-check: | :lucide-check: |
| Local sign-in, OIDC | :lucide-check: | :lucide-check: | :lucide-check: |
| [SCIM provisioning](scim.md); [SAML sign-in](saml.md) | – | – | :lucide-check: |
| The audit recorded, hash-chained | :lucide-check: | :lucide-check: | :lucide-check: |
| The audit shown in the app | the last 90 days | all | all |
| The audit's filters, export, chain verification and daily checks | – | :lucide-check: | :lucide-check: |
| The audit anchored outside the database, with signed checkpoints ([Anchor the audit](../operator-guide/audit-anchor.md)) | – | :lucide-check: | :lucide-check: |
| The audit streamed to a SIEM | – | – | planned |
| Erasure of a person's content | :lucide-check: | :lucide-check: | :lucide-check: |
| Retention | [everything kept](#retention-in-community) | the Owner's periods | the Owner's periods |
| Legal holds | – | – | :lucide-check: |
| Several companies in one instance | – | – | :lucide-check: |
| High availability: several server replicas | – | – | planned |
| Air-gapped installation: an offline bundle of every image and update, with the runner images prebuilt | – | – | planned |
| Seats | unlimited | 5 to 150, as licensed | as the contract says |

**Planned** means not in Tinfirm 1.0: the feature is part of Enterprise once it is built, and an Enterprise licence does not deliver it before then. Until then, Tinfirm runs as one server replica in every edition ([Scale and availability](../operator-guide/architecture.md#scale-and-availability)), the audit is read in the app or exported rather than streamed, and an air-gapped network copies the signed images itself ([On an air-gapped network](../operator-guide/verify-images.md#on-an-air-gapped-network)).

Erasure stays free, so that no company pays to meet the GDPR. Budgets and kill switches stay free, because nobody should pay to stop an agent.

Each paid feature is checked by the server. When an edition does not include a feature, the app hides it, and the API answers `403 EDITION_REQUIRED`.

## Prices

| Edition | Price |
|---|---|
| **Community** | Free |
| **Pro** | €15 a seat a month, billed yearly: €180 a seat a year. From 5 to 150 seats, in euros only. |
| **Enterprise** | No published price. Contact us: the contract is negotiated, seats and all. |

Seats you add during the year are priced for the rest of the year, on the invoice. They come as a new licence key with the new total of seats and the same end date, which replaces the key in force. For example, 10 more seats with 4 months left cost 10 × €15 × 4 = €600.

A small regulated firm that needs legal holds talks to us about Enterprise too.

## See the licence

Owners and Admins see the company's licence in the company's **Settings**, under **Licence**: the edition, the seats used of those licensed, and the end date. It is read-only there. Where a feature needs a paid edition, the app shows a **Pro** or **Enterprise** badge beside it, such as the Outlook add-in and the Gmail add-on in **Settings**, **General**, or legal holds on **Retention**. One licence covers the whole instance, so an instance admin enters and changes it, never a company.

## When the licence ends

When a Pro or Enterprise licence ends, the paid features go. Work in progress, your data and your compliance stay.

| When | What happens |
|---|---|
| From 30 days before the end | A banner above every company page tells Owners, Admins and instance admins **The licence ends in** so many days, with the end date. Owners and Admins also get an inbox notice marked **Licence**, which links to the licence in **Settings** and can be dismissed. |
| For 30 days after the end, the grace | Everything keeps working. The banner reads **The licence has ended: everything keeps working until** the last day of the grace. |
| After the grace | The instance runs as Community, with the exceptions below. The banner reads **The licence has run out: the instance runs as Community**, and lists what **Stopped** and what is **Kept**. |
| When a new key is entered | Everything comes back at once. Nothing was removed. |

After the grace, these stay as they were, so that the end of a licence never deletes data or breaks a compliance promise:

- the audit's export keeps working for 90 days more;
- the retention periods in force keep running, and cannot be changed: the **Retention** page shows them read-only, with a **Pro** badge. Stopping them would keep data past the periods your company agreed to;
- open legal holds stay in force, and can be lifted, but no new one can be set: **Setting a hold** shows an **Enterprise** badge;
- [SAML sign-in](saml.md) keeps working, and [SCIM](scim.md) stops: an identity provider's requests are refused, and a person who leaves is removed in **Members**;
- the companies beyond the first keep working;
- every role stays as it is, and no seat is counted.

## Retention in Community

On an instance that has never held a licence, retention keeps everything: conversations, transcripts, files and run events are never purged. Community has no retention periods of its own and no legal holds, so a company could not stop a purge it did not want; Tinfirm does not delete a company's data by default.

On an instance that has never held a licence, the **Retention** page shows the periods read-only, with a **Pro** badge, and says that nothing is removed by retention. After a licence has run out, it is different: the periods that were in force keep running and keep removing content, as [When the licence ends](#when-the-licence-ends) says, and the page says they cannot be changed until a new key. Under a Pro or Enterprise key, the Owner sets the retention periods there. They start at run events 90 days, chat and webhook content 30 days, and other content and files 365 days.

Some tidying runs in every edition: Tinfirm removes chat events, webhook bodies, revoked relay tokens and other operational records it no longer needs, and memory notes keep their own period. To remove a person's content in Community, erase it.

The database of a Community instance grows for as long as it runs. Plan its disk for that, or erase what you no longer need.

## The licence of Tinfirm

Tinfirm's code is public. Everything outside the enterprise part of the code is under the MIT licence. The enterprise part, which holds the paid features, is under a commercial licence: using it in production needs a valid subscription for the right number of seats. This documentation is under the MIT licence too. Documenting a paid feature here grants no right to use it. Each image holds the licence texts, and the notices of the components it includes, in `/licenses/`; each archive of a release holds them beside its files.
