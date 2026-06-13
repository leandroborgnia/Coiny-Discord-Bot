# Phase 1 — Data Model: Participation Earning

Three new tables + one sequence, plus one **additive** change to the coin movement type CHECK. All
new in migration **V4** (V1–V3 are immutable and untouched). The participation **credit** is NOT a
new table — it is a `coin_movement` of type `PARTICIPATION` with balanced `coin_ledger_entry` rows,
reusing feature 002's ledger (no second economy). Postgres-specific features are used per Principle I
(`CHECK`, composite PKs, `ON CONFLICT` upsert, a `sequence`).

## Entities

### GuildParticipationConfig (table `guild_participation_config`) — MUTABLE config

Per-server participation settings.

| Field | Type | Notes |
|-------|------|-------|
| `guild_id` | `bigint` PK | one row per guild |
| `minutes_per_drop` | `int NOT NULL DEFAULT 60 CHECK (> 0)` | qualifying minutes to mint one drop (FR-002) |
| `coins_per_drop` | `int NOT NULL DEFAULT 1 CHECK (> 0)` | whole coins per drop (FR-002) |
| `free_first_proposal` | `boolean NOT NULL DEFAULT false` | waives propose cost in the empty-queue bootstrap (FR-017/018) |
| `updated_at` | `timestamptz NOT NULL DEFAULT now()` | |

- Domain record `GuildParticipationConfig(guildId, ParticipationRate rate, boolean freeFirstProposal)`
  with `ParticipationRate(minutesPerDrop, coinsPerDrop)`; `defaults(guildId)` = `(60, 1, false)`.
- Absent row ⇒ defaults (do not require an admin to initialize before earning can work, given a
  designated channel + current game exist; the rate then uses 60/1).

### DesignatedVoiceChannel (table `participation_voice_channel`) — MUTABLE set

The per-server set of voice channels participation is registered on (FR-012/013/015). One-to-many.

| Field | Type | Notes |
|-------|------|-------|
| `guild_id` | `bigint NOT NULL` | |
| `channel_id` | `bigint NOT NULL` | a voice channel id |
| `created_at` | `timestamptz NOT NULL DEFAULT now()` | |
| PK | `(guild_id, channel_id)` | adding an already-present channel is idempotent (`ON CONFLICT DO NOTHING`) |

- **Add** = insert `(guild, channel)`; **reset-to-none** = delete all rows for the guild. No
  single-channel removal (out of scope).
- Empty set for a guild ⇒ earning impossible there (FR-015/SC-007).

### ParticipationAccrual (table `participation_accrual`) — MUTABLE per-member state

The banked sub-drop time and last sample instant per `(guild, member)` (clarification: persists
indefinitely).

| Field | Type | Notes |
|-------|------|-------|
| `guild_id` | `bigint NOT NULL` | |
| `member_id` | `bigint NOT NULL` | |
| `banked_seconds` | `bigint NOT NULL DEFAULT 0 CHECK (>= 0)` | qualifying time toward the next drop (the unminted remainder) |
| `last_sampled_at` | `timestamptz` | instant of the last accrual tick for this member; null until first observed |
| `updated_at` | `timestamptz NOT NULL DEFAULT now()` | |
| PK | `(guild, member)` | upserted via `ON CONFLICT (guild_id, member_id) DO UPDATE` |

- Domain record `ParticipationAccrual(guildId, memberId, bankedSeconds, lastSampledAt)`; absent row ⇒
  `(0, null)`.
- `banked_seconds` only ever holds the **remainder below** `minutes_per_drop * 60` after minting (whole
  drops are converted to coins, not left banked) — but the column itself is only `>= 0`-checked (the
  remainder bound is enforced by the accrual policy, not the schema, since the rate is mutable).

### participation_drop_seq (sequence) — synthetic ledger ids

`CREATE SEQUENCE participation_drop_seq;` Used as `interaction_id = -nextval('participation_drop_seq')`
on each participation `coin_movement` (see [ledger-and-observation.md](./ledger-and-observation.md)
§Idempotency). Negative values never collide with positive Discord snowflakes.

### Participation credit (reuses `coin_movement` + `coin_ledger_entry`) — APPEND-ONLY

A minted drop is one `coin_movement`:

| Field | Value for a participation drop |
|-------|-------------------------------|
| `type` | `'PARTICIPATION'` (new) |
| `member_id` | the earning member |
| `moderator_id` | the earning member's own id (self-initiated, mirroring queue spends) |
| `requested_amount` | `coins_per_drop` |
| `credited_amount` | coins that fit under the cap (= `coins_per_drop` unless cap-truncated) |
| `forfeited_amount` | `coins_per_drop - credited` (over-cap remainder; FR-005) |
| `reason` | `null` |
| `interaction_id` | `-nextval('participation_drop_seq')` |

with balanced `coin_ledger_entry` lines from `CoinLedgerPolicy.planGrant`: `TREASURY -credited` /
`MEMBER +credited`, and (when forfeited) `TREASURY -forfeited` / `FORFEIT +forfeited`. Balances stay
derived (`SUM` of `MEMBER` entries); the V2 append-only + balanced + non-negative triggers apply
unchanged.

## V4 additive ledger change

The only edit to existing tables — drop & recreate the `coin_movement` type CHECK to add the new
value (the V3 version listed the 5 prior types):

```sql
ALTER TABLE coin_movement DROP CONSTRAINT coin_movement_type_check;
ALTER TABLE coin_movement ADD  CONSTRAINT coin_movement_type_check
    CHECK (type IN ('GRANT','DEDUCTION','QUEUE_PROPOSE','QUEUE_BUMP','QUEUE_REFUND','PARTICIPATION'));
```

The `coin_ledger_entry_account_check` already allows `MEMBER`/`TREASURY`/`FORFEIT` (and `POT`);
participation uses only the first three, so it needs **no** change.

## Indexes

- `participation_voice_channel` PK `(guild_id, channel_id)` serves both "is this channel designated?"
  and "all channels for guild"; a plain index on `(guild_id)` is implied by the PK prefix.
- `participation_accrual` PK `(guild_id, member_id)` serves the per-member upsert/read.
- No extra index needed on `guild_participation_config` (PK lookup only).
- Ledger reads/writes reuse the existing `coin_movement` / `coin_ledger_entry` indexes (incl.
  `coin_movement_member_recent_idx` for history — US3).

## Invariants

- **I-P1 (per-server isolation, FR-021/SC-…)**: every table is keyed by `guild_id`; channels, rate,
  free-toggle, and accrual never cross servers.
- **I-P2 (no double-credit, FR-009/SC-005)**: a drop's `minutes_per_drop*60` seconds are subtracted
  from `banked_seconds` in the **same** transaction as its ledger append, under the per-account
  advisory lock, and `last_sampled_at` advances to `now`; a re-run cannot re-credit the same span.
- **I-P3 (cap, FR-005/SC-004)**: `credited + forfeited = coins_per_drop` for each minted drop;
  `credited` never pushes the derived balance above `cap` (the V2 non-negative trigger and the
  `planGrant` headroom math both hold); accrual **pauses** (no banking) while `balance >= cap`.
- **I-P4 (no retroactive credit, FR-023/SC-010)**: accrual per tick is bounded by `maxGap`; a gap
  larger than `maxGap` (downtime or session re-entry) accrues 0 and only resets `last_sampled_at`.
- **I-P5 (current game gates earning, FR-003/011)**: a guild with no current designated game accrues
  nothing (the sweep skips it); only the current game's `GameIdentity` qualifies.
- **I-P6 (append-only)**: participation movements/entries inherit the V2 append-only triggers — never
  updated or deleted. `banked_seconds`/`last_sampled_at` are mutable *config-like* accrual state, NOT
  ledger rows.

## State / lifecycle

- **Accrual row**: created on a member's first qualifying tick (`upsert`), then updated each tick they
  are observed qualifying; it persists (never deleted) so the banked remainder survives indefinitely.
- **Designated channel**: present (designated) or absent; transitions only via add / reset-all.
- **Free-first-proposal**: boolean toggled by the admin; read live by `ProposeGameService`.
