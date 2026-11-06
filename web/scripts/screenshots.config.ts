import { defineConfig } from '@playwright/test';

/**
 * Screenshots of a slice for review (plan §11: screenshots only), taken against
 * `ng serve` with the API answered from the test fixtures (`mock-api.ts`). Not part of the e2e
 * harness, which runs against a real server (`e2e/`).
 *
 *   npm run screenshots
 *
 * writes `docs/screenshots/web/w0/`. `E2E_CHROMIUM_PATH` names a Chromium of another build than
 * this Playwright's, as for the e2e run; `SCREENSHOTS_BASE_URL` a dev server already running.
 */
const port = 4300;
const baseURL = process.env['SCREENSHOTS_BASE_URL'] ?? `http://localhost:${port}`;
const executablePath = process.env['E2E_CHROMIUM_PATH'];

export default defineConfig({
  testDir: '.',
  testMatch: 'screenshots.spec.ts',
  outputDir: '../test-results/screenshots',
  fullyParallel: false,
  workers: 1,
  // A busy machine can crash a renderer now and then; one more try is enough.
  retries: 1,
  reporter: 'list',
  timeout: 120_000,
  use: {
    baseURL,
    reducedMotion: 'reduce',
    ...(executablePath ? { launchOptions: { executablePath } } : {}),
  },
  webServer: process.env['SCREENSHOTS_BASE_URL']
    ? undefined
    : {
        // The production build: smaller and faster in the browser than the development one.
        command: `npx ng serve --configuration production --port ${port}`,
        cwd: '..',
        url: baseURL,
        reuseExistingServer: true,
        timeout: 240_000,
      },
});
