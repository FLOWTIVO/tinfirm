/** A web address in parentheses after the words it names: "Link SHOP-2 to it (https://…)". */
const NAMED_URL =
  /^(\s*(?:[-*+]\s+|\d+\.\s+)?)([^[\]()\n]*[^[\]()\s])\s+\((https?:\/\/[^\s()]+)\)([.,;:]?)[ \t]*$/gm;
const BARE_URL_IN_PARENS = /(?<!\])\((https?:\/\/[^\s()]+)\)/g;

/**
 * Tinfirm's own words (a `SYSTEM` message, what a result's review writes), as Markdown: a web
 * address its words name, written as "text (url)", becomes a link on those words; one left in
 * parentheses elsewhere becomes "link". The reader sees the words, not the raw address.
 */
export function namedLinks(body: string): string {
  return body
    .replace(
      NAMED_URL,
      (_, lead: string, text: string, url: string, end: string) => `${lead}[${text}](${url})${end}`,
    )
    .replace(BARE_URL_IN_PARENS, (_, url: string) => `([link](${url}))`);
}
