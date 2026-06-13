# Contract — Ledger Posting, Earner Set & Early-Skip Rotation

## A. Contribution posting (reuses feature 002 ledger; new SKIP_POT sink)

A contribution is a `coin_movement` of type **`SKIP_JAR`** with balanced `coin_ledger_entry` lines
from the new pure policy:

```java
// bot.domain.skipjar.SkipJarLedgerPolicy  (pure, unit-tested without a DB)
static PostingPlan planContribution(long memberId, int currentBalance) {
    if (currentBalance < 1) throw new OverdrawException(memberId, currentBalance);   // FR-006 (2-arg ctor)
    List<PostingLine> lines = List.of(PostingLine.member(memberId, -1), PostingLine.skipPot(1));
    return new PostingPlan(AdjustmentType.SKIP_JAR, /*requested*/ 1, /*credited*/ 0, /*forfeited*/ 0, lines);
}
```

```
contribute:   MEMBER -1 ; SKIP_POT +1            (sums to zero; member balance −1)
```

- `moderator_id` = the contributor's own id (self-initiated, mirroring queue spends).
- Lines sum to zero (V2 `coin_assert_balanced`); the member balance never goes negative (V2
  `coin_assert_non_negative` is the backstop for FR-006). Append-only triggers apply unchanged.
- **Non-refundable (FR-003)**: there is **no** `planRefund` for SKIP_JAR — coins remain in `SKIP_POT`
  permanently. No code path ever reverses a SKIP_JAR movement.
- New enum values (additive): `LedgerAccount.SKIP_POT`, `AdjustmentType.SKIP_JAR`, and the factory
  `PostingLine.skipPot(int signedAmount)`. The `coin_movement` type CHECK and `coin_ledger_entry`
  account CHECK gain `'SKIP_JAR'` / `'SKIP_POT'` in V5 (see [data-model.md](../data-model.md)).

## B. Idempotency / at-most-once

- **Ledger uniqueness**: the `SKIP_JAR` movement uses the **Discord interaction id** as
  `interaction_id` (positive snowflake). A retried interaction short-circuits via
  `CoinLedgerPort.findByInteractionId` (step 2 of the algorithm) — charged=false, prior outcome
  returned (D-7).
- **Once-per-run**: the `skip_contribution` PK `(guild_id, week_number, member_id)` (FR-002). Checked
  optimistically (step 6) and enforced by the insert (step 8); a unique violation rolls back the
  debit.

## C. Earner set (reads the existing participation ledger — no new column)

```java
// bot.domain.skipjar.EarnerStatsPort  → JpaEarnerStatsAdapter (infrastructure)
interface EarnerStatsPort {
  /** Distinct members credited ≥1 PARTICIPATION coin for the current run (created_at >= since). */
  int distinctEarnerCount(long guildId, java.time.Instant since);
  /** Whether this member is such an earner (gate-on eligibility). */
  boolean isEarner(long guildId, long memberId, java.time.Instant since);
}
```

Native Postgres queries over `coin_movement` (D-2), where `since = RotationState.lastPopAt()`:

```sql
-- distinctEarnerCount
SELECT COUNT(DISTINCT member_id) FROM coin_movement
 WHERE guild_id = ? AND type = 'PARTICIPATION' AND credited_amount > 0 AND created_at >= ?;

-- isEarner
SELECT EXISTS (SELECT 1 FROM coin_movement
                WHERE guild_id = ? AND member_id = ? AND type = 'PARTICIPATION'
                  AND credited_amount > 0 AND created_at >= ?);
```

`credited_amount > 0` enforces the clarification "a coin actually landed in their balance" (a fully
over-cap drop does not make an earner). The run boundary is the timestamp — the participation sweep
only credits the current game, so every such movement after `lastPopAt` belongs to the current run.

## D. Run / dwell read (reuses the queue rotation clock)

`ContributeToSkipJarService` and `ViewSkipJarService` read `RotationStatePort.get(guildId)` →
`RotationState(guildId, currentSlotId, currentWeekNumber, lastPopAt)`:

- `currentSlot().isEmpty()` ⇒ **no game** (FR-019 / status NO_GAME).
- `lastPopAt` ⇒ dwell baseline (`Duration.between(lastPopAt, now) >= dwell` opens the jar, FR-007) and
  the earner-set boundary (C).
- `currentWeekNumber` ⇒ the run key scoping `skip_contribution` (FR-012).

The current game's **display name** for replies comes from the queue slot —
`queuePort.findSlot(state.currentSlot().get()).game().name()` (the same source
`AnnouncementAssembler` uses). Note: `CurrentGamePort.currentGameIdentity(guildId)` returns a
`GameIdentity` *key* (`app:<id>` / `name:<norm>`), **not** a human-readable name, so it is not used
for the reply text; `RotationState.currentSlot()` empty already signals "no current game" (FR-019).

## E. Early-skip advance (reuses feature 004's deterministic advance)

`AdvanceRotationService` is refactored to share its per-period pop body, then exposes:

```java
/**
 * Force exactly ONE early pop (the skip-jar trigger), using the SAME deterministic rules as the
 * weekly advance — top → designate → shiftUp → cooldowns (decrementAll before set) — differing only
 * in that the new game's clock baseline is `now` (an early skip restarts dwell and the weekly clock).
 * Returns the new current game's announcement when a channel is configured.
 */
@Transactional
public AdvanceResult skip(long guildId, Instant now);
```

Behavior (one pop, week = currentWeekNumber + 1, popAt = `now`):

```
queuePort.lockQueue(guildId)                       // reentrant within the contribution's transaction
state = rotationPort.get(guildId)
week  = state.currentWeekNumber() + 1
top   = queuePort.top(guildId)
if top present:
    n = queuePort.otherQueuedCount(guildId, top.id())
    queuePort.markPlayed(top.id(), week)
    queuePort.shiftUp(guildId)
    rotationPort.recordDesignation(guildId, week, top.id(), top.identity(), now)
    cooldownPort.decrementAll(guildId)             // played game counts down existing cooldowns first
    cooldownPort.set(guildId, top.proposerMemberId(), n)   // then the proposer's fresh N
    rotationPort.advanceClock(guildId, top.id(), week, now)
else:
    rotationPort.recordDesignation(guildId, week, null, null, now)   // empty queue → empty week (queue owns this)
    rotationPort.advanceClock(guildId, null, week, now)
return announcement (assemble when configured & a game was designated)
```

- **No new advance behavior (FR-010)**: the pop body is the *same code* as one iteration of
  `advanceDue`'s loop (extracted into a shared private step); only the `popAt`/clock baseline differs
  (`now` instead of `lastPop + 7d`). Empty-queue handling is the queue feature's existing behavior.
- **Exactly one step (FR-011)**: `skip` performs a single pop and returns; called once by the
  threshold-meeting contribution under the queue lock.
- Reuses `AnnouncementAssembler` (same as `advanceDue`) so an early skip announces the new current
  game when a channel is configured.

## F. Domain ports (new, in `bot.domain.skipjar`)

```java
interface SkipJarConfigPort {
  GuildSkipJarConfig get(long guildId);            // defaults (3, 24h, true) when absent
  void setFloor(long guildId, int thresholdFloor);
  void setDwell(long guildId, long dwellSeconds);
  void setGate(long guildId, boolean gateOn);
}

interface SkipContributionPort {
  boolean hasContributed(long guildId, int weekNumber, long memberId);
  int count(long guildId, int weekNumber);
  void record(long guildId, int weekNumber, long memberId, long movementId);  // PK ⇒ once-per-run
}

interface EarnerStatsPort { /* see §C */ }
```

Account locking, balance, append, and history reuse the existing `CoinLedgerPort`. The current run /
dwell baseline reuses `RotationStatePort` (feature 004); the game **display name** reuses
`QueuePort.findSlot(slotId).game().name()` (feature 004) — **not** `CurrentGamePort`, which yields an
identity key, not a name. No new locking port is introduced — the per-guild queue advisory lock
(`QueuePort.lockQueue`) is the single serialization point.

## G. Configuration (`application.yml`, new `skipjar:` defaults)

```yaml
skipjar:
  default-floor: 3        # used only as the domain default; per-guild rows override
  default-dwell: PT24H    # 24 h; per-guild rows override (stored as dwell_seconds)
```

These are *documentation of the domain defaults*; `GuildSkipJarConfig.defaults` is the authoritative
source (the absent-row case). No secrets. Tests run with `discord.enabled=false`; the services need no
gateway.
