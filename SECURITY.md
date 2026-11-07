# Security policy

## Report a vulnerability

Report vulnerabilities privately. Never report one in a public issue, pull request or discussion.

Report it in either of two ways:

- **GitHub private vulnerability reporting.** Open the repository's **Security** tab, select **Report a vulnerability** and fill in the form ([direct link](https://github.com/FLOWTIVO/tinfirm/security/advisories/new)). Only you, the maintainers and the people they add to the advisory see the report and the conversation about it.
- **Email** to [security@flowtivo.com](mailto:security@flowtivo.com).

Include in your report:

- the affected component (server, web app, runner host, Helm chart, Compose stack, release assets, CI workflows) and its version: the release (`v<x.y.z>`), the image tag (`sha-<commit>`), the version of a `rolling` asset, or the commit. The server reports its version and commit at `GET /api/system/info`; `tinfirm-runner-host --version` prints the runner host's;
- the configuration the issue needs, if any, such as the sign-in method or the runner host's executor;
- the steps to reproduce, and the impact you expect;
- any known workaround;
- whether the issue is known publicly, and where.

Do not test against instances that you do not own or operate.

## What to expect

- A maintainer answers where you reported: in the advisory, or by email.
- The maintainers reproduce the issue, and tell you whether they accept it as a vulnerability, or why they do not.
- An accepted vulnerability is fixed as [Supported versions](#supported-versions) says. Keep the details private until the fix is released and the advisory is published.
- The maintainers publish a GitHub security advisory once a fixed version is available. It credits you, unless you ask not to be named.

## Supported versions

Tinfirm ships in two ways. A release `v<x.y.z>` is cut by hand from `main`, or from a hotfix branch (`.github/workflows/release.yml`): its images, its Helm chart and its release assets. And CI publishes every commit on `main` that passes its checks: the images tagged `sha-<commit>`, the tag `main` on the newest of them, and the prerelease `rolling` with the runner host and the Compose files.

| Version | Security fixes |
|---|---|
| `main`: its newest commit, the images tagged `main` and the prerelease `rolling` | Yes. A fix lands on `main` first, and CI publishes it with that commit. The image `sha-<commit>` of an older commit gets no fix: move to the newest. |
| The latest release | Yes, in the next release. A fix that cannot wait for it can ship as a patch release from a hotfix branch cut at the release's tag (`.github/workflows/hotfix-branch.yml`). |
| Older releases | No. Upgrade to the latest release. |

No version is released yet: until the first release, `main`, its newest images and `rolling` are the supported versions.

## Known risks

The [threat model](docs/threat-model.md) lists the known threats, the controls, and the accepted risks (section 7). A report about an accepted risk is still welcome if you show an impact that the threat model does not describe.

The development defaults in `deploy/compose/.env.example` are public on purpose, including the vault key. They are not a vulnerability. Never use them outside your own machine.
