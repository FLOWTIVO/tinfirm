import { loadEach } from './company-data';

describe('loadEach', () => {
  it('loads at most the limit at a time, and keeps the order', async () => {
    let running = 0;
    let most = 0;
    const results = await loadEach([5, 1, 4, 2, 3, 0, 2], 3, async (n) => {
      running++;
      most = Math.max(most, running);
      await new Promise((resolve) => setTimeout(resolve, n));
      running--;
      return n * 10;
    });
    expect(results).toEqual([50, 10, 40, 20, 30, 0, 20]);
    expect(most).toBe(3);
  });

  it('loads nothing for no items', async () => {
    expect(await loadEach([], 3, async () => 1)).toEqual([]);
  });
});
