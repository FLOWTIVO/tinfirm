import type { Browser, Page, Route, TestInfo } from '@playwright/test';
import { expect, expectNoAxeViolations, expectNoHorizontalOverflow, test } from '../fixtures';
import { Api } from '../harness/api';
import { password, root, urls } from '../harness/config';

/**
 * The licence, its seats and the Viewer (ADR-0077).
 *
 * A key is verified by the server against the public key it ships with, and FLOWTIVO alone holds
 * the private one: the harness cannot sign a key. The licence page and the seat refusal therefore
 * run against answers in the contract's shape (`page.route`), which test the page's flow and words;
 * the server's own tests check the keys, the seats and the gates. The Viewer runs against the real
 * server: a person of the test's own, added as VIEWER.
 */

const KEY = 'TINFIRM1.bGljZW5jZS1lMmUtMDAwMQ.c2lnbmVkLWJ5LWZsb3d0aXZv'; // gitleaks:allow (test fixture)

/** A suffix that keeps the names a test makes apart between the two projects and between runs. */
function unique(testInfo: TestInfo): string {
  return `${testInfo.project.name}-${Date.now().toString(36)}`;
}

async function check(page: Page, testInfo: TestInfo): Promise<void> {
  await expectNoAxeViolations(page, testInfo);
  await expectNoHorizontalOverflow(page);
}

/** A page logged in through the API, in a context of its own with the project's viewport. */
async function pageFor(browser: Browser, page: Page, testInfo: TestInfo, api: Api, name: string) {
  const state = testInfo.outputPath(`${name}-state.json`);
  await api.saveStorageState(state);
  const context = await browser.newContext({
    storageState: state,
    viewport: page.viewportSize(),
    isMobile: testInfo.project.use.isMobile,
    hasTouch: testInfo.project.use.hasTouch,
    deviceScaleFactor: testInfo.project.use.deviceScaleFactor,
    baseURL: testInfo.project.use.baseURL,
  });
  return { context, page: await context.newPage() };
}

function licence(overrides: Record<string, unknown> = {}) {
  return {
    edition: 'COMMUNITY',
    state: 'NONE',
    customer: null,
    endsAt: null,
    graceEndsAt: null,
    auditExportUntil: null,
    seats: { licensed: null, used: 6 },
    companiesLicensed: null,
    features: [],
    ...overrides,
  };
}

const PRO = {
  edition: 'PRO',
  state: 'ACTIVE',
  customer: 'Acme s.r.o.',
  endsAt: '2027-03-31',
  seats: { licensed: 10, used: 6 },
  features: [
    'OUTLOOK_ADDIN',
    'GMAIL_ADDON',
    'AUDIT_TOOLS',
    'AUDIT_FULL_HISTORY',
    'AUDIT_ANCHOR',
    'RETENTION_SETTINGS',
    'ELASTIC_RUNNERS',
  ],
};

const fulfil = (route: Route, status: number, json?: unknown) =>
  json === undefined ? route.fulfill({ status }) : route.fulfill({ status, json });

test.describe('the instance’s licence', () => {
  test(
    'an instance admin enters a key, reads a refusal in words, then the next revision',
    {
      tag: ['@app'],
    },
    async ({ browser, page }, testInfo) => {
      const rootApi = await Api.login(urls.server, root.email, root.password);
      const { context, page: rootPage } = await pageFor(browser, page, testInfo, rootApi, 'root');
      // The licence and its keys as the server would answer them, kept by the test.
      const key = (rev: number, seats: number) => ({
        licenceId: 'LIC-E2E-0001',
        rev,
        customer: 'Acme s.r.o.',
        edition: 'PRO',
        seats,
        companies: null,
        startsAt: '2026-04-01',
        endsAt: '2027-03-31',
        appliedAt: new Date().toISOString(),
        appliedByName: 'Root',
      });
      let keys: { current: unknown; next: unknown } = { current: null, next: null };
      let current = licence();
      const sent: string[] = [];
      await rootPage.route('**/api/licence', (route) => fulfil(route, 200, current));
      await rootPage.route('**/api/admin/licence', (route) => {
        if (route.request().method() === 'GET') return fulfil(route, 200, keys);
        sent.push((route.request().postDataJSON() as { key: string }).key);
        if (sent.length === 2) {
          // The same key again: an equal revision of the licence in force.
          return fulfil(route, 409, {
            status: 409,
            code: 'LICENCE_KEY_SUPERSEDED',
            title: 'Conflict',
          });
        }
        const seats = sent.length === 1 ? 10 : 15;
        keys = { current: key(sent.length === 1 ? 1 : 2, seats), next: null };
        current = licence({ ...PRO, seats: { licensed: seats, used: 6 } });
        return fulfil(route, 200, keys);
      });

      try {
        await rootPage.goto('/admin/licence');
        await expect(rootPage.getByRole('heading', { level: 1, name: 'Licence' })).toBeVisible();
        await expect(rootPage.getByTestId('licence-edition')).toHaveText('Community');
        await expect(rootPage.getByTestId('no-key')).toBeVisible();
        await check(rootPage, testInfo);

        // A key pasted with the line breaks a mail adds is sent as one line.
        const field = rootPage.getByLabel('Licence key');
        await field.fill(`  ${KEY.slice(0, 20)}\n${KEY.slice(20)}\n`);
        await rootPage.getByTestId('enter-key').click();
        await expect(rootPage.getByTestId('current-key')).toContainText('revision 1');
        expect(sent[0]).toBe(KEY);
        await expect(rootPage.getByTestId('licence-edition')).toHaveText('Pro');
        await expect(rootPage.getByTestId('licence-seats')).toContainText('6 of 10 seats used');
        await expect(rootPage.getByTestId('licence-ends')).toHaveText('31 March 2027');
        await check(rootPage, testInfo);

        // The same key again is refused in words, and stays to be corrected.
        await field.fill(KEY);
        await rootPage.getByTestId('enter-key').click();
        await expect(rootPage.getByTestId('enter-key-error')).toContainText(
          'This key is the one in force, or an older revision of its licence.',
        );
        await expect(field).toHaveValue(KEY);

        // Seats bought during the year: the next revision replaces the key in force.
        await field.fill(KEY);
        await rootPage.getByTestId('enter-key').click();
        await expect(rootPage.getByTestId('current-key')).toContainText('revision 2');
        await expect(rootPage.getByTestId('licence-seats')).toContainText('6 of 15 seats used');
        await expect(rootPage.getByRole('button', { name: /^Remove/ })).toHaveCount(0);
      } finally {
        await context.close();
        await rootApi.dispose();
      }
    },
  );
});

test.describe('seats', () => {
  test.use({ persona: 'owner' });

  test(
    'every seat taken: the Owner is offered a Viewer, and a Member is refused in words',
    {
      tag: ['@app'],
    },
    async ({ page, appPath }, testInfo) => {
      const email = `sam-${unique(testInfo)}@acme.test`;
      await page.route('**/api/licence', (route) =>
        fulfil(route, 200, licence({ ...PRO, seats: { licensed: 6, used: 6 } })),
      );
      // The server refuses the seat the licence lacks; the person is never added.
      await page.route(/\/api\/companies\/[^/]+\/members$/, (route) =>
        route.request().method() === 'POST'
          ? fulfil(route, 409, {
              status: 409,
              title: 'Conflict',
              code: 'SEATS_EXHAUSTED',
              details: { licensed: 6, used: 6 },
            })
          : route.fallback(),
      );

      await page.goto(appPath('/members'));
      await expect(page.getByTestId('seat-count')).toContainText('6 of 6 seats used');
      await expect(page.getByTestId('seat-count')).toContainText('Every seat is taken');

      await page.getByRole('button', { name: 'Add member' }).click();
      const add = page.getByRole('dialog', { name: 'Add member' });
      await expect(add.locator('#add-member-role-VIEWER')).toBeChecked();
      await expect(add.getByTestId('add-no-seat')).toBeVisible();
      await check(page, testInfo);

      await add.getByLabel('Email').fill(email);
      await add.locator('[data-role=MEMBER]').click();
      await add.getByRole('button', { name: 'Add member' }).click();
      await expect(add).toContainText('All 6 seats of the licence are taken.');
      await expect(add).toContainText(
        'Make someone a Viewer, or enter a key with more seats, first.',
      );
    },
  );
});

test.describe('a Viewer', () => {
  test(
    'reads, and starts, edits, authors and answers nothing',
    {
      tag: ['@app', '@phone'],
    },
    async ({ browser, page, api, apiPath, appPath }, testInfo) => {
      const suffix = unique(testInfo);
      const email = `vera-${suffix}@acme.test`;
      const rootApi = await Api.login(urls.server, root.email, root.password);
      const owner = await api('owner');
      const vera = await rootApi.post('/api/admin/users', {
        email,
        displayName: `Vera ${suffix}`,
        password,
        instanceAdmin: false,
      });
      const added = await owner.raw('POST', apiPath('/members'), { email, role: 'VIEWER' });
      expect(added.status()).toBe(201);
      const veraApi = await Api.login(urls.server, email, password);
      const { context, page: veraPage } = await pageFor(browser, page, testInfo, veraApi, 'vera');

      try {
        for (const [path, heading, place] of [
          ['/conversations', 'Conversations', 'start none'],
          ['/agents', 'Agents', 'don’t talk to them, create or edit them'],
          ['/triggers', 'Triggers', 'don’t create or edit them'],
          ['/inbox', 'Inbox', 'don’t answer questions or decide approvals'],
        ] as const) {
          await veraPage.goto(appPath(path));
          await expect(veraPage.getByRole('heading', { level: 1, name: heading })).toBeVisible();
          await expect(veraPage.getByTestId('viewer-notice')).toContainText(place);
          await check(veraPage, testInfo);
        }
        await expect(veraPage.getByTestId('new-conversation')).toHaveCount(0);
        await veraPage.goto(appPath('/agents'));
        await expect(veraPage.getByTestId('new-agent')).toHaveCount(0);
        await expect(veraPage.getByTestId('talk-to')).toHaveCount(0);
        await veraPage.goto(appPath('/triggers'));
        await expect(veraPage.getByTestId('new-trigger')).toHaveCount(0);

        // The pages that start work send a Viewer to the inbox.
        for (const path of ['/conversations/new', '/agents/new', '/triggers/new', '/accounts']) {
          await veraPage.goto(appPath(path));
          await expect(veraPage).toHaveURL((url) => url.pathname === appPath('/inbox'));
        }
        await expect(veraPage.getByRole('link', { name: 'Accounts' })).toHaveCount(0);
      } finally {
        await context.close();
        await veraApi.dispose();
        await rootApi.raw('DELETE', `/api/admin/users/${vera.id}`);
        await rootApi.dispose();
      }
    },
  );
});
