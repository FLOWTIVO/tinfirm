# Company runner hosts

This page is for a company's Owners and Admins. Agents' turns run on runner hosts, machines with Docker that run each turn in a sandbox of its own, never on the Tinfirm server. On **Runner hosts**, in the sidebar's **Company** section, you register the company's hosts, watch how they stand, and set how many turns each runs at a time. Without a company host, no turn paid by a budget runs.

Installing the host on its machine, its sandboxes and its upgrades are in the Operator guide: [Install and run runner hosts](../operator-guide/runner-hosts.md).

## Company hosts and personal hosts

| | Company host | Personal host |
|---|---|---|
| Who registers it | An Owner or Admin, here | Any member, for themselves, under **Accounts**, **Runner hosts** |
| What it runs | Any member's turns that a budget pays for, and triggers' turns paid by a budget | Only its owner's turns: those their own subscription on it pays for, and those their budget pays for when every account of the conversation is their own |
| Credentials on the machine | None: the turns reach their models through Tinfirm's model relay | Its owner's Claude or ChatGPT subscription, which never leaves the machine |
| Who sees it | Owners and Admins | Only its owner, Owners and Admins included |

This page lists the company's hosts only. If you are also a member, your own hosts are under **Accounts**, **Runner hosts**.

## Register a company runner host

1. Choose **Register a runner host**. The dialog **Register a company runner host** asks for a **Name**: letters, digits, dots, dashes and underscores, starting with a letter or digit, at most 100 characters, and not taken by another host of the company.
2. Choose **Register**. The dialog **Set up** the name shows the **Host token** and the commands to run on the machine, with **Copy all**.
3. Copy the token now. **This is the only time Tinfirm shows it**: the server keeps only its hash. If you lose it, remove the host and register it again.
4. Hand the token and the commands to whoever runs the machine. They log the host in, build its images, check it and start it:

    ```sh
    tinfirm-runner-host login --server https://tinfirm.example.com --token tfr_…
    tinfirm-runner-host images build
    tinfirm-runner-host doctor
    tinfirm-runner-host run
    ```

The host shows **Online** once it connects. Registering and removing hosts is recorded in the audit log.

## Read a host's card

The page updates every 10 seconds. Each host's card shows:

| What | Meaning |
|---|---|
| **Online** | Connected now |
| **Offline · last seen** and a time | Not connected; its turns wait or go to another host |
| **Offline · never connected** | Registered, but never logged in and started |
| **Draining** | Finishing its turns before it restarts, usually for an upgrade. It takes no new turn: **Finishing** so many runs **before it stops; it takes no new runs.** |
| The version, platform and machine name | Such as `v0.3.0 · linux/x86_64 · build-01` |
| **… of … slots in use** | The turns running now, of the slots it has |
| **Up to … on this machine** | Its ceiling: the most turns at a time the machine's owner allows |

A host whose runner is older than 0.2.0 says that it **is too old for turns paid by a budget**, and runs nothing for the company until it is upgraded. When hosts are registered but none takes turns, because none is online, new enough and not draining, the page warns **No company host takes turns now**.

## Runs at a time

**Runs at a time** on each card sets how many turns the host runs at once: 1 to 16, or **Use the machine's limit**. The machine's owner sets a ceiling on the host, and the host uses the lower of the two: Tinfirm can run fewer, never more. A number above the ceiling shows the ceiling beside it, such as **8 (4 on this machine)**.

The change saves at once and applies without a restart. While the host has not confirmed it, the card says **Waiting for** the host **to apply the change**, or, while it is offline, that it **applies the change when it connects**. Fewer runs at a time never stops a running turn: the host takes no new one until fewer run.

## Remove a host

Choose :lucide-trash-2: on the card, then **Remove runner host**. Its token stops working at once, and it takes no more turns. A host cannot be removed while turns are queued for it or running on it: cancel or let them end first. You can register the machine again later, with a new token.

## Why a turn waits for a host

A turn paid by a budget goes to a company host that is online, not draining, new enough, has a free slot and, when the agent has skills, delivers them. When the conversation's previous host has a free slot, it gets the next turn, so that the agent resumes its session there; otherwise the host with the most free slots does. When no host can take it, the turn waits, and the conversation says why. For Owners and Admins, the reason links to **Runner hosts**, to the host concerned.

| Waits with | What it means | What to do |
|---|---|---|
| **No runner host** | No connected host can run the agent's runtime, or none is new enough for a budget | Start or upgrade a company host |
| **Host offline** | The only host that may run it is offline; for a subscription, its owner's personal host is | Start the host again |
| **Host busy** | Every slot of the hosts that may run it is in use | Wait, raise **Runs at a time** up to the ceiling, or add a host |
| **Host restarting** | The host drains before it restarts | Wait: it takes turns again after the restart |
| **No host may run it** | Only personal hosts are connected, and they run their owners' work only | Start a company host |
| **No host for its skills** | The agent has [skills](../user-guide/skills.md), and none of the connected hosts that may run it delivers skills. A host delivers them when it runs with Docker and speaks runner protocol 2, as the current runner host does; it says so when it connects. | Upgrade the runner host, or start one that delivers skills. The turn never runs without its skills. |
| **Subscription busy** | The subscription that pays already runs as many turns as its owner allows | Wait |

A host can also refuse a turn it was sent, for example while its images are missing; the turn then waits a few seconds and is sent again.
