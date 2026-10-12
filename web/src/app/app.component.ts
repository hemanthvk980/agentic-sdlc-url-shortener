import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="top">
      <a class="brand" routerLink="/">sho<span>.</span>rt</a>
      <nav>
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Shorten</a>
        <a routerLink="/runs" routerLinkActive="active">Agent runs</a>
        <a href="/swagger-ui.html">API</a>
      </nav>
    </header>
    <main><router-outlet /></main>
    <footer>
      Built by a governed multi-agent SDLC orchestrator ·
      <a href="https://github.com/hemanthvk980/agentic-sdlc-url-shortener">source</a>
    </footer>
  `,
})
export class AppComponent {}
