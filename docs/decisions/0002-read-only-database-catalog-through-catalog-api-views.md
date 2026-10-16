# ADR-0002: Read the catalog through `catalog_api` views, with a hand-built read-only pool

- **Status:** Accepted (2026-10-16)
- **Scope:** backend `course.persistence`, `data/catalog-api/`

## Decision

The backend reads course data only from five PostgreSQL views in the `catalog_api` schema, through `JdbcClient` and a
HikariCP pool created by hand, and only when `CATALOG_SOURCE=database`. The views expose a course only if **every** one of
its sections passes six fail-closed readiness rules (known whole credits, each lab linked to exactly one lecture, a
confirmed "exactly one lab" rule, and so on). Anything unknown keeps the whole course out of the scheduler.

## Why

- Correctness first: an uncertified course must never reach the engine, so the rule lives in the database view where it
  cannot be bypassed by a code path, and the adapter additionally rejects inconsistent rows instead of repairing them.
- The backend role needs `SELECT` on `catalog_api` and nothing else, so a bug cannot read or write the source tables.
- The default build and tests must run with no database (CLAUDE.md). `spring-boot-starter-jdbc` was tried and rejected: its
  `DataSource` auto-configuration stops the default app from starting. Only `spring-jdbc`, `HikariCP` and the PostgreSQL
  driver (runtime) are added, all versions from the Spring Boot BOM.

## Costs

- `CourseCatalog` lists only schedulable courses, so an offered but uncertified course looks unknown (422) and the AI
  reports it unresolved. A backward-compatible `isSchedulable` default method would fix this; it changes a shared interface
  and needs the Backend API owner's approval (see `data/catalog-api/README.md`).
- A database outage is a safe `503`; there is no fallback to the dummy catalog.
