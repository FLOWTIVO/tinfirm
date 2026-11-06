import { HANDLE_PATTERN, handleFromName, uniqueHandle } from './handles';

describe('handles (ADR-0034)', () => {
  it('makes a handle from the name as the server does', () => {
    expect(handleFromName('Backend engineer')).toBe('backend-engineer');
    expect(handleFromName('Vývojář')).toBe('vyvojar');
    expect(handleFromName('  Test writer  ')).toBe('test-writer');
    expect(handleFromName('QA / Release (EU) #2')).toBe('qa-release-eu-2');
    expect(handleFromName('Ürün API')).toBe('urun-api');
    expect(handleFromName('***')).toBe('agent');
    expect(handleFromName('日本')).toBe('agent');
    expect(handleFromName('')).toBe('agent');
  });

  it('cuts a long handle to 50 characters without a trailing hyphen', () => {
    expect(handleFromName('a'.repeat(49) + ' b' + 'c'.repeat(20))).toBe('a'.repeat(49));
    expect(handleFromName('x'.repeat(80))).toHaveLength(50);
  });

  it('numbers a taken handle, shortening the base to stay within 50', () => {
    expect(uniqueHandle('test-writer', ['backend-engineer'])).toBe('test-writer');
    expect(uniqueHandle('test-writer', ['test-writer'])).toBe('test-writer-2');
    expect(uniqueHandle('test-writer', ['Test-Writer', 'test-writer-2'])).toBe('test-writer-3');
    const base = 'a'.repeat(47) + '-bc';
    expect(uniqueHandle(base, [base])).toBe('a'.repeat(47) + '-2');
  });

  it('accepts letters, digits and hyphens, not at either end', () => {
    for (const ok of ['a', 'qa-lead', 'QA-Lead', 'b2', 'b'.repeat(50)]) {
      expect(HANDLE_PATTERN.test(ok), ok).toBe(true);
    }
    for (const bad of ['-qa', 'qa-', 'q a', 'q_a', '@qa', 'čeština', 'b'.repeat(51)]) {
      expect(HANDLE_PATTERN.test(bad), bad).toBe(false);
    }
  });
});
