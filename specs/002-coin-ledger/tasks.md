---
description: "Task list for Coin Economy & Append-Only Audit Trail implementation"
---

# Tasks: Coin Economy & Append-Only Audit Trail

**Input**: Design documents from `/specs/002-coin-ledger/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: INCLUDED. The constitution (Principle VI — Real-Postgres Testing Discipline; Principle
III — Append-Only, Double-Entry Coin Ledger) and the plan require JUnit 5 / Mockito / AssertJ /
Testcontainers tests, and CI (`./mvnw verify`) gates every change. The ledger's tamper-evidence,
atomicity, and idempotency guarantees are only credible with real-Postgres tests, so test tasks
are first-class, not optional.

**Organization**: Tasks are grouped by user story. The shared economy core (schema, error model,
domain primitives, per-server config) lives in Setup + Foundational; each story phase is an
independently testable increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US3, mapping to the spec's prioritized user stories
- All paths are repository-relative; base Java package is `bot` (per CLAUDE.md)

## Path Conventions

- Main: `src/main/java/bot/...`, resources: `src/main/resources/...`
- Tests: `src/test/java/bot/...`
- Reuses `src/test/java/bot/support/AbstractPostgresIntegrationTest.java` from `001`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Package skeleton and the i18n error-message wiring the domain error model needs.

- [X] T001 [P] Create the coin package skeleton under `src/main/java/bot/`: `domain/coin`,
  `application/coin`, `infrastructure/persistence/coin` (empty `package-info.java` placeholders are
  acceptable)
- [X] T002 [P] Add the i18n bundle `src/main/resources/messages/coin-messages.properties` with
  English defaults for every `DomainException` key and command reply
  (`coin.error.overdraw`, `coin.error.non-positive`, `coin.error.not-authorized`,
  `coin.error.role-not-configured`, plus success/duplicate templates), and in
  `src/main/resources/application.yml` set `spring.messages.basename` so the keys resolve via the
  Spring `MessageSource` and add `coin.history.default-limit: 10` (the single source of truth for
  the recent-history size, consumed by T035)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The economy substrate every story builds on — the immutable ledger schema with its
tamper-evidence triggers, the typed domain error model, the framework-free domain primitives and
ports, and the per-server configuration slice (a configured moderator role + cap are prerequisites
for any adjustment per FR-007/FR-011a and US1's fail-closed behavior).

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 Create the immutable Flyway migration
  `src/main/resources/db/migration/V2__coin_ledger.sql` per `data-model.md` and
  `contracts/ledger-invariants.md`: tables `guild_coin_config`, `coin_movement`,
  `coin_ledger_entry` with all CHECKs and the `coin_movement.interaction_id` UNIQUE; indexes
  `coin_ledger_entry (guild_id, member_id)` and `coin_movement (guild_id, member_id, id DESC)`; and
  the triggers — `coin_forbid_mutation()` `BEFORE UPDATE OR DELETE` on both ledger tables (I1
  append-only), a `DEFERRABLE INITIALLY DEFERRED` constraint trigger enforcing per-movement
  `SUM(amount)=0` (I2 balanced), and a deferred constraint trigger enforcing affected MEMBER
  `SUM(amount) >= 0` (I3 non-negative). `V1` is untouched
- [X] T004 [P] Create the base `src/main/java/bot/domain/DomainException.java` (abstract;
  immutable `messageKey` + `Object... args`) per the CLAUDE.md error model — pure Java
- [X] T005 [P] Create the typed exceptions in `src/main/java/bot/domain/coin/`:
  `OverdrawException`, `NonPositiveAmountException`, `ModeratorNotAuthorizedException`,
  `ModeratorRoleNotConfiguredException`, each extending `DomainException` with its i18n key
  (depends on T004)
- [X] T006 [P] Create the value object `src/main/java/bot/domain/coin/CoinAmount.java` (record over
  `int value`; `of(int)` rejects negatives, `positive(int)` additionally rejects 0 →
  `NonPositiveAmountException`; `plus`/`minus`/`min`) — pure Java (depends on T005)
- [X] T007 [P] Create the enums `src/main/java/bot/domain/coin/AdjustmentType.java` (`GRANT`,
  `DEDUCTION`) and `LedgerAccount.java` (`MEMBER`, `TREASURY`, `FORFEIT`) — pure Java
- [X] T008 [P] Create the records `src/main/java/bot/domain/coin/PostingLine.java`
  (`LedgerAccount account`, `Long memberId`, `int signedAmount`) and `PostingPlan.java`
  (`AdjustmentType type`, `int requested`, `int credited`, `int forfeited`, `List<PostingLine>
  lines`) — pure Java (depends on T007)
- [X] T009 [P] Create the value object `src/main/java/bot/domain/coin/GuildCoinConfig.java`
  (`long guildId`, `Long moderatorRoleId`, `int cap`) — pure Java
- [X] T010 [P] Create the outbound port `src/main/java/bot/domain/coin/CoinLedgerPort.java`
  (`lockAccount`, `currentBalance`, `findByInteractionId`, `append`, `recentHistory`) plus the
  domain carriers `NewMovement`/`MovementRecord` it returns/accepts — pure Java (depends on T007,
  T008)
- [X] T011 [P] Create the outbound port `src/main/java/bot/domain/coin/GuildCoinConfigPort.java`
  (`GuildCoinConfig get(long)`, `GuildCoinConfig upsert(long, Long, Integer)`) — pure Java
  (depends on T009)
- [X] T012 Create the JPA entity
  `src/main/java/bot/infrastructure/persistence/coin/GuildCoinConfigEntity.java` mapping
  `guild_coin_config` (`guild_id` PK, `moderator_role_id` nullable, `coin_cap`, `updated_at` as
  `Instant`) (depends on T003)
- [X] T013 Create `src/main/java/bot/infrastructure/persistence/coin/GuildCoinConfigJpaRepository.java`
  (Spring Data) (depends on T012)
- [X] T014 Create `src/main/java/bot/infrastructure/persistence/coin/JpaGuildCoinConfigAdapter.java`
  implementing `GuildCoinConfigPort`: `get` returns a default (`cap = 12`, `role = null`) for an
  absent guild; `upsert` leaves a `null` argument unchanged (depends on T011, T013)
- [X] T015 [P] Integration test
  `src/test/java/bot/infrastructure/persistence/coin/JpaGuildCoinConfigAdapterTest.java` (extends
  the Testcontainers base): default cap 12 for an unknown guild, full upsert, and partial upsert
  (role-only / cap-only leaves the other unchanged) (depends on T014)
- [X] T016 [P] Create the records
  `src/main/java/bot/application/coin/ConfigureCoinsRequest.java` and `CoinConfigResult.java`
  per `contracts/application-services.md`
- [X] T017 Create `src/main/java/bot/application/coin/CoinConfigService.java` (`@Service`,
  `@Transactional`): require `actorIsAdmin` (else `ModeratorNotAuthorizedException`), validate
  `cap >= 0` when present, delegate to `GuildCoinConfigPort.upsert` (depends on T014, T016)
- [X] T018 [P] Unit test `src/test/java/bot/application/coin/CoinConfigServiceTest.java` (Mockito
  port): admin required, partial update semantics, cap validation (depends on T017)
- [X] T019 Create the thin handler
  `src/main/java/bot/discord/command/CoinsConfigCommand.java` implementing `SlashCommandHandler`
  (`/coins-config`, `default_member_permissions = ADMINISTRATOR`): `deferReply(true)` first, parse
  `role`/`cap` options + caller admin flag, require at least one option, delegate to
  `CoinConfigService`, render the effective config via the `MessageSource` (depends on T017) per
  `contracts/slash-commands.md`. No change to `InteractionRouter`/`SlashCommandRegistrar` (beans
  auto-register)

**Checkpoint**: Schema + triggers applied; error model and domain primitives exist; a server can
configure its moderator role and cap. User stories can now proceed.

---

## Phase 3: User Story 1 — Moderator grants or deducts coins (Priority: P1) 🎯 MVP

**Goal**: A configured moderator can grant or deduct a member's coins; each adjustment is applied
atomically, capped/forfeited correctly, recorded as a balanced append-only movement attributed to
the moderator, and idempotent on the interaction id.

**Independent Test**: With a moderator role configured, grant then deduct coins for a member and
confirm the derived balance equals the net, two immutable attributed records exist, an overdraw or
duplicate changes nothing, and over-cap coins are forfeited.

### Tests for User Story 1 ⚠️ (write first, ensure they fail before implementation)

- [X] T020 [P] [US1] Unit test `src/test/java/bot/domain/coin/CoinLedgerPolicyTest.java` (no DB):
  `planGrant` under cap, partial cap overflow (credit up to cap, forfeit remainder), at/over cap
  (credited 0, all forfeited); `planDeduction` normal, to exactly zero, and overdraw →
  `OverdrawException`; every returned `PostingPlan.lines` sums to zero and never contains two
  `MEMBER` lines
- [X] T021 [P] [US1] Integration test
  `src/test/java/bot/infrastructure/persistence/coin/JpaCoinLedgerAdapterTest.java` (Testcontainers
  base): `append` writes movement + balanced entries; `currentBalance` equals an independent `SUM`
  (SC-003); `recentHistory` is newest-first and bounded; `findByInteractionId` round-trips;
  `lockAccount` runs within the test transaction; and **per-server isolation** (FR-023, invariant
  I7) — the same `member_id` adjusted under two different `guild_id`s keeps independent balances
  and histories, and `findByInteractionId` is unaffected across guilds (depends on test against
  T028)
- [X] T022 [P] [US1] Integration test
  `src/test/java/bot/infrastructure/persistence/coin/CoinLedgerTriggersTest.java`: `UPDATE`/`DELETE`
  on `coin_movement` and `coin_ledger_entry` are rejected (I1); an unbalanced movement is rejected
  at commit (I2); a movement that would drive a MEMBER balance negative is rejected (I3)
- [X] T023 [P] [US1] Integration test
  `src/test/java/bot/infrastructure/persistence/coin/CoinIdempotencyConcurrencyTest.java`:
  re-applying the same `interactionId` (sequentially and concurrently) yields exactly one movement
  (I4, SC-005); concurrent grants/deducts on one member never produce a negative or over-cap
  balance and the sums reconcile (I5)
- [X] T024 [P] [US1] Unit test `src/test/java/bot/application/coin/AdjustCoinsServiceTest.java`
  (Mockito ports): authorization matrix (role unset → `ModeratorRoleNotConfiguredException`;
  caller lacks role and not admin → `ModeratorNotAuthorizedException`; role-holder and admin both
  allowed); `amount <= 0` → `NonPositiveAmountException`; duplicate interaction id → `DUPLICATE`
  result without a write; grant/deduct result mapping (`newBalance`/`credited`/`forfeited`)

### Implementation for User Story 1

- [X] T025 [US1] Create the pure domain service
  `src/main/java/bot/domain/coin/CoinLedgerPolicy.java`: `planGrant(guildId, memberId,
  currentBalance, amount, cap)` and `planDeduction(guildId, memberId, currentBalance, amount)`
  building balanced `PostingPlan`s (TREASURY↔MEMBER, TREASURY↔FORFEIT), throwing `OverdrawException`
  on deduction overdraw — no I/O (depends on T006, T007, T008, T005) — makes T020 pass
- [X] T026 [P] [US1] Create the JPA entities
  `src/main/java/bot/infrastructure/persistence/coin/CoinMovementEntity.java` and
  `CoinLedgerEntryEntity.java` mapping `coin_movement` and `coin_ledger_entry` (timestamps as
  `Instant`) (depends on T003)
- [X] T027 [US1] Create `src/main/java/bot/infrastructure/persistence/coin/CoinMovementJpaRepository.java`
  (`findByInteractionId`; recent-by-member ordered `id DESC` with limit) and
  `CoinLedgerEntryJpaRepository.java` (`COALESCE(SUM(amount),0)` by guild+member; entry insert)
  (depends on T026)
- [X] T028 [US1] Create `src/main/java/bot/infrastructure/persistence/coin/JpaCoinLedgerAdapter.java`
  implementing `CoinLedgerPort` (no `@Transactional` — called inside the application transaction):
  `lockAccount` via native `SELECT pg_advisory_xact_lock(?)` on a hash of (guild, member);
  `currentBalance` via the sum query; `append` inserts the movement with `ON CONFLICT
  (interaction_id) DO NOTHING` (treating a conflict as duplicate) plus the plan's entries;
  `recentHistory` maps movements to domain carriers (depends on T010, T027) — makes T021/T022/T023
  pass
- [X] T029 [US1] Create the records `src/main/java/bot/application/coin/AdjustCoinsRequest.java` and
  `AdjustCoinsResult.java` (with the `Outcome` enum `APPLIED`/`DUPLICATE`) per
  `contracts/application-services.md`
- [X] T030 [US1] Create `src/main/java/bot/application/coin/AdjustCoinsService.java` (`@Service`,
  `@Transactional`): validate amount → load config → authorize → idempotency lookup → `lockAccount`
  → derive balance → `CoinLedgerPolicy` → `append` → map `AdjustCoinsResult`; rule violations throw
  typed `DomainException`s that roll back (depends on T025, T028, T014, T029) — makes T024 pass
- [X] T031 [US1] Create the thin handler
  `src/main/java/bot/discord/command/AdjustCoinsCommand.java` implementing `SlashCommandHandler`
  (`/coins-adjust` with `grant`/`deduct` subcommands, `default_member_permissions = MANAGE_SERVER`):
  `deferReply(true)` first, parse subcommand → `AdjustmentType`, target/amount/reason, caller role
  ids + admin flag, use `event.getInteraction().getIdLong()` as the idempotency key, delegate to
  `AdjustCoinsService`, render `APPLIED`/`DUPLICATE`/exception messages via the `MessageSource`
  (depends on T030) per `contracts/slash-commands.md`

**Checkpoint**: After `/coins-config` sets a role, `/coins-adjust` grant/deduct works end to end;
T020–T024 pass; manual verification per quickstart §1–§4.

---

## Phase 4: User Story 2 — Member checks balance and history (Priority: P2)

**Goal**: A member views their own derived balance, the server cap, and their most recent movements
newest-first.

**Independent Test**: Seed a member via moderator adjustments, then have that member view their
balance/history and confirm the balance equals the summed movements and the list is newest-first;
a member with no history sees 0 and an empty list.

- [X] T032 [P] [US2] Unit test `src/test/java/bot/application/coin/CoinQueryServiceTest.java`
  (Mockito ports): maps port balance + history + cap into `BalanceView`; empty member → balance 0
  and empty `recent`
- [X] T033 [US2] Create the records `src/main/java/bot/application/coin/ViewBalanceRequest.java` and
  `BalanceView.java` (with the nested `MovementSummary` record) per
  `contracts/application-services.md`
- [X] T034 [US2] Create `src/main/java/bot/application/coin/CoinQueryService.java` (`@Service`,
  `@Transactional(readOnly = true)`): `currentBalance` + `recentHistory` via `CoinLedgerPort` and
  `cap` via `GuildCoinConfigPort` → `BalanceView` (depends on T028, T014, T033) — makes T032 pass
- [X] T035 [US2] Create the thin handler `src/main/java/bot/discord/command/BalanceCommand.java`
  implementing `SlashCommandHandler` (`/balance`, open to all): `deferReply(true)` first (ephemeral
  — own balance only), read the history size from the `coin.history.default-limit` config property
  (default 10, added in T002 — bound via `@Value`/`@ConfigurationProperties`, not a hardcoded
  literal), delegate to `CoinQueryService` for the caller, render balance/cap/history via the
  `MessageSource` (depends on T034) per `contracts/slash-commands.md`

**Checkpoint**: `/balance` shows derived balance, cap, and recent history; US1 + US2 both work.

---

## Phase 5: User Story 3 — Coins are mine alone, non-transferable (Priority: P3)

**Goal**: No path moves coins from one member to another; the guarantee is locked in by a test and
verified across the command surface.

**Independent Test**: Confirm no command/action transfers coins between members and no posting plan
ever credits one member by debiting another.

- [X] T036 [P] [US3] Unit test `src/test/java/bot/domain/coin/NonTransferabilityTest.java`: assert
  that no `CoinLedgerPolicy` plan (grant or deduct, across cap/overdraw cases) ever contains two
  `MEMBER` `PostingLine`s — member balance changes only against TREASURY/FORFEIT (I8, FR-006,
  SC-006)
- [X] T037 [US3] Verify and document the guarantee: confirm the registered command surface is
  exactly `/balance`, `/coins-adjust`, `/coins-config` (none transfers/gifts/trades between
  members) and add an assertion in
  `src/test/java/bot/discord/command/CommandSurfaceTest.java` enumerating the `SlashCommandHandler`
  beans' names so a future transfer command can't be added silently (FR-006)

**Checkpoint**: Non-transferability is enforced by construction and guarded by tests.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T038 [P] Run `./mvnw spotless:apply` and commit formatting
- [X] T039 [P] Verify every `DomainException` renders a clear, secret-free, i18n-resolved reply
  (overdraw, non-positive, not-authorized, role-not-configured) and that all coin replies are
  ephemeral (spec FR-011/FR-022; constitution secret hygiene)
- [X] T040 Run the `quickstart.md` validation checklist end to end (§1–§7, SC-001..SC-007) and run
  `./mvnw -q verify`; fix any gaps and surface failures verbatim

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — BLOCKS all user stories (schema, error model,
  domain primitives, ports, per-server config)
- **US1 (Phase 3)**: depends on Foundational — the MVP write path
- **US2 (Phase 4)**: depends on Foundational + US1's ledger adapter (`JpaCoinLedgerAdapter`, T028)
  to read derived balances/history; seeds its independent test via the US1 write path
- **US3 (Phase 5)**: depends on the domain policy (T025) and the command surface (US1/US2 handlers)
- **Polish (Phase 6)**: depends on all desired stories being complete

### User Story Dependencies

- **US1 (P1)**: independent once Foundational is done — delivers the MVP
- **US2 (P2)**: reuses US1's `CoinLedgerPort` adapter for reads; no new persistence
- **US3 (P3)**: a guardrail over the policy + command surface; no new runtime behavior

### Within User Story 1

- Tests (T020–T024) written first and expected to fail → then implementation
- Domain policy (T025) and entities (T026) before repos (T027) before adapter (T028)
- Adapter + policy + config before service (T030); service before handler (T031)

### Parallel Opportunities

- Setup: T001, T002 in parallel
- Foundational: T004 then T005; T006/T007/T008/T009 in parallel; T010/T011 in parallel; the config
  slice T012→T013→T014→T015 is sequential, T016 parallel, then T017→T018, T019
- US1 tests T020–T024 in parallel; implementation T026 parallel with T025; then T027→T028,
  T029→T030→T031
- US2: T032 (test) parallel with T033 (records); then T034→T035
- US3: T036 parallel with T037
- Polish: T038, T039 in parallel

---

## Parallel Example: User Story 1

```text
# Tests first (different files, no deps on incomplete impl):
Task: T020 CoinLedgerPolicyTest (unit, no DB)
Task: T021 JpaCoinLedgerAdapterTest (integration, Testcontainers)
Task: T022 CoinLedgerTriggersTest (integration)
Task: T023 CoinIdempotencyConcurrencyTest (integration)
Task: T024 AdjustCoinsServiceTest (unit, Mockito)

# Then independent building blocks:
Task: T025 CoinLedgerPolicy (domain policy)
Task: T026 CoinMovementEntity + CoinLedgerEntryEntity (JPA entities)
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 → **STOP & VALIDATE**: configure a
   server, grant/deduct, prove balance/audit/overdraw/cap/idempotency per quickstart §1–§5. This is
   the calibration slice for the economy.

### Incremental Delivery

1. Setup + Foundational → schema, triggers, error model, config ready
2. US1 → moderator grant/deduct with append-only attributed ledger → MVP demo (SC-001..SC-005, SC-007)
3. US2 → members see derived balance + history (SC-003)
4. US3 → non-transferability locked in (SC-006)
5. Polish → message hygiene + full quickstart validation

---

## Notes

- [P] = different files, no dependency on an incomplete task
- [Story] labels map tasks to spec user stories for traceability
- Constitution gates to respect while implementing: domain (`bot.domain.coin`) stays
  framework-free; only `bot.application.coin` opens the single transaction and calls ports;
  handlers defer before any work and hold no business logic; the ledger is append-only and
  double-entry with balances **derived** (never stored); `V2` is immutable; the advisory lock is
  taken inside the application transaction; tests run on the host against Testcontainers Postgres —
  never inside the app container
- Run `./mvnw -q verify` after each task and surface failures verbatim (per CLAUDE.md)
- Commit after each task or logical group
