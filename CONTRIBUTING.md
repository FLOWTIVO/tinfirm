# Contributing

This guide tells you how to set up the repository, run it, check your change and open a pull request.
The binding rules are in [docs/CONVENTIONS.md](docs/CONVENTIONS.md). If this guide and CONVENTIONS disagree, CONVENTIONS wins. Report the difference.
Everyone who takes part in the project follows the [Code of Conduct](CODE_OF_CONDUCT.md).

`main` runs the redesign of 2026-10-12 ([docs/PLATFORM.md](docs/PLATFORM.md), [ADR-0058](docs/adr/0058-authority-from-the-audience.md), [ADR-0059](docs/adr/0059-litellm-budgets-and-the-model-proxy.md)). Nothing is released yet.

## 1. Prerequisites

| Tool | Version | Used for |
|---|---|---|
| JDK | 25 | Builds and tests of the server and the model port. Each has its own Maven wrapper (`server/mvnw`, `model-api/mvnw`), which downloads Maven. |
| Node.js | 24 LTS, with npm 11 or later (`engines` and `packageManager` in `web/package.json`) | Web build, tests, dev server; the runner host's end-to-end tests start a fake agent with it |
| Rust | 1.89 or later (`rust-version` in `runner/Cargo.toml`), with clippy and rustfmt | The runner host: `make runner-build`, `make runner-test` |
| Docker | Docker Engine or Docker Desktop, with Compose v2 | Compose stack, Testcontainers in the server tests, the runner host's tests in real containers, image builds, lint containers |
| Helm | 4 | `make helm-lint` |
| make | any | Entry points such as `make up`. `make help` lists them. |
| psql | 18; optional, except for the e2e tests | Inspect the database. The Playwright harness runs it to create its database ([web/e2e/README.md](web/e2e/README.md)). |
| Python | optional, 3.11 or later, with `venv` | The docs site: `make docs-serve`, `make docs-build` |

Server tests start PostgreSQL 18 with Testcontainers, so they need Docker. Without Docker, they run against an external PostgreSQL that `TINFIRM_TEST_JDBC_URL` names ([docs/development.md §6.1](docs/development.md#61-server)).

The Playwright end-to-end tests need a browser once (`cd web && npx playwright install chromium`) and a PostgreSQL 18 they can reach as a superuser ([web/e2e/README.md](web/e2e/README.md)). They need no Docker.

## 2. Run the full stack

Follow the quick start in [README.md](README.md#quick-start-docker-compose):

```sh
cp deploy/compose/.env.example deploy/compose/.env
make up          # or: make up-oidc, to add Keycloak
```

The stack serves the API and the SPA on <http://localhost:8080>. `make logs` follows the logs, and `make down` stops the stack, Keycloak included, and keeps the volumes.

## 3. Run the server locally

Run the server from your IDE or shell, and use the Compose PostgreSQL.

1. Start only the database:

   ```sh
   make dev-db
   ```

   PostgreSQL then listens on `127.0.0.1:5432`. If the Compose `server` container runs, stop it first, because it also uses port 8080: `docker compose -f deploy/compose/compose.yaml stop server`.

2. Set the environment. The values come from `deploy/compose/.env`; the comments give the defaults from `.env.example`:

   ```sh
   export SPRING_DATASOURCE_USERNAME=tinfirm_app
   export SPRING_DATASOURCE_PASSWORD='<TINFIRM_APP_PASSWORD>'          # dev-only-app
   export SPRING_FLYWAY_USER=tinfirm_owner
   export SPRING_FLYWAY_PASSWORD='<TINFIRM_OWNER_PASSWORD>'            # dev-only-owner
   export TINFIRM_VAULT_KEYS='<TINFIRM_VAULT_KEYS>'                    # dev1:...
   export TINFIRM_VAULT_ACTIVE_KEY='<TINFIRM_VAULT_ACTIVE_KEY>'        # dev1
   export TINFIRM_BOOTSTRAP_ADMIN_EMAIL='<TINFIRM_BOOTSTRAP_ADMIN_EMAIL>'        # admin@example.com
   export TINFIRM_BOOTSTRAP_ADMIN_PASSWORD='<TINFIRM_BOOTSTRAP_ADMIN_PASSWORD>'  # dev-only-admin-password
   export TINFIRM_COMPANIES_MAIN_NAME='<TINFIRM_COMPANY_NAME>'                  # My Company (ADR-0075)
   ```

   `SPRING_DATASOURCE_URL` defaults to `jdbc:postgresql://localhost:5432/tinfirm`, which matches `make dev-db`. Use the same vault key as the Compose stack. Otherwise the server cannot decrypt secrets that the Compose server stored.

3. Start the server:

   ```sh
   cd server && ./mvnw spring-boot:run
   ```

   Flyway migrates the schema as `tinfirm_owner`. The application then connects as `tinfirm_app`.

[docs/development.md](docs/development.md) explains every variable, OIDC in development, and the database roles.

## 4. Run the web app locally

```sh
cd web
npm ci
npm start
```

`npm start` serves the SPA on <http://localhost:4200>. The dev server forwards `/api`, `/oauth2` and `/login/oauth2` to `http://localhost:8080` through `web/proxy.conf.json`. Start the server first (section 3, or `make up`).

`npm start`, `npm run build` and `npm test` first regenerate the API client from `api/openapi.yaml` (script `generate:api`).

## 5. Checks before a pull request

Run the checks for every area you changed. Put the exact result in the pull request.

| Area | Command | Notes |
|---|---|---|
| Server | `make server-test` | `./mvnw -B -ntp verify`. Needs Docker, or an external PostgreSQL in `TINFIRM_TEST_JDBC_URL`. Includes Modulith verification, the tenant isolation test, RLS tests as `tinfirm_app` and the dependency licence check ([Dependency licences](#dependency-licences)). |
| Model port | `make model-api-test` | `./mvnw -B -ntp verify` in `model-api/`, against a fake LiteLLM, with the tests of `libs/litellm` and `libs/net`. Needs no Docker. The dependency licence check runs in it too. |
| Runner host | `make runner-test` | `cargo test --workspace`, `cargo clippy --workspace --all-targets -- -D warnings` and `cargo fmt --check` in `runner/`. The tests in real containers run when Docker is available ([runner/README.md](runner/README.md)). |
| Runner licences | `make runner-licences-check` | The crates' licences and `runner/THIRD-PARTY-LICENSES.txt`, with cargo-about in Docker, as CI's `runner` job checks them. `make runner-licences` writes the file again after a change to `runner/Cargo.lock` ([Dependency licences](#dependency-licences)). |
| Web | `make web-test` | `npm ci`, `npm run licences` (the production packages' licences), `npm run lint`, `npm test` (Vitest) and `npm run build` |
| Web e2e | `cd web && npm run e2e` | Playwright tests in `web/e2e/`, against a stack the command starts and seeds itself ([web/e2e/README.md](web/e2e/README.md)). CI runs them in its `e2e` job. |
| Helm | `make helm-lint` | The chart's `LICENSE` against the root one, `helm lint --strict` and `helm template`, with the default values and each file in `deploy/helm/tinfirm/ci/` |
| Deploy files | `make deploy-lint` | Compose config with and without the `oidc` profile and with the Entra ID and second-company examples; hadolint on the five Dockerfiles (the server's, `deploy/release/runner-musl.Dockerfile`, `runner/ci.Dockerfile`, `model-api/Dockerfile`, `docs-site/Dockerfile`); shellcheck on the deploy scripts; actionlint on the workflows |
| Image | `make smoke-image` | Multi-stage build of web and server (`make build-image`, which it depends on), then the image started against PostgreSQL until `/api/system/info` answers (`deploy/compose/smoke.sh`), which catches what the unit tests cannot: a class missing from the jar, a bean that fails, a migration that fails |
| Docs | Check that every relative link resolves | Follow the writing rules in section 7 |
| Docs site | `make docs-build` | Strict Zensical build of `docs-site/`: a link to a page or an anchor that does not exist fails it. CI runs it as `docker build docs-site`, and its `publish-docs` job publishes the site's image once a change to `docs-site/` is merged ([docs-site/README.md](docs-site/README.md)) |

`make ci` runs the checks of `ci.yml` except the security scans, the e2e tests, the check of the LiteLLM pin, the build of the model port's image (`make model-api-image`) and the publishing.

CI (`.github/workflows/ci.yml`) runs on every pull request to `main` or `hotfix/**` and on every push to `main`. Its jobs:

| Job | What it does |
|---|---|
| `server` | `./mvnw -B -ntp verify`, with Testcontainers' PostgreSQL 18 and the dependency licence check |
| `model-api` | The model port's `./mvnw -B -ntp verify`, with the dependency licence check |
| `web` | `npm ci`, `npm run licences`, `npm run lint`, `npm test`, `npm run build` |
| `e2e` | The Playwright tests of `web/e2e/`, with PostgreSQL 18 and the server built from the commit, which serves the app's production build ([web/e2e/README.md](web/e2e/README.md#in-ci)) |
| `docs` | The docs site's strict build (Zensical, in `docs-site/Dockerfile`) and its image's runtime contract |
| `helm` | The chart's `LICENSE`, Helm lint and template, the Compose configs, the check that the chart and the Compose file pin the same LiteLLM image, hadolint, shellcheck, actionlint and the action pins (`.github/check-pins.sh`) |
| `image` | Builds the server image (no push), checks its runtime contract and its `/licenses/`, and starts it against PostgreSQL (`deploy/compose/smoke.sh`); builds the model port's image and checks its own |
| `runner` | `cargo fmt`, clippy, the tests and the crates' licences (cargo-about), each a stage of `runner/ci.Dockerfile`; not the tests in real containers |
| `security` | `reusable-security.yml`: SAST (semgrep), secrets (gitleaks) and dependency CVEs (trivy) |
| `publish` | After a push to `main`: the server image `ghcr.io/flowtivo/tinfirm-server`, tagged `sha-<commit>`, and `main` while that commit is still the tip of `main` |
| `publish-services` | After a push to `main`: the model port's image `ghcr.io/flowtivo/tinfirm-model-api`, tagged the same way |
| `publish-docs` | After a push to `main` that changed `docs-site/`: the docs site's image `ghcr.io/flowtivo/tinfirm-docs`, tagged `sha-<commit>` and then `main` |
| `rolling` | After `publish`: the prerelease `rolling` with the runner host binaries and the Compose files (`runner-artifacts.yml`) |
| `main-complete` | After `publish`, `publish-services` and `rolling`: moves the server image's tag `ghcr.io/flowtivo/tinfirm-server:main-complete` to `sha-<commit>`, while that commit is still the tip of `main`. A deployment repository reads it on its own schedule to keep an environment on `main`; CI holds no key to it ([ADR-0107](docs/adr/0107-a-deployment-repository-follows-the-registry.md)) |

The `DCO` workflow (`.github/workflows/dco.yml`) runs beside CI on every pull request to `main` or `hotfix/**`: it fails when a commit lacks its author's sign-off ([section 6](#sign-off-and-licence)).

The publishing jobs never run under the release gate: `release.yml`, run by hand, calls `ci.yml` as its gate and then publishes a release's own images and assets. It signs the images and the chart, and attests the images' SBOMs, with FLOWTIVO's cosign key, from the protected `release` environment, and stops before it publishes anything when the key is missing ([ADR-0081](docs/adr/0081-signed-release-images.md), `deploy/release/sign-images.sh`). A release changes no environment: a version runs where a deployment repository names it, an edit made there. Dependabot updates the pinned GitHub Actions, the base images and the Compose file's images weekly. The server and web owners update Maven and npm dependencies.

PR Agent (`.github/workflows/pr_agent.yml`, settings in `.pr_agent.toml`) reviews every pull request with a model. When a pull request opens, it posts one "PR Reviewer Guide" comment (`/review`) and inline, committable suggestions as review threads (`/improve`). A later push gets an incremental review of the new commits only. A collaborator runs a tool again with a comment on the pull request: `/review`, `/improve` or `/describe`. The reviewer reads `docs/CONVENTIONS.md` and this guide from `main` as its context. The workflow needs the `OPENAI_KEY` secret, which a pull request from a fork does not receive: such a pull request gets no review on open or push, and a collaborator runs one with a `/review` comment.

### Dependency licences

CI refuses a dependency that ships under a licence outside the policy of [ADR-0082](docs/adr/0082-licence-notices-and-dependency-licences.md): the Maven build of the server and of the model port (license-maven-plugin), `npm run licences` for the web app's production packages, and the `runner` job's `licences` stage for the runner host's crates. The failing check names the dependency and its licence.

- **A licence outside the policy.** Choose another dependency, or ask the owner. A change of the policy changes ADR-0082 and its four lists together: `server/pom.xml`, `model-api/pom.xml`, `web/scripts/licences.mjs` and `runner/about.toml`.
- **An accepted licence under a name the check does not know** (a pom's free text). Read the licence, then add the name to the `licenseMerges` of both poms.
- **Metadata that names the wrong licence.** Correct it for that one version, with the reason: `server/src/license/override-THIRD-PARTY.properties`, `CLARIFIED` in `web/scripts/licences.mjs`, or a `clarify` entry in `runner/about.toml`.
- **A change to `runner/Cargo.lock`.** Run `make runner-licences` and commit the new `runner/THIRD-PARTY-LICENSES.txt` with it: every runner host archive carries that file, and the `runner` job fails when it is not what `Cargo.lock` gives.
- **A change to the root `LICENSE`.** Copy it to `deploy/helm/tinfirm/LICENSE`: the `helm` job checks that they are identical.
- **A third-party file copied into the repository, a new artefact, or a change to where an artefact keeps its notices.** Update [NOTICE](NOTICE), which names each of them.

## 6. Commits and pull requests

### Commits

- Use the form `<type>(<scope>): <summary>`.
  - `type`: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci` or `chore`.
  - `scope`: the area, for example `server`, `web`, `deploy`, `docs` or `api`.
  - `summary`: imperative mood, at most 72 characters, no period at the end. Example: `fix(server): return 404 for non-members on secret paths`.
- Use the body to explain why. Reference the issue if one exists.
- Make one logical change per commit.
- Never commit real secrets. Development defaults are allowed only in `.env.example` and in test code, and must be clearly marked (CONVENTIONS §13).
- Never edit a released Flyway migration. Add a new one (CONVENTIONS §7).
- Sign off every commit: `git commit -s`. [Sign-off and licence](#sign-off-and-licence) says why.
- A person authors, commits and signs off every commit, never an AI agent ([AI agents](#ai-agents)).

### Sign-off and licence

Every commit carries a `Signed-off-by:` line: the [Developer Certificate of Origin](https://developercertificate.org/) (DCO), as [ADR-0077](docs/adr/0077-licence-editions-and-seats.md) decision 2 decides. By signing off, you certify that you wrote the change, or otherwise have the right to submit it under the repository's licence, and that you know the contribution and your sign-off are public and kept for good. Read the certificate's full text at the link before your first sign-off.

- **Sign off.** `git commit -s` adds `Signed-off-by: Your Name <you@example.com>` from your `user.name` and `user.email`. The line must name the commit's author: the same name and email as `git log --format='%an <%ae>'` shows.
- **Every contribution is licensed under MIT.** Whatever you contribute, to the core or to `ee/`, you license to FLOWTIVO and to everyone under the MIT licence. The commercial licence of `ee/` binds its users, not your contribution.
- **Paid code goes under `ee/`.** The server code that only a paid feature runs lives under `ee/`, in the package of the module it belongs to, never in the core ([ADR-0077](docs/adr/0077-licence-editions-and-seats.md) decision 1, CONVENTIONS §15.24). So does the runner host's, under `ee/runner/`, which the manifests in `runner/` name ([ADR-0100](docs/adr/0100-elastic-runners-in-pro.md)). The planned Enterprise features go there too when they are built: the audit streamed to a SIEM, high availability and the air-gapped bundle. The core keeps only a limit that every edition applies, a primitive that every edition uses, and a fallback that answers Community, `403 EDITION_REQUIRED`, without `ee/`. `ModularityTest.onlyTheCoresKnownGatesNameAFeature` fails the build when a class of the core outside `shared.licence` and the test's list of known gates names `Feature`, until the code moves under `ee/` or the list is extended on purpose.
- **You keep your copyright.** No assignment is asked, and there is no CLA.
- **Only a person signs off.** The certificate is a person's own statement, so an AI agent can never make it: neither for itself nor in your name. A commit whose author, committer or `Signed-off-by:` is an AI agent is refused ([AI agents](#ai-agents)).
- **The check.** CI's `DCO` workflow (`.github/workflows/dco.yml`) fails a pull request with any commit whose message lacks a `Signed-off-by:` line for its author, and with any commit whose author, committer or sign-off is an AI agent's identity. It skips a merge whose result is exactly Git's automatic merge, such as GitHub's **Update branch**; a merge in which you resolved a conflict needs your sign-off (`git merge --signoff`, or `git commit -s` after resolving). Dependabot's commits pass with the sign-off GitHub gives them, `dependabot[bot] <support@github.com>`.

To fix a missing sign-off:

| Where the commit is | Command |
|---|---|
| The last commit | `git commit --amend --no-edit -s`, then `git push --force-with-lease` |
| Several commits on your branch | `git rebase --signoff origin/main`, then `git push --force-with-lease` |
| A sign-off with the wrong name or email | Set `user.name` and `user.email` to the author's, then the same commands. `git commit --amend --reset-author --no-edit -s` also makes you the author of the last commit. |

Rewrite only your own branch. On someone else's branch, ask its author to sign off.

### AI agents

An AI agent (Claude Code, Codex, Copilot, Cursor and the like) may write code, docs and commits for the person it works for. That person is the contributor, and answers for every line (CONVENTIONS §13):

- **The person is the author and the committer.** Before an agent commits, set the clone's `user.name` and `user.email` to yours. Never commit as the agent (`Claude <noreply@anthropic.com>`, `Copilot`, `Cursor Agent`, a `[bot]` account of an agent).
- **The agent never signs off.** It adds no `Signed-off-by:` line, not for itself and not for you. Read its commits, then sign them off yourself: `git rebase --signoff origin/main` on the branch, and `git push --force-with-lease`. Until you do, the `DCO` check stays red.
- **The agent is credited, not certified.** A `Co-Authored-By:` trailer may name the agent; that is the only place its name goes.
- **The agent signs nothing else either.** No commit or tag signature with the agent's own key on your commits: turn its commit signing off in the clone (`git config commit.gpgsign false`) or sign with your own key.
- **An agent pushes only to a branch of its own,** and only when you ask it to. It never pushes to `main` or `hotfix/**`, never merges a pull request, and publishes nothing else: no release, tag, image or package.

The `DCO` workflow refuses a commit whose author, committer or sign-off is one of the agents' identities it knows (`AGENT_IDENTITIES` in `.github/workflows/dco.yml`): add an agent's identity there when you start to use it. `CLAUDE.md` at the root repeats these rules for Claude Code, which reads it in every session.

### Pull requests

- Keep a pull request small and about one topic.
- Stay inside the paths your area owns (CONVENTIONS §2). A change in another area needs a reviewer from that area.
- The description states, under the headings of the pull request template (`.github/pull_request_template.md`):
  - what changed and why;
  - how you tested it, with the check results from section 5;
  - screenshots for UI changes;
  - any change to the API contract, the database schema, configuration variables or a trust boundary.
- If you change a trust boundary, an asset or a security control, update [docs/threat-model.md](docs/threat-model.md) in the same pull request.
- If you make a significant design decision, add an ADR in [docs/adr/](docs/adr/README.md).
- Read both places where PR Agent writes: the "PR Reviewer Guide" issue comment and the inline review threads. No single view shows both.
- Verify each PR Agent finding before you act on it. The findings are model-written and sometimes wrong about this code. Reply with what changed, or with the reason the finding does not hold, then resolve the thread. Nothing resolves itself: a committed suggestion only marks its comment outdated.

## 7. Writing rules for documentation

- Write clear technical English for senior engineers: short sentences, active voice, one term for one concept.
- Use tables for tabular content.
- Mark behaviour that does not exist yet as "planned", with the decision or package that plans it if known.
- Use the product name only where it is necessary (CONVENTIONS §3).

## 8. The contract: conventions and API

| File | Contents | Who edits it |
|---|---|---|
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | Engineering contract: layout, runtime configuration, auth, authorization matrix, database, audit, vault, code conventions, the rules of the domain | Anyone, agents included, in their own pull request; its code owner reviews every change (`.github/CODEOWNERS`) |
| [api/openapi.yaml](api/openapi.yaml) | HTTP contract (OpenAPI 3.0.3) | The maintainer. Others may change it in their own pull request, with the code that needs the change; a human reviews the contract change (CONVENTIONS §13). |
| [docs/PLATFORM.md](docs/PLATFORM.md) | The platform design | Anyone, agents included, in their own pull request; its code owner reviews every change (`.github/CODEOWNERS`) |
| [protocol/](protocol/) | The runner WebSocket protocol, versions 1 and 2 (JSON Schema), which the server's `RunnerMessages` and the runner's `tinfirm-protocol` crate implement | Anyone, agents included, in their own pull request; their code owner reviews every change (`.github/CODEOWNERS`) |
| [docs/adr/](docs/adr/README.md) | Architecture decision records | Any contributor, reviewed by the maintainer |

To change the contract:

1. Describe the change and the reason in the pull request's description; for a larger change, open an issue or a draft pull request first.
2. Change `CONVENTIONS.md`, `PLATFORM.md` or a schema under `protocol/` in your own pull request: their code owner reviews it before it merges (`.github/CODEOWNERS`). A change to `api/openapi.yaml` may go in your own pull request too, said in its description, where a human reviews it.
3. Change the code in the same pull request, or in the next one. Code and contract must not drift apart (CONVENTIONS, introduction).

After an API change:

- The server build regenerates the controller interfaces (`openapi-generator-maven-plugin`, `spring` generator, `interfaceOnly`). The build fails until the controllers match.
- The web app regenerates the client with `npm run generate:api`. Hand-written code never duplicates DTO types.
- The tenant isolation test reads all company-scoped paths from `api/openapi.yaml`, so it covers a new path automatically.
- If a new operation needs a role check, the maintainer also updates the authorization matrix (CONVENTIONS §6).

## 9. Security issues

Do not open a public issue for a vulnerability. Follow [SECURITY.md](SECURITY.md). The threat model is in [docs/threat-model.md](docs/threat-model.md).
