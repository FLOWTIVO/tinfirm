import { type Browser, expect, type Page, test } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import type { CurrentUser } from '../src/app/api/generated/models';
import { aCurrentUser, anAdmin, IDS } from '../src/app/testing/fixtures';
import { mockApi } from './mock-api';

/** Where the slice's screenshots go (`docs/screenshots/web/<slice>/`). */
const OUT = join(__dirname, '../../docs/screenshots/web/w0');
const DESKTOP = { width: 1280, height: 800 };
const PHONE = { width: 390, height: 844 };

type Persona = 'member' | 'admin';
type Scheme = 'light' | 'dark';

const USERS: Record<Persona, CurrentUser> = { member: aCurrentUser(), admin: anAdmin() };
const C = `/c/${IDS.company}`;

/** Every page of the plan's §3 to §5 that W0 routes, with a query where a tab differs. */
const PAGES = [
  `${C}/inbox`,
  `${C}/conversations`,
  `${C}/conversations/new`,
  `${C}/conversations/${IDS.conversation}`,
  `${C}/runs/${IDS.run}`,
  `${C}/agents`,
  `${C}/agents/new`,
  `${C}/agents/${IDS.agent}`,
  `${C}/agents/${IDS.agent}/edit`,
  `${C}/agents/${IDS.agent}/settings`,
  `${C}/triggers`,
  `${C}/triggers/new`,
  `${C}/triggers/t1`,
  `${C}/triggers/t1/edit`,
  `${C}/accounts`,
  `${C}/accounts?tab=budget`,
  `${C}/accounts?tab=hosts`,
  `${C}/systems`,
  `${C}/systems/${IDS.github}`,
  `${C}/service-identities`,
  `${C}/service-identities/${IDS.svcReader}`,
  `${C}/model-access`,
  `${C}/model-access?tab=budgets`,
  `${C}/runner-hosts`,
  `${C}/members`,
  `${C}/members?tab=groups`,
  `${C}/audit`,
  `${C}/settings`,
  `${C}/settings/limits`,
  `${C}/settings/memory`,
  `${C}/settings/secrets`,
  `${C}/settings/licence`,
  '/me',
  '/link/chat',
];

async function open(browser: Browser, persona: Persona, scheme: Scheme, phone: boolean) {
  const context = await browser.newContext({
    viewport: phone ? PHONE : DESKTOP,
    deviceScaleFactor: phone ? 2 : 1,
    isMobile: phone,
    hasTouch: phone,
    colorScheme: scheme,
    reducedMotion: 'reduce',
  });
  const page = await context.newPage();
  const api = await mockApi(page, USERS[persona]);
  return { context, page, api };
}

/** Opens a page and waits until it drew its heading and its requests settled. */
async function visit(page: Page, url: string): Promise<void> {
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.locator('main h1').first().waitFor();
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => document.fonts.ready);
}

async function shoot(page: Page, name: string): Promise<void> {
  mkdirSync(OUT, { recursive: true });
  await page.screenshot({ path: join(OUT, `${name}.png`) });
}

async function openNavigation(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Toggle navigation' }).click();
  await page
    .getByRole('dialog', { name: 'Navigation' })
    .getByRole('link', { name: /Inbox/ })
    .waitFor();
  await page.waitForTimeout(300);
}

for (const scheme of ['light', 'dark'] as const) {
  test(`the navigation on a desktop, ${scheme}`, async ({ browser }) => {
    for (const persona of ['member', 'admin'] as const) {
      const { context, page, api } = await open(browser, persona, scheme, false);
      await visit(page, `${C}/inbox`);
      await expect(page.locator('[data-testid=inbox-badge]')).toHaveText('2');
      await shoot(page, `desktop-${scheme}-${persona}-inbox`);
      if (persona === 'admin') {
        await visit(page, `${C}/members`);
        await shoot(page, `desktop-${scheme}-admin-members`);
      } else {
        await visit(page, `${C}/triggers`);
        await expect(page.getByText('Standing rules: when something happens')).toBeVisible();
        await shoot(page, `desktop-${scheme}-member-triggers`);
      }
      expect(api.unknown).toEqual([]);
      await context.close();
    }
  });

  test(`the navigation on a phone, ${scheme}`, async ({ browser }) => {
    for (const persona of ['member', 'admin'] as const) {
      const { context, page, api } = await open(browser, persona, scheme, true);
      await visit(page, `${C}/inbox`);
      if (persona === 'member') await shoot(page, `phone-${scheme}-member-inbox`);
      await openNavigation(page);
      await shoot(page, `phone-${scheme}-${persona}-navigation`);
      expect(api.unknown).toEqual([]);
      await context.close();
    }
  });
}

for (const persona of ['member', 'admin'] as const) {
  test(`every page fits a phone for ${persona === 'member' ? 'a member' : 'an admin'}`, async ({
    browser,
  }) => {
    test.setTimeout(PAGES.length * 30_000);
    for (const url of PAGES) {
      // A page of its own per URL, so that one page's state never carries to the next.
      const { context, page, api } = await open(browser, persona, 'light', true);
      await visit(page, url);
      const overflow = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        inner: window.innerWidth,
      }));
      expect(overflow.scroll, `${persona} on ${url}`).toBeLessThanOrEqual(overflow.inner);
      expect(api.unknown, url).toEqual([]);
      await context.close();
    }
  });
}
