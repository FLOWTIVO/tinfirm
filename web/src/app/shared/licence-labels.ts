import type {
  Licence,
  Edition,
  LicenceFeature,
  LicenceSeats,
  LicenceState,
} from '../api/generated/models';
import { plural } from './labels';

/**
 * The words of the licence (ADR-0077): the editions, the features each holds (decision 4), the
 * seats, the days a licence ends and the notices of its expiry (decision 7). Pure functions, tested
 * as tables; the pages, the banner and the problems' messages say the licence with them.
 */

export const EDITION_LABELS: Record<Edition, string> = {
  COMMUNITY: 'Community',
  PRO: 'Pro',
  ENTERPRISE: 'Enterprise',
};

/** A licence's state as its chip says it. */
export const LICENCE_STATE_LABELS: Record<LicenceState, string> = {
  NONE: 'No licence',
  ACTIVE: 'Active',
  EXPIRING: 'Ends soon',
  GRACE: 'Ended, in grace',
  EXPIRED: 'Expired',
};

/** The chip of a state: calm while active or absent, warning near or past the end. */
export const LICENCE_STATE_TONES: Record<LicenceState, string> = {
  NONE: 'bg-card text-muted-foreground',
  ACTIVE: 'border-success/40 bg-success/10 text-success',
  EXPIRING: 'border-warning/40 bg-warning/10 text-warning',
  GRACE: 'border-warning/40 bg-warning/10 text-warning',
  EXPIRED: 'border-destructive/40 bg-destructive/10 text-destructive',
};

/** A paid feature as a sentence's noun: "Legal holds need Enterprise." */
export const FEATURE_LABELS: Record<LicenceFeature, string> = {
  OUTLOOK_ADDIN: 'The Outlook add-in',
  GMAIL_ADDON: 'The Gmail add-on',
  AUDIT_TOOLS: 'The audit’s filters, export and chain verification',
  AUDIT_FULL_HISTORY: 'The audit before the last 90 days',
  AUDIT_ANCHOR: 'The audit anchored outside the database',
  RETENTION_SETTINGS: 'Retention periods of your own',
  ELASTIC_RUNNERS: 'Elastic runners',
  LEGAL_HOLDS: 'Legal holds',
  SAML: 'SAML sign-in',
  SCIM: 'SCIM',
  SIEM: 'Streaming the audit to a SIEM',
  MULTI_COMPANY: 'Several companies in one instance',
  HA: 'High availability',
  AIR_GAPPED: 'Air-gapped installation',
};

/** The first edition that holds a feature (decision 4's table). */
export const FEATURE_EDITION: Record<LicenceFeature, Exclude<Edition, 'COMMUNITY'>> = {
  OUTLOOK_ADDIN: 'PRO',
  GMAIL_ADDON: 'PRO',
  AUDIT_TOOLS: 'PRO',
  AUDIT_FULL_HISTORY: 'PRO',
  AUDIT_ANCHOR: 'PRO',
  RETENTION_SETTINGS: 'PRO',
  ELASTIC_RUNNERS: 'PRO',
  LEGAL_HOLDS: 'ENTERPRISE',
  SAML: 'ENTERPRISE',
  SCIM: 'ENTERPRISE',
  SIEM: 'ENTERPRISE',
  MULTI_COMPANY: 'ENTERPRISE',
  HA: 'ENTERPRISE',
  AIR_GAPPED: 'ENTERPRISE',
};

/** Whether a string the server sent is one of the features this app knows. */
export function isLicenceFeature(value: unknown): value is LicenceFeature {
  return typeof value === 'string' && Object.hasOwn(FEATURE_LABELS, value);
}

/** Whether a string the server sent is one of the editions. */
export function isLicenceEdition(value: unknown): value is Edition {
  return typeof value === 'string' && Object.hasOwn(EDITION_LABELS, value);
}

/**
 * "Legal holds need an Enterprise licence.": the sentence of a `403 EDITION_REQUIRED`, from its
 * `details.feature` and `details.edition`, either of which may be missing.
 */
export function editionRequiredMessage(feature: unknown, edition: unknown): string {
  const needed = isLicenceEdition(edition)
    ? edition
    : isLicenceFeature(feature)
      ? FEATURE_EDITION[feature]
      : null;
  const what = isLicenceFeature(feature) ? FEATURE_LABELS[feature] : 'This feature';
  const article = needed === 'ENTERPRISE' ? 'an' : 'a';
  const licence = needed ? `${article} ${EDITION_LABELS[needed]} licence` : 'a paid licence';
  return `${what} ${isPlural(feature) ? 'need' : 'needs'} ${licence}. An instance admin enters a licence key under Licence.`;
}

const PLURAL_FEATURES: ReadonlySet<LicenceFeature> = new Set([
  'AUDIT_TOOLS',
  'RETENTION_SETTINGS',
  'ELASTIC_RUNNERS',
  'LEGAL_HOLDS',
]);

function isPlural(feature: unknown): boolean {
  return isLicenceFeature(feature) && PLURAL_FEATURES.has(feature);
}

// --- Days ---------------------------------------------------------------------------------------

/** A licence's days are dates without a time: they are read and shown as the same day everywhere. */
const longDay = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

/** "31 March 2027": a licence's end, its grace's end or the audit export's last day. */
export function licenceDay(date: string | null | undefined): string {
  if (!date) return '';
  const parsed = new Date(`${date.slice(0, 10)}T00:00:00Z`);
  return Number.isNaN(parsed.getTime()) ? date : longDay.format(parsed);
}

/** Whole days from today (the reader's calendar day) until a licence's day; negative once past. */
export function daysUntil(date: string, now: Date = new Date()): number {
  const end = Date.parse(`${date.slice(0, 10)}T00:00:00Z`);
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((end - today) / 86_400_000);
}

// --- Seats --------------------------------------------------------------------------------------

/** "12 of 20 seats used", or "12 seats used" when nothing is counted (Community, some Enterprise). */
export function seatsLine(seats: LicenceSeats): string {
  return seats.licensed === null || seats.licensed === undefined
    ? `${plural(seats.used, 'seat')} used`
    : `${seats.used} of ${plural(seats.licensed, 'seat')} used`;
}

/** Whether every licensed seat is taken: a new Owner, Admin or Member is refused. */
export function seatsFull(seats: LicenceSeats): boolean {
  return seats.licensed !== null && seats.licensed !== undefined && seats.used >= seats.licensed;
}

/** The words of a `409 SEATS_EXHAUSTED`, from its `details.licensed` and `details.used`. */
export function seatsExhaustedMessage(licensed: unknown, used: unknown): string {
  const counts =
    typeof licensed === 'number'
      ? `All ${plural(licensed, 'seat')} of the licence are taken${typeof used === 'number' && used > licensed ? ` (${used} in use)` : ''}. `
      : 'Every seat of the licence is taken. ';
  return `${counts}Owners, Admins and Members each hold a seat; a Viewer holds none. Make someone a Viewer, or enter a key with more seats, first.`;
}

// --- The notices of expiry (decision 7) ----------------------------------------------------------

export interface LicenceNotice {
  readonly state: 'EXPIRING' | 'GRACE' | 'EXPIRED';
  readonly title: string;
  readonly text: string;
  /** What stopped working, for EXPIRED; what stays, beside it. */
  readonly stopped: readonly string[];
  readonly kept: readonly string[];
}

/**
 * The notice of a licence that ends soon, has ended or has run out of grace; null while it is
 * active, absent or of Community. Each says the day, never only a count.
 */
export function licenceNotice(licence: Licence, now: Date = new Date()): LicenceNotice | null {
  const ends = licenceDay(licence.endsAt);
  const grace = licenceDay(licence.graceEndsAt);
  switch (licence.state) {
    case 'EXPIRING': {
      const left = licence.endsAt ? daysUntil(licence.endsAt, now) : null;
      const when =
        left === null ? '' : left <= 0 ? ' today' : left === 1 ? ' tomorrow' : ` in ${left} days`;
      return {
        state: 'EXPIRING',
        title: `The licence ends${when}`,
        text: `The ${EDITION_LABELS[licence.edition]} licence${licence.customer ? ` of ${licence.customer}` : ''} ends on ${ends}. Ask your vendor for the renewal’s key and enter it under Licence before then; nothing changes until 30 days after the end.`,
        stopped: [],
        kept: [],
      };
    }
    case 'GRACE':
      return {
        state: 'GRACE',
        title: `The licence has ended: everything keeps working until ${grace}`,
        text: `The licence ended on ${ends}. On ${grace} the instance runs as Community and its paid features stop. A new key restores everything at once: nothing is removed.`,
        stopped: [],
        kept: [],
      };
    case 'EXPIRED':
      return {
        state: 'EXPIRED',
        title: 'The licence has run out: the instance runs as Community',
        text: `The licence ended on ${ends}${grace ? ` and its grace on ${grace}` : ''}. Work, data and roles stay; a new key restores the paid features at once.`,
        stopped: [
          'The Outlook add-in',
          ...(licence.auditExportUntil && daysUntil(licence.auditExportUntil, now) < 0
            ? [`The audit’s export, since ${licenceDay(licence.auditExportUntil)}`]
            : []),
          'The audit’s filters, chain verification and its history before the last 90 days',
          'Setting legal holds, and changing the retention periods',
          'SCIM',
        ],
        kept: [
          ...(!licence.auditExportUntil
            ? ['The audit’s export, for 90 days']
            : daysUntil(licence.auditExportUntil, now) >= 0
              ? [`The audit’s export, until ${licenceDay(licence.auditExportUntil)}`]
              : []),
          'The retention periods in force, and the open legal holds',
          'SAML sign-in, and every declared company',
          'Everyone’s role; no seat is checked',
        ],
      };
    default:
      return null;
  }
}

// --- Adding and removing keys --------------------------------------------------------------------

/** "TINFIRM1.": how every key starts. */
export const KEY_PREFIX = 'TINFIRM1.';

/** A pasted key as it is sent: without the spaces and line breaks a mail or a chat may add. */
export function cleanKey(text: string): string {
  return text.replace(/\s+/g, '');
}

/** The words of a refused key, by its problem's code and `details` (the contract's problems). */
export function licenceKeyMessage(
  code: string | undefined,
  details: Record<string, unknown> | undefined,
): string | null {
  switch (code) {
    case 'LICENCE_KEY_INVALID':
      return 'This is not a valid licence key: it is incomplete, changed, or not signed by FLOWTIVO. Copy the whole key, from TINFIRM1. to its end, and paste it again. If it still fails, it may be signed with a newer FLOWTIVO key than this version of Tinfirm knows: upgrade Tinfirm, or ask your vendor.';
    case 'LICENCE_KEY_EXPIRED':
      return 'This key has already ended. Ask your vendor for a current one.';
    case 'LICENCE_KEY_SUPERSEDED':
      return 'This key is the one in force, or an older revision of its licence. Paste the newest key your vendor sent.';
    case 'LICENCE_SEATS_TOO_FEW': {
      const required = details?.['required'];
      const licensed = details?.['licensed'];
      const counts =
        typeof required === 'number' && typeof licensed === 'number'
          ? ` ${plural(required, 'person', 'people')} hold a seat, and the key licenses ${plural(licensed, 'seat')}.`
          : '';
      return `This key licenses fewer seats than are in use.${counts} Make some Members Viewers first; the key in force works until its end meanwhile.`;
    }
    default:
      return null;
  }
}
