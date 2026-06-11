# Phase 1 Data Model: Game Queue & Weekly Rotation

Adds the per-server queue, rotation clock, cooldown, upvotes, queue config, and a cover-art cache —
all in a new, additive migration **`V3__game_queue.sql`** (V1/V2 untouched). Coin movement **reuses**
the V2 ledger; V3 only **extends** two of its CHECK constraints. Leans on Postgres (partial unique
indexes, `ON CONFLICT`, `pg_advisory_xact_lock`, `jsonb`) per Principles I & IV.

All ids are Discord snowflakes (`bigint`). Times are `timestamptz` (stored as `Instant`). Costs are
whole positive `int`. Per Principle II, the domain types below are **pure Java** (no framework
imports); the entities are JPA in `bot.infrastructure.persistence.queue`.

---

## Domain types (`bot.domain.queue`) — pure Java

### `CapturedGame` (record)
The Rich-Presence snapshot taken at propose time (FR-026).

| Field | Type | Notes |
|-------|------|-------|
| `applicationId` | `Long` (nullable) | Discord application/game id when available (often null for other users). |
| `name` | `String` | Activity name — always present; the reliable identity input. |
| `details` | `String` (nullable) | RP "details" line. |
| `state` | `String` (nullable) | RP "state" line. |
| `largeImageUrl` | `String` (nullable) | Resolved large asset URL if exposed. |
| `smallImageUrl` | `String` (nullable) | Resolved small asset URL if exposed. |
| `rawJson` | `String` (nullable) | Full snapshot serialized for future matching (stored as `jsonb`). |

- `GameIdentity.of(CapturedGame)` → `applicationId != null ? "app:"+applicationId : "name:"+normalize(name)`,
  where `normalize` = lowercase, trim, collapse whitespace, strip trailing launcher tags (best-effort).

### `GameIdentity` (record) — `String value`
Cache + cooldown/dedup key. Stable per game where an application id exists; best-effort by name otherwise.

### `QueueSlot` (record)
`id, guildId, proposerMemberId, game (CapturedGame), identity (GameIdentity), gameInstanceId (UUID),
position (Integer, null when PLAYED), status (QUEUED|PLAYED), coinsSpent (int), upvoteCount (int),
playedWeek (Integer)`.

> **`gameInstanceId` vs `identity`** — distinct on purpose. `identity` (`GameIdentity`) is the *which
> game* key (Discord `application_id` else normalized name) used for the cover-art cache and cooldown.
> `gameInstanceId` is an **application-generated `UUID`** identifying *this specific appearance* of a
> game in a slot. It is minted when the slot is created and **regenerated on replace** (FR-034); it is
> the binding target for upvotes (FR-030), so a replaced/re-proposed game starts a fresh count and a
> stale upvote button cannot affect the new game (see Upvote below; closes analysis finding U3).

### `QueueView` (record)
`currentGame (Optional<QueueSlot>), nextFive (List<QueueSlot>), ownEntry (Optional<QueueSlot>),
eligibleToPropose (boolean), gamesRemaining (int)` — assembled by `ViewQueueService`.

### Domain services (pure, unit-tested without a DB)
- **`QueueOrderingPolicy`** — `appendPosition(currentMax)`, `bumpSwap(slot, above)` (validate not top,
  ownership), `shiftUpAfterPop(slots)`. Pure list/position arithmetic.
- **`CooldownPolicy`** — `nReached(otherQueuedCount)`, `eligible(hasQueued, gamesRemaining)`,
  `decrement(gamesRemaining)`.
- **`RotationPolicy`** — `advancesDue(lastPopAt, now)` = `floor((now − lastPopAt)/7d)`,
  `nextPopAt(lastPopAt, periods)`.
- **`QueueLedgerPolicy`** — builds **balanced** `PostingPlan`s reusing `bot.domain.coin`:
  - `planSpend(memberId, cost)` → `MEMBER −cost`, `POT +cost`; throws `InsufficientCoinsException`
    when `balance < cost` (checked by the service before building, or passed in).
  - `planRefund(memberId, amount)` → `MEMBER +amount`, `POT −amount`.

### Outbound ports (interfaces, pure) — implemented in infrastructure
```text
interface QueuePort {
  void lockQueue(long guildId);                                  // pg_advisory_xact_lock(hash(guild, QUEUE_SALT))
  List<QueueSlot> queued(long guildId);                          // ordered by position
  Optional<QueueSlot> ownQueued(long guildId, long memberId);
  Optional<QueueSlot> findByProposeInteraction(long interactionId);   // idempotency
  QueueSlot append(NewSlot slot);                                // NewSlot carries a fresh gameInstanceId; ON CONFLICT(propose_interaction_id) DO NOTHING
  void replaceGame(long slotId, CapturedGame game, GameIdentity id, UUID newInstanceId);  // keeps position; new instance ⇒ upvotes reset
  UUID currentInstance(long slotId);                             // the slot's live gameInstanceId (for stale-button checks)
  void withdraw(long slotId);                                    // delete slot (still QUEUED only)
  Optional<QueueSlot> top(long guildId);                         // smallest position, QUEUED
  void markPlayed(long slotId, int week);                        // status=PLAYED, position=null, played_week=week
  void shiftUp(long guildId);                                    // close the gap after a pop
  int otherQueuedCount(long guildId, long excludingSlotId);      // N for cooldown
}
interface QueueConfigPort {
  GuildQueueConfig get(long guildId);                            // defaults: propose=1, bump=1, no channel
  void upsertCosts(long guildId, Integer proposeCost, Integer bumpCost);
  void setAnnouncementChannel(long guildId, Long channelId);     // null clears
  void setLatestAnnouncement(long guildId, long channelId, long messageId);
}
interface UpvotePort {
  boolean toggle(long slotId, long memberId, UUID gameInstanceId);   // one row per (slot, member, instance); true if state changed
  int count(long slotId, UUID gameInstanceId);                   // counts only the CURRENT instance's upvotes
  void resetForSlot(long slotId);                                // optional cleanup of prior-instance rows on replace
}
interface RotationStatePort {
  RotationState get(long guildId);                               // current slot, week, lastPopAt
  void bootstrap(long guildId, long slotId, Instant at);         // first instant-pop
  void recordDesignation(long guildId, int week, Long slotId, GameIdentity id, Instant at); // ON CONFLICT DO NOTHING
  void advanceClock(long guildId, Long currentSlotId, int week, Instant lastPopAt);
}
interface CooldownPort {
  int gamesRemaining(long guildId, long memberId);               // 0 if no row
  void set(long guildId, long memberId, int n);
  void decrementAll(long guildId);                               // games_remaining = max(0, n-1)
}
interface ArtCachePort {
  Optional<ArtEntry> lookup(GameIdentity id);
  void store(GameIdentity id, String imageUrlOrNull, ArtSource source);  // NONE marks a cached miss
}
interface ArtResolverPort {                                       // outbound IGDB (infra)
  Optional<String> resolveCover(GameIdentity id, String name);    // empty on miss/failure/disabled
}
interface AnnouncementPort {                                      // outbound JDA (infra)
  long post(long guildId, long channelId, AnnouncementView view); // returns message id
  void edit(long guildId, long channelId, long messageId, AnnouncementView view);
}
```
Ports use only domain/JDK types → the application depends inward only (Principle II).

### Reused coin domain (modified additively)
- **`LedgerAccount`** enum gains **`POT`** (alongside `MEMBER`, `TREASURY`, `FORFEIT`).
- **`CoinLedgerPort`** (existing) is reused as-is for `lockAccount`, `currentBalance`,
  `findByInteractionId`, `append`. `NewMovement.type` carries the new queue types (strings below).

---

## Persistence entities & tables (`bot.infrastructure.persistence.queue`)

### `guild_queue_config` *(MUTABLE configuration)*
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `guild_id` | `bigint` | PRIMARY KEY | Server scope. |
| `propose_cost` | `int` | NOT NULL DEFAULT 1, CHECK (`> 0`) | FR-025/017. |
| `bump_cost` | `int` | NOT NULL DEFAULT 1, CHECK (`> 0`) | FR-025/017. |
| `announcement_channel_id` | `bigint` | NULL | Unset ⇒ silent rotation (FR-037). |
| `latest_announcement_channel_id` | `bigint` | NULL | Where the live message lives. |
| `latest_announcement_message_id` | `bigint` | NULL | The single live count surface (FR-038). |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |

### `queue_entry` *(the slot — queued or played)*
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `bigserial` | PRIMARY KEY | |
| `guild_id` | `bigint` | NOT NULL | |
| `proposer_member_id` | `bigint` | NOT NULL | Attribution retained even if the proposer leaves the server (departure edge case); rendering always uses this stored id, never live membership. |
| `status` | `text` | NOT NULL, CHECK in (`'QUEUED'`,`'PLAYED'`) | |
| `position` | `int` | NULL | Set iff QUEUED; tail = max+1. |
| `game_identity` | `text` | NOT NULL | `GameIdentity.value` (which-game key: art cache + cooldown). |
| `game_instance_id` | `uuid` | NOT NULL DEFAULT `gen_random_uuid()` | App-generated id of *this appearance*; **regenerated on replace** (FR-034). Upvotes bind to it (FR-030); closes finding U3. |
| `game_name` | `text` | NOT NULL | Display name. |
| `application_id` | `bigint` | NULL | When Rich Presence exposed it. |
| `rp_details` | `text` | NULL | |
| `rp_state` | `text` | NULL | |
| `rp_large_image` | `text` | NULL | Captured asset URL (art chain step 1). |
| `rp_small_image` | `text` | NULL | |
| `snapshot` | `jsonb` | NULL | Full Rich-Presence snapshot. |
| `coins_spent` | `int` | NOT NULL DEFAULT 0, CHECK (`>= 0`) | propose + Σ bumps; the refund amount on withdraw (FR-033). |
| `propose_interaction_id` | `bigint` | NOT NULL **UNIQUE** | Idempotency (FR-015). |
| `played_week` | `int` | NULL | Set when PLAYED (FR-022). |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |

**Indexes / constraints**:
- `UNIQUE (guild_id, proposer_member_id) WHERE status = 'QUEUED'` — at most one queued slot per member (FR-003).
- `UNIQUE (guild_id, position) WHERE status = 'QUEUED'` — unambiguous total order / single top (FR-010/021).
- `INDEX (guild_id, status, position)` — queue reads and `top()`.

### `queue_upvote` *(mutable social state — NOT the coin ledger)*
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `slot_id` | `bigint` | NOT NULL, REFERENCES `queue_entry(id)` ON DELETE CASCADE | |
| `member_id` | `bigint` | NOT NULL | |
| `game_instance_id` | `uuid` | NOT NULL | The slot appearance this vote was cast for (mirrors `queue_entry.game_instance_id`). |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |
| | | **PRIMARY KEY (`slot_id`, `member_id`, `game_instance_id`)** | One-or-zero per member per **appearance**; idempotent toggle (FR-030/031). |

Count = `COUNT(*) WHERE slot_id = ? AND game_instance_id = <current>` — only the slot's **current**
appearance is counted, so a replace (new instance) shows zero without depending on a delete (FR-034),
and an upvote racing a concurrent replace lands on the old instance and is never counted (closes U3).
`resetForSlot` still deletes prior-instance rows as cleanup, but correctness no longer depends on it,
and the member can re-vote on the new instance without a primary-key collision. Principle III governs
the **coin** ledger only; upvotes are deletable social state.

### `queue_rotation_state` *(per-guild rotation clock — MUTABLE)*
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `guild_id` | `bigint` | PRIMARY KEY | |
| `current_slot_id` | `bigint` | NULL, REFERENCES `queue_entry(id)` | The current week's designated slot, or none. |
| `current_week_number` | `int` | NOT NULL DEFAULT 0 | Monotonic per guild. |
| `last_pop_at` | `timestamptz` | NULL | Rolling-7-day clock; null until bootstrap. |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |

### `weekly_designation` *(rotation log — append-only audit, FR-022)*
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `bigserial` | PRIMARY KEY | |
| `guild_id` | `bigint` | NOT NULL | |
| `week_number` | `int` | NOT NULL | |
| `slot_id` | `bigint` | NULL, REFERENCES `queue_entry(id)` | NULL ⇒ empty week (no designation, FR-009). |
| `game_identity` | `text` | NULL | Snapshot of what played. |
| `game_name` | `text` | NULL | |
| `designated_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |
| | | **UNIQUE (`guild_id`, `week_number`)** | Idempotent advance (FR-016/SC-004). |

### `queue_cooldown` *("wait N games" — MUTABLE)*
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `guild_id` | `bigint` | NOT NULL | |
| `member_id` | `bigint` | NOT NULL | |
| `games_remaining` | `int` | NOT NULL, CHECK (`>= 0`) | N at pop; counts down only on real pops. |
| `set_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |
| | | **PRIMARY KEY (`guild_id`, `member_id`)** | FR-011/012, SC-005/006. |

### `game_art_cache` *(cover-art cache — MUTABLE, keyed by identity)*
| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `game_identity` | `text` | PRIMARY KEY | `applicationId` else normalized name (FR-027). |
| `image_url` | `text` | NULL | Resolved cover; null when miss. |
| `source` | `text` | NOT NULL, CHECK in (`'RICH_PRESENCE'`,`'IGDB'`,`'NONE'`) | `NONE` = cached miss ⇒ IGDB queried at most once per identity. |
| `resolved_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |

---

## V3 additive changes to the V2 ledger (V2 file untouched)

In `V3__game_queue.sql`, **after** creating the tables above. The constraint names below are the
**verified** Postgres auto-names from V2 (both are single-column inline `CHECK`s → `<table>_<column>_check`;
confirmed against `V2__coin_ledger.sql`). Note V2 also has a *table-level* check on `coin_ledger_entry`
(`(account='MEMBER') = (member_id IS NOT NULL)`) auto-named `coin_ledger_entry_check` — **different
name**, so the drop below does not touch it.

```sql
-- Add the per-server POT account used by queue spends/refunds (balanced counter-party).
ALTER TABLE coin_ledger_entry DROP CONSTRAINT coin_ledger_entry_account_check;   -- verified name
ALTER TABLE coin_ledger_entry ADD  CONSTRAINT coin_ledger_entry_account_check
    CHECK (account IN ('MEMBER','TREASURY','FORFEIT','POT'));

-- Allow the three queue economic-event types on the reused movement header.
ALTER TABLE coin_movement DROP CONSTRAINT coin_movement_type_check;              -- verified name
ALTER TABLE coin_movement ADD  CONSTRAINT coin_movement_type_check
    CHECK (type IN ('GRANT','DEDUCTION','QUEUE_PROPOSE','QUEUE_BUMP','QUEUE_REFUND'));
```

> **Migration safety (finding F1)**: the names are correct for this repo, but they are an *implicit*
> Postgres convention. T006 must re-confirm them before relying on the `DROP` — e.g.
> `SELECT conname FROM pg_constraint WHERE conrelid='coin_ledger_entry'::regclass AND contype='c';`
> A `JpaCoinLedgerTriggersTest`-style integration test already exercises these tables, so a wrong-name
> migration fails fast under Testcontainers, not in production.

The V2 **append-only** triggers (forbid UPDATE/DELETE), the **deferred balanced-movement** trigger
(`SUM(amount)=0` per movement), and the **deferred non-negative MEMBER** trigger all continue to apply
to queue postings unchanged — queue spends/refunds inherit them for free. POT is not non-negativity
checked (same as TREASURY/FORFEIT). `moderator_id` on a queue movement carries the **acting member's
own id** (self-initiated spend), so no nullability change is needed.

---

## State & lifecycle

- **Queue entry**: `QUEUED → PLAYED` (one-way). `position` is set while QUEUED, cleared at pop.
  `coins_spent` accrues on propose/bump; read once at withdraw to compute the refund. Replace edits
  game fields + identity in place (free), keeps `position`, mints a **new `game_instance_id`** (which
  resets the visible upvote count and invalidates outstanding upvote buttons for the prior game), and
  cleans up prior-instance upvote rows. A departed proposer's row is never removed and is always
  rendered by its stored `proposer_member_id`.
- **Rotation state**: `last_pop_at` advances by exactly 7 d per applied period; `current_slot_id`/
  `current_week_number` track the live game. Bootstrap sets them from the first proposal.
- **Cooldown**: set to N at the proposer's pop; decremented on every real pop (guild-wide); reaches 0
  → eligible. Empty weeks never decrement.
- **Art cache**: write-once per identity until (optional, out-of-scope) TTL refresh; a `NONE` row
  prevents repeat IGDB calls.
- **Coin movement/entries**: write-once (V2 invariant); refunds are **new** reversing movements,
  never edits.

## Validation & invariants (summary)

- One queued slot per member (FR-003) — partial unique index.
- Single unambiguous top / total order (FR-010/021) — partial unique `(guild_id, position)`.
- At-most-once propose/bump/withdraw (FR-015) — `propose_interaction_id` UNIQUE + reused
  `coin_movement.interaction_id` UNIQUE.
- Affordability atomic, nothing on failure (FR-002, SC-002) — queue+account advisory locks, policy
  throws `InsufficientCoinsException` → rollback.
- Spend/refund balanced & append-only (FR-001/023/033) — `QueueLedgerPolicy` builds zero-sum plans;
  V2 triggers enforce; refunds reverse, never mutate.
- Deterministic, idempotent weekly advance (FR-008/009/016, SC-004) — `top()` + `weekly_designation`
  UNIQUE(guild, week) ON CONFLICT DO NOTHING.
- "Wait N games" fixed at pop, counts only played games (FR-011/012, SC-005/006) — `queue_cooldown`
  set at pop, `decrementAll` on real pops only.
- Downtime catch-up exactly once per due period (FR-032, SC-014) — `RotationPolicy.advancesDue` +
  idempotent designations.
- Upvote one-or-zero, idempotent across renders, reset on replace, no cross-appearance leak
  (FR-030/031/034; closes U3) — PK(slot, member, `game_instance_id`), ON CONFLICT, count scoped to the
  current instance, instance regenerated on replace.
- Per-server isolation (FR-020) — every table keyed by `guild_id`; asserted by a cross-guild
  integration test (two guilds' queues/cooldowns/rotation never interfere).
- Proposer departure never removes a slot (departure edge case) — `queue_entry` rows persist; all
  rendering uses the stored `proposer_member_id`, independent of live membership.
- Art never blocks/fails propose (FR-027) — resolution at render only, best-effort, cached miss.
