import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { KeyValuePipe } from '@angular/common';
import { RunIndexEntry, RunNode, RunReport } from './api';

/**
 * Read-only orchestration console: renders the evidence the orchestrator committed under runs/
 * (copied into the web build by scripts/export-runs.sh). Live agent execution is intentionally NOT
 * exposed on the public site — it would be an open LLM-cost and abuse vector.
 */
@Component({
  selector: 'app-runs',
  imports: [KeyValuePipe],
  template: `
    <section class="card">
      <h1>Agent runs</h1>
      <p class="muted">Recorded executions of the SDLC orchestrator. Each run is a DAG of governed stages with
        gates, policies, human approvals and a hash-chained audit log.</p>
      @if (index().length === 0) {
        <p class="muted">No runs exported. Run <code>scripts/export-runs.sh</code> before building the web app.</p>
      }
      <div class="tabs">
        @for (r of index(); track r.runId) {
          <button type="button" [class.active]="selected()?.runId === r.runId" (click)="select(r.runId)">
            <span class="pill" [class]="r.outcome.toLowerCase()">{{ r.outcome }}</span> {{ r.scenario }}
          </button>
        }
      </div>
    </section>

    @if (selected(); as run) {
      <section class="card">
        <h2>{{ run.scenario }} <small class="muted">{{ run.runId }}</small></h2>
        <blockquote>{{ run.requirement }}</blockquote>
        <p>
          <span class="pill" [class]="run.outcome.toLowerCase()">{{ run.outcome }}</span>
          {{ run.outcomeReason }} · model <code>{{ run.llm }}</code> ·
          audit {{ run.auditEvents }} events {{ run.auditChainValid ? '✓ chain verified' : '✗ chain broken' }}
        </p>

        <h3>Execution graph</h3>
        <div class="dag">
          @for (layer of layers(); track $index) {
            <div class="layer">
              @for (id of layer; track id) {
                <div class="node" [class]="run.nodes[id].status.toLowerCase()" [title]="run.nodes[id].summary">
                  <strong>{{ run.nodes[id].title }}</strong>
                  <small>{{ run.nodes[id].status }} · {{ run.nodes[id].attempts }} attempt(s)</small>
                </div>
              }
            </div>
          }
        </div>

        <h3>Reliability metrics</h3>
        <div class="metrics">
          @for (m of metricKeys; track m) {
            <div><strong>{{ metric(run, m) }}</strong><small>{{ m }}</small></div>
          }
        </div>

        <h3>Stages</h3>
        <table>
          <thead><tr><th>Stage</th><th>Status</th><th>Summary</th><th>Files</th></tr></thead>
          <tbody>
            @for (n of run.nodes | keyvalue: keepOrder; track n.key) {
              <tr>
                <td>{{ n.key }}</td>
                <td><span class="pill" [class]="n.value.status.toLowerCase()">{{ n.value.status }}</span></td>
                <td>{{ n.value.summary }}@if (n.value.error) { <br /><small class="error">{{ n.value.error }}</small> }</td>
                <td>{{ n.value.changes.length }}</td>
              </tr>
            }
          </tbody>
        </table>

        <h3>Decision log</h3>
        <table>
          <thead><tr><th>Stage</th><th>Kind</th><th>Actor</th><th>Decision</th><th>Rationale</th></tr></thead>
          <tbody>
            @for (d of run.decisions; track $index) {
              <tr [class.human]="d.actor.startsWith('human')">
                <td>{{ d.node ?? '—' }}</td><td>{{ d.kind }}</td><td>{{ d.actor }}</td>
                <td>{{ d.summary }}</td><td>{{ d.rationale }}</td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    }
  `,
})
export class RunsComponent {
  private readonly http = inject(HttpClient);

  readonly index = signal<RunIndexEntry[]>([]);
  readonly selected = signal<RunReport | null>(null);
  readonly metricKeys = ['e2eLatencyExcludingHumanMs', 'llmCalls', 'retries', 'rollbacks', 'mttrMs',
    'policyBlocks', 'approvalsGranted', 'approvalsRejected', 'replans'];

  /** Topological layers computed from dependsOn, so the DAG renders left → right. */
  readonly layers = computed(() => {
    const run = this.selected();
    if (!run) return [] as string[][];
    const depth: Record<string, number> = {};
    const nodeDepth = (id: string): number => {
      if (depth[id] !== undefined) return depth[id];
      const n: RunNode = run.nodes[id];
      depth[id] = n.dependsOn.length === 0 ? 0 : 1 + Math.max(...n.dependsOn.map(nodeDepth));
      return depth[id];
    };
    const out: string[][] = [];
    Object.keys(run.nodes).forEach((id) => (out[nodeDepth(id)] ??= []).push(id));
    return out;
  });

  constructor() {
    this.http.get<RunIndexEntry[]>('runs/index.json').subscribe({
      next: (idx) => {
        this.index.set(idx);
        if (idx.length) this.select(idx[0].runId);
      },
      error: () => this.index.set([]),
    });
  }

  select(runId: string): void {
    this.http.get<RunReport>(`runs/${runId}/run.json`).subscribe((r) => this.selected.set(r));
  }

  metric(run: RunReport, key: string): string {
    const v = run.metrics[key];
    return typeof v === 'number' ? String(v) : '—';
  }

  /** keyvalue pipe sorts by default; keep the orchestrator's topological order instead. */
  keepOrder = (): number => 0;
}
