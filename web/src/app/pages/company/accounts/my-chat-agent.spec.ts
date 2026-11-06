import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { anAgent, IDS } from '../../../testing/fixtures';
import { fakeNotify, problem, provideTestHttp, settle } from '../../../testing/test-helpers';
import { MyChatAgent } from './my-chat-agent';

const PREF = '/api/companies/c1/me/chat';
const DEV = anAgent({ id: 'a-dev', name: 'Developer', handle: 'dev' });

describe('MyChatAgent', () => {
  let fixture: ComponentFixture<MyChatAgent>;
  let http: HttpTestingController;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  const select = () =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select')!;

  async function create(defaultAgentId: string | null) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({ providers: [...provideTestHttp(), notifier.provider] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MyChatAgent);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.detectChanges();
    await settle();
    http.expectOne(PREF).flush({ defaultAgentId });
    const agents = http.expectOne((r) => r.url === '/api/companies/c1/agents');
    expect(agents.request.params.get('usable')).toBe('true');
    agents.flush({ items: [anAgent(), DEV], nextCursor: null });
    await settle();
    fixture.detectChanges();
  }

  async function choose(value: string) {
    select().value = value;
    select().dispatchEvent(new Event('change'));
    await settle();
  }

  afterEach(() => http.verify());

  it.each`
    case                              | saved       | shown
    ${'none: the bot asks each time'} | ${null}     | ${''}
    ${'an agent the member uses'}     | ${'a-dev'}  | ${'a-dev'}
    ${'an agent they no longer use'}  | ${'a-gone'} | ${''}
  `('shows $case', async ({ saved, shown }) => {
    await create(saved);
    expect(select().value).toBe(shown);
    expect(Array.from(select().options).map((o) => o.textContent?.trim())).toEqual([
      'Ask me each time',
      `${anAgent().name} (@${anAgent().handle})`,
      'Developer (@dev)',
    ]);
  });

  it.each`
    from       | to         | body                           | said
    ${null}    | ${'a-dev'} | ${{ defaultAgentId: 'a-dev' }} | ${'Your direct messages go to Developer'}
    ${'a-dev'} | ${''}      | ${{ defaultAgentId: null }}    | ${'The bot asks which agent each time'}
  `('saves $to as the default', async ({ from, to, body, said }) => {
    await create(from);
    await choose(to);
    const req = http.expectOne({ method: 'PUT', url: PREF });
    expect(req.request.body).toEqual(body);
    req.flush(body);
    await settle();
    expect(notify.success).toHaveBeenCalledWith(said);
  });

  it('puts the choice back when the server refuses it', async () => {
    await create(IDS.agent);
    await choose('a-dev');
    http
      .expectOne({ method: 'PUT', url: PREF })
      .flush(problem(403, 'AGENT_NOT_USABLE'), { status: 403, statusText: 'Forbidden' });
    await settle();
    fixture.detectChanges();
    expect(select().value).toBe(IDS.agent);
    expect(notify.error).toHaveBeenCalled();
  });
});
