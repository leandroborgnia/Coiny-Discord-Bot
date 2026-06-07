# Phase 1 Data Model: Foundation Skeleton

This slice has an intentionally minimal data model: just enough persistence to prove the database
is reachable end to end. No economy, ledger, cooldown, or auction entities exist yet.

## Domain value objects (`bot.domain.liveness`) — pure Java, no framework imports

### `LivenessStatus` (value object / record)

Represents the outcome of a liveness check as understood by the domain.

| Field | Type | Notes |
|-------|------|-------|
| `reachable` | `boolean` | True when the data store was successfully reached. |
| `detail` | `String` | Short human-readable note (e.g. the seeded probe label or an error summary). |

- Immutable record. No Spring/JDA/Jakarta imports.
- Convenience factories: `LivenessStatus.up(String detail)`, `LivenessStatus.down(String detail)`.

### `LivenessProbePort` (outbound port) — interface, pure Java

The domain's contract for "can we reach and read the store?". Implemented in infrastructure.

```text
interface LivenessProbePort {
    LivenessStatus probe();   // checks connectivity and reads the seeded health-check row;
                              // never throws for "down" — returns LivenessStatus.down(...)
                              // on a connection failure or read error
}
```

- Defined in the domain so the application depends inward only (Constitution Principle II).
- Infrastructure provides the implementation (a non-transactional adapter that owns connectivity).

## Persistence entity (`bot.infrastructure.persistence`)

### `HealthCheckEntity` → table `health_check`

A tiny, seeded table whose single row is read to confirm reachability. Mapped with JPA/Hibernate.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `smallint` | PRIMARY KEY | Fixed singleton id `1`. |
| `label` | `text` | NOT NULL | Seeded value, e.g. `'ok'`; returned in `LivenessStatus.detail`. |
| `created_at` | `timestamptz` | NOT NULL, default `now()` | Records when the seed row was created. |

- `created_at` uses `timestamptz`; the application maps it to `java.time.Instant` (per CLAUDE.md
  time conventions). It is informational only for this slice.
- The entity is an infrastructure adapter detail; the domain never sees it.

### Relationship to the domain

`JpaLivenessProbeAdapter implements LivenessProbePort` and is **non-transactional**. It first
verifies connectivity by opening a connection from the `DataSource` in a try-with-resources block,
**catching `SQLException` → `LivenessStatus.down(...)`**. On a healthy connection it loads the
singleton `health_check` row via `HealthCheckJpaRepository`, maps a present row to
`LivenessStatus.up(label)`, and any missing row or `DataAccessException` to `LivenessStatus.down(...)`.
No transactional proxy wraps the read, so a down database is returned as a value rather than thrown,
and the mapping never leaks the JPA entity outward.

## Schema Change History (managed by Flyway)

The spec's **Schema Change History** key entity is realized by Flyway's `flyway_schema_history`
table (created and owned by Flyway, not mapped as a JPA entity). It records, in immutable order,
which migrations have been applied, enabling restart-safe, no-manual-step startup (spec FR-009).

### Migration `V1__init_liveness.sql` (immutable once applied)

Creates and seeds the `health_check` table:

- `CREATE TABLE health_check (id smallint PRIMARY KEY, label text NOT NULL, created_at timestamptz
  NOT NULL DEFAULT now());`
- `INSERT INTO health_check (id, label) VALUES (1, 'ok') ON CONFLICT (id) DO NOTHING;`

The `ON CONFLICT ... DO NOTHING` keeps re-application/seed logic idempotent and leans on
Postgres-specific behavior, which the constitution explicitly permits. Future schema changes are
added as `V2__...`, `V3__...`; `V1` is never edited.

## Validation & invariants

- Exactly one `health_check` row with `id = 1` exists after `V1` (seed is idempotent).
- `LivenessProbePort.probe()` returns a `LivenessStatus`; it does not throw for an ordinary
  "store unreachable" condition — the application maps that to a non-success reply (spec edge case
  "data store down after healthy startup").
- No mutable balances, no derived-state storage — nothing in this model contradicts Principle III
  (there is simply no ledger yet).

## State transitions

None. All entities here are read-only at runtime (the seed is written once by the migration).
There are no lifecycle state machines in this slice.
