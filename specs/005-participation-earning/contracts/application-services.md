# Contract — Application Services

All services are `@Transactional`, take a request record and return a result record (or void for the
sweep call), and are the only layer that opens transactions / calls ports (Principle II). Rule
violations throw typed `DomainException`s that roll the transaction back.

---

## 1. `AccrueParticipationService` (US1 — the earning core)

Called once per **currently-qualifying** member per sweep tick by `ParticipationScheduler`. One
member, one transaction.

```java
record AccrueParticipationRequest(long guildId, long memberId, Instant now) {}
record AccrueParticipationResult(int dropsMinted, int coinsCredited, int coinsForfeited,
                                 long bankedSecondsAfter, Outcome outcome) {
  enum Outcome { ACCRUED, PAUSED_AT_CAP, FRESH_SESSION, MINTED }
}
```

Collaborators (ports): `ParticipationConfigPort`, `ParticipationAccrualPort`, `CoinLedgerPort`
(reused), `GuildCoinConfigPort` (reused, for the cap). The **current-game** gating is done in the
sweep *before* calling this service (the sweep only calls it for members already matched to the
current game in a designated channel), so this service does not re-check the game — it accrues time
and mints drops.

### Algorithm (`accrue`)

1. `ledgerPort.lockAccount(guildId, memberId)` — reuse the per-account advisory lock (serializes with
   coin spends/grants).
2. `cap = guildCoinConfigPort.get(guildId).cap()`; `balance = ledgerPort.currentBalance(...)`.
3. `acc = accrualPort.get(guildId, memberId)` → `(bankedSeconds, lastSampledAt)` (`(0, null)` if absent).
4. **Cap pause (I-P3/FR-005)**: if `balance >= cap` → `accrualPort.upsert(banked unchanged,
   lastSampledAt = now)`; return `PAUSED_AT_CAP`.
5. **Elapsed (I-P4/FR-023)** via `ParticipationAccrualPolicy.elapsedToCredit(lastSampledAt, now,
   maxGap)`:
   - `null` or `> maxGap` → `0` (fresh session); else the real delta in seconds.
   - If `0` and nothing banked changes → `accrualPort.upsert(banked unchanged, lastSampledAt = now)`;
     return `FRESH_SESSION`.
6. `newBanked = bankedSeconds + elapsed`.
7. `rate = config.rate()`; `thresholdSeconds = rate.minutesPerDrop() * 60`.
8. **Mint loop** while `newBanked >= thresholdSeconds` **and** `balance < cap`:
   - `plan = CoinLedgerPolicy.planGrant(guildId, memberId, balance, rate.coinsPerDrop(), cap)`;
   - `id = accrualPort.nextDropId()` (negative); `ledgerPort.append(new NewMovement(guildId, memberId,
     memberId, PARTICIPATION, requested, credited, forfeited, null, id), plan)`;
   - `balance += plan.credited()`; `newBanked -= thresholdSeconds`; accumulate `dropsMinted`,
     `coinsCredited`, `coinsForfeited`;
   - if `plan.forfeited() > 0` (this drop hit the cap) → **break** (do not mint further whole drops; the
     leftover `newBanked` remainder stays, but the member is now at cap so subsequent ticks pause).
9. `accrualPort.upsert(guildId, memberId, bankedSeconds = newBanked, lastSampledAt = now)`.
10. Return `MINTED` (if any drop) else `ACCRUED`, with the tallies and `bankedSecondsAfter`.

Notes: minting per drop reuses the **existing** `planGrant` cap/forfeiture math (no new arithmetic).
The whole method is idempotent under replay because consumed seconds are persisted and
`last_sampled_at` advances in the same transaction (I-P2/FR-009).

---

## 2. `ParticipationAccrualPolicy` (pure domain — unit-tested without a DB)

```java
final class ParticipationAccrualPolicy {
  /** 0 when lastSampledAt is null or the gap exceeds maxGap (fresh session / downtime); else the gap in seconds. */
  static long elapsedToCredit(Instant lastSampledAt, Instant now, Duration maxGap);
  /** thresholdSeconds = minutesPerDrop * 60. */
  static long thresholdSeconds(ParticipationRate rate);
  /** whole drops ready from banked seconds (banked / threshold), and the remainder (banked % threshold). */
  static DropsAndRemainder dropsReady(long bankedSeconds, long thresholdSeconds);
}
record DropsAndRemainder(int drops, long remainderSeconds) {}
```

Coin amounts/cap are handled by the reused `CoinLedgerPolicy.planGrant`; this policy is **time
arithmetic only**, keeping the domain framework-free and the cap rule single-sourced.

---

## 3. `ConfigureParticipationService` (US2 + rate + US4 toggle)

```java
record ConfigureParticipationRequest(
    long guildId, long actorMemberId, Set<Long> actorRoleIds, boolean actorIsAdmin,
    Op op,                          // CHANNEL_ADD | CHANNEL_RESET | RATE | FREE_PROPOSAL
    Long channelId,                 // for CHANNEL_ADD
    Integer minutesPerDrop, Integer coinsPerDrop,   // for RATE
    Boolean freeFirstProposal) {}   // for FREE_PROPOSAL
record ParticipationConfigResult(
    int designatedChannelCount, int minutesPerDrop, int coinsPerDrop, boolean freeFirstProposal) {}
```

Collaborators: `GuildCoinConfigPort` (authorize), `ParticipationConfigPort`, `DesignatedChannelPort`.

### Algorithm (`configure`)

1. **Authorize** exactly like `AdjustCoinsService.authorize`:
   `config = guildCoinConfigPort.get(guildId)`; if `!config.hasModeratorRole()` →
   `ModeratorRoleNotConfiguredException`; if `!actorIsAdmin && !actorRoleIds.contains(moderatorRoleId)`
   → `ModeratorNotAuthorizedException`.
2. Dispatch on `op`:
   - `CHANNEL_ADD` → `designatedChannelPort.add(guildId, channelId)` (idempotent).
   - `CHANNEL_RESET` → `designatedChannelPort.resetAll(guildId)`.
   - `RATE` → validate `minutesPerDrop >= 1 && coinsPerDrop >= 1` (the command already
     `setMinValue(1)`; re-validate defensively, throw `IllegalArgumentException` otherwise);
     `participationConfigPort.setRate(guildId, minutesPerDrop, coinsPerDrop)`.
   - `FREE_PROPOSAL` → `participationConfigPort.setFreeFirstProposal(guildId, freeFirstProposal)`.
3. Return the current `ParticipationConfigResult` (re-read config + channel count).

Authorization unauthorized cases change nothing (SC-008).

---

## 4. `ProposeGameService` — free-first-proposal change (US4 / FR-018/019)

**Modify** the existing service (feature 004). Add one collaborator:
`ParticipationConfigPort` (read-only `freeFirstProposalEnabled(guildId)`).

Current flow (unchanged up to here): no-activity guard → `lockQueue` → propose-idempotency →
replace-branch → eligibility check. **Change** begins where affordability is computed:

1. Read `RotationState rotation` and `List<QueueSlot> queued` (already read today, just ensure it
   happens **before** any account lock / spend).
2. `boolean bootstrap = rotation.currentSlotId() == null && queued.isEmpty();`
3. `boolean waive = bootstrap && participationConfigPort.freeFirstProposalEnabled(guildId);`
4. **If `waive`**: do **not** `lockAccount`, do **not** build a `planSpend`, do **not** `postSpend`.
   Append the instant-popped slot with `coinsSpent = 0`, record the designation + bootstrap + cooldown
   `N = 0` exactly as today, assemble the announcement if a channel is configured, and return
   `INSTANT_POPPED` with `coinsSpent = 0` and the **unchanged** balance.
5. **Else** (not waived): the existing path runs unchanged — `lockAccount`, `planSpend` (which throws
   `InsufficientCoinsException` if short), append (instant-pop or tail), `postSpend`.

Result records are unchanged; a waived proposal simply reports `coinsSpent = 0` and an unchanged
balance. This keeps the waiver scoped exactly to the recurring no-current-game + empty-queue state
(FR-018) and means a waived proposal posts **no** coin movement ("free = cost 0").

### Acceptance mapping

- FR-018 → step 4 (waived): no charge, no balance check, proceeds to instant-pop.
- FR-019 → step 5 (flag off, or current game exists, or queue non-empty → `bootstrap` false): normal
  cost applies.
- Idempotency/replace/eligibility ordering unchanged (a re-delivered waived propose still returns the
  original slot via the existing `findByProposeInteraction` check).

---

## Service ↔ requirement matrix

| Service | Requirements |
|---------|--------------|
| `AccrueParticipationService` | FR-001..006, FR-009, FR-010, FR-011, FR-022, FR-023; SC-001..005, SC-010 |
| `ConfigureParticipationService` | FR-012..017, FR-020, FR-021; SC-007, SC-008 |
| `ProposeGameService` (modified) | FR-018, FR-019; SC-009 |
| `CoinQueryService` + `BalanceCommand` (modified renderer) | FR-007, FR-008; SC-006 (US3) |
