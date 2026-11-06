import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink, HlmButton, HlmEmptyImports],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div hlmEmpty class="min-h-[60svh]">
      <div hlmEmptyHeader>
        <p class="text-muted-foreground font-mono text-sm">404</p>
        <h1 hlmEmptyTitle class="text-base">Page not found</h1>
        <p hlmEmptyDescription>This page doesn't exist, or it moved.</p>
      </div>
      <div hlmEmptyContent>
        <a hlmBtn variant="outline" routerLink="/">Go home</a>
      </div>
    </div>
  `,
})
export class NotFound {}
