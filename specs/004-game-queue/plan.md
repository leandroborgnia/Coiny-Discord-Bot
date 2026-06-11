# Implementation Plan: Game Queue & Weekly Rotation

**Branch**: `004-game-queue` | **Date**: 2026-06-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/004-game-queue/spec.md`

## Summary

Add a per-server **game queue** and an automatic **weekly rotation**. Members who are actively
playing a game spend coins to **propose** it (captured from their Discord Rich Presence), **bump**
it up one slot, **withdraw** it (with refund), and **upvote** other slots (social signal only).
A scheduled rolling-7-day rotation **pops** the top slot, designates it the week's game, records
the proposer's "wait N games" cooldown, and (optionally) announces it to a configured channel where
that single latest message is kept live with upvote counts.

Technical approach: extend the existing hexagonal codebase. A new pure-domain package
`bot.domain.queue` holds ordering / cooldown / rotation / game-identity policies and outbound ports;
`bot.application.queue` holds the `@Transactional` services (request record in, result record out);
`bot.infrastructure.persistence.queue` holds JPA adapters that lean on Postgres
(`pg_advisory_xact_lock`, `ON CONFLICT`, partial unique indexes, `jsonb`). Coin spends and refunds
**reuse feature 002's append-only double-entry ledger** (`coin_ledger_entry`), adding a per-server
`POT` account so member balances stay a single derived `SUM` — there is no second economy.
A new Flyway migration **V3** is purely additive (V2 untouched). Cover art resolves lazily at render
time through a chain (Rich-Presence asset → DB cache → IGDB → name-only) that **never blocks or fails
the propose path**. Thin JDA handlers and a new button router defer within 2.5 s and delegate.

MVP = **US1 (propose)** + **US2 (weekly rotation)**; US3 (view), US4 (bump), US5 (upvote) layer on.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.3.5 (Data JPA, Validation, Context/Scheduling), JDA 5.2.1,
Flyway (core + postgresql), PostgreSQL 17 driver. **New for this feature**: Spring `@EnableScheduling`
(already on classpath via spring-context — no new artifact); an IGDB cover-art client built on the
JDK `java.net.http.HttpClient` (no new artifact) for transport, with **Jackson** for JSON parsing.
**One new Maven coordinate is required**: `spring-boot-starter-json` (compile scope, version managed by
the Spring Boot BOM — no version pin, no web server). JDA pulls `jackson-databind` only at **runtime**
scope, so it is *not* on the compile classpath; the starter supplies the compile-scope binding the
IGDB resolver imports. Recorded here per Constitution (Development Workflow) before the build change in
T005. See [research.md](./research.md) §Dependencies.

**Storage**: PostgreSQL 17 only (dev/prod in Docker; tests via Testcontainers). New tables in V3;
reuses `coin_ledger_entry` for all coin movement.

**Testing**: JUnit 5 + Mockito + AssertJ + Testcontainers (real throwaway Postgres, on the host, no
secrets). Domain policies unit-tested without a DB; the IGDB client unit-tested against a stub
(no network, no credentials).

**Target Platform**: Linux container (single multi-stage image), run via Docker Compose launch scripts.

**Project Type**: Single Spring Boot service (Discord bot). Existing four-layer hexagonal layout.

**Performance Goals**: Discord interaction acknowledged < 2.5 s (defer-first). Art resolution off the
propose path; IGDB queried at most once per game identity (cached). Announcement edit is a single
async REST call per upvote.

**Constraints**: No new database engine; no dotenv; secrets only as `${...}` env vars via Compose;
V2 migration immutable; tests need no secrets and never run in a container; domain stays
framework-free.

**Scale/Scope**: Multi-guild; per-server queue/rotation/config/cooldown isolation. 5 slash commands
+ 1 button, 7 application services (Propose, Withdraw, Bump, Upvote, ViewQueue, ConfigureQueue,
AdvanceRotation), 7 new tables, 1 migration, 1 scheduler, 1 IGDB adapter.

## Constitution Check

*GATE: evaluated before Phase 0 and re-affirmed after Phase 1 design (below).*

| # | Principle | Compliance in this plan |
|---|-----------|--------------------------|
| I | Postgres-only | New tables use `bigserial`, partial unique indexes (one queued slot per member; unique position), `ON CONFLICT` idempotency, `pg_advisory_xact_lock`, `jsonb` snapshot. No H2/in-memory, no portability shims. ✓ |
| II | Hexagonal / domain purity | `bot.domain.queue` is pure Java (ordering, cooldown, rotation, identity policies + ports). `bot.application.queue` services are the only place opening transactions / calling ports. JDA + IGDB HTTP confined to `bot.infrastructure`. ✓ |
| III | Append-only double-entry ledger | Propose/bump **spend** and withdraw **refund** post **balanced** lines into the existing `coin_ledger_entry` (MEMBER ↔ POT), via the existing `CoinLedgerPort`. Posted rows never mutated; refund is a new reversing movement; balances stay derived. POT is a new account, added additively in V3. ✓ |
| IV | Atomic cooldown / affordability | Affordability + queue mutation resolve under a per-guild **queue** advisory lock + the per-member **account** advisory lock, with `ON CONFLICT`/state-guarded inserts. Ineligible/unaffordable/duplicate actions change nothing. Idempotency keyed by interaction id (propose/bump/withdraw), week number (advance), and (member, slot) (upvote). ✓ |
| V | Thin, fast handlers | Slash + button handlers defer/ack first, read the member's in-memory cached presence, delegate to a service, render. The upvote button acks without re-rendering the ephemeral message (counts stay a snapshot). ✓ |
| VI | Real-Postgres testing | Integration tests use Testcontainers Postgres; domain tests need no DB. IGDB client is stubbed (no network) and disabled without credentials, so `./mvnw verify` stays green and secret-free. ✓ |
| VII | Immutable migrations / config / secrets | New **V3** migration; V2 untouched (V3 only ADDs tables and ALTERs CHECK constraints additively). Config in `application.yml`; IGDB `client-id`/`client-secret` are `${...}` env vars injected by Compose, never committed; no dotenv. ✓ |

**Result**: PASS. Two items are recorded in **Complexity Tracking** — the privileged
`GUILD_PRESENCES` intent and the IGDB outbound HTTP integration — because they expand the runtime's
footprint and privacy surface, even though neither violates a principle.

## Project Structure

### Documentation (this feature)

```text
specs/004-game-queue/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions & rationale
├── data-model.md        # Phase 1 — entities, V3 schema, invariants
├── quickstart.md        # Phase 1 — runnable validation guide
├── contracts/           # Phase 1 — interface contracts
│   ├── slash-commands.md          # /queue-* commands + upvote button surface
│   ├── application-services.md    # request/result records & algorithms
│   └── ledger-and-art.md          # POT postings, refund, art-resolution chain, ports
└── tasks.md             # Phase 2 (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/java/bot/
├── domain/queue/                      # PURE — no Spring/JDA/JPA imports
│   ├── CapturedGame.java              # Rich-Presence snapshot value object
│   ├── GameIdentity.java              # application_id else normalized name
│   ├── QueueSlot.java  QueueView.java # ordering value objects
│   ├── QueueOrderingPolicy.java       # append-tail, bump-swap, shift-up-on-pop
│   ├── CooldownPolicy.java            # "wait N games": N at pop, decrement, eligibility
│   ├── RotationPolicy.java            # rolling-7-day advances-due from lastPopAt
│   ├── QueueLedgerPolicy.java         # builds balanced spend/refund PostingPlans (reuses bot.domain.coin)
│   ├── *Port.java                     # QueuePort, QueueConfigPort, UpvotePort,
│   │                                  #   RotationStatePort, CooldownPort, ArtCachePort,
│   │                                  #   ArtResolverPort (outbound IGDB), AnnouncementPort (outbound)
│   └── *Exception.java                # InsufficientCoinsException, NoGameActivityException,
│                                      #   NoQueuedGameException, NotEligibleException, NotAuthorizedException
│                                      #   (no AlreadyAtTop/NotSlotOwner — those are result Outcomes; see tasks T009)
├── domain/coin/LedgerAccount.java     # MODIFIED: add POT (additive enum value)
├── application/queue/                 # @Transactional services (request rec → result rec)
│   ├── ProposeGameService.java   BumpGameService.java   WithdrawGameService.java
│   ├── UpvoteService.java   ViewQueueService.java   ConfigureQueueService.java
│   ├── AdvanceRotationService.java    # advance + downtime catch-up
│   └── *Request.java / *Result.java
├── infrastructure/persistence/queue/  # JPA adapters implementing the domain ports
│   ├── *Entity.java  *JpaRepository.java
│   └── Jpa{Queue,QueueConfig,Upvote,RotationState,Cooldown,ArtCache}Adapter.java
├── infrastructure/art/IgdbArtResolver.java       # ArtResolverPort: HttpClient + Twitch OAuth
├── infrastructure/discord/                        # MODIFIED + new adapters
│   ├── JdaConfig.java                # MODIFIED: GUILD_PRESENCES + GUILD_MEMBERS, CacheFlag.ACTIVITY, NON-retaining cache
│   ├── PresenceReader.java           # NEW: on-demand retrieveMembersByIds(true, id) → CapturedGame
│   ├── ButtonInteractionRouter.java  # NEW: routes onButtonInteraction → ButtonHandler beans
│   └── JdaAnnouncementAdapter.java   # AnnouncementPort: post/edit the live message
├── infrastructure/schedule/
│   ├── SchedulingConfig.java         # @EnableScheduling
│   └── RotationScheduler.java        # @Scheduled tick + ApplicationReadyEvent catch-up
└── discord/command/                   # thin handlers + i18n
    ├── ProposeCommand.java  BumpCommand.java  WithdrawCommand.java
    ├── QueueViewCommand.java  QueueConfigCommand.java
    ├── UpvoteButtonHandler.java       # implements a new ButtonHandler interface
    └── QueueMessages.java

src/main/resources/
├── db/migration/V3__game_queue.sql    # NEW (V2 untouched)
├── application.yml                     # MODIFIED: queue:* config, spring.messages.basename list
└── messages/queue-messages.properties  # NEW i18n bundle

src/test/java/bot/
├── domain/queue/                       # pure unit tests (ordering, cooldown, rotation, identity)
├── application/queue/                  # service tests (Mockito ports)
├── infrastructure/persistence/queue/   # Testcontainers: idempotency, partial-unique, lock concurrency,
│                                       #   POT postings & refund balance, rotation catch-up, cooldown
└── infrastructure/art/IgdbArtResolverTest.java   # stubbed HttpClient, no network/secrets
```

**Structure Decision**: Reuse the existing single-project hexagonal layout (`bot.domain` →
`bot.application` → `bot.infrastructure` / `bot.discord.command`). The queue feature mirrors the
coin feature's package shape, and **shares** the coin ledger rather than introducing a parallel one.

## Complexity Tracking

> Neither item violates a Core Principle; both expand the runtime footprint beyond the prior minimal
> build and are recorded here with the rejected simpler alternative.

| Deviation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| **`GUILD_PRESENCES` privileged intent** + on-demand presence fetch (intent-explicit build with `GUILD_MEMBERS` + `CacheFlag.ACTIVITY`, but **`MemberCachePolicy.NONE`** — no roster retained; read via `retrieveMembersByIds(true, memberId)` at propose time) | A proposal's game is captured from the member's live Rich Presence (FR-026); the bot cannot read activities without this privileged intent. | Free-text game entry was rejected by the user (no game-ID catalog/autocomplete for bots). The intent is an all-or-nothing stream and presence cannot be REST-fetched, so reception is unavoidable — but **retention is not**: a non-retaining cache keeps memory flat at any scale, and the targeted on-demand fetch reads the activity only at propose time. A retaining `ONLINE`/`ALL` cache was rejected (memory grows with online membership); voice-gating (`MemberCachePolicy.VOICE`) was deferred to a later spec (it would restrict who may propose). Privacy is bounded: only the first PLAYING activity is read at command time. Must be enabled in the Discord dev portal. |
| **IGDB outbound HTTP** (Twitch OAuth client-credentials) behind `ArtResolverPort` | Cover art makes the view/announcement engaging (FR-027); Rich-Presence assets are usually absent for *other* members, so a reliable art source is needed. | Name-only rendering is the fallback but degrades the core UX. The integration is isolated in `bot.infrastructure.art`, **never on the propose path**, queried at most once per game (DB cache), best-effort (any failure → name-only), and **disabled when credentials are absent** so tests and credential-less dev runs are unaffected. Credentials are env-var secrets. |

---

## Phase 0 — Research

See [research.md](./research.md). All NEEDS CLARIFICATION resolved (presence-capture reality for
non-self users, ledger reuse strategy, advisory-lock ordering, rotation catch-up math, art-cache
keying, IGDB auth & secret handling, scheduling, button routing, i18n bundle merge).

## Phase 1 — Design & Contracts

- [data-model.md](./data-model.md) — 7 new tables, the additive V3 changes to the coin ledger
  (POT account + queue movement types), entities, indexes, and invariants.
- [contracts/slash-commands.md](./contracts/slash-commands.md) — `/queue-propose`, `/queue-bump`,
  `/queue-withdraw`, `/queue-view`, `/queue-config`, and the upvote **button** contract.
- [contracts/application-services.md](./contracts/application-services.md) — request/result records
  and algorithms for every service (US1–US5 + rotation + config).
- [contracts/ledger-and-art.md](./contracts/ledger-and-art.md) — POT double-entry postings, the
  withdraw refund, the cover-art resolution chain, and the new domain ports.
- [quickstart.md](./quickstart.md) — end-to-end validation scenarios mapped to the user stories.

**Post-design Constitution re-check**: PASS — the design keeps the domain framework-free, routes all
coin movement through the one append-only ledger, resolves every mutation atomically under advisory
locks with explicit idempotency keys, and keeps art/presence/IGDB strictly in infrastructure behind
ports. No new violations introduced.

## Phase 2 — Next step

`/speckit-tasks` will derive the dependency-ordered task list, **sliced by user-story priority**
(MVP = US1 + US2 first). This command stops here.
