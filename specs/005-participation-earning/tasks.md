---
description: "Task list for feature 005 — Participation Earning"
---

# Tasks: Participation Earning

**Input**: Design documents from `/specs/005-participation-earning/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (slash-commands, application-services, ledger-and-observation), quickstart.md

**Tests**: INCLUDED — the plan and quickstart explicitly name the test suites this feature adds
(`ParticipationAccrualPolicyTest`, `AccrueParticipationServiceTest`, persistence/Testcontainers suites,
`ConfigureParticipationServiceTest`, extended `ProposeGameServiceTest`). Write each test before (or
alongside) the code it covers and confirm it fails first.

**Organization**: Tasks are grouped by user story (priority order P1→P4). Foundational shared
scaffolding precedes the stories. The MVP is **US1 + US2 together** (earning is inert until a channel
is designated), but each story below is independently testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1, US2, US3, US4 (setup/foundational/polish carry no story label)
- Every task names an exact file path

## Path Conventions

Single Spring Boot service, existing four-layer hexagonal layout under `src/main/java/bot/` and
`src/test/java/bot/`. Migrations in `src/main/resources/db/migration/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project scaffolding — no behavior yet.

- [X] T001 [P] Add the `participation:` config block (`sweep.tick: PT1M`, `sweep.max-gap: PT2M`) to `src/main/resources/application.yml` per contracts/ledger-and-observation.md §F
- [X] T002 [P] Create the pure-domain package `bot.domain.participation` with `package-info.java` in `src/main/java/bot/domain/participation/package-info.java` (note: no Spring/JDA/JPA imports allowed here)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared schema, the additive ledger type, domain value objects + ports, and the
config/channel persistence used by BOTH US1 (sweep) and US2 (admin commands).

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 Create Flyway migration `src/main/resources/db/migration/V4__participation.sql` — tables `guild_participation_config`, `participation_voice_channel`, `participation_accrual`, the `participation_drop_seq` sequence, and the **additive** rewrite of `coin_movement_type_check` adding `'PARTICIPATION'` (V1–V3 untouched), per data-model.md
- [X] T004 [P] Add the additive `PARTICIPATION` enum value to `src/main/java/bot/domain/coin/AdjustmentType.java`
- [X] T005 [P] Create `ParticipationRate` record (`minutesPerDrop`, `coinsPerDrop`; positive-int validation; `defaults()` = 60/1) in `src/main/java/bot/domain/participation/ParticipationRate.java`
- [X] T006 [P] Create `GuildParticipationConfig` record (`guildId`, `ParticipationRate rate`, `boolean freeFirstProposal`; `defaults(guildId)` = 60/1/false) in `src/main/java/bot/domain/participation/GuildParticipationConfig.java`
- [X] T007 [P] Create `ParticipationAccrual` record (`guildId`, `memberId`, `bankedSeconds`, `Instant lastSampledAt`) in `src/main/java/bot/domain/participation/ParticipationAccrual.java`
- [X] T008 [P] Define `ParticipationConfigPort` (`get`, `freeFirstProposalEnabled`, `setRate`, `setFreeFirstProposal`) in `src/main/java/bot/domain/participation/ParticipationConfigPort.java`
- [X] T009 [P] Define `DesignatedChannelPort` (`add`, `resetAll`, `list`, `contains`, `guildsWithChannels`) in `src/main/java/bot/domain/participation/DesignatedChannelPort.java`
- [X] T010 [P] Define `ParticipationAccrualPort` (`get`, `upsert`, `nextDropId`) in `src/main/java/bot/domain/participation/ParticipationAccrualPort.java`
- [X] T011 [P] Define `CurrentGamePort` (`Optional<GameIdentity> currentGameIdentity(guildId)`) in `src/main/java/bot/domain/participation/CurrentGamePort.java`
- [X] T012 [P] Create `GuildParticipationConfigEntity` + `GuildParticipationConfigJpaRepository` in `src/main/java/bot/infrastructure/persistence/participation/`
- [X] T013 [P] Create `ParticipationVoiceChannelEntity` + `ParticipationVoiceChannelId` + `ParticipationVoiceChannelJpaRepository` in `src/main/java/bot/infrastructure/persistence/participation/`
- [X] T014 Implement `JpaParticipationConfigAdapter implements ParticipationConfigPort` (absent row ⇒ defaults; upsert for setRate/setFreeFirstProposal) in `src/main/java/bot/infrastructure/persistence/participation/JpaParticipationConfigAdapter.java` (depends on T006, T008, T012)
- [X] T015 Implement `JpaDesignatedChannelAdapter implements DesignatedChannelPort` (`ON CONFLICT DO NOTHING` add; delete-all reset; `guildsWithChannels` distinct query) in `src/main/java/bot/infrastructure/persistence/participation/JpaDesignatedChannelAdapter.java` (depends on T009, T013)

**Checkpoint**: Schema, ledger type, domain types/ports, and shared config/channel persistence ready —
user stories can now proceed.

---

## Phase 3: User Story 1 - Earn coins by playing the current week's game (Priority: P1) 🎯 MVP

**Goal**: A member playing the current week's game in a designated voice channel accrues qualifying
time and is credited whole drops at the configured flat rate (cap-respected, no double-credit, no
retroactive credit), driven by the background sweep.

**Independent Test**: With a designated channel and a current week's game set, a member connected to
that channel playing that game earns coins at the configured rate; a wrong game, a non-designated
channel, or bot downtime earns nothing; the same span is never credited twice (quickstart US1).

### Tests for User Story 1 ⚠️ (write first, confirm failing)

- [X] T016 [P] [US1] `ParticipationAccrualPolicyTest` (pure, no DB): `elapsedToCredit` returns 0 for null `lastSampledAt` and for gaps `> maxGap`, the real delta otherwise; `dropsReady` splits banked seconds into whole drops + remainder — in `src/test/java/bot/domain/participation/ParticipationAccrualPolicyTest.java`
- [X] T017 [P] [US1] `AccrueParticipationServiceTest` (Mockito ports): cap-pause, fresh-session (gap > maxGap), single-drop, multi-drop, cap-crossing forfeiture (break after first forfeiting drop), banked-remainder persistence — in `src/test/java/bot/application/participation/AccrueParticipationServiceTest.java`
- [X] T018 [P] [US1] `JpaParticipationAccrualAdapterTest` (Testcontainers Postgres): accrual upsert round-trip, **no double-credit** under a replayed tick, negative-id namespacing (`interaction_id < 0`, collision-free), cap forfeiture posts balanced `MEMBER`+`FORFEIT` and leaves the derived balance at the cap — in `src/test/java/bot/infrastructure/persistence/participation/JpaParticipationAccrualAdapterTest.java`

### Implementation for User Story 1

- [X] T019 [P] [US1] Implement pure `ParticipationAccrualPolicy` (`elapsedToCredit`, `thresholdSeconds`, `dropsReady`) + `DropsAndRemainder` record in `src/main/java/bot/domain/participation/ParticipationAccrualPolicy.java` (time arithmetic only — coin/cap math stays in reused `CoinLedgerPolicy.planGrant`)
- [X] T020 [P] [US1] Create `AccrueParticipationRequest` and `AccrueParticipationResult` (with `Outcome` enum) records in `src/main/java/bot/application/participation/`
- [X] T021 [P] [US1] Create `ParticipationAccrualEntity` + `ParticipationAccrualId` + `ParticipationAccrualJpaRepository` in `src/main/java/bot/infrastructure/persistence/participation/`
- [X] T022 [US1] Implement `JpaParticipationAccrualAdapter implements ParticipationAccrualPort` — `get` (absent ⇒ `(0, null)`), `ON CONFLICT` upsert, `nextDropId()` = `-nextval('participation_drop_seq')` — in `src/main/java/bot/infrastructure/persistence/participation/JpaParticipationAccrualAdapter.java` (depends on T010, T021)
- [X] T023 [P] [US1] Implement `JpaCurrentGameAdapter implements CurrentGamePort` — join `queue_rotation_state.current_slot_id → queue_entry.game_identity` returning `Optional<GameIdentity>` — in `src/main/java/bot/infrastructure/persistence/participation/JpaCurrentGameAdapter.java` (depends on T011)
- [X] T024 [US1] Implement `AccrueParticipationService` (`@Transactional`): lockAccount → cap-pause check → `elapsedToCredit` → mint loop via `CoinLedgerPolicy.planGrant` + `CoinLedgerPort.append` with `PARTICIPATION` and `nextDropId()` → upsert banked/last-sampled, per the algorithm in contracts/application-services.md §1 — in `src/main/java/bot/application/participation/AccrueParticipationService.java` (depends on T019, T020, T022; reuses `CoinLedgerPort`, `CoinLedgerPolicy`, `GuildCoinConfigPort`)
- [X] T025 [US1] Extract the pure `Activity → GameIdentity` mapping out of `PresenceReader.toCapturedGame` into a shared `GameActivities` helper in `src/main/java/bot/infrastructure/discord/GameActivities.java`, and refactor `src/main/java/bot/infrastructure/discord/PresenceReader.java` to use it (so propose-capture and sweep-matching agree byte-for-byte)
- [X] T026 [US1] Implement `VoiceParticipantsReader.qualifyingMembers(guildId, channelIds, currentGame)` reading JDA in-memory cache (`guild.getVoiceChannelById(id).getMembers()`, first `PLAYING` activity via `GameActivities`, equality vs `currentGame`; empty on any miss) in `src/main/java/bot/infrastructure/discord/VoiceParticipantsReader.java` (depends on T025)
- [X] T027 [US1] Modify `src/main/java/bot/infrastructure/discord/JdaConfig.java`: add `GatewayIntent.GUILD_VOICE_STATES` and change `MemberCachePolicy.NONE` → `MemberCachePolicy.VOICE` (leave `GUILD_PRESENCES`, `GUILD_MEMBERS`, `CacheFlag.ACTIVITY` unchanged)
- [X] T028 [US1] Implement `ParticipationScheduler` — `@Component @ConditionalOnProperty(discord.enabled)`, `@Scheduled(fixedDelayString = "${participation.sweep.tick}")` + `ApplicationReadyEvent` primer; per tick iterate `guildsWithChannels()`, skip guilds with no current game, call `accrue` per qualifying member, catch/log per-member failures — in `src/main/java/bot/infrastructure/schedule/ParticipationScheduler.java` (depends on T024, T026; mirrors `RotationScheduler`)

**Checkpoint**: Earning works end-to-end given a designated channel + current game (combine with US2
for the configurable channel surface = MVP).

---

## Phase 4: User Story 2 - Admin configures the participation voice channels (Priority: P2)

**Goal**: A member with the configured economy/moderator role can add voice channels to the
designated set, reset the set to none, and set the per-server rate; non-authorized members cannot.

**Independent Test**: As a member with the moderator role, add a channel (registered), add a second
(both kept), reset (none remain), set the rate (echoed); a member without the role changes nothing
(quickstart US2).

### Tests for User Story 2 ⚠️ (write first, confirm failing)

- [X] T029 [P] [US2] `ConfigureParticipationServiceTest` (Mockito ports): `CHANNEL_ADD` (idempotent), `CHANNEL_RESET`, `RATE` set + validation, moderator-role authorization (no role configured ⇒ `ModeratorRoleNotConfiguredException`; lacking role and not Administrator ⇒ `ModeratorNotAuthorizedException`, set unchanged) — in `src/test/java/bot/application/participation/ConfigureParticipationServiceTest.java`

### Implementation for User Story 2

- [X] T030 [P] [US2] Create `ConfigureParticipationRequest` (with `Op` enum `CHANNEL_ADD|CHANNEL_RESET|RATE|FREE_PROPOSAL`) and `ParticipationConfigResult` records in `src/main/java/bot/application/participation/`
- [X] T031 [US2] Implement `ConfigureParticipationService` (`@Transactional`): authorize via `GuildCoinConfigPort` exactly like `AdjustCoinsService.authorize`, dispatch `CHANNEL_ADD`/`CHANNEL_RESET`/`RATE` (defensive `>= 1` re-validation), return re-read `ParticipationConfigResult` — in `src/main/java/bot/application/participation/ConfigureParticipationService.java` (depends on T030; reuses `GuildCoinConfigPort`, T008, T009)
- [X] T032 [US2] Implement thin `ParticipationConfigCommand` handler (guild-only, `deferReply(true)` first) wiring subcommands `channel-add` (`CHANNEL`, voice/stage), `channel-reset`, `rate` (two `INTEGER setMinValue(1)`) to `ConfigureParticipationRequest`; capture `actorRoleIds` + `actorIsAdmin`; render `DomainException` via messages — in `src/main/java/bot/discord/command/ParticipationConfigCommand.java`, and register it alongside the existing slash commands (mirror `AdjustCoinsCommand` registration)
- [X] T033 [P] [US2] Add reply i18n keys `participation.reply.channel-added`, `participation.reply.channel-reset`, `participation.reply.rate-set` to `src/main/resources/messages/coin-messages.properties` (reuse existing `coin.error.not-authorized` / `coin.error.role-not-configured`)

**Checkpoint**: US1 + US2 together = MVP — admins designate channels/rate and members earn.

---

## Phase 5: User Story 3 - Participation earnings are visible in coin history (Priority: P3)

**Goal**: A participation earning renders in `/balance` history as a clearly-labelled credit line,
distinct from moderator adjustments and queue spends, with the forfeiture suffix when cap-truncated.

**Independent Test**: After earning, `/balance` shows a participation **credit** line (not "deducted");
a cap-truncated drop shows credited + forfeited; a member who never earned shows no participation
lines and no error (quickstart US3).

### Tests for User Story 3 ⚠️ (write first, confirm failing)

- [X] T034 [P] [US3] Test that `BalanceCommand` renders an `AdjustmentType.PARTICIPATION` movement as a `+{credited}` credit line with the `[{n} forfeited]` suffix when applicable (not the deduction fall-through) — in `src/test/java/bot/discord/command/BalanceCommandTest.java` (extend if it exists)

### Implementation for User Story 3

- [X] T035 [P] [US3] Add i18n key `coin.reply.history.participation` to `src/main/resources/messages/coin-messages.properties`
- [X] T036 [US3] Add a `PARTICIPATION` branch to `BalanceCommand.historyLine` rendering it as a credit (with existing forfeiture suffix) in `src/main/java/bot/discord/command/BalanceCommand.java` (depends on T004, T035)

**Checkpoint**: Earnings are legible in history.

---

## Phase 6: User Story 4 - Free first proposal when the queue is empty (Priority: P4)

**Goal**: When the free-first-proposal toggle is ON and the server has no current game and an empty
queue, a proposal is accepted with the cost waived (no movement, no balance check); otherwise normal
cost applies. The toggle is moderator-role gated.

**Independent Test**: With the toggle ON and the empty bootstrap state, a 0-coin member proposes →
accepted, 0 charged, instant-pops; OFF → normal cost; current game exists or queue non-empty → normal
cost; non-authorized members cannot toggle (quickstart US4).

### Tests for User Story 4 ⚠️ (write first, confirm failing)

- [X] T037 [P] [US4] Extend `ProposeGameServiceTest` — waiver applies in the `currentSlotId == null && queue empty` state when the flag is on (no lock/spend/movement, `coinsSpent = 0`, balance unchanged, instant-pop); normal cost when flag off / current game exists / queue non-empty — in `src/test/java/bot/application/queue/ProposeGameServiceTest.java`
- [X] T038 [P] [US4] Extend `ConfigureParticipationServiceTest` with the `FREE_PROPOSAL` op (set on/off, moderator-role gated) — in `src/test/java/bot/application/participation/ConfigureParticipationServiceTest.java`

### Implementation for User Story 4

- [X] T039 [US4] Add the `FREE_PROPOSAL` dispatch branch to `ConfigureParticipationService` (`setFreeFirstProposal`) in `src/main/java/bot/application/participation/ConfigureParticipationService.java` (depends on T031)
- [X] T040 [US4] Add the `free-proposal` subcommand (`BOOLEAN enabled`, required) to `ParticipationConfigCommand` in `src/main/java/bot/discord/command/ParticipationConfigCommand.java` (depends on T032)
- [X] T041 [P] [US4] Add reply i18n key `participation.reply.free-proposal-set` to `src/main/resources/messages/coin-messages.properties`
- [X] T042 [US4] Modify `ProposeGameService` to inject `ParticipationConfigPort` and, before computing the spend, compute `bootstrap = rotation.currentSlotId == null && queued.isEmpty()` and `waive = bootstrap && freeFirstProposalEnabled(guildId)`; when waived skip lockAccount/planSpend/postSpend, append the instant-popped slot with `coinsSpent = 0`, leave balance unchanged; else run the existing path — in `src/main/java/bot/application/queue/ProposeGameService.java` (depends on T008, per contracts/application-services.md §4)

**Checkpoint**: All four stories functional and independently testable.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T043 Run `./mvnw -q verify` (Docker running) and fix any failures; confirm the suite stays green and secret-free
- [X] T044 [P] Run `./mvnw spotless:apply` and review formatting on all new/changed files
- [X] T045 Execute the `quickstart.md` manual smoke test (live JDA sweep with a token + intents) to validate US1 end-to-end against a real guild — verified live: playing War Thunder in a designated channel detected and credited one coin per minute as configured

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup; **blocks all user stories**.
- **US1 (Phase 3)**: depends on Foundational. The earning core; combine with US2 for the MVP.
- **US2 (Phase 4)**: depends on Foundational. Independent of US1 (different services/files).
- **US3 (Phase 5)**: depends on Foundational (needs `AdjustmentType.PARTICIPATION`, T004). Independent of US1/US2/US4 at the code level; meaningful once earnings exist.
- **US4 (Phase 6)**: depends on Foundational and on US2's `ConfigureParticipationService`/command (T031, T032) for the toggle plumbing; the `ProposeGameService` change (T042) only needs `ParticipationConfigPort` (T008).
- **Polish (Phase 7)**: after the desired stories are complete.

### Within Each User Story

- Tests are written first and must fail before implementation.
- Records/ports/policy before the services that use them; services before the command/scheduler that call them.

### Parallel Opportunities

- Setup: T001, T002 in parallel.
- Foundational: T004–T013 (`[P]`) in parallel after T003; adapters T014/T015 follow their entities/ports.
- US1 tests T016–T018 in parallel; impl T019/T020/T021/T023 in parallel, then T022→T024, T025→T026, T028 last.
- Stories US1 and US2 can be built in parallel by different developers once Foundational is done.

---

## Parallel Example: User Story 1 tests

```bash
# Launch the US1 test tasks together (they touch different files):
Task: "ParticipationAccrualPolicyTest in src/test/java/bot/domain/participation/ParticipationAccrualPolicyTest.java"
Task: "AccrueParticipationServiceTest in src/test/java/bot/application/participation/AccrueParticipationServiceTest.java"
Task: "JpaParticipationAccrualAdapterTest in src/test/java/bot/infrastructure/persistence/participation/JpaParticipationAccrualAdapterTest.java"
```

---

## Implementation Strategy

### MVP (US1 + US2 together)

1. Phase 1 Setup → Phase 2 Foundational (V4 migration, enum, domain types/ports, config+channel persistence).
2. Phase 3 US1 (earning core + sweep) and Phase 4 US2 (admin channel/rate config) — earning is inert without a designated channel, so ship them together.
3. **STOP and VALIDATE**: designate a channel, set a fast rate, play the current game, confirm a drop credits and `/balance` reflects it.

### Incremental Delivery

1. Foundation → US1 + US2 (MVP) → demo earning.
2. Add US3 (history label) → members see participation credits distinctly.
3. Add US4 (free-first-proposal) → cold-start bootstrap.

---

## Notes

- `[P]` = different files, no incomplete dependencies.
- The participation credit reuses the one coin ledger (`CoinLedgerPort` + `CoinLedgerPolicy.planGrant`); no second economy, no new arithmetic.
- V1–V3 migrations are immutable — all schema changes ship in **V4**.
- Domain (`bot.domain.participation`) stays framework-free; transactions/ports only in `bot.application.participation`; JDA/scheduler/JPA only in `bot.infrastructure`.
- Run `./mvnw -q verify` after each task and surface failures verbatim.
