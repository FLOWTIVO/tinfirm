import { HttpErrorResponse } from '@angular/common/http';
import { Injector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { form } from '@angular/forms/signals';
import {
  credentialMatches,
  mapSubmissionError,
  problemMessage,
  problemDetails,
  problemReason,
  toProblem,
} from './problem';

const httpError = (status: number, body: unknown) =>
  new HttpErrorResponse({ status, error: body, url: '/api/x' });

describe('problem details', () => {
  it('reads problem+json bodies, also when they arrive as text', () => {
    const body = {
      status: 409,
      code: 'LAST_OWNER',
      detail: 'nope',
      errors: [{ field: 'role', message: 'bad' }],
    };
    expect(toProblem(httpError(409, body))).toMatchObject({ status: 409, code: 'LAST_OWNER' });
    expect(toProblem(httpError(409, JSON.stringify(body))).fieldErrors).toEqual([
      { field: 'role', message: 'bad' },
    ]);
    expect(toProblem(httpError(502, '<html>bad gateway</html>')).code).toBeUndefined();
  });

  it('prefers overrides, then known codes, then the detail, then the status', () => {
    const lastOwner = httpError(409, { code: 'LAST_OWNER', detail: 'server text' });
    expect(problemMessage(lastOwner, { LAST_OWNER: 'custom' })).toBe('custom');
    expect(problemMessage(lastOwner)).toContain('at least one owner');
    expect(problemMessage(httpError(400, { detail: 'Name is taken' }))).toBe('Name is taken');
    expect(problemMessage(httpError(0, null))).toContain("Can't reach the server");
    expect(problemMessage(httpError(500, { detail: 'NullPointerException' }))).toContain(
      'Something went wrong on the server',
    );
    expect(problemMessage(httpError(403, {}), { 'status:403': 'Owners only.' })).toBe(
      'Owners only.',
    );
  });

  it('names the core duplicates, and reads an unknown DUPLICATE_ code as one', () => {
    expect(problemMessage(httpError(409, { code: 'DUPLICATE_GROUP_NAME' }))).toBe(
      'A group with this name already exists.',
    );
    expect(problemMessage(httpError(409, { code: 'DUPLICATE_TRIGGER_NAME' }))).toBe(
      'A trigger with this name already exists.',
    );
    // An unknown DUPLICATE_ code of a later package still reads as a duplicate.
    expect(problemMessage(httpError(409, { code: 'DUPLICATE_CHANNEL_NAME' }))).toBe(
      'Something with this name already exists.',
    );
  });

  it.each`
    code                        | status | says
    ${'COMPANY_STOPPED'}        | ${409} | ${'An admin stopped the company’s runs'}
    ${'SYSTEM_PAUSED'}          | ${409} | ${'An admin paused this system'}
    ${'PRINCIPAL_SUSPENDED'}    | ${409} | ${'Suspended in this company'}
    ${'PRINCIPAL_SUSPENDED'}    | ${403} | ${'Suspended in this company'}
    ${'IDEMPOTENCY_KEY_REUSED'} | ${422} | ${'carried the key of an earlier, different one'}
  `(
    'says the kill switch or key $code ($status) in words, not the detail',
    ({ code, status, says }) => {
      const message = problemMessage(httpError(status, { code, detail: 'server text' }));
      expect(message).toContain(says);
      expect(message).not.toContain('server text');
    },
  );

  it.each`
    code                              | status | says                                                        | withDetail
    ${'INVALID_TRIGGER'}              | ${400} | ${'could never fire'}                                       | ${true}
    ${'SOURCE_ACCOUNT_REQUIRED'}      | ${400} | ${'needs an account on its own system'}                     | ${false}
    ${'ACCOUNT_NOT_ENTITLED'}         | ${400} | ${'may not use this account'}                               | ${false}
    ${'MODEL_ACCESS_NOT_ENTITLED'}    | ${400} | ${'may not use this budget'}                                | ${false}
    ${'OPEN_EVENTS_NOT_ACKNOWLEDGED'} | ${400} | ${'Anyone who may write at its source drives this trigger'} | ${false}
    ${'TRIGGER_NOT_EDITABLE'}         | ${403} | ${'Only the trigger’s author and the company’s admins'}     | ${false}
    ${'TOO_MANY_TRIGGERS'}            | ${409} | ${'as many triggers as it can hold'}                        | ${false}
    ${'TRIGGER_LIMIT_TOO_HIGH'}       | ${400} | ${'more firings per hour than the company allows'}          | ${false}
    ${'TRIGGER_PAUSED'}               | ${409} | ${'The trigger is paused'}                                  | ${true}
    ${'SUBJECT_NOT_VISIBLE'}          | ${409} | ${'cannot read this issue'}                                 | ${false}
    ${'TRIGGER_CONDITIONS_FAIL'}      | ${409} | ${'does not meet the trigger’s scope or conditions'}        | ${true}
    ${'PLACE_IS_EXTERNAL'}            | ${409} | ${'lives in its place'}                                     | ${false}
    ${'SERVICE_IDENTITY_IN_USE'}      | ${409} | ${'A trigger acts through this service account'}            | ${true}
  `('says the trigger code $code ($status) in words', ({ code, status, says, withDetail }) => {
    const message = problemMessage(httpError(status, { code, detail: 'server text' }));
    expect(message).toContain(says);
    expect(message.includes('server text')).toBe(withDetail);
  });

  it('reads a problem’s details, such as the row a trigger’s refusal names', () => {
    const error = httpError(400, { code: 'ACCOUNT_NOT_ENTITLED', details: { systemId: 's1' } });
    expect(problemDetails(error)).toEqual({ systemId: 's1' });
    expect(problemDetails(httpError(400, { code: 'VALIDATION_FAILED' }))).toBeUndefined();
  });

  it('maps field errors and code-specific errors onto a signal form', () => {
    const injector = TestBed.inject(Injector);
    const f = form(signal({ email: '', role: 'MEMBER' }), { injector });

    const validation = mapSubmissionError(
      f,
      httpError(400, {
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'email', message: 'must be an email' }],
      }),
    );
    expect(validation.message).toBeNull();
    expect(validation.fieldErrors).toHaveLength(1);
    expect(validation.fieldErrors[0].fieldTree).toBe(f.email);

    const unknownUser = mapSubmissionError(f, httpError(404, { code: 'USER_NOT_FOUND' }), {
      codeFields: { USER_NOT_FOUND: 'email' },
    });
    expect(unknownUser.message).toBeNull();
    expect(unknownUser.fieldErrors[0].message).toContain('No user has this email');

    const other = mapSubmissionError(f, httpError(500, {}));
    expect(other.fieldErrors).toHaveLength(0);
    expect(other.message).toContain('server');
  });

  it('explains the codes of systems, accounts and grants (package 1)', () => {
    expect(problemMessage(httpError(409, { code: 'PERSONAL_SYSTEMS_OFF' }))).toContain(
      'switched off',
    );
    expect(problemMessage(httpError(409, { code: 'IDENTITY_IN_USE' }))).toContain(
      'belongs to someone else',
    );
    // ACCOUNT_REFUSED: the message, then the system's reason.
    expect(
      problemMessage(httpError(400, { code: 'ACCOUNT_REFUSED', detail: 'GitHub said 401' })),
    ).toBe('The system refused this token. GitHub said 401.');
    expect(problemMessage(httpError(409, { code: 'OAUTH_NOT_AVAILABLE' }))).toContain('token');
    // A system's credentials (ADR-0072): a name taken on the system, a grant of it.
    expect(problemMessage(httpError(409, { code: 'DUPLICATE_CREDENTIAL_NAME' }))).toBe(
      'The system has a credential of this name already. Names are unique on a system, ignoring case.',
    );
    expect(problemMessage(httpError(409, { code: 'DUPLICATE_GRANT' }))).toBe(
      'This credential is already lent to this group.',
    );
  });

  it('explains the codes of model access and runner hosts (package 2)', () => {
    // A known code beats a per-status message: this 409 is about the runs, not the role.
    expect(
      problemMessage(httpError(409, { code: 'RUNNER_HOST_HAS_ACTIVE_RUNS' }), {
        'status:409': 'Something else is in the way.',
      }),
    ).toContain('Runs are queued for or running on this runner host');
    expect(problemMessage(httpError(409, { code: 'GATEWAY_NOT_CONFIGURED' }))).toContain(
      'Model access',
    );
    expect(
      problemMessage(httpError(502, { code: 'GATEWAY_UNREACHABLE', detail: 'connection refused' })),
    ).toBe('Tinfirm could not reach the model gateway. connection refused.');
  });

  it('explains the codes of agents, settings and conversations (package 3)', () => {
    expect(problemMessage(httpError(409, { code: 'AGENT_HAS_ACTIVE_RUNS' }))).toContain(
      'queued or running',
    );
    expect(problemMessage(httpError(403, { code: 'AGENT_NOT_USABLE' }))).toContain(
      'not shared with you',
    );
    expect(problemMessage(httpError(403, { code: 'AGENT_NOT_EDITABLE' }))).toContain(
      'owner and the company’s admins',
    );
    expect(problemMessage(httpError(400, { code: 'SETTINGS_WIDEN' }))).toContain('never widen');
    expect(problemMessage(httpError(409, { code: 'CONVERSATION_BOUND' }))).toContain(
      'Only the person whose conversation this is',
    );
    expect(problemMessage(httpError(409, { code: 'TURN_ACTIVE' }))).toContain('still queued');
    expect(problemMessage(httpError(409, { code: 'DECISION_DECIDED' }))).toContain(
      'already decided',
    );
    expect(problemMessage(httpError(409, { code: 'AGENT_PAUSED' }))).toContain('paused');
  });

  it('reads the reason of MODEL_ACCESS_UNAVAILABLE and NO_HOST_FOR_CONVERSATION', () => {
    const refused = httpError(409, {
      code: 'MODEL_ACCESS_UNAVAILABLE',
      details: { reason: 'GATEWAY_NOT_CONFIGURED' },
    });
    expect(problemReason(refused)).toBe('GATEWAY_NOT_CONFIGURED');
    expect(problemReason(httpError(409, { code: 'NO_HOST_FOR_CONVERSATION', details: {} }))).toBe(
      undefined,
    );
    expect(problemReason(httpError(409, { code: 'X', details: { reason: 7 } }))).toBeUndefined();
    expect(problemReason(new Error('offline'))).toBeUndefined();
  });
});

describe('conversations’ files (ADR-0014)', () => {
  it('explains the codes, with the server’s reason where it names the limit', () => {
    expect(
      problemMessage(httpError(413, { code: 'ATTACHMENT_TOO_LARGE', detail: 'At most 25 MB' })),
    ).toBe('This file is larger than the server accepts. At most 25 MB.');
    expect(problemMessage(httpError(409, { code: 'TOO_MANY_ATTACHMENTS' }))).toContain(
      'as many files',
    );
    expect(problemMessage(httpError(409, { code: 'ATTACHMENT_IS_OUTPUT' }))).toContain('output');
    expect(problemMessage(httpError(409, { code: 'DUPLICATE_ATTACHMENT_NAME' }))).toContain(
      'already has a file with this name',
    );
    expect(problemMessage(httpError(422, { code: 'CREDENTIAL_FOUND' }))).toContain('credential');
    expect(problemMessage(httpError(422, { code: 'ATTACHMENT_NOT_SCANNABLE' }))).toContain(
      'scanned',
    );
  });

  it('reads the credential matches of a refused upload, never a value', () => {
    const error = httpError(422, {
      code: 'CREDENTIAL_FOUND',
      credentialMatches: [
        { kind: 'FORMAT', name: 'AWS access key' },
        { kind: 'VAULT_SECRET', name: 'JIRA_TOKEN' },
        { kind: 'OTHER', name: 'ignored' },
        'junk',
      ],
    });
    expect(credentialMatches(error)).toEqual([
      { kind: 'FORMAT', name: 'AWS access key' },
      { kind: 'VAULT_SECRET', name: 'JIRA_TOKEN' },
    ]);
    expect(credentialMatches(httpError(422, { code: 'CREDENTIAL_FOUND' }))).toEqual([]);
    expect(credentialMatches(new Error('offline'))).toEqual([]);
  });
});

describe('the licence’s problems (ADR-0077)', () => {
  const failure = (status: number, code: string, details?: Record<string, unknown>) =>
    new HttpErrorResponse({ status, error: { status, code, details } });

  it('words SEATS_EXHAUSTED with its counts', () => {
    expect(problemMessage(failure(409, 'SEATS_EXHAUSTED', { licensed: 20, used: 20 }))).toMatch(
      /^All 20 seats of the licence are taken\./,
    );
  });

  it('words EDITION_REQUIRED before a page’s own message for a 403', () => {
    const error = failure(403, 'EDITION_REQUIRED', {
      feature: 'LEGAL_HOLDS',
      edition: 'ENTERPRISE',
    });
    expect(problemMessage(error, { 'status:403': 'Your role cannot do this.' })).toBe(
      'Legal holds need an Enterprise licence. An instance admin enters a licence key under Licence.',
    );
  });

  it('words VIEWER_READ_ONLY before a page’s own message for a 403', () => {
    expect(problemMessage(failure(403, 'VIEWER_READ_ONLY'), { 'status:403': 'No.' })).toMatch(
      /^Viewers read what is shared with them/,
    );
  });

  it('words a refused key, LICENCE_SEATS_TOO_FEW with its counts', () => {
    expect(
      problemMessage(failure(409, 'LICENCE_SEATS_TOO_FEW', { required: 3, licensed: 1 })),
    ).toContain('3 people hold a seat, and the key licenses 1 seat.');
    expect(problemMessage(failure(400, 'LICENCE_KEY_INVALID'))).toMatch(
      /^This is not a valid licence key/,
    );
  });
});
