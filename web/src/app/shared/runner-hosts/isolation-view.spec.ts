import type { RunnerHostIsolation } from '../../api/generated/models';
import { isolationLines, placementText } from './isolation-view';

function isolation(overrides: Partial<RunnerHostIsolation> = {}): RunnerHostIsolation {
  return { tiers: ['MICROVM'], level: 'MACHINE', hardened: 'NONE', stated: false, ...overrides };
}

describe('a runner host’s isolation (ADR-0113)', () => {
  it('shows nothing before the host’s first report', () => {
    expect(isolationLines(null)).toBeNull();
    expect(isolationLines(undefined)).toBeNull();
  });

  it('says what the host reports, never what Tinfirm checked', () => {
    const lines = isolationLines(isolation())!;
    expect(lines.reports).toBe('Reports machine');
    expect(lines.hardening).toContain('provider’s hypervisor is the boundary');
    expect(lines.reaches).toContain('credentials are on another machine');
    expect(lines.tiers).toBe('Tier microvm');
  });

  it.each<[RunnerHostIsolation['level'], string, string]>([
    ['MACHINE', 'Reports machine', 'on another machine'],
    ['VM_JAIL', 'Reports vm-jail', 'reaches every credential'],
    ['CONTAINER', 'Reports container', 'behind the kernel alone'],
  ])('names %s and what an escape reaches there', (level, reports, reaches) => {
    const lines = isolationLines(isolation({ level, hardened: 'NO' }))!;
    expect(lines.reports).toBe(reports);
    expect(lines.reaches).toContain(reaches);
  });

  it('marks a level the operator stated', () => {
    expect(isolationLines(isolation({ level: 'VM_JAIL', stated: true }))!.reports).toBe(
      'Reports vm-jail, stated by the operator',
    );
  });

  it.each<[RunnerHostIsolation['hardened'], string]>([
    ['YES', 'Hardened'],
    ['NO', 'Not hardened'],
    ['NONE', 'No machine of its own'],
  ])('shows the hardening %s', (hardened, words) => {
    expect(isolationLines(isolation({ hardened }))!.hardening).toMatch(new RegExp(`^${words}`));
  });

  it('shows a host without a level with its tier, placed as a container host', () => {
    const lines = isolationLines({ tiers: ['MICROVM'], stated: false })!;
    expect(lines.reports).toBe('Reports no level: its runs are placed as on a container host.');
    expect(lines.tiers).toBe('Tier microvm');
    expect(lines.hardening).toBeNull();
    expect(lines.reaches).toBeNull();
  });
});

describe('a run’s placement (ADR-0113)', () => {
  it('names the level its host was placed at, and why it ran there', () => {
    expect(placementText(null, null)).toBeNull();
    expect(placementText('MACHINE', null)).toBe('Placed on a host that reports machine.');
    expect(placementText('CONTAINER', 'STAYED_ON_WEAKER_HOST')).toBe(
      'Placed on a host that reports container. It stayed on the host that holds the conversation’s session; the agent’s new conversations go to a stronger level.',
    );
    expect(placementText('MACHINE', 'FRESH_SESSION_CHOSEN_LEVEL')).toContain(
      'level chosen for the agent',
    );
    expect(placementText('MACHINE', 'FRESH_SESSION_STRONGER_LEVEL')).toContain('could not take it');
  });
});
