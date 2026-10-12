/** Mirrors the service DTOs (LinkResponse, LinkStats, RFC 7807 problem details). */
export interface LinkResponse {
  code: string;
  shortUrl: string;
  targetUrl: string;
  createdAt: string;
  expiresAt: string | null;
  status: 'ACTIVE' | 'DISABLED';
  clickCount: number;
}

export interface LinkStats {
  code: string;
  totalClicks: number;
  since: string;
  clicksByDay: { day: string; clicks: number }[];
  topReferrers: { referrer: string; clicks: number }[];
}

export interface Problem {
  title?: string;
  detail?: string;
  status?: number;
  errors?: Record<string, string>;
}

/** Shape of runs/<id>/run.json written by the orchestrator's RunReporter. */
export interface RunReport {
  runId: string;
  scenario: string;
  llm: string;
  outcome: 'SUCCEEDED' | 'FAILED' | 'STOPPED' | 'RUNNING';
  outcomeReason: string;
  requirement: string;
  nodes: Record<string, RunNode>;
  decisions: RunDecision[];
  clarifications: Record<string, string>;
  metrics: Record<string, number | object>;
  auditEvents: number;
  auditChainValid: boolean;
}

export interface RunNode {
  title: string;
  phase: string;
  dependsOn: string[];
  status: string;
  attempts: number;
  summary: string;
  error: string | null;
  commit: string | null;
  changes: { path: string; type: string; lines: number; reason: string }[];
}

export interface RunDecision {
  at: string;
  node: string | null;
  kind: string;
  actor: string;
  summary: string;
  rationale: string;
}

export interface RunIndexEntry {
  runId: string;
  scenario: string;
  outcome: string;
  llm: string;
}
