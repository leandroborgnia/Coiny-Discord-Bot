# Phase 0 — Research: Skip Jar

All unknowns from the Technical Context are resolved below. The recurring theme: **reuse existing
state and locks** (the rotation clock, the coin ledger, the per-guild queue advisory lock, the
deterministic advance) so the skip jar adds a spend path and an early-skip trigger without inventing
new economy, rotation, or concurrency machinery.

---

## D-1: Identifying the current game **run** and its **became-current** instant

**Decision**: Read the existing `RotationState` via `RotationStatePort.get(guildId)`. It already
carries everything the skip jar needs:

- `currentSlotId` — the current designated slot; **`null` ⇒ no current game** (nothing to skip,
  FR-019 / status "no game").
- `currentWeekNumber` — a monotonically increasing integer that **uniquely identifies the run** and is
  the natural scoping key for contributions (see D-3).
- `lastPopAt` — the instant the current game became current (the queue feature sets it on each
  designation), used directly as the **dwell baseline** (FR-007) and the **earner-set run boundary**
  (D-2).

**Rationale**: The game-queue feature already maintains `last_pop_at` as the rolling-7-day clock,
written to the designation instant on every pop (`advanceClock`). Whenever there *is* a current game,
`last_pop_at` is exactly when that game became current — including instant-pop bootstrap and after an
early skip. No new column, no new query path, no duplicate "became current" timestamp.

**Alternatives considered**: (a) Joining `weekly_designation` for `designated_at` — equivalent value
but an extra join when `RotationState` already has it. (b) Storing a separate skip-jar "game became
current" timestamp — duplicates a source of truth (Principle III spirit) and risks drift.

---

## D-2: Sizing the **distinct earner set** of the current run

**Decision**: Read it from the **existing coin ledger** — count distinct `member_id` with a
`coin_movement` of type `PARTICIPATION` and `credited_amount > 0` and `created_at >= lastPopAt`
(`EarnerStatsPort.distinctEarnerCount`). The gate-on eligibility check is the same predicate for a
single member (`isEarner`). **No new attribution column is added to `coin_movement`.**

**Rationale**: Clarification (2026-06-13) defines an "earner" as a member with **≥ 1 credited
participation drop** for the **current run** — "a coin actually landed in their balance." The
participation sweep only credits the *current* game, so every `PARTICIPATION` movement after
`lastPopAt` belongs to the current run; the run boundary is therefore the timestamp, not a stored
game/week key. `credited_amount > 0` excludes a fully-over-cap drop where no coin landed (matching the
clarification). A future re-run of the same game starts a fresh `lastPopAt`, so its earner set resets
automatically (FR-020 "fresh earner set").

**Alternatives considered**: (a) Adding `week_number`/`run_id` to participation movements — would
edit feature 005's posting path and the V4 shape for no functional gain over the timestamp boundary.
(b) Materializing an "earners" table — redundant with the ledger, which is already the source of truth.

---

## D-3: Scoping contributions to a run & the **free reset** on game change

**Decision**: Store each contribution keyed by `(guild_id, week_number, member_id)` where
`week_number = currentWeekNumber` at contribution time. The jar's count for the current run is
`COUNT(*) WHERE guild_id = ? AND week_number = currentWeekNumber`. **Once-per-run** is the composite
PK. When the current game changes (early skip or weekly advance), `currentWeekNumber` increments, so
the new run's count starts at **zero with no deletes** and retired-run contributions stop counting
(FR-012 / SC-010).

**Rationale**: `week_number` is unique per designation (`weekly_designation` has
`UNIQUE (guild_id, week_number)`), monotonic, and survives the same game re-appearing (new week ⇒
fresh jar). Reset-by-key-rollover needs no cleanup job and never touches prior rows (consistent with
the append-only ethos, though `skip_contribution` is mutable social state, not ledger data).

**Alternatives considered**: (a) Keying by `current_slot_id` — the slot id is reused/mutated as it
moves QUEUED→PLAYED and could be ambiguous across re-appearances; `week_number` is the cleaner run
identity. (b) Deleting contributions on advance — extra write path and a race with concurrent
contributions; rollover avoids both.

---

## D-4: Concurrency — exactly **one** early skip, no double-advance (FR-011)

**Decision**: `ContributeToSkipJarService` acquires the **existing per-guild queue advisory lock**
(`QueuePort.lockQueue(guildId)`) at the start of its transaction, then the per-account lock for the
debit — the **same lock order** as `ProposeGameService`. The dwell check, gate check, once-per-run
insert, debit, threshold evaluation, and (if met) the early skip all happen inside this single locked
transaction. The early skip is performed by `AdvanceRotationService.skip` (D-6), which also takes the
queue lock (reentrant within the same transaction/session via `pg_advisory_xact_lock`).

**Rationale**: Principle IV demands a single atomic source of truth with no read-then-write race. The
queue advisory lock already serializes the rotation; reusing it serializes contributions against each
other **and** against the weekly advance. Two near-simultaneous threshold-meeting contributions become
strictly ordered: the first triggers the pop and advances `currentWeekNumber`/`lastPopAt`; the second,
on re-read, sees the **new** run (dwell not yet elapsed) and is refused — exactly one advance, one
rotation step.

**Alternatives considered**: (a) An optimistic check-then-advance without the lock — classic
read-then-write race, forbidden by Principle IV. (b) A separate skip-specific lock — a second lock
ordering against the queue lock invites deadlock; reusing the one queue lock is simplest and proven.

---

## D-5: The **dedicated SKIP_POT** account vs reusing the queue `POT`

**Decision**: Add a new ledger account **`SKIP_POT`** (additive `LedgerAccount` enum value + additive
`coin_ledger_entry` account CHECK in V5) and a new movement type **`SKIP_JAR`**. Each contribution
posts a balanced `MEMBER −1 / SKIP_POT +1` movement; coins accumulate there and are **never** moved
out (non-refundable, no distribution/burn defined — FR-003 / out-of-scope).

**Rationale**: The clarification mandates a skip-jar pot **separate from the queue propose pot** so the
two spend sinks are distinguishable in coin history (FR-013). `SKIP_POT` mirrors how the queue `POT`
was itself added in V3 — a per-guild sink account, not non-negativity-checked. The "per-game-run pot"
framing in the spec is *logical*: no requirement ever reads a per-run pot balance, so a single
per-guild `SKIP_POT` account suffices, with the run association carried by the contribution row.

**Alternatives considered**: (a) Reusing the queue `POT` — violates the clarification's separation and
muddies history. (b) One `SKIP_POT` sub-account per run — unnecessary; nothing reads it.

---

## D-6: The **early skip** = one pop reusing the deterministic advance

**Decision**: Refactor `AdvanceRotationService` to extract the per-period loop body into a single
private `pop(...)` step, then add a public `@Transactional skip(long guildId, Instant now)` that takes
the queue lock and performs **exactly one** pop with the clock baseline set to **`now`**: `week++`,
designate the top slot (or record an empty week if the queue is empty), `shiftUp`, `decrementAll` then
`set` the proposer cooldown, `advanceClock(..., now)`, and return the same `AdvanceResult` (with the
announcement when a channel is configured). The weekly path is unchanged: it still applies every due
7-day period using its computed pop instants.

**Rationale**: FR-010/FR-011 require the early skip to use the **same deterministic rules** as the
weekly advance with **no new advance behavior** — including empty-queue handling, which the queue
feature owns. Extracting the shared pop body guarantees byte-for-byte identical designation/cooldown
logic. The only legitimate difference is the **clock baseline**: an early skip happens before the
7-day mark, so the new game's dwell and the next weekly pop both restart from `now` (the skip instant),
matching "the new game just became current."

**Alternatives considered**: (a) Calling `advanceDue` — it is period-driven (advances 0 unless a
7-day boundary passed), so it cannot force an early pop. (b) Duplicating the pop logic in the skip
service — risks divergence from the weekly rules, exactly what FR-010 forbids.

---

## D-7: Contribution **idempotency key**

**Decision**: Use the **Discord interaction id** (a positive snowflake) as the `coin_movement
.interaction_id` for the `SKIP_JAR` movement, exactly like `QUEUE_PROPOSE`. A retried interaction
short-circuits via `CoinLedgerPort.findByInteractionId`. The `skip_contribution` PK is the independent
once-per-run guard.

**Rationale**: Unlike participation (a background sweep with no interaction, which needed the negative
synthetic sequence), a contribution is a real slash-command interaction with a natural unique id.
Reusing it keeps the at-most-once mechanism identical to queue spends.

**Alternatives considered**: A synthetic sequence like participation's `participation_drop_seq` —
unnecessary when a real interaction id exists.

---

## D-8: **Dwell** storage & the threshold formula

**Decision**: Store the dwell as `dwell_seconds bigint NOT NULL DEFAULT 86400 CHECK (> 0)` and expose
it in the domain as `java.time.Duration` (`Duration.ofSeconds`). The dwell-elapsed test is
`Duration.between(lastPopAt, now).compareTo(dwell) >= 0`. The threshold is the pure
`SkipThresholdPolicy.threshold(distinctEarners, floor) = max(floor(N/2) + 1, floor)` — strictly more
than half, floored at the configurable minimum (default 3).

**Rationale**: The time convention is `Instant` for storage and `Duration` for cooldowns; seconds is a
clean integer encoding of a `Duration` (not "long ms"). With the gate **off** and zero earners,
`floor(0/2)+1 = 1` but the floor (default 3) governs ⇒ threshold 3, matching the spec edge "No earners
yet, gate off: floor still applies." With one or two earners the floor likewise governs.

**Alternatives considered**: Postgres `interval` — workable but adds JDBC mapping friction; `bigint`
seconds ↔ `Duration` is simpler and equally Postgres-native.

---

## D-9: **Authorization** for `/skip-config`

**Decision**: Reuse the participation/coin pattern exactly: authorize against
`GuildCoinConfigPort.get(guildId).moderatorRoleId()` — Discord Administrator bypasses, **fails closed**
when no role is configured (`ModeratorRoleNotConfiguredException` / `ModeratorNotAuthorizedException`).
Contributing and viewing status require no special role.

**Rationale**: The spec's admin role is "the server's configured economy-administration role" — the
same role feature 005 uses. Reusing `ConfigureParticipationService`'s `authorize(...)` shape keeps one
authorization story (FR-017 / SC-009).

**Alternatives considered**: Discord's generic Manage-Server permission — explicitly rejected by the
spec's Assumptions.
