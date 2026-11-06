import { escapeHtml, renderMarkdown } from './markdown';

describe('renderMarkdown mentions (ADR-0034)', () => {
  const NAMES = new Map([
    ['test-writer', 'Test writer'],
    ['qa', 'Reviewer <QA>'],
  ]);

  it('shows the mention of a known agent in bold, with its name as the title', () => {
    expect(renderMarkdown('@Test-Writer please, and @qa.', NAMES)).toBe(
      '<p><strong class="mention" title="Test writer">@Test-Writer</strong> please, and ' +
        '<strong class="mention" title="Reviewer &lt;QA&gt;">@qa</strong>.</p>\n',
    );
  });

  it('leaves code, addresses, unknown handles and longer words alone', () => {
    const html = renderMarkdown(
      '`@test-writer` and\n\n```\n@qa\n```\n\nmail ops@qa.example, @nobody, @test-writers',
      NAMES,
    );
    expect(html).not.toContain('<strong');
    expect(html).toContain('<code>@test-writer</code>');
  });

  it('finds mentions inside emphasis, lists and links', () => {
    const html = renderMarkdown('- *ask @qa*\n- [@test-writer](https://example.test)', NAMES);
    expect(html.match(/class="mention"/g)).toHaveLength(2);
  });

  it('renders no mention without names', () => {
    expect(renderMarkdown('@test-writer please')).toBe('<p>@test-writer please</p>\n');
    expect(renderMarkdown('@test-writer please', new Map())).toBe('<p>@test-writer please</p>\n');
  });
});

describe("renderMarkdown with a document's files (ADR-0047)", () => {
  const files = (href: string) =>
    href === 'chart.png' ? '/api/f/chart' : href === 'data.csv' ? '/api/f/data' : null;

  it("shows the document's own images and links its own files, without a new tab", () => {
    expect(renderMarkdown('![Rev "Q3"](chart.png "Q3") and [data](data.csv)', null, files)).toBe(
      '<p><img src="/api/f/chart" alt="Rev &quot;Q3&quot;" title="Q3"> and ' +
        '<a href="/api/f/data">data</a></p>\n',
    );
  });

  it('keeps the rules for everything else', () => {
    expect(
      renderMarkdown('![x](https://e.test/x.png) [y](other.md) [z](https://e.test)', null, files),
    ).toBe(
      '<p><a href="https://e.test/x.png" target="_blank" rel="noopener noreferrer">x</a> y ' +
        '<a href="https://e.test" target="_blank" rel="noopener noreferrer">z</a></p>\n',
    );
    expect(renderMarkdown('![x](chart.png)')).toBe('<p>x</p>\n');
  });
});

describe('renderMarkdown raw HTML (M10)', () => {
  // Text after an inline <kbd>, <code>, <pre> or <script> opener may begin a tag that is never
  // closed; marked calls it escaped, and the next `</p>` would close the tag, with its attributes.
  it.each([
    ['x <kbd> <a class="fixed inset-0 z-50" href="/x"'],
    ['x <code> <img src="/api/secret"'],
    ['x <pre> <a class="fixed" href="/x"'],
    ['x <script> <img src=x'],
    ['text <kbd>Ctrl</kbd> <a class="x" href="/x"'],
    ['<kbd> <a class="fixed" href="/x"'],
    ['<script> <img src=x'],
  ])('shows an unterminated tag after an opener as text: %s', (source) => {
    const html = renderMarkdown(source);
    expect(html).not.toMatch(/<(a|img|kbd|code|pre|script)\b/i);
    expect(html).not.toMatch(/<[^>]*\sclass=/i);
    expect(html.replace(/<\/?p>/g, '').trim()).toBe(escapeHtml(source));
  });

  it('still bolds a mention in text that follows an opener, on the escaped text', () => {
    const html = renderMarkdown('<kbd> @qa <img src="x"', new Map([['qa', 'QA']]));
    expect(html).toContain('<strong class="mention" title="QA">@qa</strong>');
    expect(html).not.toMatch(/<img/i);
    expect(html).toContain('&lt;img src=&quot;x&quot;');
  });

  it('escapes the quotes and ampersands of plain text', () => {
    expect(renderMarkdown('a & "b" <c>')).toBe('<p>a &amp; &quot;b&quot; &lt;c&gt;</p>\n');
  });
});

describe('renderMarkdown links', () => {
  it('refuses a backslash after the slash, which browsers read as a second slash', () => {
    expect(renderMarkdown('[x](/\\evil.test)')).not.toContain('<a');
    expect(renderMarkdown('[x](/a\\b)')).not.toContain('<a');
    expect(renderMarkdown('[x](//evil.test)')).not.toContain('<a');
  });

  it('keeps in-app paths and anchors', () => {
    expect(renderMarkdown('[x](/c/1/tasks)')).toContain('<a href="/c/1/tasks">x</a>');
    expect(renderMarkdown('[x](#top)')).toContain('<a href="#top">x</a>');
  });
});
