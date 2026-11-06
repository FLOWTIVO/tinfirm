import { problemDetails, problemMessage, problemReason, toProblem } from '../../../api/problem';
import {
  type SettingsErrorAt,
  settingsFailure,
  type SettingsErrors,
} from '../../../shared/agent-settings-model';
import type { TriggerSection } from './trigger-draft';

/**
 * A failed preview or save of the trigger editor, placed where it belongs (the package 4
 * contract, §5.2 and §7): the rule (`INVALID_TRIGGER`, `SYSTEM_KIND_INVALID`), a row of the
 * account table (`ACCOUNT_NOT_ENTITLED` with `details.systemId`, `SOURCE_ACCOUNT_REQUIRED`), who
 * pays (`MODEL_ACCESS_NOT_ENTITLED`, `MODEL_ACCESS_UNAVAILABLE`), the runner hosts
 * (`CANNOT_RUN_ANYWHERE`), the acknowledgement (`OPEN_EVENTS_NOT_ACKNOWLEDGED`), the name
 * (`DUPLICATE_TRIGGER_NAME`), the firing limit (`TRIGGER_LIMIT_TOO_HIGH`), the settings (package
 * 3's codes), or the whole form (`TRIGGER_NOT_EDITABLE`, `TOO_MANY_TRIGGERS`, `AGENT_NOT_USABLE`).
 */
export interface TriggerFailure {
  /** Where the message goes; null: the whole form. */
  readonly at: TriggerSection | 'name' | 'acknowledge' | 'panel' | null;
  /** A row of the account table, when the problem names one. */
  readonly systemId: string | null;
  readonly message: string;
  /** The settings form's own field errors. */
  readonly settings: SettingsErrors;
  /** The system or tool of the settings a refusal names, for the form to say where it stands. */
  readonly settingsAt?: SettingsErrorAt | null;
}

const AT: Record<string, TriggerFailure['at']> = {
  INVALID_TRIGGER: 'when',
  SYSTEM_KIND_INVALID: 'when',
  CHANNEL_UNAVAILABLE: 'when',
  CHANNEL_NOT_JOINED: 'when',
  CHANNEL_TAKEN: 'when',
  BOT_NOT_SET: 'when',
  MAPPING_INVALID: 'when',
  SOURCE_ACCOUNT_REQUIRED: 'accounts',
  ACCOUNT_NOT_ENTITLED: 'accounts',
  MODEL_ACCESS_NOT_ENTITLED: 'payer',
  MODEL_ACCESS_UNAVAILABLE: 'payer',
  CANNOT_RUN_ANYWHERE: 'panel',
  OPEN_EVENTS_NOT_ACKNOWLEDGED: 'acknowledge',
  DUPLICATE_TRIGGER_NAME: 'name',
  TRIGGER_LIMIT_TOO_HIGH: 'results',
  AGENT_NOT_USABLE: 'agent',
};

const SETTINGS_CODES = new Set([
  'RUNTIME_NOT_LISTED',
  'MODEL_NOT_ALLOWED',
  'MODEL_REQUIRED',
  'SUBSCRIPTION_NOT_SERVING',
  'SUBSCRIPTION_GONE',
  'SETTINGS_WIDEN',
  'SYSTEM_NOT_ON_AGENT',
  'UNKNOWN_TOOL',
]);

/** Words for a refusal's `details.reason`, after the code's own message. */
const REASONS: Record<string, string> = {
  GATEWAY_NOT_CONFIGURED: 'An admin must set up model access first.',
  PRINCIPAL_INACTIVE: 'The payer is suspended.',
  SUBSCRIPTION_GONE: 'The subscription is no longer on its runner host.',
  PERSONAL_HOST_CREDENTIAL_ONLY:
    'A personal runner host runs only work paid by its owner’s own subscriptions.',
  NO_RUNNER_HOST: 'No runner host is registered.',
  NO_HOST_FOR_CREDENTIALS:
    'A subscription runs only on its own host, which may not hold the credentials a service account borrows.',
  CANNOT_RUN_ANYWHERE: 'No runner host may run it as it is set up.',
};

export function triggerFailure(error: unknown): TriggerFailure {
  const problem = toProblem(error);
  const code = problem.code ?? '';
  const systemId = problemDetails(error)?.['systemId'];
  if (SETTINGS_CODES.has(code)) {
    const failure = settingsFailure(error);
    return {
      at: 'settings',
      systemId: null,
      message: failure.message ?? problemMessage(error),
      settings: failure.errors,
      settingsAt: failure.at,
    };
  }
  let message = problemMessage(error);
  const reason = problemReason(error);
  if (reason && REASONS[reason] && !message.includes(REASONS[reason])) {
    message = `${message} ${REASONS[reason]}`;
  }
  if (
    (code === 'INVALID_TRIGGER' || code === 'CHANNEL_NOT_JOINED' || code === 'MAPPING_INVALID') &&
    problem.detail &&
    !message.includes(problem.detail)
  ) {
    message = `${message} ${problem.detail}`;
  }
  return {
    at: AT[code] ?? null,
    systemId: typeof systemId === 'string' ? systemId : null,
    message,
    settings: {},
  };
}
