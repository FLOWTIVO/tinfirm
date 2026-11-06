import type { Page, TestInfo } from '@playwright/test';
import type { Api } from '../harness/api';
import { expect, expectNoAxeViolations, shoot, test } from '../fixtures';

/**
 * V7 (the plan §8): a budget is spent. Each test is recorded on video.
 *
 * The fake LiteLLM keeps each principal's key with its spend: `litellm.setSpend(principalId, 50)` puts Alice at her
 * $50 budget, so that the relay's next request answers 429 budget_exceeded and the server marks the key spent. Each
 * test puts Alice's key back as it found it: no spend, the company's default budget, `OK`.
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

/** Waits until the server's copy of Alice's key says `keyStatus` (a sync runs after an admin's change). */
async function keyStatus(alice: Api, path: string, status: string): Promise<void> {
  await expect
    .poll(async () => ((await alice.get(path)) as { keyStatus: string }).keyStatus, {
      timeout: 30_000,
    })
    .toBe(status);
}

test.describe('V7: a spent budget', { tag: '@app' }, () => {
  test.afterEach(async ({ api, apiPath, litellm, seed }) => {
    // Alice's budget as the seed left it: no spend, the default budget, the key OK.
    const principalId = seed.people.alice.principalId;
    await litellm.setSpend(principalId, 0);
    const admin = await api('admin');
    await admin.patch(apiPath(`/principals/${principalId}/model-access`), { budgetUsd: 49 });
    await admin.patch(apiPath(`/principals/${principalId}/model-access`), { clearBudget: true });
    await keyStatus(await api('alice'), apiPath('/me/model-access'), 'OK');
  });

  test('Alice’s turn fails with USAGE_LIMIT and the reset time @serial', async ({
    alicePage,
    appPath,
    apiPath,
    api,
    runner,
    litellm,
    seed,
    startConversation,
  }, testInfo) => {
    const { conversation, run } = await startConversation(
      'alice',
      `Write the release notes (${testInfo.project.name} ${Date.now().toString(36)})`,
    );
    await alicePage.goto(appPath(`/conversations/${conversation.id}`));
    await expect(alicePage.getByTestId('live-turn-working')).toContainText('Working');

    // Her spend reaches her budget: the relay refuses the run's model request, and the key is spent.
    await litellm.setSpend(seed.people.alice.principalId, seed.gateway.defaultBudgetUsd);
    const model = await runner.callModel(run.runId);
    expect(model.status).toBe(429);
    await keyStatus(await api('alice'), apiPath('/me/model-access'), 'BUDGET_SPENT');
    await runner.finish(run.runId, {
      outcome: 'FAILED',
      failureClass: 'USAGE_LIMIT',
      failureMessage: 'The model gateway refused the request: the budget is spent.',
      summary: null,
      result: null,
    });

    // The failure in words, with the reset and a link to the budget; neutral, not orange.
    const status = alicePage.getByTestId('conversation-status');
    await expect(status).toHaveAttribute('data-failure', 'USAGE_LIMIT');
    await expect(status).toContainText('The last turn failed: Usage limit reached.');
    await expect(status.getByTestId('failure-detail')).toHaveText(
      /^Your budget is spent; it resets on .+\.$/,
    );
    await expect(status.getByTestId('failure-fix')).toHaveAttribute(
      'href',
      appPath('/accounts?tab=budget'),
    );
    await expect(alicePage.getByTestId('conversation-state')).toHaveText('Turn failed');
    await expectNoAxeViolations(alicePage, testInfo);
    await capture(alicePage, testInfo, 'usage-limit');
  });

  test('her second conversation waits for the reset, and the inbox lists it @serial', async ({
    alicePage,
    appPath,
    api,
    apiPath,
    litellm,
    seed,
  }, testInfo) => {
    const principalId = seed.people.alice.principalId;
    const alice = await api('alice');
    // Spent, as the relay found it in the test above: an admin's change syncs the key from LiteLLM.
    await litellm.setSpend(principalId, seed.gateway.defaultBudgetUsd);
    await (
      await api('admin')
    ).patch(apiPath(`/principals/${principalId}/model-access`), {
      budgetUsd: seed.gateway.defaultBudgetUsd,
    });
    await keyStatus(alice, apiPath('/me/model-access'), 'BUDGET_SPENT');

    // Before the start, the page says the first turn waits.
    await alicePage.goto(appPath(`/conversations/new?agentId=${seed.agent.id}`));
    await expect(alicePage.getByTestId('readiness-waits').first()).toContainText(
      'You can start; the first turn waits until your budget resets on',
    );
    const message = `Summarise SHOP-4 (${testInfo.project.name} ${Date.now().toString(36)})`;
    await alicePage.getByTestId('first-message').fill(message);
    await alicePage.getByTestId('start-conversation').click();
    await expect(alicePage).toHaveURL(/\/conversations\/[0-9a-f-]{36}$/);
    const conversationId = alicePage.url().split('/').at(-1)!;

    try {
      // Neutral: waiting for her budget to reset, with the link to it.
      const queued = alicePage.getByTestId('live-turn-queued');
      await expect(queued).toHaveAttribute('data-reason', 'BUDGET_SPENT');
      await expect(queued.getByTestId('live-turn-reason')).toHaveText(
        /^Waiting for your budget to reset on .+\.$/,
      );
      await expect(queued.getByTestId('live-turn-fix')).toHaveAttribute(
        'href',
        appPath('/accounts?tab=budget'),
      );
      await expect(alicePage.getByTestId('conversation-state')).toHaveText(
        'Waiting · Budget spent',
      );
      await expect(alicePage.locator('main .text-human')).toHaveCount(0);
      await expectNoAxeViolations(alicePage, testInfo);
      await capture(alicePage, testInfo, 'budget-spent');

      // Her inbox lists it as waiting.
      await expect
        .poll(async () =>
          (
            (await alice.get(apiPath('/inbox'))) as {
              items: { kind: string; conversation?: { id: string } | null }[];
            }
          ).items
            .filter((item) => item.conversation?.id === conversationId)
            .map((item) => item.kind),
        )
        .toContain('WAITING');
    } finally {
      await alice.post(apiPath(`/conversations/${conversationId}/cancel`));
      await alice.post(apiPath(`/conversations/${conversationId}/end`));
    }
  });

  test('Bob’s conversation with the same agent runs @serial', async ({
    api,
    apiPath,
    litellm,
    runner,
    seed,
    startConversation,
  }, testInfo) => {
    const principalId = seed.people.alice.principalId;
    await litellm.setSpend(principalId, seed.gateway.defaultBudgetUsd);
    await (
      await api('admin')
    ).patch(apiPath(`/principals/${principalId}/model-access`), {
      budgetUsd: seed.gateway.defaultBudgetUsd,
    });
    await keyStatus(await api('alice'), apiPath('/me/model-access'), 'BUDGET_SPENT');

    // Bob pays from his own budget: the runner is assigned his turn at once.
    const { run } = await startConversation(
      'bob',
      `Summarise SHOP-5 (${testInfo.project.name} ${Date.now().toString(36)})`,
    );
    expect(run.state).toBe('running');
    await runner.finish(run.runId, { summary: 'Done.' });
  });
});
