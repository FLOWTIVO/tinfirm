# Provision people with SCIM

With SCIM, your company's identity provider adds people to Tinfirm, sets their role, and removes their access at once when they leave, from the same place as your other applications. Tinfirm serves SCIM 2.0 `Users` to Microsoft Entra ID, Okta and other SCIM clients. SCIM is part of [Enterprise](editions.md).

SCIM works once whoever runs your instance allows it for your company. Then your company's Owner issues a token in Tinfirm and pastes it at the identity provider. The guides for [Microsoft Entra ID](scim-entra-id.md) and [Okta](scim-okta.md) give each step.

## What SCIM does in Tinfirm

| Your identity provider | Tinfirm |
|---|---|
| Assigns a person to the app | Makes them a member of your company, with the role the provider sends, or the default role. A person who has no Tinfirm account yet gets one; they sign in later through your company's sign-in. |
| Changes their name or email | Changes them, if your company's SCIM created the person and no other company of the instance has them. |
| Changes their role | Changes it at once, and the role is no longer changed in Tinfirm: **Members** shows it as **managed by SCIM**. |
| Deactivates them | Suspends them in your company at once: their sessions, tokens and connected apps stop working there, and their running turns stop. A person who is in no other company is deactivated at the instance too. Their conversations, agents, triggers and files stay. |
| Reactivates them | Lifts the suspension SCIM set, and only that one. A suspension an Owner or Admin set stays. |
| Deletes them | Removes them from your company, as when an Admin removes a member: the departure's checklist for the agents and triggers they own, and their content kept. |

SCIM never erases anyone's content. An Owner erases a person's content on **Retention**, as for anyone.

SCIM provisions people, not groups. Tinfirm's groups stay yours to make and fill in **Members**, **Groups**: they decide who uses which agents and credentials, which an identity provider's groups were never meant to decide. Your identity provider sends each person's role instead.

## Roles

Your identity provider sends one of `admin`, `member` or `viewer`. A role that holds a [seat](roles.md#seats) becomes **Viewer** while every seat is taken: the provider's request still succeeds, and the company's Owners and Admins get a **Seats** notice in their inbox. Free a seat, or enter a key with more seats; Entra ID asks again at its next cycle, and with Okta you push the person again.

SCIM never makes anyone an Owner and never changes an Owner's role. It may deactivate or remove an Owner who left, but never the company's last active Owner: the provider gets an error, and that Owner gets a **Provisioning** notice in their inbox. Appoint another Owner first.

Who runs your instance chooses whether your identity provider decides roles after it creates a person (the default), or gives a role once and leaves it to you in **Members** after.

## The token

Only an Owner issues and revokes a token, in **Settings**, **Sign-in**. Admins see the tokens and when each was last used.

1. Select **New token**, give it a name such as **Entra ID**, and choose when it expires. Identity providers expect a token that does not expire; revoke it when you no longer need it.
2. Copy the token. It starts with `tfs_` and is shown once: Tinfirm keeps only a hash of it.
3. Paste it at your identity provider, with the **Base URL** shown above the tokens.

Two tokens work at a time. To replace a token, issue the next one, paste it at the provider, check that the provider's connection works, then revoke the old one. A revoked token stops at once.

Whoever holds a token can add, change and remove your company's members, so keep it where your identity provider's secrets are kept. Every change it makes is recorded in the **Audit log** under the token's name.

## When the licence ends

SCIM keeps working while the licence is in force and during its 30 days of grace. After that, your identity provider's requests are refused, and nothing SCIM did is undone: the people, their roles and their suspensions stay. Remove a person who leaves in **Members** until a new key is entered; then the provider's next cycle catches up. Roles SCIM managed become yours to change in **Members**.

## Sign-in of a person SCIM created

A person SCIM created has no password. They sign in through your company's identity provider, and their first sign-in is bound to the account SCIM made, by the id your identity provider sends in both (its `externalId`). The guides set that id up for each provider. Ask whoever runs your instance to turn off new accounts at sign-in for your company's provider, so that only the people your identity provider assigned can sign in.
