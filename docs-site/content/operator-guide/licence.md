# Enter and renew a licence key

A licence key turns an instance from Community into Pro or Enterprise, at once and with no reinstall: every image holds every edition. [Editions and prices](../admin-guide/editions.md) says what each edition includes.

A licence key is a line of text that FLOWTIVO signs. The server checks its signature itself, against FLOWTIVO's public keys built into it, when it starts and every hour after. It never calls out to check a key, so a licence works the same on an instance with no route to the internet.

## Who enters the key

One key covers the whole instance, every company in it. It is not bound to anything the installation generates, so you can receive it before you install.

**One production install per key.** A key covers one production installation. You may also use it on your staging and test installations. Your contract sets this, not the key: nothing in the key ties it to an installation, and nothing is reported to FLOWTIVO. The customer's name shows on the licence page, and the licence's id is in the instance audit log.

- An **instance admin** enters the key, on the **Licence** page of the **Instance admin** section.
- Each company's **Owners** and **Admins** see the edition, the seats used of those licensed and the end date in their company's **Settings**, under **Licence**, read-only.

The key is never a file the deployment mounts, an environment variable or a Helm value. The server keeps the key in its database, and the instance audit log records each key applied (`licence.key.applied`) and each renewal that takes over on its start date (`licence.key.activated`, recorded by the system).

## Enter a key

1. Copy the key from the email or the file FLOWTIVO sent you. It is one line that starts with `TINFIRM1.`.
2. Sign in as an instance admin, and open **Licence** under **Instance admin**.
3. Under **Paste a new key**, paste it into **Licence key**, and choose **Enter key**.

The page confirms **Key entered**, **Key replaced** or **Next key entered**. The **This instance** card shows the edition and its state, the customer, the end, the end of the grace, the seats used of those licensed, and the paid features. Under **Key**, the page shows the key in force, marked **In force**, and a renewal held for later, marked **Next, from** its start date. The page lists no other keys, and a key cannot be removed. When the server refuses a key, the page says why; [Licence keys](../reference/licence.md#problems) lists every reason.

## One key at a time

One key is in force at a time, with the whole licence in it: the edition, the total of seats and the end date. Each key also carries the licence's id and a revision, which FLOWTIVO raises with every new key of the same licence.

## Add seats

Seats bought during the year come as a new key: the same licence, the next revision, the new total of seats and the same end date. Paste it on the **Licence** page: it replaces the key in force at once. The price for the rest of the year is on the invoice, not in the key.

A key of the same licence with an older or the same revision is refused (`LICENCE_KEY_SUPERSEDED`), so an older key cannot be pasted back.

## Renew

FLOWTIVO sends the renewal key before the end date. It starts later than today, so the server holds it as the next key, shown as **Next, from** its start date, beside the key in force, and it takes over on its start date, with no gap. Paste it as soon as you receive it. The server holds at most one next key: a renewal pasted while another is held replaces the held one.

While a renewal waits to take over, the instance's admins and the companies' Owners see no notice of the end.

**Fewer seats.** The seats of a key are checked against the people who hold one on the day the key comes into force. A key that comes into force at once with too few seats is refused (`LICENCE_SEATS_TOO_FEW`): the Owners first make some Members Viewers ([Seats](../admin-guide/roles.md#seats)), then you paste the key again, and the key in force keeps working meanwhile. A renewal held for later is checked when it takes over, so make the Members Viewers before its start date.

### Renew an air-gapped instance

A key is plain text, so an instance with no route to the internet renews like any other. Carry the key into the network the way you bring in any other text, such as a file on approved media or a message typed or pasted across, and paste it on the **Licence** page. Nothing else needs to reach the instance, and nothing leaves it.

## When FLOWTIVO changes its signing key

FLOWTIVO can sign keys with more than one key of its own, and replaces them from time to time. Each Tinfirm release trusts the keys FLOWTIVO used up to then. A key signed with a newer key than your release knows is refused (`LICENCE_KEY_INVALID`, "signed with a FLOWTIVO key this Tinfirm does not trust"): upgrade Tinfirm, or ask FLOWTIVO for a key signed with an older one.

If one of FLOWTIVO's signing keys were ever exposed, FLOWTIVO sends you a new key of the same licence, signed with another, before a release stops trusting the exposed one. Paste it as you would to add seats, then upgrade.

## What happens at the end

From 30 days before the end date, a banner above every company page warns Owners, Admins and instance admins, with a link **Open the licence** for an instance admin, and the companies' Owners and Admins get an inbox notice marked **Licence**. For 30 days after it, everything keeps working, with the notice. Then the instance runs as Community, with exceptions that keep the companies' data and compliance as they were. [When the licence ends](../admin-guide/editions.md#when-the-licence-ends) lists them. A new key brings back everything at once: nothing was removed.

!!! warning "Keep the clock right"

    The server reads the dates of a key against its own clock. Run NTP, or your network's time source, on the hosts of the server, as you would for anything that signs in through OIDC.
