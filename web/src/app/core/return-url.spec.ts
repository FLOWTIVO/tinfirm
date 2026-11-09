import { safeAuthorizationReturn, safeReturnUrl, ssoStartUrl } from './return-url';

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

describe('ssoStartUrl', () => {
  const start = '/oauth2/authorization/keycloak';

  it.each([
    ['/me', `${start}?returnTo=%2Fme`],
    ['/me?tab=tokens', `${start}?returnTo=%2Fme%3Ftab%3Dtokens`],
    ['/c/1/conversations/2?filter=a%2Fb', `${start}?returnTo=%2Fc%2F1%2Fconversations%2F2%3Ffilter%3Da%252Fb`],
    // Nothing to come back to, or nothing the server would take: the start stays as it is.
    [undefined, start],
    ['', start],
    ['/', start],
    ['/?account=x', start],
    ['//evil.example', start],
    ['/\\evil.example', start],
    ['https://evil.example/', start],
    ['/me\nx', start],
    ['/me#x', start],
    ['/api/me', start],
    ['/API/me', start],
    ['/oauth2/authorize?client_id=x', start],
    ['/login?returnUrl=/me', start],
    ['/saml2/authenticate/kc', start],
  ])('%s → %s', (page, expected) => {
    expect(ssoStartUrl(start, page)).toBe(expected);
  });

  it('works for a SAML start too', () => {
    expect(ssoStartUrl('/saml2/authenticate/kc', '/me')).toBe('/saml2/authenticate/kc?returnTo=%2Fme');
  });
});
