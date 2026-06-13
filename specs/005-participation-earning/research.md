# Phase 0 — Research: Participation Earning

All decisions below resolve the spec's deferred (planning-level) items and the design unknowns. Each
is **Decision / Rationale / Alternatives considered**. They reuse the conventions already proven by
features 002 (coin ledger) and 004 (queue/rotation, scheduler, presence capture).

## 1. Earning measurement: time-sampled sweep vs event-driven sessions

- **Decision**: A background `@Scheduled` **sweep** (`ParticipationScheduler`, gated by
  `discord.enabled`) runs every `participation.sweep.tick` (default `PT1M`). Each tick it reads, from
  JDA's in-memory cache, the members currently connected to each guild's designated voice channels who
  are playing that guild's current designated game, and calls `AccrueParticipationService.accrue` once
  per such member. There are **no** voice/presence event listeners for earning.
- **Rationale**: Sampling current state is inherently restart-safe and satisfies the hard
  requirements without bookkeeping gymnastics: while the bot is down **no ticks fire**, so missed time
  is never credited (FR-023); there is **no event stream to replay**, so the same span is never
  double-credited (FR-009); and the per-member `banked_seconds` carries the sub-drop remainder
  indefinitely (clarification: persist indefinitely). It mirrors the existing `RotationScheduler`
  (`@Scheduled(fixedDelayString=…)` + `ApplicationReadyEvent`), so the pattern, the `discord.enabled`
  gate, and the "tests schedule nothing" property are reused verbatim.
- **Alternatives considered**: Event-driven session tracking (subscribe to
  `GuildVoiceUpdateEvent` + `UserActivity*`/presence events, open a session on entering the qualifying
  state, accrue on exit). Rejected: it must persist/replay in-flight sessions across restarts and
  reconcile dropped gateway events to avoid losing or double-counting time — far more complex for a
  deliberately coarse flat rate. Trade-off accepted for the sweep: crediting granularity equals the
  tick interval, and a session edge can over/under-credit by ≤ one interval (see §3).

## 2. Crediting model: drops, banked seconds, and reusing `planGrant`

- **Decision**: Accrual is tracked per `(guild, member)` as `banked_seconds`. A drop completes when
  `banked_seconds >= minutes_per_drop * 60`; minting a drop credits `coins_per_drop` coins through the
  **existing** `CoinLedgerPolicy.planGrant(guild, member, balance, coinsPerDrop, cap)` and the existing
  `CoinLedgerPort.append`, with the new movement type `AdjustmentType.PARTICIPATION`. `planGrant`
  already credits up to the cap and posts the over-cap remainder as a `TREASURY→FORFEIT` line, so cap
  forfeiture (FR-005) is reused with zero new arithmetic. Each minted drop subtracts
  `minutes_per_drop * 60` from `banked_seconds`.
- **Rationale**: The participation credit is economically identical to a moderator **grant** (mint
  from the per-guild `TREASURY`, cap-truncated, forfeiture recorded) — only the movement *type* and
  the *trigger* differ. Reusing `planGrant` keeps one cap rule and one forfeiture rule across the whole
  economy (Principle III) and means the history view already carries `credited`/`forfeited` for US3.
- **Alternatives considered**: A bespoke participation posting policy (rejected: duplicates the cap
  math and risks divergence). Crediting fractional coins (rejected: smallest unit is 1 — FR-002 forbids
  fractional coins).

## 3. Restart safety, downtime, and session re-entry (FR-009 / FR-023)

- **Decision**: Each accrual computes `elapsed = now - last_sampled_at`, then:
  - if `last_sampled_at` is null **or** `elapsed > maxGap` (configurable
    `participation.sweep.max-gap`, default `PT2M` ≈ 2× tick): treat this as a **fresh session start** —
    accrue **0** this tick and set `last_sampled_at = now`;
  - otherwise accrue `elapsed` into `banked_seconds` and set `last_sampled_at = now`.
  The read-modify-write runs under the per-account advisory lock in one transaction.
- **Rationale**: Clamping by `maxGap` bounds any credit to ~one tick, so downtime (a long gap) credits
  nothing retroactively (FR-023), and a member who left and returns next week (stale
  `last_sampled_at`) starts cleanly rather than getting a huge erroneous credit. Advancing
  `last_sampled_at` every tick in the same transaction as the banked-seconds change makes a replay a
  no-op (FR-009): consumed seconds are gone and the timestamp has moved.
- **Alternatives considered**: Crediting the literal wall-clock delta unclamped (rejected: would
  credit downtime and re-entry gaps); a fixed "+tick" increment ignoring the real delta (rejected:
  drifts under scheduler jitter; the clamped real delta is both simpler to reason about and accurate).

## 4. Ledger idempotency key without a Discord interaction id

- **Decision**: `coin_movement.interaction_id` is `bigint NOT NULL UNIQUE`, but a participation drop
  has no Discord interaction. Drops use a **negative synthetic id** from a dedicated Postgres sequence:
  `interaction_id = -nextval('participation_drop_seq')`. Discord snowflakes are always positive
  (< 2^63), so the negative range can never collide with a real interaction id.
- **Rationale**: This satisfies the existing `NOT NULL UNIQUE` constraint with **no** change to the
  ledger schema semantics and no risk of colliding with moderator/queue movements. The interaction id
  is *not* the at-most-once mechanism here (that is the banked-seconds decrement, §3) — it only needs
  to be unique, which the sequence guarantees.
- **Alternatives considered**: Making `interaction_id` nullable + a separate participation dedup column
  (rejected: invasive change to an immutable, shipped ledger contract); reusing small positive sequence
  values (rejected: not provably disjoint from the snowflake space); a hash of `(guild, member, drop#)`
  (rejected: collision risk on a UNIQUE column). The `next_drop_id` is exposed via
  `ParticipationAccrualPort.nextDropId()` and implemented with `nextval`.

## 5. Cap-pause semantics (FR-005)

- **Decision**: At the start of each accrual, if `currentBalance >= cap`, **pause**: set
  `last_sampled_at = now`, leave `banked_seconds` unchanged, mint nothing, return. When minting a
  sequence of ready drops, mint one at a time via `planGrant`; the first drop that reaches the cap
  records its partial-credit + forfeiture (one record), after which minting stops and the leftover
  whole drops are **not** minted (they would be pure forfeiture) — `banked_seconds` is set to the
  sub-threshold remainder.
- **Rationale**: Honors "earnings beyond the cap are forfeited" while avoiding a continuous stream of
  zero/forfeiture records (the spec's explicit concern). Reaching the cap means no more coins can be
  credited anyway, so discarding the over-cap whole drops is exactly the cap rule; the single crossing
  drop documents the forfeiture. Accrual resumes automatically on a later tick once the member has
  spent coins (e.g., on the queue) and is below the cap.
- **Alternatives considered**: Banking time while at cap and minting forfeit-only drops every tick
  (rejected: forfeiture-record spam); never recording any forfeiture (rejected: violates the
  cap-forfeiture audit rule reused from feature 002).

## 6. Observing voice + presence: JDA cache and intents

- **Decision**: In `JdaConfig`, change `setMemberCachePolicy(MemberCachePolicy.NONE)` →
  `MemberCachePolicy.VOICE` and add `GatewayIntent.GUILD_VOICE_STATES`. `GUILD_PRESENCES` +
  `CacheFlag.ACTIVITY` are already enabled (feature 004). The sweep reads
  `guild.getVoiceChannelById(id).getMembers()` for each designated channel and `member.getActivities()`
  to find the first `PLAYING` activity, mapped to a `GameIdentity` via a shared `GameActivities` mapper
  (extracted from `PresenceReader.toCapturedGame`). A member qualifies iff that identity equals the
  guild's current-game identity.
- **Rationale**: `VOICE` retention bounds memory to members currently in voice (small) instead of the
  whole roster, while still exposing their activities (presence is applied to cached members when
  `GUILD_PRESENCES` is on). `GUILD_VOICE_STATES` is **non-privileged**. This is precisely the cache
  change feature 004's plan deferred to "a later spec." `PresenceReader`'s on-demand propose-time
  capture is unaffected (it uses `retrieveMembersByIds`).
- **Alternatives considered**: `MemberCachePolicy.ALL`/`ONLINE` (rejected: memory grows with
  membership); REST-fetching each voice member's presence per tick (rejected: presence cannot be
  REST-fetched and per-member fetches would be slow/rate-limited); keeping `NONE` and fetching on
  demand (rejected: cannot continuously observe many members). **Caveat noted**: a member's activity may
  briefly lag the first presence update after they enter the cache; acceptable for a coarse sweep.

## 7. "The current game" for matching

- **Decision**: A new `CurrentGamePort.currentGameIdentity(guildId)` returns
  `Optional<GameIdentity>`, implemented by joining `queue_rotation_state.current_slot_id →
  queue_entry.game_identity` (the slot designated this week). Matching reuses feature 004's
  `GameIdentity` (application id when present, else normalized name) so participation matching and queue
  capture agree, with the same best-effort cross-launcher caveat (FR-022).
- **Rationale**: A single tiny read keeps the sweep cheap and keeps participation aligned with how the
  queue already identifies games — no second notion of "same game." When the port returns empty (no
  current game), the guild is skipped entirely (FR-011).
- **Alternatives considered**: Depending on the whole `QueuePort`/`RotationStatePort` pair from the
  sweep (rejected: larger surface than needed); recomputing identity from a stored snapshot in the
  participation feature (rejected: duplicates queue state).

## 8. Authorization for participation configuration

- **Decision**: `ConfigureParticipationService` authorizes against the server's **configured coin
  moderator role**, reusing `GuildCoinConfigPort.get(guild).moderatorRoleId` and the
  `actorIsAdmin` (Discord Administrator) bypass — exactly as `AdjustCoinsService.authorize`. If no
  moderator role is configured, it fails closed (`ModeratorRoleNotConfiguredException`). The
  `/participation-config` command passes the caller's role ids + admin flag and performs the
  authoritative check in-service (the Discord `DefaultMemberPermissions` filter cannot express a
  per-guild configured role, identical to `/coins-adjust`).
- **Rationale**: The spec's clarification states "admin" = the configured economy-administration role
  (not Manage-Server, which feature 004 uses for queue costs). Reusing the coin authorization keeps one
  economy-admin concept and one fail-closed behavior.
- **Alternatives considered**: Manage-Server permission (rejected: contradicts the clarification and
  the spec's grouping of these under economy administration); a brand-new participation role
  (rejected: needless second role to configure).

## 9. Free-first-proposal hook point (US4)

- **Decision**: Store `free_first_proposal` (default false) on the new `guild_participation_config`
  row; expose `ParticipationConfigPort.freeFirstProposalEnabled(guildId)`. Modify `ProposeGameService`:
  detect the bootstrap condition (`rotation.currentSlotId == null && queued.isEmpty()`) **before**
  computing the spend; if bootstrap **and** the flag is on, **waive** — skip the account lock,
  `planSpend`, and `postSpend`, append the instant-popped slot with `coinsSpent = 0`, and leave the
  balance unchanged. Otherwise the existing affordability path runs unchanged.
- **Rationale**: The instant-pop branch's condition is already exactly the spec's waiver condition (no
  current game + empty queue), so the change is localized. Because the waiver recurs every time that
  empty state recurs (clarification), gating on the live condition + flag (not a one-time marker) is
  correct. A waived proposal posts no coin movement (cost 0), matching "free means cost = 0."
- **Alternatives considered**: A separate "bootstrap proposal" service (rejected: duplicates the
  propose path); storing the flag on `guild_queue_config` (rejected: it is administered by the
  moderator-role participation command, not the Manage-Server queue command — keeping it on the
  participation config aligns storage with its writer and its conceptual home).

## 10. History label for participation (US3)

- **Decision**: Extend `BalanceCommand.historyLine` to render `AdjustmentType.PARTICIPATION` as a
  **credit** line (`+{credited}`, with the existing `[{n} forfeited]` suffix when applicable), using a
  new `coin.reply.history.participation` i18n key — not the current GRANT/else→DEDUCTION fall-through
  (which would wrongly show participation as a deduction).
- **Rationale**: FR-007/FR-008 require participation earnings to be distinguishable from moderator
  adjustments and queue spends. The movement type already flows to the renderer via `MovementSummary`;
  only a new branch + key are needed. (The pre-existing GRANT/else fall-through means queue spends
  currently render via the deduct line; fixing that fully is out of this feature's scope, but the new
  branch ensures participation specifically is labelled correctly.)
- **Alternatives considered**: A generic per-type label map for all movement types (reasonable but
  larger than this feature needs; a `PARTICIPATION` branch is the minimal correct change for US3).

## Dependencies

No new Maven coordinate. Scheduling (`spring-context` `@EnableScheduling`) is already enabled by
feature 004's `SchedulingConfig`; the credit reuses the existing ledger; observation reuses JDA's
gateway cache. This feature therefore adds **zero** build dependencies (recorded per Constitution
"Development Workflow & Quality Gates").
