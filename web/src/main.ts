import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { applyInitialTheme } from './app/core/theme';

// Set the theme class before the first render so dark-mode users never see a light flash.
applyInitialTheme();

bootstrapApplication(App, appConfig).catch((err: unknown) => console.error(err));
