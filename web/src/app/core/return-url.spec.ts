import {
  rememberSignInReturn,
  safeAuthorizationReturn,
  safeReturnUrl,
  takeSignInReturn,
} from './return-url';

describe('rememberSignInReturn', () => {
  afterEach(() => sessionStorage.clear());

  it('keeps a safe in-app page once, for 15 minutes', () => {
    rememberSignInReturn('/me', 1_000);
    expect(takeSignInReturn(1_000 + 60_000)).toBe('/me');
    expect(takeSignInReturn(1_000 + 60_000)).toBeNull();
    rememberSignInReturn('/me', 1_000);
    expect(takeSignInReturn(1_000 + 16 * 60_000)).toBeNull();
  });

  it('keeps nothing that would leave the app', () => {
    rememberSignInReturn('https://evil.example/', 1_000);
    expect(takeSignInReturn(1_000)).toBeNull();
    sessionStorage.setItem('tinfirm.signInReturn', JSON.stringify({ path: '//evil', at: 1_000 }));
    expect(takeSignInReturn(1_000)).toBeNull();
  });
});

describe('safeReturnUrl', () => {
  it.each([
    ['/c/1/members', '/c/1/members'],
    ['/me?tab=password', '/me?tab=password'],
    ['//evil.example', null],
    ['/\\evil.example', null],
    ['https://evil.example/', null],
    ['javascript:alert(1)', null],
    ['/login?returnUrl=/x', null],
    ['/ok\nnope', null],
    ['', null],
    [undefined, null],
  ])('%s → %s', (input, expected) => {
    expect(safeReturnUrl(input)).toBe(expected);
  });
});

describe('safeAuthorizationReturn', () => {
  it.each([
    ['/oauth2/authorize?client_id=x&state=y', '/oauth2/authorize?client_id=x&state=y'],
    ['/oauth2/authorize', '/oauth2/authorize'],
    ['/oauth2/authorizex?a=1', null],
    ['/oauth2/token', null],
    ['/c/1/inbox', null],
    ['//evil.example/oauth2/authorize', null],
    ['https://evil.example/oauth2/authorize', null],
    ['/oauth2/authorize?x=\\evil', null],
    ['/oauth2/authorize?x=\nevil', null],
    [undefined, null],
  ])('%s → %s', (input, expected) => {
    expect(safeAuthorizationReturn(input)).toBe(expected);
  });
});
