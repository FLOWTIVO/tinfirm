import { replyHtml } from './reply-body';

describe('replyHtml', () => {
  it('makes paragraphs, line breaks, bold and italics', () => {
    expect(replyHtml('Dear Ann,\n\nThank you, **really**.  \nBest, _Dev_')).toBe(
      '<p>Dear Ann,</p><p>Thank you, <strong>really</strong>.<br>Best, <em>Dev</em></p>',
    );
  });

  it('shows a link as its text and its address, and an image as a link, never as an element', () => {
    const html = replyHtml(
      'See [the report](https://e.test/r?a=1&b=2) and ![logo](https://evil.test/p.png).',
    );
    expect(html).toBe(
      '<p>See the report (https://e.test/r?a=1&amp;b=2) and logo (https://evil.test/p.png).</p>',
    );
    expect(html).not.toContain('<a');
    expect(html).not.toContain('<img');
  });

  it('shows a bare address once', () => {
    expect(replyHtml('<https://e.test/x>')).toBe('<p>https://e.test/x</p>');
  });

  it('lists items on their own lines', () => {
    expect(replyHtml('- one\n- two\n\n1. a\n2. b')).toBe(
      '<p>- one<br>- two</p><p>1. a<br>2. b</p>',
    );
  });

  it.each([
    '<kbd> <a class="fixed inset-0" href="https://evil.test/x"',
    '`x` <img src="https://evil.test/p.png?q=',
    '<code> <img src="/api/x" onerror="alert(1)"',
    '<pre> <a class="a" href="https://evil.test"',
    '<script> <img src=x',
    '<img src="https://evil.test/p.png"> and <b>text</b>',
    '<div class="x">block</div>',
  ])('turns no element of the source into an element: %s', (source) => {
    const html = replyHtml(source);
    const withoutOurs = html.replace(/<\/?(p|br|strong|em)>/g, '');
    expect(withoutOurs).not.toContain('<');
    expect(html).not.toMatch(/<[^>]*\sclass=/i);
    expect(html).not.toMatch(/<img/i);
  });

  it('keeps an attribute-bearing tag as text', () => {
    expect(replyHtml('<img src="https://evil.test/p.png">')).toBe(
      '<p>&lt;img src=&quot;https://evil.test/p.png&quot;&gt;</p>',
    );
  });
});
