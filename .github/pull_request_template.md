<!--
CONTRIBUTING.md, section 6, says what a pull request states. Keep it small and about one topic.
Write "None" under a heading that does not apply; delete the screenshots section if nothing visible changed.
-->

## What and why

<!-- What changed, and the reason. Link the issue, if there is one. -->

## How it was tested

<!-- The checks of CONTRIBUTING.md, section 5, for every area you changed, with their exact result. -->

## Contract, schema, configuration and trust boundaries

<!--
Any change to the HTTP contract (api/openapi.yaml), the runner protocol (protocol/), the database schema
(a new Flyway migration), a configuration variable, docs/CONVENTIONS.md, docs/PLATFORM.md, or a trust boundary.
-->

## Screenshots

<!-- For a change to the web app: desktop and phone, and dark mode where the change shows in it. -->

## Checklist

- [ ] Every commit is signed off (`git commit -s`) by its author: the Developer Certificate of Origin (CONTRIBUTING.md, section 6).
- [ ] Commit messages follow `<type>(<scope>): <summary>`, one logical change per commit.
- [ ] Tests cover the change, and the checks of CONTRIBUTING.md, section 5, pass for every area changed.
- [ ] A significant design decision has an ADR in `docs/adr/`; a changed decision has a new ADR that supersedes or amends the old one.
- [ ] A change to a trust boundary, an asset or a security control updates `docs/threat-model.md`.
- [ ] The documentation follows the change: `docs-site/` for what users, admins and operators see, `docs/` for the engineering notes.
