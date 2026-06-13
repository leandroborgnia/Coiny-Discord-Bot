# Implementation Plan: Skip Jar

**Branch**: `006-skip-jar` | **Date**: 2026-06-13 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/006-skip-jar/spec.md`

## Summary

The **skip jar** lets the people playing the current week's game retire it early by paying coins. A
member pays **exactly one non-refundable coin** to vote to move on; once contributions reach a
threshold — a **majority** of the distinct members who have earned coins from the current game,
floored at a configurable minimum (default **3**) — the current game is retired early and the
rotation advances **using the same deterministic rules as the normal weekly advance**. A game cannot
be skipped until it has been current for a configurable **dwell time** (default **24 h**). Admins (the
configured economy/moderator role) tune the floor, the dwell, and a **participation gate** (on by
default → only earners may contribute; off → any member may).

Technical approach: extend the existing hexagonal codebase. A new pure-domain package
`bot.domain.skipjar` holds the threshold arithmetic (`SkipThresholdPolicy`), the per-server config
value object, the contribution ledger plan (`SkipJarLedgerPolicy`), and outbound ports. A new
`@Transactional` `ContributeToSkipJarService` in `bot.application.skipjar` orchestrates the whole vote
**under the existing per-guild queue advisory lock** (`QueuePort.lockQueue`) — serializing every
contribution and the rotation so the threshold-meeting contribution triggers **exactly one** early
advance with no double-advance (FR-011). Each contribution **reuses feature 002's append-only
double-entry ledger**: a balanced `MEMBER −1 / SKIP_POT +1` movement of a new additive type
`SKIP_JAR`, posted through the existing `CoinLedgerPort` (the Discord interaction id is the
idempotency key, mirroring queue spends). The early skip **reuses feature 004's deterministic
advance** — `AdvanceRotationService` gains a `skip(guildId, now)` that performs exactly one pop using
the *same* pop logic as the weekly loop (top → designate → shiftUp → cooldowns), differing only in
that the new game's clock baseline is `now`.

The **current run** is identified by the existing `RotationState` (read via `RotationStatePort.get`):
`currentSlotId` (none ⇒ no game to skip), `currentWeekNumber` (the run key that scopes contributions
and resets the jar for free when the game changes), and `lastPopAt` (when the current game became
current — the dwell baseline). The **distinct earner set** is read from the existing ledger:
members with a `PARTICIPATION` movement crediting ≥ 1 coin since `lastPopAt` (no new attribution
column on `coin_movement`). A new Flyway migration **V5** is purely additive (V1–V4 untouched): two
new tables plus the additive `SKIP_POT` account and `SKIP_JAR` movement-type CHECK extensions. Two
thin handlers (`/skip`, `/skip-config`) defer within 2.5 s and delegate.

MVP = **US1 (contribute)** + **US2 (skip triggers)** together (a contribution that can never trigger
a skip is half a feature, and US2 is the payoff that gives US1 its teeth); **US3 (status view)** and
**US4 (admin config)** layer on.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.3.5 (Data JPA, Validation, Context), JDA 5.2.1, Flyway (core +
postgresql), PostgreSQL 17 driver. **No new Maven coordinate is required** — contributions reuse the
existing coin ledger and per-guild advisory lock, the early skip reuses the queue's advance, and the
earner set reuses the participation feature's ledger movements. (Recorded per Constitution
"Development Workflow": this feature adds **no** dependency to the build.)

**Storage**: PostgreSQL 17 only (dev/prod in Docker; tests via Testcontainers). Two new tables in
**V5**; reuses `coin_movement` / `coin_ledger_entry` for the contribution spend, and reads
`queue_rotation_state` (current run) + `coin_movement` (earner set).

**Testing**: JUnit 5 + Mockito + AssertJ + Testcontainers (real throwaway Postgres, on the host, no
secrets). The threshold arithmetic and the contribution ledger plan are unit-tested as pure domain
policies without a DB; once-per-run uniqueness, dwell gating, the earner-set/threshold query, the
non-refundable balanced posting, the early-skip advance, and concurrent-threshold no-double-advance
are integration-tested on real Postgres.

**Target Platform**: Linux container (single multi-stage image), run via Docker Compose launch scripts.

**Project Type**: Single Spring Boot service (Discord bot). Existing four-layer hexagonal layout.

**Performance Goals**: Discord interaction acknowledged < 2.5 s (defer-first) for `/skip` and
`/skip-config`. A contribution is one short transaction holding the per-guild queue advisory lock and
one account lock; the threshold-meeting one additionally performs a single pop. The status view is a
lock-free read.

**Constraints**: No new database engine; no dotenv; secrets only as `${...}` env vars via Compose;
V1–V4 migrations immutable (changes ship as V5); the domain stays framework-free; handlers defer
before any work; the early skip introduces **no** new advance behavior (reuses the queue's
deterministic pop); contributed coins are never refunded; tests require no secrets and never run in a
container.

**Scale/Scope**: Multi-guild; per-server contributions/floor/dwell/gate isolation. 2 slash commands
(`/skip` with `contribute` + `status`; `/skip-config` with `floor`, `dwell`, `gate`), 3 application
services (Contribute, ViewStatus, Configure) + 1 new method on `AdvanceRotationService`, 2 new tables,
1 migration (V5), 1 new ledger account (`SKIP_POT`) + 1 new movement type (`SKIP_JAR`).

## Constitution Check

*GATE: evaluated before Phase 0 and re-affirmed after Phase 1 design (below).*

| # | Principle | Compliance in this plan |
|---|-----------|--------------------------|
| I | Postgres-only | New tables use `bigint`/`boolean`/`int`/`timestamptz` with `CHECK` (`threshold_floor > 0`, `dwell_seconds > 0`), a composite PK `(guild_id, week_number, member_id)` enforcing once-per-run (`ON CONFLICT`), and additive CHECK rewrites. No H2/in-memory, no portability shims. ✓ |
| II | Hexagonal / domain purity | `bot.domain.skipjar` is pure Java (threshold policy, ledger plan, config value object, ports). `bot.application.skipjar` services are the only place opening transactions / calling ports. JPA + JDA confined to `bot.infrastructure` / `bot.discord.command`. ✓ |
| III | Append-only double-entry ledger | Each contribution posts a **balanced** `MEMBER −1 / SKIP_POT +1` movement via the existing `CoinLedgerPort.append`; posted rows are never mutated; balances stay derived. The only ledger schema change is the **additive** `SKIP_POT` account + `SKIP_JAR` movement type. Non-refundable: no reversing movement is ever written. ✓ |
| IV | Atomic affordability / at-most-once / no double-advance | The whole vote runs under the existing per-guild **`QueuePort.lockQueue`** advisory lock (serializing contributions *and* the rotation advance), then the per-account `lockAccount` for the debit — same lock order as `ProposeGameService`. Once-per-run is the transactional `skip_contribution` PK insert; affordability is the V2 non-negative trigger; the early skip happens inside the same locked transaction, so concurrent threshold-meeting contributions resolve to exactly one pop (FR-011). ✓ |
| V | Thin, fast handlers | `/skip` and `/skip-config` defer first, then delegate to a service (request record in, result record out) and render. No business logic in the handler. ✓ |
| VI | Real-Postgres testing | Threshold/ledger-plan policies need no DB; once-per-run, dwell, earner-set sizing, balanced non-refundable posting, early-skip advance, and concurrent no-double-advance use Testcontainers Postgres. `./mvnw verify` stays green and secret-free. ✓ |
| VII | Immutable migrations / config / secrets | New **V5**; V1–V4 untouched (V5 only ADDs tables and re-adds the two CHECKs additively to include `SKIP_POT` / `SKIP_JAR`). New config under `skipjar:` defaults in `application.yml`; no secrets introduced, no dotenv. ✓ |

**Result**: PASS. No Core-Principle deviation — **Complexity Tracking is empty**. Two cross-feature
couplings (reusing the queue's advance for the early skip; reading the participation ledger for the
earner set) are recorded below as noted reads, consistent with how feature 005 already composes the
queue and coin ports.

## Project Structure

### Documentation (this feature)

```text
specs/006-skip-jar/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions & rationale
├── data-model.md        # Phase 1 — entities, V5 schema, invariants
├── quickstart.md        # Phase 1 — runnable validation guide
├── contracts/           # Phase 1 — interface contracts
│   ├── slash-commands.md            # /skip + /skip-config subcommands & authorization
│   ├── application-services.md      # request/result records, the contribution algorithm, status & config
│   └── ledger-and-rotation.md       # SKIP_JAR posting, earner-set query, the early-skip advance, ports
└── tasks.md             # Phase 2 (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/java/bot/
├── domain/skipjar/                           # PURE — no Spring/JDA/JPA imports
│   ├── GuildSkipJarConfig.java               # (guildId, thresholdFloor, Duration dwell, boolean gateOn); defaults(guildId)=(3, 24h, true)
│   ├── SkipThresholdPolicy.java              # threshold(distinctEarners, floor) = max(floor(N/2)+1, floor)
│   ├── SkipJarLedgerPolicy.java              # planContribution(memberId, balance) → balanced MEMBER −1 / SKIP_POT +1
│   ├── SkipJarConfigPort.java                # get / setFloor / setDwell / setGate
│   ├── SkipContributionPort.java             # count(guild,week) / hasContributed(guild,week,member) / record(...)
│   ├── EarnerStatsPort.java                  # distinctEarnerCount(guild, since) / isEarner(guild, member, since)
│   ├── JarClosedException.java               # dwell not elapsed
│   ├── NoCurrentGameException.java           # nothing to skip
│   ├── AlreadyContributedException.java      # once-per-run violated
│   └── NotEligibleToContributeException.java # gate on + member is not an earner
├── domain/coin/LedgerAccount.java            # MODIFIED: add SKIP_POT (additive enum value)
├── domain/coin/AdjustmentType.java           # MODIFIED: add SKIP_JAR (additive enum value)
├── domain/coin/PostingLine.java              # MODIFIED: add skipPot(signedAmount) factory
├── application/skipjar/                       # @Transactional services (request rec → result rec)
│   ├── ContributeToSkipJarService.java        # the vote: queue-lock, dwell, gate, once, debit, evaluate, skip
│   ├── ViewSkipJarService.java                # lock-free status: count, threshold, remaining / not-open / no-game
│   ├── ConfigureSkipJarService.java           # floor / dwell / gate (moderator-role auth, mirrors 005)
│   └── *Request.java / *Result.java
├── application/queue/AdvanceRotationService.java  # MODIFIED: extract single-pop body; add skip(guildId, now)
├── infrastructure/persistence/skipjar/        # JPA adapters implementing the domain ports
│   ├── GuildSkipJarConfigEntity.java + GuildSkipJarConfigJpaRepository.java
│   ├── SkipContributionEntity.java + SkipContributionId.java + SkipContributionJpaRepository.java
│   ├── JpaSkipJarConfigAdapter.java
│   ├── JpaSkipContributionAdapter.java
│   └── JpaEarnerStatsAdapter.java             # native count(distinct member) over PARTICIPATION since `since`
└── discord/command/
    ├── SkipCommand.java                       # NEW thin handler: /skip contribute | status
    └── SkipConfigCommand.java                 # NEW thin handler: /skip-config floor | dwell | gate (role-gated)
    # i18n: reuse the existing coin-messages bundle — no separate SkipMessages accessor (see tasks T002)

src/main/resources/
├── db/migration/V5__skip_jar.sql              # NEW (V1–V4 untouched)
├── application.yml                            # MODIFIED: skipjar:* defaults (floor, dwell)
└── messages/coin-messages.properties          # MODIFIED: SKIP_JAR history label + skip replies

src/test/java/bot/
├── domain/skipjar/                            # pure unit tests (threshold majority/floor; ledger plan balance)
├── application/skipjar/                        # service tests (Mockito ports): dwell, gate, once, no-game, trigger
├── application/queue/                          # AdvanceRotationService.skip() one-step semantics test
└── infrastructure/persistence/skipjar/         # Testcontainers: once-per-run PK, earner-set count, balanced
                                                #   non-refundable posting, week-scoped reset, concurrent no-double-advance
```

**Structure Decision**: Reuse the existing single-project hexagonal layout. The skip jar mirrors the
coin/queue/participation package shape and **shares the one coin ledger** (a new `SKIP_POT` sink, no
second economy). It reads the queue feature's `RotationState` (current run + dwell baseline), invokes
the queue's deterministic advance for the early skip, and reads the participation feature's ledger
movements for the earner set — the cross-feature couplings, all noted below.

## Complexity Tracking

> No Core-Principle deviation requires justification — this table is intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| *(none)* | | |

> **Cross-feature reads/calls** (not deviations, noted for review):
> 1. `ContributeToSkipJarService` and `ViewSkipJarService` read the queue feature's `RotationState`
>    (`RotationStatePort.get`) for the current slot (none ⇒ no game), the `currentWeekNumber` (the run
>    key that scopes contributions), and `lastPopAt` (the dwell baseline). A single existing port read.
> 2. The threshold/eligibility earner set is read from the **existing coin ledger** — distinct members
>    with a `PARTICIPATION` movement crediting ≥ 1 coin since `lastPopAt` (`EarnerStatsPort`). No new
>    attribution column is added to `coin_movement`; the run boundary is the timestamp.
> 3. The early skip calls `AdvanceRotationService.skip(guildId, now)`, which runs **one** pop reusing
>    the *same* deterministic body as the weekly loop — the queue feature still owns advance behavior
>    (incl. empty-queue handling). The skip differs only by setting the new clock baseline to `now`.

---

## Phase 0 — Research

See [research.md](./research.md). All decisions resolved (identifying the current run & its
became-current instant without a new column; sizing the earner set from existing `PARTICIPATION`
movements at the run boundary; the per-guild queue advisory lock as the single serialization point for
no-double-advance; the dedicated `SKIP_POT` account vs reusing the queue `POT`; using the Discord
interaction id as the contribution idempotency key; reusing `AdvanceRotationService`'s pop body for the
early skip and what the clock baseline becomes; dwell storage as `bigint` seconds → `Duration`;
moderator-role authorization reuse).

## Phase 1 — Design & Contracts

- [data-model.md](./data-model.md) — 2 new tables, the additive V5 `SKIP_POT`/`SKIP_JAR` CHECK
  changes, entities, the run-scoping key, indexes, and invariants (once-per-run, non-refundable,
  week-scoped reset, no double-advance, per-server isolation).
- [contracts/slash-commands.md](./contracts/slash-commands.md) — `/skip` (`contribute`, `status`) and
  `/skip-config` (`floor`, `dwell`, `gate`) with authorization.
- [contracts/application-services.md](./contracts/application-services.md) — request/result records and
  the contribution algorithm for `ContributeToSkipJarService`, plus `ViewSkipJarService` and
  `ConfigureSkipJarService`.
- [contracts/ledger-and-rotation.md](./contracts/ledger-and-rotation.md) — the `SKIP_JAR` balanced
  posting, the earner-set query, the `AdvanceRotationService.skip` early-advance contract, and the new
  domain ports.
- [quickstart.md](./quickstart.md) — end-to-end validation scenarios mapped to US1–US4.

**Post-design Constitution re-check**: PASS — the design keeps the domain framework-free, routes the
contribution through the one append-only ledger as a balanced non-refundable spend, serializes the
entire vote-and-advance under the existing per-guild queue advisory lock (so concurrent
threshold-meeting contributions yield exactly one pop), reuses the queue's deterministic advance
unchanged, and confines all JPA/JDA to infrastructure behind ports. No new violations introduced; no
new dependency added.

## Phase 2 — Next step

`/speckit-tasks` will derive the dependency-ordered task list, **sliced by user-story priority**
(MVP = US1 + US2 first; then US3, then US4). This command stops here.
