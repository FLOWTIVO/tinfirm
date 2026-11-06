import { type Locator, type Page, request, type TestInfo } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { Api } from '../harness/api.ts';
import { root, urls } from '../harness/config.ts';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  shoot,
  test,
} from '../fixtures';

/** W7's review screenshots go to docs/screenshots/web/w7/. */
const SLICE = 'w7';

function unique(testInfo: TestInfo): string {
  return `${testInfo.project.name}-${Date.now().toString(36)}`;
}

/** Axe with no serious or critical finding, no sideways scroll, and the review shot once the toasts went. */
async function check(page: Page, testInfo: TestInfo, shot?: string, dialog = false): Promise<void> {
  await expectNoAxeViolations(page, testInfo);
  await expectNoHorizontalOverflow(page);
  if (shot) {
    if (process.env['E2E_SCREENSHOTS']) {
      await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 15_000 });
    }
    await shoot(page, testInfo, SLICE, shot, { fullPage: !dialog });
  }
}

/** A button that was just enabled fades in; axe reads its colours only once it has. */
async function settled(button: Locator): Promise<void> {
  await expect(button).toBeEnabled();
  await expect(button).toHaveCSS('opacity', '1');
}

async function openPage(page: Page, path: string, heading: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
}

async function loginAsRoot(): Promise<Api> {
  const context = await request.newContext({
    baseURL: urls.server,
    storageState: { cookies: [], origins: [] },
  });
  const api = new Api(context, urls.server);
  await api.get('/api/auth/providers');
  api.me = await api.post('/api/auth/login', { email: root.email, password: root.password });
  return api;
}

/**
 * W7, governance (the plan §7; the package 6 contract; CONVENTIONS §15.11 to §15.13), against the real server: the
 * company's runs stopped and lifted (a conversation's turn waits, then runs), a member suspended and resumed, a legal
 * hold stopping an erasure, the audit exported, and the pages around them. Every test puts back what it changed: the
 * stop is lifted, the suspension and the hold undone.
 */
test.describe('W7: governance', { tag: ['@app', '@phone'] }, () => {
  test.use({ persona: 'admin' });

  test('the company’s runs: stopped, a turn waits, then runs after the lift @serial', async ({
    page,
    appPath,
    apiPath,
    api,
    pageAs,
    runner,
    startConversation,
  }, testInfo) => {
    const admin = await api('admin');
    const alice = await api('alice');
    const { conversation, run } = await startConversation('alice', `W7 stop ${unique(testInfo)}`);
    try {
      await openPage(page, appPath('/kill-switches'), 'Kill switches');
      await expect(page.getByTestId('runs-going')).toBeVisible();
      await check(page, testInfo, 'kill-switches');

      await page.getByTestId('stop-runs').click();
      const dialog = page.getByTestId('stop-dialog');
      await expect(dialog.getByTestId('stop-dialog-confirm')).toBeDisabled();
      await dialog.getByLabel('Reason').fill('A key may have leaked');
      await settled(dialog.getByTestId('stop-dialog-confirm'));
      await check(page, testInfo, 'stop-dialog', true);
      await dialog.getByTestId('stop-dialog-confirm').click();
      await expect(page.getByTestId('runs-stopped')).toContainText('“A key may have leaked”');
      // Every page says so, with who and why.
      await expect(page.getByTestId('runs-stopped-banner')).toContainText('A key may have leaked');
      await check(page, testInfo, 'kill-switches-stopped');

      // The running turn was cancelled; Alice's message is taken, and its turn waits.
      await expect
        .poll(async () => (await alice.get(apiPath(`/runs/${run.runId}`))).status, {
          timeout: 15_000,
        })
        .toBe('CANCELLED');
      await alice.post(apiPath(`/conversations/${conversation.id}/messages`), {
        body: 'Go on once the stop is lifted',
      });
      await expect
        .poll(async () => {
          const c = await alice.get(apiPath(`/conversations/${conversation.id}`));
          const runId = c.activeRunId as string | null;
          return runId ? (await alice.get(apiPath(`/runs/${runId}`))).queueReason?.code : null;
        })
        .toBe('COMPANY_STOPPED');
      const refused = await alice.raw('POST', apiPath('/conversations'), {
        agentId: (await alice.get(apiPath(`/conversations/${conversation.id}`))).agentId,
        message: 'A new one',
      });
      expect(refused.status()).toBe(409);
      expect((await refused.json()).code).toBe('COMPANY_STOPPED');

      // Alice sees the banner on her pages.
      const alicePage = await pageAs('alice');
      await alicePage.goto(appPath('/agents'));
      await expect(alicePage.getByTestId('runs-stopped-banner')).toContainText(
        'The company’s runs are stopped',
      );
      await expect(alicePage.getByTestId('runs-stopped-banner').getByRole('link')).toHaveCount(0);
      await check(alicePage, testInfo, 'banner-member');

      // The lift: the waiting turn goes to the runner.
      await page.getByTestId('lift-runs').click();
      await expect(page.getByTestId('lift-dialog')).toContainText('No other switch is on.');
      await page.getByTestId('confirm-lift').click();
      await expect(page.getByTestId('lift-result')).toContainText('The stop is lifted');
      await expect(page.getByTestId('runs-going')).toBeVisible();
      await expect(page.getByTestId('runs-stopped-banner')).toHaveCount(0);
      const next = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
      expect(next.runId).not.toBe(run.runId);
      await check(page, testInfo, 'kill-switches-lifted');
    } finally {
      await admin.raw('DELETE', apiPath('/runs-stop'));
    }
  });

  test('a system paused and resumed from the switches’ page @serial', async ({
    page,
    appPath,
    apiPath,
    api,
    seed,
  }, testInfo) => {
    const admin = await api('admin');
    try {
      await openPage(page, appPath('/kill-switches'), 'Kill switches');
      await page.getByTestId('pause-system').click();
      const dialog = page.getByTestId('pause-dialog');
      await dialog.getByLabel('System').selectOption({ label: seed.systems.jira.name });
      await dialog.getByLabel('Reason').fill('Jira answers slowly');
      await dialog.getByTestId('pause-dialog-confirm').click();
      const row = page.locator(`[data-system="${seed.systems.jira.name}"]`);
      await expect(row).toContainText('“Jira answers slowly”');
      await expect(page.getByTestId('counts')).toContainText('1 paused system');
      await check(page, testInfo, 'system-paused');

      await row.getByRole('button', { name: `Resume ${seed.systems.jira.name}` }).click();
      await expect(page.getByTestId('resume-dialog')).toContainText('Its pause paused no trigger.');
      await page.getByTestId('confirm-resume').click();
      await expect(row).toHaveCount(0);
    } finally {
      await admin.raw('POST', apiPath(`/systems/${seed.systems.jira.id}/resume`), {});
    }
  });

  test('a member suspended, who then sees only the switches, and resumed @serial', async ({
    page,
    appPath,
    apiPath,
    api,
    pageAs,
    seed,
  }, testInfo) => {
    const admin = await api('admin');
    const dave = seed.people.dave;
    try {
      await openPage(page, appPath('/members'), 'Members');
      const row = page.getByTestId(`member-${dave.email}`);
      await row.getByRole('button').click();
      const member = page.getByTestId('member-dialog');
      await member.getByTestId('suspend-member').click();
      const dialog = page.getByTestId('suspend-member-dialog');
      await dialog.getByLabel('Reason').fill('His laptop was stolen');
      await dialog.getByTestId('suspend-member-dialog-confirm').click();
      await expect(row.getByTestId('member-suspended')).toBeVisible();
      await expect(member.getByTestId('resume-member')).toBeVisible();
      await check(page, testInfo, 'member-suspended');
      await member.getByRole('button', { name: 'Close' }).click();
      await expect(member).toBeHidden();

      // Nobody suspends themselves: the admin's own dialog offers nothing.
      await page.getByTestId(`member-${seed.people.admin.email}`).getByRole('button').click();
      await expect(member).toContainText('You cannot suspend yourself.');
      await expect(member.getByTestId('suspend-member')).toHaveCount(0);
      await member.getByRole('button', { name: 'Close' }).click();

      // Dave reads only the switches' page, which tells him why.
      const davePage = await pageAs('dave');
      await davePage.goto(appPath('/kill-switches'));
      await expect(davePage.getByTestId('my-suspension')).toContainText('His laptop was stolen');
      await expect(davePage.getByTestId('my-suspension-card')).toBeVisible();
      await check(davePage, testInfo, 'suspended-member-view');
      const refused = await (await api('dave')).raw('GET', apiPath('/agents'));
      expect(refused.status()).toBe(403);

      // The switches' page lists him, and resumes him in place.
      await openPage(page, appPath('/kill-switches'), 'Kill switches');
      const suspended = page.locator(`[data-principal="${dave.displayName}"]`);
      await expect(suspended).toContainText('His laptop was stolen');
      await check(page, testInfo, 'kill-switches-suspended');
      await suspended.getByRole('button', { name: `Resume ${dave.displayName}` }).click();
      await expect(suspended).toHaveCount(0);

      await openPage(page, appPath('/members'), 'Members');
      await expect(row.getByTestId('member-suspended')).toHaveCount(0);
    } finally {
      await admin.raw('POST', apiPath(`/members/${dave.id}/resume`));
    }
  });

  test('a member who leaves: the preview, the checklist and its closing', async ({
    page,
    appPath,
    api,
    apiPath,
    seed,
  }, testInfo) => {
    const suffix = unique(testInfo);
    const rootApi = await loginAsRoot();
    const admin = await api('admin');
    const email = `fay-${suffix}@acme.test`;
    const name = `Fay ${suffix}`;
    const fay = await rootApi.post('/api/admin/users', {
      email,
      displayName: name,
      password: seed.password,
      instanceAdmin: false,
    });
    try {
      await admin.post(apiPath('/members'), { email, role: 'MEMBER' });
      await openPage(page, appPath('/members'), 'Members');
      const row = page.getByTestId(`member-${email}`);
      await row.getByRole('button').click();
      await page.getByTestId('member-dialog').getByTestId('remove-member').click();
      const dialog = page.getByRole('alertdialog', { name: `Remove ${name}?` });
      await expect(dialog.getByTestId('departure-preview')).toContainText(
        'Nothing else of theirs needs someone.',
      );
      await check(page, testInfo, 'remove-preview', true);
      await dialog.getByRole('button', { name: 'Remove member' }).click();

      await expect(page.getByRole('heading', { level: 1, name: `${name} left` })).toBeVisible();
      await expect(page.getByTestId('departure-triggers')).toContainText('None.');
      await check(page, testInfo, 'departure');

      // Members lists them while their checklist is open, and leads back to it.
      await openPage(page, appPath('/members'), 'Members');
      const open = page.getByTestId('open-departures');
      await expect(open).toContainText(name);
      // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp -- built from the test's own values, never input
      await open.getByRole('link', { name: new RegExp(name) }).click();
      await expect(page.getByRole('heading', { level: 1, name: `${name} left` })).toBeVisible();

      await page.getByTestId('close-departure').click();
      await expect(page.getByTestId('departure-closed')).toBeVisible();
      await openPage(page, appPath('/members'), 'Members');
      await expect(page.getByTestId('open-departures').filter({ hasText: name })).toHaveCount(0);
    } finally {
      await rootApi.raw('DELETE', `/api/admin/users/${fay.id}`);
    }
  });

  test('spend, and the budget alerts on model access', async ({
    page,
    appPath,
    seed,
  }, testInfo) => {
    await openPage(page, appPath('/model-access?tab=spend'), 'Model access');
    await expect(page.getByLabel('Group the spend')).toBeVisible();
    await expect(page.getByTestId('spend-table').or(page.getByTestId('spend-empty'))).toBeVisible();
    await check(page, testInfo, 'spend');
    await page.getByTestId('spend-filters').locator('summary').click();
    await page.getByLabel('Who paid').selectOption({ label: seed.people.alice.displayName });
    await page.getByLabel('Counted from').selectOption('GATEWAY');
    await expect(page.getByTestId('spend-filters')).toContainText('2 set');
    await expect(page.getByTestId('spend-table').or(page.getByTestId('spend-empty'))).toBeVisible();
    await check(page, testInfo, 'spend-filters');
    await page.getByTestId('spend-clear-filters').click();

    // The radio is visually hidden inside its pill: a click on its own box lands beside the pill, so the pill's label
    // is what a person, and the test, clicks.
    const grouping = page.getByRole('radiogroup', { name: 'Group the spend by' });
    await grouping.locator('label[data-value="agent"]').click();
    await expect(grouping.getByRole('radio', { name: 'Agent' })).toBeChecked();
    await expect(page.getByTestId('spend-table').or(page.getByTestId('spend-empty'))).toBeVisible();
    await page.getByLabel('Then by').selectOption('model');
    await expect(page.getByTestId('spend-table').or(page.getByTestId('spend-empty'))).toBeVisible();

    await page.getByTestId('model-access-sections').locator('[data-section="defaults"]').click();
    const alerts = page.getByTestId('alerts');
    await expect(alerts.getByLabel('Tell people at')).toHaveValue('80, 100');
    await alerts.getByLabel('Tell people at').fill('80, 80');
    // A percentage given twice is marked as it is typed, and Save waits for it.
    await expect(alerts).toContainText('Enter 1 to 3 different percentages');
    await expect(page.getByTestId('save-model-settings')).toBeDisabled();
    await alerts.getByLabel('Tell people at').fill('80, 100');
    await expect(page.getByTestId('save-model-settings')).toBeHidden();
    await alerts.scrollIntoViewIfNeeded();
    await check(page, testInfo, 'model-access-alerts');
  });

  test(
    'a member reads their own spend',
    { tag: '@phone' },
    async ({ pageAs, appPath }, testInfo) => {
      const alicePage = await pageAs('alice');
      await openPage(alicePage, appPath('/spend'), 'Spend');
      await expect(
        alicePage.getByTestId('spend-table').or(alicePage.getByTestId('spend-empty')),
      ).toBeVisible();
      await check(alicePage, testInfo, 'spend-member');
    },
  );

  test('the audit: its daily checks and an export as NDJSON', async ({
    page,
    appPath,
  }, testInfo) => {
    await openPage(page, appPath('/audit'), 'Audit log');
    // The chain's status line: the latest daily check, or none yet.
    await expect(page.getByTestId('audit-checks')).toContainText(
      /Chain checked|Daily check failed|No check has run yet/,
    );
    await page.getByTestId('open-export').click();
    const dialog = page.getByTestId('audit-export');
    await dialog.getByLabel('From event').fill('1');
    await dialog.getByLabel('To event').fill('20');
    await expect(dialog.getByTestId('export-summary')).toContainText('Event 1 to event 20');
    const download = page.waitForEvent('download');
    await dialog.getByTestId('export-audit').click();
    const file = await (await download).path();
    const lines = readFileSync(file, 'utf8')
      .trim()
      .split('\n')
      .map((line) => JSON.parse(line));
    expect(lines[0]).toMatchObject({ kind: 'export', fromSeq: 1, toSeq: 20, count: 20 });
    expect(lines.at(-1).kind).toBe('head');
    expect(lines[1]).toMatchObject({ seq: 1 });
    expect(lines[1].hash).toMatch(/^[0-9a-f]{64}$/);
    await expect(dialog.getByTestId('export-done')).toContainText('The download has started');

    // An inverted range is refused before asking.
    await dialog.getByLabel('From event').fill('30');
    await expect(dialog.getByTestId('export-audit')).toBeDisabled();
    await expect(dialog).toContainText('The first event must come before the last.');
    await dialog.getByLabel('From event').fill('1');
    await check(page, testInfo, 'audit');
  });
});

test.describe('W7: retention, as the Owner', { tag: ['@app', '@phone'] }, () => {
  test.use({ persona: 'owner' });

  test('a legal hold on a person stops their erasure, until it is lifted @serial', async ({
    page,
    appPath,
    apiPath,
    api,
    seed,
  }, testInfo) => {
    const owner = await api('owner');
    const carol = seed.people.carol;
    const reason = `Litigation ${unique(testInfo)}`;
    try {
      await openPage(page, appPath('/retention'), 'Retention');
      await expect(page.getByTestId('periods')).toContainText('These are the defaults.');
      await expect(page.getByLabel('Conversations’ content')).toHaveValue('365');

      // A shorter period is counted before it is saved; discarded, nothing changed.
      await page.getByLabel('Conversations’ content').fill('30');
      await expect(page.getByTestId('due-proposed')).toContainText(
        'With these periods, the next sweep deletes',
      );
      await page
        .getByRole('region', { name: 'Unsaved changes' })
        .getByRole('button', { name: 'Discard' })
        .click();
      await expect(page.getByLabel('Conversations’ content')).toHaveValue('365');
      await expect(page.getByTestId('due-proposed')).toHaveCount(0);

      // One section at a time: the holds, then the erasure, from the rail.
      await page.getByRole('tab', { name: 'Legal holds' }).click();
      await page.getByTestId('set-hold').click();
      const dialog = page.getByTestId('hold-dialog');
      await dialog.locator('label[data-value="person"]').click();
      await dialog.getByLabel('Person', { exact: true }).selectOption({ label: carol.displayName });
      await dialog.getByLabel('Reason').fill(reason);
      await expect(dialog.getByTestId('hold-untold')).toContainText('is not told');
      await settled(dialog.getByTestId('hold-dialog-confirm'));
      await check(page, testInfo, 'hold-dialog', true);
      await dialog.getByTestId('hold-dialog-confirm').click();
      const hold = page.locator(`[data-hold="${carol.displayName}"]`);
      await expect(hold).toContainText(reason);
      await expect(page.getByRole('tab', { name: 'Legal holds' })).toContainText('1 active');

      // The erasure's dry run names the hold on the person at its review, and whom to ask; there
      // is no way on to the erasure while it stands.
      await expect(dialog).toHaveCount(0);
      await page.getByRole('tab', { name: 'Erase on request' }).click();
      await page.locator('#erase-person').selectOption({ label: carol.displayName });
      await page.getByTestId('erase-review').click();
      await expect(page.getByTestId('erasure-held')).toContainText(reason);
      await expect(page.getByTestId('erasure-ask')).toContainText('You set it');
      await expect(page.getByTestId('erasure-counted')).toBeVisible();
      await expect(page.getByTestId('erase-next')).toHaveCount(0);
      await check(page, testInfo, 'retention');

      await page.getByTestId('erase-to-holds').click();
      await hold.getByRole('button', { name: `Lift the hold on ${carol.displayName}` }).click();
      await page.getByTestId('confirm-lift-hold').click();
      await expect(hold).toHaveCount(0);

      // Lifted, the dry run counts again and the review lets the erasure go on to its confirmation.
      await page.getByRole('tab', { name: 'Erase on request' }).click();
      await expect(page.getByTestId('erasure-held')).toHaveCount(0);
      await expect(page.getByTestId('erasure-counted')).toBeVisible();
      await expect(page.getByTestId('erase-next')).toBeEnabled();
    } finally {
      for (const h of await owner.all<{ id: string; principalId?: string }>(apiPath('/holds'))) {
        if (h.principalId === carol.principalId)
          await owner.raw('DELETE', apiPath(`/holds/${h.id}`));
      }
    }
  });
});
