# Requirements

This page lists what each part of Tinfirm needs to run: hardware, software and network. Use it to plan an installation before you start; [Architecture](architecture.md) explains what each part does.

The numbers come from the limits each part runs with, such as the memory the Helm chart gives the server and the fixed size of a sandbox. They are starting points: watch the real use of your installation and adjust.

## What decides the size

Two numbers decide almost everything:

- **How many turns run at the same time.** Each running turn takes a sandbox on a runner host, and each sandbox may use up to **2 CPUs and 4 GiB of memory**. The runner hosts are by far the largest part of an installation.
- **How much the company keeps.** Conversations, their transcripts and their files live in PostgreSQL. Under Community nothing is purged, so the database grows for as long as the instance runs ([Retention in Community](../admin-guide/editions.md#retention-in-community)).

The number of people matters much less: the agents' work happens on the runner hosts, and the server mostly stores it and relays it.

## Minimum hardware

### A trial on one machine

To try Tinfirm with [Docker Compose](quick-start.md), with the server, PostgreSQL, LiteLLM and one runner host on the same machine:

| Resource | Minimum | Recommended |
|---|---|---|
| CPU | 4 cores | 8 cores |
| Memory | 16 GiB | 24 GiB |
| Disk | 60 GB free, SSD | 100 GB free, SSD |
| Operating system | Linux, or macOS on Apple silicon, with Docker and Compose v2 | Linux |

The Compose stack itself takes up to about 4.5 GiB: the server and LiteLLM may use 2 GiB each, and PostgreSQL what it needs. The rest is for the runner host and its two sandboxes, its default number of turns at a time.

### The deployment

The server and LiteLLM, on Kubernetes or on a Compose host. The minimum is what the Helm chart asks for by default.

| Part | Minimum | Recommended | Notes |
|---|---|---|---|
| **Server** | 0.25 CPU, 1 GiB memory | 1 to 2 CPUs, 2 GiB memory | It uses about 450 MB when idle. The chart's default limit of 1 GiB gives its Java heap 768 MiB. Give it 2 GiB when many turns run at once: the model relay holds up to 256 MiB of requests in flight. |
| **LiteLLM** | 0.25 CPU, 1 GiB memory | 1 CPU, 2 GiB memory | It uses about 500 MB when idle. The chart's default limit is 2 GiB. |
| **Total** | 1 CPU, 2 GiB memory | 2 to 4 CPUs, 4 GiB memory | On Kubernetes, leave room for a second server pod: an upgrade starts the new pod before it stops the old one. |

The server needs no disk of its own: its root filesystem is read-only, and it writes only to `/tmp`. LiteLLM writes into its own container's filesystem.

### PostgreSQL

| | Minimum | Recommended |
|---|---|---|
| Version | PostgreSQL 18 | PostgreSQL 18, managed or replicated, with point-in-time recovery |
| CPU | 1 core | 2 cores or more |
| Memory | 2 GiB | 4 GiB or more |
| Disk | 20 GB, SSD | 100 GB or more, SSD, that you can grow |
| Connections | 25 | 50 or more |

**Connections.** Count them for both databases: each server replica keeps a pool of up to 10 connections, its migrations take one more at start, LiteLLM keeps up to 10, and you need a few for administration. A small managed plan can have a low limit, about 22 connections on a 1 GiB plan at some providers: then lower the pools ([Prepare PostgreSQL](database.md)).

**Disk.** The database holds every conversation's transcript and files: a conversation can hold up to 50 files of up to 25 MB each. Watch its size from the start and grow the disk before it fills. Under Pro and Enterprise, the company's Owners set retention periods that keep it bounded ([Retention, legal holds and erasure](../admin-guide/retention.md)). Backups with point-in-time recovery need space of their own, usually several times the database.

### Runner hosts

A runner host runs a number of turns at a time, its **slots**: 2 by default, up to 16. Size it from the number of slots:

| Resource | Per slot | Plus, for the host itself | Example: 4 slots |
|---|---|---|---|
| CPU | 2 cores | 1 core | 9 cores |
| Memory | 4 GiB | 2 GiB | 18 GiB |
| Disk | | 100 GB, SSD | 100 GB, SSD |

- **Memory** is a hard limit per sandbox, without swap: when the processes of a turn need more than 4 GiB, the kernel ends one of them, often the agent's build. Plan all of it for every slot: a host with less memory than its slots' total can run out while the sandboxes stay within their limits.
- **CPU** is a ceiling. A turn mostly waits for the model and uses its CPUs when the agent builds or tests code. Agents that rarely build can share fewer cores than 2 per slot; agents that compile large projects need all of them.
- **Disk** holds the runner images (a base image with Node, a JDK, Maven, Gradle, Python and Docker, and one image per runtime: several gigabytes in all), a mirror of every repository the agents work on, the agents' session homes, and each running turn's workspace, a full checkout of its repositories. Mirrors and homes that no turn used for 30 days are deleted. Plan more disk for large repositories, and for **Docker in the sandbox**: each turn that uses it gets a disk of up to 40 GB, which grows as its containers need.
- **Kata Containers** runs each sandbox as a microVM, which adds the VM's own base memory to each slot: `default_memory` in Kata's configuration, 2 GiB by default. Kata needs `/dev/kvm`: bare metal, or a VM with nested virtualisation.

Add hosts rather than growing one without end: several hosts also keep agents working while one is down or being upgraded.

### Examples

These assume Claude Code or a similar runtime and agents that sometimes build code.

| | Small team | Department |
|---|---|---|
| People | up to 25 | up to 150 |
| Turns at a time | 4 | 16 |
| Deployment (server, LiteLLM) | 2 CPUs, 6 GiB | 4 CPUs, 8 GiB |
| PostgreSQL | 2 CPUs, 4 GiB, 50 GB | 4 CPUs, 8 GiB, 200 GB |
| Runner hosts | one host: 9 CPUs, 18 GiB, 150 GB | four hosts of 4 slots: each 9 CPUs, 18 GiB, 200 GB |

## Software

| Part | Requirement |
|---|---|
| Kubernetes, for the Helm chart | Kubernetes 1.29 or later, Helm 4, an ingress controller or a Gateway API implementation, and a way to create Secrets. OpenShift and namespaces that enforce the `restricted` Pod Security Standard work too. |
| Docker Compose, for one host | Docker Engine with Compose v2 |
| Database | PostgreSQL 18 |
| Runner host | Linux on x86_64 or arm64 (the binary is static and runs on any distribution), or macOS on Apple silicon; Windows is planned. Docker (Docker Engine on Linux, Docker Desktop on macOS) and `git`. |
| Runner host, with Kata Containers | Linux with `/dev/kvm`, Docker Engine 23 or later. The installation by Ansible supports Ubuntu 22.04 and 24.04, Debian 12 and 13, and RHEL, Rocky Linux or AlmaLinux 9 and 10. |
| Runner host, with the egress proxy | Docker Engine on Linux; not Docker Desktop |
| Models | An account and an API key at a model provider that LiteLLM supports, such as Anthropic, OpenAI or Amazon Bedrock, or a gateway of your own |
| Sign-in, optional | An identity provider that speaks OIDC, such as Microsoft Entra ID, Keycloak or Okta |

## Network

| Need | Why |
|---|---|
| A host name with a TLS certificate, such as `tinfirm.example.com` | People, AI clients, add-ins, chats, webhook senders and runner hosts all reach the server there |
| The ingress allows request bodies up to **64 MiB** and responses that pause up to **10 minutes**, and passes WebSockets | Runners' model requests go through the server and stream long answers; a conversation's uploads are up to 25 MB; runner hosts hold a WebSocket open. ingress-nginx allows 1 MiB and 60 seconds by default: [Install on Kubernetes with Helm](helm.md) gives the annotations. |
| Runner hosts reach the server's host name on 443, and the code hosts | They open every connection themselves and need no inbound port |
| The server reaches PostgreSQL, LiteLLM, the identity provider and the company's systems | Its outbound connections: [Architecture](architecture.md#connections) lists them |
| LiteLLM reaches the model providers on 443 | And `raw.githubusercontent.com` for its price list, unless you turn that off |
| Slack's Events API, Microsoft Teams, Google and webhook senders reach the server from the internet | Only for the chats, the Gmail add-on and the webhooks you use. Slack's Socket Mode needs only outbound access. |
| The clocks are right: NTP or your network's time source | Licence keys, sign-in tokens and signed webhooks are checked against the server's clock |

On a network without internet access, the server needs no route out except to the systems you connect, and LiteLLM only to the models you use: a model inside your network is enough.

## Before you start

- [ ] A host name and a TLS certificate for it
- [ ] PostgreSQL 18, with room to grow, and backups
- [ ] A secret manager or a safe place for the vault's master key and LiteLLM's keys
- [ ] An account at a model provider, and its API key
- [ ] At least one machine for a runner host
- [ ] Optional: an OIDC client at your identity provider ([Declare companies and how their people sign in](sign-in.md))

Then [install on Kubernetes with Helm](helm.md), or [try Tinfirm with Docker Compose](quick-start.md).
