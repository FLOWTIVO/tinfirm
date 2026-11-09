import type { AdminLicence, Licence, LicenceKeyView } from '../../api/generated/models';
import { AT } from './ids';

/** Tag `Licence` (ADR-0077): the instance's licence and its keys. */

/** Acme's Pro licence: 20 seats, 12 used, ending 31 March 2027, every Pro feature in force. */
export function aLicence(overrides: Partial<Licence> = {}): Licence {
  return {
    edition: 'PRO',
    state: 'ACTIVE',
    customer: 'Acme s.r.o.',
    endsAt: '2027-03-31',
    graceEndsAt: null,
    auditExportUntil: null,
    seats: { licensed: 20, used: 12 },
    companiesLicensed: null,
    features: [
      'OUTLOOK_ADDIN',
      'GMAIL_ADDON',
      'AUDIT_TOOLS',
      'AUDIT_FULL_HISTORY',
      'AUDIT_ANCHOR',
      'RETENTION_SETTINGS',
      'ELASTIC_RUNNERS',
    ],
    ...overrides,
  };
}

/** An instance without a key: Community, nothing counted against a licence. */
export function aCommunityLicence(overrides: Partial<Licence> = {}): Licence {
  return aLicence({
    edition: 'COMMUNITY',
    state: 'NONE',
    customer: null,
    endsAt: null,
    seats: { licensed: null, used: 12 },
    features: [],
    ...overrides,
  });
}

/** Enterprise: every feature, no seat count. */
export function anEnterpriseLicence(overrides: Partial<Licence> = {}): Licence {
  return aLicence({
    edition: 'ENTERPRISE',
    seats: { licensed: null, used: 140 },
    features: [
      'OUTLOOK_ADDIN',
      'GMAIL_ADDON',
      'AUDIT_TOOLS',
      'AUDIT_FULL_HISTORY',
      'AUDIT_ANCHOR',
      'RETENTION_SETTINGS',
      'ELASTIC_RUNNERS',
      'LEGAL_HOLDS',
      'SAML',
      'SCIM',
      'SIEM',
      'MULTI_COMPANY',
      'HA',
      'AIR_GAPPED',
    ],
    ...overrides,
  });
}

/** The key in force of {@link aLicence}, revision 1; the key string itself is never returned. */
export function aLicenceKey(overrides: Partial<LicenceKeyView> = {}): LicenceKeyView {
  return {
    licenceId: 'LIC-2026-0042',
    rev: 1,
    customer: 'Acme s.r.o.',
    edition: 'PRO',
    seats: 20,
    companies: null,
    startsAt: '2026-04-01',
    endsAt: '2027-03-31',
    appliedAt: AT.earlier,
    appliedBy: '0192f1e2-4c00-7a00-8000-00000000a001',
    appliedByName: 'Ivan Instance',
    ...overrides,
  };
}

/** What `GET /api/admin/licence` answers: the key in force, and the next one if any. */
export function anAdminLicence(overrides: Partial<AdminLicence> = {}): AdminLicence {
  return { current: aLicenceKey(), next: null, ...overrides };
}
