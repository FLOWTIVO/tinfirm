# Your budget and spend

Every turn of an agent calls a model through the company's model gateway, and someone pays for it. This page is for every member: who pays for your turns, how to read your budget, what happens when it runs out, how a subscription of your own can pay instead, and how to see what your turns cost. Owners and Admins set budgets: see [Model access and budgets](../admin-guide/model-access.md).

Your budget is on **Accounts**, in its **Budget** tab. The **Spend** page, with what your turns cost, has no entry in the sidebar and no link from other pages yet: open it by its address, `https://tinfirm.example.com/c/<company id>/spend`, with your company's id as in your inbox's address. Owners and Admins also find it under **Model access**, **Spend** ([Model access and budgets](../admin-guide/model-access.md#spend)).

## Who pays for a turn

| Work | Paid by |
|---|---|
| A conversation you start: in Tinfirm, from an AI client, an add-in or a chat direct message | Your budget, or a subscription of yours on your own runner host, as your [My settings](my-settings.md) for the agent choose |
| A trigger's conversation | The payer its author chose: the author, or a service account |

A conversation keeps its payer for its whole life: a change of your settings reaches new conversations. Tinfirm never switches to another payer by itself, and nobody else pays when your budget is spent. The company's own key pays for Tinfirm's own model requests, such as tidying agents' memories, never for a turn.

## Your budget

Open **Accounts** and its **Budget** tab. **Your budget** shows:

- what is left, of how much, per period: *$38.20 left of $50.00 per 30 days*, with a bar of what is spent;
- whose amount it is: **The company's default**, or **Set for you by an admin**;
- **Spent**, the **Period** (*Resets 1 Nov*), and the **Models** your budget may pay for: **Every model the gateway serves**, or a list;
- when the gateway last reported your spend. Spend reaches Tinfirm a few minutes after a turn.

Tinfirm keeps a key at the gateway for you, with your budget on it. You never see the key, unless your company's gateway team made it for you ([Your model key](#your-model-key)), and it never reaches a runner host or a sandbox. A note above the numbers says when something holds your budget up:

| Note | Meaning |
|---|---|
| **Model access is not set up yet** | An Admin has not set up the company's model gateway. Until then nothing is paid by a budget, and new conversations are refused unless a subscription of yours pays |
| **Your budget is being set up** | Tinfirm is creating your key at the gateway; it is ready in a moment |
| **Your budget is spent until {day}** | Turns it pays wait until it resets |
| **Your budget is blocked** | Your access to the company is suspended; nothing is paid by your budget until an Admin resumes it |
| **Your budget cannot be used right now** | The gateway refused your key or cannot be reached. Turns it pays wait, and Tinfirm tries again by itself |

Your budget can pay only for a model the gateway serves and your budget allows. Choose the model in your **My settings** for the agent: a budget needs one.

### Your model key

Some companies' gateway teams make each person's key themselves, with its budget and models ([A gateway whose team makes the keys](../admin-guide/model-access.md#a-gateway-whose-team-makes-the-keys)). Then, once a key is entered for you or where you may enter your own, the **Budget** tab also shows **Your model key**: who entered the key, its last four characters and when. Until a key is entered, your turns wait with **You have no key at the company's model gateway yet**.

Where your company lets members enter their own key, paste the key the gateway team gave you and choose **Save key**. Tinfirm asks the gateway whether it knows the key before it keeps it, keeps it in the company's vault and never shows it again. **Remove** forgets it, and your turns wait until a key is entered again. A key an admin entered is theirs to change.

### Budget alerts

When your spend crosses a threshold of your budget, your [inbox](inbox.md) says so: *80 % of your budget used*, with what is spent, when it resets and how many turns wait. Admins choose the thresholds, 80 % and 100 % at first. An alert caps nothing; you can dismiss it.

## When your budget runs out

When your budget is spent, the gateway refuses the next model request that it pays:

- A turn that was working fails with **Usage limit reached** (`USAGE_LIMIT`). The line under the thread says when your budget resets, and links to **Your budget**.
- New turns of your conversations wait, shown as **Waiting · Budget spent**: *Waiting for your budget to reset on {day}*. They start by themselves once the budget resets, or as soon as an Admin raises it.
- **New conversation** says *You can start; the first turn waits until your budget resets on {day}.*

The failed turn does not run again by itself: once your budget has reset, or been raised, choose **Retry** in the conversation or in your inbox. Retried before, it waits for the budget like any new turn. Meanwhile, a subscription on a runner host of yours can pay for new conversations.

To get more, ask an Owner or Admin to raise your budget.

## When a budget resets

A budget resets every period, 30 days unless an Admin sets another. The reset follows the calendar: a period of 30 days or one month resets on the 1st of the month, a week on Mondays, a day at midnight. Your **Budget** tab shows the next reset; before your first spend it reads **Resets with its first spend**. At the reset, your spend starts again from zero, and turns that waited for it start.

## Pay with a subscription on your runner host

You can pay for your own conversations with a Claude or ChatGPT subscription, or an Anthropic or OpenAI API key of your own, instead of your budget:

1. Register a runner host of your own and add the subscription on it: [Connect your accounts](accounts.md#your-runner-hosts).
2. Once the host reports it, the subscription shows under **Your subscriptions** on the **Budget** tab, with its host, its runtimes and its state.
3. In your **My settings** for an agent, pick the subscription as the payer.

What changes:

- The gateway does not meter a subscription's turns: in Tinfirm they cost nothing, and only their tokens are counted. Your subscription's own limits still apply at its provider.
- Its turns run only on its host. While the host is offline, they wait (**Waiting · Host offline**). While the subscription is busy with as many turns as the host lets it run, more wait (**Waiting · Subscription busy**). A turn that hits the subscription's usage limit fails with **Usage limit reached**, and the subscription shows **Cooling down** until its limit resets.
- It pays only for your own work. A trigger may be paid by its author's subscription only where the company allows it.
- If you remove the host, conversations paid by its subscriptions wait until another host of yours can run them. When a subscription your settings name is gone, **New conversation** asks you to pick another payer in your settings.

## The Spend page

**Spend** shows what turns cost, over a range of days. As a member you see what you paid, and the turns of the triggers you wrote or whose team you are in. Open it at `https://tinfirm.example.com/c/<company id>/spend` (see the top of this page). Its heading says *What your turns and your triggers' cost, by agent, trigger and model.*

| Role | What Spend shows |
|---|---|
| Member | The turns you paid, and those of your triggers |
| Admin | Every turn. Other people's personal conversations are summed per person, as *Their personal conversations* |
| Owner | Every turn; another person's personal conversation by its key, without its title |
| Viewer | Nothing |

How to read it:

- Group the rows by **Person**, **Agent**, **Trigger**, **Model** or **Time**, then optionally by one more (**Then by**). Over time, choose **Per** **Days**, **Weeks** or **Months**.
- **From** and **Until (not included)** set the range: this month at first, at most 366 days.
- **Only some** narrows it by **Agent**, **Trigger**, **Model** and **Counted from**; Owners and Admins also by **Who paid** and **Who acted**.
- Each row shows its **Turns**, **Tokens** and **Cost**, with a **Total**. Days are in your browser's time zone, and a turn counts on the day it ended. A conversation of yours links to it.
- **CSV** saves the same rows as a file. Each export is recorded in the audit log.

Where a cost comes from, **Counted from** tells: **The gateway's records**, the usual source; **Tinfirm's own count**, when the gateway's logs could not be read, shown apart as *by Tinfirm's count*; or **Subscriptions (reported, not metered)**, which have tokens and no cost.

## What one conversation cost

The **Cost** section beside a conversation shows what it cost so far, with its tokens: input, cache writes, cache reads and output. A turn's spend settles a few minutes after it ends; until then the total is marked `~` and holds the runtime's own estimate. A subscription's tokens are counted at no cost. Each finished turn shows its own cost on its status line, and the **Conversations** list shows what each conversation's settled turns cost.

## From your AI client

An AI client connected to Tinfirm reads the spend you may see with `get_spend`, as the **Spend** page shows it. See [Work with Tinfirm from your AI client](ai-clients.md).
