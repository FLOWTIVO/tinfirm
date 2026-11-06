import {
  buildPreview,
  diffLines,
  MAX_CSV_COLUMNS,
  MAX_CSV_ROWS,
  MAX_DIFF_LINES,
  MAX_TEXT_BYTES,
  parseCsv,
  prettyJson,
  previewKind,
  resolveSibling,
} from './preview';

describe('previewKind (ADR-0047)', () => {
  it('decides by the extension of the last path segment, whatever the case', () => {
    expect(previewKind('report.md', 'text/markdown')).toBe('markdown');
    expect(previewKind('reports/q3.MD', 'application/octet-stream')).toBe('markdown');
    expect(previewKind('notes.TXT', null)).toBe('text');
    expect(previewKind('run.log', 'text/plain')).toBe('text');
    expect(previewKind('data.csv', 'text/csv')).toBe('csv');
    expect(previewKind('data.tsv', null)).toBe('csv');
    expect(previewKind('fix.patch', 'text/x-diff')).toBe('diff');
    expect(previewKind('out.json', 'application/json')).toBe('json');
    expect(previewKind('chart.png', 'image/png')).toBe('image');
    expect(previewKind('chart.svg', 'image/svg+xml')).toBe('image');
  });

  it('shows code by extension although the runner stores it as octet-stream', () => {
    expect(previewKind('main.py', 'application/octet-stream')).toBe('code');
    expect(previewKind('schema.sql', 'application/octet-stream')).toBe('code');
    expect(previewKind('.env', 'application/octet-stream')).toBe('code');
    expect(previewKind('.gitignore', null)).toBe('code');
    expect(previewKind('page.html', 'text/html')).toBe('code');
    expect(previewKind('config.yaml', 'application/yaml')).toBe('code');
  });

  it('knows the usual names without an extension', () => {
    expect(previewKind('Makefile', 'application/octet-stream')).toBe('code');
    expect(previewKind('docker/Dockerfile', null)).toBe('code');
    expect(previewKind('README', null)).toBe('text');
    expect(previewKind('LICENSE', null)).toBe('text');
  });

  it('falls back to the content type for an unknown name, without its parameters', () => {
    expect(previewKind('output.dat', 'text/plain')).toBe('text');
    expect(previewKind('blob.bin', 'image/png')).toBe('image');
    expect(previewKind('export', 'application/json')).toBe('json');
    expect(previewKind('export', 'application/json; charset=utf-8')).toBe('json');
    expect(previewKind('notes', 'Text/Markdown;charset=UTF-8')).toBe('markdown');
  });

  it('shows any image type and any text type the tables do not name', () => {
    expect(previewKind('favicon', 'image/x-icon')).toBe('image');
    expect(previewKind('notes.weird', 'text/x-anything')).toBe('text');
  });

  it('offers no preview for binary, office and archive files, or an unknown file', () => {
    expect(previewKind('report.pdf', 'application/pdf')).toBe('none');
    expect(previewKind('deck.pptx', null)).toBe('none');
    expect(previewKind('report.xlsx', 'application/octet-stream')).toBe('none');
    expect(previewKind('bundle.zip', 'application/zip')).toBe('none');
    expect(previewKind('x.unknownext', 'application/octet-stream')).toBe('none');
    expect(previewKind('x.unknownext', null)).toBe('none');
    // A name that is a property of Object.prototype is not a known name.
    expect(previewKind('constructor', null)).toBe('none');
    expect(previewKind('x.constructor', null)).toBe('none');
  });
});

describe('parseCsv', () => {
  it('reads quoted fields, doubled quotes, line breaks inside quotes and CRLF', () => {
    const csv = parseCsv('name,note\r\n"Smith, J","He said ""hi""\nand left"\r\nLee,ok\r\n');
    expect(csv.header).toEqual(['name', 'note']);
    expect(csv.rows).toEqual([
      ['Smith, J', 'He said "hi"\nand left'],
      ['Lee', 'ok'],
    ]);
    expect(csv.truncated).toBe(false);
  });

  it('guesses semicolons and tabs from the first line, and skips empty lines', () => {
    expect(parseCsv('a;b\n1;2\n\n3;4').rows).toEqual([
      ['1', '2'],
      ['3', '4'],
    ]);
    expect(parseCsv('a\tb\n1,5\t2').rows).toEqual([['1,5', '2']]);
  });

  it('keeps at most the given rows and says there were more', () => {
    const csv = parseCsv('h\n1\n2\n3\n4', 2);
    expect(csv.rows).toEqual([['1'], ['2']]);
    expect(csv.truncated).toBe(true);
    expect(parseCsv('h\n1\n2', 2).truncated).toBe(false);
  });

  it('keeps at most the given columns of every record, reads the rest to its end, and says so', () => {
    const csv = parseCsv('a,b,c\n1,2,"3,\nx"\n4,5,6', MAX_CSV_ROWS, 2);
    expect(csv.header).toEqual(['a', 'b']);
    expect(csv.rows).toEqual([
      ['1', '2'],
      ['4', '5'],
    ]);
    expect(csv.truncatedColumns).toBe(true);
    expect(csv.truncated).toBe(false);
    expect(parseCsv('a,b\n1,2', MAX_CSV_ROWS, 2).truncatedColumns).toBe(false);
  });

  it('keeps at most the given cells in all, the header counted, and drops the rows past them', () => {
    const csv = parseCsv('a,b\n1,2\n3,4\n5,6', MAX_CSV_ROWS, MAX_CSV_COLUMNS, 4);
    expect(csv.rows).toEqual([['1', '2']]);
    expect(csv.truncated).toBe(true);
    expect(parseCsv('a,b\n1,2', MAX_CSV_ROWS, MAX_CSV_COLUMNS, 4).truncated).toBe(false);
  });

  it('reads a file without a trailing line break and an empty file', () => {
    expect(parseCsv('a,b\n1,2')).toEqual({
      header: ['a', 'b'],
      rows: [['1', '2']],
      truncated: false,
      truncatedColumns: false,
    });
    expect(parseCsv('')).toEqual({
      header: [],
      rows: [],
      truncated: false,
      truncatedColumns: false,
    });
  });
});

describe('diffLines', () => {
  it('tells headers, hunks, changes and context apart, and drops the markers', () => {
    const { lines } = diffLines(
      [
        'diff --git a/x.sql b/x.sql',
        'index 1..2 100644',
        '--- a/x.sql',
        '+++ b/x.sql',
        '@@ -1,3 +1,3 @@',
        ' select 1',
        '--- a comment removed',
        '+-- a comment added',
        '\\ No newline at end of file',
      ].join('\n'),
    );
    expect(lines.map((l) => l.type)).toEqual([
      'meta',
      'meta',
      'meta',
      'meta',
      'hunk',
      'context',
      'del',
      'add',
      'meta',
    ]);
    expect(lines.slice(4).map((l) => l.text)).toEqual([
      '@@ -1,3 +1,3 @@',
      'select 1',
      '-- a comment removed',
      '-- a comment added',
      '\\ No newline at end of file',
    ]);
  });

  it('starts a new file at a --- and +++ pair without a diff line', () => {
    const types = diffLines(
      '--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y\n--- c\n+++ d\n@@ -1 +1 @@\n-p\n+q',
    ).lines.map((l) => l.type);
    expect(types).toEqual([
      'meta',
      'meta',
      'hunk',
      'del',
      'add',
      'meta',
      'meta',
      'hunk',
      'del',
      'add',
    ]);
  });

  it('keeps at most the given lines and says there were more', () => {
    const diff = diffLines('@@ -1 +1 @@\n' + '+x\n'.repeat(4), 3);
    expect(diff.lines.map((l) => l.type)).toEqual(['hunk', 'add', 'add']);
    expect(diff.truncated).toBe(true);
    expect(diffLines('@@ -1 +1 @@\n+x\n+y\n', 3).truncated).toBe(false);
  });
});

describe('prettyJson', () => {
  it('indents JSON and leaves anything else as it is', () => {
    expect(prettyJson('{"a":[1,2]}')).toBe('{\n  "a": [\n    1,\n    2\n  ]\n}');
    expect(prettyJson('{"a":')).toBe('{"a":');
  });

  it('keeps the text when indenting would change a number', () => {
    // Beyond 2^53 JavaScript rounds; a preview must show the file's own numbers.
    expect(prettyJson('{"id":9007199254740993}')).toBe('{"id":9007199254740993}');
    expect(prettyJson('{"v":1.0}')).toBe('{"v":1.0}');
    expect(prettyJson('[1e2]')).toBe('[1e2]');
    expect(prettyJson('{"n":-1.5e-3}')).toBe('{"n":-1.5e-3}');
    // Digits inside strings are not numbers.
    expect(prettyJson('{"s":"9007199254740993","n":2}')).toBe(
      '{\n  "s": "9007199254740993",\n  "n": 2\n}',
    );
  });
});

describe('resolveSibling', () => {
  it("resolves a relative reference against the document's folder", () => {
    expect(resolveSibling('reports/q3.md', 'chart.png')).toBe('reports/chart.png');
    expect(resolveSibling('reports/q3.md', './img/chart.png')).toBe('reports/img/chart.png');
    expect(resolveSibling('reports/q3.md', '../data/q3.csv')).toBe('data/q3.csv');
    expect(resolveSibling('q3.md', 'chart.png')).toBe('chart.png');
    expect(resolveSibling('q3.md', 'a%20b.png?x=1#top')).toBe('a b.png');
  });

  it('refuses URLs, absolute paths, anchors, bad escapes and paths above the files', () => {
    expect(resolveSibling('q3.md', 'https://example.com/x.png')).toBeNull();
    expect(resolveSibling('q3.md', 'mailto:a@b.c')).toBeNull();
    expect(resolveSibling('q3.md', '/api/x')).toBeNull();
    expect(resolveSibling('q3.md', '//evil/x')).toBeNull();
    expect(resolveSibling('q3.md', '#section')).toBeNull();
    expect(resolveSibling('q3.md', '?q')).toBeNull();
    expect(resolveSibling('q3.md', '')).toBeNull();
    expect(resolveSibling('q3.md', 'bad%')).toBeNull();
    expect(resolveSibling('reports/q3.md', '../../x.png')).toBeNull();
    expect(resolveSibling('q3.md', '.')).toBeNull();
  });
});

describe('buildPreview', () => {
  it('reads text up to the cap, cut at a whole line', async () => {
    const line = 'x'.repeat(99) + '\n';
    const blob = new Blob([line.repeat(MAX_TEXT_BYTES / 100 + 5)]);
    const preview = await buildPreview('text', blob);
    expect(preview.kind).toBe('text');
    if (preview.kind !== 'text') throw new Error();
    expect(preview.truncated).toBe(true);
    expect(preview.text.endsWith('\n')).toBe(true);
    expect(preview.text.length).toBe(MAX_TEXT_BYTES - (MAX_TEXT_BYTES % 100));
  });

  it('indents JSON and parses CSV, and reads nothing for an image', async () => {
    const json = await buildPreview('json', new Blob(['{"a":1}']));
    expect(json).toEqual({ kind: 'json', text: '{\n  "a": 1\n}', truncated: false });
    const csv = await buildPreview('csv', new Blob(['a,b\n1,2\n']));
    expect(csv).toEqual({
      kind: 'csv',
      header: ['a', 'b'],
      rows: [['1', '2']],
      truncated: false,
      truncatedColumns: false,
    });
    expect(await buildPreview('image', new Blob(['not read']))).toEqual({ kind: 'image' });
    expect(await buildPreview('none', new Blob(['not read']))).toEqual({ kind: 'none' });
  });

  it('caps a file of a million lines at the lines of a diff, and reads them as no rows of a CSV', async () => {
    const blob = new Blob(['\n'.repeat(MAX_TEXT_BYTES)]);
    const diff = await buildPreview('diff', blob);
    expect(diff).toMatchObject({ kind: 'diff', truncated: true });
    if (diff.kind !== 'diff') throw new Error();
    expect(diff.lines).toHaveLength(MAX_DIFF_LINES);
    // Empty lines are no rows: nothing to show, and nothing left out.
    expect(await buildPreview('csv', blob)).toEqual({
      kind: 'csv',
      header: [],
      rows: [],
      truncated: false,
      truncatedColumns: false,
    });
  });

  it('decodes UTF-8 and drops the byte order mark', async () => {
    const preview = await buildPreview(
      'text',
      new Blob([new Uint8Array([0xef, 0xbb, 0xbf]), 'Žluťoučký']),
    );
    expect(preview).toEqual({ kind: 'text', text: 'Žluťoučký', truncated: false });
  });
});
