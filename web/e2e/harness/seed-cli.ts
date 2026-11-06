/** `npm run e2e:seed`: seeds the running stack (`npm run e2e:stack`) and prints what it made. */
import { printSeed, seed } from './seed.ts';

seed()
  .then((state) => printSeed(state))
  .catch((error: unknown) => {
    console.error(`[e2e:seed] ${(error as Error).message}`);
    process.exit(1);
  });
