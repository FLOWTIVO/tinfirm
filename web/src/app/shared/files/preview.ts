/**
 * What the web app shows of a file without interpreting it (ADR-0047): Markdown through the
 * renderer of `shared/markdown`, text and code escaped by Angular's bindings, CSV as a table, a
 * unified diff with its lines coloured, and images through an `img` element, which runs no script
 * even for SVG. Everything else is downloaded. The kind comes from the file's name first, because
 * the stored content type is the name's extension too (the runner host's table, or the browser's)
 * and says `application/octet-stream` for most code; the content type decides only for a name
 * without a known extension.
 */
export type PreviewKind = 'markdown' | 'text' | 'code' | 'json' | 'csv' | 'diff' | 'image' | 'none';

/** The most a text preview reads of a file. */
export const MAX_TEXT_BYTES = 1024 * 1024;

/** Rows of a CSV shown, the header apart. */
export const MAX_CSV_ROWS = 500;

/** Columns of a CSV shown, of every row. */
export const MAX_CSV_COLUMNS = 100;

/** Cells of a CSV shown in all, the header's counted: the rows past it are left out. */
export const MAX_CSV_CELLS = 20_000;

/** Lines of a diff shown. */
export const MAX_DIFF_LINES = 5000;

/** Up to this size, JSON is shown pretty-printed; beyond it as it is. */
const MAX_PRETTY_JSON_BYTES = 256 * 1024;

const KIND_BY_EXTENSION = new Map<string, PreviewKind>([
  ['md', 'markdown'],
  ['markdown', 'markdown'],
  ['mdown', 'markdown'],
  ['mkd', 'markdown'],
  ['txt', 'text'],
  ['text', 'text'],
  ['log', 'text'],
  ['rst', 'text'],
  ['adoc', 'text'],
  ['asciidoc', 'text'],
  ['csv', 'csv'],
  ['tsv', 'csv'],
  ['diff', 'diff'],
  ['patch', 'diff'],
  ['json', 'json'],
  ['geojson', 'json'],
  ['ipynb', 'json'],
  ['webmanifest', 'json'],
  ['png', 'image'],
  ['jpg', 'image'],
  ['jpeg', 'image'],
  ['gif', 'image'],
  ['webp', 'image'],
  ['svg', 'image'],
  ['bmp', 'image'],
  ['avif', 'image'],
  ['apng', 'image'],
]);

const CODE_EXTENSIONS = new Set([
  'ts',
  'tsx',
  'js',
  'jsx',
  'mjs',
  'cjs',
  'java',
  'kt',
  'kts',
  'rs',
  'go',
  'py',
  'rb',
  'php',
  'cs',
  'c',
  'h',
  'cpp',
  'hpp',
  'cc',
  'hh',
  'm',
  'swift',
  'scala',
  'sql',
  'sh',
  'bash',
  'zsh',
  'fish',
  'ps1',
  'bat',
  'cmd',
  'yaml',
  'yml',
  'toml',
  'ini',
  'cfg',
  'conf',
  'properties',
  'env',
  'xml',
  'xsl',
  'xsd',
  'html',
  'htm',
  'xhtml',
  'css',
  'scss',
  'less',
  'vue',
  'svelte',
  'graphql',
  'gql',
  'proto',
  'tf',
  'tfvars',
  'hcl',
  'gradle',
  'groovy',
  'lua',
  'pl',
  'pm',
  'r',
  'dart',
  'ex',
  'exs',
  'erl',
  'hs',
  'clj',
  'cljs',
  'edn',
  'lisp',
  'el',
  'vim',
  'mdx',
  'jsonl',
  'ndjson',
  'tex',
  'bib',
  'sty',
  'mk',
  'cmake',
  'dockerfile',
  'gitignore',
  'gitattributes',
  'dockerignore',
  'editorconfig',
  'npmrc',
  'nvmrc',
  'prettierrc',
  'eslintrc',
  'babelrc',
  'htaccess',
  'lock',
  'sum',
  'mod',
  'nix',
  'jl',
  'f90',
  'asm',
  's',
  'v',
  'sv',
  'vhd',
  'zig',
  'nim',
  'cr',
  'ml',
  'mli',
  'fs',
  'fsx',
  'rake',
  'gemspec',
  'podspec',
  'plist',
  'cue',
  'dhall',
  'rego',
  'puml',
  'plantuml',
  'mmd',
  'mermaid',
  'dot',
  'gv',
  'pug',
  'jade',
  'haml',
  'erb',
  'ejs',
  'hbs',
  'mustache',
  'twig',
  'jinja',
  'j2',
  'liquid',
]);

/** Files that are text although their name has no extension. */
const KIND_BY_NAME = new Map<string, PreviewKind>([
  ['makefile', 'code'],
  ['dockerfile', 'code'],
  ['containerfile', 'code'],
  ['jenkinsfile', 'code'],
  ['procfile', 'code'],
  ['gemfile', 'code'],
  ['rakefile', 'code'],
  ['vagrantfile', 'code'],
  ['brewfile', 'code'],
  ['readme', 'text'],
  ['license', 'text'],
  ['licence', 'text'],
  ['copying', 'text'],
  ['changelog', 'text'],
  ['changes', 'text'],
  ['notice', 'text'],
  ['authors', 'text'],
  ['contributors', 'text'],
  ['todo', 'text'],
]);

const KIND_BY_CONTENT_TYPE = new Map<string, PreviewKind>([
  ['text/markdown', 'markdown'],
  ['text/x-markdown', 'markdown'],
  ['text/plain', 'text'],
  ['text/csv', 'csv'],
  ['text/tab-separated-values', 'csv'],
  ['text/x-diff', 'diff'],
  ['text/x-patch', 'diff'],
  ['application/json', 'json'],
  ['application/ld+json', 'json'],
  ['application/geo+json', 'json'],
  ['application/xml', 'code'],
  ['text/xml', 'code'],
  ['application/yaml', 'code'],
  ['application/x-yaml', 'code'],
  ['text/yaml', 'code'],
  ['text/x-yaml', 'code'],
  ['application/toml', 'code'],
  ['application/javascript', 'code'],
  ['text/javascript', 'code'],
  ['application/x-sh', 'code'],
  ['application/sql', 'code'],
  ['application/x-ndjson', 'code'],
  ['text/html', 'code'],
  ['text/css', 'code'],
  ['text/x-python', 'code'],
  ['text/x-java-source', 'code'],
  ['text/x-c', 'code'],
  ['image/png', 'image'],
  ['image/jpeg', 'image'],
  ['image/gif', 'image'],
  ['image/webp', 'image'],
  ['image/svg+xml', 'image'],
  ['image/bmp', 'image'],
  ['image/avif', 'image'],
  ['image/apng', 'image'],
]);

/**
 * How the web app can show a file, from its name (a path from `/output` keeps its folders), else
 * from its content type without parameters: a known type, any image type, any text type.
 */
export function previewKind(fileName: string, contentType: string | null | undefined): PreviewKind {
  const base = fileName.slice(fileName.lastIndexOf('/') + 1).toLowerCase();
  const dot = base.lastIndexOf('.');
  const extension = dot >= 0 ? base.slice(dot + 1) : '';
  if (extension) {
    const byExtension =
      KIND_BY_EXTENSION.get(extension) ?? (CODE_EXTENSIONS.has(extension) ? 'code' : undefined);
    if (byExtension) return byExtension;
  } else {
    const byName = KIND_BY_NAME.get(base);
    if (byName) return byName;
  }
  const type = (contentType ?? '').split(';')[0].trim().toLowerCase();
  const byType = KIND_BY_CONTENT_TYPE.get(type);
  if (byType) return byType;
  if (type.startsWith('image/')) return 'image';
  return type.startsWith('text/') ? 'text' : 'none';
}

export interface TextPreview {
  readonly kind: 'markdown' | 'text' | 'code' | 'json';
  readonly text: string;
  /** The file is longer than what is shown. */
  readonly truncated: boolean;
}

export interface CsvPreview {
  readonly kind: 'csv';
  readonly header: readonly string[];
  readonly rows: readonly (readonly string[])[];
  /** The file has more rows than are shown. */
  readonly truncated: boolean;
  /** The file has more columns than are shown. */
  readonly truncatedColumns: boolean;
}

export type DiffLineType = 'meta' | 'hunk' | 'add' | 'del' | 'context';

export interface DiffLine {
  readonly type: DiffLineType;
  readonly text: string;
}

export interface DiffPreview {
  readonly kind: 'diff';
  readonly lines: readonly DiffLine[];
  /** The file has more lines than are shown. */
  readonly truncated: boolean;
}

export interface ImagePreview {
  readonly kind: 'image';
}

export interface NoPreview {
  readonly kind: 'none';
}

export type Preview = TextPreview | CsvPreview | DiffPreview | ImagePreview | NoPreview;

/** The text up to its last line break, so a cut file does not end in half a line. */
function wholeLines(text: string): string {
  const cut = text.lastIndexOf('\n');
  return cut > 0 ? text.slice(0, cut + 1) : text;
}

/**
 * The preview of a file's content. Text is read up to {@link MAX_TEXT_BYTES} and decoded as
 * UTF-8; an image is shown by the browser from its download URL, so nothing is read here.
 */
export async function buildPreview(kind: PreviewKind, blob: Blob): Promise<Preview> {
  if (kind === 'image' || kind === 'none') return { kind };
  const truncated = blob.size > MAX_TEXT_BYTES;
  let text = await (truncated ? blob.slice(0, MAX_TEXT_BYTES) : blob).text();
  if (truncated) text = wholeLines(text);
  switch (kind) {
    case 'csv': {
      const csv = parseCsv(text);
      return {
        kind,
        header: csv.header,
        rows: csv.rows,
        truncated: truncated || csv.truncated,
        truncatedColumns: csv.truncatedColumns,
      };
    }
    case 'diff': {
      const diff = diffLines(text);
      return { kind, lines: diff.lines, truncated: truncated || diff.truncated };
    }
    case 'json':
      return {
        kind,
        text: truncated || blob.size > MAX_PRETTY_JSON_BYTES ? text : prettyJson(text),
        truncated,
      };
    default:
      return { kind, text, truncated };
  }
}

/** The number literals of a JSON text, in order, outside strings. */
const JSON_NUMBERS = /"(?:[^"\\]|\\.)*"|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g;

function numberLiterals(json: string): string[] {
  const numbers: string[] = [];
  for (const match of json.matchAll(JSON_NUMBERS)) {
    if (match[1] !== undefined) numbers.push(match[1]);
  }
  return numbers;
}

/**
 * Indented JSON, or the text as it is when it is not JSON or when indenting would change a
 * number: JavaScript reads an integer beyond 2^53 or `1.0` and writes something else, and a
 * preview must show the file's own numbers.
 */
export function prettyJson(text: string): string {
  let pretty: string;
  try {
    pretty = JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
  const before = numberLiterals(text);
  const after = numberLiterals(pretty);
  const same = before.length === after.length && before.every((n, i) => n === after[i]);
  return same ? pretty : text;
}

/** The delimiter that occurs most in the first line: comma, semicolon or tab. */
function detectDelimiter(text: string): string {
  const end = text.search(/\r?\n/);
  const line = end >= 0 ? text.slice(0, end) : text;
  let best = ',';
  let bestCount = -1;
  for (const candidate of [',', ';', '\t']) {
    const count = line.split(candidate).length - 1;
    if (count > bestCount) {
      best = candidate;
      bestCount = count;
    }
  }
  return best;
}

/**
 * RFC 4180 CSV (quoted fields, doubled quotes, line breaks inside quotes), with the delimiter
 * guessed from the first line. The first record is the header. Empty lines are skipped. At most
 * `maxRows` rows and `maxCells` cells in all (the header's counted) are kept; `truncated` says
 * there were more. Of every record the first `maxColumns` fields are kept, the rest read to its
 * end and dropped; `truncatedColumns` says there were more.
 */
export function parseCsv(
  text: string,
  maxRows = MAX_CSV_ROWS,
  maxColumns = MAX_CSV_COLUMNS,
  maxCells = MAX_CSV_CELLS,
): { header: string[]; rows: string[][]; truncated: boolean; truncatedColumns: boolean } {
  const delimiter = detectDelimiter(text);
  const records: string[][] = [];
  let record: string[] = [];
  let field = '';
  let quoted = false;
  let truncated = false;
  let truncatedColumns = false;
  let cells = 0;

  const endField = (): void => {
    if (record.length < maxColumns) record.push(field);
    else truncatedColumns = true;
    field = '';
  };

  const endRecord = (): boolean => {
    endField();
    const empty = record.length === 1 && record[0] === '';
    if (!empty) {
      if (records.length > maxRows || (records.length > 0 && cells + record.length > maxCells)) {
        truncated = true;
        return false;
      }
      records.push(record);
      cells += record.length;
    }
    record = [];
    return true;
  };

  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          quoted = false;
        }
      } else {
        field += c;
      }
    } else if (c === '"' && field === '') {
      quoted = true;
    } else if (c === delimiter) {
      endField();
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      if (!endRecord()) break;
    } else {
      field += c;
    }
  }
  if (!truncated && (field !== '' || record.length > 0)) endRecord();

  const [header = [], ...rows] = records;
  return { header, rows, truncated, truncatedColumns };
}

/**
 * The lines of a unified diff by kind: what comes before the first hunk and between files is
 * `meta` (`diff --git`, `index`, `--- a/x`, `+++ b/x`, modes), `@@` lines are `hunk`, and inside
 * a hunk a leading `+` or `-` is a change. A line `--- x` inside a hunk is a removed line, unless
 * `+++ y` follows it: then a new file starts without a `diff` line. A hunk line's text is the
 * line without its marker (`+`, `-` or the space of a context line). At most `maxLines` lines are
 * kept; `truncated` says there were more.
 */
export function diffLines(
  text: string,
  maxLines = MAX_DIFF_LINES,
): { lines: DiffLine[]; truncated: boolean } {
  const lines = text.split('\n');
  if (lines[lines.length - 1] === '') lines.pop();
  const result: DiffLine[] = [];
  let inHunk = false;
  for (let i = 0; i < Math.min(lines.length, maxLines); i++) {
    const line = lines[i];
    let type: DiffLineType;
    if (line.startsWith('@@')) {
      inHunk = true;
      type = 'hunk';
    } else if (line.startsWith('diff ') || line.startsWith('Index: ')) {
      inHunk = false;
      type = 'meta';
    } else if (inHunk && line.startsWith('--- ') && lines[i + 1]?.startsWith('+++ ')) {
      inHunk = false;
      type = 'meta';
    } else if (!inHunk || line.startsWith('\\')) {
      type = 'meta';
    } else if (line.startsWith('+')) {
      type = 'add';
    } else if (line.startsWith('-')) {
      type = 'del';
    } else {
      type = 'context';
    }
    const hunkLine = type === 'add' || type === 'del' || type === 'context';
    result.push({ type, text: hunkLine ? line.slice(1) : line });
  }
  return { lines: result, truncated: lines.length > maxLines };
}

/**
 * A reference of a document (`chart.png`, `../data/q3.csv`, `a%20b.png?x#y`) as the name of
 * another file of the same conversation, resolved against the document's own path. Null for a URL
 * with a scheme, an absolute path, an anchor, a query, or a path that leads above the files.
 */
export function resolveSibling(from: string, href: string): string | null {
  const ref = href.trim();
  if (
    ref === '' ||
    /^[a-z][a-z0-9+.-]*:/i.test(ref) ||
    ref.startsWith('/') ||
    ref.startsWith('#') ||
    ref.startsWith('?')
  ) {
    return null;
  }
  const end = ref.search(/[?#]/);
  const path = end >= 0 ? ref.slice(0, end) : ref;
  let decoded: string;
  try {
    decoded = decodeURIComponent(path);
  } catch {
    return null;
  }
  const directory = from.slice(0, from.lastIndexOf('/') + 1);
  const segments: string[] = [];
  for (const segment of (directory + decoded).split('/')) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') {
      if (segments.length === 0) return null;
      segments.pop();
      continue;
    }
    segments.push(segment);
  }
  return segments.length > 0 ? segments.join('/') : null;
}
