import {
  provideHttpClient,
  withFetch,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import {
  type ApplicationConfig,
  ErrorHandler,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import {
  PreloadAllModules,
  provideRouter,
  TitleStrategy,
  withComponentInputBinding,
  withInMemoryScrolling,
  withNavigationErrorHandler,
  withPreloading,
  withRouterConfig,
} from '@angular/router';
import { provideSpartanHlm } from '@spartan-ng/helm/utils';
import { provideApiConfiguration } from './api/generated/api-configuration';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { ReportingErrorHandler } from './core/error-reports';
import { reloadOnStaleChunk } from './core/stale-chunk';
import { AppTitleStrategy } from './core/title-strategy';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Every error the handler sees is also reported to the server's error reports (ADR-0070).
    { provide: ErrorHandler, useClass: ReportingErrorHandler },
    provideZonelessChangeDetection(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
      withRouterConfig({ paramsInheritanceStrategy: 'always' }),
      // Every page's code loads in the background after the first page, so a menu click never waits
      // for the server to send it.
      withPreloading(PreloadAllModules),
      // A tab still running the build before a deploy loads the page fresh instead of failing silently.
      withNavigationErrorHandler(reloadOnStaleChunk),
    ),
    provideHttpClient(
      withFetch(),
      // The server's Spring Security CSRF setup uses Angular's defaults (CONVENTIONS §5);
      // spelled out so a change on either side is a deliberate one.
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      withInterceptors([authInterceptor]),
    ),
    // Operation paths already start with /api, so the root URL is the page origin.
    provideApiConfiguration(''),
    provideSpartanHlm(),
    { provide: TitleStrategy, useClass: AppTitleStrategy },
  ],
};
