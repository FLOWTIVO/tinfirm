import { anchorSentence } from './audit-verification-result';

describe('the checkpoint a verification was compared with (ADR-0111)', () => {
  it('reads as a sentence an admin may note elsewhere', () => {
    expect(
      anchorSentence({
        number: 1234,
        issuedAt: '2026-11-07T10:00:00.123456Z',
        keyId: 'k2026',
        seq: 88123,
        hash: 'a'.repeat(64),
      }),
    ).toBe(
      'Anchored outside the database at event 88,123: checkpoint 1,234 of 2026-11-07 10:00 UTC, key k2026.',
    );
  });
});
