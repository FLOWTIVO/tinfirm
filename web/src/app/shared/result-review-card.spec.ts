import type { DecisionWrite } from '../api/generated/models';
import { reviewItem, reviewWrite } from './result-review-card';

describe('reviewWrite: each write of a result’s review, and what it is', () => {
  it.each([
    ['Move SHOP-2 through “In review”', 'A transition of SHOP-2'],
    ['Attach Brief.md to SHOP-2', 'A document on SHOP-2'],
    ['Add the label “ready” to SHOP-2', 'A label on SHOP-2'],
    ['Remove the label “draft” from SHOP-2', 'A label on SHOP-2'],
    ['Close SHOP-2', 'Closing SHOP-2'],
    ['Something new the server says', 'An action'],
  ])('%s', (write, label) => {
    expect(reviewWrite(write)).toEqual({ kind: 'action', label, text: write });
  });

  it('heads a comment by its first line, and shows its links by their words', () => {
    expect(
      reviewWrite('A comment on SHOP-2:\n\n**Analyst:** Ready.\n\nSee it (https://t.test/x)'),
    ).toEqual({
      kind: 'comment',
      label: 'A comment on SHOP-2',
      text: '**Analyst:** Ready.\n\n[See it](https://t.test/x)',
    });
    expect(reviewWrite('Link SHOP-2 to this conversation in Tinfirm (https://t.test/c)')).toEqual({
      kind: 'action',
      label: 'A link on SHOP-2',
      text: '[Link SHOP-2 to this conversation in Tinfirm](https://t.test/c)',
    });
  });
});

describe('reviewItem: each write by its kind and target', () => {
  const item = (overrides: Partial<DecisionWrite>): DecisionWrite => ({
    kind: 'TRANSITION',
    target: 'SHOP-2',
    value: null,
    text: 'words',
    ...overrides,
  });

  it.each<[Partial<DecisionWrite>, string, string]>([
    [
      { kind: 'TRANSITION', value: 'In review' },
      'A transition of SHOP-2',
      'Moves SHOP-2 to “In review”',
    ],
    [{ kind: 'LABEL_ADD', value: 'ready' }, 'A label on SHOP-2', 'Adds the label “ready”'],
    [{ kind: 'LABEL_REMOVE', value: 'draft' }, 'A label on SHOP-2', 'Removes the label “draft”'],
    [{ kind: 'CLOSE' }, 'Closing SHOP-2', 'Closes SHOP-2'],
    [{ kind: 'ATTACHMENT', value: 'Brief' }, 'A document on SHOP-2', 'Attaches “Brief”'],
    [
      {
        kind: 'REMOTE_LINK',
        value: 'https://t.test/c',
        text: 'Link SHOP-2 to it (https://t.test/c)',
      },
      'A link on SHOP-2',
      '[Link SHOP-2 to it](https://t.test/c)',
    ],
    [
      { kind: 'CHAT_FILE', target: 'C1:171', value: 'Brief' },
      'A file in the chat thread',
      'Uploads “Brief”',
    ],
  ])('%o', (overrides, label, text) => {
    expect(reviewItem(item(overrides))).toMatchObject({ kind: 'action', label, text });
  });

  it('heads a comment by its target, without the words’ first line', () => {
    expect(
      reviewItem(item({ kind: 'COMMENT', text: 'A comment on SHOP-2:\n\n**Analyst:** Ready.' })),
    ).toEqual({ kind: 'comment', label: 'A comment on SHOP-2', text: '**Analyst:** Ready.' });
    expect(
      reviewItem(
        item({ kind: 'CHAT_POST', target: 'C1:171', text: 'The answer, posted in the thread' }),
      ),
    ).toEqual({
      kind: 'comment',
      label: 'A post in the chat thread',
      text: 'The answer, posted in the thread',
    });
  });

  it('falls back to the words for a review raised before writes had a kind', () => {
    expect(reviewItem(item({ kind: null, text: 'Close SHOP-2' }))).toEqual(
      reviewWrite('Close SHOP-2'),
    );
  });
});
