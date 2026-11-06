import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type DiffRowType = 'same' | 'add' | 'del' | 'skip';

export interface DiffRow {
  readonly type: DiffRowType;
  /** The line; for `skip`, empty. */
  readonly text: string;
  /** For `skip`: how many unchanged lines it stands for. */
  readonly count?: number;
}

/** Lines of context kept around a change; longer unchanged runs fold into one `skip` row. */
export const DIFF_CONTEXT = 2;

/** Above this many cells the middle is shown as removed, then added, rather than compared line by line. */
const MAX_CELLS = 4_000_000;

function lines(text: string): string[] {
  return text === '' ? [] : text.replace(/\r\n?/g, '\n').split('\n');
}

/**
 * A line diff of two texts, with no library: the common start and end, and between them the longest
 * common subsequence of lines (removed lines before added ones at each change). Unchanged runs longer
 * than twice {@link DIFF_CONTEXT} fold into a `skip` row that says how many lines it hides.
 */
export function lineDiff(before: string, after: string): DiffRow[] {
  const a = lines(before);
  const b = lines(after);
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--;
    endB--;
  }
  const rows: DiffRow[] = a.slice(0, start).map((text) => ({ type: 'same', text }));
  const midA = a.slice(start, endA);
  const midB = b.slice(start, endB);
  if (midA.length * midB.length > MAX_CELLS) {
    rows.push(...midA.map((text): DiffRow => ({ type: 'del', text })));
    rows.push(...midB.map((text): DiffRow => ({ type: 'add', text })));
  } else {
    rows.push(...middle(midA, midB));
  }
  rows.push(...a.slice(endA).map((text): DiffRow => ({ type: 'same', text })));
  return fold(rows);
}

/** The longest common subsequence of two line lists, walked from the start. */
function middle(a: readonly string[], b: readonly string[]): DiffRow[] {
  const n = a.length;
  const m = b.length;
  // lcs[i * (m + 1) + j]: the length of the longest common subsequence of a[i..] and b[j..].
  const lcs = new Uint32Array((n + 1) * (m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i * (m + 1) + j] =
        a[i] === b[j]
          ? lcs[(i + 1) * (m + 1) + j + 1] + 1
          : Math.max(lcs[(i + 1) * (m + 1) + j], lcs[i * (m + 1) + j + 1]);
    }
  }
  const rows: DiffRow[] = [];
  let i = 0;
  let j = 0;
  while (i < n || j < m) {
    if (i < n && j < m && a[i] === b[j]) {
      rows.push({ type: 'same', text: a[i] });
      i++;
      j++;
    } else if (j >= m || (i < n && lcs[(i + 1) * (m + 1) + j] >= lcs[i * (m + 1) + j + 1])) {
      rows.push({ type: 'del', text: a[i] });
      i++;
    } else {
      rows.push({ type: 'add', text: b[j] });
      j++;
    }
  }
  return rows;
}

function fold(rows: readonly DiffRow[]): DiffRow[] {
  const folded: DiffRow[] = [];
  let index = 0;
  while (index < rows.length) {
    if (rows[index].type !== 'same') {
      folded.push(rows[index++]);
      continue;
    }
    let end = index;
    while (end < rows.length && rows[end].type === 'same') end++;
    const keepBefore = index === 0 ? 0 : DIFF_CONTEXT;
    const keepAfter = end === rows.length ? 0 : DIFF_CONTEXT;
    const hidden = end - index - keepBefore - keepAfter;
    if (hidden > 1) {
      folded.push(...rows.slice(index, index + keepBefore));
      folded.push({ type: 'skip', text: '', count: hidden });
      folded.push(...rows.slice(end - keepAfter, end));
    } else {
      folded.push(...rows.slice(index, end));
    }
    index = end;
  }
  return folded;
}

/**
 * The instructions of the version a person accepted against those of the current one, line by line
 * (the security review of 2026-10-27, D1): removed lines red with "−", added ones green with "+",
 * unchanged runs folded. Shown on the widening cards when an agent that can act changed what it is
 * told.
 */
@Component({
  selector: 'app-instructions-diff',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div class="bg-muted/30 max-h-80 overflow-auto rounded-md border">
      <table
        class="w-full border-collapse font-mono text-xs leading-[1.65]"
        data-testid="instructions-diff"
      >
        <caption class="sr-only">
          Changes to the instructions
        </caption>
        <tbody>
          @for (row of rows(); track $index) {
            <tr
              [class.bg-diff-add]="row.type === 'add'"
              [class.bg-diff-del]="row.type === 'del'"
              [attr.data-type]="row.type"
            >
              @if (row.type === 'skip') {
                <td colspan="2" class="text-faint px-3.5 italic select-none">
                  {{ row.count }} unchanged lines
                </td>
              } @else {
                <td
                  class="w-3.5 ps-3.5 align-top select-none"
                  [class.text-success]="row.type === 'add'"
                  [class.text-destructive]="row.type === 'del'"
                >
                  <span aria-hidden="true">{{ sign(row.type) }}</span
                  ><span class="sr-only">{{ spoken(row.type) }}</span>
                </td>
                <td class="pe-3.5">
                  <span class="break-words whitespace-pre-wrap">{{ row.text }}</span>
                </td>
              }
            </tr>
          } @empty {
            <tr>
              <td class="text-muted-foreground px-3.5 py-1">No instructions in either version.</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class InstructionsDiff {
  /** The instructions of the version accepted. */
  readonly accepted = input.required<string>();
  /** The instructions of the current version. */
  readonly current = input.required<string>();

  protected readonly rows = computed(() => lineDiff(this.accepted(), this.current()));

  protected sign(type: DiffRowType): string {
    return type === 'add' ? '+' : type === 'del' ? '−' : '';
  }

  protected spoken(type: DiffRowType): string {
    return type === 'add' ? 'added: ' : type === 'del' ? 'removed: ' : '';
  }
}
