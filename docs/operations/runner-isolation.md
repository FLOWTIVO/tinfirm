# Runner isolation: levels and where runs go

A runner host reports a level of isolation, and Tinfirm sends an agent's runs to the strongest level its company's hosts have, or to the level chosen for the agent. This guide is for the operators and admins who set up runner hosts and agents. The decision is [ADR-0113](../adr/0113-levels-of-isolation-and-placement.md); the binding rules are [CONVENTIONS §15.4](../CONVENTIONS.md#154-model-access-and-runner-hosts) and [§15.6](../CONVENTIONS.md#156-runs-and-turns), and the host's side is in the [runner README](../../runner/README.md#isolation-level).

## 1. The levels

| Level | The wall between an agent's code and the credentials | Who reports it |
|---|---|---|
| `machine` | Another machine: the credentials are not on the machine that runs the VM | A host with a VM per run at a provider (`--vm-driver digitalocean`); a host whose operator states it for a driver of their own |
| `vm-jail` | A jail around each VM, on the machine that holds the credentials | A host whose operator states it for a driver of their own |
| `container` | The container and the kernel | A host with Docker; and every host that reports no level |

A host also reports whether the machine that runs its VMs is hardened: `yes`, `no`, or `none` for a VM per run at a provider, whose hypervisor is the boundary. Hardening orders hosts of one level. It never refuses a host and never makes a run wait.

Tinfirm shows what a host reports and checks none of it. A company host is trusted with its own report, as with everything else it does. A level the operator stated, rather than one the host could see, is marked "stated by the operator" on the host's card.

A host that reports no level is placed as `container`, whatever its tier. That covers a runner host of an older release, a Kata host, and a host with a VM driver of the operator's own that states no level.

## 2. Automatic and chosen

**Automatic** is the default for every agent. Its runs go to the strongest level among the hosts that may run them: the company's hosts, and for a member's own work their personal hosts. Within that level a hardened host comes first.

**A chosen level** is set in the agent's editor, under **Isolation**, by whoever may edit the agent: its owner, and the company's Owners and Admins. The editor offers a choice only where the company's hosts that serve the agent's runtimes report more than one level, or a level is already chosen. Only the levels those hosts report are offered. An agent with a chosen level runs on hosts of that level only.

A change of the setting makes a new version of the agent that widens nothing, so nobody has to accept it. It reaches every conversation's next turn. It is recorded in the audit log as `agent.changed`, with `isolation` and `previousIsolation`.

A member's personal hosts are never offered in the editor. They do count for their owner's own runs, so a member whose own host reports a stronger level than the company's hosts raises the level of their own runs of an automatic agent. Those runs then wait while that host is offline.

## 3. Waiting instead of a weaker host

A run never goes to a weaker host by itself. When every host of its level is busy or offline, the run waits, and its queue reason says so: "This agent's runs go to hosts that report machine and do not move to a weaker one." A hostile input that fills the strong hosts' slots cannot push the next run onto a container.

This costs availability. A company whose one `machine` host is offline has its automatic agents wait, though a `container` host stands free. What an admin can do:

- start the host again, or add another host of that level;
- choose `container` for an agent that may run in a container;
- remove the host that is gone: the strongest level is then what the remaining hosts report.

A conversation of an automatic agent stays on the host that holds its session while that host takes its turns, even when it is weaker than the level its new conversations go to. The run's page and the conversation say so. When that host cannot take the turn, the turn goes to the strongest level with a fresh session of the runtime, and the conversation says why. With a chosen level, a turn whose session is on a host of another level starts a fresh session on the chosen level. Where a session bundle of the conversation is kept ([session-bundles.md](session-bundles.md)), the session moves with the turn instead.

## 4. Removing the last host of a level

Removing a host is audited as `runner_host.removed`, with the level it was placed at and `lastOfLevel` when no other company host of that level remains.

- **Automatic agents** go to the strongest level that is left, from the next dispatch on.
- **An agent with that level chosen** keeps it. Its page and editor say that no company host reports the level any more. New conversations with it are refused with `409 NO_HOST_FOR_CONVERSATION`, reason `NO_HOST_OF_LEVEL`. Turns of its open conversations and its triggers' firings wait with the queue reason `NO_HOST_OF_LEVEL`, until a host reports the level again or its editor chooses another.

## 5. Upgrading: VM hosts first

A host is placed by the level it reports, and a host that reports none counts as `container`. So the moment one host reports `machine`, it is the strongest level of its company, and the company's automatic agents' new conversations go to it alone. They wait while it is offline or full, though other hosts stand free.

Before a company that already has VM hosts takes a release that reports levels:

- upgrade the VM hosts first, so that they report `machine` together;
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
