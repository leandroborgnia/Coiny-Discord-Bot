---
description: "Task list for feature implementation: Skip Jar"
---

# Tasks: Skip Jar

**Input**: Design documents from `specs/006-skip-jar/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (slash-commands.md,
application-services.md, ledger-and-rotation.md), quickstart.md

**Tests**: INCLUDED — required by Constitution Principle VI (real-Postgres testing) and enumerated in
quickstart.md (15 scenarios: pure-domain unit + Testcontainers integration).

**Organization**: Tasks are grouped by user story (P1–P4) to enable independent implementation and
testing. **MVP = US1 + US2** (a contribution that can never trigger a skip is half a feature); US3 and
US4 layer on.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 / US3 / US4 (Setup, Foundational, Polish have no story label)
- Exact file paths are included in every task

## Path Conventions

Single Spring Boot service, existing four-layer hexagonal layout:
`src/main/java/bot/{domain,application,infrastructure,discord}`, resources under
`src/main/resources`, tests under `src/test/java/bot/`. Run `./mvnw -q verify` after each task.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration and message scaffolding shared by all stories. No new Maven dependency
(plan.md "Primary Dependencies").

- [X] T001 [P] Add `skipjar:` defaults (`default-floor: 3`, `default-dwell: PT24H`) to `src/main/resources/application.yml` per contracts/ledger-and-rotation.md §G (documentation of domain defaults; `GuildSkipJarConfig.defaults` stays authoritative)
- [X] T002 [P] Add skip-jar i18n keys to `src/main/resources/messages/coin-messages.properties`: the `SKIP_JAR` coin-history label plus all `/skip` and `/skip-config` reply strings from contracts/slash-commands.md (success/skip-triggered/already-contributed/not-earner/insufficient/jar-closed/no-game; status open/not-open/no-game; config updated/not-authorized/no-role)

**Checkpoint**: Config and messages in place; no behavior change yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The V5 schema, additive ledger enum values, and all new domain ports + infrastructure
adapters that **every** user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Ledger enum additions + migration (paired — additive only)

- [X] T003 [P] Add additive enum value `SKIP_POT` to `src/main/java/bot/domain/coin/LedgerAccount.java`
- [X] T004 [P] Add additive enum value `SKIP_JAR` to `src/main/java/bot/domain/coin/AdjustmentType.java`
- [X] T005 [P] Add `skipPot(int signedAmount)` factory to `src/main/java/bot/domain/coin/PostingLine.java` (no `member_id`, so the V2 `(account='MEMBER')=(member_id IS NOT NULL)` CHECK is satisfied)
- [X] T006 Create migration `src/main/resources/db/migration/V5__skip_jar.sql` (V1–V4 untouched): create tables `guild_skip_jar_config` (PK `guild_id`; `threshold_floor int NOT NULL DEFAULT 3 CHECK (>0)`, `dwell_seconds bigint NOT NULL DEFAULT 86400 CHECK (>0)`, `participation_gate boolean NOT NULL DEFAULT true`, `updated_at timestamptz NOT NULL DEFAULT now()`) and `skip_contribution` (`guild_id bigint`, `week_number int`, `member_id bigint`, `movement_id bigint NOT NULL REFERENCES coin_movement(id)`, `created_at timestamptz NOT NULL DEFAULT now()`, PK `(guild_id, week_number, member_id)`); then drop+re-add `coin_ledger_entry_account_check` to include `'SKIP_POT'` and `coin_movement_type_check` to include `'SKIP_JAR'` per data-model.md §"V5 additive ledger changes"

### Pure-domain contracts (no Spring/JDA/JPA)

- [X] T007 [P] Create `src/main/java/bot/domain/skipjar/GuildSkipJarConfig.java` — record `(long guildId, int thresholdFloor, Duration dwell, boolean gateOn)` with static `defaults(guildId) = (3, Duration.ofHours(24), true)` (data-model.md §GuildSkipJarConfig)
- [X] T008 [P] Create `src/main/java/bot/domain/skipjar/SkipThresholdPolicy.java` — pure `threshold(int distinctEarners, int floor) = max(floor(N/2)+1, floor)` (FR-008/FR-009, I-S5)
- [X] T009 [P] Create domain ports in `src/main/java/bot/domain/skipjar/`: `SkipJarConfigPort.java` (get/setFloor/setDwell/setGate), `SkipContributionPort.java` (hasContributed/count/record), `EarnerStatsPort.java` (distinctEarnerCount/isEarner) per contracts/ledger-and-rotation.md §C/§F
- [X] T010 [P] Create domain exceptions in `src/main/java/bot/domain/skipjar/` as typed `DomainException`s with i18n keys: `NoCurrentGameException.java`, `JarClosedException.java`, `NotEligibleToContributeException.java`, `AlreadyContributedException.java` (contracts/application-services.md §"Domain exceptions")

### Infrastructure adapters (JPA behind the ports)

- [X] T011 [P] Create `src/main/java/bot/infrastructure/persistence/skipjar/GuildSkipJarConfigEntity.java` + `GuildSkipJarConfigJpaRepository.java` + `JpaSkipJarConfigAdapter.java` implementing `SkipJarConfigPort` (absent row ⇒ `GuildSkipJarConfig.defaults`; setters upsert via `ON CONFLICT`)
- [X] T012 [P] Create `src/main/java/bot/infrastructure/persistence/skipjar/SkipContributionEntity.java` + `SkipContributionId.java` (composite `(guildId, weekNumber, memberId)`) + `SkipContributionJpaRepository.java` + `JpaSkipContributionAdapter.java` implementing `SkipContributionPort` (`record` inserts; unique-violation surfaces as once-per-run; `count` over the `(guild_id, week_number)` PK prefix)
- [X] T013 [P] Create `src/main/java/bot/infrastructure/persistence/skipjar/JpaEarnerStatsAdapter.java` implementing `EarnerStatsPort` with native Postgres queries over `coin_movement` (`type='PARTICIPATION' AND credited_amount>0 AND created_at>=:since`): `COUNT(DISTINCT member_id)` and `EXISTS` (contracts/ledger-and-rotation.md §C, D-2)

**Checkpoint**: Schema, ledger enums, ports, and adapters ready — user stories can begin.

---

## Phase 3: User Story 1 — Pay a coin into the skip jar to vote (Priority: P1) 🎯 MVP

**Goal**: A member can pay exactly one non-refundable coin into the current game's skip jar; the
contribution is counted, gated (dwell/earner/balance), and limited to once per run. No skip is
triggered yet (that is US2).

**Independent Test**: With a current game past its dwell and an earner with ≥1 coin, contribute →
exactly 1 coin debited as a `SKIP_JAR` movement, jar count = 1; a second contribution by the same
member is refused with no charge; a non-earner (gate on) and a zero-balance member are refused.

### Tests for User Story 1 ⚠️ (write first, ensure they FAIL)

- [X] T014 [P] [US1] Unit test `SkipJarLedgerPolicy.planContribution` in `src/test/java/bot/domain/skipjar/SkipJarLedgerPolicyTest.java`: balanced `MEMBER −1 / SKIP_POT +1`, `requested=1/credited=0/forfeited=0`, type `SKIP_JAR`, and `OverdrawException` when balance < 1 (quickstart #2)
- [X] T015 [P] [US1] Service unit test (Mockito ports) `src/test/java/bot/application/skipjar/ContributeToSkipJarServiceTest.java`: no-current-game → `NoCurrentGameException`; within dwell → `JarClosedException`; gate-on non-earner → `NotEligibleToContributeException`; gate-off skips the earner check; already-contributed → `AlreadyContributedException`; verifies lock order `queue → account`
- [X] T016 [P] [US1] Integration test (Testcontainers) `src/test/java/bot/infrastructure/persistence/skipjar/SkipContributionIntegrationTest.java`: contribute debits exactly 1, posts a `SKIP_JAR` movement, jar count = 1 (#3); second same-run contribution → `AlreadyContributedException`, PK enforced, balance unchanged (#4); zero balance → `OverdrawException`, jar unchanged (#7); jar closed during dwell → `JarClosedException`, no charge (#8); gate-on non-earner refused (#5); gate-off accepted (#6); non-refundable (no reversal written, #13)

### Implementation for User Story 1

- [X] T017 [US1] Create `src/main/java/bot/domain/skipjar/SkipJarLedgerPolicy.java` — pure `planContribution(long memberId, int currentBalance)` returning a `PostingPlan(SKIP_JAR, 1, 0, 0, [member(-1), skipPot(+1)])`, throwing `new OverdrawException(memberId, currentBalance)` (2-arg ctor) when `currentBalance < 1` (contracts/ledger-and-rotation.md §A)
- [X] T018 [US1] Create request/result records in `src/main/java/bot/application/skipjar/`: `ContributeRequest(long guildId, long memberId, long interactionId, Instant now)` and `ContributeResult(boolean charged, int count, int threshold, int remaining, boolean skipped, String gameName, String newGameName, Optional<AnnouncementView> announcement)` — `newGameName` is the post-skip current game's display name (null when `!skipped` or the new week is empty) so the skip reply renders `{newGame}` even with no announcement channel (contracts/application-services.md §1)
- [X] T019 [US1] Create `src/main/java/bot/application/skipjar/ContributeToSkipJarService.java` (`@Transactional`) implementing algorithm steps 1–9 **without the trigger** (US2 adds step 10): queue lock → idempotency replay via `CoinLedgerPort.findByInteractionId` → read `RotationStatePort.get` (empty slot ⇒ `NoCurrentGameException`) → dwell gate → gate/earner check → `hasContributed` → `lockAccount` + balance + `SkipJarLedgerPolicy.planContribution` + `CoinLedgerPort.append` (9-arg `NewMovement` mirroring `ProposeGameService.postSpend`: moderator=self, reason=null, type/requested/credited/forfeited from the plan → MEMBER −1 / SKIP_POT +1) → `skipContributionPort.record(..., applied.movement().id())` → compute `count`/`threshold` (`SkipThresholdPolicy`) and return `skipped=false, remaining=threshold-count, newGameName=null`. Resolve the game **display name** from the queue slot via `queuePort.findSlot(state.currentSlot().get()).game().name()` (NOT `CurrentGamePort`, which returns an identity key) (contracts/application-services.md §1, ledger-and-rotation.md §A/§B/§D)
- [X] T020 [US1] Create thin handler `src/main/java/bot/discord/command/SkipCommand.java` registering `/skip contribute` (no options; reject DMs): `event.deferReply(true)` FIRST, then delegate to `ContributeToSkipJarService.contribute` with `(guildId, memberId, interactionId, Instant.now())` and render the success/refusal replies (contracts/slash-commands.md `/skip contribute`)

**Checkpoint**: A member can contribute, see `{count}/{threshold} — {remaining}`, and is correctly
refused for duplicate / non-earner / insufficient balance / dwell / no-game. No skip triggers yet.

---

## Phase 4: User Story 2 — The group retires the game early and the rotation advances (Priority: P2) 🎯 MVP

**Goal**: When a contribution makes the jar reach the threshold (and dwell has elapsed), the current
game is retired and the rotation advances exactly one step using the queue's deterministic rules, with
no double-advance under concurrency.

**Independent Test**: Drive contributions to the computed threshold → on the threshold-meeting
contribution the current game changes and the rotation advances one step (as a weekly advance would);
one short of threshold → nothing advances; after a skip the new game's jar is empty; concurrent
threshold-meeting contributions yield exactly one advance.

### Tests for User Story 2 ⚠️ (write first, ensure they FAIL)

- [X] T021 [P] [US2] Unit test `SkipThresholdPolicy` in `src/test/java/bot/domain/skipjar/SkipThresholdPolicyTest.java`: `threshold(N, floor=3)` for N = 0,1,2,5,6 — majority `floor(N/2)+1` vs floor governs small earner sets (quickstart #1, US2 AS-3)
- [X] T022 [P] [US2] Test `AdvanceRotationService.skip` one-step semantics in `src/test/java/bot/application/queue/AdvanceRotationServiceSkipTest.java`: exactly one pop with `popAt = now`, same body as `advanceDue` (top → markPlayed → shiftUp → designate → `decrementAll` then `set` → advanceClock), empty-queue → empty week; assembles announcement when configured
- [X] T023 [P] [US2] Integration test (Testcontainers) `src/test/java/bot/infrastructure/persistence/skipjar/SkipTriggerIntegrationTest.java`: threshold-meeting contribution retires the game and advances one step (#9); one short → current game unchanged, jar accumulates (#10); after skip the new week's count = 0 and retired contributions don't count (#11, SC-010); a **normal weekly advance** (not a skip) that rolls the run before the jar triggers also resets the jar — the new week's count = 0 and the prior run's contributions never count (#11b, FR-012 edge "Normal weekly advance before a skip"); concurrent threshold-meeting contributions → exactly one pop, second refused via dwell reset (#12, FR-011); SKIP_POT retains coins, no reversal (#13)

### Implementation for User Story 2

- [X] T024 [US2] Refactor `src/main/java/bot/application/queue/AdvanceRotationService.java`: extract the single-pop body of `advanceDue`'s loop into a shared private step (top → designate → shiftUp → cooldowns `decrementAll` before `set`), parameterized by the `popAt`/clock baseline — no behavior change to the weekly advance
- [X] T025 [US2] Add `skip(long guildId, Instant now)` to `src/main/java/bot/application/queue/AdvanceRotationService.java` (`@Transactional`): take `queuePort.lockQueue` (reentrant within the contribution txn), perform **exactly one** pop via the extracted body with `week = currentWeekNumber + 1` and `popAt = now`, return `AdvanceResult` with the new game's announcement when a channel is configured (contracts/ledger-and-rotation.md §E, FR-010/FR-011)
- [X] T026 [US2] Wire the trigger into `src/main/java/bot/application/skipjar/ContributeToSkipJarService.java` (algorithm step 10): when `count >= threshold`, call `advanceRotationService.skip(guildId, now)` within the same locked transaction; then re-read `RotationStatePort.get` and resolve `newGameName = newState.currentSlot().flatMap(queuePort::findSlot).map(s -> s.game().name()).orElse(null)` (null when the new week is empty), and return `ContributeResult(skipped=true, remaining=0, newGameName, announcement=advance.finalAnnouncement())` (no-double-advance is guaranteed by the queue lock + dwell reset; `newGameName` is independent of the announcement so the reply renders with no channel — F1)
- [X] T027 [US2] Update `src/main/java/bot/discord/command/SkipCommand.java` to render the skip-triggered reply ("the jar is full! **{retiredGame}** retired; **{newGame}** is up now.") using `result.gameName()` for `{retiredGame}` and `result.newGameName()` for `{newGame}` (both always present on a skip, even with no channel); and, when an announcement channel is configured, post/update the regular rotation announcement from `result.announcement()` via the existing `AnnouncementAssembler` (contracts/slash-commands.md `/skip contribute` success-skip)

**Checkpoint**: MVP complete — contributions accumulate and, at threshold, trigger exactly one
deterministic early advance with the new game's jar empty.

---

## Phase 5: User Story 3 — See how full the skip jar is (Priority: P3)

**Goal**: A member can view the current game's jar: count, threshold, remaining; "not open yet" during
dwell; "no game" without error.

**Independent Test**: With a current game and some contributions, `/skip status` shows count /
threshold / remaining; within dwell it shows not-open (with when it opens); with no current game it
reports there is nothing to skip without erroring.

### Tests for User Story 3 ⚠️ (write first, ensure they FAIL)

- [X] T028 [P] [US3] Integration test (Testcontainers) `src/test/java/bot/application/skipjar/ViewSkipJarServiceTest.java`: OPEN shows correct count/threshold/remaining/earnerCount/floor; NOT_OPEN during dwell with `opensAt = becameCurrent + dwell`; NO_GAME when no current slot — never throws (quickstart #14, FR-014)

### Implementation for User Story 3

- [X] T029 [P] [US3] Create request/result records in `src/main/java/bot/application/skipjar/`: `ViewRequest(long guildId, Instant now)` and `SkipJarStatus(State state, String gameName, int count, int threshold, int remaining, int earnerCount, int floor, Instant opensAt)` with `enum State { NO_GAME, NOT_OPEN, OPEN }` (contracts/application-services.md §2)
- [X] T030 [US3] Create `src/main/java/bot/application/skipjar/ViewSkipJarService.java` (`@Transactional(readOnly=true)`, no lock): read `RotationStatePort` → NO_GAME (`gameName=null`) when slot empty; else resolve `gameName` from the queue slot via `queuePort.findSlot(state.currentSlot().get()).game().name()` (same source as the contribute service, NOT `CurrentGamePort`), then `opensAt = lastPopAt + dwell` → NOT_OPEN when `now < opensAt`; else OPEN with `count`, `earnerCount`, `threshold = SkipThresholdPolicy.threshold(...)`, `remaining = max(0, threshold-count)` (contracts/application-services.md §2)
- [X] T031 [US3] Add `/skip status` subcommand to `src/main/java/bot/discord/command/SkipCommand.java` (defer-first, delegate to `ViewSkipJarService.view`, render OPEN / NOT_OPEN / NO_GAME ephemeral replies per contracts/slash-commands.md `/skip status`)

**Checkpoint**: US1 + US2 + US3 all function independently.

---

## Phase 6: User Story 4 — Admin configures the skip jar (Priority: P4)

**Goal**: A member with the configured moderator role sets the threshold floor, the dwell, and toggles
the participation gate; non-admins are refused and change nothing.

**Independent Test**: As a moderator-role member, set floor / dwell / gate and confirm each takes
effect on subsequent evaluations; a member without the role is refused and settings are unchanged.

### Tests for User Story 4 ⚠️ (write first, ensure they FAIL)

- [X] T032 [P] [US4] Integration test (Testcontainers) `src/test/java/bot/application/skipjar/ConfigureSkipJarServiceTest.java`: authorized floor/dwell/gate changes persist and re-read correctly; admin bypass; `ModeratorRoleNotConfiguredException` when no role; `ModeratorNotAuthorizedException` for a member lacking the role — settings unchanged (quickstart #15, FR-017/SC-009)

### Implementation for User Story 4

- [X] T033 [P] [US4] Create request/result records in `src/main/java/bot/application/skipjar/`: `ConfigureRequest(long guildId, Op op, Set<Long> actorRoleIds, boolean actorIsAdmin, int floor, long dwellSeconds, boolean gateOn)` with `enum Op { FLOOR, DWELL, GATE }` and `SkipJarConfigResult(int thresholdFloor, long dwellSeconds, boolean gateOn)` (contracts/application-services.md §3)
- [X] T034 [US4] Create `src/main/java/bot/application/skipjar/ConfigureSkipJarService.java` (`@Transactional`): `authorize` via `GuildCoinConfigPort.get` (mirrors `ConfigureParticipationService` — fails closed, admin bypass); switch on `Op` with defensive re-validation (`floor >= 1`, `dwellSeconds >= 1`) calling `SkipJarConfigPort.setFloor/setDwell/setGate`; return the re-read config (contracts/application-services.md §3)
- [X] T035 [US4] Create thin handler `src/main/java/bot/discord/command/SkipConfigCommand.java` registering `/skip-config floor|dwell|gate` (option mins: floor `>=1`, dwell hours `>0`; `dwell_seconds = round(hours*3600)`): defer-first, build `ConfigureRequest` from the actor's roles/admin flag, delegate to `ConfigureSkipJarService.configure`, render the updated/not-authorized/no-role replies (contracts/slash-commands.md `/skip-config`)

**Checkpoint**: All four user stories functional and independently testable.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T036 [P] Apply formatting: run `./mvnw spotless:apply` across the new files
- [X] T037 Run the full quickstart.md automated gate `./mvnw -q verify` (all 15 scenarios green; confirm `git diff` touches no V1–V4 migration) and surface any failure verbatim
- [X] T038 [P] Update `CLAUDE.md` source-layout notes if needed (e.g. mention `bot.application.skipjar` / `bot.domain.skipjar`) and confirm `application.yml` `skipjar:` defaults are documented

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — can start immediately.
- **Foundational (Phase 2)**: depends on Setup; **BLOCKS all user stories**. T006 (V5) pairs with the
  additive enum values T003–T005.
- **User Stories (Phase 3–6)**: all depend on Foundational. US1 and US2 form the MVP and share
  `ContributeToSkipJarService` (US2's T026 extends US1's T019). US3 and US4 are independent of each
  other and of US2's trigger; both depend only on Foundational (+ shared records).
- **Polish (Phase 7)**: after the desired stories are complete.

### User Story Dependencies

- **US1 (P1)**: Foundational only. Independently testable (contribute/count/refuse) without the trigger.
- **US2 (P2)**: Foundational + US1 (extends the contribute service with step 10 and adds
  `AdvanceRotationService.skip`). MVP = US1 + US2.
- **US3 (P3)**: Foundational only (reuses `SkipThresholdPolicy`, ports). Independent of US2.
- **US4 (P4)**: Foundational only (reuses `SkipJarConfigPort`, coin-config auth). Independent of US2/US3.

### Within Each User Story

- Tests are written first and must FAIL before implementation.
- Pure domain (policies) → application service → handler.
- Records before the service that returns them.

### Parallel Opportunities

- Setup: T001, T002 in parallel.
- Foundational: T003/T004/T005 in parallel; then T006; T007–T010 (domain) in parallel; T011–T013
  (infra adapters) in parallel after their ports (T009) exist.
- Within a story, all `[P]` test files run in parallel; record files run in parallel with each other.
- With capacity, US3 and US4 can be built in parallel once Foundational is done.

---

## Parallel Example: Foundational domain + adapters

```bash
# After T006 (migration) and T009 (ports) land:
Task: "GuildSkipJarConfig value object in src/main/java/bot/domain/skipjar/GuildSkipJarConfig.java"   # T007
Task: "SkipThresholdPolicy in src/main/java/bot/domain/skipjar/SkipThresholdPolicy.java"             # T008
Task: "Domain exceptions in src/main/java/bot/domain/skipjar/"                                        # T010
Task: "JpaSkipJarConfigAdapter in src/main/java/bot/infrastructure/persistence/skipjar/"             # T011
Task: "JpaSkipContributionAdapter in src/main/java/bot/infrastructure/persistence/skipjar/"          # T012
Task: "JpaEarnerStatsAdapter in src/main/java/bot/infrastructure/persistence/skipjar/"               # T013
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Phase 1: Setup.
2. Phase 2: Foundational (CRITICAL — blocks all stories).
3. Phase 3 (US1): contribute, count, refuse — **STOP and VALIDATE** (`./mvnw -q verify`).
4. Phase 4 (US2): trigger + `AdvanceRotationService.skip` — **STOP and VALIDATE**. MVP done.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. US1 → test → demo (votes register).
3. US2 → test → demo (votes trigger the skip). **← MVP**
4. US3 → test → demo (status view).
5. US4 → test → demo (admin tuning).

---

## Notes

- `[P]` = different files, no dependency on an incomplete task.
- Run `./mvnw -q verify` after each task and surface failures verbatim (CLAUDE.md).
- Never touch V1–V4 migrations — all schema ships in V5.
- Handlers defer before any work (Constitution Principle V).
- Contributed coins are never refunded — no SKIP_JAR reversal path is ever written (FR-003).
