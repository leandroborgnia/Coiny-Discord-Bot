# Implementation Plan: Coin Economy & Append-Only Audit Trail

**Branch**: `002-coin-ledger` | **Date**: 2026-06-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-coin-ledger/spec.md`

## Summary

Introduce the coin economy and its tamper-evident, append-only audit trail on top of the
foundation skeleton. Coins are whole numbers scoped **per server**; a member's balance is
**derived** from an append-only, double-entry ledger and is never stored as a mutable column.
In this slice coins move only via **moderator adjustments** (grant / deduct), gated by a
**per-server configured moderator role**, and members can view their own balance and recent
history. Balances can never go negative (overdraw fails atomically and changes nothing), are
capped at a **per-server configurable maximum (default 12)** with over-cap coins forfeited at
earning time, and the same logical operation is applied **at most once** (keyed by the Discord
interaction id).

Technical approach: stay within the existing Spring Boot 3 / Java 21 / JDA 5 / Spring Data JPA
/ Flyway / Postgres 17 stack — **no new dependencies**. Pure-domain types (`CoinAmount`, a
posting/forfeiture **policy**, ports, typed `DomainException`s) compute the double-entry
postings; a single `@Transactional` application service per use case opens the only transaction,
takes a **Postgres transaction-level advisory lock** on the (guild, member) account to remove
the read-then-write race, derives the current balance by summing ledger entries, applies the
domain policy, and appends a balanced movement + entries. A new immutable Flyway migration `V2`
creates the ledger tables, a `guild_coin_config` table, and Postgres triggers that make the
ledger **append-only** (reject UPDATE/DELETE), enforce **balanced** movements, and guarantee
**non-negative** balances. Idempotency is a `UNIQUE` constraint on the interaction id with an
`ON CONFLICT`-guarded insert. Three thin slash commands (`/balance`, `/coins-adjust`,
`/coins-config`) defer first and delegate.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.3.x (Data JPA, Validation), JDA 5.0.x
(`net.dv8tion:JDA`), Hibernate ORM (via Spring Data JPA), Flyway (`flyway-core` +
`flyway-database-postgresql`), PostgreSQL JDBC driver. **No new dependencies are added by this
feature** (advisory locks and triggers are plain SQL).

**Storage**: PostgreSQL 17 — the ONLY engine. New tables `coin_movement`, `coin_ledger_entry`,
`guild_coin_config` owned by Flyway (`V2`). The model leans on Postgres-specific behavior:
`bigserial` sequences for monotonic ordering, `pg_advisory_xact_lock` for per-account
serialization, `ON CONFLICT` for idempotent inserts, and `plpgsql` triggers / deferred
constraint triggers for append-only, balanced, and non-negative invariants.

**Testing**: JUnit 5 (Jupiter), Mockito, AssertJ, Testcontainers (`postgresql`) against a real
throwaway Postgres 17. Domain policy is unit-tested (no DB). Ledger atomicity, idempotency,
append-only triggers, cap/forfeit, and concurrent overdraw are integration-tested on real
Postgres. Tests run via Maven on the host (no Docker-in-Docker; never inside the app container).

**Target Platform**: JVM (Linux container in production; local host for development). Long-lived
process connected to the Discord gateway.

**Project Type**: Single backend service (Discord bot) — layered/hexagonal, single Maven module.

**Performance Goals**: Each command handler defers within 2.5s (Constitution V / Discord's 3s
window). The adjustment critical section (advisory lock → sum → insert) touches a handful of
rows and completes well within that budget. Balance is derived by an indexed `SUM`.

**Constraints**: `bot.domain` imports no Spring/JDA/Jakarta. Only `bot.application` opens
transactions and calls ports. Handlers contain no business logic and defer before any work.
Balances are derived, never stored as a mutable source of truth. Ledger rows are never updated
or deleted. The single `V1` migration is untouched; schema changes ship as `V2`. No new secrets.

**Scale/Scope**: Per-guild membership is modest; deriving a balance by `SUM` over an indexed
`(guild_id, member_id)` is comfortably fast at this scale. A future balance **checkpoint**
(periodic snapshot row that the `SUM` starts from) can optimize without changing the
derived-balance contract — explicitly out of scope here.

**Base package**: `bot` (per CLAUDE.md). New packages: `bot.domain.coin`, `bot.application.coin`,
`bot.discord.command` (new handlers), `bot.infrastructure.persistence.coin`. A base
`bot.domain.DomainException` (i18n message key + args) and typed subclasses are introduced per
the CLAUDE.md error model.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Gate | Status |
|---|-----------|------|--------|
| I | Postgres-Only Persistence | Only Postgres 17; relies on sequences, advisory locks, `ON CONFLICT`, triggers, MVCC; Testcontainers runs real Postgres; no H2/in-memory | PASS |
| II | Hexagonal Architecture & Domain Purity | `bot.domain.coin` is pure Java (amount, policy, ports, exceptions); only `bot.application.coin` opens the transaction and calls ports; `bot.infrastructure.persistence.coin` holds JPA + advisory lock; handlers depend inward | PASS |
| III | Append-Only, Double-Entry Coin Ledger | Every movement posts balanced entries (sum = 0); rows are append-only (triggers reject UPDATE/DELETE); **balance is derived by SUM**, never a mutable stored column. The `requested/credited/forfeited` columns on a movement are immutable *facts of that event*, not a running balance (justified below) | PASS |
| IV | Atomic Cooldown Engine | No cooldown in this slice. The related atomicity need (no overdraw / at-most-once) is met by an advisory-lock + single-transaction critical section and a `UNIQUE` idempotency key | N/A |
| V | Thin, Fast Discord Handlers | `BalanceCommand`, `AdjustCoinsCommand`, `CoinsConfigCommand` each defer first, parse the interaction into a request record, delegate to an application service, render the result/exception — no business logic | PASS |
| VI | Real-Postgres Testing Discipline | JUnit 5 + Mockito + AssertJ; domain policy unit-tested; ledger/idempotency/trigger/concurrency tests on Testcontainers Postgres on the host | PASS |
| VII | Immutable Migrations & Secret Hygiene | New immutable `V2` migration; `V1` untouched; no new secrets (no env additions) | PASS |
| — | Containerization & Environment Topology | No change: one Dockerfile, dev/prod via profile + env + compose override, separate volumes | PASS |

**Result**: All gates pass. No deviations to justify; Complexity Tracking left empty.
Principle III note (pre-empting review): the `credited_amount` / `forfeited_amount` /
`requested_amount` columns on `coin_movement` are **immutable attributes of a past event**
(what was minted, what landed, what was forfeited at that instant), written once and never
updated. The authoritative balance remains `SUM(coin_ledger_entry.amount)` for the account;
those columns are a convenience for rendering history and are not read to compute balances, so
there is no mutable stored balance and no second source of truth. Re-checked after Phase 1 —
see Post-Design Constitution Re-Check.

## Project Structure

### Documentation (this feature)

```text
specs/002-coin-ledger/
├── plan.md              # This file (/speckit-plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── slash-commands.md
│   ├── application-services.md
│   └── ledger-invariants.md
├── checklists/
│   └── requirements.md  # From /speckit-specify (+ /speckit-clarify)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/java/bot/
├── domain/
│   └── coin/                                  # pure Java — no Spring/JDA/Jakarta
│       ├── CoinAmount.java                    # value object: non-negative whole amount (≥1 to adjust)
│       ├── AdjustmentType.java                # GRANT, DEDUCTION
│       ├── LedgerAccount.java                 # MEMBER, TREASURY, FORFEIT
│       ├── PostingLine.java                   # (account, memberId?, signed amount) — one entry
│       ├── PostingPlan.java                   # the balanced set of lines for one movement
│       ├── CoinLedgerPolicy.java              # pure policy: planGrant(balance,amount,cap), planDeduction(balance,amount)
│       ├── CoinLedgerPort.java                # outbound port: lock, balance, append, history, find-by-interaction
│       ├── GuildCoinConfigPort.java           # outbound port: read/upsert per-guild role + cap
│       └── GuildCoinConfig.java               # value object: moderatorRoleId?, cap
├── domain/
│   ├── DomainException.java                   # base: i18n message key + args (NEW, per CLAUDE.md)
│   └── coin/
│       ├── OverdrawException.java
│       ├── NonPositiveAmountException.java
│       ├── ModeratorNotAuthorizedException.java
│       └── ModeratorRoleNotConfiguredException.java
├── application/
│   └── coin/                                  # @Transactional; the ONLY layer that opens tx + calls ports
│       ├── AdjustCoinsService.java            # grant/deduct: lock → derive → policy → append (one tx)
│       ├── AdjustCoinsRequest.java / AdjustCoinsResult.java
│       ├── CoinQueryService.java              # @Transactional(readOnly) balance + recent history
│       ├── ViewBalanceRequest.java / BalanceView.java
│       ├── CoinConfigService.java             # upsert per-guild moderator role + cap
│       └── ConfigureCoinsRequest.java / CoinConfigResult.java
├── discord/
│   └── command/                               # thin handlers (defer → parse → delegate → render)
│       ├── BalanceCommand.java                # /balance (open to all)
│       ├── AdjustCoinsCommand.java            # /coins-adjust grant|deduct (mod-gated)
│       └── CoinsConfigCommand.java            # /coins-config (admin-gated)
└── infrastructure/
    └── persistence/
        └── coin/
            ├── CoinMovementEntity.java         # @Entity → coin_movement
            ├── CoinLedgerEntryEntity.java      # @Entity → coin_ledger_entry
            ├── GuildCoinConfigEntity.java      # @Entity → guild_coin_config
            ├── CoinMovementJpaRepository.java  # find-by-interaction; recent-by-member
            ├── CoinLedgerEntryJpaRepository.java# sum-by-account; append
            ├── GuildCoinConfigJpaRepository.java
            ├── JpaCoinLedgerAdapter.java        # implements CoinLedgerPort (advisory lock + SUM + append)
            └── JpaGuildCoinConfigAdapter.java   # implements GuildCoinConfigPort (default cap 12)

src/main/resources/
├── db/migration/
│   └── V2__coin_ledger.sql                     # tables + indexes + append-only/balanced/non-negative triggers
└── messages/
    └── coin-messages.properties                # i18n defaults for DomainException keys + replies (English)

src/test/java/bot/
├── domain/coin/
│   └── CoinLedgerPolicyTest.java               # unit (no DB): grant/cap/forfeit/deduct/overdraw arithmetic
├── application/coin/
│   └── AdjustCoinsServiceTest.java             # unit (Mockito ports): authorization, idempotency mapping
├── infrastructure/persistence/coin/
│   ├── JpaCoinLedgerAdapterTest.java           # integration: append, SUM balance, history, find-by-interaction
│   ├── CoinLedgerTriggersTest.java             # integration: UPDATE/DELETE rejected; unbalanced rejected; negative rejected
│   └── CoinIdempotencyConcurrencyTest.java     # integration: duplicate interaction id + concurrent overdraw/cap
└── ... (reuses AbstractPostgresIntegrationTest from 001)
```

**Structure Decision**: Continue the single Maven module hexagonal layout under base package
`bot`. New domain subpackage `bot.domain.coin` holds the framework-free economy core (amount,
policy, ports, exceptions); `bot.application.coin` orchestrates and owns the single transaction;
`bot.infrastructure.persistence.coin` provides JPA adapters and the advisory-lock SQL; three new
thin handlers live in `bot.discord.command`. This preserves Constitution Principle II and keeps
the ledger logic unit-testable without a container.

## Post-Design Constitution Re-Check

After Phase 1 design (data model, contracts, quickstart): no new violations.

- **Principle II** holds — `CoinLedgerPolicy` and the value objects/ports in `bot.domain.coin`
  import no framework types; the posting math is pure. Only `bot.application.coin` services are
  `@Transactional` and call ports; the advisory lock is acquired *inside* that single
  application-owned transaction (it is `pg_advisory_xact_lock`, released on commit/rollback), so
  no adapter or handler opens a transaction.
- **Principle III** holds — each movement writes a balanced `PostingPlan` (entries sum to zero,
  enforced by a deferred constraint trigger); ledger tables reject UPDATE/DELETE via triggers;
  balance is `SUM(coin_ledger_entry.amount)`, never a stored mutable column. Over-cap coins are
  posted as a balanced TREASURY→FORFEIT pair so the books reconcile and the audit trail shows
  the forfeiture. The `credited/forfeited/requested` movement columns are write-once event facts
  (see Constitution Check note), not a balance.
- **Principle V** holds — every handler calls `deferReply()` before any work; the authorization
  rule (configured-role membership) is enforced in `AdjustCoinsService`, not the handler.
- **Atomicity (supports II/III correctness)** — overdraw and cap are evaluated under the
  per-account advisory lock in one transaction; a violation throws a typed `DomainException`
  that rolls back, leaving balance and audit trail unchanged. At-most-once is the `UNIQUE`
  interaction id with an `ON CONFLICT DO NOTHING` insert; a duplicate returns the original
  outcome without a second movement.
- **Principle VII** — `V2` is additive and immutable; no secret added. Gates remain green.

## Complexity Tracking

> No Constitution Check violations. Table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
