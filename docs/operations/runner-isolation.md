# Runner isolation: levels and where runs go

A runner host reports a level of isolation, and Tinfirm sends an agent's runs to the strongest level its company's hosts have, or to the level chosen for the agent. A member's own host takes their runs too when it reports that level or a stronger one. This guide is for the operators and admins who set up runner hosts and agents. The decision is [ADR-0113](../adr/0113-levels-of-isolation-and-placement.md); the binding rules are [CONVENTIONS §15.4](../CONVENTIONS.md#154-model-access-and-runner-hosts) and [§15.6](../CONVENTIONS.md#156-runs-and-turns), and the host's side is in the [runner README](../../runner/README.md#isolation-level).

## 1. The levels

| Level | The wall between an agent's code and the credentials | Who reports it |
|---|---|---|
| `machine` | Another machine: the credentials are not on the machine that runs the VM | A host with a VM per run at a provider (`--vm-driver digitalocean`); a host whose operator states it for a driver of their own |
| `vm-jail` | A jail around each VM, on the machine that holds the credentials | A host whose operator states it for a driver of their own |
| `container` | The container and the kernel | A host with Docker, Kata Containers included; a host with gVisor, shown as "container with gVisor"; and a host that has not connected to a server of this release yet |

The host sends its level in `hello.isolationTiers`, one value. A host that sends several is placed by the weakest. A host also reports whether the machine that runs its VMs is hardened: `yes`, `no`, or `none` for a VM per run at a provider, whose hypervisor is the boundary. A host that says nothing is `no`. Hardening orders hosts of one level. It never refuses a host and never makes a run wait.

Tinfirm shows what a host reports and checks none of it. A company host is trusted with its own report, as with everything else it does. A level the operator stated, rather than one the host could see, is marked "stated by the operator" on the host's card.

A host that has not connected to a server of this release yet has no stored level. It is placed as `container`, and its card shows no isolation until it connects. A host with a VM driver of the operator's own reports `container` unless the operator states another level.

## 2. Automatic and chosen

**Automatic** is the default for every agent. The company's hosts set the level: its runs go to the strongest level among the company's hosts that may run them. A member's personal host takes their own runs too, when it reports that level or a stronger one. Among the free hosts a stronger one comes first, then a hardened one. Only where no company host may run a turn do the member's own hosts set the level: a turn paid by their subscription, which only their own host runs, or a runtime no company host serves.

**A chosen level** is set in the agent's editor, under **Isolation**, by whoever may edit the agent: its owner, and the company's Owners and Admins. The editor offers a choice only where the company's hosts that serve the agent's runtimes report more than one level, or a level is already chosen. Only the levels those hosts report are offered. An agent with a chosen level runs on the company's hosts of that level, and on a member's own host that reports it or a stronger one.

A change of the setting makes a new version of the agent that widens nothing, so nobody has to accept it. It reaches every conversation's next turn. It is recorded in the audit log as `agent.changed`, with `isolation` and `previousIsolation`.

A member's personal hosts are never offered in the editor, and they never set the level while a company host may run the turn. A member whose own host reports a stronger level than the company's hosts gets their runs on it while it is free; while it is busy or offline, the company's hosts take them. A member's own host that reports a weaker level than the company's hosts takes none of their runs. No run waits for a personal host.

## 3. Waiting instead of a weaker host

A run never goes to a weaker host by itself. When every host of its level is busy or offline, the run waits for the company's hosts of that level, and its queue reason says so: "This agent's runs go to hosts that report machine and do not move to a weaker one." A hostile input that fills the strong hosts' slots cannot push the next run onto a container.

This costs availability. A company whose one `machine` host is offline has its automatic agents wait, though a `container` host stands free. What an admin can do:

- start the host again, or add another host of that level;
- choose `container` for an agent that may run in a container;
- remove the host that is gone: the strongest level is then what the remaining hosts report.

An agent with [skills](../../docs-site/content/user-guide/skills.md) waits the same way when the hosts of its level do not deliver skills (`NO_HOST_FOR_SKILLS`, naming the level): it does not go to a weaker host that does. Upgrade the runner hosts of that level, or choose a level for the agent whose hosts deliver them.

A conversation of an automatic agent stays on the host that holds its session while that host takes its turns, even when it is weaker than the level its new conversations go to, a member's own host included. The run's page and the conversation say so. When that host cannot take the turn, the turn goes to the strongest level with a fresh session of the runtime, and the conversation says why. With a chosen level, a turn whose session is on a host of another level starts a fresh session on the chosen level. Where a session bundle of the conversation is kept ([session-bundles.md](session-bundles.md)), the session moves with the turn to a free host of the level that restores bundles. Without such a host, an automatic agent's conversation stays on its session's host, and a chosen level starts a fresh session on another host of that level.

## 4. Removing the last host of a level

Removing a host is audited as `runner_host.removed`, with the level it was placed at and `lastOfLevel` when no other company host of that level remains.

- **Automatic agents** go to the strongest level that is left, from the next dispatch on.
- **An agent with that level chosen** keeps it. Its page and editor say that no company host reports the level any more. New conversations with it are refused with `409 NO_HOST_FOR_CONVERSATION`, reason `NO_HOST_OF_LEVEL`. Turns of its open conversations and its triggers' firings wait with the queue reason `NO_HOST_OF_LEVEL`, until a host reports the level again or its editor chooses another.

## 5. Upgrading: the server, then the VM hosts at once

In this release a host sends its level in `hello.isolationTiers`. A host of an older release sent its technology there: `container`, `gvisor`, or `microvm` for Kata Containers and for a VM per run. `container` means the same in both, so an older Docker host keeps working with the new server. `microvm` named two levels at once, so the new server cannot place such a host: it refuses its connection with `error` `UPGRADE_REQUIRED`, which names the server's version, and the host stops with that message instead of reconnecting. This is the one change that the server and some hosts take together ([ADR-0113](../adr/0113-levels-of-isolation-and-placement.md), decision 2, an exception to [ADR-0109](../adr/0109-runner-version-is-the-release-version.md)).

The order:

1. Upgrade the server.
2. At once, upgrade every runner host with a VM per run (`--executor vm`) or with Kata Containers. The new server refuses them until then, and runs that only they may take wait. A Kata host then reports `container`, a VM per run at a provider `machine`.
3. Upgrade the Docker hosts at any time. They keep working as container hosts until then.

A host is placed by the level it reports. So the moment one host reports `machine`, it is the strongest level of its company, and the company's automatic agents' new conversations go to it and to the members' own hosts of that level. They wait while it is offline or full, though other hosts stand free. Before the upgrade, decide for a company that has both VM hosts and container hosts:

- upgrade all its VM hosts together, so that they report `machine` together;
- or state the level of the VM hosts whose driver is your own (`run --isolation-level`, `--isolation-hardened`);
- or choose `container` for the agents that should keep running on the container hosts.

A company with one kind of host has nothing to choose, and nothing changes for it.

## 6. Outside input on a container

An agent whose runs can start from outside the company and would run in a container gets a warning on its page. Its runs can start from outside when it has a trigger, live or paused, on a tracker, a chat channel or a webhook. Schedules do not count, nor do members' own starts from the app, their AI clients, a direct message or an email add-in. The warning says what an escape from a container reaches: the runner host and its credentials. The fix is a stronger level for the agent, or a runner host that reports `vm-jail` or `machine`.

## 7. What to watch

- Each run records the level its host was placed at (`Run.isolationLevel`) and, when its session was elsewhere, why it ran where it did (`Run.placementNote`).
- `tinfirm_runs_assigned_total{isolation_level, isolation, placement_note}` counts assignments by level, automatic or chosen, and note.
- Many `STAYED_ON_WEAKER_HOST` notes mean that long-lived conversations stay on a weaker host. Session bundles move them.
- `runner_host.isolation_changed` in the audit records each change of a host's report.
