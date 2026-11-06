/**
 * The e2e fixtures: import `test` and `expect` from here, not from `@playwright/test`.
 *
 * - `seed`: what the seed made (ids of the company, people, groups, systems, the agent, the runner host).
 * - `persona` (an option, `test.use({ persona: 'alice' })`): logs the default `page` in as that person.
 * - `ownerPage`, `adminPage`, `alicePage`, `bobPage`, and `pageAs(name)` for anyone seeded: a page in a browser
 *   context of its own, logged in as that person, with the project's viewport (V1 drives two people at once).
 * - `api(name)`: the real API as that person (their session), and `apiPath(suffix)` for the company's paths.
 * - `runner`: the scripted runner host's controls (finish the current run, ask, request an approval, call a tool);
 *   `stubs`: the calls Jira and GitHub saw; `litellm`: the fake gateway's keys and spend; `slack`: what the bot
 *   posted to the Slack stub.
 * - `startConversation(name, message)`: starts a conversation with the seeded agent through the API and answers it
 *   with the run the runner was assigned.
 *
 * The helpers `expectNoAxeViolations`, `expectNoHorizontalOverflow` and `shoot` are exported beside them.
 *
 * The specs run on parallel workers. Around every test, an automatic fixture holds the company's lock
 * (harness/company-lock.ts): shared, or alone for a test tagged `@serial`, one that changes what other tests read.
 * After every test, another ends the runs of the conversations that test made, and no other test's.
 */
/* eslint-disable no-empty-pattern -- Playwright's fixtures take their dependencies as a destructured object */
import AxeBuilder from '@axe-core/playwright';
import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type BrowserContextOptions,
  test as base,
  expect,
  type Page,
  request,
  type TestInfo,
} from '@playwright/test';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { Api, type Body, type CallOptions } from './harness/api.ts';
import { type PersonaName, repoDir, urls } from './harness/config.ts';
import {
  LiteLlmControl,
  LockControl,
  RunnerControl,
  type RunnerRun,
  SlackControl,
  StubsControl,
} from './harness/control-client.ts';
import type { SeedState } from './harness/seed.ts';
import { seedFile, storageStateFile } from './harness/seed.ts';

export { expect };
export type { PersonaName, RunnerRun, SeedState };

function readSeed(): SeedState {
  if (!existsSync(seedFile)) {
    throw new Error(
      `No seed at ${seedFile}: run the "seed" project first (it is a dependency of every project), or npm run e2e:seed`,
    );
  }
  return JSON.parse(readFileSync(seedFile, 'utf8')) as SeedState;
}

export interface Fixtures {
  /** The person the default `page` is logged in as; null (the default) leaves it anonymous. */
  persona: PersonaName | null;
  seed: SeedState;
  /** A page logged in as the person, in a context of its own; closed after the test. */
  pageAs: (name: PersonaName) => Promise<Page>;
  ownerPage: Page;
  adminPage: Page;
  alicePage: Page;
  bobPage: Page;
  /** The API as the person, with their session. */
  api: (name: PersonaName) => Promise<Api>;
  /** `/api/companies/{companyId}{suffix}`. */
  apiPath: (suffix: string) => string;
  /** `/c/{companyId}{suffix}`: a page of the company in the app. */
  appPath: (suffix: string) => string;
  runner: RunnerControl;
  /** Automatic: holds the company's lock while the test runs, alone for a test tagged `@serial`. */
  companyLock: void;
  /** The conversations this test made: through `startConversation`, `api`, a page, or a run the runner was awaited for. */
  ownConversations: Set<string>;
  /** Automatic: ends the runs of this test's conversations that it left running, and resets the default script. */
  endHeldRuns: void;
  stubs: StubsControl;
  litellm: LiteLlmControl;
  slack: SlackControl;
  /** Starts a conversation with the seeded agent as the person; answers it and the run the runner was assigned. */
  startConversation: (
    name: PersonaName,
    message: string,
    extra?: Record<string, unknown>,
  ) => Promise<{ conversation: Body; run: RunnerRun }>;
}

interface WorkerFixtures {
  seedState: SeedState;
}

export const test = base.extend<Fixtures, WorkerFixtures>({
  seedState: [async ({}, use) => use(readSeed()), { scope: 'worker' }],
  seed: async ({ seedState }, use) => use(seedState),
  persona: [null, { option: true }],
  storageState: async ({ persona, storageState }, use) => {
    await use(persona ? storageStateFile(persona) : storageState);
  },

  pageAs: async (
    {
      browser,
      viewport,
      isMobile,
      hasTouch,
      deviceScaleFactor,
      userAgent,
      colorScheme,
      baseURL,
      ownConversations,
    },
    use,
  ) => {
    const contexts: Awaited<ReturnType<Browser['newContext']>>[] = [];
    const options: BrowserContextOptions = {
      viewport,
      isMobile,
      hasTouch,
      deviceScaleFactor,
      userAgent,
      colorScheme,
      baseURL,
    };
    await use(async (name) => {
      const context = await browser.newContext({
        ...options,
        storageState: storageStateFile(name),
      });
      contexts.push(context);
      recordConversations(context, ownConversations);
      return context.newPage();
    });
    await Promise.all(contexts.map((context) => context.close()));
  },
  ownerPage: async ({ pageAs }, use) => use(await pageAs('owner')),
  adminPage: async ({ pageAs }, use) => use(await pageAs('admin')),
  alicePage: async ({ pageAs }, use) => use(await pageAs('alice')),
  bobPage: async ({ pageAs }, use) => use(await pageAs('bob')),

  context: async ({ context, ownConversations }, use) => {
    recordConversations(context, ownConversations);
    await use(context);
  },

  api: async ({ ownConversations }, use) => {
    const apis = new Map<PersonaName, Api>();
    await use(async (name) => {
      let api = apis.get(name);
      if (!api) {
        const context = await request.newContext({
          baseURL: urls.server,
          storageState: storageStateFile(name),
        });
        api = new RecordingApi(context, urls.server, ownConversations);
        apis.set(name, api);
      }
      return api;
    });
    await Promise.all([...apis.values()].map((api) => api.dispose()));
  },
  apiPath: async ({ seed }, use) => use((suffix) => `/api/companies/${seed.companyId}${suffix}`),
  appPath: async ({ seed }, use) => use((suffix) => `/c/${seed.companyId}${suffix}`),

  ownConversations: async ({}, use) => use(new Set<string>()),
  runner: async ({ ownConversations }, use) =>
    use(new RecordingRunnerControl(urls.control, ownConversations)),
  companyLock: [
    async ({}, use, testInfo) => {
      const lock = new LockControl(urls.control);
      const owner = `${process.pid} ${testInfo.testId} ${testInfo.retry}`;
      await lock.acquire(owner, testInfo.tags.includes('@serial') ? 'exclusive' : 'shared');
      try {
        await use();
      } finally {
        await lock.release(owner);
      }
    },
    // Waiting for the lock is not the test's time: a test alone may hold it for minutes.
    { auto: true, timeout: 20 * 60_000 },
  ],
  // After every test: a run of its own conversations that the test left holding (a failed test) ends, so that it
  // holds no slot of the runner host; the runs of the tests on other workers go on. The default script again, when
  // the test changed it.
  endHeldRuns: [
    async ({ companyLock: _held, runner, ownConversations }, use) => {
      await use();
      if (runner instanceof RecordingRunnerControl && runner.changedScript) {
        await runner.setDefaultScript('hold');
      }
      for (const run of await runner.runs({ state: 'running' })) {
        if (!ownConversations.has(run.conversationId)) continue;
        await runner
          .finish(run.runId, {
            outcome: 'FAILED',
            failureClass: 'INFRA',
            failureMessage: 'The e2e test ended before the run did',
            summary: null,
            result: null,
          })
          .catch(() => undefined);
      }
    },
    { auto: true },
  ],
  stubs: async ({}, use) => use(new StubsControl(urls.control)),
  litellm: async ({}, use) => use(new LiteLlmControl(urls.control)),
  slack: async ({}, use) => use(new SlackControl(urls.control)),

  startConversation: async ({ api, apiPath, runner, seed }, use) => {
    await use(async (name, message, extra = {}) => {
      const conversation = await (
        await api(name)
      ).post(apiPath('/conversations'), {
        agentId: seed.agent.id,
        message,
        ...extra,
      });
      const run = await runner.awaitRun({ conversationId: conversation.id });
      return { conversation, run };
    });
  },
});

/** `POST /api/companies/{id}/conversations`: a new conversation, whose id its answer names. */
const NEW_CONVERSATION = /^\/api\/companies\/[^/]+\/conversations$/;

/** Records the conversations the pages of the context make. */
function recordConversations(context: BrowserContext, own: Set<string>): void {
  context.on('response', (response) => {
    if (response.request().method() !== 'POST' || !response.ok()) return;
    if (!NEW_CONVERSATION.test(new URL(response.url()).pathname)) return;
    response
      .json()
      .then((body: { id?: string }) => body.id && own.add(body.id))
      .catch(() => undefined);
  });
}

/** The API, which records the conversations it makes. */
class RecordingApi extends Api {
  private readonly own: Set<string>;

  constructor(context: APIRequestContext, baseURL: string, own: Set<string>) {
    super(context, baseURL);
    this.own = own;
  }

  override async post<T = Body>(path: string, body?: unknown, options?: CallOptions): Promise<T> {
    const answer = await super.post<T>(path, body, options);
    const id = (answer as { id?: unknown } | null)?.id;
    if (NEW_CONVERSATION.test(path.split('?')[0]) && typeof id === 'string') this.own.add(id);
    return answer;
  }
}

/** The runner's controls, which record the conversation of every run the test awaited or drove. */
class RecordingRunnerControl extends RunnerControl {
  private readonly own: Set<string>;
  changedScript = false;

  constructor(baseUrl: string, own: Set<string>) {
    super(baseUrl);
    this.own = own;
  }

  override async awaitRun(
    filter: Parameters<RunnerControl['awaitRun']>[0],
    timeoutMs?: number,
  ): Promise<RunnerRun> {
    if (filter.conversationId) this.own.add(filter.conversationId);
    const run = await super.awaitRun(filter, timeoutMs);
    this.own.add(run.conversationId);
    return run;
  }

  override setDefaultScript(script: string): ReturnType<RunnerControl['setDefaultScript']> {
    this.changedScript = true;
    return super.setDefaultScript(script);
  }
}

/** Impacts that fail a page (the plan §8: no serious or critical finding). */
const FAILING_IMPACTS = new Set(['serious', 'critical']);

/**
 * Runs axe on the page (WCAG 2.2 A and AA rules) and fails on any serious or critical violation, listing each with
 * its nodes; every result is attached to the test.
 */
export async function expectNoAxeViolations(
  page: Page,
  testInfo: TestInfo,
  options: { include?: string; exclude?: string[]; disableRules?: string[] } = {},
): Promise<void> {
  // A toast fades in and out: axe reads its colours only while every toast is whole, else a half-faded toast fails
  // contrast. One that starts to fade during the analysis (its time ran out) makes the results stale: again.
  const toastsWhole = () =>
    page.evaluate(() => {
      const toasts = Array.from(document.querySelectorAll('[data-sonner-toast]'));
      return toasts.every(
        (toast) =>
          getComputedStyle(toast).opacity === '1' && toast.getAttribute('data-removed') !== 'true',
      )
        ? toasts.length
        : -1;
    });
  // Likewise a CSS transition or animation on its way (a dialog that opens, a button enabled again, a row that fades
  // in) blends its colours with the ground: axe reads them once every finite one has ended, which it waits for here
  // (5 s at most). It answers how many it waited for: one that ran during the analysis makes the results stale.
  const settled = () =>
    page.evaluate(async () => {
      const running = document.getAnimations().filter((animation) => {
        const end = animation.effect?.getComputedTiming().endTime;
        return animation.playState === 'running' && typeof end === 'number' && Number.isFinite(end);
      });
      await Promise.race([
        Promise.all(running.map((animation) => animation.finished.catch(() => undefined))),
        new Promise((resolve) => setTimeout(resolve, 5_000)),
      ]);
      return running.length;
    });
  // One that starts and ends during the analysis is no longer running after it: its start is counted instead.
  const started = () =>
    page.evaluate(() => {
      const w = window as unknown as { axeAnimationsStarted?: number };
      if (w.axeAnimationsStarted === undefined) {
        w.axeAnimationsStarted = 0;
        for (const type of ['transitionrun', 'animationstart']) {
          document.addEventListener(type, () => (w.axeAnimationsStarted! += 1), true);
        }
      }
      return w.axeAnimationsStarted;
    });
  let builder = new AxeBuilder({ page }).withTags([
    'wcag2a',
    'wcag2aa',
    'wcag21a',
    'wcag21aa',
    'wcag22aa',
  ]);
  if (options.include) builder = builder.include(options.include);
  for (const selector of options.exclude ?? []) builder = builder.exclude(selector);
  if (options.disableRules) builder = builder.disableRules(options.disableRules);
  let results: Awaited<ReturnType<AxeBuilder['analyze']>>;
  for (let attempt = 1; ; attempt++) {
    await expect.poll(toastsWhole).toBeGreaterThanOrEqual(0);
    await settled();
    const before = await toastsWhole();
    const starts = await started();
    results = await builder.analyze();
    const still =
      before >= 0 &&
      (await toastsWhole()) === before &&
      (await settled()) === 0 &&
      (await started()) === starts;
    if (still || attempt === 5) break;
  }
  await testInfo.attach('axe-results.json', {
    body: JSON.stringify(results, null, 2),
    contentType: 'application/json',
  });
  const failing = results.violations.filter((violation) =>
    FAILING_IMPACTS.has(violation.impact ?? ''),
  );
  const report = failing
    .map(
      (violation) =>
        `${violation.impact} ${violation.id}: ${violation.help}\n` +
        violation.nodes
          .map((node) => `    ${node.target.join(' ')}: ${node.failureSummary ?? ''}`)
          .join('\n'),
    )
    .join('\n');
  expect(failing, `axe found serious or critical violations on ${page.url()}:\n${report}`).toEqual(
    [],
  );
}

/**
 * Fails when the page scrolls sideways (the phone project's check, the plan §8). A mobile browser zooms a page
 * that is too wide out to fit it, and its layout viewport (`innerWidth`) grows with it: so the page must fit the
 * project's viewport, not only its own.
 */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const viewport = page.viewportSize()?.width ?? Number.POSITIVE_INFINITY;
  const { scrollWidth, innerWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }));
  expect(
    scrollWidth,
    `the page is ${scrollWidth}px wide in a ${innerWidth}px viewport`,
  ).toBeLessThanOrEqual(Math.min(innerWidth, viewport));
}

/**
 * A slice's screenshot for review (the plan §11): `docs/screenshots/web/<slice>/<name>.<project>.png`, written only
 * when E2E_SCREENSHOTS is set, so that an ordinary run changes no file. `dark` emulates the dark theme first.
 */
export async function shoot(
  page: Page,
  testInfo: TestInfo,
  slice: string,
  name: string,
  options: { fullPage?: boolean; dark?: boolean } = {},
): Promise<void> {
  if (!process.env['E2E_SCREENSHOTS']) return;
  const dir = resolve(repoDir, 'docs/screenshots/web', slice);
  mkdirSync(dir, { recursive: true });
  if (options.dark) await page.emulateMedia({ colorScheme: 'dark' });
  const path = resolve(dir, `${name}${options.dark ? '.dark' : ''}.${testInfo.project.name}.png`);
  if (options.fullPage ?? true) {
    // Chromium's full-page capture draws fixed and sticky elements (the sidebar, the header) where the page was
    // scrolled to. Instead: from the top, with a viewport as tall as the page, so that `svh` spans all of it and the
    // shot shows the layout as a person sees it; then the viewport as it was.
    const viewport = page.viewportSize();
    await page.evaluate(() => window.scrollTo(0, 0));
    const height = await page.evaluate(() => document.documentElement.scrollHeight);
    if (viewport && height > viewport.height) {
      await page.setViewportSize({ width: viewport.width, height });
    }
    try {
      await page.screenshot({ path });
    } finally {
      if (viewport) await page.setViewportSize(viewport);
    }
  } else {
    await page.screenshot({ path });
  }
  if (options.dark) await page.emulateMedia({ colorScheme: null });
}
