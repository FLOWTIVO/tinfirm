import { expect, type Page } from '@playwright/test';
import { password, personas } from './harness/config';

/**
 * Logging in through the login page, for the specs of the login itself. Every other spec starts logged in: the
 * fixtures' `persona` option and `pageAs` use the sessions the seed saved (fixtures.ts).
 *
 * The Owner of the seeded company is the person of these specs (TEST FIXTURE credentials, e2e/harness/config.ts).
 */
export const owner = { email: personas.owner.email, password };

export async function login(page: Page, email = owner.email, secret = owner.password) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(secret);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}

/** Logs in as the Owner and waits for the company's inbox, the home of a member (the plan §3). */
export async function loginToFirstCompany(page: Page) {
  await login(page);
  await expect(page).toHaveURL(/\/c\/[^/]+\/inbox$/);
}
