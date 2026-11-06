# Model access and budgets

This page is for a company's Owners and Admins. Every turn of an agent calls a model through the company's model gateway, and someone pays for it. On **Model access**, in the sidebar's **Company** section, you choose the gateway, set the budgets that pay for turns, and see what they spent. The page has four sections: **Gateway**, **Default budget**, **Members' budgets** and **Spend**.

The operator installs the gateway, adds its providers and models, and connects the company's other services to it: [Run the model gateway](../operator-guide/model-gateway.md).

## Who pays for a turn

| Work | Paid by |
|---|---|
| A conversation | The person who started it: their budget, or a Claude or ChatGPT subscription on their own personal runner host, as **Who pays** in their **My settings** of the agent says |
| A trigger's conversation | The trigger's payer, which its editor names: its author or a service account, from their budget. The author's subscription only where the company allows it, under **Settings**, **General**, **Triggers**, **Its author's subscription**. |
| Tinfirm's own requests, such as tidying agents' memories | The [company key](#the-company-key) |

Each member and each service account has one key at the gateway, with a **budget**: an amount in USD per period, and the models it may call. Tinfirm makes the keys, or, at a [gateway whose team makes the keys](#a-gateway-whose-team-makes-the-keys), you enter them; Tinfirm keeps them in its vault, and never switches a conversation's payer. A subscription is not metered: it shows on **Spend** with its tokens and no cost. Members see their own budget under **Accounts**, **Budget**: [Your budget and spend](../user-guide/spend.md).

## Set up the gateway

A new company can pay for nothing until its gateway passes a test. Until then, the **Gateway** section says **Model access is not set up**, and every new conversation on a budget is refused: members see **An admin must set up model access first**.

1. Open **Model access**. **Gateway** shows where the gateway stands, with three steps: **Choose the model gateway**, **Set it up**, **Test it**.
2. Under **Which model gateway**, choose the **Gateway**:
    - **Bundled**: the LiteLLM that comes with this Tinfirm deployment. Nothing to enter. When the deployment bundles none, the page says **This deployment bundles none.** and you cannot choose it.
    - **Your own LiteLLM**: a LiteLLM your company already runs. Enter its **Admin URL**, where Tinfirm reaches LiteLLM's admin API, and a **Master or admin key**. The key is **Write-only**: it is kept in the company's vault and never shown again, and a new URL needs the key again.
3. Check the **Default budget** ([below](#default-budget)): setting up saves it with the gateway.
4. Choose **Set up model access** in the bar at the bottom.
5. Choose **Test**. The test reaches LiteLLM's admin API, makes sure the company exists there, and lists the models the gateway serves. When it passes, the page says **The gateway answered and serves** so many models, **Turns paid by a budget may start**, and every member and service account gets a key. When it fails, it gives LiteLLM's reason; fix it with the operator, then choose **Test again**.

Once the gateway is **Ready**, the card lists under **Serves** the models people can pick. The **Relay** row shows the address through which runner hosts reach the models; they never see a key.

A change of the gateway, its URL or its key needs a new test, and the card says **Test the gateway again**: until it passes, nothing is paid by a budget. A change of the gateway or its URL also deletes every key of the company at the former gateway; the new gateway's test makes them again, with the same budgets and models.

The operator decides which addresses your own LiteLLM may have: by default an `https` address on the internet, not inside your network. Ask them when the test refuses the address.

## A gateway whose team makes the keys

Some companies run their own model gateway and do not give Tinfirm its admin API. Its team makes a key for each member and service account, with the budget and models it allows, and the gateway enforces them. Tinfirm makes, rotates and deletes no key there: you enter the keys the team made.

1. Under **Which model gateway**, choose **Keys from your gateway** as the **Gateway**.
2. Enter the **Model URL**, where the relay sends the model requests, each with the key of whoever pays.
3. Under **Gateway product**, choose **LiteLLM** or **Another gateway**. Tinfirm reads a spent budget in the gateway's own words: LiteLLM's, or another gateway's `402`, or a `429` that speaks of a budget, a quota, a spend limit, credit or billing and not of a rate limit.
4. Turn on **Members' own keys** when members may enter the key the gateway team gave them themselves, under **Accounts**, **Budget**. Admins always may. A member replaces or removes only a key they entered; one an admin entered is the admin's to change.
5. Choose **Set up model access**, then **Test**. The test asks the gateway for its model list with a key no gateway issues: a refusal of that key shows the gateway answers. When it passes, the page says **The gateway answered. Turns may start for whoever has a key entered.**, and **Serves** says that each key serves the models its gateway team allows.
6. Enter each key: **Change budget** on a row of **Members' budgets** opens **Key of** the name. Paste the key and choose **Save key**. Tinfirm asks the gateway whether it knows the key before it keeps it: **The gateway refused this key** means it does not. A key is one payer's: a key already entered for another member, service account or the company is refused. The key is kept in the company's vault and never shown again, only its last four characters. The [company key](#the-company-key) is entered the same way, on its row.

Until a payer has a key, their turns wait with **You have no key at the company's model gateway yet**, and go on once one is entered. Budgets and models are set at the gateway, not here, and the gateway team rotates the keys. When a member or service account is suspended or leaves, Tinfirm stops using their key and records **model_key.revoke_requested** in the audit log: ask the gateway team to revoke it there. Spend is what the relay counted from each turn's answers.

A change of the model URL, or a change to another kind of gateway, forgets every key entered and records **model_key.revoke_requested** for each; a change of the product or of **Members' own keys** keeps them.

## Default budget

**Default budget** holds the budget and models of every member and service account without their own. A change reaches their keys at once, and a line under the fields says which keys saving reaches.

| Field | What it sets |
|---|---|
| **Budget** | What each key may spend in a period: from $0 to $1,000,000, in dollars and cents |
| **Period** | How often every key's budget resets: a number from 1 to 9999 and **hours**, **days**, **weeks** or **months**. 30 days at first. |
| **Models** | **Every model the gateway serves**, or **Only these**, picked from what the gateway serves. A model it no longer serves stays listed, marked **not served now**. |

The period is the company's, for every key, also those with a budget of their own. LiteLLM sets each key's reset from the period and the calendar: 30 days and 1 month reset on the 1st of the month, 7 days on Mondays, 1 day at midnight, hours on the hour, and other numbers of days from midnight of the day the key was made. A new period restarts every key's period. Each budget shows when it resets next.

Limit the models with care. A runtime asks for models on its own, such as Claude Code's small model for background work: when a key does not allow a model a turn asks for, the turn fails. Ask the operator which models the agents need.

### Alerts

**Alerts** tell people in their inbox as budgets are spent. They apply to every budget, a key's own or the default, and cap nothing.

| Field | What it does |
|---|---|
| **Tell people at** | One to three percentages of a budget, 80 and 100 at first. A member is told of their own budget, the Owners and Admins of a service account's, and of everyone's once it is spent. Each percentage is told once per period. |
| **Tell the admins when a month passes** | An amount of the company's spend in a calendar month (UTC). The Owners and Admins are told once that month when the spend passes it. Leave it empty for no alert. **This month so far** shows the month's spend. |

**Save** in the bar at the bottom saves the gateway, the defaults and the alerts together; **Discard** drops your changes.

## Members' budgets

**Members' budgets** lists every member, every service account and the **Company key**, with what each spent in the current period. Those near or past the limit come first, then those with their own budget, then those on the default. **Show** filters the list: **All**, **Their own budget**, **Near the limit**, **Spent**; the search finds a name or a member's email.

Each row shows its badge, **Own budget** or **The default**, its models, and a bar of what is spent: neutral, from 80 % **Near the limit**, and **Spent** once the budget is used up. Under the bar, when the budget resets, or what is wrong with the key:

| Note | Meaning |
|---|---|
| **Resets** and a day | The key works |
| **New turns refused until** a day | The budget is spent: LiteLLM refuses the key until the reset |
| **Blocked while suspended** | The member or service account is suspended: [Kill switches](kill-switches.md) |
| **Key error** and a reason | LiteLLM refused the key or did not answer. Tinfirm tries again every 5 minutes. |
| **No key yet**, **No key until the gateway passes its test** | The key is not made yet |

The spend is what LiteLLM said at its last reading, every few minutes and after each turn.

### Change one budget

1. Choose **Change budget** on the row. The dialog **Budget of** the name shows what is **Spent now** and when it **Resets**.
2. Under **Budget**, choose **The company's default**, or **Their own** (a member) or **Its own** (a service account) and an amount per period.
3. Under **Models**, choose **The company's default**, or **Only these** and the models.
4. Choose **Save**. The key in LiteLLM follows at once. A raised budget frees a spent key.

A member's own subscription pays for their own work and shows no budget here.

## The company key

The company has one more key, nobody's. It pays for Tinfirm's own model requests, never for a turn: today, the tidying of agents' memories, whose model is chosen in **Settings**, **Memory**: [Company settings](settings.md). Tinfirm makes it once the gateway passed its test. Set its budget and models on its row of **Members' budgets**, **Company key**, like a member's. Tinfirm replaces it like the other keys; it is never blocked and raises no budget alert.

## When a budget is spent

1. LiteLLM refuses the key's next model request. The turn that made it fails with **Usage limit reached**.
2. The payer's next turns wait, each with **Budget spent**: **The budget that pays for this conversation is spent; the turn goes on once it resets.**, and the time of the reset. A new conversation can start, and its first turn waits.
3. The alert at 100 % tells the member, or the Owners and Admins for a service account.
4. The turns go on by themselves at the reset, or as soon as you raise the budget: their own on **Members' budgets**, or the default.

## Keys for the company's own services

The bundled gateway also serves the company's own services, such as a script or an internal application, each with a LiteLLM key of its own. The operator makes those keys in LiteLLM. Their spend is in LiteLLM under each key, not on this page. Tinfirm replaces each member's and service account's key by itself, every 30 days unless the operator chose otherwise, with no gap for running turns. If you suspect that a key leaked, ask the operator to rotate it at once: [Rotate secrets and keys](../operator-guide/model-gateway.md#rotate-secrets-and-keys).

## Spend

**Spend** shows what the agents' turns cost in this company, by person, agent, trigger and model.

1. Under **Group the spend by**, choose **Person**, **Agent**, **Trigger**, **Model** or **Time**, and optionally **Then by** a second grouping: **Who paid**, **Who acted**, **Agent**, **Trigger**, **Conversation**, **Model** or **Period**. With a time grouping, **Per** chooses **Days**, **Weeks** or **Months**.
2. Set **From** and **Until (not included)**: the current month at first, 366 days at most. Days are in your browser's time zone, and a turn counts on the day it ended.
3. Under **Only some**, filter by **Who paid**, **Who acted**, **Agent**, **Trigger**, **Model** or **Counted from**.

The table shows each row's **Turns**, **Tokens** and **Cost**, and the **Total**. **Who paid** is the budget that paid; **Who acted** is whose accounts the agent used, which can differ for a trigger.

| Counted from | What it is |
|---|---|
| **The gateway's records** | LiteLLM's record of each request: the cost the budgets count |
| **Tinfirm's own count** | Where the gateway kept no record, Tinfirm's own count of the turn's tokens, priced at the gateway's prices |
| **Subscriptions (reported, not metered)** | The tokens a subscription's runner reported, with no cost |

What you see depends on your role. An Admin sees every turn, with other people's personal conversations summed in one row per person, **Their personal conversations**. An Owner sees each of them by its key, without its title; opening one is a read of that conversation, recorded and shown to its owner. Turns of triggers' conversations show with their titles. **CSV** downloads the rows as shown, and is recorded in the audit log.
