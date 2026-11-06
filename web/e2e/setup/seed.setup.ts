import { test as setup } from '@playwright/test';
import { printSeed, seed } from '../harness/seed';

/**
 * The `seed` project, a dependency of every other: seeds the stack through the API (idempotent) and saves every
 * person's session for the browser (e2e/.state/auth/<persona>.json).
 */
setup('seed the people, the systems, the gateway, the runner host and the agent', async () => {
  setup.setTimeout(180_000);
  printSeed(await seed());
});
