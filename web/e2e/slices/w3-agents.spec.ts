import type { Locator, Page, TestInfo } from '@playwright/test';
import type { Api, Body } from '../harness/api';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  type RunnerRun,
  type SeedState,
  shoot,
  test,
} from '../fixtures';
import { company } from '../harness/config';

/**
 * W3, agents (the plan §4.3, §4.4 and §8): the list, the agent page, the editor with its widening notice, the
 * widening card and the settings per agent, against the real server. The agents a test makes are its own, and are
 * deleted when it ends; the settings it changes go back to the seed's, which name a model: a budget pays for nothing
 * else (`MODEL_REQUIRED`).
 */

/**
 * Deletes the live agents of these names, as the admin: the test's own, and what an earlier, failed run left. A run
 * that the runner has just finished may still count as active for a moment (409 AGENT_HAS_ACTIVE_RUNS), so a refused
 * delete is tried again for a few seconds.
 */
async function deleteAgents(admin: Api, path: (suffix: string) => string, names: string[]) {
  const agents: { id: string; name: string }[] = await admin.all(path('/agents'));
  for (const agent of agents.filter((a) => names.includes(a.name))) {
    for (let attempt = 1; ; attempt++) {
      try {
        await admin.delete(path(`/agents/${agent.id}`));
        break;
      } catch (error) {
        if (attempt >= 20) {
          console.warn(`Could not delete the agent ${agent.name}: ${String(error)}`);
          break;
        }
        await new Promise((resolve) => setTimeout(resolve, 500));
      }
    }
  }
}

/**
 * Opens a section of the agent editor by its tab; on a phone, from the list of sections. A tab is
 * named by its content, the label and then what the section holds: the tab is the one whose label
 * reads `name`.
 */
async function openSection(page: Page, name: string): Promise<void> {
  const list = page.getByRole('button', { name: 'All sections' });
  if (await list.isVisible()) await list.click();
  await page
    .getByRole('tab')
    .filter({ has: page.getByText(name, { exact: true }) })
    .click();
}

/** The rows of a system's tools on the editor or the settings form, by the tool's name. */
function toolRow(page: Page, systemId: string, tool: string): Locator {
  return page.locator(`[data-system="${systemId}"] [data-tool="${tool}"]`);
}

/** Starts a conversation of Alice's with the agent; answers the run the runner took. */
async function aliceTalksTo(
  alice: Api,
  path: (suffix: string) => string,
  agentId: string,
  awaitRun: (filter: { conversationId: string }) => Promise<RunnerRun>,
): Promise<RunnerRun> {
  const conversation: Body = await alice.post(path('/conversations'), {
    agentId,
    message: 'Summarise SHOP-1 [e2e:hold]',
  });
  return awaitRun({ conversationId: conversation.id });
}

/** Whether a run lists the Jira comment tool as one that waits for an approval. */
function commentAsks(run: RunnerRun): boolean {
  const tool = run.tools.find((t) => t.name === 'jira_comment');
  expect(tool, `the run lists ${run.tools.map((t) => t.name).join(', ')}`).toBeDefined();
  return /approval/i.test(tool!.description);
}

/**
 * A review screenshot of the whole page as it lays out at its full height, so that the sidebar, the sticky save bar
 * and the section index sit where a reader sees them rather than where the last scroll left them.
 */
async function capture(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  if (!process.env['E2E_SCREENSHOTS']) return;
  const viewport = page.viewportSize()!;
  await page.evaluate(() => window.scrollTo(0, 0));
  const height = await page.evaluate(() => document.documentElement.scrollHeight);
  await page.setViewportSize({ width: viewport.width, height: Math.max(height, viewport.height) });
  await page.evaluate(() => document.fonts.ready);
  await shoot(page, testInfo, 'w3', name);
  await page.setViewportSize(viewport);
}

function analysts(seed: SeedState): string {
  return seed.groups['Analysts'];
}

test.describe('W3: agents', { tag: '@app' }, () => {
  test(
    'create an agent and share it with a group',
    { tag: '@phone' },
    async ({ adminPage, alicePage, bobPage, api, apiPath, appPath, seed }, testInfo) => {
      const name = `Release notes ${testInfo.project.name}`;
      const admin = await api('admin');
      await deleteAgents(admin, apiPath, [name]);
      try {
        const page = adminPage;
        await page.goto(appPath('/agents/new'));
        await expect(page.getByRole('heading', { level: 1, name: 'New agent' })).toBeVisible();
        await expect(page.getByRole('navigation', { name: 'Sections' })).toBeVisible();

        await openSection(page, 'Who it is');
        await page.getByLabel('Name', { exact: true }).fill(name);
        await page
          .getByLabel('Description')
          .fill('Reads the merged changes in Jira and GitHub and drafts the release notes.');

        // Jira, filled as the server fills it: its reads run, its writes ask. Without systems, the
        // section opens with the picker of the company's systems.
        await openSection(page, 'What it may use');
        await page.locator(`[data-add-system="${seed.systems.jira.id}"]`).click();
        const jiraGet = toolRow(page, seed.systems.jira.id, 'get');
        await expect(jiraGet.locator('input[value=ALLOW]')).toBeChecked();
        await expect(jiraGet).toContainText('Reads');
        const jiraComment = toolRow(page, seed.systems.jira.id, 'comment');
        await expect(jiraComment.locator('input[value=ASK]')).toBeChecked();
        await expect(jiraComment).toContainText('Writes');

        // GitHub, for acme/shop with writes: the picker stays open for the next system.
        await page.getByLabel('Add a system', { exact: true }).fill('GitHub');
        await page.locator(`[data-add-system="${seed.systems.github.id}"]`).click();
        const shop = page.locator(`[data-repository="${seed.repository.id}"]`);
        await shop.getByRole('checkbox').check();
        await shop.getByText('Read and write').click();
        await expect(shop.locator('input[value=WRITE]')).toBeChecked();

        await openSection(page, 'Whom you share it with');
        await page
          .locator(`[data-group="${analysts(seed)}"]`)
          .getByRole('checkbox')
          .check();
        // A new agent widens nobody's: no notice.
        await expect(page.getByTestId('widening-notice')).toHaveCount(0);
        await expectNoAxeViolations(page, testInfo);
        if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
        await capture(page, testInfo, 'agent-editor-new');

        await page.getByTestId('save-agent').click();
        await expect(page).toHaveURL(/\/agents\/[0-9a-f-]{36}$/);
        await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
        await expect(page.getByTestId('agent-meta')).toContainText('Version 1');
        await expect(page.getByTestId('agent-sharing')).toContainText('Analysts');
        await expect(page.getByTestId('definition-repositories')).toContainText(
          /acme\/shop\s*read and write/,
        );

        // Alice is in Analysts: she may use it. Bob is not.
        await alicePage.goto(appPath('/agents'));
        await expect(alicePage.getByRole('link', { name, exact: true })).toBeVisible();
        await bobPage.goto(appPath('/agents'));
        await expect(bobPage.getByRole('link', { name: 'Analyst', exact: true })).toBeVisible();
        await expect(bobPage.getByRole('link', { name, exact: true })).toHaveCount(0);
      } finally {
        await deleteAgents(admin, apiPath, [name]);
      }
    },
  );

  test('widen the agent; a user keeps the old version until they accept', async ({
    adminPage,
    alicePage,
    api,
    apiPath,
    appPath,
    runner,
    seed,
  }, testInfo) => {
    const name = 'Triage';
    const admin = await api('admin');
    const alice = await api('alice');
    await deleteAgents(admin, apiPath, [name]);
    try {
      const agent: Body = await admin.post(apiPath('/agents'), {
        name,
        description: 'Sorts new Jira issues and comments on what is missing.',
        instructions: 'Read new issues and say what is missing. Be brief.',
        runtimes: ['CLAUDE_CODE'],
        systems: [{ systemId: seed.systems.jira.id }],
        shareGroupIds: [analysts(seed)],
      });
      expect(agent.version).toBe(1);
      // Her settings of it name a model: her budget pays for nothing else (MODEL_REQUIRED).
      await alice.put(apiPath(`/agents/${agent.id}/settings/me`), { model: company.agent.model });

      // Alice's first use records the version she has: 1, where comments ask her first.
      const first = await aliceTalksTo(alice, apiPath, agent.id, (f) => runner.awaitRun(f));
      expect(commentAsks(first)).toBe(true);
      await runner.finish(first.runId, { summary: 'Done.' });

      // Adam lets Jira comments run; the notice names the widening before he saves.
      await adminPage.goto(appPath(`/agents/${agent.id}/edit`));
      await openSection(adminPage, 'What it may use');
      const comment = toolRow(adminPage, seed.systems.jira.id, 'comment');
      await comment.getByText('Allowed', { exact: true }).click();
      const notice = adminPage.getByTestId('widening-notice');
      await expect(notice).toContainText(
        'People who use Triage keep version 1 until they accept this change; what you remove applies at their next turn.',
      );
      await expect(notice.locator('li')).toHaveText([
        /^Jira · .+: Allowed, where it needed your approval$/,
      ]);
      await expectNoAxeViolations(adminPage, testInfo);
      await capture(adminPage, testInfo, 'agent-editor-widening');
      await adminPage.getByTestId('save-agent').click();
      await expect(adminPage).toHaveURL(appPath(`/agents/${agent.id}`));
      await expect(adminPage.getByTestId('agent-meta')).toContainText('Version 2');

      // Alice's list says there is a new version to review; her agent page shows the orange card, and until she
      // accepts, her conversations keep version 1.
      await alicePage.goto(appPath('/agents'));
      const row = alicePage.getByTestId(`agent-${agent.id}`);
      await expect(row.getByTestId('agent-widening')).toHaveText('New version to review');
      await capture(alicePage, testInfo, 'agents-list-widening');
      await row.getByTestId('agent-link').click();
      const card = alicePage.getByTestId('widening-card');
      await expect(card).toContainText('Triage has a new version to accept');
      await expect(card).toContainText(/Jira · .+: Allowed, where it needed your approval/);
      await expect(card.getByTestId('accept-version')).toHaveText('Accept version 2');
      await expectNoAxeViolations(alicePage, testInfo);
      await capture(alicePage, testInfo, 'agent-widening-card');

      const before = await aliceTalksTo(alice, apiPath, agent.id, (f) => runner.awaitRun(f));
      expect(commentAsks(before)).toBe(true);
      await runner.finish(before.runId, { summary: 'Done.' });

      await card.getByTestId('accept-version').click();
      await expect(alicePage.getByTestId('widening-card')).toHaveCount(0);
      await expect(alicePage.getByTestId('agent-meta')).not.toContainText('you use');

      const after = await aliceTalksTo(alice, apiPath, agent.id, (f) => runner.awaitRun(f));
      expect(commentAsks(after)).toBe(false);
      await runner.finish(after.runId, { summary: 'Done.' });
    } finally {
      await deleteAgents(admin, apiPath, [name]);
    }
  });

  test(
    'the agent page says who acts and who pays when you use it',
    { tag: '@phone' },
    async ({ alicePage, appPath, seed }, testInfo) => {
      const page = alicePage;
      await page.goto(appPath('/agents'));
      const row = page.getByTestId(`agent-${seed.agent.id}`);
      await expect(row.getByRole('link', { name: 'Talk to Analyst' })).toBeVisible();
      await expect(row.getByRole('link', { name: 'My settings of Analyst' })).toBeVisible();
      await expect(row.getByRole('link', { name: 'Edit Analyst' })).toHaveCount(0);
      await expectNoAxeViolations(page, testInfo);
      if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
      await capture(page, testInfo, 'agents-list');

      await row.getByTestId('agent-link').click();
      await expect(page.getByRole('heading', { level: 1, name: 'Analyst' })).toBeVisible();
      const when = page.getByTestId('when-you-use-it');
      await expect(when).toContainText(
        'An agent holds no credentials: it acts as whoever uses it.',
      );
      const acts = when.locator('[data-part=acts] li');
      await expect(acts).toContainText([
        'Jira: via Analysts, read-only',
        'GitHub: your own account',
      ]);
      await expect(when.locator('[data-part=pays]')).toContainText('Your budget');
      await expect(when.locator('[data-part=runs]')).toContainText('A company host');
      await expect(
        page.locator(`[data-system="${seed.systems.jira.id}"] [data-tool=comment] [data-mode]`),
      ).toHaveText('Needs your approval');
      await expectNoAxeViolations(page, testInfo);
      if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
      await capture(page, testInfo, 'agent-page');
    },
  );

  test(
    // @serial: it changes Alice's settings of the seeded agent, which every test of hers reads.
    'settings per agent narrow and never widen @serial',
    { tag: '@phone' },
    async ({ alicePage, api, apiPath, appPath, seed }, testInfo) => {
      const page = alicePage;
      const alice = await api('alice');
      const settingsPath = apiPath(`/agents/${seed.agent.id}/settings/me`);
      // The seed's settings: stored, with the model alone (SettingsService.put: absent fields take their defaults).
      const seeded = { model: company.agent.model };
      await alice.put(settingsPath, seeded);
      try {
        await page.goto(appPath(`/agents/${seed.agent.id}/settings`));
        await expect(
          page.getByRole('heading', { level: 1, name: 'My settings for Analyst' }),
        ).toBeVisible();
        await expect(page.getByTestId('defaults-note')).toHaveCount(0);
        await expect(page.locator('select[id$="-model"]')).toHaveValue(company.agent.model);

        // Each system's tools wait behind its chevron.
        const expandJira = page.getByTestId(`system-expand-${seed.systems.jira.id}`);
        await expect(expandJira).toHaveAttribute('aria-expanded', 'false');
        await expandJira.click();
        await expect(expandJira).toHaveAttribute('aria-expanded', 'true');

        // Jira's comment asks: the settings offer as the agent says, ask, or off; never "Allowed".
        const comment = toolRow(page, seed.systems.jira.id, 'comment');
        await expect(comment).toHaveAttribute('data-agent-mode', 'ASK');
        await expect(comment).toContainText('Analyst asks you');
        await expect(comment.locator('app-segmented label')).toHaveText([
          'As the agent says',
          'Ask me',
          'Off',
        ]);
        await expect(comment.locator('input[value=ALLOW]')).toHaveCount(0);
        // Jira's get runs: the reader may make it ask.
        const get = toolRow(page, seed.systems.jira.id, 'get');
        await expect(get).toHaveAttribute('data-agent-mode', 'ALLOW');
        await expect(get).toContainText('Analyst runs it');
        await expect(get.getByTestId('narrowed')).toHaveCount(0);
        await expect(page.getByTestId('settings-who-acts')).toContainText(
          'Jira: via Analysts, read-only',
        );
        await expectNoAxeViolations(page, testInfo);
        if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
        await capture(page, testInfo, 'agent-settings');

        // Narrow: GitHub off for Alice. Who acts and who pays is read again after the save.
        await page
          .getByTestId(`system-switch-${seed.systems.github.id}`)
          .getByRole('switch')
          .click();
        await expect(page.locator(`[data-system="${seed.systems.github.id}"]`)).toContainText(
          'Off for your conversations',
        );
        await get.getByText('Ask me').click();
        await expect(get.getByTestId('narrowed')).toHaveText('Narrowed');
        await expect(get).toHaveAttribute('data-choice', 'ASK');
        const unsaved = page.getByRole('region', { name: 'Unsaved changes' });
        await expect(unsaved.getByTestId('unsaved-count')).toHaveText('2 unsaved changes');
        await expect(unsaved.getByTestId('unsaved-names')).toHaveText('GitHub off · 1 Jira tool');
        await expectNoAxeViolations(page, testInfo);
        const reread = page.waitForResponse(
          (r) => r.url().endsWith(`/agents/${seed.agent.id}/readiness`) && r.ok(),
        );
        await page.getByTestId('save-settings').click();
        await reread;
        await expect(unsaved).toHaveCount(0);
        await expect(page.getByTestId('defaults-note')).toHaveCount(0);
        await expect(page.getByTestId('settings-who-acts')).not.toContainText('GitHub');
        const saved = await alice.get(settingsPath);
        expect(saved.systemsOff).toEqual([seed.systems.github.id]);
        expect(saved.toolModes).toEqual([
          { systemId: seed.systems.jira.id, tool: 'get', mode: 'ASK' },
        ]);

        // Back to the defaults, once confirmed: GitHub and the tool as the agent says, read again. The row stays,
        // with the version accepted (SettingsService.reset), and the model goes back to none, which a budget does
        // not pay for.
        await page.getByTestId('reset-settings').click();
        await expect(page.getByTestId('reset-dialog')).toContainText(
          'The version you accepted stays.',
        );
        await page.getByTestId('confirm-reset').click();
        await expect(page.getByTestId('settings-who-acts')).toContainText(
          'GitHub: your own account',
        );
        await expect(get).toHaveAttribute('data-choice', '');
        await expect(get.getByTestId('narrowed')).toHaveCount(0);
        await expect(page.getByTestId('settings-who-acts')).toContainText(
          'Choose a model in your settings first',
        );
        const reset = await alice.get(settingsPath);
        expect(reset.systemsOff).toEqual([]);
        expect(reset.toolModes).toEqual([]);
        expect(reset.model ?? null).toBeNull();
        if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
      } finally {
        await alice.put(settingsPath, seeded).catch(() => undefined);
      }
    },
  );
});
