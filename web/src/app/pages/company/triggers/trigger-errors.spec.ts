import { HttpErrorResponse } from '@angular/common/http';
import { triggerFailure } from './trigger-errors';

function refusal(status: number, body: Record<string, unknown>): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    error: { type: 'about:blank', title: 'Error', status, ...body },
  });
}

describe('a trigger’s refusal, placed where it belongs', () => {
  it.each`
    code                              | status | at               | says
    ${'INVALID_TRIGGER'}              | ${400} | ${'when'}        | ${'could never fire'}
    ${'SYSTEM_KIND_INVALID'}          | ${400} | ${'when'}        | ${'cannot be used here'}
    ${'SOURCE_ACCOUNT_REQUIRED'}      | ${400} | ${'accounts'}    | ${'needs an account on its own system'}
    ${'MODEL_ACCESS_NOT_ENTITLED'}    | ${400} | ${'payer'}       | ${'may not use this budget'}
    ${'MODEL_ACCESS_UNAVAILABLE'}     | ${409} | ${'payer'}       | ${'Nobody can pay'}
    ${'CANNOT_RUN_ANYWHERE'}          | ${409} | ${'panel'}       | ${'No runner host may run this'}
    ${'OPEN_EVENTS_NOT_ACKNOWLEDGED'} | ${400} | ${'acknowledge'} | ${'set the write tools to Ask'}
    ${'DUPLICATE_TRIGGER_NAME'}       | ${409} | ${'name'}        | ${'already exists'}
    ${'TRIGGER_LIMIT_TOO_HIGH'}       | ${400} | ${'results'}     | ${'more firings per hour'}
    ${'AGENT_NOT_USABLE'}             | ${403} | ${'agent'}       | ${'not shared with you'}
    ${'TRIGGER_NOT_EDITABLE'}         | ${403} | ${null}          | ${'Only the trigger’s author'}
    ${'TOO_MANY_TRIGGERS'}            | ${409} | ${null}          | ${'as many triggers as it can hold'}
  `('$code ($status) goes to $at', ({ code, status, at, says }) => {
    const failure = triggerFailure(refusal(status, { code }));
    expect(failure.at).toBe(at);
    expect(failure.message).toContain(says);
  });

  it('marks the row an ACCOUNT_NOT_ENTITLED names', () => {
    const failure = triggerFailure(
      refusal(400, { code: 'ACCOUNT_NOT_ENTITLED', details: { systemId: 'sys-jira' } }),
    );
    expect(failure).toMatchObject({ at: 'accounts', systemId: 'sys-jira' });
  });

  it('adds the reason of a refusal and the rule an INVALID_TRIGGER names', () => {
    expect(
      triggerFailure(
        refusal(409, {
          code: 'MODEL_ACCESS_UNAVAILABLE',
          details: { reason: 'GATEWAY_NOT_CONFIGURED' },
        }),
      ).message,
    ).toContain('An admin must set up model access first.');
    expect(
      triggerFailure(
        refusal(400, { code: 'INVALID_TRIGGER', detail: 'CLOSED never fires on ONCE' }),
      ).message,
    ).toContain('CLOSED never fires on ONCE');
  });

  it.each(['MODEL_NOT_ALLOWED', 'MODEL_REQUIRED', 'RUNTIME_NOT_LISTED', 'SETTINGS_WIDEN'])(
    'hands %s to the settings form',
    (code) => {
      const failure = triggerFailure(refusal(400, { code }));
      expect(failure.at).toBe('settings');
    },
  );
});
