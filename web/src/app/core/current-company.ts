import { computed, inject, Injectable, linkedSignal, resource } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { type ActivatedRouteSnapshot, NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { Api } from '../api/generated/api';
import { getCompany } from '../api/generated/functions';
import type { CompanyAccess } from './permissions';
import { Session } from './session';

function findParam(route: ActivatedRouteSnapshot | null, name: string): string | undefined {
  let current: ActivatedRouteSnapshot | null = route;
  let found: string | undefined;
  while (current) {
    found = current.paramMap.get(name) ?? found;
    current = current.firstChild;
  }
  return found;
}

/**
 * The company of the current route (`/c/:companyId/...`): its id, the loaded `Company` and the
 * caller's access. Shared by the shell (switcher, nav) and the company pages.
 */
@Injectable({ providedIn: 'root' })
export class CurrentCompany {
  private readonly router = inject(Router);
  private readonly api = inject(Api);
  private readonly session = inject(Session);

  /** The company id in the URL, or undefined outside company pages. */
  readonly id = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => findParam(this.router.routerState.snapshot.root, 'companyId')),
    ),
    { initialValue: findParam(this.router.routerState.snapshot.root, 'companyId') },
  );

  readonly company = resource({
    params: () => this.id(),
    loader: ({ params: companyId }) => this.api.invoke(getCompany, { companyId }),
  });

  private readonly loaded = computed(() => {
    const company = this.company.hasValue() ? this.company.value() : undefined;
    return company && company.id === this.id() ? company : undefined;
  });

  private readonly membership = computed(() =>
    this.session.memberships().find((m) => m.companyId === this.id()),
  );

  /** The company as far as it is known: loaded, or from the session's memberships meanwhile. */
  readonly name = computed(() => {
    const loaded = this.loaded();
    if (loaded) return loaded.name;
    return this.membership()?.companyName;
  });

  readonly access = computed<CompanyAccess>(() => ({
    role: this.loaded()?.myRole ?? this.membership()?.role ?? null,
    instanceAdmin: this.session.isInstanceAdmin(),
  }));

  /**
   * The company the sidebar links point to: the current one, else the last one visited, else the
   * first membership. Keeps company navigation useful on /me and /admin pages.
   */
  readonly navId = linkedSignal<
    { id: string | undefined; userId: string | undefined },
    string | undefined
  >({
    source: () => ({ id: this.id(), userId: this.session.user()?.id }),
    // Forget the last company when the user changes (logout, another login).
    computation: (source, previous) =>
      source.id ??
      (previous && previous.source.userId === source.userId ? previous.value : undefined),
  });

  readonly navTargetId = computed(() => this.navId() ?? this.session.memberships()[0]?.companyId);

  /** The caller's access in the {@link navTargetId} company, for showing or hiding nav items. */
  readonly navAccess = computed<CompanyAccess>(() => {
    const target = this.navTargetId();
    if (target !== undefined && target === this.id()) return this.access();
    return {
      role: this.session.memberships().find((m) => m.companyId === target)?.role ?? null,
      instanceAdmin: this.session.isInstanceAdmin(),
    };
  });

  /** The name of the {@link navTargetId} company, when known. */
  readonly navName = computed(() => {
    const target = this.navTargetId();
    if (target !== undefined && target === this.id()) return this.name();
    return this.session.memberships().find((m) => m.companyId === target)?.companyName;
  });
}
