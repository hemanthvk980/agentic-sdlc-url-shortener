import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { LinkResponse, LinkStats, Problem } from './api';

@Component({
  selector: 'app-shorten',
  imports: [FormsModule, DatePipe],
  template: `
    <section class="card">
      <h1>Shorten a link</h1>
      <form (ngSubmit)="create()" #f="ngForm">
        <label for="url">Long URL</label>
        <input id="url" name="url" type="url" required maxlength="2048" [(ngModel)]="url"
               placeholder="https://example.com/a/very/long/path?utm_source=newsletter" autocomplete="off" />
        <details>
          <summary>Options</summary>
          <label for="alias">Custom alias <small>(4–32: letters, digits, - _)</small></label>
          <input id="alias" name="alias" pattern="[A-Za-z0-9_-]{4,32}" [(ngModel)]="alias" />
          <label for="expires">Expires at <small>(optional)</small></label>
          <input id="expires" name="expires" type="datetime-local" [(ngModel)]="expires" />
        </details>
        <button type="submit" [disabled]="busy() || f.invalid">{{ busy() ? 'Shortening…' : 'Shorten' }}</button>
      </form>

      @if (error(); as e) {
        <p class="error" role="alert">{{ e.title }}: {{ e.detail }}
          @for (kv of errorEntries(e); track kv[0]) { <br /><small>{{ kv[0] }}: {{ kv[1] }}</small> }
        </p>
      }

      @if (link(); as l) {
        <div class="result">
          <a [href]="l.shortUrl" target="_blank" rel="noopener">{{ l.shortUrl }}</a>
          <button type="button" class="ghost" (click)="copy(l.shortUrl)">{{ copied() ? 'Copied ✓' : 'Copy' }}</button>
          <p class="muted">→ {{ l.targetUrl }}@if (l.expiresAt) { · expires {{ l.expiresAt | date: 'medium' }} }</p>
        </div>
      }
    </section>

    <section class="card">
      <h2>Link analytics</h2>
      <form (ngSubmit)="loadStats()" class="inline">
        <input name="code" [(ngModel)]="statsCode" placeholder="short code, e.g. aZ3k9Qx" required />
        <button type="submit" [disabled]="!statsCode">Show</button>
      </form>
      @if (stats(); as s) {
        <p><strong>{{ s.totalClicks }}</strong> clicks · since {{ s.since }}</p>
        @if (s.clicksByDay.length) {
          <div class="bars" aria-label="clicks per day">
            @for (d of s.clicksByDay; track d.day) {
              <div class="bar" [style.height.%]="(d.clicks / maxDaily(s)) * 100" [title]="d.day + ': ' + d.clicks"></div>
            }
          </div>
        } @else {
          <p class="muted">No clicks in the window yet.</p>
        }
        @if (s.topReferrers.length) {
          <h3>Top referrers</h3>
          <ul>
            @for (r of s.topReferrers; track r.referrer) { <li>{{ r.referrer }} — {{ r.clicks }}</li> }
          </ul>
        }
      }
    </section>
  `,
})
export class ShortenComponent {
  private readonly http = inject(HttpClient);

  url = '';
  alias = '';
  expires = '';
  statsCode = '';

  readonly busy = signal(false);
  readonly link = signal<LinkResponse | null>(null);
  readonly stats = signal<LinkStats | null>(null);
  readonly error = signal<Problem | null>(null);
  readonly copied = signal(false);

  create(): void {
    this.busy.set(true);
    this.error.set(null);
    const body: Record<string, string> = { url: this.url.trim() };
    if (this.alias.trim()) body['alias'] = this.alias.trim();
    if (this.expires) body['expiresAt'] = new Date(this.expires).toISOString();
    // A fresh idempotency key per submission makes network retries safe (no duplicate links).
    const headers = new HttpHeaders({ 'Idempotency-Key': crypto.randomUUID() });
    this.http.post<LinkResponse>('/api/v1/links', body, { headers }).subscribe({
      next: (l) => {
        this.link.set(l);
        this.statsCode = l.code;
        this.copied.set(false);
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(this.toProblem(e));
        this.busy.set(false);
      },
    });
  }

  loadStats(): void {
    this.error.set(null);
    this.http.get<LinkStats>(`/api/v1/links/${encodeURIComponent(this.statsCode.trim())}/stats`).subscribe({
      next: (s) => this.stats.set(s),
      error: (e: HttpErrorResponse) => {
        this.stats.set(null);
        this.error.set(this.toProblem(e));
      },
    });
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text).then(() => this.copied.set(true));
  }

  maxDaily(s: LinkStats): number {
    return Math.max(1, ...s.clicksByDay.map((d) => d.clicks));
  }

  errorEntries(p: Problem): [string, string][] {
    return p.errors ? Object.entries(p.errors) : [];
  }

  private toProblem(e: HttpErrorResponse): Problem {
    if (e.status === 429) return { title: 'Slow down', detail: 'Too many requests — try again in a minute.' };
    const p = (e.error ?? {}) as Problem;
    return { title: p.title ?? 'Request failed', detail: p.detail ?? e.message, errors: p.errors };
  }
}
