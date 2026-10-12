import { provideZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, withHashLocation } from '@angular/router';
import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';

// Hash routing: the Spring service owns every single-segment path (/{code} redirects),
// so client-side routes live after '#' and never collide with short codes.
bootstrapApplication(AppComponent, {
  // Zoneless: all state is in signals and zone.js is not a dependency.
  providers: [provideZonelessChangeDetection(), provideRouter(routes, withHashLocation()), provideHttpClient()],
}).catch((err) => console.error(err));
