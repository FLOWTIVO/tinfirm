import type { Page, TestInfo } from '@playwright/test';
import {
  expect,
  expectNoAxeViolations,
  expectNoHorizontalOverflow,
  shoot,
  test,
} from '../fixtures';

/**
 * W4a, conversations (the plan §4.1, §4.2 and §8): the new conversation page and the conversation page, with its live
 * turn and its composer. W4b's cards and inbox are in w4b-inbox.spec.ts, W4c's list, files, outputs and run page in
 * w4c-conversations.spec.ts. The member flows run on the phone too.
 */

/** A message of this test's own: its first line is the conversation's title. */
function unique(text: string, testInfo: TestInfo): string {
  return `${text} (${testInfo.project.name} ${Date.now().toString(36)})`;
}

/**
 * A review screenshot of the whole page, through `shoot()`: from the top, laid out at the page's full height. A toast
 * would stand over the top bar on a phone, so it waits for toasts to go.
 */
async function capture(
  page: Page,
  testInfo: TestInfo,
  name: string,
  options: { dark?: boolean } = {},
): Promise<void> {
  if (!process.env['E2E_SCREENSHOTS']) return;
  await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 15_000 });
  await page.evaluate(() => document.fonts.ready);
  await shoot(page, testInfo, 'w4a', name, { dark: options.dark });
}

/** The checks of every page of the slice: axe, and on the phone no sideways scroll. */
async function check(page: Page, testInfo: TestInfo): Promise<void> {
  await expectNoAxeViolations(page, testInfo);
  if (testInfo.project.name === 'phone') await expectNoHorizontalOverflow(page);
}

/**
 * In a real viewport, scrolled to `y` (by default halfway down): the top bar stays at the top, the composer sticks to
 * the bottom while the thread goes on below, and only the document scrolls (no second scrollbar).
 */
async function checkScrolling(page: Page, y?: number): Promise<void> {
  await page.evaluate(
    (to) => window.scrollTo(0, to ?? document.documentElement.scrollHeight / 2),
    y,
  );
  const layout = await page.evaluate(() => {
    const bar = document.querySelector('main header')!.getBoundingClientRect();
    const dock = document.querySelector('[data-testid=composer-dock]')?.getBoundingClientRect();
    const thread = document.querySelector('[data-testid=thread]')!.getBoundingClientRect();
    const scrollers = [...document.querySelectorAll('main *')]
      .filter((element) => {
        const overflow = getComputedStyle(element).overflowY;
        return /auto|scroll/.test(overflow) && element.scrollHeight > element.clientHeight + 1;
      })
      .map((element) => element.getAttribute('data-testid') ?? element.tagName);
    return {
      scrolled: window.scrollY,
      barTop: bar.top,
      dockBottom: dock ? Math.round(dock.bottom) : null,
      threadBelow: thread.bottom > window.innerHeight,
      innerHeight: window.innerHeight,
      scrollers,
    };
  });
  expect(layout.scrolled, 'the page scrolls').toBeGreaterThan(0);
  expect(layout.barTop, 'the top bar stays at the top').toBe(0);
  if (layout.dockBottom !== null && layout.threadBelow) {
    expect(layout.dockBottom, 'the composer sticks to the bottom').toBe(layout.innerHeight);
  }
  expect(layout.scrollers, 'only the document scrolls').toEqual([]);
}

test.describe('W4: conversations', { tag: ['@app', '@phone'] }, () => {
  test.use({ persona: 'alice' });

  test('start a conversation: who acts and who pays before the first message', async ({
    page,
    appPath,
    runner,
    seed,
  }, testInfo) => {
    await page.goto(appPath(`/conversations/new?agentId=${seed.agent.id}`));
    await expect(page.getByRole('heading', { level: 1, name: 'New conversation' })).toBeVisible();
    await expect(page.getByTestId('agent-select').locator('select')).toHaveValue(seed.agent.id);
    const panel = page.getByTestId('readiness').getByTestId('who-acts-who-pays');
    await expect(panel.locator('[data-part=acts]')).toContainText('GitHub: your own account');
    await expect(panel.locator('[data-part=acts]')).toContainText('Jira: via Analysts, read-only');
    await expect(panel.locator('[data-part=pays]')).toContainText('Your budget');
    await expect(panel.locator('[data-part=runs]')).toContainText('A company host');
    await expect(page.locator(`[data-repository="${seed.repository.id}"] input`)).toBeChecked();
    await expect(page.getByTestId('start-conversation')).toBeDisabled();

    const message = unique('Summarise SHOP-1 for the release notes', testInfo);
    await page.getByTestId('first-message').fill(message);
    await check(page, testInfo);
    await capture(page, testInfo, 'new-conversation');

    await page.getByTestId('start-conversation').click();
    await expect(page).toHaveURL(/\/conversations\/[0-9a-f-]{36}$/);
    const conversationId = page.url().split('/').at(-1)!;
    await expect(page.getByTestId('conversation-title')).toHaveText(message);
    const run = await runner.awaitRun({ conversationId });

    // The live turn: the green working line, its latest step, and Show work.
    const live = page.getByTestId('live-turn');
    await expect(live.getByTestId('live-turn-working')).toContainText('Working');
    await expect(page.getByTestId('conversation-state')).toHaveText('Working');
    await runner.message(run.runId, 'Reading SHOP-1 and the shop’s checkout.');
    await expect(live.getByTestId('live-turn-step')).toContainText(
      'Reading SHOP-1 and the shop’s checkout.',
    );
    await runner.event(run.runId, 'TOOL_CALL', {
      toolCallId: 'e2e-read',
      title: 'Read src/checkout.ts',
      kind: 'read',
      status: 'completed',
    });
    await expect(live.getByTestId('live-turn-step')).toContainText('Read src/checkout.ts');
    await live.getByTestId('live-turn-show-work').click();
    await expect(live.getByTestId('live-turn-work')).toContainText('Read 1 file');
    await expect(page.getByTestId('composer-hint')).toHaveText(
      'Analyst is at work: it takes your message after its current step, or in the next turn.',
    );
    await check(page, testInfo);
    await capture(page, testInfo, 'conversation-working');

    // The answer, with the turn's status line and "View run".
    await runner.finish(run.runId, {
      summary: 'SHOP-1 adds a **guest checkout**: guests pay without an account.',
    });
    await expect(page.getByTestId('live-turn')).toHaveCount(0);
    const turn = page.getByTestId('turn').last();
    await expect(turn.getByTestId('turn-answer')).toContainText(
      'SHOP-1 adds a guest checkout: guests pay without an account.',
    );
    await expect(turn.getByTestId('turn-status')).toContainText('Completed');
    await expect(turn.getByTestId('turn-run')).toHaveAttribute(
      'href',
      appPath(`/runs/${run.runId}`),
    );
    await expect(turn.getByTestId('turn-work-toggle')).toContainText('Worked for');
    await expect(turn.getByTestId('turn-work-toggle')).toHaveAttribute('aria-expanded', 'false');
    await turn.getByTestId('turn-work-toggle').click();
    await expect(turn.getByTestId('turn-work')).toContainText('Read 1 file');
    await expect(page.getByTestId('conversation-state')).toHaveText('Idle');
    await check(page, testInfo);
    await capture(page, testInfo, 'conversation');

    // The side column: beside the thread from lg, a sheet behind "Details" on a phone.
    if (testInfo.project.name === 'phone') {
      await page.getByTestId('conversation-details').click();
      const details = page.getByRole('dialog', { name: 'Details' });
      await expect(details.getByTestId('side-workspace')).toContainText('acme/shop');
      await expect(details.getByTestId('side-turns')).toContainText('Turn 1');
      // What the phone's head leaves out: who acts and who pays in full, and who else read it.
      await expect(details.locator('[data-part=pays]')).toContainText('Your budget');
      await expect(details.getByTestId('conversation-reads')).toContainText(
        'Nobody but you has read it.',
      );
      await check(page, testInfo);
      await page.keyboard.press('Escape');
      await expect(details).toBeHidden();
    } else {
      await expect(page.getByTestId('conversation-details')).toBeHidden();
      await expect(page.locator('aside').getByTestId('side-workspace')).toContainText('acme/shop');
      // Who acts and who pays in full, in place; who else read it, behind the eye.
      await page.getByTestId('who-acts-details').click();
      await expect(page.locator('#who-acts-panel [data-part=pays]')).toContainText('Your budget');
      await page.getByTestId('owner-notice').click();
      await expect(page.getByTestId('reads-popover')).toContainText('Nobody but you has read it.');
      await check(page, testInfo);
      await page.keyboard.press('Escape');
      await expect(page.getByTestId('reads-popover')).toHaveCount(0);
    }
  });

  test('a message sent while the turn runs waits for the next turn', async ({
    page,
    appPath,
    runner,
    startConversation,
  }, testInfo) => {
    const { conversation, run } = await startConversation(
      'alice',
      unique('Draft the release notes', testInfo),
    );
    await page.goto(appPath(`/conversations/${conversation.id}`));
    await expect(page.getByTestId('live-turn-working')).toContainText('Working');

    await page.getByTestId('composer-input').fill('Mention the guest checkout first.');
    await page.getByTestId('composer-input').press('Control+Enter');
    const waiting = page.locator('[data-testid=message][data-waiting]');
    await expect(waiting).toContainText('Mention the guest checkout first.');
    await expect(waiting.getByTestId('message-waiting')).toHaveText('Waits for the next turn');
    await expect(page.getByTestId('composer-input')).toHaveValue('');

    // The turn ends; the waiting message starts the next one.
    await runner.finish(run.runId, { summary: 'A first draft.' });
    const next = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
    expect(next.runId).not.toBe(run.runId);
    expect(next.request).toContain('Mention the guest checkout first.');
    await expect(page.getByTestId('live-turn')).toHaveAttribute('data-run-id', next.runId);
    await expect(page.locator('[data-testid=message][data-waiting]')).toHaveCount(0);
    await runner.finish(next.runId, { summary: 'The guest checkout leads.' });
    await expect(page.getByTestId('turn-answer').last()).toContainText('The guest checkout leads.');
  });

  test('answer a question', async ({ page, appPath, runner, startConversation }, testInfo) => {
    const { conversation, run } = await startConversation(
      'alice',
      unique('Prepare the brief of SHOP-2', testInfo),
    );
    await page.goto(appPath(`/conversations/${conversation.id}`));
    await runner.ask(run.runId, {
      question: 'Should the brief cover the mobile app too?',
      options: ['Yes', 'No, the web shop only'],
    });
    await expect(page.getByTestId('conversation-state')).toHaveText('Needs you · Question');
    await expect(page.getByTestId('conversation-state')).toHaveAttribute('data-tone', 'human');
    // The question's card stands in the thread, and pinned above the composer while it is out of view; the
    // composer answers it too.
    await expect(page.locator('[data-testid=turn] app-decision-card')).toContainText(
      'Should the brief cover the mobile app too?',
    );
    const answers = page.getByTestId('answers-open-questions');
    await expect(answers).toBeVisible();
    await answers.check();
    await expect(page.getByTestId('composer-hint')).toHaveText(
      'Analyst gets this as the answer to its open questions and goes on.',
    );
    await page.getByTestId('composer-input').fill('Yes, the mobile app too.');
    await page.getByTestId('composer-send').click();

    const next = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
    expect(next.runId).not.toBe(run.runId);
    expect(next.request).toContain('Yes, the mobile app too.');
    await expect(page.getByTestId('conversation-state')).toHaveText('Working');
    await check(page, testInfo);
  });

  test('cancel the turn, retry it, and end the conversation', async ({
    page,
    appPath,
    runner,
    startConversation,
  }, testInfo) => {
    const { conversation, run } = await startConversation(
      'alice',
      unique('Check the shop’s open pull requests', testInfo),
    );
    await page.goto(appPath(`/conversations/${conversation.id}`));
    await expect(page.getByTestId('live-turn-working')).toContainText('Working');

    // Cancel turn: the host is told and ends the turn cancelled; Retry is offered.
    await page.getByTestId('conversation-menu').click();
    await page.getByTestId('cancel-turn').click();
    const status = page.getByTestId('conversation-status');
    await expect(status).toHaveAttribute('data-phase', 'cancelled');
    await expect(status).toContainText('The turn was cancelled.');
    await expect(page.getByTestId('conversation-state')).toHaveText('Cancelled');

    // Retry: the same request again, as a new turn.
    await status.getByTestId('retry').click();
    const retried = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
    expect(retried.runId).not.toBe(run.runId);
    await expect(page.getByTestId('live-turn')).toHaveAttribute('data-run-id', retried.runId);
    await runner.finish(retried.runId, { summary: 'Two pull requests are open.' });
    await expect(page.getByTestId('turn-answer').last()).toContainText(
      'Two pull requests are open.',
    );

    // End, after a confirm: read only, no composer.
    await page.getByTestId('conversation-menu').click();
    await page.getByTestId('end-conversation').click();
    const confirm = page.getByRole('alertdialog', { name: 'End this conversation?' });
    await expect(confirm).toBeVisible();
    await check(page, testInfo);
    await confirm.getByTestId('confirm-end').click();
    await expect(confirm).toBeHidden();
    await expect(page.getByTestId('conversation-state')).toHaveText('Ended');
    await expect(page.getByTestId('composer')).toHaveCount(0);
    await expect(page.getByTestId('conversation-status')).toContainText(
      'It is read only; its outputs and files stay.',
    );
    await check(page, testInfo);
    await capture(page, testInfo, 'conversation-ended');
  });

  test('the owner reads someone else’s conversation, and cannot write in it', async ({
    ownerPage,
    appPath,
    runner,
    startConversation,
  }, testInfo) => {
    const { conversation, run } = await startConversation(
      'alice',
      unique('Compare the shop’s two checkouts', testInfo),
    );
    await runner.finish(run.runId, { summary: 'The guest checkout skips the address check.' });

    await ownerPage.goto(appPath(`/conversations/${conversation.id}`));
    const banner = ownerPage.getByTestId('owner-banner');
    await expect(banner).toContainText(
      'Alice Analyst’s conversation. Only Alice Analyst writes, decides and connects accounts here.',
    );
    await expect(banner).toContainText('Your read is recorded.');
    // Where the composer would be.
    await expect(ownerPage.getByTestId('composer-dock').getByTestId('owner-banner')).toBeVisible();
    await expect(ownerPage.getByTestId('turn-answer')).toContainText(
      'The guest checkout skips the address check.',
    );
    await expect(ownerPage.getByTestId('composer')).toHaveCount(0);
    await expect(ownerPage.getByTestId('rename')).toHaveCount(0);
    await expect(
      ownerPage.getByTestId('who-acts-who-pays').locator('[data-part=pays]'),
    ).toContainText('Alice Analyst’s budget');
    await expect(
      ownerPage.getByTestId('who-acts-who-pays').locator('[data-part=acts]'),
    ).toContainText('GitHub: Alice Analyst’s own account');
    // Cancel, retry and end stay, as the API allows.
    await ownerPage.getByTestId('conversation-menu').click();
    await expect(ownerPage.getByTestId('end-conversation')).toBeVisible();
    await ownerPage.keyboard.press('Escape');
    await check(ownerPage, testInfo);
    await capture(ownerPage, testInfo, 'conversation-owner');
  });

  test('the conversation page with its cards, outputs and files, in both themes', async ({
    page,
    appPath,
    runner,
    startConversation,
  }, testInfo) => {
    const { conversation, run } = await startConversation(
      'alice',
      unique('Summarise SHOP-3', testInfo),
    );
    await runner.event(run.runId, 'TOOL_CALL', {
      toolCallId: 'e2e-search',
      title: 'Search for "guest" in src',
      kind: 'search',
      status: 'completed',
    });
    // A summary long enough that, scrolled into the thread, the next turn's card is out of view.
    await runner.finish(run.runId, {
      summary: [
        'SHOP-3 asks for a **saved address** at checkout.',
        '',
        '- Guests keep one address.',
        '- Signed-in customers keep up to five.',
        '- The default address is filled in at checkout.',
        '- An address can be renamed, changed or removed.',
        '- Removing the default makes the oldest one the default.',
        '- Addresses are checked against the postcode lookup.',
        '- A failed lookup keeps the address and marks it unchecked.',
        '- Unchecked addresses ask for a confirmation at checkout.',
        '- The account page lists the addresses.',
        '- The order confirmation names the address used.',
        '- Exports include the saved addresses.',
        '- Deleting the account deletes its addresses.',
      ].join('\n'),
    });
    await page.goto(appPath(`/conversations/${conversation.id}`));
    const side = page.locator('aside [data-testid=conversation-side]');
    await expect(side.getByTestId('conversation-outputs')).toBeAttached();
    // A file of Alice's, through W4c's files in the side column (the sheet's on a phone).
    await side.getByTestId('file-input').setInputFiles({
      name: 'checkout-notes.md',
      mimeType: 'text/markdown',
      buffer: Buffer.from('# Checkout\n\nGuests keep one saved address.\n'),
    });
    await expect(side.getByTestId('conversation-files')).toContainText('checkout-notes.md');

    // The next turn asks a question: its card stands in the thread.
    await page.getByTestId('composer-input').fill('Which pages change?');
    await page.getByTestId('composer-send').click();
    const next = await runner.awaitRun({ conversationId: conversation.id, state: 'running' });
    await runner.message(next.runId, 'Looking at the checkout pages.');
    await runner.ask(next.runId, {
      question: 'Should the saved address cover the mobile app too?',
      options: ['Yes', 'No, the web shop only'],
    });
    const card = page.locator('[data-testid=turn] app-decision-card');
    await expect(card).toContainText('Should the saved address cover the mobile app too?');
    await expect(page.getByTestId('conversation-state')).toHaveText('Needs you · Question');
    await check(page, testInfo);
    await checkScrolling(page);
    await page.evaluate(() => window.scrollTo(0, 0));
    await capture(page, testInfo, 'conversation-cards');

    await page.emulateMedia({ colorScheme: 'dark' });
    await check(page, testInfo);
    await page.emulateMedia({ colorScheme: null });
    await capture(page, testInfo, 'conversation-cards', { dark: true });

    // Above the thread nothing is pinned: the header says what waits, and the composer stays on the screen.
    const pinned = page.getByTestId('pinned-cards');
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(pinned).toBeHidden();

    // Scrolled into the thread, away from the card, the card is pinned above the composer, which stays at the
    // bottom; "Where it was raised" goes back to it.
    const threadTop = await page
      .getByTestId('thread')
      .evaluate((thread) => Math.ceil(thread.getBoundingClientRect().top + window.scrollY));
    await page.evaluate((y) => window.scrollTo(0, y), threadTop + 1);
    await expect(card).not.toBeInViewport();
    await expect(pinned).toBeVisible();
    await expect(pinned).toContainText('Should the saved address cover the mobile app too?');
    // On a phone, or a screen 720px high, the cards would cover most of it: one line says what waits, with its
    // action. Both projects' screens are that short.
    const compact =
      (page.viewportSize()?.height ?? 1000) <= 800 || testInfo.project.name === 'phone';
    if (compact) {
      await expect(pinned).toHaveAttribute('data-compact', 'true');
      await expect(pinned.getByTestId('pinned-act')).toHaveText('Answer');
      expect((await pinned.boundingBox())!.height).toBeLessThan(64);
    }
    await checkScrolling(page, threadTop + 1);
    await expect(page.getByTestId('composer-input')).toBeInViewport();
    await expectNoAxeViolations(page, testInfo);
    if (process.env['E2E_SCREENSHOTS']) {
      await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 15_000 });
      await shoot(page, testInfo, 'w4a', 'conversation-pinned', { fullPage: false });
    }
    // Dispatched rather than clicked: scrolling the button into view first would bring the card back into view and
    // unpin it under the pointer.
    if (compact) {
      // The line unfolds the card, and folds it again.
      await pinned.getByTestId('pinned-summary').dispatchEvent('click');
      await expect(pinned.locator('app-decision-card')).toBeVisible();
      await pinned.getByTestId('pinned-fold').dispatchEvent('click');
      await expect(pinned.locator('app-decision-card')).toHaveCount(0);
    }
    await pinned.getByTestId(compact ? 'pinned-act' : 'pinned-jump').dispatchEvent('click');
    await expect(card).toBeInViewport();
    await expect(pinned).toBeHidden();
  });
});
