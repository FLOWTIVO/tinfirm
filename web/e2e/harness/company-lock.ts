/**
 * The company's lock, a readers–writer lock held in the stack's process, so that the specs can run on parallel
 * workers. Every test holds it `shared` while it runs; a test tagged `@serial` holds it `exclusive`, alone. A test
 * changes what every other test reads (the runner host, the company's stop, the gateway, a seeded person's accounts or
 * spend, the stubs' call log) only under `@serial` (e2e/README.md, "Parallel workers").
 *
 * Waiters are served first come, first served: a waiting `exclusive` holds back the `shared` that come after it, so it
 * never starves. A holder that never lets go (a worker that died) loses the lock after `leaseMs`. TEST FIXTURE ONLY.
 */

export type LockMode = 'shared' | 'exclusive';

interface Waiter {
  owner: string;
  mode: LockMode;
  grant: () => void;
}

export class CompanyLock {
  private readonly holders = new Map<string, { mode: LockMode; expires: number }>();
  private readonly waiters: Waiter[] = [];
  private readonly leaseMs: number;

  constructor(leaseMs = 15 * 60_000) {
    this.leaseMs = leaseMs;
  }

  /** Resolves true once the owner holds the lock, or false after `waitMs` (the client asks again). */
  acquire(owner: string, mode: LockMode, waitMs: number): Promise<boolean> {
    this.release(owner);
    return new Promise((resolve) => {
      const waiter: Waiter = {
        owner,
        mode,
        grant: () => {
          clearTimeout(timer);
          resolve(true);
        },
      };
      const timer = setTimeout(() => {
        const index = this.waiters.indexOf(waiter);
        if (index >= 0) this.waiters.splice(index, 1);
        resolve(false);
        this.serve();
      }, waitMs);
      this.waiters.push(waiter);
      this.serve();
    });
  }

  release(owner: string): void {
    if (this.holders.delete(owner)) this.serve();
  }

  status(): { holders: { owner: string; mode: LockMode }[]; waiting: number } {
    this.expire();
    return {
      holders: [...this.holders].map(([owner, { mode }]) => ({ owner, mode })),
      waiting: this.waiters.length,
    };
  }

  private serve(): void {
    this.expire();
    while (this.waiters.length > 0) {
      const next = this.waiters[0];
      const modes = [...this.holders.values()].map((holder) => holder.mode);
      const free = next.mode === 'exclusive' ? modes.length === 0 : !modes.includes('exclusive');
      if (!free) return;
      this.waiters.shift();
      this.holders.set(next.owner, { mode: next.mode, expires: Date.now() + this.leaseMs });
      next.grant();
    }
  }

  private expire(): void {
    const now = Date.now();
    for (const [owner, holder] of this.holders) {
      if (holder.expires < now) this.holders.delete(owner);
    }
  }
}
