# Implementation Plan: Participation Earning

**Branch**: `005-participation-earning` | **Date**: 2026-06-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/005-participation-earning/spec.md`

## Summary

Members **earn coins by playing the current week's game** while connected to a server-designated
voice channel. Coins are minted in flat **drops** — two per-server integers `minutes-per-drop`
(default 60) and `coins-per-drop` (default 1) — so qualifying time accrues toward the next drop and
whole coins are credited as each threshold is crossed, respecting the existing balance cap (over-cap
coins forfeited). Admins (the configured **economy/moderator role**) designate participation voice
channels (add / reset-to-none), set the rate, and toggle a **free-first-proposal** bootstrap setting
that waives the propose cost while there is no current game and the queue is empty.

Technical approach: extend the existing hexagonal codebase. A new pure-domain package
`bot.domain.participation` holds the accrual arithmetic (clamp elapsed, bank time, mint whole drops)
and outbound ports; `bot.application.participation` holds the `@Transactional` services. Earnings
**reuse feature 002's append-only double-entry ledger** via the existing `CoinLedgerPort` and
`CoinLedgerPolicy.planGrant` (TREASURY→MEMBER credit + TREASURY→FORFEIT over-cap), adding one
additive movement type `PARTICIPATION`. A background **`@Scheduled` sweep** samples, each tick, who
is currently in a designated voice channel **and** playing the current game (read from JDA's
in-memory cache — no REST), and accrues the elapsed interval per member under the existing per-account
advisory lock; the banked-time decrement is the at-most-once guard, and downtime simply produces no
ticks (so no retroactive credit). The **free-first-proposal** waiver hooks into
`ProposeGameService`'s existing bootstrap (instant-pop) branch. A new Flyway migration **V4** is
purely additive (V1–V3 untouched). `JdaConfig` switches member retention from `NONE` to **`VOICE`**
and adds the non-privileged `GUILD_VOICE_STATES` intent so voice-connected members (and their
activities) are observable. One thin `/participation-config` handler defers within 2.5 s and delegates.

MVP = **US2 (designate channels)** + **US1 (earn)** together (earning is inert without a designated
channel); **US3 (history label)** and **US4 (free-first-proposal)** layer on.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.3.5 (Data JPA, Validation, Context/**Scheduling** — already on
the classpath and enabled by feature 004's `SchedulingConfig`), JDA 5.2.1, Flyway (core +
postgresql), PostgreSQL 17 driver. **No new Maven coordinate is required** — the credit path reuses
the existing ledger, observation reuses JDA's gateway cache (voice + activities), and scheduling is
already enabled. (Recorded per Constitution "Development Workflow": this feature adds **no**
dependency to the build.)

**Storage**: PostgreSQL 17 only (dev/prod in Docker; tests via Testcontainers). New tables + one
sequence in **V4**; reuses `coin_movement` / `coin_ledger_entry` for the participation credit.

**Testing**: JUnit 5 + Mockito + AssertJ + Testcontainers (real throwaway Postgres, on the host, no
secrets). The accrual arithmetic is unit-tested as a pure domain policy without a DB; the sweep's JDA
reading is isolated behind a collaborator and tested with a stub (no gateway, no token); idempotency,
cap forfeiture, and the negative-id namespacing are integration-tested on real Postgres.

**Target Platform**: Linux container (single multi-stage image), run via Docker Compose launch scripts.

**Project Type**: Single Spring Boot service (Discord bot). Existing four-layer hexagonal layout.

**Performance Goals**: Discord interaction acknowledged < 2.5 s (defer-first) for `/participation-config`.
The sweep reads only JDA's in-memory cache (no REST/network), then performs one short transaction per
**currently-qualifying** member (bounded by people actively in designated voice channels playing the
game — small). Sweep cadence is configurable (`participation.sweep.tick`, default `PT1M`).

**Constraints**: No new database engine; no dotenv; secrets only as `${...}` env vars via Compose;
V1–V3 migrations immutable (changes ship as V4); the domain stays framework-free; handlers defer
before any work; tests require no secrets and never run in a container.

**Scale/Scope**: Multi-guild; per-server channels/rate/free-toggle/accrual isolation. 1 slash command
(4 subcommands: `channel-add`, `channel-reset`, `rate`, `free-proposal`), 1 background sweep,
3 application services (AccrueParticipation, ConfigureParticipation, + a free-proposal change to the
existing `ProposeGameService`), 3 new tables + 1 sequence, 1 migration (V4), 1 `JdaConfig` change,
1 history-renderer extension (PARTICIPATION label).

## Constitution Check

*GATE: evaluated before Phase 0 and re-affirmed after Phase 1 design (below).*

| # | Principle | Compliance in this plan |
|---|-----------|--------------------------|
| I | Postgres-only | New tables use `bigint`/`boolean`/`timestamptz` with `CHECK` (`minutes_per_drop > 0`, `coins_per_drop > 0`, `banked_seconds >= 0`), composite PKs, `ON CONFLICT` upsert for accrual, and a dedicated `sequence` for negative-namespaced synthetic ids. No H2/in-memory, no portability shims. ✓ |
| II | Hexagonal / domain purity | `bot.domain.participation` is pure Java (accrual policy + value objects + ports). `bot.application.participation` services are the only place opening transactions / calling ports. JDA voice/presence reads, the scheduler, and JPA confined to `bot.infrastructure`. ✓ |
| III | Append-only double-entry ledger | Each drop posts a **balanced** grant into the existing `coin_ledger_entry` (TREASURY→MEMBER, plus TREASURY→FORFEIT for the over-cap remainder) via the existing `CoinLedgerPort` + `CoinLedgerPolicy.planGrant`. Posted rows are never mutated; balances stay derived. The only ledger schema change is the **additive** `PARTICIPATION` movement type. ✓ |
| IV | Atomic affordability/at-most-once | The per-member drop mint runs under the existing per-account `pg_advisory_xact_lock` (reused `CoinLedgerPort.lockAccount`); the at-most-once guard is the transactional **banked-seconds decrement** (consumed seconds cannot be re-credited) plus `last_sampled_at` advancement; cap truncation is computed in one transaction. The free-proposal waiver runs inside `ProposeGameService`'s existing queue→account lock order. ✓ |
| V | Thin, fast handlers | `/participation-config` defers first, then delegates to a service (request record in, result record out). The sweep is a background `@Scheduled` job (not an interaction — the 2.5 s rule is N/A) that reads JDA's in-memory cache off the gateway threads and never blocks on REST. ✓ |
| VI | Real-Postgres testing | Accrual/idempotency/cap/negative-id tests use Testcontainers Postgres; the pure `ParticipationAccrualPolicy` needs no DB; the sweep's JDA reading is stubbed (no gateway/token). `./mvnw verify` stays green and secret-free. ✓ |
| VII | Immutable migrations / config / secrets | New **V4** migration; V1–V3 untouched (V4 only ADDs tables/sequence and swaps the `coin_movement` type CHECK additively to include `PARTICIPATION`). New config under `participation:` in `application.yml`; no secrets introduced, no dotenv. ✓ |

**Result**: PASS. Two items are recorded in **Complexity Tracking** — the broadened JDA member cache
(`NONE`→`VOICE`) plus the `GUILD_VOICE_STATES` intent, and the second background scheduler (the
time-sampled participation sweep) — because they expand the runtime footprint, even though neither
violates a principle.

## Project Structure

### Documentation (this feature)

```text
specs/005-participation-earning/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions & rationale
├── data-model.md        # Phase 1 — entities, V4 schema, invariants
├── quickstart.md        # Phase 1 — runnable validation guide
├── contracts/           # Phase 1 — interface contracts
│   ├── slash-commands.md          # /participation-config subcommands
│   ├── application-services.md    # request/result records, accrual algorithm, propose-waiver change
│   └── ledger-and-observation.md  # PARTICIPATION posting, negative-id namespacing, JDA cache/sweep, ports
└── tasks.md             # Phase 2 (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/java/bot/
├── domain/participation/                  # PURE — no Spring/JDA/JPA imports
│   ├── ParticipationRate.java             # (minutesPerDrop, coinsPerDrop); defaults 60/1; positive
│   ├── GuildParticipationConfig.java      # rate + freeFirstProposal; defaults(guildId)
│   ├── ParticipationAccrual.java          # (guildId, memberId, bankedSeconds, lastSampledAt)
│   ├── ParticipationAccrualPolicy.java    # clamp-elapsed, bank, drops-and-remainder, cap-aware mint plan
│   ├── ParticipationConfigPort.java       # get/setRate/setFreeFirstProposal
│   ├── DesignatedChannelPort.java         # add / resetAll / list / contains / guildsWithChannels
│   ├── ParticipationAccrualPort.java      # get / upsert / nextDropId (negative sequence)
│   └── CurrentGamePort.java               # Optional<GameIdentity> currentGameIdentity(guildId)
├── domain/coin/AdjustmentType.java        # MODIFIED: add PARTICIPATION (additive enum value)
├── application/participation/             # @Transactional services (request rec → result rec)
│   ├── AccrueParticipationService.java    # one member's tick: lock, accrue, mint drops, cap, persist
│   ├── ConfigureParticipationService.java # channels add/reset, rate, free-proposal (moderator-role auth)
│   └── *Request.java / *Result.java
├── application/queue/ProposeGameService.java   # MODIFIED: free-first-proposal waiver in bootstrap branch
├── infrastructure/persistence/participation/   # JPA adapters implementing the domain ports
│   ├── GuildParticipationConfigEntity.java  ParticipationVoiceChannelEntity.java
│   ├── ParticipationAccrualEntity.java + *Id.java
│   ├── *JpaRepository.java
│   └── Jpa{ParticipationConfig,DesignatedChannel,ParticipationAccrual,CurrentGame}Adapter.java
├── infrastructure/discord/
│   ├── JdaConfig.java                     # MODIFIED: MemberCachePolicy.VOICE + GUILD_VOICE_STATES intent
│   ├── GameActivities.java               # NEW: shared Activity→GameIdentity mapper (extracted from PresenceReader)
│   └── VoiceParticipantsReader.java      # NEW: qualifying member ids in a guild's designated channels
├── infrastructure/schedule/
│   └── ParticipationScheduler.java        # NEW: @Scheduled sweep + ApplicationReadyEvent priming
└── discord/command/
    ├── ParticipationConfigCommand.java    # NEW thin handler (moderator-role gated, in-service)
    ├── BalanceCommand.java               # MODIFIED: render PARTICIPATION as a credit line (US3)
    └── ParticipationMessages.java        # NEW i18n accessor (or reuse the coin-messages bundle)

src/main/resources/
├── db/migration/V4__participation.sql     # NEW (V1–V3 untouched)
├── application.yml                        # MODIFIED: participation:* config (sweep tick, max-gap)
└── messages/coin-messages.properties      # MODIFIED: participation history label + config replies

src/test/java/bot/
├── domain/participation/                  # pure unit tests (accrual: clamp, bank, drops, cap, remainder)
├── application/participation/             # service tests (Mockito ports) + propose-waiver test
└── infrastructure/persistence/participation/   # Testcontainers: accrual upsert, idempotency/no-double-credit,
                                                #   cap forfeiture via ledger, negative-id namespacing, channel set
```

**Structure Decision**: Reuse the existing single-project hexagonal layout. Participation mirrors the
coin/queue package shape and **shares the one coin ledger** (no second economy). It reads the
queue feature's current-game state and waives the queue propose cost through a single boolean port —
the only cross-feature couplings, both justified below.

## Complexity Tracking

> Neither item violates a Core Principle; both expand the runtime footprint and are recorded with the
> rejected simpler alternative.

| Deviation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| **JDA member cache `NONE`→`VOICE`** + the non-privileged **`GUILD_VOICE_STATES`** intent (presence/`GUILD_PRESENCES` + `CacheFlag.ACTIVITY` already enabled by 004) | Earning requires continuously knowing who is connected to a designated voice channel **and** what they are playing (FR-001/003/004). The sweep reads this from JDA's in-memory cache; voice-connected members must therefore be retained with their activities. | Feature 004's `MemberCachePolicy.NONE` retains nothing and only does an on-demand presence fetch at propose time — unworkable for continuously observing many members. Retaining **all** members (`ONLINE`/`ALL`) was rejected: memory grows with online membership. `VOICE` bounds retention to the (small) set currently in voice; `GUILD_VOICE_STATES` is non-privileged. This is the cache change feature 004's plan explicitly deferred to "a later spec." |
| **Second background scheduler — the time-sampled participation sweep** (vs event-driven session tracking) | Qualifying time must be measured and credited continuously while the bot runs (FR-001/010), survive restarts, and never credit downtime (FR-023) or double-credit (FR-009). | Event-driven voice/presence session tracking would have to reconstruct in-flight sessions across restarts and reconcile missed events. The sweep samples current state each tick: downtime fires no ticks (no retroactive credit, FR-023), there is no event replay (FR-009), and persisted `banked_seconds` carries the remainder indefinitely. Trade-off: crediting granularity = the sweep interval and ≤ one interval of over/under-credit at session edges — acceptable for a deliberately coarse, flat rate. The `RotationScheduler` already established the `@Scheduled` + startup pattern. |

> **Cross-feature reads** (not deviations, noted for review): `AccrueParticipationService` reads the
> queue feature's current designated game (`CurrentGamePort`, a one-row join over the rotation/queue
> tables) to decide what counts as "the current game"; `ProposeGameService` reads a single
> participation boolean (`ParticipationConfigPort.freeFirstProposalEnabled`) to waive the propose
> cost in the empty-queue bootstrap state. Both are minimal port reads in the application layer,
> consistent with how the queue already composes coin ports.

---

## Phase 0 — Research

See [research.md](./research.md). All NEEDS CLARIFICATION resolved (time-sampled sweep vs event
tracking; banked-fraction persistence & restart safety; clamp/max-gap for FR-023 and session
re-entry; negative-id namespacing for ledger idempotency without a Discord interaction id; reusing
`planGrant` for cap forfeiture; cap-pause-accrual semantics; JDA `VOICE` cache + activity reads for
voice members; moderator-role authorization reuse; the free-proposal hook point in `ProposeGameService`).

## Phase 1 — Design & Contracts

- [data-model.md](./data-model.md) — 3 new tables + 1 sequence, the additive V4 change to the coin
  movement type CHECK, entities, indexes, and invariants (banked-seconds, cap pause, no-double-credit).
- [contracts/slash-commands.md](./contracts/slash-commands.md) — `/participation-config` with the
  `channel-add`, `channel-reset`, `rate`, and `free-proposal` subcommands and their authorization.
- [contracts/application-services.md](./contracts/application-services.md) — request/result records
  and the accrual algorithm for `AccrueParticipationService` and `ConfigureParticipationService`, plus
  the precise change to `ProposeGameService` for the free-first-proposal waiver.
- [contracts/ledger-and-observation.md](./contracts/ledger-and-observation.md) — the `PARTICIPATION`
  double-entry posting, negative synthetic-id namespacing, the JDA cache/intent change, the sweep, and
  the new domain ports.
- [quickstart.md](./quickstart.md) — end-to-end validation scenarios mapped to US1–US4.

**Post-design Constitution re-check**: PASS — the design keeps the domain framework-free, routes the
participation credit through the one append-only ledger (with cap forfeiture), resolves each mint
atomically under the existing per-account advisory lock with the banked-seconds decrement as the
at-most-once guard, and confines all JDA/voice/presence/scheduling to infrastructure behind ports. No
new violations introduced.

## Phase 2 — Next step

`/speckit-tasks` will derive the dependency-ordered task list, **sliced by user-story priority**
(MVP = US2 + US1 first; then US3, then US4). This command stops here.
