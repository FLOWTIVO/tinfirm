import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  type ElementRef,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideBold,
  lucideCode,
  lucideHeading2,
  lucideHeading3,
  lucideItalic,
  lucideLink,
  lucideList,
  lucideListOrdered,
  lucideListTodo,
  lucideQuote,
  lucideSquareCode,
  lucideStrikethrough,
} from '@ng-icons/lucide';
import { Editor } from '@tiptap/core';
import { TaskItem, TaskList } from '@tiptap/extension-list';
import { TableKit } from '@tiptap/extension-table';
import { Placeholder } from '@tiptap/extensions';
import { Markdown as MarkdownExtension } from '@tiptap/markdown';
import StarterKit from '@tiptap/starter-kit';
import type { FormValueControl } from '@angular/forms/signals';

export type MarkdownEditorMode = 'rich' | 'markdown';

export const EDITOR_MODE_STORAGE_KEY = 'tinfirm.editorMode';

/** The mode the user last picked, rich text by default. */
export function readStoredEditorMode(): MarkdownEditorMode {
  try {
    return globalThis.localStorage?.getItem(EDITOR_MODE_STORAGE_KEY) === 'markdown'
      ? 'markdown'
      : 'rich';
  } catch {
    return 'rich';
  }
}

function writeStoredEditorMode(mode: MarkdownEditorMode): void {
  try {
    globalThis.localStorage?.setItem(EDITOR_MODE_STORAGE_KEY, mode);
  } catch {
    // Storage can be disabled; the choice then lasts for this editor only.
  }
}

/** Only web and mail links, as `renderMarkdown()` shows them. */
function normalizeHref(value: string): string | null {
  const href = value.trim();
  if (!href) return null;
  if (/^(https?:|mailto:)/i.test(href)) return href;
  if (/^[a-z][a-z0-9+.-]*:/i.test(href)) return null;
  return `https://${href}`;
}

type Mark = 'bold' | 'italic' | 'strike' | 'code' | 'link';
type Block =
  'heading2' | 'heading3' | 'bulletList' | 'orderedList' | 'taskList' | 'blockquote' | 'codeBlock';

interface ToolbarItem {
  readonly id: Mark | Block;
  readonly icon: string;
  readonly label: string;
  readonly keys?: string;
}

const TOOLBAR: readonly (readonly ToolbarItem[])[] = [
  [
    { id: 'bold', icon: 'lucideBold', label: 'Bold', keys: 'Mod+B' },
    { id: 'italic', icon: 'lucideItalic', label: 'Italic', keys: 'Mod+I' },
    { id: 'strike', icon: 'lucideStrikethrough', label: 'Strikethrough' },
    { id: 'code', icon: 'lucideCode', label: 'Inline code', keys: 'Mod+E' },
    { id: 'link', icon: 'lucideLink', label: 'Link' },
  ],
  [
    { id: 'heading2', icon: 'lucideHeading2', label: 'Heading' },
    { id: 'heading3', icon: 'lucideHeading3', label: 'Subheading' },
  ],
  [
    { id: 'bulletList', icon: 'lucideList', label: 'Bulleted list' },
    { id: 'orderedList', icon: 'lucideListOrdered', label: 'Numbered list' },
    { id: 'taskList', icon: 'lucideListTodo', label: 'Checklist' },
    { id: 'blockquote', icon: 'lucideQuote', label: 'Quote' },
    { id: 'codeBlock', icon: 'lucideSquareCode', label: 'Code block' },
  ],
];

/**
 * A Markdown field with two modes: rich text (WYSIWYG, Tiptap) and the Markdown source. The value
 * is always Markdown; the user's choice of mode is remembered. Works with `[formField]`.
 *
 * The rich editor writes the value only when the user edits in it, so opening a text in rich mode
 * never rewrites Markdown the user did not touch.
 */
@Component({
  selector: 'app-markdown-editor',
  imports: [NgIcon],
  providers: [
    provideIcons({
      lucideBold,
      lucideCode,
      lucideHeading2,
      lucideHeading3,
      lucideItalic,
      lucideLink,
      lucideList,
      lucideListOrdered,
      lucideListTodo,
      lucideQuote,
      lucideSquareCode,
      lucideStrikethrough,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block min-w-0', '[attr.data-mode]': 'mode()' },
  template: `
    <div
      class="border-input bg-card focus-within:border-ring focus-within:ring-ring/50 flex min-w-0 flex-col rounded-lg border transition-colors focus-within:ring-3"
      [class.opacity-50]="disabled()"
      [class.border-destructive]="invalid() && touched()"
    >
      <div class="flex flex-wrap items-center gap-1 border-b px-1.5 py-1">
        @if (mode() === 'rich') {
          <div
            class="flex min-w-0 flex-1 flex-wrap items-center gap-0.5"
            role="toolbar"
            [attr.aria-label]="'Formatting'"
            aria-orientation="horizontal"
          >
            @for (group of toolbar; track $index) {
              @if ($index > 0) {
                <span class="bg-border mx-1 h-5 w-px" aria-hidden="true"></span>
              }
              @for (item of group; track item.id) {
                <button
                  type="button"
                  class="text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-ring/50 aria-pressed:bg-muted aria-pressed:text-foreground inline-flex size-7 items-center justify-center rounded-md outline-none focus-visible:ring-3 disabled:pointer-events-none"
                  [attr.aria-label]="item.label"
                  [attr.aria-pressed]="active()[item.id]"
                  [title]="item.keys ? item.label + ' (' + keyLabel(item.keys) + ')' : item.label"
                  [disabled]="disabled()"
                  [attr.data-action]="item.id"
                  (mousedown)="$event.preventDefault()"
                  (click)="apply(item.id)"
                >
                  <ng-icon [name]="item.icon" aria-hidden="true" />
                </button>
              }
            }
          </div>
        } @else {
          <p class="text-muted-foreground flex-1 px-1.5 text-xs">Markdown source</p>
        }
        <div
          class="bg-muted ms-auto inline-flex shrink-0 rounded-md p-0.5 text-xs font-medium"
          role="group"
          aria-label="Editor mode"
        >
          <button
            type="button"
            class="aria-pressed:bg-card aria-pressed:text-foreground text-muted-foreground focus-visible:ring-ring/50 rounded px-2 py-0.5 outline-none aria-pressed:shadow-sm focus-visible:ring-3"
            data-testid="editor-mode-rich"
            [attr.aria-pressed]="mode() === 'rich'"
            (click)="setMode('rich')"
          >
            Rich text
          </button>
          <button
            type="button"
            class="aria-pressed:bg-card aria-pressed:text-foreground text-muted-foreground focus-visible:ring-ring/50 rounded px-2 py-0.5 outline-none aria-pressed:shadow-sm focus-visible:ring-3"
            data-testid="editor-mode-markdown"
            [attr.aria-pressed]="mode() === 'markdown'"
            (click)="setMode('markdown')"
          >
            Markdown
          </button>
        </div>
      </div>

      @if (linkOpen()) {
        <div class="flex flex-wrap items-center gap-2 border-b px-2.5 py-1.5">
          <label class="text-muted-foreground text-xs" [for]="inputId() + '-link'">Link</label>
          <input
            #linkInput
            type="url"
            class="bg-background min-w-40 flex-1 rounded-md border px-2 py-1 text-sm outline-none"
            placeholder="https://"
            [id]="inputId() + '-link'"
            [value]="linkDraft()"
            (input)="linkDraft.set(linkInput.value)"
            (keydown.enter)="$event.preventDefault(); applyLink()"
            (keydown.escape)="$event.preventDefault(); closeLink()"
          />
          <button
            type="button"
            class="bg-primary text-primary-foreground rounded-md px-2.5 py-1 text-xs font-semibold"
            (click)="applyLink()"
          >
            Apply
          </button>
          @if (active().link) {
            <button
              type="button"
              class="text-muted-foreground hover:text-foreground rounded-md px-2 py-1 text-xs font-medium"
              (click)="removeLink()"
            >
              Remove
            </button>
          }
        </div>
      }

      <!-- The rich editor stays mounted, so its undo history survives a peek at the source. -->
      <div
        #richHost
        class="markdown markdown-editor flex flex-col overflow-y-auto px-3 py-2"
        [class.hidden]="mode() !== 'rich'"
        [style.min-height.rem]="minRows() * 1.625 + 1"
        [style.max-height.rem]="maxRows() * 1.625 + 1"
        (focusout)="touch.emit()"
      ></div>

      @if (mode() === 'markdown') {
        <textarea
          #source
          class="placeholder:text-muted-foreground w-full resize-y bg-transparent px-3 py-2 font-mono text-[0.8rem] leading-relaxed outline-none"
          [id]="inputId()"
          [rows]="minRows()"
          [attr.aria-labelledby]="labelId() || null"
          [attr.aria-invalid]="invalid() && touched()"
          [placeholder]="placeholder()"
          [value]="value()"
          [disabled]="disabled()"
          (input)="value.set(source.value)"
          (blur)="touch.emit()"
        ></textarea>
      }
    </div>
  `,
})
export class MarkdownEditor implements FormValueControl<string> {
  /** Markdown. */
  readonly value = model('');
  readonly disabled = input(false);
  readonly invalid = input(false);
  readonly touched = input(false);
  readonly touch = output<void>();

  /** Id of the editable element, in either mode. */
  readonly inputId = input.required<string>();
  /** Id of the visible label; a rich editor can't be labelled with `for`. */
  readonly labelId = input('');
  readonly placeholder = input('');
  readonly minRows = input(6);
  readonly maxRows = input(24);

  protected readonly toolbar = TOOLBAR;
  protected readonly mode = signal<MarkdownEditorMode>(readStoredEditorMode());

  private readonly richHost = viewChild.required<ElementRef<HTMLElement>>('richHost');
  private readonly source = viewChild<ElementRef<HTMLTextAreaElement>>('source');
  private readonly linkInput = viewChild<ElementRef<HTMLInputElement>>('linkInput');

  private editor: Editor | null = null;
  /** The Markdown the rich editor shows; it re-reads the value when that differs. */
  private shown: string | null = null;
  /** Bumped on every editor transaction, for the toolbar's pressed states. */
  private readonly revision = signal(0);

  protected readonly active = computed<Record<Mark | Block, boolean>>(() => {
    this.revision();
    const e = this.editor;
    return {
      bold: !!e?.isActive('bold'),
      italic: !!e?.isActive('italic'),
      strike: !!e?.isActive('strike'),
      code: !!e?.isActive('code'),
      link: !!e?.isActive('link'),
      heading2: !!e?.isActive('heading', { level: 2 }),
      heading3: !!e?.isActive('heading', { level: 3 }),
      bulletList: !!e?.isActive('bulletList'),
      orderedList: !!e?.isActive('orderedList'),
      taskList: !!e?.isActive('taskList'),
      blockquote: !!e?.isActive('blockquote'),
      codeBlock: !!e?.isActive('codeBlock'),
    };
  });

  protected readonly linkOpen = signal(false);
  protected readonly linkDraft = signal('');

  private readonly mac = /Mac|iPhone|iPad/.test(globalThis.navigator?.platform ?? '');

  constructor() {
    afterNextRender(() => {
      this.editor = new Editor({
        element: this.richHost().nativeElement,
        extensions: [
          StarterKit.configure({
            underline: false,
            link: { openOnClick: false, autolink: true, defaultProtocol: 'https' },
          }),
          TaskList,
          TaskItem.configure({ nested: true }),
          TableKit.configure({ table: { resizable: false } }),
          Placeholder.configure({ placeholder: () => untracked(this.placeholder) }),
          MarkdownExtension,
        ],
        editable: !untracked(this.disabled),
        editorProps: { attributes: this.editorAttributes() },
        onTransaction: () => this.revision.update((n) => n + 1),
        onUpdate: ({ editor }) => {
          const markdown = editor.isEmpty ? '' : editor.getMarkdown().trimEnd();
          this.shown = markdown;
          this.value.set(markdown);
        },
      });
      if (untracked(this.mode) === 'rich') this.syncEditor();
    });

    // A value set from outside (a reset, a loaded task) replaces what the rich editor shows.
    effect(() => {
      this.value();
      if (this.mode() === 'rich') untracked(() => this.syncEditor());
    });
    effect(() => {
      const editable = !this.disabled();
      untracked(() => this.editor?.setEditable(editable, false));
    });
    effect(() => {
      const attributes = this.editorAttributes();
      untracked(() => this.editor?.setOptions({ editorProps: { attributes } }));
    });

    inject(DestroyRef).onDestroy(() => {
      this.editor?.destroy();
      this.editor = null;
    });
  }

  private editorAttributes(): Record<string, string> {
    const attributes: Record<string, string> = {
      id: this.mode() === 'rich' ? this.inputId() : `${this.inputId()}-rich`,
      role: 'textbox',
      'aria-multiline': 'true',
      class: 'flex-1 outline-none',
    };
    if (this.labelId()) attributes['aria-labelledby'] = this.labelId();
    if (this.invalid() && this.touched()) attributes['aria-invalid'] = 'true';
    return attributes;
  }

  private syncEditor(): void {
    const editor = this.editor;
    const value = this.value();
    if (!editor || value === this.shown) return;
    editor.commands.setContent(value, { contentType: 'markdown', emitUpdate: false });
    this.shown = value;
  }

  protected keyLabel(keys: string): string {
    return keys.replace('Mod', this.mac ? '⌘' : 'Ctrl');
  }

  protected setMode(mode: MarkdownEditorMode): void {
    if (mode === this.mode()) return;
    this.linkOpen.set(false);
    this.mode.set(mode);
    writeStoredEditorMode(mode);
    // Keep the focus in the field the user switched to.
    setTimeout(() => this.focus());
  }

  /** For signal forms: focuses the editable element of the current mode. */
  focus(options?: FocusOptions): void {
    if (this.mode() === 'markdown') this.source()?.nativeElement.focus(options);
    else this.editor?.commands.focus();
  }

  protected apply(action: Mark | Block): void {
    const editor = this.editor;
    if (!editor) return;
    const chain = editor.chain().focus();
    switch (action) {
      case 'bold':
        chain.toggleBold().run();
        break;
      case 'italic':
        chain.toggleItalic().run();
        break;
      case 'strike':
        chain.toggleStrike().run();
        break;
      case 'code':
        chain.toggleCode().run();
        break;
      case 'link':
        this.linkDraft.set((editor.getAttributes('link')['href'] as string | undefined) ?? '');
        this.linkOpen.set(true);
        setTimeout(() => this.linkInput()?.nativeElement.focus());
        break;
      case 'heading2':
        chain.toggleHeading({ level: 2 }).run();
        break;
      case 'heading3':
        chain.toggleHeading({ level: 3 }).run();
        break;
      case 'bulletList':
        chain.toggleBulletList().run();
        break;
      case 'orderedList':
        chain.toggleOrderedList().run();
        break;
      case 'taskList':
        chain.toggleTaskList().run();
        break;
      case 'blockquote':
        chain.toggleBlockquote().run();
        break;
      case 'codeBlock':
        chain.toggleCodeBlock().run();
        break;
    }
  }

  protected applyLink(): void {
    const editor = this.editor;
    const href = normalizeHref(this.linkDraft());
    if (!editor) return;
    if (!href) {
      this.removeLink();
      return;
    }
    const chain = editor.chain().focus().extendMarkRange('link');
    if (editor.state.selection.empty && !editor.isActive('link')) {
      chain.insertContent({ type: 'text', text: href, marks: [{ type: 'link', attrs: { href } }] });
    } else {
      chain.setLink({ href });
    }
    chain.run();
    this.linkOpen.set(false);
  }

  protected removeLink(): void {
    this.editor?.chain().focus().extendMarkRange('link').unsetLink().run();
    this.linkOpen.set(false);
  }

  protected closeLink(): void {
    this.linkOpen.set(false);
    this.editor?.commands.focus();
  }
}
