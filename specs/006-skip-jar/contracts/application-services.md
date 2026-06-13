# Contract — Application Services

Three `@Transactional` services in `bot.application.skipjar`. Each takes a request record and returns
a result record (CLAUDE.md convention). They are the only place that opens transactions / calls ports.

## 1. `ContributeToSkipJarService` (US1 + US2 — the vote and the trigger)

```java
record ContributeRequest(long guildId, long memberId, long interactionId, Instant now) {}

record ContributeResult(
    boolean charged,            // true when a coin was debited (false only on idempotent replay)
    int count,                  // jar count for the current run after this contribution
    int threshold,             // threshold at evaluation time
    int remaining,             // max(0, threshold - count)
    boolean skipped,           // true iff this contribution triggered the early skip
    String gameName,           // the (retired/current) game's display name, from the queue slot
    String newGameName,        // the new current game's display name after a skip; null when !skipped
                               //   or the new week is empty (so the reply always has {newGame} even
                               //   when no announcement channel is configured — F1)
    Optional<AnnouncementView> announcement) {} // posting payload: present iff skipped AND a channel is configured
```

### Algorithm (single transaction)

```
1.  queuePort.lockQueue(guildId)                       // serialize vote + rotation (D-4, Principle IV)
2.  movement = coinLedgerPort.findByInteractionId(interactionId)
       if present: return the prior outcome (idempotent replay; charged=false)   // D-7
3.  state = rotationStatePort.get(guildId)
       if state.currentSlot() empty: throw NoCurrentGameException                 // FR-019
    becameCurrent = state.lastPopAt()                  // dwell baseline + earner-run boundary (D-1)
    week = state.currentWeekNumber()                   // run key (D-3)
    gameName = queuePort.findSlot(state.currentSlot().get()).game().name()  // display name from the queue slot (NOT CurrentGamePort, which returns an identity key)
4.  cfg = skipJarConfigPort.get(guildId)               // defaults (3, 24h, true) when absent
       if Duration.between(becameCurrent, now) < cfg.dwell(): throw JarClosedException  // FR-007
5.  if cfg.gateOn()
       && !earnerStatsPort.isEarner(guildId, memberId, becameCurrent):
           throw NotEligibleToContributeException        // FR-004 (gate off ⇒ skip this check, FR-005)
6.  if skipContributionPort.hasContributed(guildId, week, memberId):
           throw AlreadyContributedException             // FR-002 (also enforced by the PK, step 8)
7.  coinLedgerPort.lockAccount(guildId, memberId)        // per-account lock AFTER queue lock (order!)
    balance = coinLedgerPort.currentBalance(guildId, memberId)
    plan = SkipJarLedgerPolicy.planContribution(memberId, balance)   // throws OverdrawException if <1 (FR-006)
    // NewMovement is the 9-arg header (mirrors ProposeGameService.postSpend); moderator = self, reason = null,
    // type/requested/credited/forfeited copied from the plan:
    applied = coinLedgerPort.append(
        new NewMovement(guildId, memberId, /*moderator*/ memberId,
                        plan.type(), plan.requested(), plan.credited(), plan.forfeited(),
                        /*reason*/ null, interactionId),
        plan)                                            // MEMBER −1 / SKIP_POT +1
8.  skipContributionPort.record(guildId, week, memberId, applied.movement().id())  // PK ⇒ once-per-run (FR-002)
9.  count = skipContributionPort.count(guildId, week)                            // includes this one
    earners = earnerStatsPort.distinctEarnerCount(guildId, becameCurrent)        // FR-020 (credited≥1 since boundary)
    threshold = SkipThresholdPolicy.threshold(earners, cfg.thresholdFloor())     // max(⌊N/2⌋+1, floor)
10. if count >= threshold:                                                       // FR-010
        advance = advanceRotationService.skip(guildId, now)   // exactly one pop, same deterministic rules (D-6)
        newState = rotationStatePort.get(guildId)             // re-read: the skip advanced the run
        newGameName = newState.currentSlot()                  // null when the new week is empty (queue exhausted)
            .flatMap(queuePort::findSlot).map(s -> s.game().name()).orElse(null)
        return ContributeResult(charged=true, count, threshold, remaining=0,
                                skipped=true, gameName, newGameName, advance.finalAnnouncement())
    else:
        return ContributeResult(charged=true, count, threshold, remaining = threshold - count,
                                skipped=false, gameName, /*newGameName*/ null, empty)
```

- **Lock order** `queue → account` matches `ProposeGameService`; no deadlock.
- **Game display names**: both `gameName` (retired/current) and `newGameName` (post-skip) are read
  from the **queue slot** via `queuePort.findSlot(slotId).game().name()` — the same source
  `AnnouncementAssembler` uses. `CurrentGamePort.currentGameIdentity` is **not** used here: it returns
  a `GameIdentity` *key* (`app:<id>` / `name:<norm>`), not a human-readable name. `newGameName` is
  resolved independently of `advance.finalAnnouncement()`, so the skip reply renders `{newGame}` even
  when **no announcement channel** is configured (F1); it is `null` only when the new week is empty
  (queue exhausted).
- **No-double-advance (FR-011)**: serialized by the queue lock. A concurrent second threshold-meeting
  contribution re-reads at step 3/4 the **new** run (`lastPopAt = now`), fails the dwell gate (step 4),
  and is refused — exactly one pop.
- **Insufficient balance (FR-006)**: `planContribution` pre-checks `balance >= 1`; the V2 non-negative
  trigger is the backstop. Either way the transaction rolls back — no row, no charge.
- **Once-per-run backstop**: even if step 6's read races, step 8's PK insert raises a unique violation
  → the transaction rolls back the debit (FR-002, no charge).

## 2. `ViewSkipJarService` (US3 — status)

```java
record ViewRequest(long guildId, Instant now) {}

record SkipJarStatus(
    State state,                 // NO_GAME | NOT_OPEN | OPEN
    String gameName,            // queue-slot display name; null when NO_GAME, set for NOT_OPEN/OPEN
    int count, int threshold, int remaining,   // meaningful when OPEN
    int earnerCount, int floor,                 // meaningful when OPEN
    Instant opensAt) {          // becameCurrent + dwell; meaningful when NOT_OPEN
  enum State { NO_GAME, NOT_OPEN, OPEN }
}
```

Read-only (no lock). Reads `RotationState`; if no current slot → `NO_GAME` (`gameName = null`). Else
resolve `gameName = queuePort.findSlot(state.currentSlot().get()).game().name()` (the queue-slot
display name, same source as `ContributeToSkipJarService` — **not** `CurrentGamePort`) and compute
`becameCurrent + dwell`; if `now < opensAt` → `NOT_OPEN` (with `gameName` + `opensAt`). Else `OPEN`
with `count = skipContributionPort.count(guildId, week)`,
`earnerCount = earnerStatsPort.distinctEarnerCount(guildId, becameCurrent)`,
`threshold = SkipThresholdPolicy.threshold(earnerCount, cfg.thresholdFloor())`,
`remaining = max(0, threshold - count)`. Never throws for the no-game case (FR-014).

## 3. `ConfigureSkipJarService` (US4 — admin)

```java
enum Op { FLOOR, DWELL, GATE }
record ConfigureRequest(
    long guildId, Op op,
    Set<Long> actorRoleIds, boolean actorIsAdmin,   // for authorization
    int floor,                  // op == FLOOR
    long dwellSeconds,          // op == DWELL
    boolean gateOn) {}          // op == GATE

record SkipJarConfigResult(int thresholdFloor, long dwellSeconds, boolean gateOn) {}
```

```
authorize(request)                                  // mirrors ConfigureParticipationService (D-9)
switch (op):
  FLOOR -> if floor < 1 throw IllegalArgumentException; skipJarConfigPort.setFloor(guildId, floor)
  DWELL -> if dwellSeconds < 1 throw IllegalArgumentException; skipJarConfigPort.setDwell(guildId, dwellSeconds)
  GATE  -> skipJarConfigPort.setGate(guildId, gateOn)
return skipJarConfigPort.get(guildId)  // re-read effective config
```

`authorize` reads `GuildCoinConfigPort.get(guildId)`: throws `ModeratorRoleNotConfiguredException`
when no role; throws `ModeratorNotAuthorizedException.missingRole()` unless `actorIsAdmin` or
`actorRoleIds.contains(moderatorRoleId)` (FR-017 / SC-009).

## Domain exceptions (new, `bot.domain.skipjar`)

Typed `DomainException`s with i18n keys (CLAUDE.md error model), surfaced to the handler:
`NoCurrentGameException`, `JarClosedException`, `NotEligibleToContributeException`,
`AlreadyContributedException`. Insufficient balance reuses the coin economy's `OverdrawException`.
