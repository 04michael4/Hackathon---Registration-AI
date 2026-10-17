# Architecture Decision Records (ADRs)

An ADR records **one** significant technical decision: what we decided, why, and what it costs us. ADRs are short and are never rewritten after acceptance. To change a decision, write a new ADR that supersedes the old one, and update the old one's status.

Smaller decisions live in [architecture.md § 15](../architecture.md#15-phase-1-decisions-implemented). Write an ADR when a decision is hard to reverse, affects more than one module, or is likely to be questioned later.

## Index

| ADR | Title | Status | Date |
|---|---|---|---|
| [0001](0001-java-spring-boot-over-python-fastapi.md) | Java 21 + Spring Boot for the backend instead of Python + FastAPI | Accepted | 2026-10-15 |
| [0003](0003-promote-evidence-through-approvals-into-catalog-live.md) | Promote Banner evidence through recorded approvals into `catalog_live` | Accepted for implementation (nothing promoted) | 2026-10-16 |
| [0004](0004-proposed-class-standing-eligibility.md) | Separate schedule proposals from registration eligibility, and show every unresolved requirement | Proposed | 2026-10-16 |
| [0005](0005-store-the-researched-term-catalog-separately-from-serving.md) | Store the complete researched term catalog separately from serving (`catalog_research`) | Proposed (implemented locally; nothing served) | 2026-10-17 |

## How to add one

1. Copy the template below to `NNNN-short-kebab-title.md`, using the next number.
2. Set **Status: Proposed**, and open a PR so the team can discuss it.
3. On merge, set **Status: Accepted** and add a row to the index.
4. Link it from the relevant section of `architecture.md`.

Status values: `Proposed` · `Accepted` · `Superseded by ADR-NNNN` · `Deprecated`.

## Template

```markdown
# ADR-NNNN: <Decision title>

- **Status:** Proposed | Accepted | Superseded by ADR-NNNN | Deprecated
- **Date:** YYYY-MM-DD
- **Deciders:** <names or GitHub handles>

## Decision
One or two sentences stating what we decided.

## Context
The problem and the constraints at the time of the decision.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|

## Reasoning
Why the chosen option wins, given the context.

## Consequences
What becomes easier, what becomes harder, and any follow-up work.
```
