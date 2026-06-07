# Contract: Application Services

Per CLAUDE.md, every public application-service method takes a request record and returns a result
record. This slice exposes one service. It calls the domain port and Discord handlers depend on it
and never on infrastructure directly.

## `LivenessService` (`bot.application.liveness`)

```text
@Service
class LivenessService {
    CheckLivenessResult check(CheckLivenessRequest request);   // NOT @Transactional
}
```

- **Not** annotated `@Transactional`. A liveness probe is a read-only connectivity check that needs
  no transaction, and — critically — a transactional proxy would acquire a database connection
  *before* the method body runs, so a `SQLException` from a down database would surface from the
  proxy and could never be caught inside `check()`. Keeping the service non-transactional lets the
  infrastructure adapter own connectivity and turn failures into a clean `reachable = false`
  (Constitution Principle II is still satisfied: the application orchestrates, infrastructure owns
  the database access).
- Depends on `bot.domain.liveness.LivenessProbePort` (constructor-injected). It does NOT reference
  JPA, JDA, or any entity type.
- Pure delegation + mapping: calls `LivenessProbePort.probe()` and maps the returned
  `LivenessStatus` to the result record. It never throws for a "store down" condition because the
  port never throws for one — it returns a result with `reachable = false`.

### Request record — `CheckLivenessRequest`

| Field | Type | Notes |
|-------|------|-------|
| (none) | — | Empty record for this slice; a singleton instance is acceptable. Kept as a record so the signature is stable as the slice grows. |

### Result record — `CheckLivenessResult`

| Field | Type | Notes |
|-------|------|-------|
| `reachable` | `boolean` | Whether the data store was reached and the probe row read. |
| `detail` | `String` | Short note for the reply (seeded label on success, error summary otherwise). |

### Method contract — `check`

- **Preconditions**: Application context started; schema migrated (guaranteed by fail-fast startup).
- **Behavior**: Calls `LivenessProbePort.probe()`, maps `LivenessStatus` → `CheckLivenessResult`.
- **Postconditions**: Returns a non-null result; `reachable` is true only if the seeded row was
  read successfully.
- **Errors**: The service performs no error handling of its own — the adapter already converts a
  down/unreachable store into `LivenessStatus.down(...)` (see below), so `check()` always returns a
  result and never lets a transaction or `SQLException` escape to `PingCommand`.

## Port boundary (for reference)

`LivenessProbePort` (domain) ← implemented by `JpaLivenessProbeAdapter` (infrastructure). The
service talks only to the port. The adapter is **non-transactional** and owns connectivity: it
opens a connection from the `DataSource` in a try-with-resources block and **catches `SQLException`
→ `LivenessStatus.down(...)`**; on a healthy connection it reads the seeded `health_check` label via
`HealthCheckJpaRepository` (defensively mapping any `DataAccessException` to `down(...)`) and returns
`LivenessStatus.up(label)`. Because no transactional proxy wraps the call, a down database is caught
as a value rather than thrown. See [../data-model.md](../data-model.md) for the entity and port
definitions.
