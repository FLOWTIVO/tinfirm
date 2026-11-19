import { TestBed } from '@angular/core/testing';
import type { RunRepository } from '../../../api/generated/models';
import { changeRequestLabel } from '../../../shared/run-status';
import { gitResult, revokedNote, RunRepositories } from './run-repositories';

const SERVER: RunRepository = {
  repositoryId: 'r1',
  repositoryName: 'shop-api',
  dir: 'shop-api',
  readOnly: false,
  pushed: true,
  commits: 3,
  headSha: 'fedcba9876543210',
};
const WEB: RunRepository = {
  repositoryId: 'r2',
  repositoryName: 'shop-web',
  dir: 'shop-web',
  readOnly: false,
  pushed: false,
  commits: 0,
};

async function render(inputs: Record<string, unknown>): Promise<HTMLElement> {
  const fixture = TestBed.createComponent(RunRepositories);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('gitResult', () => {
  it('says what happened in a repository', () => {
    expect(gitResult(SERVER, false)).toBe('Pushed 3 commits');
    expect(gitResult({ ...SERVER, commits: 1 }, false)).toBe('Pushed 1 commit');
    expect(gitResult({ ...SERVER, commits: null }, false)).toBe('Pushed');
    expect(gitResult(WEB, false)).toBe('No changes');
    expect(gitResult({ ...WEB, commits: 2 }, false)).toBe('2 commits, not pushed');
    expect(gitResult({ ...WEB, commits: 2, readOnly: true }, false)).toBe(
      '2 commits, not pushed (read-only)',
    );
    const pending = { repositoryName: 'x', dir: 'x', readOnly: false };
    expect(gitResult(pending, true)).toBe('Working');
    expect(gitResult(pending, false)).toBe('No result');
  });
});

describe('revokedNote', () => {
  const revoked = {
    reason: 'RECONNECT_COMPANY',
    message:
      "Tinfirm revoked this run's access to shop-web: the shared credential CI bot was disabled.",
    revokedAt: '2026-11-19T10:00:00Z',
    delivery: 'SENT' as const,
  };

  it('says why the git access went, and whether the host could drop it', () => {
    expect(revokedNote(WEB)).toBeNull();
    expect(revokedNote({ ...WEB, gitRevoked: revoked })).toBe(revoked.message);
    expect(revokedNote({ ...WEB, gitRevoked: { ...revoked, delivery: 'PENDING' } })).toContain(
      'It is told when it reconnects, before it pushes',
    );
    expect(revokedNote({ ...WEB, gitRevoked: { ...revoked, delivery: 'UNSUPPORTED' } })).toContain(
      'cannot drop a token (an older version), so the run keeps its git access until it ends',
    );
    expect(gitResult({ ...WEB, commits: 2, gitRevoked: revoked }, false)).toBe(
      '2 commits, not pushed (access revoked)',
    );
  });

  it('shows the revocation on the repository', async () => {
    const el = await render({ repositories: [{ ...WEB, commits: 1, gitRevoked: revoked }] });
    const row = el.querySelector('[data-testid=run-repository]')!;
    expect(row.querySelector('[data-testid=git-revoked]')?.textContent).toBe('Git access revoked');
    expect(row.querySelector('[data-testid=git-revoked-note]')?.textContent?.trim()).toBe(
      revoked.message,
    );
    expect(row.textContent).toContain('1 commit, not pushed (access revoked)');
  });
});

describe('changeRequestLabel', () => {
  it('shortens GitHub pull request and GitLab merge request links', () => {
    expect(changeRequestLabel('https://github.com/acme/server/pull/41')).toBe('acme/server#41');
    expect(
      changeRequestLabel('https://git.acme.internal/platform/backend/api/-/merge_requests/12'),
    ).toBe('platform/backend/api!12');
    // Older GitLab versions have no "/-/" in the path.
    expect(changeRequestLabel('https://git.acme.internal/acme/api/merge_requests/4')).toBe(
      'acme/api!4',
    );
    expect(changeRequestLabel('https://git.example.com/pr/7')).toBe('https://git.example.com/pr/7');
  });
});

describe('RunRepositories', () => {
  it('lists the workspace with each directory, result and head', async () => {
    const el = await render({ repositories: [SERVER, WEB] });
    const rows = el.querySelectorAll('[data-testid=run-repository]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('shop-api');
    expect(rows[0].querySelector('code')?.getAttribute('title')).toBe('/workspace/shop-api');
    expect(rows[0].textContent).toContain('Pushed 3 commits');
    expect(rows[0].textContent).toContain('fedcba9');
    expect(rows[1].textContent).toContain('No changes');
    expect(el.querySelector('[data-testid=read-only]')).toBeNull();
  });

  it('marks read-only repositories, and says a live run has no result yet', async () => {
    const el = await render({
      repositories: [{ ...WEB, readOnly: true, pushed: null, commits: null }],
      active: true,
    });
    const row = el.querySelector('[data-testid=run-repository]')!;
    expect(row.querySelector('[data-testid=read-only]')?.textContent).toBe('Read-only');
    expect(row.textContent).toContain('Working');
  });

  it('says why a repository is read-only', async () => {
    const el = await render({
      repositories: [{ ...WEB, readOnly: true, readOnlyCause: 'GRANT' }],
    });
    expect(el.querySelector('[data-testid=read-only]')?.getAttribute('title')).toBe(
      'Read-only: the conversation acts through a read-only grant here: Tinfirm does not push it',
    );
  });
});
