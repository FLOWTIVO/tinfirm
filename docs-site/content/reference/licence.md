# Licence keys

The format of a licence key, the states of a licence, and the errors the API returns for licences and seats. [Enter and renew a licence key](../operator-guide/licence.md) says how to use them.

## The key

A key is one line of text in three parts, separated by dots:

```text
TINFIRM1.<payload>.<signature>
```

| Part | Contents |
|---|---|
| `TINFIRM1` | The version of the format |
| `<payload>` | A JSON object, base64url-encoded |
| `<signature>` | FLOWTIVO's Ed25519 signature of the payload, base64url-encoded, made with the signing key the payload names |

The payload's fields:

| Field | Meaning |
|---|---|
| `v` | The version of the payload: `2` |
| `kid` | The id of FLOWTIVO's signing key. The server checks the signature against its public key of that id, and refuses a key whose id it does not know |
| `id` | The licence's id, which FLOWTIVO quotes on invoices and in support |
| `rev` | The revision of the licence: each new key of the same licence, such as one with more seats, has a higher one |
| `customer` | The name of the customer it was issued to |
| `edition` | Pro or Enterprise |
| `seats` | The total of seats; empty for an Enterprise key without a seat count |
| `companies` | The most companies the instance may hold, for an Enterprise key that caps them |
| `startsAt` | When it starts |
| `endsAt` | When it ends |

Changing any character of a key breaks its signature, and the server refuses it.

## The states of a licence

`GET /api/licence` returns the licence in force to any signed-in user:

| Field | Meaning |
|---|---|
| `edition` | `COMMUNITY`, `PRO` or `ENTERPRISE` |
| `state` | One of the states below |
| `customer` | The customer the key was issued to |
| `endsAt` | The last day of the key in force, or of the one that ended |
| `graceEndsAt` | The last day of the grace, 30 days after `endsAt` |
| `auditExportUntil` | The last day the audit's export stays after the licence expired, 90 days after `graceEndsAt` |
| `seats` | `licensed`, the seats of the key in force (empty when nothing is counted), and `used`, the people who hold Owner, Admin or Member in any company, each once |
| `companiesLicensed` | The companies the instance may run: 1, except under Enterprise; empty for no cap |
| `features` | The paid features in force now, such as `OUTLOOK_ADDIN`, `AUDIT_TOOLS` or `LEGAL_HOLDS`. Under an Enterprise key it also lists `SIEM`, `HA` and `AIR_GAPPED`, which are planned and not built in 1.0 ([Editions and prices](../admin-guide/editions.md)) |

| State | Meaning |
|---|---|
| `NONE` | No key. The instance runs as Community. |
| `ACTIVE` | A valid key is in force. |
| `EXPIRING` | The key in force ends within 30 days, and no renewal is held. Admins see a notice. |
| `GRACE` | The key has ended, less than 30 days ago. Everything keeps working, with the notice. |
| `EXPIRED` | The grace is over. The instance runs as Community, with the exceptions of [When the licence ends](../admin-guide/editions.md#when-the-licence-ends), until a new key is applied. |

An instance admin reads the keys the instance holds with `GET /api/admin/licence`: `current`, the key in force (or the one that ended), and `next`, a renewal held until its start date. Each shows its `licenceId`, `rev`, customer, edition, seats, companies, start and end, and who applied it and when. `PUT /api/admin/licence` applies a key: one that starts by today replaces the key in force, and a later one is held as `next`, in place of any held. Neither ever returns a key's text.

## Problems

The API answers with a problem whose `code` names the reason.

| Status and code | When |
|---|---|
| `400 LICENCE_KEY_INVALID` | The text is not a key, or its signature does not verify: copy the whole line again. Or it is signed with a FLOWTIVO key this release does not know: upgrade Tinfirm, or ask FLOWTIVO for a key signed with a key it knows. |
| `409 LICENCE_KEY_EXPIRED` | The key's end date has passed. |
| `409 LICENCE_KEY_SUPERSEDED` | The instance holds a key of this licence with the same or a higher revision (`rev`). |
| `409 LICENCE_SEATS_TOO_FEW` | The key holds fewer seats than are in use on the day it comes into force. `details.required` and `details.licensed` give the numbers. Make some Members Viewers first. |
| `409 SEATS_EXHAUSTED` | A role that holds a seat (Owner, Admin, Member) was given to someone without a seat while every seat is taken. `details.licensed` and `details.used` give the numbers. |
| `403 VIEWER_READ_ONLY` | A Viewer tried to start or continue a conversation, own or edit an agent or a skill, write a trigger, or answer or decide a request. |
| `403 EDITION_REQUIRED` | The feature is not in the edition in force. `details.feature` and `details.edition` name it and the edition it needs. |

## Audit events

| Event | Recorded when |
|---|---|
| `licence.key.applied` | An instance admin applies a key. `details.next` says whether it was held as the next key; `details.replaced` names the key it replaced (`licenceId`, `rev`), or is empty |
| `licence.key.activated` | The next key takes over on its start date, recorded by the system |
