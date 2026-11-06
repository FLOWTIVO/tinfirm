import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideCircleCheck, lucideFlaskConical } from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { Api } from '../../../../api/generated/api';
import { tryWebhookMapping, updateSystem } from '../../../../api/generated/functions';
import type {
  ExternalSystem,
  WebhookMapped,
  WebhookMapping as Mapping,
} from '../../../../api/generated/models';
import { problemMessage, toProblem } from '../../../../api/problem';
import { Notify } from '../../../../core/notify';

export type MappingField = keyof Mapping;

/** The mapping's fields in order, what each one reads, and an example pointer. */
export const MAPPING_FIELDS: readonly {
  key: MappingField;
  label: string;
  hint: string;
  example: string;
}[] = [
  {
    key: 'event',
    label: 'Event',
    hint: 'What happened; a trigger picks deliveries by it.',
    example: '/action',
  },
  {
    key: 'subjectKey',
    label: 'Subject key',
    hint: 'What it is about: one conversation per trigger and key, and echoes of its own writes are recognised.',
    example: '/issue/key',
  },
  {
    key: 'subjectTitle',
    label: 'Subject title',
    hint: 'Shown with the key.',
    example: '/issue/title',
  },
  {
    key: 'subjectUrl',
    label: 'Subject URL',
    hint: 'An http(s) link to it.',
    example: '/issue/url',
  },
  {
    key: 'actorId',
    label: 'Sender’s id',
    hint: 'Who did it, as the sender says: never proof.',
    example: '/user/id',
  },
  { key: 'actorName', label: 'Sender’s name', hint: 'As the sender says.', example: '/user/name' },
  {
    key: 'text',
    label: 'Text',
    hint: 'The words the agent reads first.',
    example: '/comment/body',
  },
  {
    key: 'deliveryId',
    label: 'Delivery id',
    hint: 'A secret-header sender without webhook-id: repeats are recognised by it, else by the body.',
    example: '/id',
  },
];

/** A JSON Pointer as the server takes it (RFC 6901): empty, or segments after a /, ~ only as ~0 or ~1. */
export function isPointer(value: string): boolean {
  return value === '' || /^(\/([^~/]|~[01])*)+$/.test(value);
}

/** The mapping a save sends: every field, a blank one as none (a mapping is replaced whole). */
export function mappingBody(model: Record<MappingField, string>): Mapping {
  const body: Mapping = {};
  for (const { key } of MAPPING_FIELDS) {
    const value = model[key].trim();
    body[key] = value ? value : null;
  }
  return body;
}

function modelOf(mapping: Mapping | undefined): Record<MappingField, string> {
  return Object.fromEntries(MAPPING_FIELDS.map(({ key }) => [key, mapping?.[key] ?? ''])) as Record<
    MappingField,
    string
  >;
}

/**
 * A webhook system's mapping (package 5, §5.12): JSON Pointers into a delivery's body, one per field
 * a trigger and its conversation read, and nothing else (no expressions). Tested against a sample
 * delivery: `POST …/webhook/map` answers what the mapping reads and which triggers would fire,
 * storing and firing nothing.
 */
@Component({
  selector: 'app-webhook-mapping',
  imports: [NgIcon, HlmAlertImports, HlmButton, HlmFieldImports, HlmInput, HlmSpinner, HlmTextarea],
  providers: [provideIcons({ lucideCircleAlert, lucideCircleCheck, lucideFlaskConical })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <section
      class="bg-card flex flex-col gap-4 rounded-xl border px-4 py-4 sm:px-5 sm:pt-5"
      aria-labelledby="webhook-mapping-title"
      data-testid="webhook-mapping"
    >
      <div class="flex min-w-0 flex-col gap-0.5">
        <h2 id="webhook-mapping-title" class="text-base font-bold">Mapping</h2>
        <p class="text-muted-foreground text-sm text-pretty">
          Where each field is in a delivery's JSON, as a JSON Pointer such as
          <code class="font-mono">/issue/key</code>. A text, number or true/false is read as text;
          anything else, or nothing there, is empty.
        </p>
      </div>

      <form
        id="mapping-form"
        class="grid gap-x-4 gap-y-3 sm:grid-cols-2"
        novalidate
        (submit)="save($event)"
      >
        @for (field of fields; track field.key) {
          <div hlmField>
            <label hlmFieldLabel [for]="'mapping-' + field.key">{{ field.label }}</label>
            <input
              hlmInput
              class="font-mono"
              autocomplete="off"
              spellcheck="false"
              [id]="'mapping-' + field.key"
              [placeholder]="field.example"
              [value]="model()[field.key]"
              [readOnly]="!canManage()"
              [attr.aria-invalid]="!!fieldError(field.key)"
              [attr.aria-describedby]="'mapping-' + field.key + '-hint'"
              (input)="set(field.key, $event)"
            />
            <p hlmFieldDescription [id]="'mapping-' + field.key + '-hint'">{{ field.hint }}</p>
            @if (fieldError(field.key); as message) {
              <hlm-field-error forceShow>{{ message }}</hlm-field-error>
            }
          </div>
        }
      </form>
      @if (saveError(); as message) {
        <div hlmAlert variant="destructive" data-testid="mapping-error">
          <ng-icon name="lucideCircleAlert" aria-hidden="true" />
          <p hlmAlertDescription>{{ message }}</p>
        </div>
      }
      @if (canManage()) {
        <div class="flex flex-wrap items-center gap-2">
          <button
            hlmBtn
            type="submit"
            form="mapping-form"
            size="sm"
            class="max-sm:h-11"
            data-testid="mapping-save"
            [disabled]="saving() || !dirty()"
          >
            @if (saving()) {
              <hlm-spinner aria-hidden="true" />
            }
            Save the mapping
          </button>
          @if (dirty()) {
            <span class="text-muted-foreground text-xs"
              >Not saved yet: a try uses the saved one.</span
            >
          }
        </div>

        <div class="flex flex-col gap-2 border-t pt-4" data-testid="mapping-try">
          <h3 class="flex items-center gap-2 text-sm font-semibold">
            <ng-icon name="lucideFlaskConical" aria-hidden="true" />
            Try it with a sample delivery
          </h3>
          <div hlmField>
            <label hlmFieldLabel for="mapping-sample">A delivery's body (JSON)</label>
            <textarea
              hlmTextarea
              id="mapping-sample"
              class="min-h-32 font-mono text-xs"
              spellcheck="false"
              placeholder='{ "action": "opened", "issue": { "key": "OPS-7", "title": "Disk full" } }'
              [value]="sample()"
              (input)="setSample($event)"
            ></textarea>
            <p hlmFieldDescription>
              Paste one the sender sent. Nothing is stored and no trigger fires.
            </p>
            @if (sampleError(); as message) {
              <hlm-field-error forceShow>{{ message }}</hlm-field-error>
            }
          </div>
          <div>
            <button
              hlmBtn
              variant="outline"
              size="sm"
              class="max-sm:h-11"
              type="button"
              data-testid="mapping-try-run"
              [disabled]="trying() || !sample().trim()"
              (click)="tryIt()"
            >
              @if (trying()) {
                <hlm-spinner aria-hidden="true" />
              }
              Try it
            </button>
          </div>
          <div role="status" aria-live="polite" class="empty:hidden">
            @if (mapped(); as m) {
              @if (m.failure) {
                <div hlmAlert variant="destructive" data-testid="mapping-failure">
                  <ng-icon name="lucideCircleAlert" aria-hidden="true" />
                  <p hlmAlertDescription>The mapping cannot read this body: {{ m.failure }}</p>
                </div>
              } @else {
                <div class="flex flex-col gap-3 rounded-lg border p-3" data-testid="mapping-result">
                  <dl class="grid gap-x-4 gap-y-1.5 text-xs sm:grid-cols-2">
                    @for (row of readRows(m); track row.label) {
                      <div class="flex min-w-0 flex-col">
                        <dt class="text-muted-foreground font-semibold">{{ row.label }}</dt>
                        <dd class="break-words" [class.text-muted-foreground]="row.value === null">
                          {{ row.value ?? 'empty' }}
                        </dd>
                      </div>
                    }
                  </dl>
                  <div class="flex flex-col gap-1 text-xs" data-testid="mapping-triggers">
                    <span class="text-muted-foreground font-semibold">Webhook triggers</span>
                    @if (!m.triggers.length) {
                      <span>None on this system yet: a delivery like it fires nothing.</span>
                    } @else {
                      <ul class="flex flex-col gap-0.5">
                        @for (t of m.triggers; track t.triggerId) {
                          <li class="flex items-center gap-1.5" [attr.data-matches]="t.matches">
                            <ng-icon
                              [name]="t.matches ? 'lucideCircleCheck' : 'lucideCircleAlert'"
                              [class]="t.matches ? 'text-success' : 'text-muted-foreground'"
                              aria-hidden="true"
                            />
                            {{ t.name }}: {{ t.matches ? 'would fire' : 'not for this delivery' }}
                          </li>
                        }
                      </ul>
                    }
                  </div>
                </div>
              }
            }
          </div>
        </div>
      }
    </section>
  `,
})
export class WebhookMapping {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  readonly system = input.required<ExternalSystem>();
  readonly canManage = input(false);
  readonly saved = output<ExternalSystem>();

  protected readonly fields = MAPPING_FIELDS;
  protected readonly model = signal<Record<MappingField, string>>(modelOf(undefined));
  private readonly savedModel = computed(() => modelOf(this.system().webhook?.mapping));
  protected readonly dirty = computed(() =>
    MAPPING_FIELDS.some(({ key }) => this.model()[key].trim() !== this.savedModel()[key]),
  );
  private readonly serverField = signal<{ key: string; message: string } | null>(null);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  constructor() {
    // The fields follow the saved mapping, read again after a save.
    effect(() => {
      const saved = this.savedModel();
      untracked(() => this.model.set({ ...saved }));
    });
  }

  protected fieldError(key: MappingField): string | null {
    if (!isPointer(this.model()[key].trim())) {
      return 'A JSON Pointer: / and the names in the body, such as /issue/key.';
    }
    const server = this.serverField();
    return server?.key === key ? server.message : null;
  }

  protected set(key: MappingField, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.serverField.set(null);
    this.model.update((m) => ({ ...m, [key]: value }));
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving() || MAPPING_FIELDS.some(({ key }) => this.fieldError(key))) return;
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const system = await this.api.invoke(updateSystem, {
        companyId: this.companyId(),
        systemId: this.system().id,
        body: { webhook: { mapping: mappingBody(this.model()) } },
      });
      this.saved.emit(system);
      this.notify.success('The mapping is saved');
    } catch (error) {
      const problem = toProblem(error);
      const field = MAPPING_FIELDS.find(({ key }) => problem.detail?.includes(`mapping.${key} `));
      if (problem.code === 'MAPPING_INVALID' && field) {
        this.serverField.set({
          key: field.key,
          message: 'Not a JSON Pointer, such as /issue/key.',
        });
      } else {
        this.saveError.set(problemMessage(error));
      }
    } finally {
      this.saving.set(false);
    }
  }

  // --- Try it ---------------------------------------------------------------------------------

  protected readonly sample = signal('');
  protected readonly sampleError = signal<string | null>(null);
  protected readonly trying = signal(false);
  protected readonly mapped = signal<WebhookMapped | null>(null);

  protected setSample(event: Event): void {
    this.sample.set((event.target as HTMLTextAreaElement).value);
    this.sampleError.set(null);
  }

  protected async tryIt(): Promise<void> {
    if (this.trying()) return;
    let body: unknown;
    try {
      body = JSON.parse(this.sample());
    } catch {
      this.sampleError.set('This is not JSON.');
      return;
    }
    if (typeof body !== 'object' || body === null || Array.isArray(body)) {
      this.sampleError.set('A delivery’s body is a JSON object, between { and }.');
      return;
    }
    this.trying.set(true);
    this.mapped.set(null);
    try {
      this.mapped.set(
        await this.api.invoke(tryWebhookMapping, {
          companyId: this.companyId(),
          systemId: this.system().id,
          body: body as Record<string, unknown>,
        }),
      );
    } catch (error) {
      this.sampleError.set(problemMessage(error));
    } finally {
      this.trying.set(false);
    }
  }

  protected readRows(mapped: WebhookMapped): { label: string; value: string | null }[] {
    return MAPPING_FIELDS.map(({ key, label }) => ({ label, value: mapped[key] ?? null }));
  }
}
