import { chromium, defineConfig, devices } from '@playwright/test';
import { existsSync } from 'node:fs';
import { availableParallelism } from 'node:os';
import { resolve } from 'node:path';
import { urls, webMode } from './e2e/harness/config';

/**
 * End-to-end tests against the real server with seeded data (e2e/README.md). Playwright starts the stack
 * (`npm run e2e:stack`: the database, the fakes, the server and the web app) unless one is up already, then the
 * `seed` project seeds it, then the projects run:
 *
 * - `desktop`: every spec, in desktop Chromium;
 * - `phone`: the specs tagged `@phone`, at 390 × 844 with touch (the member flows; no horizontal overflow).
 *
 * Specs tagged `@app` need the web app's pages; E2E_WEB=none leaves them out.
 */

/** A Chromium of another build than this Playwright's (E2E_CHROMIUM_PATH), or the pre-installed one when this one's is missing. */
function chromiumPath(): string | undefined {
  if (process.env['E2E_CHROMIUM_PATH']) return process.env['E2E_CHROMIUM_PATH'];
  if (existsSync(chromium.executablePath())) return undefined;
  const preinstalled = resolve(
    process.env['PLAYWRIGHT_BROWSERS_PATH'] ?? '/ms-playwright',
    'chromium',
  );
  return existsSync(preinstalled) ? preinstalled : undefined;
}

const executablePath = chromiumPath();
const launchOptions = executablePath ? { launchOptions: { executablePath } } : {};
const ci = !!process.env['CI'];
/** E2E_STACK=external: the stack is started by someone else (`npm run e2e:stack`), and Playwright starts none. */
const startStack = process.env['E2E_STACK'] !== 'external';

export default defineConfig({
  testDir: './e2e',
  // The specs share one server, one runner host and one company. They run on parallel workers; a test that changes
  // what the others read is tagged @serial and runs alone (the company's lock, e2e/harness/company-lock.ts).
  fullyParallel: false,
  // Three on 4 CPUs or more, fewer below: the stack and the browsers share the CPUs, and a third worker on 2 CPUs made
  // pages miss their 10 s (e2e/README.md, "Parallel workers"). availableParallelism counts the CPUs this process may use.
  workers: Number(process.env['E2E_WORKERS'] ?? Math.min(3, availableParallelism())),
  forbidOnly: ci,
  retries: ci ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: ci ? [['list'], ['html', { open: 'never' }]] : 'list',
  grepInvert: webMode === 'none' ? /@app/ : undefined,
  use: {
    baseURL: urls.app,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: (process.env['E2E_VIDEO'] as 'on' | 'off' | 'retain-on-failure' | undefined) ?? 'off',
  },
  webServer: startStack
    ? {
        command: 'node --disable-warning=MODULE_TYPELESS_PACKAGE_JSON e2e/harness/stack.ts',
        url: `${urls.control}/health`,
        reuseExistingServer: !ci,
        // A first run builds the server jar, and ng serve compiles the app; a loaded machine takes minutes.
        timeout: 1_200_000,
        stdout: 'pipe',
        stderr: 'pipe',
        gracefulShutdown: { signal: 'SIGTERM', timeout: 30_000 },
      }
    : undefined,
  projects: [
    {
      name: 'seed',
      testMatch: /setup\/.*\.setup\.ts/,
    },
    {
      name: 'desktop',
      dependencies: ['seed'],
      testIgnore: /setup\//,
      use: { ...devices['Desktop Chrome'], ...launchOptions },
    },
    {
      name: 'phone',
      dependencies: ['seed'],
      testIgnore: /setup\//,
      grep: /@phone/,
      use: {
        ...devices['Desktop Chrome'],
        ...launchOptions,
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 3,
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
});
