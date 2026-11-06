import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { form, FormField } from '@angular/forms/signals';
import { settle, typeInto } from '../testing/test-helpers';
import { EDITOR_MODE_STORAGE_KEY, MarkdownEditor } from './markdown-editor';

@Component({
  imports: [FormField, MarkdownEditor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-markdown-editor inputId="brief" [formField]="briefForm.body" />`,
})
class Host {
  readonly model = signal({ body: '' });
  readonly briefForm = form(this.model);
}

describe('MarkdownEditor', () => {
  let fixture: ComponentFixture<Host>;
  let el: HTMLElement;

  async function create(body: string, mode?: 'rich' | 'markdown') {
    if (mode) localStorage.setItem(EDITOR_MODE_STORAGE_KEY, mode);
    else localStorage.removeItem(EDITOR_MODE_STORAGE_KEY);
    TestBed.configureTestingModule({ imports: [Host] });
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.model.set({ body });
    el = fixture.nativeElement;
    await settle();
    await fixture.whenStable();
  }

  const rich = () => el.querySelector<HTMLElement>('.ProseMirror');
  const source = () => el.querySelector<HTMLTextAreaElement>('textarea');
  const modeButton = (mode: 'rich' | 'markdown') =>
    el.querySelector<HTMLButtonElement>(`[data-testid=editor-mode-${mode}]`)!;

  afterEach(() => localStorage.removeItem(EDITOR_MODE_STORAGE_KEY));

  it('opens in rich text mode by default and shows the Markdown formatted', async () => {
    await create('## Goal\n\nReturn **200**.');
    expect(modeButton('rich').getAttribute('aria-pressed')).toBe('true');
    expect(source()).toBeNull();
    expect(rich()?.querySelector('h2')?.textContent).toBe('Goal');
    expect(rich()?.querySelector('strong')?.textContent).toBe('200');
    expect(rich()?.id).toBe('brief');
  });

  it('switches to the Markdown source and back, and remembers the choice', async () => {
    await create('Some **bold** text');
    modeButton('markdown').click();
    await settle();
    expect(source()?.value).toBe('Some **bold** text');
    expect(source()?.id).toBe('brief');
    expect(localStorage.getItem(EDITOR_MODE_STORAGE_KEY)).toBe('markdown');

    typeInto(source(), '- one\n- two');
    await settle();
    expect(fixture.componentInstance.model().body).toBe('- one\n- two');

    modeButton('rich').click();
    await settle();
    expect(rich()?.querySelectorAll('li')).toHaveLength(2);
    expect(localStorage.getItem(EDITOR_MODE_STORAGE_KEY)).toBe('rich');
  });

  it('opens in the mode the user picked last', async () => {
    await create('text', 'markdown');
    expect(source()?.value).toBe('text');
  });

  it('does not rewrite Markdown the user did not edit in rich text mode', async () => {
    const original = '* star bullet\n\n__underscored__';
    await create(original);
    modeButton('markdown').click();
    await settle();
    expect(fixture.componentInstance.model().body).toBe(original);
    expect(source()?.value).toBe(original);
  });

  it('writes Markdown when formatting from the toolbar', async () => {
    await create('hello');
    el.querySelector<HTMLButtonElement>('[data-action=heading2]')!.click();
    await settle();
    expect(fixture.componentInstance.model().body).toBe('## hello');
    expect(rich()?.querySelector('h2')?.textContent).toBe('hello');
    expect(el.querySelector('[data-action=heading2]')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('follows a value set from outside, such as a reset', async () => {
    await create('draft');
    fixture.componentInstance.model.set({ body: '' });
    await settle();
    await fixture.whenStable();
    expect(rich()?.textContent).toBe('');
  });
});
