import { TestBed } from '@angular/core/testing';
import {
  failureClassLabel,
  formatCost,
  formatDuration,
  formatTokens,
  formatUsage,
  isRunActive,
  resumeText,
  runDuration,
  RunStatusBadge,
  shortSha,
  stoppedAtTimeLimit,
  turnReasonPhrase,
  usageTitle,
} from './run-status';

describe('run formatting', () => {
  it('formats tokens, costs and durations', () => {
    expect(formatTokens(1234)).toBe('1,234');
    expect(formatTokens(12_345)).toBe('12.3K');
    expect(formatTokens(null)).toBe('—');
    expect(formatCost(0.42)).toBe('$0.420');
    expect(formatCost(1.284)).toBe('$1.28');
    expect(formatCost(undefined)).toBeNull();
    expect(formatDuration(42_000)).toBe('42s');
    expect(formatDuration(372_000)).toBe('6m 12s');
    expect(formatDuration(3_720_000)).toBe('1h 02m');
    expect(runDuration('2026-10-05T08:13:48Z', '2026-10-05T08:20:00Z')).toBe('6m 12s');
    expect(runDuration('2026-10-05T08:13:48Z', null, Date.parse('2026-10-05T08:14:00Z'))).toBe(
      '12s',
    );
    expect(runDuration(null, null)).toBeNull();
    expect(shortSha('4e40758abcdef')).toBe('4e40758');
  });

  it('spells out the cache reads and writes of a run apart from its input', () => {
    const usage = {
      inputTokens: 1200,
      cachedInputTokens: 18_000,
      cacheWriteTokens: 1500,
      outputTokens: 850,
    };
    expect(formatUsage(usage)).toBe('1,200 in · 18K cache read · 1,500 cache write · 850 out');
    expect(usageTitle(usage)).toBe(
      'Uncached input 1,200 · cache read 18K · cache write 1,500 · output 850 tokens',
    );
    expect(usageTitle({ ...usage, cacheWriteTokens: 0, estimated: true })).toBe(
      'Uncached input 1,200 · cache read 18K · cache write 0 · output 850 tokens (estimated split)',
    );
    // A v1 runner host, or a run from before: the input includes the cache writes.
    const v1 = {
      inputTokens: 2700,
      cachedInputTokens: 18_000,
      cacheWriteTokens: null,
      outputTokens: 850,
    };
    expect(formatUsage(v1)).toBe('2,700 in · 18K cache read · 850 out');
    expect(usageTitle(v1)).toBe(
      'Input 2,700, cache writes included · cache read 18K · output 850 tokens',
    );
    expect(formatUsage({ inputTokens: 5, outputTokens: 2 })).toBe('5 in · 2 out');
  });

  it('knows active runs and why a turn started', () => {
    expect(isRunActive('QUEUED')).toBe(true);
    expect(isRunActive('RUNNING')).toBe(true);
    expect(isRunActive('LOST')).toBe(false);
    expect(turnReasonPhrase('DECIDED')).toBe('after a decision');
    expect(turnReasonPhrase(null)).toBe('');
  });
});

describe('RunStatusBadge', () => {
  it('shows the status as a pill, live while running', async () => {
    const fixture = TestBed.createComponent(RunStatusBadge);
    fixture.componentRef.setInput('status', 'RUNNING');
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent?.trim()).toBe('Running');
    expect(el.getAttribute('data-status')).toBe('RUNNING');
    expect(el.classList).toContain('text-primary');
    expect(el.querySelector('app-live-dot .animate-ping')).not.toBeNull();

    fixture.componentRef.setInput('status', 'FAILED');
    await fixture.whenStable();
    expect(el.textContent?.trim()).toBe('Failed');
    expect(el.classList).toContain('text-destructive');
    expect(el.querySelector('app-live-dot')).toBeNull();
  });
});

describe('resumeText', () => {
  it('says whether a finished run resumed its session, and why not', () => {
    expect(resumeText({ resumed: true, resumeOutcome: null })).toBe('Resumed its earlier session');
    expect(resumeText({ resumed: false, resumeOutcome: 'FIRST_RUN' })).toBe(
      'Started a new session: first turn of the conversation',
    );
    expect(resumeText({ resumed: false, resumeOutcome: 'NO_SESSION' })).toBe(
      'Started a new session: the earlier session was not on this runner host',
    );
    expect(resumeText({ resumed: false, resumeOutcome: 'NOT_SUPPORTED' })).toContain(
      'cannot resume sessions',
    );
  });

  it('says nothing when it is not known', () => {
    expect(resumeText({})).toBeNull();
    expect(resumeText({ resumed: null, resumeOutcome: null })).toBeNull();
  });

  it('a run with a session to resume can end before it tries', () => {
    expect(resumeText({ resumed: false, resumeOutcome: null })).toBe(
      'Ended before it opened a session',
    );
  });
});

describe('failureClassLabel', () => {
  it('names the classes of the redesign, and keeps an unknown one as the server sent it', () => {
    expect(failureClassLabel('USAGE_LIMIT')).toBe('Usage limit reached');
    expect(failureClassLabel('RUNTIME_REMOVED')).toBe('The agent no longer lists this runtime');
    expect(failureClassLabel('MODEL_NOT_ALLOWED')).toBe('The model is no longer allowed');
    expect(failureClassLabel('TIME_LIMIT')).toBe('Time limit reached');
    expect(failureClassLabel('MODEL_REQUIRED')).toBe('No model chosen');
    expect(failureClassLabel('MODEL_NOT_SERVED')).toBe('The gateway does not serve the model');
    expect(failureClassLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(failureClassLabel(null)).toBeNull();
  });

  it('tells a turn stopped at its time limit from the other guards', () => {
    expect(stoppedAtTimeLimit('TIME_LIMIT')).toBe(true);
    expect(stoppedAtTimeLimit('GUARD_STOPPED')).toBe(false);
    expect(stoppedAtTimeLimit(null)).toBe(false);
  });

  it('phrases why a turn started', () => {
    expect(turnReasonPhrase('CONNECTED')).toBe('after an account was connected');
    expect(turnReasonPhrase(null)).toBe('');
  });
});
