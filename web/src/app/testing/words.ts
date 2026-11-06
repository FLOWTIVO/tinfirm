/**
 * The words a reader sees in a node, for assertions: its text nodes joined by a space (jsdom has
 * no layout, so `textContent` runs a heading into the paragraph after it), whitespace collapsed,
 * and no space before punctuation.
 */
export function words(node: Node | null | undefined): string | undefined {
  if (!node) return undefined;
  const parts: string[] = [];
  const walker = (node.ownerDocument ?? document).createTreeWalker(node, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) parts.push(walker.currentNode.textContent ?? '');
  return parts
    .join(' ')
    .replace(/\s+/g, ' ')
    .replace(/\s([.,:;])/g, '$1')
    .trim();
}
