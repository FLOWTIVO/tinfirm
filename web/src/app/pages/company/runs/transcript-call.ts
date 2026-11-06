import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { unifiedDiff } from './diff';
import type { ToolItem } from './transcript-model';

/** Diff lines shown before "Show more". */
const DIFF_LINES = 400;

/**
 * An opened tool call (DESIGN.md, The Thread): one code block with the places it touched, its
 * input (a command as the command, other input as JSON), its output and the files it changed,
 * split by hairlines.
 */
@Component({
  selector: 'app-transcript-call',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let tool = item();
    <div
      class="bg-code mt-1 mb-2 ms-[22px] flex flex-col overflow-hidden rounded-[10px] font-mono text-xs leading-[1.65]"
      data-testid="transcript-call"
    >
      @if (locations(); as locs) {
        <p class="text-muted-foreground px-3.5 pt-2.5 break-all" aria-label="Locations">
          {{ locs }}
        </p>
      }
      @if (inputText(); as text) {
        <pre
          class="text-foreground max-h-60 overflow-auto px-3.5 py-2.5 whitespace-pre"
          aria-label="Input"
          >{{ text }}</pre>
      }
      @if (tool.output; as output) {
        <pre
          class="text-muted-foreground max-h-80 overflow-auto border-t px-3.5 py-2.5 whitespace-pre"
          [class.border-t-0]="!inputText()"
          aria-label="Output"
          >{{ output }}</pre>
      }
      @for (diff of diffs(); track $index) {
        <div class="overflow-x-auto border-t first:border-t-0">
          <div class="text-muted-foreground flex flex-wrap gap-x-1.5 px-3.5 py-1.5">
            <span class="min-w-0 break-all">{{ diff.path }}</span>
            @if (diff.created) {
              <span>new file</span>
            }
            <span class="text-success">+{{ diff.added }}</span>
            <span class="text-destructive">−{{ diff.removed }}</span>
          </div>
          <table class="w-full border-collapse">
            <caption class="sr-only">
              {{
                'Changes to ' + diff.path
              }}
            </caption>
            <tbody>
              @for (line of diff.lines; track $index) {
                <tr
                  [class.bg-diff-add]="line.type === 'add'"
                  [class.bg-diff-del]="line.type === 'del'"
                  [attr.data-type]="line.type"
                >
                  <td class="text-muted-foreground w-7 ps-3.5 align-top select-none">{{ line.no }}</td>
                  <td
                    class="w-3.5 align-top select-none"
                    [class.text-success]="line.type === 'add'"
                    [class.text-destructive]="line.type === 'del'"
                  >
                    <span aria-hidden="true">{{ line.sign }}</span
                    ><span class="sr-only">{{ line.spoken }}</span>
                  </td>
                  <td class="pe-3.5" [class.text-muted-foreground]="line.type === 'hunk'">
                    <span class="whitespace-pre">{{ line.text }}</span>
                  </td>
                </tr>
              }
            </tbody>
          </table>
          @if (diff.hidden > 0) {
            <button
              type="button"
              class="text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 w-full py-1 font-sans outline-none focus-visible:ring-3 focus-visible:ring-inset"
              (click)="all.set(true)"
            >
              Show {{ diff.hidden }} more lines
            </button>
          }
        </div>
      }
      @if (!inputText() && !tool.output && tool.diffs.length === 0) {
        <p class="text-muted-foreground px-3.5 py-2.5 font-sans">No details reported.</p>
      }
    </div>
  `,
})
export class TranscriptCall {
  readonly item = input.required<ToolItem>();
  protected readonly all = signal(false);

  /** "src/app.ts:12, src/b.ts" when the call named the places it worked on. */
  protected readonly locations = computed(() => {
    const locs = this.item().locations;
    if (locs.length === 0) return null;
    return locs.map((l) => (l.line ? `${l.path}:${l.line}` : l.path)).join(', ');
  });

  /** A command shows as the command; other input as JSON. */
  protected readonly inputText = computed(() => {
    const input = this.item().input;
    if (!input || Object.keys(input).length === 0) return null;
    if (typeof input['command'] === 'string') return input['command'];
    try {
      return JSON.stringify(input, null, 2);
    } catch {
      return null;
    }
  });

  protected readonly diffs = computed(() =>
    this.item().diffs.map((diff) => {
      const { lines, stats } = unifiedDiff(diff.oldText ?? null, diff.newText);
      const rows = lines.map((line) =>
        line.type === 'hunk'
          ? { type: line.type, no: '', sign: '', spoken: '', text: line.text }
          : {
              type: line.type,
              no: String(line.newNo ?? line.oldNo ?? ''),
              sign: line.type === 'add' ? '+' : line.type === 'del' ? '−' : '',
              spoken: line.type === 'add' ? 'added: ' : line.type === 'del' ? 'removed: ' : '',
              text: line.text,
            },
      );
      const shown = this.all() ? rows : rows.slice(0, DIFF_LINES);
      return {
        path: diff.path,
        created: !diff.oldText,
        added: stats.added,
        removed: stats.removed,
        lines: shown,
        hidden: rows.length - shown.length,
      };
    }),
  );
}
