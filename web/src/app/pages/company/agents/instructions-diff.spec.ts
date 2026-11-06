import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { InstructionsDiff, lineDiff } from './instructions-diff';

describe('lineDiff', () => {
  it('shows a changed line as removed, then added, between the lines both keep', () => {
    expect(
      lineDiff(
        'Read the issue.\nAnswer in French.\nBe brief.',
        'Read the issue.\nAnswer in German.\nBe brief.',
      ),
    ).toEqual([
      { type: 'same', text: 'Read the issue.' },
      { type: 'del', text: 'Answer in French.' },
      { type: 'add', text: 'Answer in German.' },
      { type: 'same', text: 'Be brief.' },
    ]);
  });

  it('finds lines added and removed in the middle', () => {
    const rows = lineDiff('a\nb\nc\nd', 'a\nc\nx\nd\ne');
    expect(rows.map((r) => `${r.type}:${r.text}`)).toEqual([
      'same:a',
      'del:b',
      'same:c',
      'add:x',
      'same:d',
      'add:e',
    ]);
  });

  it('handles empty texts and Windows line ends', () => {
    expect(lineDiff('', 'One.\r\nTwo.')).toEqual([
      { type: 'add', text: 'One.' },
      { type: 'add', text: 'Two.' },
    ]);
    expect(lineDiff('Gone.', '')).toEqual([{ type: 'del', text: 'Gone.' }]);
    expect(lineDiff('', '')).toEqual([]);
  });

  it('folds long unchanged runs, keeping two lines of context around a change', () => {
    const before = Array.from({ length: 20 }, (_, i) => `line ${i + 1}`);
    const after = [...before];
    after[10] = 'line 11, changed';
    const rows = lineDiff(before.join('\n'), after.join('\n'));
    expect(
      rows.map((r) => (r.type === 'skip' ? `skip:${r.count}` : `${r.type}:${r.text}`)),
    ).toEqual([
      'skip:8',
      'same:line 9',
      'same:line 10',
      'del:line 11',
      'add:line 11, changed',
      'same:line 12',
      'same:line 13',
      'skip:7',
    ]);
  });

  it('keeps a short unchanged run between two changes whole', () => {
    const rows = lineDiff('a\nb\nc\nd\ne\nf\ng', 'A\nb\nc\nd\ne\nf\nG');
    expect(rows.filter((r) => r.type === 'skip')).toEqual([]);
    expect(rows.filter((r) => r.type === 'same').map((r) => r.text)).toEqual([
      'b',
      'c',
      'd',
      'e',
      'f',
    ]);
  });
});

describe('InstructionsDiff', () => {
  let fixture: ComponentFixture<InstructionsDiff>;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [InstructionsDiff] });
    fixture = TestBed.createComponent(InstructionsDiff);
    fixture.componentRef.setInput('accepted', 'Read the issue.\nAnswer in French.');
    fixture.componentRef.setInput('current', 'Read the issue.\nAnswer in German.\nMerge it.');
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  it('marks removed and added lines, for the eye and for a screen reader', () => {
    const rows = Array.from(el.querySelectorAll('[data-testid=instructions-diff] tr'));
    expect(rows.map((r) => r.getAttribute('data-type'))).toEqual(['same', 'del', 'add', 'add']);
    expect(rows[1].classList).toContain('bg-diff-del');
    expect(rows[2].classList).toContain('bg-diff-add');
    expect(rows[1].textContent).toContain('removed: ');
    expect(rows[1].textContent).toContain('Answer in French.');
    expect(rows[3].textContent).toContain('added: ');
    expect(rows[3].textContent).toContain('Merge it.');
  });

  it('says how many unchanged lines a fold hides', async () => {
    const many = Array.from({ length: 12 }, (_, i) => `line ${i + 1}`).join('\n');
    fixture.componentRef.setInput('accepted', many);
    fixture.componentRef.setInput('current', `${many}\nline 13`);
    await fixture.whenStable();
    const skip = el.querySelector('tr[data-type=skip]');
    expect(skip?.textContent?.trim()).toBe('10 unchanged lines');
  });
});
