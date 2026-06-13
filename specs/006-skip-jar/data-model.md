# Phase 1 — Data Model: Skip Jar

Two new tables, plus two **additive** changes to the coin ledger CHECKs. All new in migration **V5**
(V1–V4 are immutable and untouched). The skip-jar **contribution** is NOT a new economy — it is a
`coin_movement` of type `SKIP_JAR` with balanced `coin_ledger_entry` rows (`MEMBER −1 / SKIP_POT +1`),
reusing feature 002's ledger. The **current run**, **dwell baseline**, and **earner set** are *read*
from existing state (`queue_rotation_state`, `coin_movement`) — no new column is added to any existing
table. Postgres-specific features are used per Principle I (`CHECK`, composite PK, `ON CONFLICT`).

## New entities

### GuildSkipJarConfig (table `guild_skip_jar_config`) — MUTABLE config

Per-server skip-jar settings.

| Field | Type | Notes |
|-------|------|-------|
| `guild_id` | `bigint` PK | one row per guild |
| `threshold_floor` | `int NOT NULL DEFAULT 3 CHECK (> 0)` | minimum contributions to skip, regardless of majority (FR-008/FR-015) |
| `dwell_seconds` | `bigint NOT NULL DEFAULT 86400 CHECK (> 0)` | minimum seconds a game must be current before its jar opens; default 24 h (FR-007/FR-016) |
| `participation_gate` | `boolean NOT NULL DEFAULT true` | on ⇒ only earners may contribute; off ⇒ any member (FR-004/FR-005) |
| `updated_at` | `timestamptz NOT NULL DEFAULT now()` | |

- Domain record `GuildSkipJarConfig(long guildId, int thresholdFloor, java.time.Duration dwell,
  boolean gateOn)` with `defaults(guildId) = (3, Duration.ofHours(24), true)`.
- Absent row ⇒ defaults (no admin initialization required before the jar works).

### SkipContribution (table `skip_contribution`) — MUTABLE per-run social state

One member's single vote for one run. Counts the jar and enforces once-per-run; **not** ledger data
(the coin movement in `coin_movement` is the immutable economic record).

| Field | Type | Notes |
|-------|------|-------|
| `guild_id` | `bigint NOT NULL` | |
| `week_number` | `int NOT NULL` | the run key = `queue_rotation_state.current_week_number` at contribution time (D-3) |
| `member_id` | `bigint NOT NULL` | the contributor |
| `movement_id` | `bigint NOT NULL REFERENCES coin_movement (id)` | the balanced SKIP_JAR spend backing this vote |
| `created_at` | `timestamptz NOT NULL DEFAULT now()` | |
| PK | `(guild_id, week_number, member_id)` | **once per member per run** (FR-002); insert via `ON CONFLICT DO NOTHING` / unique-violation → refuse |

- Jar count for the current run = `COUNT(*) WHERE guild_id = ? AND week_number = currentWeekNumber`.
- **Reset by rollover**: when the current game changes, `current_week_number` increments, so the new
  run's count is 0 and retired-run rows stop counting — no deletes (FR-012 / SC-010).

### Skip-jar contribution (reuses `coin_movement` + `coin_ledger_entry`) — APPEND-ONLY

A contribution is one `coin_movement`:

| Field | Value for a skip contribution |
|-------|-------------------------------|
| `type` | `'SKIP_JAR'` (new) |
| `member_id` | the contributing member |
| `moderator_id` | the contributor's own id (self-initiated, mirroring queue spends) |
| `requested_amount` | `1` (fixed; FR-001) |
| `credited_amount` | `0` (a spend, not a grant — like `QUEUE_PROPOSE`) |
| `forfeited_amount` | `0` |
| `reason` | `null` |
| `interaction_id` | the Discord slash interaction id (positive snowflake; at-most-once, D-7) |

with balanced `coin_ledger_entry` lines from `SkipJarLedgerPolicy.planContribution`:
`MEMBER −1` / `SKIP_POT +1`. The V2 append-only + balanced + non-negative triggers apply unchanged
(a member with 0 coins fails the non-negative trigger → refused, FR-006). **Non-refundable**: no
reversing movement is ever written — coins stay in `SKIP_POT` forever (FR-003).

## V5 additive ledger changes

The only edits to existing tables — re-add the two CHECKs to admit the new account and movement type
(V1–V4 stay immutable):

```sql
ALTER TABLE coin_ledger_entry DROP CONSTRAINT coin_ledger_entry_account_check;
ALTER TABLE coin_ledger_entry ADD  CONSTRAINT coin_ledger_entry_account_check
    CHECK (account IN ('MEMBER', 'TREASURY', 'FORFEIT', 'POT', 'SKIP_POT'));

ALTER TABLE coin_movement DROP CONSTRAINT coin_movement_type_check;
ALTER TABLE coin_movement ADD  CONSTRAINT coin_movement_type_check
    CHECK (type IN ('GRANT', 'DEDUCTION', 'QUEUE_PROPOSE', 'QUEUE_BUMP', 'QUEUE_REFUND',
                    'PARTICIPATION', 'SKIP_JAR'));
```

`SKIP_POT` carries no `member_id`, so the V2 table-level `(account = 'MEMBER') = (member_id IS NOT
NULL)` CHECK is satisfied unchanged.

## State read from existing tables (no schema change)

- **Current run / dwell baseline** — `queue_rotation_state` (read via `RotationStatePort.get`):
  `current_slot_id` (null ⇒ no game), `current_week_number` (run key), `last_pop_at` (became-current
  instant). See research **D-1**.
- **Distinct earner set** — `coin_movement`: `COUNT(DISTINCT member_id)` (resp. `EXISTS`) where
  `guild_id = ?` AND `type = 'PARTICIPATION'` AND `credited_amount > 0` AND
  `created_at >= last_pop_at`. See research **D-2**. Served by the existing
  `coin_movement_member_recent_idx (guild_id, member_id, id DESC)` plus a filter on type/created_at.

## Indexes

- `guild_skip_jar_config` PK `(guild_id)` — config lookup only.
- `skip_contribution` PK `(guild_id, week_number, member_id)` serves both the once-per-run insert and
  the per-run count (the `(guild_id, week_number)` prefix). No extra index needed.
- Earner-set read reuses existing `coin_movement` indexes; the predicate
  `type='PARTICIPATION' AND created_at >= lastPopAt` is selective within a guild. (If profiling shows
  it is hot, a later migration may add `coin_movement (guild_id, type, created_at)` — **not** added
  speculatively here.)
- Ledger writes reuse existing `coin_movement` / `coin_ledger_entry` indexes and triggers.

## Invariants

- **I-S1 (per-server isolation, FR-018/SC-…)**: every table is keyed by `guild_id`; contributions,
  floor, dwell, and gate never cross servers.
- **I-S2 (once per run, FR-002/SC-002)**: the `skip_contribution` PK `(guild_id, week_number,
  member_id)` makes a second contribution for the same run a unique-violation → refused with **no**
  additional charge (the debit and the row insert are in the same transaction; the insert failing
  rolls back the debit).
- **I-S3 (exactly one coin, non-refundable, FR-001/FR-003/SC-001/SC-007)**: each contribution debits
  exactly 1 (`MEMBER −1 / SKIP_POT +1`); no reversing movement is ever written, so coins in
  `SKIP_POT` are never returned — across triggered skips, weekly advances, and departures.
- **I-S4 (jar closed during dwell, FR-007/SC-004)**: a contribution is accepted only when
  `Duration.between(lastPopAt, now) >= dwell`; otherwise refused with no charge and no row.
- **I-S5 (threshold, FR-008/FR-009/SC-005)**: threshold = `max(floor(N/2) + 1, thresholdFloor)` where
  `N` = distinct earners since `lastPopAt`, evaluated at the moment of each contribution (not
  re-evaluated when the earner set later grows).
- **I-S6 (one skip, no double-advance, FR-010/FR-011/SC-006)**: the whole vote-and-advance runs under
  the per-guild queue advisory lock; the threshold-meeting contribution calls
  `AdvanceRotationService.skip` for **exactly one** pop; a concurrent second contribution re-reads the
  **new** run (dwell reset) and is refused — one rotation step.
- **I-S7 (week-scoped reset, FR-012/SC-010)**: the jar count is filtered by `current_week_number`;
  changing the current game (skip or weekly advance) advances the week, so the new run starts empty
  and retired-run contributions never count — with no deletes.
- **I-S8 (append-only economy, Principle III)**: skip movements/entries inherit the V2 append-only
  triggers — never updated or deleted. `skip_contribution` is mutable per-run social state, NOT a
  ledger row, and is the count/uniqueness index, not the economic record.

## State / lifecycle

- **`guild_skip_jar_config` row**: created on first admin change (`ON CONFLICT` upsert); absent ⇒
  defaults `(3, 24 h, true)`.
- **`skip_contribution` row**: inserted once per `(guild, run, member)` on a successful contribution;
  never updated; superseded by the week rolling over (it simply stops being counted).
- **Run lifecycle (read, owned by the queue feature)**: a game becomes current (instant-pop, weekly
  advance, or early skip) → `last_pop_at = becameCurrent`, `current_week_number++`; the jar opens once
  dwell elapses; it closes when the run ends (the next current-game change).
