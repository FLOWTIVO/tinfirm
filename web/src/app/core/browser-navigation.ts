import { DOCUMENT, Injectable, inject } from '@angular/core';

/**
 * Full-page navigation away from the SPA: back to the server's OAuth authorization endpoint after a
 * login, or to an MCP client's redirect URI after a consent (ADR-0045). A service, so tests can
 * replace it.
 */
@Injectable({ providedIn: 'root' })
export class BrowserNavigation {
  private readonly document = inject(DOCUMENT);

  assign(url: string): void {
    this.document.location.assign(url);
  }
}
