import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  type ActivatedRouteSnapshot,
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { HlmSeparator } from '@spartan-ng/helm/separator';
import { HlmSidebarImports } from '@spartan-ng/helm/sidebar';
import { filter, map } from 'rxjs';
import { CurrentCompany } from '../core/current-company';
import { InboxCounts } from '../core/inbox-counts';
import { pollWhileVisible } from '../core/polling';
import { Session } from '../core/session';
import { Logo } from '../shared/logo';
import { CompanySwitcher } from './company-switcher';
import { ADMIN_NAV, companyNav, NAV_ICONS } from './navigation';
import { UserMenu } from './user-menu';

/** How often the inbox badge is read again while the page is visible. */
const INBOX_POLL_MS = 30_000;

function deepestTitle(route: ActivatedRouteSnapshot): string | undefined {
  let current: ActivatedRouteSnapshot | null = route;
  let title: string | undefined;
  while (current) {
    title = current.title ?? title;
    current = current.firstChild;
  }
  return title;
}

/** Pages with wide content (threads, transcripts) set `data: { wide: true }` to use the full width. */
function isWide(route: ActivatedRouteSnapshot): boolean {
  let current: ActivatedRouteSnapshot | null = route;
  while (current) {
    if (current.data['wide'] === true) return true;
    current = current.firstChild;
  }
  return false;
}

/** App frame for logged-in pages: sidebar (company switcher, nav, user menu) and content. */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    NgIcon,
    HlmSidebarImports,
    HlmSeparator,
    Logo,
    CompanySwitcher,
    UserMenu,
  ],
  providers: [provideIcons(NAV_ICONS)],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.html',
})
export class Shell {
  protected readonly session = inject(Session);
  protected readonly current = inject(CurrentCompany);
  private readonly router = inject(Router);

  protected readonly inbox = inject(InboxCounts);

  /** The company's navigation by group (plan §3): five items for a member, and the setup for admins. */
  protected readonly companyNav = computed(() => companyNav(this.current.navAccess()));

  protected readonly adminNav = ADMIN_NAV;

  constructor() {
    pollWhileVisible(() => {
      if (this.current.navTargetId()) this.inbox.refresh();
    }, INBOX_POLL_MS);
  }

  /** Title of the current page, for the top bar. */
  protected readonly pageTitle = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => deepestTitle(this.router.routerState.snapshot.root)),
    ),
    { initialValue: deepestTitle(this.router.routerState.snapshot.root) },
  );

  protected readonly wide = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => isWide(this.router.routerState.snapshot.root)),
    ),
    { initialValue: isWide(this.router.routerState.snapshot.root) },
  );

  protected readonly inCompany = computed(() => this.current.id() !== undefined);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /** First crumb of the top bar: the company, or the instance-admin area. */
  protected readonly crumb = computed(() => {
    if (this.inCompany()) return this.current.name();
    return this.url().startsWith('/admin') ? 'Instance admin' : undefined;
  });
}
