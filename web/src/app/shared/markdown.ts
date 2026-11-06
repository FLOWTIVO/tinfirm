import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Marked, type Tokens } from 'marked';
import { mentionMatches } from './handles';

/** Beyond this size, text is shown as-is: parsing it on every live update would stall the page. */
const MAX_MARKDOWN_CHARS = 200_000;

const ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ESCAPES[c]);
}

/** The agents' names by handle while {@link renderMarkdown} renders text with mentions. */
let activeMentions: ReadonlyMap<string, string> | null = null;

/**
 * A document's own reference (`chart.png`, `../data/q3.csv`) as a same-origin URL, or null when
 * it is not one of the document's files. A resolved image is shown, a resolved link opens the
 * file; everything else keeps the rules below.
 */
export type FileResolver = (href: string) => string | null;

/** The resolver of the document {@link renderMarkdown} renders, while it renders. */
let activeFiles: FileResolver | null = null;

/**
 * `@handle` of a known agent in bold, with the agent's name as its title (ADR-0034). Only text
 * reaches this, never code, so a handle in code stays as it is.
 */
function boldMentions(html: string, names: ReadonlyMap<string, string>): string {
  let result = '';
  let from = 0;
  for (const match of mentionMatches(html)) {
    const name = names.get(match.handle.toLowerCase());
    if (name === undefined) continue;
    const end = match.index + 1 + match.handle.length;
    result += html.slice(from, match.index);
    result += `<strong class="mention" title="${escapeHtml(name)}">${html.slice(match.index, end)}</strong>`;
    from = end;
  }
  return result + html.slice(from);
}

/** Only web links, mail links and in-app paths or anchors become links. */
function safeHref(href: string | null | undefined): string | null {
  const value = (href ?? '').trim();
  if (/^(https?:|mailto:)/i.test(value)) return value;
  // A backslash is read as a slash by browsers: `/\host` is `//host`, a link to another origin.
  if (/^[/#]/.test(value) && !value.startsWith('//') && !value.includes('\\')) return value;
  return null;
}

const marked = new Marked({
  gfm: true,
  breaks: false,
  async: false,
  renderer: {
    // Raw HTML in the source is shown as text, never interpreted.
    html(token: Tokens.HTML | Tokens.Tag): string {
      return escapeHtml(token.text);
    },
    // A document's own image is shown from its same-origin URL. External images would be blocked
    // by the CSP anyway; show them as links.
    image(token: Tokens.Image): string {
      const own = activeFiles?.(token.href) ?? null;
      if (own) {
        const title = token.title ? ` title="${escapeHtml(token.title)}"` : '';
        return `<img src="${escapeHtml(own)}" alt="${escapeHtml(token.text)}"${title}>`;
      }
      const href = safeHref(token.href);
      const label = escapeHtml(token.text || token.href);
      return href
        ? `<a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">${label}</a>`
        : label;
    },
    link(token: Tokens.Link): string {
      const inner = this.parser.parseInline(token.tokens);
      const own = activeFiles?.(token.href) ?? null;
      const href = own ?? safeHref(token.href);
      if (!href) return inner;
      const title = token.title ? ` title="${escapeHtml(token.title)}"` : '';
      const external =
        !own && /^(https?:|mailto:)/i.test(href)
          ? ' target="_blank" rel="noopener noreferrer"'
          : '';
      return `<a href="${escapeHtml(href)}"${title}${external}>${inner}</a>`;
    },
    // Task-list checkboxes as glyphs: <input> is not allowed by the sanitizer.
    checkbox(token: Tokens.Checkbox): string {
      return `<span class="md-task">${token.checked ? '☑' : '☐'}</span> `;
    },
    // Plain text, with the mentions of known agents in bold when the caller names them.
    text(token: Tokens.Text | Tokens.Escape): string {
      if ('tokens' in token && token.tokens) return this.parser.parseInline(token.tokens);
      // Always escaped: marked's `escaped` flag also covers text after an inline `<kbd>`, `<code>`,
      // `<pre>` or `<script>` opener that begins an unterminated tag, which the sanitizer would keep.
      const html = escapeHtml(token.text);
      return token.type === 'text' && activeMentions ? boldMentions(html, activeMentions) : html;
    },
  },
});

/**
 * Markdown to an HTML string. The result is only ever bound through `[innerHTML]`, so Angular's
 * sanitizer still runs over it; raw HTML in the source is escaped before that. With `mentions`
 * (agent names by lower-case handle), `@handle` of those agents outside code is shown in bold.
 * With `files`, a document's references to its own files (the preview of a document, ADR-0047)
 * become images and links of their same-origin URLs.
 */
export function renderMarkdown(
  source: string,
  mentions: ReadonlyMap<string, string> | null = null,
  files: FileResolver | null = null,
): string {
  if (source.length > MAX_MARKDOWN_CHARS) {
    return `<pre><code>${escapeHtml(source)}</code></pre>`;
  }
  // Parsing is synchronous, so the names and the resolver apply to this source only.
  activeMentions = mentions && mentions.size > 0 ? mentions : null;
  activeFiles = files;
  try {
    return marked.parse(source) as string;
  } catch {
    return `<p>${escapeHtml(source)}</p>`;
  } finally {
    activeMentions = null;
    activeFiles = null;
  }
}

/**
 * Renders untrusted Markdown (task descriptions, comments, agent output). The HTML goes through
 * Angular's sanitizer via `[innerHTML]`; this component never bypasses it.
 */
@Component({
  selector: 'app-markdown',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'markdown block min-w-0' },
  template: `<div [innerHTML]="html()"></div>`,
})
export class Markdown {
  readonly text = input.required<string>();
  /** Agents' names by lower-case handle: their mentions show in bold (ADR-0034). */
  readonly mentions = input<ReadonlyMap<string, string> | null>(null);
  /** The document's own files by relative reference, when the text is a document (ADR-0047). */
  readonly files = input<FileResolver | null>(null);
  protected readonly html = computed(() =>
    renderMarkdown(this.text(), this.mentions(), this.files()),
  );
}
