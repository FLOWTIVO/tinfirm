import { lexer, type Token, type Tokens } from 'marked';
import { escapeHtml } from '../../shared/markdown';

/**
 * The body of Outlook's reply form for an agent's Markdown answer. Outlook shows it as it is: no
 * sanitizer and no CSP of ours stand in front of it, and the text may come from an email an
 * agent read. So it is built from escaped text alone: paragraphs, line breaks, bold and italics
 * without attributes, and a link as `text (url)`. Nothing of the source is ever HTML, and no
 * image is ever loaded.
 */
export function replyHtml(markdown: string): string {
  let tokens: Token[];
  try {
    tokens = lexer(markdown);
  } catch {
    tokens = [{ type: 'paragraph', raw: markdown, text: markdown, tokens: [] } as Tokens.Paragraph];
  }
  const paragraphs = blocks(tokens).filter((paragraph) => paragraph.trim() !== '');
  return paragraphs.map((paragraph) => `<p>${paragraph.replace(/\n/g, '<br>')}</p>`).join('');
}

/** The blocks as escaped paragraphs, whose line breaks are `\n`. */
function blocks(tokens: Token[]): string[] {
  const out: string[] = [];
  for (const token of tokens) {
    switch (token.type) {
      case 'space':
      case 'hr':
        break;
      case 'paragraph':
      case 'heading':
      case 'text':
        out.push(inline((token as Tokens.Paragraph).tokens ?? [], token.raw));
        break;
      case 'blockquote':
        out.push(...blocks((token as Tokens.Blockquote).tokens));
        break;
      case 'list': {
        const list = token as Tokens.List;
        const start = list.ordered ? Number(list.start) || 1 : 0;
        out.push(
          list.items
            .map(
              (item, i) =>
                `${list.ordered ? `${start + i}.` : '-'} ${blocks(item.tokens).join(' ')}`,
            )
            .join('\n'),
        );
        break;
      }
      case 'code':
        out.push(escapeHtml((token as Tokens.Code).text));
        break;
      default:
        // HTML, tables and anything else: the source as text.
        out.push(escapeHtml(token.raw.trim()));
    }
  }
  return out;
}

/** Inline tokens as escaped text; `raw` when there are none. */
function inline(tokens: Token[], raw: string): string {
  if (tokens.length === 0) return escapeHtml(raw.trim());
  return tokens.map(inlineToken).join('');
}

function inlineToken(token: Token): string {
  switch (token.type) {
    case 'strong':
      return `<strong>${inline((token as Tokens.Strong).tokens, token.raw)}</strong>`;
    case 'em':
      return `<em>${inline((token as Tokens.Em).tokens, token.raw)}</em>`;
    case 'del':
      return inline((token as Tokens.Del).tokens, token.raw);
    case 'codespan':
      return escapeHtml((token as Tokens.Codespan).text);
    case 'br':
      return '\n';
    case 'link':
    case 'image': {
      const link = token as Tokens.Link | Tokens.Image;
      const label =
        token.type === 'link' ? inline((link as Tokens.Link).tokens, '') : escapeHtml(link.text);
      const href = escapeHtml(link.href);
      return label === '' || label === href ? href : `${label} (${href})`;
    }
    case 'text': {
      const text = token as Tokens.Text;
      return text.tokens?.length ? inline(text.tokens, '') : escapeHtml(text.text);
    }
    case 'escape':
      return escapeHtml((token as Tokens.Escape).text);
    default:
      // Inline HTML and anything else: the source as text.
      return escapeHtml(token.raw);
  }
}
