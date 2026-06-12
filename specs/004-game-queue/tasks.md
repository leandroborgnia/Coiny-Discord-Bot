---
description: "Task list for Game Queue & Weekly Rotation (feature 004)"
---

# Tasks: Game Queue & Weekly Rotation

**Input**: Design documents from `specs/004-game-queue/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: INCLUDED. This project is test-driven by **Constitution Principle VI** (real-Postgres
testing via Testcontainers) and CLAUDE.md ("Run `./mvnw -q verify` after each task; surface failures
verbatim"). Pure domain logic is unit-tested without a DB; persistence/idempotency/ledger behavior is
integration-tested against Testcontainers Postgres. No secrets are needed; the IGDB resolver is a
disabled no-op without credentials.

**Organization**: Tasks are grouped by user story. **MVP = US1 (propose) + US2 (rotation)**.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- All paths are repo-relative; Java root is `src/main/java/`, tests `src/test/java/`.
- Run `./mvnw -q verify` after each task; `./mvnw spotless:apply` before committing.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration, i18n, scheduling, and JDA intents the feature needs before any story.

- [X] T001 [P] Add the `queue:` config block (rotation tick interval default hourly, view `next=5`,
  `art.igdb.enabled`/`base-url`, `art.igdb.client-id`/`client-secret` as `${IGDB_CLIENT_ID}`/
  `${IGDB_CLIENT_SECRET}`) and change `spring.messages.basename` to
  `messages/coin-messages,messages/queue-messages` in `src/main/resources/application.yml`.
- [X] T002 [P] Create the i18n bundle `src/main/resources/messages/queue-messages.properties` with
  keys for every reply/error in `contracts/slash-commands.md` (proposed/instant-pop/replaced/
  duplicate/no-activity/insufficient/cooldown/withdrawn/bumped/at-top/no-queued/config/
  not-authorized).
- [X] T003 [P] Add `SchedulingConfig` with `@EnableScheduling` (guarded by `discord.enabled`) in
  `src/main/java/bot/infrastructure/schedule/SchedulingConfig.java`.
- [X] T004 Update `src/main/java/bot/infrastructure/discord/JdaConfig.java`: build JDA with
  `GatewayIntent.GUILD_PRESENCES` + `GUILD_MEMBERS`, `enableCache(CacheFlag.ACTIVITY)`, and a
  **non-retaining** `MemberCachePolicy.NONE` with no eager member chunking (no roster/presence held in
  memory — memory stays flat at any scale); keep the `discord.enabled` gate. Add a code comment that
  both intents are **privileged** and must be enabled in the Discord Developer Portal. Also add
  `PresenceReader` in `src/main/java/bot/infrastructure/discord/PresenceReader.java` — an injectable
  component that, given a guild + member id, calls `guild.retrieveMembersByIds(true, memberId)`
  (verified JDA 5.2.1 API: `Task<List<Member>> retrieveMembersByIds(boolean includePresence, long...)`),
  reads the first `ActivityType.PLAYING` activity, and maps it to `CapturedGame` (empty when none).
- [X] T005 [P] Add `spring-boot-starter-json` to `pom.xml` (compile scope, version managed by the
  Spring Boot BOM — no version pin, no web server). **Why required**: JDA declares `jackson-databind`
  at **`runtime`** scope, so it is *not* on the compile classpath — the IGDB resolver's Jackson imports
  would not compile without this starter. This is the **only** new Maven coordinate in the feature and
  is recorded in `plan.md` (Technical Context) and `research.md` (§Dependencies) per Constitution
  (Development Workflow) before the build change.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Migration, pure domain, ports, entities, and the ledger POT extension that every story
depends on.

**⚠️ CRITICAL**: No user-story work begins until this phase is complete.

- [X] T006 Create `src/main/resources/db/migration/V3__game_queue.sql` (V2 untouched): the 7 tables
  (`guild_queue_config`, `queue_entry`, `queue_upvote`, `queue_rotation_state`, `weekly_designation`,
  `queue_cooldown`, `game_art_cache`) with the indexes/partial-unique constraints from
  `data-model.md`. `queue_entry` includes `game_instance_id uuid NOT NULL DEFAULT gen_random_uuid()`
  and `queue_upvote`'s PRIMARY KEY is `(slot_id, member_id, game_instance_id)` with a `game_instance_id
  uuid NOT NULL` column (finding U3). **Plus** the additive ledger changes — **(F1)** first confirm the
  V2 auto-names via `SELECT conname FROM pg_constraint WHERE conrelid='coin_ledger_entry'::regclass AND
  contype='c'` (verified: `coin_ledger_entry_account_check` and `coin_movement_type_check`; the
  table-level `coin_ledger_entry_check` is a different constraint and must NOT be dropped), then
  `ALTER ... DROP/ADD coin_ledger_entry_account_check` to add `'POT'` and
  `ALTER ... DROP/ADD coin_movement_type_check` to add `'QUEUE_PROPOSE','QUEUE_BUMP','QUEUE_REFUND'`.
- [X] T007 [P] Add `POT` to the `LedgerAccount` enum in
  `src/main/java/bot/domain/coin/LedgerAccount.java`.
- [X] T008 [P] Create pure-domain value objects in `src/main/java/bot/domain/queue/`: `CapturedGame`,
  `GameIdentity` (with `of(CapturedGame)` + `normalize(name)`), `QueueSlot` (incl. a `UUID
  gameInstanceId` field — the per-appearance id, distinct from `GameIdentity`), `QueueView`. No
  framework imports.
- [X] T009 [P] Create domain exceptions (extending `bot.domain.DomainException`) in
  `src/main/java/bot/domain/queue/`: `InsufficientCoinsException`, `NotEligibleException(int
  gamesRemaining)`, `NoQueuedGameException`, `NoGameActivityException`, `NotAuthorizedException`
  (raised by `ConfigureQueueService` when the actor lacks Manage Server). **No `AlreadyAtTopException`
  or `NotSlotOwnerException`**: bump returns an `AT_TOP` result outcome (not a throw), and acting only
  on the caller's own slot makes a "not owner" case structurally impossible — FR-005 is satisfied by
  construction, so neither exception nor a not-owner reply is needed.
- [X] T010 [P] Define the outbound port interfaces + carrier records in
  `src/main/java/bot/domain/queue/`: `QueuePort`, `QueueConfigPort`, `UpvotePort`,
  `RotationStatePort`, `CooldownPort`, `ArtCachePort`, `ArtResolverPort`, `AnnouncementPort` and
  carriers (`NewSlot` — carries a fresh `gameInstanceId`, `RotationState`, `GuildQueueConfig`
  (finding F2: not `QueueQueueConfig`), `ArtEntry`, `ArtSource`, `AnnouncementView`, `AnnouncementRef`)
  per `data-model.md`. `UpvotePort.toggle`/`count` take a `UUID gameInstanceId`; `QueuePort` exposes
  `currentInstance(slotId)` and `replaceGame(..., UUID newInstanceId)`. Domain/JDK types only.
- [X] T011 Implement the pure `QueueLedgerPolicy` (`planSpend`, `planRefund` building balanced
  `bot.domain.coin.PostingPlan`s; throws `InsufficientCoinsException`) in
  `src/main/java/bot/domain/queue/QueueLedgerPolicy.java`, with a unit test
  `src/test/java/bot/domain/queue/QueueLedgerPolicyTest.java` (zero-sum lines, overdraw throws).
- [X] T012 [P] Create the JPA entities + Spring Data repositories for all 7 tables in
  `src/main/java/bot/infrastructure/persistence/queue/` (`*Entity` + `*JpaRepository`; `jsonb`
  snapshot mapped as `String`/`@JdbcTypeCode`).
- [X] T013 [P] Add the `QueueMessages` i18n helper (mirrors `CoinMessages`) in
  `src/main/java/bot/discord/command/QueueMessages.java`.

**Checkpoint**: Schema, domain, ports, entities, and the POT ledger extension are ready.

---

## Phase 3: User Story 1 — Propose a game with coins (Priority: P1) 🎯 MVP

**Goal**: A playing member spends coins to propose (Rich-Presence captured), can replace or withdraw
(refund); ineligible/unaffordable/no-activity/duplicate change nothing; empty server instant-pops.

**Independent Test**: Propose while playing → slot at tail, exactly 1 coin deducted; not-playing →
rejected, no charge; first proposal on an empty server → instant-pop; duplicate → at most once;
withdraw → slot gone + refund.

### Tests for User Story 1 ⚠️ (write first; ensure they fail)

- [X] T014 [P] [US1] Unit test `QueueOrderingPolicy` (append-tail, shift-up) in
  `src/test/java/bot/domain/queue/QueueOrderingPolicyTest.java`.
- [X] T015 [P] [US1] Unit test `CooldownPolicy` (eligibility, N at pop, decrement, N=0) in
  `src/test/java/bot/domain/queue/CooldownPolicyTest.java`.
- [X] T016 [P] [US1] Testcontainers integration test for `JpaQueueAdapter`: tail append, partial-unique
  (one queued slot per member; unique position), `propose_interaction_id` idempotency
  (`ON CONFLICT`), concurrent-propose advisory-lock serialization, **per-server isolation (C2)** —
  two guilds' queues/positions/`ownQueued` never interfere — and **departed-proposer retention (C3)** —
  a slot persists and renders by its stored `proposer_member_id` regardless of membership — in
  `src/test/java/bot/infrastructure/persistence/queue/JpaQueueAdapterTest.java`.
- [X] T017 [P] [US1] Testcontainers test that propose/withdraw post balanced `MEMBER↔POT` entries and
  that `/balance`'s derived `SUM` reflects them, incl. refund reversal, in
  `src/test/java/bot/infrastructure/persistence/queue/QueuePotLedgerTest.java`.
- [X] T018 [P] [US1] Service test `ProposeGameServiceTest` (Mockito ports): no-activity guard,
  affordability, replace branch (free, resets upvotes), instant-pop bootstrap, eligibility, duplicate,
  in `src/test/java/bot/application/queue/ProposeGameServiceTest.java`.
- [X] T019 [P] [US1] Service test `WithdrawGameServiceTest` (refund amount = `coins_spent`, no-queued,
  duplicate) in `src/test/java/bot/application/queue/WithdrawGameServiceTest.java`.

### Implementation for User Story 1

- [X] T020 [P] [US1] Implement the pure `QueueOrderingPolicy` (append-tail, bump-swap, shift-up) in
  `src/main/java/bot/domain/queue/QueueOrderingPolicy.java`.
- [X] T021 [P] [US1] Implement the pure `CooldownPolicy` (N, eligibility, decrement) in
  `src/main/java/bot/domain/queue/CooldownPolicy.java`.
- [X] T022 [US1] Implement `JpaQueueAdapter` (`lockQueue` via `pg_advisory_xact_lock`, `queued`,
  `ownQueued`, `findByProposeInteraction`, `append` with `ON CONFLICT DO NOTHING` setting a fresh
  `game_instance_id`, `replaceGame(..., UUID newInstanceId)` writing the new instance, `currentInstance`,
  `withdraw`, `top`, `markPlayed`, `shiftUp`, `otherQueuedCount`) in
  `src/main/java/bot/infrastructure/persistence/queue/JpaQueueAdapter.java`.
- [X] T023 [P] [US1] Implement `JpaQueueConfigAdapter` (`get` with defaults 1/1, `upsertCosts`) in
  `src/main/java/bot/infrastructure/persistence/queue/JpaQueueConfigAdapter.java`.
- [X] T024 [P] [US1] Implement `JpaCooldownAdapter` (`gamesRemaining`, `set`) and minimal
  `JpaRotationStateAdapter` (`get`, `bootstrap`, `recordDesignation`) needed for instant-pop, in
  `src/main/java/bot/infrastructure/persistence/queue/`.
- [X] T025 [P] [US1] Implement `JpaUpvoteAdapter.resetForSlot` (used by replace) in
  `src/main/java/bot/infrastructure/persistence/queue/JpaUpvoteAdapter.java`.
- [X] T026 [US1] Implement `ProposeGameService` (`@Transactional`; lock order queue→account;
  no-activity/replace/eligibility/affordability/instant-pop/normal per `contracts/`; mint a fresh
  `gameInstanceId` on a new slot and **regenerate it on replace** before `resetForSlot`) with
  `ProposeGameRequest`/`ProposeGameResult` in `src/main/java/bot/application/queue/`. (Depends on
  T011, T020–T025.)
- [X] T027 [US1] Implement `WithdrawGameService` (+ `WithdrawGameRequest`/`Result`; refund via
  `QueueLedgerPolicy.planRefund`) in `src/main/java/bot/application/queue/`.
- [X] T028 [US1] Implement the thin `ProposeCommand` (`/queue-propose`: **defer ephemeral first** →
  `PresenceReader.capture(guild, memberId)` via the on-demand `retrieveMembersByIds(true, …)` fetch →
  build `CapturedGame` (or none → no-activity guard) → delegate → render) in
  `src/main/java/bot/discord/command/ProposeCommand.java`. Any member currently playing a game may
  propose (no voice requirement). The presence fetch happens **after** the defer, so the 2.5 s ack
  deadline is met (Principle V).
- [X] T029 [US1] Implement the thin `WithdrawCommand` (`/queue-withdraw`) in
  `src/main/java/bot/discord/command/WithdrawCommand.java`.
- [X] T030 [US1] Add the US1 reply/error keys to `queue-messages.properties` and a
  `QueueMessagesTest` assertion that every referenced key resolves, in
  `src/test/java/bot/discord/command/QueueMessagesTest.java`.

**Checkpoint**: Propose/replace/withdraw work end-to-end with the shared ledger; `./mvnw verify` green.

---

## Phase 4: User Story 2 — Weekly rotation designates the top game (Priority: P2) 🎯 MVP

**Goal**: A rolling-7-day tick (and startup catch-up) pops the top slot, designates the week's game,
records the proposer's cooldown N, decrements others, is idempotent per week, and announces once.

> **Announcements are opt-in / silent-by-default (resolves analysis finding C1).** Per FR-036/FR-037
> the MVP rotation is **silent** until an admin configures an announcement channel — so US1+US2 is a
> complete, correct MVP on its own (the rotation works; it just posts nothing yet). T037 builds the
> posting adapter **and** the `setAnnouncementChannel` persistence here, but the user-facing switch to
> turn it on (`/queue-config announce`) lives in Phase 8 (T057/T058). If you want to *demo* a live
> announcement before shipping Phase 8, implement only the `announce`/`announce-clear` slice of
> T057/T058 right after this phase — it depends solely on the T037 adapter already built here.

**Independent Test**: With a known queue, advance → top designated & removed, rest shift up, advancing
again for the same week is a no-op; empty queue → no designation; downtime → each missed advance once.

### Tests for User Story 2 ⚠️

- [X] T031 [P] [US2] Unit test `RotationPolicy.advancesDue`/`nextPopAt` (rolling 7d, zero, multi-period)
  in `src/test/java/bot/domain/queue/RotationPolicyTest.java`.
- [X] T032 [P] [US2] Testcontainers test `AdvanceRotationServiceIT`: pop+shift+designate, idempotent
  `UNIQUE(guild,week)` double-advance no-op, empty-week no designation & no cooldown decrement,
  cooldown N set at pop + guild-wide decrement on real pops, multi-period catch-up applies each once,
  **single-announcement guarantee (C1)** — one advance yields at most one `AnnouncementView` to post,
  a re-advance for the same week yields none, and a multi-period catch-up yields exactly one (final)
  — and **per-guild rotation isolation (C2)** — advancing one guild leaves another's clock/queue
  untouched — in `src/test/java/bot/application/queue/AdvanceRotationServiceIT.java`.

### Implementation for User Story 2

- [X] T033 [P] [US2] Implement the pure `RotationPolicy` (`advancesDue`, `nextPopAt`) in
  `src/main/java/bot/domain/queue/RotationPolicy.java`.
- [X] T034 [US2] Extend `JpaRotationStateAdapter` with `advanceClock` and `JpaCooldownAdapter` with
  `decrementAll`; ensure `recordDesignation` uses `ON CONFLICT (guild_id, week_number) DO NOTHING`.
- [X] T035 [US2] Implement `AdvanceRotationService` (`advanceDue`, `catchUpAll`; lock queue; per-period
  loop; sets cooldown N=`otherQueuedCount`, decrements others only on real pops; returns the **final**
  `AnnouncementView`) in `src/main/java/bot/application/queue/AdvanceRotationService.java`.
- [X] T036 [US2] Implement `RotationScheduler` (`@Scheduled` fixed-delay tick over guilds +
  `@EventListener(ApplicationReadyEvent)` startup catch-up; gated by `discord.enabled`) in
  `src/main/java/bot/infrastructure/schedule/RotationScheduler.java`.
- [X] T037 [US2] Implement `JdaAnnouncementAdapter.post` (current game + key art + "up next" 5;
  returns message id) and store it as the guild's latest announcement via
  `QueueConfigPort.setLatestAnnouncement`, in
  `src/main/java/bot/infrastructure/discord/JdaAnnouncementAdapter.java` +
  `JpaQueueConfigAdapter.setAnnouncementChannel`/`setLatestAnnouncement`.
- [X] T038 [US2] Add rotation/announcement message keys to `queue-messages.properties`.

**Checkpoint**: MVP complete — proposals rotate weekly and survive downtime; `./mvnw verify` green.

---

## Phase 5: User Story 3 — View the queue, richly presented (Priority: P3)

**Goal**: Ephemeral view: current game + next 5 (key art, proposer, position, snapshot count) + own
entry always marked + own eligibility; cover art resolved via Rich-Presence→cache→IGDB→name-only.

**Independent Test**: Populated queue → view renders current + next 5 with art and counts; own entry
beyond top 5 still shown/marked; cooldown visible; empty state clear.

### Tests for User Story 3 ⚠️

- [X] T039 [P] [US3] Service test `ViewQueueServiceTest` (current+next5+own-marked-beyond-5+eligibility;
  **departed-proposer rendering (C3)** — a slot whose proposer is no longer a guild member still renders
  by the stored `proposer_member_id`) in
  `src/test/java/bot/application/queue/ViewQueueServiceTest.java`.
- [X] T040 [P] [US3] Test the art-resolution chain (RP asset → cache hit → cache miss→IGDB→store →
  IGDB miss→NONE name-only → resolver disabled) with a Mockito `ArtResolverPort`/`ArtCachePort` in
  `src/test/java/bot/application/queue/ArtResolutionTest.java`.
- [X] T041 [P] [US3] Unit test `IgdbArtResolver` against a **stubbed** `HttpClient` (token cache, cover
  parse, failure→empty, disabled-when-blank-creds), no network/secrets, in
  `src/test/java/bot/infrastructure/art/IgdbArtResolverTest.java`.

### Implementation for User Story 3

- [X] T042 [P] [US3] Implement `JpaArtCacheAdapter` (`lookup`, `store` incl. `NONE` miss) in
  `src/main/java/bot/infrastructure/persistence/queue/JpaArtCacheAdapter.java`.
- [X] T043 [P] [US3] Implement `IgdbArtResolver` (`ArtResolverPort`; JDK `HttpClient`; Twitch OAuth
  client-credentials with token caching; disabled no-op when creds blank) in
  `src/main/java/bot/infrastructure/art/IgdbArtResolver.java`.
- [X] T044 [US3] Implement the art-resolution chain helper (RP→cache→IGDB→name-only; never throws) and
  wire it into a `ViewQueueService` (`@Transactional(readOnly=true)`, `ViewQueueRequest`→`QueueView`)
  in `src/main/java/bot/application/queue/ViewQueueService.java`.
- [X] T045 [US3] Implement the thin `QueueViewCommand` (`/queue-view`: ephemeral embed; per-slot
  `upvote:{slotId}:{gameInstanceId}` buttons attached but not yet handled until US5) in
  `src/main/java/bot/discord/command/QueueViewCommand.java`.

**Checkpoint**: Members can see the queue with art and counts; `./mvnw verify` green.

---

## Phase 6: User Story 4 — Bump your game one position (Priority: P4)

**Goal**: A member spends coins to swap their own non-top slot up by exactly one; at-top / no-queued /
unaffordable change nothing. Bumping another member's game is impossible by construction — the command
acts only on the caller's own queued slot (no slot argument), so FR-005 holds without a not-owner path.

**Independent Test**: Bump a non-top own slot → single swap, 1 coin deducted, displaced game one
lower; bump at top / unaffordable → no change.

### Tests for User Story 4 ⚠️

- [ ] T046 [P] [US4] Service test `BumpGameServiceTest` (swap, at-top, no-queued, insufficient,
  duplicate) in `src/test/java/bot/application/queue/BumpGameServiceTest.java`.
- [ ] T047 [P] [US4] Testcontainers test that a bump is a single swap preserving all other positions
  and posts a `QUEUE_BUMP` `MEMBER↔POT` movement, in
  `src/test/java/bot/infrastructure/persistence/queue/BumpSwapIT.java`.

### Implementation for User Story 4

- [ ] T048 [US4] Implement `BumpGameService` (`@Transactional`; lock queue→account; `bumpSwap` via
  `QueueOrderingPolicy`; `coins_spent += bumpCost`; `QUEUE_BUMP` spend) + `BumpGameRequest`/`Result` in
  `src/main/java/bot/application/queue/BumpGameService.java`.
- [ ] T049 [US4] Implement the thin `BumpCommand` (`/queue-bump`) in
  `src/main/java/bot/discord/command/BumpCommand.java`, and add bump keys to
  `queue-messages.properties`.

**Checkpoint**: Coins buy position by exactly one swap; `./mvnw verify` green.

---

## Phase 7: User Story 5 — Upvote a queued slot (Priority: P4)

**Goal**: A button toggles a one-or-zero upvote per member per slot, idempotent across renders; the
single latest announcement message is the live count surface; ephemeral views stay snapshots.

**Independent Test**: Press upvote → +1; press again → back; press across two renders → counted once;
with a channel configured, only the latest announcement's counts update.

### Tests for User Story 5 ⚠️

- [ ] T050 [P] [US5] Testcontainers test `JpaUpvoteAdapter` toggle/count with
  PK(slot, member, game_instance_id) idempotency (duplicate press = no-op), count scoped to the
  current instance, and **replace-invalidates-upvotes (U3)** — after `replaceGame` mints a new instance,
  the count is 0 and a stray old-instance row is never counted yet the member can re-vote on the new
  instance — in `src/test/java/bot/infrastructure/persistence/queue/JpaUpvoteAdapterTest.java`.
- [ ] T051 [P] [US5] Service test `UpvoteServiceTest` (changed flag, new count, live-surface ref only
  when a channel is configured; no-op when unchanged; **`STALE` outcome (U3)** when the press's
  `gameInstanceId` ≠ the slot's current instance — no write, no announcement edit) in
  `src/test/java/bot/application/queue/UpvoteServiceTest.java`.

### Implementation for User Story 5

- [ ] T052 [US5] Add the `ButtonHandler` interface and `ButtonInteractionRouter extends ListenerAdapter`
  (dispatch `onButtonInteraction` by `componentId` prefix; gated by `discord.enabled`; registered as a
  JDA listener alongside `InteractionRouter`) in `src/main/java/bot/infrastructure/discord/`.
- [ ] T053 [US5] Implement `JpaUpvoteAdapter.toggle(slotId, memberId, gameInstanceId)` /
  `count(slotId, gameInstanceId)` (insert `ON CONFLICT DO NOTHING` / delete; count filtered to the
  given instance) in `src/main/java/bot/infrastructure/persistence/queue/JpaUpvoteAdapter.java`.
- [ ] T054 [US5] Implement `UpvoteService` (`@Transactional`; `currentInstance` stale-button guard →
  `STALE`; toggle + count by instance; return `AnnouncementRef` when a live surface exists and the vote
  changed) + `ToggleUpvoteRequest`(incl. `UUID gameInstanceId`)/`Result` in
  `src/main/java/bot/application/queue/UpvoteService.java`.
- [ ] T055 [US5] Implement `JdaAnnouncementAdapter.edit` (update only the latest announcement message's
  counts — FR-038) in `src/main/java/bot/infrastructure/discord/JdaAnnouncementAdapter.java`.
- [ ] T056 [US5] Implement the thin `UpvoteButtonHandler` (`ButtonHandler` prefix `upvote:`; parse
  `slotId` and `gameInstanceId` from the `upvote:{slotId}:{gameInstanceId}` component id; `deferEdit()`
  ack without re-rendering the ephemeral; delegate; trigger the announcement edit only when the vote
  changed) in `src/main/java/bot/discord/command/UpvoteButtonHandler.java`. Ensure `QueueViewCommand`
  (T045) renders each upvote button with that instance-encoded id.

**Checkpoint**: Upvotes toggle idempotently; the latest announcement is the live surface; `./mvnw verify` green.

---

## Phase 8: Configuration command & Polish (Cross-Cutting)

**Purpose**: Admin config surface, docs, and full validation.

- [ ] T057 [P] Implement `ConfigureQueueService` (authorize on the **Manage Server** permission:
  `ConfigureQueueRequest.actorHasManageServer`, else throw `NotAuthorizedException` — no coin-moderator
  role coupling, no Administrator/owner requirement, no role-not-configured path; set costs /
  announcement channel) + `ConfigureQueueRequest`/`QueueConfigResult` in
  `src/main/java/bot/application/queue/ConfigureQueueService.java`, with a service test (authorized
  vs unauthorized) in `src/test/java/bot/application/queue/ConfigureQueueServiceTest.java`.
- [ ] T058 [P] Implement the thin `QueueConfigCommand` (`/queue-config` subcommands `costs` /
  `announce` / `announce-clear`, `DefaultMemberPermissions.enabledFor(MANAGE_SERVER)`; pass
  `actorHasManageServer = member.hasPermission(Permission.MANAGE_SERVER)` into the request so the
  Discord-layer filter and the service check agree) in
  `src/main/java/bot/discord/command/QueueConfigCommand.java`.
- [ ] T059 [P] Update `src/test/java/bot/discord/command/CommandSurfaceTest.java` to assert the new
  `/queue-*` commands (and the button router) register without collision.
- [ ] T060 [P] Update `CLAUDE.md` Build/Test/Run + Source-Layout notes for the queue feature (privileged
  intents prerequisite, IGDB env vars) and add the two IGDB env passthroughs (`IGDB_CLIENT_ID`,
  `IGDB_CLIENT_SECRET`) to `compose.yaml` and `compose.prod.yaml`.
- [ ] T061 Run the `quickstart.md` validation scenarios (US1–US5 + cooldown + art) and fix any gaps.
- [ ] T062 Run `./mvnw spotless:apply` then `./mvnw -q verify`; surface any failure verbatim.

---

## Dependencies & Execution Order

### Phase dependencies
- **Setup (P1)**: no dependencies.
- **Foundational (P2)**: depends on Setup — **blocks all stories** (migration, domain, ports, entities,
  POT ledger).
- **US1 (P3)** → after Foundational. **US2 (P4)** → after Foundational (reuses US1's
  rotation-state/cooldown adapters; pop logic builds on US1's `JpaQueueAdapter`). **US3 (P5)**,
  **US4 (P6)**, **US5 (P7)** → after Foundational; independently testable.
- **Polish (P8)** → after the stories you intend to ship.

### Story dependencies / independence
- **US1** is standalone (the entry point). **US2** needs the queue adapter from US1 (`top`,
  `markPlayed`, `shiftUp`) — implement US1 first for the MVP. **US3/US4/US5** each depend only on
  Foundational + US1's queue adapter and are independently testable. US5's live announcement edit
  reuses US2's `JdaAnnouncementAdapter` (degrades gracefully if no channel configured).
- **Announcement enablement (C1)**: US2 posts announcements only once a channel is set, and the
  command that sets it (`/queue-config announce`, T057/T058) ships in Phase 8. This is intentional —
  announcements are opt-in and silent by default (FR-036/037), so the MVP is unaffected. Pull the
  `announce` subcommand forward if an MVP announcement demo is needed (it needs only T037's adapter).

### Within a story
Tests first (must fail) → pure domain → adapters → application service → thin handler → i18n.

---

## Parallel Execution Examples

```text
# Phase 2 Foundational — independent files in parallel:
T007 LedgerAccount POT | T008 domain value objects | T009 exceptions | T010 ports | T012 entities | T013 QueueMessages

# US1 tests (write first, in parallel):
T014 QueueOrderingPolicyTest | T015 CooldownPolicyTest | T016 JpaQueueAdapterTest | T017 QueuePotLedgerTest | T018 ProposeGameServiceTest | T019 WithdrawGameServiceTest

# US1 independent implementations in parallel before the services:
T020 QueueOrderingPolicy | T021 CooldownPolicy | T023 JpaQueueConfigAdapter | T024 cooldown/rotation adapters | T025 JpaUpvoteAdapter.resetForSlot
```

---

## Implementation Strategy

### MVP first (US1 + US2)
1. Phase 1 Setup → Phase 2 Foundational.
2. Phase 3 (US1: propose/replace/withdraw) → **STOP & validate** independently.
3. Phase 4 (US2: weekly rotation + catch-up) → **STOP & validate** → this is the demoable MVP
   ("everything proposed is eventually played").

### Incremental delivery
Add US3 (view + art) → US4 (bump) → US5 (upvote + live announcement) → Polish (config + docs +
quickstart). Each story is independently testable and keeps `./mvnw verify` green.

---

## Notes
- `[P]` = different files, no incomplete dependency. Story labels map tasks to spec.md user stories.
- Tests are included per Constitution Principle VI; verify tests **fail** before implementing.
- Run `./mvnw -q verify` after each task; never run tests in a container; no secrets required.
- V2 migration is immutable — all schema lives in V3. Postgres 17 only. Config stays in
  `application.yml`; IGDB credentials are env-var secrets, never committed.
- Commit after each task or logical group (the `after_tasks`/`after_implement` git hooks are enabled).
- **Command registration (G1)**: each `/queue-*` handler implements the existing `SlashCommandHandler`
  SPI and is **auto-registered** by `SlashCommandRegistrar` (it injects `List<SlashCommandHandler>` and
  upserts every bean's `commandData()` per guild) — so no dedicated registration task exists; the
  upvote button registers via `ButtonInteractionRouter` (T052), and T059 asserts the whole surface
  registers without collision.
