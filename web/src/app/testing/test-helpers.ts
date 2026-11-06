import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { type EnvironmentProviders, type Provider, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideApiConfiguration } from '../api/generated/api-configuration';
import type { CurrentUser, Licence, LicenceFeature, Problem, Role } from '../api/generated/models';
import { authInterceptor } from '../core/auth.interceptor';
import { CurrentCompany } from '../core/current-company';
import { LicenceState } from '../core/licence';
import { Notify } from '../core/notify';
import type { CompanyAccess } from '../core/permissions';
import { anEnterpriseLicence } from './fixtures/licence';

/** HttpClient with the app's interceptors against HttpTestingController, as in production. */
export function provideTestHttp(): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(withInterceptors([authInterceptor])),
    provideHttpClientTesting(),
    provideApiConfiguration(''),
  ];
}

/** Lets effects, resource loaders and promise chains run until requests are issued. */
export async function settle(rounds = 3): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

/**
 * Settles, one turn at a time, until `condition` holds: for what the page shows once promise
 * chains that a fixed `settle(n)` cannot count, such as a digest of `crypto.subtle`, have run,
 * however loaded the machine. Throws after `rounds` turns, naming `what` was waited for. Use a plain
 * `settle(n)` to assert that something has not happened yet.
 */
export async function settleUntil(
  condition: () => boolean,
  what: string,
  rounds = 50,
): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    if (condition()) return;
    await settle(1);
  }
  throw new Error(`settleUntil: ${what} did not happen within ${rounds} turns`);
}

/** Settles until an element matches `selector` under `root`, and returns it. */
export async function appears<E extends Element = HTMLElement>(
  root: ParentNode,
  selector: string,
  rounds = 50,
): Promise<E> {
  await settleUntil(
    () => root.querySelector(selector) !== null,
    `an element matching ${selector}`,
    rounds,
  );
  return root.querySelector<E>(selector)!;
}

export function problem(status: number, code?: string, detail?: string): Problem {
  return { type: 'about:blank', title: 'Error', status, code, detail };
}

export function currentUser(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: 'u-me',
    email: 'me@example.com',
    displayName: 'Me Myself',
    instanceAdmin: false,
    authSource: 'LOCAL',
    memberships: [{ companyId: 'c1', companyName: 'Acme', role: 'OWNER' }],
    ...overrides,
  };
}

/** A stand-in for the router-driven CurrentCompany service. */
export function fakeCurrentCompany(role: Role | null, instanceAdmin = false) {
  const access = signal<CompanyAccess>({ role, instanceAdmin });
  const fake = {
    id: signal<string | undefined>('c1'),
    name: signal<string | undefined>('Acme'),
    access,
    company: { reload: vi.fn(), set: vi.fn() },
  };
  return {
    fake,
    provider: { provide: CurrentCompany, useValue: fake as unknown as CurrentCompany },
  };
}

export function fakeNotify() {
  const notify = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
  return { notify, provider: { provide: Notify, useValue: notify } };
}

/** Sets an input's value the way a user would, so signal forms pick it up. */
export function typeInto(element: Element | null, value: string): void {
  if (!(element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement)) {
    throw new Error('typeInto: not an input');
  }
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
  element.dispatchEvent(new Event('blur'));
}

export function submitForm(form: Element | null): void {
  if (!(form instanceof HTMLFormElement)) throw new Error('submitForm: not a form');
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
}

/**
 * A stand-in for the instance's licence (`core/licence.ts`), as `GET /api/licence` would answer:
 * Enterprise by default, so that no paid feature is hidden from a spec that is not about them.
 * `licence` is writable to change it within a spec.
 */
export function fakeLicence(value: Licence | null = anEnterpriseLicence()) {
  const licence = signal<Licence | null>(value);
  const fake = {
    value: licence,
    licence: { error: () => undefined, isLoading: () => false },
    has: (feature: LicenceFeature) => !licence() || licence()!.features.includes(feature),
    reload: vi.fn(),
    set: (next: Licence) => licence.set(next),
  };
  return {
    fake,
    licence,
    provider: { provide: LicenceState, useValue: fake as unknown as LicenceState },
  };
}
