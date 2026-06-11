# Contract: Application Services (Game Queue)

Every public method **takes a request record and returns a result record**, is the **only** place
opening a transaction / calling ports (Principle II), and signals rule violations with typed
`DomainException`s (rollback ⇒ nothing changes). Records are immutable; ids are `long`; amounts `int`.

Lock order in every mutating service: **queue lock → account lock** (see research §4), inside one
`@Transactional` unit.

## `ProposeGameService.propose` (US1, P1)
`@Transactional`.

**Request** `ProposeGameRequest(long guildId, long memberId, CapturedGame game /*nullable*/, long interactionId)`

**Result** `ProposeGameResult(Outcome outcome, int position, boolean instantPop, int coinsSpent, int newBalance)`
where `Outcome ∈ {PROPOSED, INSTANT_POPPED, REPLACED, DUPLICATE, NO_ACTIVITY, INSUFFICIENT, NOT_ELIGIBLE}`.

**Algorithm**:
1. **No-activity guard**: `game == null` → return `NO_ACTIVITY` (no lock, no charge — FR-035).
2. `queuePort.lockQueue(guildId)`.
3. **Idempotency**: `queuePort.findByProposeInteraction(interactionId)` present → `DUPLICATE`.
4. **Replace branch**: if `queuePort.ownQueued(guildId, memberId)` present → mint a fresh
   `newInstanceId = UUID.randomUUID()`, `replaceGame(slotId, game, identity, newInstanceId)`,
   `upvotePort.resetForSlot(slotId)`, return `REPLACED` (free, keeps position — FR-034). The new
   instance id alone resets the visible count and invalidates outstanding upvote buttons for the old
   game. *(No charge, no eligibility/affordability check; naturally idempotent — a duplicate replace
   yields the same game and a fresh-but-empty count.)*
5. **New-proposal branch** — eligibility: `cooldownPort.gamesRemaining(guildId, memberId) == 0`
   (and step 4 already proved no queued slot) else `NOT_ELIGIBLE` (FR-011).
6. `coinLedgerPort.lockAccount(guildId, memberId)`; `balance = currentBalance`. Load `proposeCost`.
   If `balance < proposeCost` → `InsufficientCoinsException` (→ rollback) / `INSUFFICIENT`.
7. **Bootstrap instant-pop** (FR-024): if `rotationState.current_slot_id` is null **and** queue empty:
   create slot already `PLAYED` (week 0) with a fresh `game_instance_id`, `recordDesignation`,
   `rotationStatePort.bootstrap(...)`, `cooldownPort.set(guildId, memberId, 0)`, post `QUEUE_PROPOSE`
   spend, return `INSTANT_POPPED`.
8. **Normal**: `append(NewSlot status=QUEUED, position=tail, coinsSpent=proposeCost,
   gameInstanceId=UUID.randomUUID(), propose_interaction_id=interactionId, …)`; post `QUEUE_PROPOSE`
   spend (`MEMBER −cost / POT +cost`) via `coinLedgerPort.append` (interaction-id idempotent); return
   `PROPOSED` with position & newBalance.

*Maps to*: FR-001/002/003/015/019/023/024/026/034/035; US1 scenarios 1–7.

## `WithdrawGameService.withdraw` (US1, P1)
`@Transactional`.

**Request** `WithdrawGameRequest(long guildId, long memberId, long interactionId)`
**Result** `WithdrawGameResult(Outcome outcome, int refunded, int newBalance)` — `{WITHDRAWN, NO_QUEUED, DUPLICATE}`.

**Algorithm**: lock queue; find own QUEUED slot (else `NO_QUEUED`); idempotency by `interactionId`
on the refund movement; lock account; `refund = slot.coinsSpent`; `queuePort.withdraw(slotId)` +
`shiftUp`; post `QUEUE_REFUND` (`MEMBER +refund / POT −refund`). A played slot cannot be withdrawn.

*Maps to*: FR-033; SC-015; US1 scenario 7.

## `BumpGameService.bump` (US4, P4)
`@Transactional`.

**Request** `BumpGameRequest(long guildId, long memberId, long interactionId)`
**Result** `BumpGameResult(Outcome outcome, int newPosition, int newBalance)` —
`{BUMPED, AT_TOP, NO_QUEUED, INSUFFICIENT, DUPLICATE}`.

**Algorithm**: lock queue; find own QUEUED slot (else `NO_QUEUED`); if at top → `AT_TOP` (FR-006,
nothing charged); idempotency by `interactionId`; lock account; if `balance < bumpCost` →
`InsufficientCoinsException`/`INSUFFICIENT`; `bumpSwap` with the slot directly above (single swap,
FR-004/005/007); `coins_spent += bumpCost`; post `QUEUE_BUMP` spend. Bumping another's game is
impossible (acts only on caller's own slot).

*Maps to*: FR-004/005/006/007/015; SC-009; US4 scenarios 1–4.

## `UpvoteService.toggle` (US5, P4)
`@Transactional`.

**Request** `ToggleUpvoteRequest(long guildId, long memberId, long slotId, UUID gameInstanceId, long interactionId)`
— `gameInstanceId` is the appearance the button was rendered for (encoded in its component id).
**Result** `ToggleUpvoteResult(Outcome outcome, boolean changed, int newCount, Optional<AnnouncementRef> liveSurface)`
where `Outcome ∈ {TOGGLED, STALE, NO_SLOT}`.

**Algorithm**:
1. `current = queuePort.currentInstance(slotId)` (or `NO_SLOT` if the slot is gone/played).
2. **Stale-button guard** (FR-030; closes U3): if `current != request.gameInstanceId`, the game on
   this slot changed (replaced) or the appearance ended → return `STALE` with `changed = false`,
   **no write** (the button referenced a prior appearance).
3. `changed = upvotePort.toggle(slotId, memberId, gameInstanceId)` — PK(slot, member, instance) +
   ON CONFLICT ⇒ idempotent across multiple ephemeral renders (FR-031); `newCount =
   upvotePort.count(slotId, gameInstanceId)` (current appearance only).
4. If `changed` and the guild has a `latest_announcement_message_id`, return that ref so the handler
   edits the **single live surface** (FR-038); otherwise empty. Never re-renders ephemeral views
   (FR-029). Upvotes never affect order (FR-030).

Because the toggle is keyed by the instance and the count is scoped to the current instance, an upvote
that races a concurrent replace lands on the old instance and is never counted, and the member can
still vote on the new appearance without a key collision — the U3 race is structurally impossible.

*Maps to*: FR-029/030/031/038; SC-013; US5 scenarios 1–5.

## `ViewQueueService.view` (US3, P3)
`@Transactional(readOnly = true)`.

**Request** `ViewQueueRequest(long guildId, long memberId)`
**Result** `QueueView(Optional<QueueSlot> currentGame, List<QueueSlot> nextFive,
Optional<QueueSlot> ownEntry, boolean eligibleToPropose, int gamesRemaining)` — each shown slot
carries its **snapshot** upvote count and a resolved cover image (via the art chain; see
`ledger-and-art.md`). Own entry always included/marked, even beyond top 5 (FR-028, SC-012).

*Maps to*: FR-013/014/027/028/029; US3 scenarios 1–5.

## `ConfigureQueueService.configure` (config)
`@Transactional`.

**Request** `ConfigureQueueRequest(long guildId, boolean actorHasManageServer,
Integer proposeCost /*nullable*/, Integer bumpCost /*nullable*/, ChannelOp announcement /*nullable*/)`
where `ChannelOp` is `SET(channelId)` or `CLEAR`.
**Result** `QueueConfigResult(int proposeCost, int bumpCost, Long announcementChannelId)`.

**Algorithm**: authorize on the **Manage Server** permission — `if (!actorHasManageServer) throw new
NotAuthorizedException()`. This is the single authoritative bar and it matches the command's
`DefaultMemberPermissions.enabledFor(MANAGE_SERVER)`, so the Discord-layer filter and the service
check never disagree (no coin-moderator-role coupling, no Administrator/owner requirement, and no
role-not-configured precondition). Then validate costs `≥ 1` when present; `upsertCosts` /
`setAnnouncementChannel`. Changes apply to subsequent actions only (FR-017/018/037).

## `AdvanceRotationService` (US2, P2) — scheduler-driven, not a slash command
`@Transactional` per applied advance.

**`advanceDue(long guildId, Instant now)` → `AdvanceResult(int advancesApplied, Optional<AnnouncementView> finalAnnouncement)`**
1. `queuePort.lockQueue(guildId)`; load `rotationState`.
2. `periods = RotationPolicy.advancesDue(rotationState.lastPopAt, now)`; if 0 → no-op.
3. For each period 1..periods (each idempotent via `weekly_designation` UNIQUE(guild, week)):
   - `week = current_week_number + 1`; `top = queuePort.top(guildId)`.
   - If `top` present: `markPlayed(top, week)`, `shiftUp`, `recordDesignation(week, top, …)`,
     `cooldownPort.set(top.proposer, N = otherQueuedCount(guildId, top.id))`,
     `cooldownPort.decrementAll(guildId)` *(this played game counts down others — FR-012)*,
     set `current_slot_id = top`.
   - Else (empty queue): `recordDesignation(week, slotId=null, …)`, `current_slot_id = null`,
     **no** cooldown decrement (empty week — FR-009/012).
   - `advanceClock(guildId, current_slot_id, week, lastPopAt += 7d)`.
4. If an announcement channel is configured and any non-empty designation occurred, return the
   **final** current game's `AnnouncementView` (post once — FR-036; downtime ⇒ no backlog spam).

**`catchUpAll(Instant now)`** — iterate guilds with rotation state, call `advanceDue`. Invoked by the
`@Scheduled` tick and once on `ApplicationReadyEvent` (FR-032, SC-014).

*Maps to*: FR-008/009/010/011/012/016/022/032/036; SC-001/004/005/006/014/016; US2 scenarios 1–6.

## Error model (`bot.domain.queue`, extends the shared `DomainException`)
`InsufficientCoinsException`, `NotEligibleException(int gamesRemaining)`, `NoQueuedGameException`,
`NoGameActivityException`, `NotAuthorizedException` (raised by `ConfigureQueueService` when the actor
lacks Manage Server). There is **no** `AlreadyAtTopException` or `NotSlotOwnerException` — `AT_TOP` is a
result `Outcome`, and acting only on the caller's own slot makes a not-owner case impossible (FR-005
holds by construction). Throwing aborts the
transaction ⇒ the spec's "fails atomically and changes nothing." (No-activity, at-top, no-queued, and
duplicate are also representable as result `Outcome`s where the handler renders guidance without an
exception — implementer's choice, but a charge/mutation must never occur in those paths.)
