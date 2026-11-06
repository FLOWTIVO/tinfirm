import { HttpErrorResponse } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { ErrorHandler } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideTestHttp, settle } from '../testing/test-helpers';
import {
  ErrorReporter,
  framesOf,
  MAX_REPORTS_PER_PAGE,
  ReportingErrorHandler,
  routePattern,
} from './error-reports';

const CHROME_STACK = `TypeError: Cannot read properties of undefined (reading 'title')
    at ConversationPage.send (https://tinfirm.acme.example/chunk-ABC123.js:1:4211)
    at https://tinfirm.acme.example/main-XYZ.js:2:17
    at new Promise (<anonymous>)`;

const FIREFOX_STACK = `send@https://tinfirm.acme.example/chunk-ABC123.js:1:4211
@https://tinfirm.acme.example/main-XYZ.js:2:17`;

describe('error reports', () => {
  describe('framesOf', () => {
    it('reads Chromium stacks, the innermost frame first, and skips lines without a place', () => {
      expect(framesOf(CHROME_STACK)).toEqual([
        {
          function: 'ConversationPage.send',
          file: 'https://tinfirm.acme.example/chunk-ABC123.js',
          line: 1,
          column: 4211,
        },
        { file: 'https://tinfirm.acme.example/main-XYZ.js', line: 2, column: 17 },
      ]);
    });

    it('reads Firefox and Safari stacks', () => {
      expect(framesOf(FIREFOX_STACK)).toEqual([
        {
          function: 'send',
          file: 'https://tinfirm.acme.example/chunk-ABC123.js',
          line: 1,
          column: 4211,
        },
        { file: 'https://tinfirm.acme.example/main-XYZ.js', line: 2, column: 17 },
      ]);
      expect(framesOf(undefined)).toEqual([]);
    });
  });

  describe('ErrorReporter', () => {
    let http: HttpTestingController;
    let reporter: ErrorReporter;

    beforeEach(async () => {
      TestBed.configureTestingModule({
        providers: [
          provideRouter([
            { path: 'c/:companyId', children: [{ path: 'inbox', children: [] }] },
            { path: 'login', children: [] },
          ]),
          ...provideTestHttp(),
        ],
      });
      http = TestBed.inject(HttpTestingController);
      reporter = TestBed.inject(ErrorReporter);
      await TestBed.inject(Router).navigateByUrl('/c/0192/inbox');
    });

    afterEach(() => http.verify());

    it("posts the error with its route's pattern, never the URL", async () => {
      const error = new TypeError('x is undefined');
      reporter.report(error);
      await settle();

      const request = http.expectOne('/api/client-errors');
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual(
        expect.objectContaining({
          kind: 'TypeError',
          message: 'x is undefined',
          route: '/c/:companyId/inbox',
        }),
      );
      expect(JSON.stringify(request.request.body)).not.toContain('0192');
      request.flush(null, { status: 204, statusText: 'No Content' });
    });

    it('sends one of a kind a minute, and at most ten a page load', async () => {
      const now = 1_000_000;
      reporter.report(new Error('same'), now);
      reporter.report(new Error('same'), now + 1_000);
      await settle();
      http.expectOne('/api/client-errors').flush(null, { status: 204, statusText: 'No Content' });

      reporter.report(new Error('same'), now + 61_000);
      for (let i = 0; i < 20; i++) reporter.report(new Error(`other ${i}`), now + 62_000);
      await settle();
      expect(http.match('/api/client-errors')).toHaveLength(MAX_REPORTS_PER_PAGE - 1);
    });

    it("skips a failed request, which the server captured itself, and a deploy's stale chunk", async () => {
      reporter.report(new HttpErrorResponse({ status: 500 }));
      reporter.report(new TypeError('Failed to fetch dynamically imported module: /chunk-1.js'));
      await settle();
      http.expectNone('/api/client-errors');
    });

    it('stays on the page when the report is refused', async () => {
      const router = TestBed.inject(Router);
      reporter.report(new Error('boom'));
      await settle();
      http.expectOne('/api/client-errors').flush(null, { status: 401, statusText: 'Unauthorized' });
      await settle();
      expect(router.url).toBe('/c/0192/inbox');
    });
  });

  it('the handler still logs the error, then reports it', () => {
    const report = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: ErrorHandler, useClass: ReportingErrorHandler },
        { provide: ErrorReporter, useValue: { report } },
      ],
    });
    const console = vi.spyOn(globalThis.console, 'error').mockImplementation(() => undefined);
    const error = new Error('boom');

    TestBed.inject(ErrorHandler).handleError(error);

    expect(console).toHaveBeenCalled();
    expect(report).toHaveBeenCalledWith(error);
    console.mockRestore();
  });

  it('routePattern joins the matched routes’ paths', () => {
    expect(routePattern(null)).toBe('/');
  });
});
