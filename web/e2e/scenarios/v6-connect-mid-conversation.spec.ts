import type { Locator, Page, TestInfo } from '@playwright/test';
import type { Api } from '../harness/api';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  shoot,
  test,
} from '../fixtures';

/**
 * V6 (the plan §8): Dave connects his account in the middle of a conversation, through W1's binding
 * card; Carol, bound through the grant, connects her own. On video; the phone project runs it too.
 *
 * Seeded: Dave holds no account and is in no group with a grant; the stubs know gh-token-dave and jira-token-dave as
 * dave and gh-token-carol as carol, so that a pasted token is accepted. The agent's workspace is acme/shop on GitHub,
 * so a conversation binds GitHub when it starts; Jira binds when the agent first calls it.
 */
// Recorded as test evidence (the plan §11).
test.use({ video: 'on' });

/**
 * A review screenshot of the whole page, through `shoot()`: from the top, laid out at the page's full height. A toast
 * would stand over the top bar on a phone, so it waits for toasts to go.
 */
async function capture(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  if (!process.env['E2E_SCREENSHOTS']) return;
  await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 15_000 });
  await page.evaluate(() => document.fonts.ready);
  await shoot(page, testInfo, 'w4a', name);
}

/** Starts a conversation from the new conversation page; answers its id. */
async function start(page: Page, path: string, message: string): Promise<string> {
  await page.goto(path);
  await page.getByTestId('first-message').fill(message);
  await page.getByTestId('start-conversation').click();
  await expect(page).toHaveURL(/\/conversations\/[0-9a-f-]{36}$/);
  return page.url().split('/').at(-1)!;
}

/**
 * The Connect card of a system where it was raised, at the end of the thread. W4b's pinned cards repeat it above the
 * composer while it is out of view.
 */
function card(page: Page, systemId: string): Locator {
  return page
    .getByTestId('conversation-cards')
    .locator(`[data-testid=binding-card][id="binding-${systemId}"]`);
}

/** Pastes a token into a Connect card with the keyboard alone, from the card's region. */
async function pasteWithTheKeyboard(page: Page, connect: Locator, token: string): Promise<void> {
  await connect.getByTestId('binding-paste-token').focus();
  await page.keyboard.press('Enter');
  await expect(connect.getByTestId('binding-token')).toBeFocused();
  await page.keyboard.type(token);
  await page.keyboard.press('Enter');
}

async function disconnect(api: Api, path: string): Promise<void> {
  await api.delete(path, { ok: [204, 404] });
}

test.describe('V6: connecting mid-conversation', { tag: ['@app', '@phone'] }, () => {
  test('Dave pastes a token in the Connect card and the parked call goes on as his own account @serial', async ({
    pageAs,
    api,
    apiPath,
    appPath,
    runner,
    stubs,
    seed,
  }, testInfo) => {
    const dave = await pageAs('dave');
    const daveApi = await api('dave');
    const github = seed.systems.github.id;
    const jira = seed.systems.jira.id;
    try {
      const conversationId = await start(
        dave,
        appPath(`/conversations/new?agentId=${seed.agent.id}`),
        `Dave looks at SHOP-1 (${testInfo.project.name} ${Date.now().toString(36)})`,
      );

      // GitHub, the workspace's system, waits for Dave's own account: the turn waits, and the card asks.
      const githubCard = card(dave, github);
      await expect(githubCard).toHaveAttribute('data-reason', 'NONE');
      await expect(githubCard).toContainText('Connect GitHub to continue');
      await expect(githubCard).toHaveClass(/border-human/);
      await expect(dave.getByTestId('conversation-state')).toHaveText('Needs you · Account');
      await expectNoAxeViolations(dave, testInfo);
      if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(dave);
      await pasteWithTheKeyboard(dave, githubCard, 'gh-token-dave');
      await expect(githubCard).toHaveCount(0);
      await expect(dave.getByTestId('who-acts-who-pays').locator('[data-part=acts]')).toContainText(
        'GitHub: your own account',
      );

      // The turn runs; the agent calls Jira, which Dave has not connected: the call is parked, and a card asks.
      const run = await runner.awaitRun({ conversationId, state: 'running' });
      await stubs.clearCalls();
      const ack = await runner.tool(run.runId, { tool: 'jira_get', endTurn: true });
      expect(ack.status).toBe('PENDING');
      const jiraCard = card(dave, jira);
      await expect(jiraCard).toContainText('Connect Jira to continue');
      await expect(jiraCard).toContainText('Analyst asked to use Jira.');
      await expect(dave.getByTestId('conversation-state')).toHaveText('Needs you · Account');
      await expectNoAxeViolations(dave, testInfo);
      await expect(dave.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 15_000 });
      await capture(dave, testInfo, 'connect-card');
      await pasteWithTheKeyboard(dave, jiraCard, 'jira-token-dave');
      await expect(jiraCard).toHaveCount(0);

      // The parked call goes on as Dave's own Jira account, and the next turn starts.
      await expect
        .poll(async () => (await stubs.calls('jira')).map((call) => call.identity))
        .toContain('dave');
      const next = await runner.awaitRun({ conversationId, state: 'running' });
      expect(next.runId).not.toBe(run.runId);
      await expect(dave.getByTestId('who-acts-who-pays').locator('[data-part=acts]')).toContainText(
        'Jira: your own account',
      );
      await runner.finish(next.runId, { summary: 'SHOP-1 is about the harness.' });
      await expect(dave.getByTestId('turn-answer').last()).toContainText(
        'SHOP-1 is about the harness.',
      );
    } finally {
      await disconnect(daveApi, apiPath(`/me/accounts/${github}`));
      await disconnect(daveApi, apiPath(`/me/accounts/${jira}`));
    }
  });

  test('the OAuth path returns to the conversation through the stub @serial', async ({
    pageAs,
    api,
    apiPath,
    appPath,
    runner,
    stubs,
    seed,
  }, testInfo) => {
    const admin = await api('admin');
    const daveApi = await api('dave');
    const github = seed.systems.github.id;
    const app = apiPath(`/systems/${github}/app`);
    await admin.put(app, { clientId: 'e2e-github-app', clientSecret: 'e2e-github-secret' });
    try {
      await stubs.setOAuthIdentity('github', 'dave');
      const dave = await pageAs('dave');
      const conversationId = await start(
        dave,
        appPath(`/conversations/new?agentId=${seed.agent.id}`),
        `Dave signs in (${testInfo.project.name} ${Date.now().toString(36)})`,
      );
      const githubCard = card(dave, github);
      await githubCard.getByTestId('binding-sign-in').click();

      // Through the stub's authorization page and the server's callback, back to the conversation, which continues
      // the binding and drops the outcome from the URL.
      await expect(dave).toHaveURL(appPath(`/conversations/${conversationId}`));
      await expect(githubCard).toHaveCount(0);
      await expect(dave.getByTestId('who-acts-who-pays').locator('[data-part=acts]')).toContainText(
        'GitHub: your own account',
      );
      const run = await runner.awaitRun({ conversationId, state: 'running' });
      await runner.finish(run.runId, { summary: 'Signed in.' });
    } finally {
      await stubs.setOAuthIdentity('github', 'oauth-user');
      await disconnect(daveApi, apiPath(`/me/accounts/${github}`));
      await admin.delete(app, { ok: [204, 404] });
    }
  });

  test('Carol connects her own account: this conversation keeps the grant, a new one binds her own @serial', async ({
    pageAs,
    api,
    apiPath,
    appPath,
    runner,
    seed,
  }, testInfo) => {
    const carol = await pageAs('carol');
    const carolApi = await api('carol');
    const github = seed.systems.github.id;
    const tag = `${testInfo.project.name} ${Date.now().toString(36)}`;
    try {
      const first = await start(
        carol,
        appPath(`/conversations/new?agentId=${seed.agent.id}`),
        `Carol through the grant (${tag})`,
      );
      const acts = carol.getByTestId('who-acts-who-pays').locator('[data-part=acts]');
      await expect(acts).toContainText('GitHub: via Analysts, read-only');
      const run = await runner.awaitRun({ conversationId: first, state: 'running' });
      await runner.finish(run.runId, { summary: 'Read through the grant.' });

      // Her own account, connected on Accounts.
      await carolApi.put(apiPath(`/me/accounts/${github}/token`), { token: 'gh-token-carol' });

      // This conversation keeps the grant it bound…
      await carol.reload();
      await expect(acts).toContainText('GitHub: via Analysts, read-only');
      // …and a new one binds her own account.
      const second = await start(
        carol,
        appPath(`/conversations/new?agentId=${seed.agent.id}`),
        `Carol as herself (${tag})`,
      );
      await expect(acts).toContainText('GitHub: your own account');
      const next = await runner.awaitRun({ conversationId: second, state: 'running' });
      await runner.finish(next.runId, { summary: 'Read as Carol.' });
    } finally {
      await disconnect(carolApi, apiPath(`/me/accounts/${github}`));
    }
  });
});
