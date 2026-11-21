import type { GitHubApp } from '../../../api/generated/models';
import { hostsLine, permissionLine } from './system-github-app';

const APP: GitHubApp = {
  systemId: 's1',
  appId: 424242,
  setAt: '2026-11-19T10:00:00Z',
  hostsConnected: 3,
  hostsWithoutAppTokens: 1,
  permissions: [
    { name: 'Contents', access: 'WRITE' },
    { name: 'Metadata', access: 'READ' },
    { name: 'Workflows', access: 'NEVER' },
  ],
};

describe('the GitHub App section (ADR-0116)', () => {
  it('says what to give the App, and never Workflows', () => {
    expect(permissionLine(APP)).toBe(
      'Give it Contents (read and write), Metadata (read); never Workflows.',
    );
  });

  it('counts the connected hosts that cannot renew its tokens', () => {
    expect(hostsLine(APP)).toContain('1 of the 3 connected runner hosts cannot renew');
    expect(hostsLine({ ...APP, hostsWithoutAppTokens: 0 })).toBeNull();
  });
});
