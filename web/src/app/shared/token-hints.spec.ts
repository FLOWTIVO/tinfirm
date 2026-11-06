import { HttpErrorResponse } from '@angular/common/http';
import { problem } from '../testing/test-helpers';
import { accountProblemMessage, tokenHint } from './token-hints';

function failure(status: number, code?: string, detail?: string): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: problem(status, code, detail) });
}

describe('tokenHint', () => {
  it.each`
    kind            | label
    ${'JIRA_DC'}    | ${'Personal access token'}
    ${'JIRA_CLOUD'} | ${'Email and API token'}
    ${'GITHUB'}     | ${'GitHub token'}
    ${'GITLAB'}     | ${'GitLab token'}
    ${'GIT'}        | ${'Token for git'}
    ${'MCP'}        | ${'Token'}
    ${'SLACK'}      | ${'Token'}
    ${null}         | ${'Token'}
  `('asks for the token $kind takes', ({ kind, label }) => {
    expect(tokenHint(kind).label).toBe(label);
  });
});

describe('accountProblemMessage', () => {
  it.each`
    detail                                                                                          | says
    ${'The system refused the token: GitHub answered 401 when asked whose the token is'}            | ${'GitHub answered 401 when asked whose the token is.'}
    ${'The system refused the token: GitHub named no id for the token.'}                            | ${'GitHub named no id for the token.'}
    ${'The system refused the token: the request was not sent: a private address'}                  | ${'The request was not sent: a private address.'}
    ${'The platform refused the token: Slack says the token is revoked'}                            | ${'Slack says the token is revoked.'}
    ${'The system refused the client credentials: The token endpoint answered 401 (access_denied)'} | ${'The token endpoint answered 401 (access_denied).'}
    ${"The system refused the client's token: Jira answered 403 when asked whose the token is"}     | ${'Jira answered 403 when asked whose the token is.'}
    ${'GitHub answered 403 when asked whose the token is'}                                          | ${'GitHub answered 403 when asked whose the token is.'}
    ${'The system refused the token: '}                                                             | ${'GitHub refused this token.'}
    ${undefined}                                                                                    | ${'GitHub refused this token.'}
  `('says a refused token in the server’s own sentence: "$says"', ({ detail, says }) => {
    expect(accountProblemMessage(failure(400, 'ACCOUNT_REFUSED', detail), 'GitHub')).toBe(says);
  });

  it.each`
    status | code                      | words
    ${409} | ${'IDENTITY_IN_USE'}      | ${'This Jira account already belongs to someone else'}
    ${409} | ${'PERSONAL_SYSTEMS_OFF'} | ${'Personal MCP servers are switched off'}
    ${409} | ${'OAUTH_NOT_AVAILABLE'}  | ${'Jira offers no sign-in here'}
    ${409} | ${'PRINCIPAL_SUSPENDED'}  | ${'suspended'}
    ${404} | ${undefined}              | ${'Jira is gone'}
  `('says why $code ($status) did not connect', ({ status, code, words }) => {
    expect(accountProblemMessage(failure(status, code), 'Jira')).toContain(words);
  });

  it('names the company’s credential whose user it is, and whom to ask (ADR-0072)', () => {
    const shared = new HttpErrorResponse({
      status: 409,
      error: {
        ...problem(409, 'IDENTITY_IN_USE'),
        details: { holder: 'CREDENTIAL', credentialName: 'Analysts bot' },
      },
    });
    expect(accountProblemMessage(shared, 'GitHub')).toBe(
      'This GitHub account is the company’s credential “Analysts bot”, which groups act with as ' +
        'a shared account. Ask an owner or admin to give “Analysts bot” another user, such as a ' +
        'machine user; then connect yours.',
    );
  });

  it('never says which member holds the user', () => {
    const member = new HttpErrorResponse({
      status: 409,
      error: { ...problem(409, 'IDENTITY_IN_USE'), details: { holder: 'MEMBER' } },
    });
    expect(accountProblemMessage(member, 'Jira')).toBe(
      'This Jira account already belongs to someone else in this company.',
    );
  });
});
