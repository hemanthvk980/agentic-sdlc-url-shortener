# Scenario walkthroughs

Each scenario is a directory under [`scenarios/`](../scenarios) with a `scenario.json` (requirement,
constraints, scope, verification command, budgets, scripted human decisions) and `fixtures/` (offline
stand-ins for model output). The committed runs under [`runs/`](../runs) were produced with
`--llm offline`; see the note in the [README](../README.md#scenarios).

Reproduce any of them:

```bash
./gradlew :orchestrator-core:run --args="run --scenario scenarios/<name>/scenario.json --llm offline [--replan]"
```

---

## 1. Greenfield — "build a public URL shortener with analytics"

**Run:** [`runs/greenfield-20261012-160221/report.md`](../runs/greenfield-20261012-160221/report.md)

| Step | What happened | Control demonstrated |
|---|---|---|
| requirements ∥ repo-index | 4 FRs, 5 NFRs, 5 acceptance criteria; 3 low/medium ambiguities become logged assumptions (no default expiry, new code per request, 180-day retention) | Parallel start; ambiguity triage |
| impact-analysis | Repo index says greenfield → answered deterministically, **no model call** | Spend model budget only where judgment is needed |
| design 🔒 | API contract, data model, 3 ADRs (302 vs 301, random codes, async clicks), risk register, 5 tasks with dependencies → approved | Task decomposition, exit gate validates the task DAG, human sign-off |
| implementation ∥ tests ∥ docs | Attempt 1 puts `password: "Sup3rS3cretPw"` in `application.yml` → **`security.secret-scan` BLOCK**, nothing written; attempt 2 uses `${DATABASE_PASSWORD:}` | Policy guardrail + self-correction |
| | New Flyway migration + new build file → approval required → approved | Policy-triggered human checkpoint |
| verify | Build check fails: `LinkController.java` unbalanced braces → **rollback**; repair agent gets the log, fixes the file; re-verify passes | Exit gate, rollback, bounded repair loop, MTTR measured |
| review | All 5 criteria implemented + tested; MEDIUM findings (rate limiting not yet implemented, IPv6 ULA) | Independent review |
| release-readiness 🔒 | Checklist all PASS, audit chain verified → approved; CHANGELOG written | Deterministic release gate + final human sign-off |

Metrics: 12 model calls, 2 retries, 1 rollback, 1 policy block, 2 recoveries.

The reviewer's MEDIUM findings are exactly what the hand-built [`url-shortener-service`](../url-shortener-service)
goes on to address (rate limiting, IPv6 unique-local rejection, injectable `Clock`) — the human owns final quality.

## 2. Brownfield — "add an atomic batch-create endpoint"

**Run:** [`runs/brownfield-20261012-160226/report.md`](../runs/brownfield-20261012-160226/report.md) ·
**Diff:** [`changes.patch`](../runs/brownfield-20261012-160226/changes.patch)

| Step | What happened | Control demonstrated |
|---|---|---|
| repo-index | Real codebase seeded into the workspace: 126 files, 5 endpoints, 3 entities, 2 migrations, 13 test files | Deterministic codebase reasoning |
| impact-analysis | Attempt 1 cites `LinkValidator.java`, which does not exist → **`impacted-files-exist` gate fails**, rollback, retry → correct analysis: 4 impacted files, 3 new files, no schema change, regression risks (single create, rate limiting) | Hallucination guard |
| design 🔒 | All-or-nothing transaction, reuse `create()`; ADR vs 207 Multi-Status; 4 tasks → approved | Architectural reasoning on existing code |
| implementation ∥ tests ∥ docs | Modifies `LinkController`, `LinkService`, `GlobalExceptionHandler`; adds DTOs, exception, integration tests, README row | Change confined to `url-shortener-service/` by path policy |
| verify | Passes first time | — |
| review | **HIGH:** a batch consumes one rate-limit token but creates 50 links (30/min → 1,500/min) → escalated to human | Agent-raised risk → approval |
| | Human approves with *risk accepted* for internal rollout + follow-up ticket | Risk acceptance is recorded and checked by release readiness |
| release-readiness 🔒 | "No unaccepted HIGH findings" passes **because** the acceptance is in the decision log | Traceable exception handling |

## 3. Ambiguous — "make analytics more useful and the service more reliable"

**Run:** [`runs/ambiguous-20261012-160231/report.md`](../runs/ambiguous-20261012-160231/report.md)

| Step | What happened | Control demonstrated |
|---|---|---|
| requirements | Two **HIGH** ambiguities (what "useful analytics" means; which reliability problem) → blocking questions | Ambiguity detection |
| | Human: analytics = configurable 7–90 day window + distinct referrers; reliability = **out of scope** until SLOs exist | Human clarification narrows scope |
| | Stage re-executes with answers → testable criteria; reliability moved to out-of-scope | In-stage re-plan |
| design 🔒 | Proposes default window 7 days → human **requests changes** ("keep 30 so existing clients see no change") → design revised, risk register updated → approved | Bounded revision loop |
| implementation ∥ tests ∥ docs → verify → review → release 🔒 | Additive `?days=` parameter, `distinctReferrers`, bounds validation, tests, docs | — |
| **upstream change** (`--replan`) | Stakeholder adds AC4 ("response states the applied window") → engine invalidates the 8 stages that consumed `requirements.json` (directly or transitively) and re-runs them; **`repo-index` keeps its result** | Hash-based targeted re-planning |

Metrics: 2 re-plans, 4 approvals, 1 change request.

---

## Recording live runs

```bash
export GEMINI_API_KEY=...                        # from aistudio.google.com; never commit
./gradlew :orchestrator-core:run --args="run --scenario scenarios/brownfield/scenario.json --llm gemini --record"
```

- Uses the real model and the real build (`./gradlew :url-shortener-service:test`) as the verify gate.
- `--record` stores each response under `scenarios/<name>/recordings/` keyed by a hash of the prompt;
  `--llm replay` then reproduces the run with no key and fails if any prompt differs.
- Add `--interactive` to make approvals and clarifications yourself at the terminal.
- Live runs are not deterministic; expect different designs and occasionally more retries. That is
  what the gates, policies and budgets are for.
