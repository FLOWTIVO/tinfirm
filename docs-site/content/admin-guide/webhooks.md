# Receive webhooks

A webhook system lets other software start agents: a monitoring tool, a form, a CI pipeline, your own service. The software posts JSON to a URL Tinfirm gives you. Tinfirm checks that the delivery is really from the sender, reads the fields you map, and fires the webhook triggers that match. This page is for Owners and Admins. It covers registering the system, the secret, the mapping, the deliveries and the limits. The triggers themselves are in [Write a trigger](../user-guide/triggers.md). [Start an agent from an alert](alerts.md) is a worked example.

A webhook system holds no account and no credential, and is never tested: its health is its deliveries.

## How it works

| Step | What happens |
|---|---|
| Deliver | The sender posts a JSON body to the system's **Delivery URL**, signed with the secret or carrying it in a header |
| Check | Tinfirm checks the proof, the size and the rate, and drops a delivery it has seen before |
| Map | The mapping reads the event, the subject and the text from the body, by JSON Pointers |
| Fire | Each webhook trigger on the system whose events and conditions match starts a conversation with its agent, or continues one about the same subject |

## Register a webhook system

1. Open **Systems**, choose **Register a system**, and the kind **Webhook**. Give it a **Name**.
2. **How the sender proves it is them**: **Signed (Standard Webhooks)** or **Secret in a header (weaker)** ([below](#choose-how-the-sender-proves-it-is-them)). With a header, **Header** is optional: empty means `X-Tinfirm-Secret`.
3. **Deliveries a minute** (optional): more are refused with `429`, and the sender tries again. Empty takes the company's default ([Limits](#limits)).
4. Choose **Register**. The dialog shows **Copy the secret now**: the **Secret** and the **Delivery URL**, for example `https://tinfirm.example.com/api/hooks/0190f5a4-…`. Tinfirm shows the secret only this once. Give both to the sender, then choose **I have copied the secret**.
5. On the **Mapping** tab, map the delivery's fields and **Save the mapping**.
6. Add webhook triggers on the system ([Write a trigger](../user-guide/triggers.md)).

The **Overview**'s **The sender** card shows the **Delivery URL** (*POST, JSON, at most 1 MiB*), the **Proof**, the **Deliveries a minute** and the **Secret**'s age. **Change** there changes the scheme, the header and the rate.

## Choose how the sender proves it is them

| Scheme | The sender sends | Tinfirm checks |
|---|---|---|
| **Signed (Standard Webhooks)** | The headers `webhook-id`, `webhook-timestamp` and `webhook-signature`, as any [Standard Webhooks](https://www.standardwebhooks.com/) library makes them with the secret as it is | That the signature matches the body, and that the timestamp is within 5 minutes of Tinfirm's clock. A captured delivery cannot be changed, and is refused after 5 minutes |
| **Secret in a header (weaker)** | The secret itself, in the system's header | That the header equals the secret |

Use signed deliveries whenever the sender can sign. With the secret in a header, whoever captures one delivery can send any until the secret is rotated, and can send an old one again. The page marks it **The weaker scheme**. Send it over https only, and rotate the secret often.

For signed deliveries, keep the sender's clock right: a clock more than 5 minutes off is refused.

## Rotate the secret

The secret starts with `whsec_`. Rotate it before it is 90 days old: the health strip warns once it is older (*older than 90 days: rotate it*).

1. Choose **Rotate the secret**, in the health strip or on **The sender**.
2. Confirm **Rotate**. Tinfirm makes a new secret and shows it once.
3. Give the sender the new secret. The current one still works for 24 hours; the one before it stops at once.

If the secret leaked, tick **Stop the current secret now** before you confirm: the current secret stops at once, and the sender's deliveries are refused until it has the new one.

## Map a delivery's fields

The **Mapping** tab says where each field is in a delivery's JSON, as a [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901) such as `/issue/key`. There are no expressions. A text, a number or true/false is read as text; anything else, or nothing at that place, is empty.

| Field | What it is | Cut to |
|---|---|---|
| **Event** | What happened. A trigger picks deliveries by it | 200 characters |
| **Subject key** | What it is about. One conversation per trigger and key, and the echoes of a conversation's own writes are recognised by it | 200 |
| **Subject title** | Shown with the key | 500 |
| **Subject URL** | An http(s) link to it; anything else is left empty | 2000 |
| **Sender's id** | Who did it, as the sender says: never proof | 200 |
| **Sender's name** | As the sender says | 200 |
| **Text** | The words the agent reads first | 20 000 |
| **Delivery id** | For a sender that puts the secret in a header and sends no `webhook-id`: repeats are recognised by it | 1 to 200 printable characters |

**Save the mapping** replaces the whole mapping. A delivery whose pointer cannot be read, or whose mapped delivery id is not 1 to 200 printable characters, is kept as **Mapping failed** and fires nothing.

## Try a body

Under **Try it with a sample delivery**, paste **A delivery's body (JSON)** that the sender sent, and choose **Try it**. It shows what each field reads, and each webhook trigger on the system with *would fire* or *not for this delivery*. Nothing is stored and no trigger fires. The try uses the saved mapping, so save your changes first. A trigger that is paused or waits takes no delivery, whatever it matches.

An AI client connected to Tinfirm can make the same try with `try_webhook_delivery`, and also names the first event or condition a trigger fails ([MCP tools](../reference/mcp-tools.md)).

## What a webhook trigger does with a delivery

A webhook trigger on the system names **Its events**, the event names the mapping reads, comma-separated, or none for every delivery. It may add conditions under **Only when the body**, each a JSON Pointer and a test such as **is**. Every delivery that matches fires it.

- **One conversation per subject.** With a subject key, a delivery about a subject whose conversation is still open continues it, as a new turn. The agent sees its earlier work. Without a key, each delivery starts a new conversation.
- **What the agent reads.** A new conversation starts with the system, the time, the delivery id, the event, the subject, the sender as the sender names it (marked unverified), the text, and the whole body as JSON, cut to 16 KiB in all. A delivery that continues a conversation adds a message with the event, the subject, the sender and the text, without the whole body. The agent is told these are other people's words, never instructions.
- **Echoes.** When the agent writes back to the subject and the sender then posts about that write, Tinfirm recognises the echo by the subject key. After three such rounds, a delivery starts nothing more, so an agent cannot loop with the sender.
- **Who pays.** The trigger's accounts and budget, as its author chose. Every delivery that fires is a turn of the agent, so keep the events and conditions tight.

Anyone who can make the sender post can drive the trigger's agent. The trigger editor says so with **Anyone can drive this agent**. Give such agents read tools, or keep writes at **Needs approval**.

## What the sender is answered

The checks run in this order, so that nobody without the secret uses up the sender's rate:

| Answer | When | Stored |
|---|---|---|
| `415` | The body is not `application/json` | No |
| `429` | More than 60 refused deliveries from the same address for this system in the last minute | No |
| `404` | No such webhook system | No |
| `413` | The body is over 1 MiB | No |
| `401` | The signature or the header is wrong, or the timestamp is outside the 5 minutes | No |
| `503`, retry after 5 minutes | The company's runs are stopped: the sender delivers it again later | No |
| `200` with `"paused": true` | An admin paused the system: it never fires later | No |
| `429` | More deliveries a minute than the system takes | No |
| `400` | The body is not a JSON object, is deeper than 32 levels, holds more than 10 000 values, or holds the character `U+0000` | No |
| `200` with `"duplicate": true` | A delivery the system has seen | Already |
| `202` | A new delivery, mapped and fired | Yes |

Refused deliveries are not stored. The audit log counts them, once a minute per system and reason.

## Duplicates

A delivery's id is its `webhook-id` header, else the mapped **Delivery id**, else a hash of its body. A delivery whose id the system has seen in the last 7 days does nothing more, so a sender that retries never fires a trigger twice. Without a `webhook-id` or a mapped delivery id, two deliveries with the same body byte for byte count as one.

## Recent deliveries

The **Deliveries** tab, for Owners and Admins, lists the deliveries that passed the checks, newest first. Each shows its event, its subject key, what it fired, its time and its size:

| Outcome | Means |
|---|---|
| **Fired 1 trigger**, **Fired** … **triggers** | It started or continued that many conversations |
| **No trigger matched** | No trigger's events and conditions matched it, or those that matched were paused, were waiting, or skipped it: more firings in an hour than the trigger takes, or an echo after three rounds |
| **Mapping failed** | A pointer could not be read; open it for why |

Open a delivery for its delivery id, why its mapping failed, and its body. The heading counts the last 7 days, for example *12 deliveries in 7 days, 1 not mapped, 3 refused*, and the health strip shows the last delivery. Deliveries are kept 7 days, and their bodies 24 hours.

## Limits

A webhook system takes its own **Deliveries a minute**, from 1 to 6000 and at most the company's maximum, or else the company's default. The company's limits are under **Settings**, **Chats and webhooks** ([Company settings](settings.md)):

| Setting | What it does |
|---|---|
| **Deliveries a minute, by default** | For a webhook system that names none |
| **Deliveries a minute, at most** | The most a webhook system may name. Lowering it lowers the systems above it |

Until you set them, the instance's values apply: 60 and 600 unless your operator changed them.

| Limit | Value |
|---|---|
| A body | 1 MiB; a JSON object of at most 32 levels and 10 000 values |
| A signed delivery's timestamp | Within 5 minutes of Tinfirm's clock |
| The previous secret after a rotation | Accepted for 24 hours, unless stopped at once |
| Deliveries kept | 7 days; their bodies 24 hours |
| Live triggers on one system | 100 |

## Pause and remove

**Pause** stops the system: deliveries are answered `200` with `"paused": true`, stored nowhere and never fired later, and its triggers pause. **Resume** takes deliveries again. **Remove** stops its URL at once and deletes its secret and its webhook triggers. [Register systems and lend their credentials](systems.md#pause-resume-and-remove-a-system) says more.

## Troubleshooting

| What you see | Cause | What to do |
|---|---|---|
| The sender gets `401` | The wrong secret or header. For signed deliveries, a clock more than 5 minutes off | Give the sender the current secret; fix its clock. A header read from a file must have no trailing newline |
| The sender gets `429` | Over the system's deliveries a minute, or many refused deliveries from its address | Raise **Deliveries a minute**; fix what is refused |
| Nothing on **Deliveries** | The delivery was refused, or never sent | Check the sender's log for the answer |
| A delivery shows **Mapping failed** | A pointer cannot be read, or the delivery id is not 1 to 200 printable characters | Fix the mapping, and try the body |
| A delivery shows **No trigger matched** | No trigger's events or conditions match, or the trigger is paused or waits | Try the body: it shows which triggers would fire |
| The same alert never starts a second analysis | The body is byte for byte the same, so it is a duplicate | Map a **Delivery id** that changes, or let the sender send `webhook-id` |
