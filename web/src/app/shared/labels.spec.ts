import { formatPeriod } from './labels';

describe('formatPeriod', () => {
  it.each`
    period   | words
    ${'30d'} | ${'30 days'}
    ${'1d'}  | ${'day'}
    ${'12h'} | ${'12 hours'}
    ${'2w'}  | ${'2 weeks'}
    ${'1mo'} | ${'month'}
    ${'odd'} | ${'odd'}
    ${null}  | ${''}
  `('says $period as “$words”', ({ period, words }) => {
    expect(formatPeriod(period)).toBe(words);
  });
});
