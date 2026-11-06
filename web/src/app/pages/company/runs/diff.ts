/** One line of a unified diff. */
export interface DiffLine {
  readonly type: 'context' | 'add' | 'del' | 'hunk';
  readonly text: string;
  /** Line numbers in the old and new text (null for the other side). */
  readonly oldNo: number | null;
  readonly newNo: number | null;
}

export interface DiffStats {
  readonly added: number;
  readonly removed: number;
}

/** Above this many cells the LCS table would be too large; the change is shown as replace-all. */
const MAX_LCS_CELLS = 4_000_000;
const CONTEXT = 3;

function splitLines(text: string | null | undefined): string[] {
  if (!text) return [];
  const lines = text.split('\n');
  if (lines[lines.length - 1] === '') lines.pop();
  return lines;
}

interface Op {
  type: 'context' | 'add' | 'del';
  text: string;
}

/** Line-level edit script with a common prefix/suffix fast path and an LCS core. */
function editScript(a: string[], b: string[]): Op[] {
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--;
    endB--;
  }
  const head: Op[] = a.slice(0, start).map((text) => ({ type: 'context', text }));
  const tail: Op[] = a.slice(endA).map((text) => ({ type: 'context', text }));
  const midA = a.slice(start, endA);
  const midB = b.slice(start, endB);

  const n = midA.length;
  const m = midB.length;
  let middle: Op[];
  if (n === 0 || m === 0 || (n + 1) * (m + 1) > MAX_LCS_CELLS) {
    middle = [
      ...midA.map((text): Op => ({ type: 'del', text })),
      ...midB.map((text): Op => ({ type: 'add', text })),
    ];
  } else {
    // lcs[i][j] = LCS length of midA[i..] and midB[j..], flattened.
    const w = m + 1;
    const lcs = new Uint32Array((n + 1) * w);
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        lcs[i * w + j] =
          midA[i] === midB[j]
            ? lcs[(i + 1) * w + j + 1] + 1
            : Math.max(lcs[(i + 1) * w + j], lcs[i * w + j + 1]);
      }
    }
    middle = [];
    let i = 0;
    let j = 0;
    while (i < n && j < m) {
      if (midA[i] === midB[j]) {
        middle.push({ type: 'context', text: midA[i] });
        i++;
        j++;
      } else if (lcs[(i + 1) * w + j] >= lcs[i * w + j + 1]) {
        middle.push({ type: 'del', text: midA[i++] });
      } else {
        middle.push({ type: 'add', text: midB[j++] });
      }
    }
    while (i < n) middle.push({ type: 'del', text: midA[i++] });
    while (j < m) middle.push({ type: 'add', text: midB[j++] });
  }
  return [...head, ...middle, ...tail];
}

/**
 * A unified diff (3 lines of context, `@@` hunk headers) between two texts. A null `oldText`
 * means a new file.
 */
export function unifiedDiff(
  oldText: string | null | undefined,
  newText: string,
): { lines: DiffLine[]; stats: DiffStats } {
  const ops = editScript(splitLines(oldText), splitLines(newText));
  const stats = {
    added: ops.filter((o) => o.type === 'add').length,
    removed: ops.filter((o) => o.type === 'del').length,
  };

  // Mark which ops are within CONTEXT lines of a change.
  const keep = new Array<boolean>(ops.length).fill(false);
  ops.forEach((op, index) => {
    if (op.type === 'context') return;
    for (
      let k = Math.max(0, index - CONTEXT);
      k <= Math.min(ops.length - 1, index + CONTEXT);
      k++
    ) {
      keep[k] = true;
    }
  });

  const lines: DiffLine[] = [];
  let oldNo = 1;
  let newNo = 1;
  let inHunk = false;
  ops.forEach((op, index) => {
    if (!keep[index]) {
      inHunk = false;
    } else {
      if (!inHunk) {
        lines.push({ type: 'hunk', text: `@@ -${oldNo} +${newNo} @@`, oldNo: null, newNo: null });
        inHunk = true;
      }
      lines.push({
        type: op.type,
        text: op.text,
        oldNo: op.type === 'add' ? null : oldNo,
        newNo: op.type === 'del' ? null : newNo,
      });
    }
    if (op.type !== 'add') oldNo++;
    if (op.type !== 'del') newNo++;
  });
  return { lines, stats };
}
