import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ScimSettings } from './scim-settings';
import { SignInProviders } from './sign-in-providers';

/**
 * The settings' Sign-in section (ADR-0086 decision 15, ADR-0087 decision 11): how the company's
 * people come and go. The providers people sign in with are the deployment's (ADR-0075), shown
 * read-only; what the company decides here is its SCIM provisioning, whose token its OWNER issues.
 */
@Component({
  selector: 'app-sign-in-settings',
  imports: [ScimSettings, SignInProviders],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-col gap-6' },
  template: `<app-sign-in-providers /><app-scim-settings />`,
})
export class SignInSettings {}
