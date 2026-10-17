# data

Placeholder for non-code data: course catalog snapshots, section/meeting-time seed files, and sample student inputs used for development and tests.

Guidelines:
- Commit only public or synthetic data. No real student records or personal information.
- Prefer plain, diffable formats (CSV or JSON) and note each file's source and retrieval date in this README.

## Database layers

| Folder | Contents |
|---|---|
| [cse007-snapshot/](cse007-snapshot/README.md) | The verified CSE 007 PostgreSQL schema and seed, loaded unchanged |
| [catalog-api/](catalog-api/README.md) | Read-only `catalog_api` views with six fail-closed readiness rules (what the backend reads) |
| [banner-evidence/](banner-evidence/README.md) | Append-only evidence schema and a read-only Banner collector (no Banner records are published) |
| [setup-local-database.sh](setup-local-database.sh) | Builds a private local database from the three folders above and verifies it; see [../docs/database.md](../docs/database.md) |
