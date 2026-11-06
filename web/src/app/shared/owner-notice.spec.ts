import { ownerNames } from './owner-notice';

describe('ownerNames', () => {
  it('joins the names as a sentence does', () => {
    expect(ownerNames([])).toBe('');
    expect(ownerNames(['Ann'])).toBe('Ann');
    expect(ownerNames(['Ann', 'Bob'])).toBe('Ann and Bob');
    expect(ownerNames(['Ann', 'Bob', 'Cleo'])).toBe('Ann, Bob and Cleo');
  });
});
