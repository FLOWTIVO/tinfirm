import type { RunnerHostIsolation } from '../../api/generated/models';
import {
  chosenUnavailableText,
  containerWarningText,
  isolationLines,
  isolationOptions,
  placementText,
  showsIsolationChoice,
} from './isolation-view';

function isolation(overrides: Partial<RunnerHostIsolation> = {}): RunnerHostIsolation {
  return { level: 'MACHINE', gvisor: false, hardened: 'NONE', stated: false, ...overrides };
}

describe('a runner host’s isolation (ADR-0113)', () => {
  it('shows nothing before the host’s first hello to a server of this release', () => {
    expect(isolationLines(null)).toBeNull();
    expect(isolationLines(undefined)).toBeNull();
  });

  it('says what the host reports, never what Tinfirm checked', () => {
    const lines = isolationLines(isolation())!;
    expect(lines.reports).toBe('Reports machine');
    expect(lines.hardening).toContain('provider’s hypervisor is the boundary');
    expect(lines.reaches).toContain('credentials are on another machine');
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

  it('names gVisor on a container host', () => {
    const lines = isolationLines(isolation({ level: 'CONTAINER', gvisor: true, hardened: 'NO' }))!;
    expect(lines.reports).toBe('Reports container with gVisor');
    expect(lines.reaches).toContain('behind gVisor and the kernel');
    expect(
      isolationLines(isolation({ level: 'CONTAINER', gvisor: true, stated: true }))!.reports,
    ).toBe('Reports container with gVisor, stated by the operator');
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
    // The line starts with these words; no pattern is built from them.
    expect(isolationLines(isolation({ hardened }))!.hardening.slice(0, words.length)).toBe(words);
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

describe('an agent’s isolation (ADR-0113)', () => {
  const base = {
    setting: 'AUTOMATIC' as const,
    level: 'MACHINE' as const,
    offered: ['MACHINE' as const, 'CONTAINER' as const],
    chosenUnavailable: false,
    outsideStarts: false,
    containerWarning: false,
  };

  it('offers automatic, which names where runs go now, and each level the hosts report', () => {
    expect(isolationOptions(base).map((o) => o.label)).toEqual([
      'Automatic: the strongest level your company’s hosts report (now: machine)',
      'Only hosts that report machine',
      'Only hosts that report container',
    ]);
    expect(showsIsolationChoice(base)).toBe(true);
    expect(showsIsolationChoice({ ...base, offered: ['CONTAINER'] })).toBe(false);
    expect(showsIsolationChoice(null)).toBe(false);
  });

  it('keeps a chosen level no host reports any more, and says so', () => {
    const gone = {
      ...base,
      setting: 'VM_JAIL' as const,
      level: null,
      chosenUnavailable: true,
      offered: ['CONTAINER' as const],
    };
    expect(showsIsolationChoice(gone)).toBe(true);
    expect(isolationOptions(gone).map((o) => o.value)).toEqual([
      'AUTOMATIC',
      'CONTAINER',
      'VM_JAIL',
    ]);
    expect(chosenUnavailableText(gone)).toContain(
      'No company runner host reports vm-jail any more',
    );
    expect(chosenUnavailableText(base)).toBeNull();
  });

  it('warns only where outside input meets a container', () => {
    expect(containerWarningText({ ...base, containerWarning: true })).toContain(
      'run in a container',
    );
    expect(containerWarningText(base)).toBeNull();
  });
});
