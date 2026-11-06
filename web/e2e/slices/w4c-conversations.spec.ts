import type { Page, TestInfo } from '@playwright/test';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  shoot,
  test,
} from '../fixtures';

/**
 * W4c (the plan §5 and §8): the conversations list with its filters, "By subject" and the Owner's "Everyone's", and
 * the run page of a scripted run, against the real server. Each test starts conversations of its own, about a subject
 * key of its own, so that the filters find exactly them among what other specs leave.
 */

const SLICE = 'w4c';

/** A subject key no other test or run uses. */
function uniqueKey(testInfo: TestInfo): string {
  return `W4C-${testInfo.project.name.toUpperCase()}${Date.now().toString(36).toUpperCase()}`;
}

/** axe with no serious or critical finding, no sideways scroll, and the review shot. */
async function check(page: Page, testInfo: TestInfo, shot?: string): Promise<void> {
  await expectNoAxeViolations(page, testInfo);
  await expectNoHorizontalOverflow(page);
  if (shot) {
    if (process.env['E2E_SCREENSHOTS']) {
      await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 15_000 });
    }
    await shoot(page, testInfo, SLICE, shot);
  }
}

test.describe('W4c: conversations and runs', { tag: ['@app', '@phone'] }, () => {
  test.use({ persona: 'alice' });

  test('the list filters by state, whom it waits on and subject, and groups by subject', async ({
    page,
    appPath,
    seed,
    runner,
    startConversation,
    api,
    apiPath,
  }, testInfo) => {
    const key = uniqueKey(testInfo);
    const subject = { systemId: seed.systems.jira.id, key, title: 'Guests cannot check out' };

    // One answered and open, one ended, both about the test's subject; one still working, without it.
    const answered = await startConversation('alice', `Summarise ${key} [e2e:hold]`, { subject });
    await runner.finish(answered.run.runId, { summary: `${key} is about guest checkout.` });
    const ended = await startConversation('alice', `Close out ${key} [e2e:hold]`, { subject });
    await runner.finish(ended.run.runId, { summary: 'Done.' });
    const alice = await api('alice');
    await expect
      .poll(
        async () =>
          (await alice.get(apiPath(`/conversations/${ended.conversation.id}`))).activeRunId,
      )
      .toBeFalsy();
    await alice.post(apiPath(`/conversations/${ended.conversation.id}/end`), {});
    const working = await startConversation(
      'alice',
      `Draft the release notes ${key}-NOTES [e2e:hold]`,
    );

    // Unfiltered: the newest first, each with its agent, subject and whom it waits on.
    await page.goto(appPath('/conversations'));
    await expect(page.getByRole('heading', { level: 1, name: 'Conversations' })).toBeVisible();
    const row = (id: string) =>
      page.locator(`[data-testid=conversation][data-conversation-id="${id}"]`);
    await expect(row(working.conversation.id).getByTestId('waiting-on')).toHaveText('Working');
    await expect(row(answered.conversation.id).getByTestId('conversation-subject')).toHaveText(key);
    await expect(row(ended.conversation.id).getByTestId('conversation-ended')).toHaveText('Ended');
    await check(page, testInfo, 'conversations');

    // The subject's two, then the ended one only; the URL keeps the filters.
    await page.goto(
      appPath(`/conversations?subjectSystemId=${seed.systems.jira.id}&subjectKey=${key}`),
    );
    await expect(page.getByTestId('subject-filter')).toHaveText(`About ${key}`);
    await expect(page.getByTestId('conversation')).toHaveCount(2);
    // "Show" is one pill: a state or whom it waits on (SHOW_FILTERS, conversation-labels.ts).
    const show = (label: string) =>
      page.getByRole('radiogroup', { name: 'Show' }).getByText(label, { exact: true }).click();
    await show('Ended');
    await expect(page).toHaveURL(/state=ENDED/);
    await expect(page.getByTestId('conversation')).toHaveCount(1);
    await expect(row(ended.conversation.id)).toBeVisible();

    // Nothing of the subject waits for a runner: the filtered empty state, and Clear.
    await show('Waiting for a runner');
    await expect(page).toHaveURL(/waitingOn=RUNNER/);
    await expect(page).not.toHaveURL(/state=/);
    await expect(page.getByTestId('conversations-filtered-empty')).toBeVisible();
    await check(page, testInfo, 'conversations-filtered-empty');
    await page.getByTestId('clear-filters').click();
    await expect(page).not.toHaveURL(/state=|waitingOn=|subjectKey=/);
    await expect(row(working.conversation.id)).toBeVisible();

    // Whom it waits on, alone: the working one is there, the answered one not.
    await show('Working');
    await expect(page).toHaveURL(/waitingOn=AGENT/);
    await expect(row(working.conversation.id)).toBeVisible();
    await expect(row(answered.conversation.id)).toHaveCount(0);

    // By subject: the test's subject with its counts; Show conversations narrows the list to it.
    await page.goto(appPath('/conversations?view=subjects'));
    const subjectRow = page.locator(`[data-testid=subject][data-key="${key}"]`);
    await expect(subjectRow.getByTestId('subject-counts')).toContainText(
      '2 conversations · 1 open',
    );
    await check(page, testInfo, 'conversations-by-subject');
    await subjectRow.getByTestId('subject-conversations').click();
    // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp -- built from the test's own values, never input
    await expect(page).toHaveURL(new RegExp(`subjectKey=${key}`));
    await expect(page.getByTestId('conversation')).toHaveCount(2);

    await runner.finish(working.run.runId, { summary: 'Done.' });
  });

  test('with no conversation yet, the page offers the agents one may talk to', async ({
    page,
    appPath,
    seed,
  }, testInfo) => {
    // Alice has conversations by now; the list's one call answers none, the agents are the server's.
    await page.route(/\/api\/companies\/[^/]+\/conversations(\?.*)?$/, (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({ json: { items: [], nextCursor: null } })
        : route.fallback(),
    );
    await page.goto(appPath('/conversations'));
    const empty = page.getByTestId('conversations-empty');
    await expect(empty).toContainText('Talk to an agent');
    const talk = empty.getByTestId('talk-to-agent').filter({ hasText: 'Talk to Analyst' });
    await expect(talk).toBeVisible();
    await expect(talk).toHaveAttribute(
      'href',
      appPath(`/conversations/new?agentId=${seed.agent.id}`),
    );
    await check(page, testInfo, 'conversations-empty');
  });

  test('the Owner reads their own, or everyone’s under a banner', async ({
    ownerPage,
    appPath,
    seed,
    runner,
    startConversation,
  }, testInfo) => {
    const key = uniqueKey(testInfo);
    const mine = await startConversation('alice', `Look at ${key} [e2e:hold]`, {
      subject: { systemId: seed.systems.jira.id, key },
    });
    await runner.finish(mine.run.runId, { summary: 'Looked.' });
    const query = `subjectSystemId=${seed.systems.jira.id}&subjectKey=${key}`;

    // "Mine": Alice's conversation is not the Owner's.
    await ownerPage.goto(appPath(`/conversations?${query}`));
    await expect(ownerPage.getByTestId('conversations-filtered-empty')).toBeVisible();
    await expect(ownerPage.getByTestId('everyone-banner')).toHaveCount(0);

    // "Everyone's": the banner, and Alice named on her row.
    await ownerPage.locator('label[data-value=everyone]').click();
    await expect(ownerPage).toHaveURL(/who=everyone/);
    await expect(ownerPage.getByTestId('everyone-banner')).toContainText(
      'every conversation you open is recorded in the audit log',
    );
    const row = ownerPage.locator(
      `[data-testid=conversation][data-conversation-id="${mine.conversation.id}"]`,
    );
    await expect(row.getByTestId('conversation-person')).toHaveText('Alice Analyst');
    await check(ownerPage, testInfo, 'conversations-everyone');

    // One person's only.
    await row.getByTestId('conversation-person').click();
    await expect(ownerPage).toHaveURL(/personId=/);
    await expect(ownerPage.getByLabel('Person', { exact: true })).toHaveValue(
      seed.people.alice.principalId,
    );
    await expect(
      ownerPage.getByLabel('Person', { exact: true }).locator('option:checked'),
    ).toHaveText('Alice Analyst');
    await expect(row).toBeVisible();
  });

  test('the run page streams a scripted run, names who it is for and who pays, and cancels', async ({
    page,
    appPath,
    runner,
    startConversation,
  }, testInfo) => {
    const { conversation, run } = await startConversation('alice', 'Prepare the brief [e2e:hold]');

    await page.goto(appPath(`/runs/${run.runId}`));
    await expect(page.getByRole('heading', { level: 1, name: 'Analyst' })).toBeVisible();
    await expect(page.locator('app-run-status-badge')).toHaveText('Running');
    await expect(page.getByTestId('run-back')).toHaveAttribute(
      'href',
      appPath(`/conversations/${conversation.id}`),
    );

    // Who acts and who pays, and the host with its kind.
    const panel = page.getByTestId('who-acts-who-pays');
    await expect(panel.locator('[data-part=acts]')).toContainText('Alice Analyst');
    await expect(panel.locator('[data-part=pays]')).toContainText('budget');
    await expect(page.getByTestId('run-host').locator('dd')).toHaveText(
      'e2e-runner, a company host',
    );

    // Live: what the agent says arrives over the stream.
    await runner.message(run.runId, 'Reading SHOP-1 and its comments.');
    await expect(page.getByRole('log', { name: 'Run events' })).toContainText(
      'Reading SHOP-1 and its comments.',
    );
    await check(page, testInfo, 'run-running');

    // It ends: the final status, and the result of its finish.
    await runner.finish(run.runId, { summary: 'The brief is ready.' });
    await expect(page.locator('app-run-status-badge')).toHaveText('Succeeded');
    await expect(page.getByTestId('run-result')).toContainText('The brief is ready.');
    await expect(page.getByTestId('cancel-run')).toHaveCount(0);
    await check(page, testInfo, 'run-succeeded');

    // Another turn, cancelled from its page.
    const next = await startConversation('alice', 'Another brief [e2e:hold]');
    await page.goto(appPath(`/runs/${next.run.runId}`));
    await expect(page.locator('app-run-status-badge')).toHaveText('Running');
    await page.getByTestId('cancel-run').click();
    await expect(page.locator('app-run-status-badge')).toHaveText('Cancelled', { timeout: 30_000 });
    await expect(page.getByTestId('run-cancelled')).toContainText('This turn was cancelled');
    await check(page, testInfo, 'run-cancelled');
  });
});
