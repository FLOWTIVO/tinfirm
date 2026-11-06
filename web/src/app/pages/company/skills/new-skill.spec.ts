import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { MockInstance } from 'vitest';
import { aSkill, IDS, JIRA_SKILL_MD } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { NewSkill } from './new-skill';

const SKILLS = '/api/companies/c1/skills';

describe('NewSkill', () => {
  let fixture: ComponentFixture<NewSkill>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let navigate: MockInstance<Router['navigate']>;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(): Promise<void> {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [NewSkill],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('MEMBER').provider,
        notifier.provider,
      ],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NewSkill);
    el = fixture.nativeElement;
    await settle();
  }

  afterEach(() => http.verify());

  const $ = <T extends Element = HTMLElement>(selector: string) => el.querySelector<T>(selector);
  const submit = async () => {
    submitForm($('[data-testid=new-skill-form]'));
    await settle();
  };
  const words = (node: Element | null | undefined) =>
    node?.textContent?.replace(/\s+/g, ' ').trim() ?? null;

  /** Picks a file in the zip field. */
  async function pick(file: File): Promise<void> {
    $<HTMLInputElement>('#skill-upload-0-source-zip, [id$=-source-zip]')!.click();
    await settle();
    const input = $<HTMLInputElement>('[data-testid=skill-zip]')!;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle();
  }

  it('starts from the front matter to fill in, and creates the skill from the SKILL.md', async () => {
    await open();
    const text = $<HTMLTextAreaElement>('[data-testid=skill-md]')!;
    expect(text.value).toBe('---\nname: \ndescription: \n---\n');
    typeInto(text, JIRA_SKILL_MD);
    typeInto($('[data-testid=skill-note]'), 'First version');
    await settle();
    expect(words($('[data-testid=skill-md-name]'))).toBe('Its name: write-jira-ticket');

    await submit();
    const req = http.expectOne({ method: 'POST', url: SKILLS });
    const body = req.request.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect(body.get('skillMd')).toBe(JIRA_SKILL_MD);
    expect(body.get('note')).toBe('First version');
    expect(body.get('file')).toBeNull();
    req.flush(aSkill({ latestVersion: 1 }), { status: 201, statusText: 'Created' });
    await settle();
    expect(notify.success).toHaveBeenCalledWith(
      'write-jira-ticket created',
      'Version 1, private to you until you share it.',
    );
    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'skills', IDS.skill]);
  });

  it('uploads a zip of the folder instead', async () => {
    await open();
    const zip = new File(['PK'], 'write-jira-ticket.zip', { type: 'application/zip' });
    await pick(zip);
    expect(words($('[data-testid=skill-zip-chosen]'))).toContain('write-jira-ticket.zip');
    await submit();
    const req = http.expectOne({ method: 'POST', url: SKILLS });
    const body = req.request.body as FormData;
    expect(body.get('file')).toBeInstanceOf(File);
    expect(body.get('skillMd')).toBeNull();
    expect(body.get('note')).toBeNull();
    req.flush(aSkill({ latestVersion: 1 }), { status: 201, statusText: 'Created' });
    await settle();
    expect(navigate).toHaveBeenCalled();
  });

  it('asks for the zip before sending anything', async () => {
    await open();
    $<HTMLInputElement>('[id$=-source-zip]')!.click();
    await settle();
    await submit();
    http.expectNone(SKILLS);
    expect(words($('[data-testid=upload-error]'))).toBe('Choose the zip of the skill’s folder.');
  });

  it('says the rule the server refused, and the file it names', async () => {
    await open();
    await pick(new File(['PK'], 'skill.zip'));
    await submit();
    http.expectOne({ method: 'POST', url: SKILLS }).flush(
      {
        ...problem(400, 'SKILL_INVALID'),
        details: { rule: 'PATH', path: 'C:/scripts/run.sh' },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    const error = $('[data-testid=upload-error]')!;
    expect(error.textContent).toContain('A path in the zip is not accepted');
    expect(words(error.querySelector('[data-testid=upload-error-path]'))).toBe(
      'In C:/scripts/run.sh',
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it('says the limit an upload broke', async () => {
    await open();
    await pick(new File(['PK'], 'skill.zip'));
    await submit();
    http
      .expectOne({ method: 'POST', url: SKILLS })
      .flush(
        { ...problem(400, 'SKILL_TOO_LARGE'), details: { limit: 'FILES' } },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();
    expect(words($('[data-testid=upload-error]'))).toBe(
      'The skill is too large. A skill holds at most 200 files.',
    );
  });

  it('names the skill that has the name already', async () => {
    await open();
    typeInto($('[data-testid=skill-md]'), JIRA_SKILL_MD);
    await settle();
    await submit();
    http
      .expectOne({ method: 'POST', url: SKILLS })
      .flush(problem(409, 'DUPLICATE_SKILL_NAME'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect($('[data-testid=upload-error]')?.textContent).toContain(
      'The library already has a skill named write-jira-ticket.',
    );
  });

  it('lists what the credential scan found, never a value', async () => {
    await open();
    typeInto($('[data-testid=skill-md]'), `${JIRA_SKILL_MD}\nToken: ghp_secret\n`);
    await settle();
    await submit();
    http.expectOne({ method: 'POST', url: SKILLS }).flush(
      {
        ...problem(422, 'SKILL_CONTAINS_CREDENTIAL'),
        credentialMatches: [
          { kind: 'FORMAT', name: 'GitHub token' },
          { kind: 'VAULT_SECRET', name: 'JIRA_TOKEN' },
        ],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    await settle();
    const error = $('[data-testid=upload-error]')!;
    expect(error.textContent).toContain('The skill contains a credential, so nothing was stored.');
    expect(
      [...error.querySelectorAll('[data-testid=upload-matches] li')].map((li) => words(li)),
    ).toEqual([
      'A GitHub token credential',
      'The value of the secret JIRA_TOKEN from the company’s vault',
    ]);
    expect(error.textContent).not.toContain('ghp_secret');
  });
});
