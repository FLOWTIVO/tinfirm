# End-to-end tests

Playwright against the **real server** with seeded data. The plan is
[docs/reviews/2026-10-12-web-plan.md](../../docs/reviews/2026-10-12-web-plan.md) (§7 and §8). The harness:

- starts the real server from `server/`, on a database it recreates for each run;
- starts fakes of what the server talks to: LiteLLM, Jira Data Center, GitHub, Slack's Web API and an MCP server; a spec posts signed Slack events and webhook deliveries to the server itself;
- connects a **scripted runner host** that speaks runner protocol v2, so that a test drives each turn;
- seeds a company through the API;
- serves the web app.

Nothing here needs Docker.

```
web/e2e/
  harness/       the stack, the seed, the fakes, the scripted runner, the API and control clients
  setup/         the `seed` project (a dependency of every other project)
  fixtures.ts    `test` and `expect` for the specs, plus the axe, phone and screenshot helpers
  smoke/         the harness's own specs, which need no page of the app
  scenarios/     V1, V5, V6 and V7
  slices/        W0 to W7, a spec per slice
  login.spec.ts, secrets.spec.ts   the login page and the company's secrets (`@app`)
  .state/        git-ignored: logs, the seed's ids, the people's sessions
```

## What you need

| Tool | Notes |
|---|---|
| Node 24 | `npm ci` in `web/`. The Angular CLI refuses a Node 24 before 24.15.0, so `ng serve` and `ng build` need 24.15.0 or later. The harness scripts themselves run on Node 22.18 or later. |
| Java 25 | Runs the server, and builds its jar when there is none. The harness takes `E2E_JAVA_HOME`, else `JAVA_HOME`. |
| PostgreSQL 18 | Must be reachable as a superuser. The harness drops and creates the e2e database, and creates the roles `tinfirm_owner` and `tinfirm_app` when they are missing. It never changes the password of a role that already exists. `psql` must be on the `PATH`, or set `E2E_PSQL`. |
| Chromium | Playwright's own (`npx playwright install chromium`). When that build is missing, the config uses `$PLAYWRIGHT_BROWSERS_PATH/chromium`, or the path in `E2E_CHROMIUM_PATH`. |

## Run it

```bash
cd web
npm run e2e                      # start the stack, seed it, run every project, stop the stack
E2E_WEB=none npm run e2e         # without the web app: the specs tagged @app are left out
npx playwright test smoke        # one folder; the seed project runs first anyway
```

The slices W0 to W7 have their specs in `slices/`, tagged `@app` like the scenarios: they need the app. When the app does not build, `ng serve` serves nothing: the stack stops with "The web app does not build" and points at `e2e/.state/web.log`. `E2E_WEB=none` still runs the specs that need no page.

### Keep a stack up while you write specs

```bash
npm run e2e:up                   # the stack, seeded; stays up until Ctrl+C
npm run e2e                      # in another terminal: reuses the running stack (not under CI)
npm run e2e:seed                 # seed again; idempotent
npm run e2e:stack                # the stack without seeding
```

When the stack is up, <http://127.0.0.1:18090/> shows the harness's status page:

- the addresses;
- whether the runner host is connected;
- its latest runs;
- the seeded people.

Log in to the app as any seeded person with the password `e2e-password-123`.

### How the web app is served: `E2E_WEB`

| Value | The app | `baseURL` |
|---|---|---|
| `serve` (default) | `ng serve` on `E2E_WEB_PORT` (18082), with a proxy of `/api`, `/oauth2` and `/login/oauth2` to the server. The proxy file is generated into `.state/`. | `http://127.0.0.1:18082` |
| `built` | The production build, served by the real server itself, with its CSP and SPA fallback. The server reads `dist/tinfirm-web/browser` (or `E2E_WEB_DIST`) as `classpath:/static/` through Spring Boot's `PropertiesLauncher` and `-Dloader.path`. The harness runs `npm run build` first when there is no build. | `http://127.0.0.1:18080` |
| `none` | No app. Specs tagged `@app` are left out. | `http://127.0.0.1:18080` |

`TINFIRM_PUBLIC_URL` is the app's origin. That way, OAuth callbacks and the model relay's root go where the browser and the runner reach the server.

## Configuration

Every variable is optional.

| Variable | Default | Meaning |
|---|---|---|
| `E2E_SERVER_PORT`, `E2E_MANAGEMENT_PORT` | 18080, 18081 | The server's ports. |
| `E2E_WEB_PORT` | 18082 | `ng serve`'s port. |
| `E2E_CONTROL_PORT` | 18090 | The control API and the status page. |
| `E2E_LITELLM_PORT` | 18091 | The fake LiteLLM. |
| `E2E_STUBS_PORT` | 18092 | The fake Jira, GitHub, Slack and MCP server. |
| `E2E_DB_HOST`, `E2E_DB_PORT`, `E2E_DB_NAME` | 127.0.0.1, 54417, `tinfirm_e2e` | The database. |
| `E2E_DB_SUPERUSER`, `E2E_DB_SUPERUSER_PASSWORD` | `postgres`, `postgres` | The account that recreates the database. |
| `E2E_DB_OWNER_PASSWORD`, `E2E_DB_APP_PASSWORD` | the server tests' passwords | The roles' passwords: used when the harness creates a role, and when the server connects. |
| `E2E_DB_KEEP=1` | off | Keep the database, and with it the data of earlier runs. |
| `E2E_SERVER_JAR` | `server/target/tinfirm-server-*.jar` | The jar to run. |
| `E2E_SERVER_BUILD` | `missing` | `missing` builds the jar when there is none (`./mvnw -DskipTests package`). `always` rebuilds it. `never` does not build. |
| `E2E_SERVER_START_TIMEOUT_MS` | 600000 | How long the server may take to become ready. It needs about 30 s, and minutes on a loaded machine (the migrations of a new database). |
| `E2E_JAVA_HOME` | see above | The JDK. |
| `E2E_WEB`, `E2E_WEB_DIST` | `serve` | See above. |
| `E2E_STACK=external` | off | Playwright starts no stack. Start one yourself with `npm run e2e:stack`. |
| `E2E_WORKERS` | 3, or the CPUs when fewer | Playwright's workers. The specs share one server and run in parallel; see "Parallel workers" below. `1` runs them one at a time. |
| `E2E_VIDEO` | `off` | Videos of every test (`on`, `retain-on-failure`). V1, V5, V6 and V7 record videos anyway. |
| `E2E_SCREENSHOTS=1` | off | `shoot()` writes the slices' review screenshots to `docs/screenshots/web/<slice>/`. |
| `E2E_CHROMIUM_PATH` | | A Chromium to use instead of Playwright's. |

The server runs with the settings that a test run needs. These settings are for tests, never for production:

- the outbound guard allows http and private addresses, so the server can reach the stubs;
- the bundled LiteLLM is the fake;
- the login throttle is raised;
- the tool timeout is 10 s;
- the dispatcher runs every second;
- the `e2e` profile, under which the server trusts the run's own licence key pair (`TINFIRM_LICENCE_TEST_PUBLIC_KEY`, ADR-0077): the stack makes a new Ed25519 pair with `ee/tools/LicenceKeyTool.java` at each start, its private key in `.state/licence.pem`;
- Slack's Web API and file host are the stub's (`TINFIRM_CHAT_ENDPOINTS_SLACKAPI`, `…_SLACKFILES`), which the server takes only under the `e2e` and `test` profiles (`FixedEndpoints`);
- triggers' polls, schedules and write-back look for work every two seconds, and a change is read once it is three seconds old (a poll still runs once per system interval, 60 s, which the API cannot set).

The full list is in `startServer()` in `harness/stack.ts`.

## The seed

`harness/seed.ts` uses the API, logged in as the people who would make each thing. It is idempotent: it creates what is missing and keeps what is there. First, the root enters an Enterprise licence key (`PUT /api/admin/licence`), signed with the run's key pair, without seats or a cap of companies, so that every paid feature is in force; a spec that tests the licence's own pages answers the licence itself (`slices/licence.spec.ts`). It also puts back what a spec changed in the seeded people's accounts. It runs:

- as the `seed` project, before any other project;
- through `npm run e2e:seed`.

It writes the ids to `.state/seed.json`, and every person's session to `.state/auth/<persona>.json`.

| Who | Email | Role | Groups | Own accounts |
|---|---|---|---|---|
| root | `root@tinfirm.test` | instance admin, not a member | | |
| owner | `olivia@acme.test` | OWNER | Everyone | |
| admin | `adam@acme.test` | ADMIN | Everyone | |
| alice | `alice@acme.test` | MEMBER | Everyone, Analysts | GitHub |
| bob | `bob@acme.test` | MEMBER | Everyone | GitHub |
| carol | `carol@acme.test` | MEMBER | Everyone, Analysts | none (V5) |
| dave | `dave@acme.test` | MEMBER | Everyone | none (V6) |

The company is Acme, which the stack declares (`TINFIRM_COMPANIES_ACME_NAME`, ADR-0075), and the server creates at its start with the root, the bootstrap admin, as OWNER. The root creates the people, hands Acme to Olivia and leaves. The stack also declares Beta (`beta`), which nothing sets up: the model access page's state before a gateway. Adam then sets up:

- the systems Jira (`JIRA_DC`) and GitHub (`GITHUB`), on the stubs, with the repository `acme/shop`;
- the service account `svc-dev` and, on both systems, a credential named `svc-dev` ([ADR-0072](../../docs/adr/0072-system-credentials-lent-to-groups-and-service-accounts.md)), lent to Analysts read-only and to the service account `svc-dev` with writes;
- model access: the bundled gateway, which is the fake LiteLLM. It is tested, with a default budget of $50 per 30 days;
- the company runner host `e2e-runner`, which the scripted runner connects to as version 0.2.0;
- the agent Analyst (`@analyst`):
  - the tools of both systems (reads run, writes ask first);
  - `acme/shop` with `WRITE`;
  - shared with Everyone.

The smoke specs check what each person then sees. For example, Alice reads Jira "via Analysts, read-only". Carol acts on GitHub through the grant. Dave holds nothing.

## Writing specs

```ts
import { expect, expectNoAxeViolations, test } from '../fixtures';

test.use({ persona: 'alice' }); // `page` is logged in as Alice

test('the agent answers', { tag: ['@app', '@phone'] }, async ({ page, appPath, startConversation, runner }, testInfo) => {
  const { conversation, run } = await startConversation('alice', 'Summarise SHOP-1');
  await runner.finish(run.runId, { summary: 'SHOP-1 is about the harness.' });
  await page.goto(appPath(`/conversations/${conversation.id}`));
  await expect(page.getByText('SHOP-1 is about the harness.')).toBeVisible();
  await expectNoAxeViolations(page, testInfo);
});
```

| Fixture or helper | What it gives |
|---|---|
| `seed` | The ids from `.state/seed.json`: `companyId`, `people.alice.principalId`, `agent.id`, `systems.github.id`, and so on. |
| `persona` (option) | Logs the default `page` in as that person. |
| `ownerPage`, `adminPage`, `alicePage`, `bobPage`, `pageAs(name)` | Each a page in a browser context of its own, logged in, with the project's viewport. |
| `api(name)`, `apiPath(suffix)`, `appPath(suffix)` | The real API as that person, and the company's API and app paths. |
| `Api.login(urls.server, email, password)` | The API as someone who is no persona (the root, a person a test made). It starts from an empty cookie jar, so the test's persona stays logged in. |
| `startConversation(name, message)` | Starts a conversation with Analyst (`POST /conversations`). Returns it with the run the runner was assigned. |
| `runner` | The scripted runner host (below). |
| `stubs` | Every call that Jira and GitHub saw, with the identity of its token; after `clearCalls()`, only the calls that came after it (the log itself stays, for the tests on other workers). Also teaches them tokens, and picks who the next OAuth sign-in is (`setOAuthIdentity`). |
| `litellm` | The fake gateway's keys. It can set a principal's spend (V7) or a cost per request. |
| `expectNoAxeViolations(page, testInfo)` | Runs axe with the WCAG 2.2 A and AA rules. It fails on serious and critical findings, and attaches the full results. |
| `expectNoHorizontalOverflow(page)` | The phone check: `scrollWidth <= innerWidth`. |
| `shoot(page, testInfo, slice, name, { dark })` | A review screenshot, when `E2E_SCREENSHOTS` is set. |

Tags and projects:

- `@app` marks a spec that needs the app's pages.
- `@phone` adds a spec to the `phone` project: 390 × 844, touch, Chromium. The member flows (the plan §8) belong there.
- Every spec runs in `desktop`.

Rules:

- **Make your own data.** Start your own conversations; their keys and titles are unique.
- **Put back what you change.** A spec that changes a seeded person or the company puts it back, or makes people of its own. Examples of such changes: connecting an account, suspending `svc-dev`, setting a spend. The seed puts back the seeded people's own accounts, not the rest.
- **Runs end on their own.** After every test, a fixture ends the runs of that test's conversations that it left running, so they hold no slot of the runner host. A test's conversations are those it made through `startConversation`, `api(name)` or a page, and those of every run it awaited with `runner.awaitRun`. The runs of the tests on other workers go on. The same fixture resets the runner's default script when the test changed it.
- **Change what others read only under `@serial`.** See below.

### Parallel workers

The specs run on `E2E_WORKERS` workers against the one stack: 3 by default, or as many as the CPUs Playwright may use when there are fewer. A test runs beside others unless its title carries the tag `@serial`. Such a test runs alone: no other test starts until it ends, and it waits for the running ones to end first.

The default fits a machine of 4 CPUs, which the server, `ng serve`, PostgreSQL and the browsers share. A larger machine may take more workers.

With more workers than CPUs, pages missed their 10 s. That is why the default takes no more workers than the CPUs.

An automatic fixture holds the **company's lock** (`harness/company-lock.ts`, in the stack's process, through the control API's `/lock`) around every test. Every other test holds it shared; a `@serial` test holds it alone. Waiters are served in turn, so a waiting `@serial` test holds back the tests that come after it. Waiting is not the test's time.

Tag a test `@serial` when it changes what other tests read, even if it puts it back, or when it asserts on a log that every test writes to. For example, it:

- registers or removes a company runner host, or holds the seeded one busy for a check;
- stops the company's runs, pauses a seeded system, or suspends or holds a seeded person;
- changes the gateway, a budget, a seeded person's spend, or the company's settings;
- connects or disconnects a seeded person's account, or picks the stubs' next OAuth sign-in;
- changes a seeded group;
- asserts on every call the stubs saw (`stubs.calls()` with an exact list, or the order of identities), or on the gateway's newest request.

A test that only makes its own data, and reads the seed, runs beside the others. Its checks must hold when other tests add their own data meanwhile: their systems, hosts, conversations and audit events. Look for the seed's among them; don't expect the seed alone.

## The scripted runner host

`harness/scripted-runner.ts` is a protocol v2 runner host in Node. It ports the server's test `FakeRunner`. It:

- sends `hello` as runner 0.2.0, with every runtime;
- answers `host.config`;
- keeps `psl.update`;
- sends heartbeats;
- reconnects with its active runs;
- accepts every `run.assign`.

A run then stays **running** until the test drives it. The marker can also come from the person's message (the prompt's `# Request`), or from the default script (`runner.setDefaultScript('answer')`).

| Marker in the message | The run |
|---|---|
| none, `[e2e:hold]` | Waits for the test. |
| `[e2e:answer]` | Says something and finishes `SUCCEEDED` with a `COMPLETED` result. |
| `[e2e:ask]` | Asks a question with two options, and ends its turn. |
| `[e2e:approval]`, `[e2e:approval:<tool>]` | Calls a tool that asks first (by default a tracker's comment): an APPROVAL. Then ends its turn. |
| `[e2e:tool:<name>]` | Calls that tool on the server (for example `jira_get`), then finishes. |
| `[e2e:repository:<name>]`, `[e2e:network:<host>]` | Requests a repository or a network host, and ends its turn. |
| `[e2e:push]` | Finishes with every writable repository pushed, so a change request is opened on the stub. |
| `[e2e:blocked]` | Finishes with a `BLOCKED` result and an open question. |
| `[e2e:model]` | Sends one model request through the server's relay, then answers. |
| `[e2e:fail]`, `[e2e:fail:<CLASS>]` | Finishes `FAILED`, with `USAGE_LIMIT` by default. |

The `runner` fixture can do the same at any moment:

| Control | What the runner does |
|---|---|
| `awaitRun({ conversationId, state })` | Waits for a run. |
| `finish(runId, { summary, result, outcome, failureClass, pushed })` | Finishes the run. |
| `message(runId, text)` | Sends an `AGENT_MESSAGE`. |
| `event(runId, type, payload)` | Sends any run event. |
| `ask(runId, { question, options })` | Asks a person, and ends the turn. |
| `approval(runId, { tool })` | Asks for an approval, and ends the turn. |
| `tool(runId, { tool, arguments })` | Calls a tool on the server. |
| `requestAccess` | Requests access to a repository. |
| `requestNetwork` | Requests a network host. |
| `callModel(runId)` | Sends a model request through the relay, with the run's one-run token. |

Each request answers with the server's `run.request.ack`.

## The fakes

They all run in the stack's process, and the control API on port 18090 drives them. `harness/control-client.ts` is the typed client.

- **LiteLLM** (`harness/fake-litellm.ts`, a port of the server's `FakeLiteLlm`) serves:
  - the admin API that Tinfirm uses: keys, teams, models;
  - the model routes. They refuse a blocked key, a spent budget (`429 budget_exceeded`) and a model that the key may not use.
- **Jira and GitHub** (`harness/stubs.ts`) answer "who am I" for each known token, and serve issues, search, comments, transitions, labels, closing and pull requests. They record which identity each call acted as. They also serve each system's OAuth app:
  - the authorization endpoint signs in at once as the identity the test picked, or refuses (`deny`);
  - the token endpoint issues a token that the stub then knows.

  Every seeded person has a token on each system: `gh-token-<name>`, `jira-token-<name>`.

- **Slack** (`harness/slack-stub.ts`, on the stubs' port under `/slack/api`, which the server reaches as Slack's Web API through `TINFIRM_CHAT_ENDPOINTS_SLACKAPI`): a bot token `xoxb-e2e-<TAG>-…` is the bot of the workspace `T<TAG>`; it answers `auth.test`, `users.info`, `users.conversations` and `conversations.open`, and records the bot's posts, which the `slack` fixture reads. `deliverSlackEvent` posts a signed Events API delivery from a spec (W6).
- **A webhook's sender** (`harness/webhook-sender.ts`): posts a delivery to the server's `/api/hooks/{systemId}`, signed as Standard Webhooks sign, or with the secret in a header (W6).
- **Jira's issues** can be changed by a spec, for triggers to poll (W5): `stubs.putJiraIssue({ key, assignee, … })` makes or changes an issue now, `stubs.commentJiraIssue(key, author, body)` comments on it, `stubs.jiraIssue(key)` reads it with its comments (Tinfirm's write-back among them). Search filters by the JQL the server sends (project, `updated >=`, `assignee = currentUser()`, key, text); SHOP-1 to SHOP-3 are there from the start, last changed on 1 October.

- **An MCP server** (`harness/mcp-stub.ts`, on the stubs' port under `/mcp`; any path below it is an endpoint of its own) needs no token and offers no OAuth. It answers `initialize` and `tools/list` with three tools: `search_notes` (its hints say it only reads), `create_note` (its hints say it writes) and `summarise_note` (no hints), and `tools/call` with a text that names the tool. W2a's spec confirms their effects.

## In CI

The `e2e` job of `.github/workflows/ci.yml` runs this suite on every pull request and push to main, beside the `web` job's lint, unit tests and build. On GitHub-hosted `ubuntu-latest`, or on the runner label that the repository variable `CI_RUNNER` names (a pull request from a fork always on `ubuntu-latest`), it:

- picks free ports for the database and the stack (`E2E_DB_PORT`, `E2E_SERVER_PORT` and the others of "Configuration"), because jobs on one machine may share its Docker daemon and its host ports: two e2e jobs side by side would otherwise fight over them;
- starts PostgreSQL 18 (`postgres:18`) on the runner's Docker daemon, and gives the harness that image's `psql` through `E2E_PSQL`, since a runner need not have one;
- sets up Java 25 and Node 24, and builds the server jar (`./mvnw -DskipTests package`);
- starts the stack on the runner (`e2e/harness/stack.ts` with `E2E_WEB=built` and `E2E_SERVER_BUILD=never`) and seeds it there (`npm run e2e:seed`, which signs the run's licence key with the JDK);
- runs `npx playwright test` with `CI=1` and `E2E_STACK=external` in Microsoft's Playwright image (`mcr.microsoft.com/playwright`, the tag of the lockfile's version, pinned by digest), whose Chromium has the system libraries a runner may lack, on the host's network;
- keeps the HTML report, the traces and the stack's logs as the artifact `e2e-report` when it fails.

When `@playwright/test` changes version, change the image's tag and digest with it.

It takes about 20 minutes on 4 CPUs, and has 45, as the `runner` job does.

Under `CI`, Playwright:

- never reuses a running stack;
- retries a failed test once;
- forbids `test.only`;
- writes its HTML report.

The first run builds the server jar. A job that has built it already can pass it in with `E2E_SERVER_JAR` and `E2E_SERVER_BUILD=never`.

## When something fails

| Problem | Where to look |
|---|---|
| Anything | The logs are in `e2e/.state/`: `server.log` (the server's JSON logs) and `web.log` (`ng serve`). Playwright prints the stack's own lines as `[WebServer]`. |
| "Port … is in use" | Another stack is running, or another program uses the port. Move the harness with the `E2E_*_PORT` variables. |
| The seed fails | It names the call and the server's problem. `npm run e2e:seed` against a running stack shows the same. |
| A failed test | Its trace is kept (`npx playwright show-trace test-results/…/trace.zip`). A run that the test left running is ended by the fixture. |
