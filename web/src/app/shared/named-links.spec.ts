import { namedLinks } from './named-links';

describe('namedLinks: Tinfirm’s words show links by their words', () => {
  it.each([
    [
      'Link SHOP-2 to this conversation in Tinfirm (https://tinfirm.test/c/c1/conversations/x)',
      '[Link SHOP-2 to this conversation in Tinfirm](https://tinfirm.test/c/c1/conversations/x)',
    ],
    ['- Read the run (https://t.test/runs/r1).', '- [Read the run](https://t.test/runs/r1).'],
    [
      'See it (https://t.test/a) and then answer.',
      'See it ([link](https://t.test/a)) and then answer.',
    ],
    ['[Open it](https://t.test/a)', '[Open it](https://t.test/a)'],
    ['No address here (just words).', 'No address here (just words).'],
  ])('%s', (body, shown) => {
    expect(namedLinks(body)).toBe(shown);
  });
});
