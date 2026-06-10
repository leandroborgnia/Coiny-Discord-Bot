# Phase 0 Research: Coin Economy & Append-Only Audit Trail

The stack is fixed by the constitution and CLAUDE.md, and the four open product questions were
resolved in `/speckit-clarify` (per-server scope; configured moderator role; interaction-id
idempotency; per-server cap default 12). There are therefore **no open `NEEDS CLARIFICATION`
items**. This document records the design decisions that turn the spec + clarifications into a
buildable ledger, with rationale and rejected alternatives.

## Decision 1 — Ledger shape: double-entry postings grouped by a movement

- **Decision**: Model two append-only tables. `coin_movement` is the economic-event header (one
  per grant/deduct), and `coin_ledger_entry` holds the **balanced postings** (≥2 signed rows per
  movement that sum to zero). Accounts are identified by `(guild_id, account, member_id?)` where
  `account ∈ {MEMBER, TREASURY, FORFEIT}`. A member's balance is
  `SUM(coin_ledger_entry.amount)` over their `MEMBER` rows in that guild.
  - **Grant** of N to a member at balance B with cap C: `creditable = clamp(C − B, 0, N)`,
    `forfeited = N − creditable`. Postings: `TREASURY −creditable`, `MEMBER +creditable`, and if
    `forfeited > 0` also `TREASURY −forfeited`, `FORFEIT +forfeited`. Net = 0.
  - **Deduct** of N from a member at balance B (requires `N ≤ B`): `MEMBER −N`, `TREASURY +N`.
- **Rationale**: Constitution Principle III mandates an append-only, double-entry ledger with
  **derived** balances. Per-guild `TREASURY` (the mint/source) and `FORFEIT` (the sink) accounts
  keep every server's books independently reconciled to zero and make forfeitures first-class,
  auditable movements rather than silent losses.
- **Alternatives considered**: A single signed `balance` column updated in place — **rejected**,
  it stores a mutable balance (forbidden by Principle III) and loses the audit trail. A single
  one-sided "transaction" row without a balancing counterpart — rejected, not double-entry and
  cannot prove the books reconcile.

## Decision 2 — Balances are derived by SUM, not stored

- **Decision**: Never persist a running balance as a source of truth. Compute it on demand with
  `SELECT COALESCE(SUM(amount), 0) FROM coin_ledger_entry WHERE guild_id = ? AND account = 'MEMBER'
  AND member_id = ?`, backed by an index on `(guild_id, member_id)`.
- **Rationale**: Principle III ("balances MUST be DERIVED … never stored as a mutable column that
  can drift"). At this scale an indexed `SUM` is fast.
- **Alternatives considered**: A materialized `member_balance` row updated per movement —
  rejected as a forbidden second source of truth that can drift. A periodic **checkpoint/snapshot**
  (a sealed row the `SUM` resumes from) is a *future* optimization that preserves the derived
  contract; explicitly out of scope now.

## Decision 3 — Atomicity: per-account advisory lock inside one application transaction

- **Decision**: `AdjustCoinsService` (the only transactional component for this path) takes a
  Postgres **transaction-level advisory lock** keyed on a hash of `(guild_id, member_id)` —
  `SELECT pg_advisory_xact_lock(?)` — as the first DB action, then derives the balance, applies
  the domain policy, and appends the movement + entries. The lock serializes concurrent
  adjustments to the *same* account and is released automatically on commit/rollback.
- **Rationale**: Principle IV's spirit (no read-then-write race) and FR-002/FR-007: overdraw and
  cap must be evaluated against a balance that cannot change underfoot. The advisory lock gives a
  single atomic critical section without storing state, and the constitution explicitly permits
  advisory locks. A failed rule throws a typed `DomainException` → rollback → nothing changes.
- **Alternatives considered**: `SERIALIZABLE` isolation with retry — viable but pushes
  serialization failures into every caller and is heavier than a per-account lock. A conditional
  `UPDATE … WHERE balance + delta BETWEEN 0 AND cap` on a stored balance — rejected, it
  reintroduces a stored balance (Principle III). Optimistic version columns — rejected, more
  moving parts than an advisory lock for a low-contention per-account path.

## Decision 4 — Idempotency: UNIQUE interaction id + ON CONFLICT guard

- **Decision**: `coin_movement.interaction_id` carries the Discord interaction (command
  invocation) id with a `UNIQUE` constraint. The service inserts the movement with
  `INSERT … ON CONFLICT (interaction_id) DO NOTHING`; if no row is inserted, the operation was
  already applied — it loads the existing movement and returns that original outcome
  (`outcome = DUPLICATE`) without writing a second movement or any entries.
- **Rationale**: Clarification chose the interaction id as the at-most-once key (FR-008/FR-021).
  Snowflake interaction ids are globally unique and supplied automatically, so retries of the
  same invocation collapse to one application while a deliberately re-issued command (a new
  invocation) is correctly a new operation.
- **Alternatives considered**: A moderator-supplied reference — rejected by clarification (relies
  on humans). A derived `(mod, member, amount, reason)` + time-window key — rejected, can wrongly
  merge two legitimately identical adjustments. An in-memory dedup cache — rejected, not durable
  across restarts and not a single source of truth.

## Decision 5 — Tamper-evidence: Postgres triggers enforce append-only, balanced, non-negative

- **Decision**: `V2` adds plpgsql triggers:
  1. **Append-only** — `BEFORE UPDATE OR DELETE` on `coin_movement` and `coin_ledger_entry`
     raises an exception, so posted rows can never be modified or removed.
  2. **Balanced movement** — a `DEFERRABLE INITIALLY DEFERRED` constraint trigger checks, at
     transaction commit, that each touched movement's entries sum to zero.
  3. **Non-negative balance** — a deferred constraint trigger checks that an affected `MEMBER`
     account's `SUM(amount) ≥ 0` after the movement.
- **Rationale**: Principles I + III. Enforcing these in the database (not only the application)
  makes the guarantees tamper-evident and true even against any future or out-of-band writer,
  and leans on Postgres-specific behavior the constitution encourages.
- **Why the cap is *not* a DB trigger**: the cap is configurable and may be lowered **below** an
  existing balance, which the spec explicitly allows (no retroactive confiscation). An absolute
  `balance ≤ cap` constraint would be violated by that legal state. The cap is therefore enforced
  **at earning time** in `CoinLedgerPolicy` (credit up to the cap, forfeit the rest); the DB only
  guarantees the always-true invariant `balance ≥ 0`.
- **Alternatives considered**: Revoking UPDATE/DELETE privileges from the app role — complementary
  but coarser and environment-dependent; triggers give a clear, portable, self-documenting error.
  Application-only enforcement — rejected as the sole mechanism; it is not tamper-evident.

## Decision 6 — Authorization: per-server configured moderator role, checked in the service

- **Decision**: A `guild_coin_config` row per guild stores `moderator_role_id` (nullable) and
  `coin_cap` (default 12). The adjustment handler reads the invoking member's role ids and admin
  flag from the interaction and passes them in the request; `AdjustCoinsService` loads the guild
  config and authorizes only if the moderator role is configured **and** the caller holds it (a
  server Administrator is also allowed, since admins configure the role). If the role is unset the
  service throws `ModeratorRoleNotConfiguredException` — the feature **fails closed**.
- **Rationale**: Clarification chose a per-server configured role (FR-009/FR-011a/FR-015).
  Keeping the authoritative check in the application service (not the handler) respects Principle
  V and makes the rule unit-testable. Discord's `default_member_permissions` on the command is set
  to **Manage Server** as a coarse visibility gate, but the service check is authoritative.
- **Alternatives considered**: Relying solely on Discord permissions / a fixed permission like
  Manage Server — rejected, the spec requires a *configurable* role. Checking the role in the
  handler — rejected, puts a business rule in the inbound adapter.

## Decision 7 — Command surface: three thin commands by permission tier

- **Decision**: Separate top-level slash commands so each can carry its own
  `default_member_permissions`:
  - `/balance` — open to all; shows the caller's own balance + recent history.
  - `/coins-adjust grant|deduct member amount [reason]` — visible to Manage Server; authoritative
    configured-role check in the service.
  - `/coins-config [role] [cap]` — visible to Administrator; sets the per-guild moderator role
    and/or cap.
  Each is a `SlashCommandHandler` bean routed by the existing `InteractionRouter`, registered
  per-guild by the existing `SlashCommandRegistrar` (the 001 mechanism, no changes).
- **Rationale**: Discord applies `default_member_permissions` per top-level command, not per
  subcommand, so splitting by permission tier gives correct visibility while the in-service checks
  remain authoritative. `grant`/`deduct` share the moderator tier, so they fit as two subcommands
  of one `/coins-adjust` command and one handler/service path with a direction enum.
- **Alternatives considered**: One mega `/coins` command with all subcommands — rejected, cannot
  hide the mod/admin subcommands from regular members (per-subcommand permissions are
  unsupported). Two separate `/coins-grant` and `/coins-deduct` commands — workable but redundant;
  the subcommand pair is cleaner.

## Decision 8 — Domain error model: typed DomainExceptions with i18n keys

- **Decision**: Introduce an abstract `bot.domain.DomainException` carrying an i18n **message
  key** (+ args), and typed subclasses `OverdrawException`, `NonPositiveAmountException`,
  `ModeratorNotAuthorizedException`, `ModeratorRoleNotConfiguredException`. Default English
  messages live in `messages/coin-messages.properties`. Handlers catch `DomainException` and
  render the resolved message into the deferred reply.
- **Rationale**: CLAUDE.md mandates "typed `DomainException`s with i18n keys; never throw
  `RuntimeException` directly." This is the first slice that needs a real error model, so the base
  type is introduced here. Throwing for rule violations gives the atomic rollback the spec
  requires (overdraw changes nothing).
- **Alternatives considered**: Returning error codes in the result record — rejected for failure
  cases; an exception cleanly aborts the transaction. Hard-coded English strings in handlers —
  rejected, violates the i18n-key convention and scatters copy.

## Decision 9 — Whole-coin amounts as a domain value object

- **Decision**: `CoinAmount` is a value object over an integer count (the smallest unit is 1).
  Adjustment amounts must be `≥ 1`; construction rejects zero/negative (`NonPositiveAmountException`)
  and, being integer-typed, cannot represent fractions. Storage uses integer columns (`int` for
  amounts/cap, `bigint` for entry sums to stay headroom-safe).
- **Rationale**: FR-001/FR-016 (whole numbers, smallest unit 1, reject ≤ 0). A value object keeps
  the invariant in one pure, unit-testable place.
- **Alternatives considered**: Passing raw `int` everywhere — rejected, scatters validation.
  Decimal/`long ms`-style units — rejected, coins are whole and CLAUDE.md forbids primitive-obsession
  for domain quantities.

## Dependencies

**No new dependencies.** Everything is already on the classpath from `001-foundation-skeleton`
(Spring Data JPA, JDA 5, Flyway, Postgres driver, Testcontainers). Advisory locks, `ON CONFLICT`,
and triggers are plain SQL in the `V2` migration and native queries — no library is added, so the
constitution's "record dependencies in plan.md before adding" gate has nothing to record here.
