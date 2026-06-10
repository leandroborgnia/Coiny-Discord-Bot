# Phase 1 Data Model: Coin Economy & Append-Only Audit Trail

This slice adds an append-only, double-entry coin ledger scoped **per server**, plus a small
per-server configuration row. Balances are **derived** by summing ledger entries — never stored.
All amounts are whole numbers (smallest unit 1). The model deliberately leans on Postgres
(sequences, `pg_advisory_xact_lock`, `ON CONFLICT`, triggers) per Constitution Principles I & III.

## Domain types (`bot.domain.coin`) — pure Java, no framework imports

### `CoinAmount` (value object / record)

A non-negative whole coin quantity; the smallest unit is 1.

| Field | Type | Notes |
|-------|------|-------|
| `value` | `int` | `≥ 0`. Adjustment amounts must be `≥ 1`. |

- Factory `CoinAmount.of(int)` rejects negative values; `CoinAmount.positive(int)` additionally
  rejects `0` (throws `NonPositiveAmountException`). Integer-typed → fractions are impossible.
- Pure helpers: `plus`, `minus`, `min`, comparisons. No framework imports.

### `AdjustmentType` (enum)

`GRANT`, `DEDUCTION` — the moderator-chosen operation. (Forfeiture is a *consequence* of a grant,
recorded as ledger entries, not a type a moderator selects.)

### `LedgerAccount` (enum)

`MEMBER`, `TREASURY`, `FORFEIT` — the three account kinds whose entries sum to zero per movement.
`TREASURY` is the per-guild mint/source; `FORFEIT` is the per-guild sink for over-cap coins.

### `PostingLine` (record) and `PostingPlan` (record)

`PostingLine(LedgerAccount account, Long memberId, int signedAmount)` — one balanced-ledger entry
(`memberId` non-null iff `account == MEMBER`). `PostingPlan(AdjustmentType type, int requested,
int credited, int forfeited, List<PostingLine> lines)` — the full, balanced set of lines for one
movement; `lines` always sum to zero.

### `CoinLedgerPolicy` (domain service) — pure, unit-tested without a DB

The economy's arithmetic. No I/O.

```text
PostingPlan planGrant(long guildId, long memberId, int currentBalance, int amount, int cap)
    // creditable = clamp(cap - currentBalance, 0, amount); forfeited = amount - creditable
    // lines: TREASURY -creditable, MEMBER +creditable [, TREASURY -forfeited, FORFEIT +forfeited]
PostingPlan planDeduction(long guildId, long memberId, int currentBalance, int amount)
    // if amount > currentBalance -> throw OverdrawException (nothing is posted)
    // lines: MEMBER -amount, TREASURY +amount
```

Invariants guaranteed by the policy: `amount ≥ 1`; resulting member delta never pushes balance
below 0 or above `cap`; every returned `PostingPlan.lines` sums to zero.

### `GuildCoinConfig` (value object / record)

`GuildCoinConfig(long guildId, Long moderatorRoleId, int cap)` — `moderatorRoleId` is `null` until
a server designates one (feature fails closed while null); `cap` defaults to `12`.

### Outbound ports (interfaces, pure Java) — implemented in infrastructure

```text
interface CoinLedgerPort {
    void lockAccount(long guildId, long memberId);               // pg_advisory_xact_lock (within the app tx)
    int currentBalance(long guildId, long memberId);             // SUM of MEMBER entries (0 if none)
    Optional<MovementRecord> findByInteractionId(long interactionId);  // idempotency lookup
    MovementRecord append(NewMovement movement, PostingPlan plan);     // ON CONFLICT(interaction_id) DO NOTHING
    List<MovementRecord> recentHistory(long guildId, long memberId, int limit);  // newest first
}
interface GuildCoinConfigPort {
    GuildCoinConfig get(long guildId);                           // returns default cap 12, null role, if absent
    GuildCoinConfig upsert(long guildId, Long moderatorRoleId, Integer cap);  // null arg = leave unchanged
}
```

`MovementRecord` / `NewMovement` are simple domain carriers (movement header fields below). Ports
use only domain/JDK types, so the application depends inward only (Principle II).

## Persistence entities (`bot.infrastructure.persistence.coin`)

### `GuildCoinConfigEntity` → table `guild_coin_config` *(mutable configuration — not the ledger)*

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `guild_id` | `bigint` | PRIMARY KEY | Discord guild (server) id. |
| `moderator_role_id` | `bigint` | NULL | Designated role authorizing adjustments; `NULL` ⇒ no one authorized (fails closed). |
| `coin_cap` | `int` | NOT NULL DEFAULT 12, CHECK (`coin_cap >= 0`) | Per-server balance cap; default 12. |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `now()` | Last config change. |

This is configuration, not ledger data, so it is **mutable** (cap/role can change). Principle III
(append-only) governs the ledger tables below, not config.

### `CoinMovementEntity` → table `coin_movement` *(append-only)*

One row per economic event (a grant or a deduction).

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `bigserial` | PRIMARY KEY | Monotonic ordering via sequence. |
| `guild_id` | `bigint` | NOT NULL | Server scope. |
| `member_id` | `bigint` | NOT NULL | Affected member. |
| `moderator_id` | `bigint` | NOT NULL | Initiating moderator (permanent attribution, FR-011). |
| `type` | `text` | NOT NULL, CHECK in (`'GRANT'`,`'DEDUCTION'`) | The operation. |
| `requested_amount` | `int` | NOT NULL, CHECK (`> 0`) | Amount the moderator requested (FR-001/016). |
| `credited_amount` | `int` | NOT NULL DEFAULT 0, CHECK (`>= 0`) | Coins that actually landed (≤ requested for grants). |
| `forfeited_amount` | `int` | NOT NULL DEFAULT 0, CHECK (`>= 0`) | Over-cap coins forfeited at earning time (FR-007/018/019). |
| `reason` | `text` | NULL | Moderator-supplied reason/context. |
| `interaction_id` | `bigint` | NOT NULL **UNIQUE** | Idempotency key (FR-008/021). |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | Event time. |

`credited_amount` / `forfeited_amount` / `requested_amount` are **write-once facts of the event**,
not a running balance — they are never read to compute a balance (see plan Constitution note).

### `CoinLedgerEntryEntity` → table `coin_ledger_entry` *(append-only, double-entry postings)*

Two or more signed rows per movement; the rows of a movement **sum to zero**.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | `bigserial` | PRIMARY KEY | |
| `movement_id` | `bigint` | NOT NULL, REFERENCES `coin_movement(id)` | Groups the balanced postings. |
| `guild_id` | `bigint` | NOT NULL | Server scope (denormalized for fast balance sums). |
| `account` | `text` | NOT NULL, CHECK in (`'MEMBER'`,`'TREASURY'`,`'FORFEIT'`) | Account kind. |
| `member_id` | `bigint` | NULL, CHECK (`(account='MEMBER') = (member_id IS NOT NULL)`) | Set iff MEMBER. |
| `amount` | `bigint` | NOT NULL | Signed: `+` credits the account, `−` debits it. |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |

**Indexes**:
- `coin_ledger_entry (guild_id, member_id)` — fast balance `SUM` for `MEMBER` accounts.
- `coin_movement (guild_id, member_id, id DESC)` — recent-history reads, newest first.
- (`coin_movement.interaction_id` is already uniquely indexed by the UNIQUE constraint.)

## Migration `V2__coin_ledger.sql` (immutable once applied)

Creates the three tables, indexes, and the trigger functions/triggers that enforce the ledger
invariants. `V1` is untouched; this is purely additive.

1. **Tables & indexes** as specified above (`guild_coin_config`, `coin_movement`,
   `coin_ledger_entry`).
2. **Append-only triggers** — a `coin_forbid_mutation()` plpgsql function that
   `RAISE EXCEPTION`s, wired as `BEFORE UPDATE OR DELETE FOR EACH ROW` on both `coin_movement`
   and `coin_ledger_entry`. Posted rows can never be edited or deleted (FR-004, SC-002).
3. **Balanced-movement constraint** — a `DEFERRABLE INITIALLY DEFERRED` constraint trigger on
   `coin_ledger_entry` that, at commit, verifies `SUM(amount) = 0` for each touched
   `movement_id` (FR-003, double-entry). Deferred so all of a movement's lines are inserted
   before the check runs.
4. **Non-negative balance constraint** — a deferred constraint trigger verifying the affected
   `MEMBER` account's `SUM(amount) ≥ 0` after the movement (FR-002, SC-001/007). The **cap is not
   enforced in the DB** (a legally lowered cap may sit below an existing balance); it is enforced
   at earning time by `CoinLedgerPolicy`.

Future schema changes ship as `V3__…`; `V2` is never edited.

## State & lifecycle

- **Coin Account**: no row of its own — it is the *derived* `SUM` of a `(guild_id, member_id)`'s
  `MEMBER` entries. It has no mutable state; it only grows an append-only history.
- **Coin Movement / Ledger Entry**: write-once. Created together in one transaction; never
  transition, never mutate, never delete.
- **Guild Coin Config**: the only mutable entity — `moderator_role_id` and `coin_cap` change via
  `/coins-config`; `updated_at` advances. Lowering `coin_cap` does **not** retroactively forfeit
  existing balances (cap applies only at earning time).

## Validation & invariants (summary)

- Adjustment amount `≥ 1`, whole (FR-001/016) — `CoinAmount` + `requested_amount > 0` CHECK.
- Balance never `< 0` (FR-002) — policy + advisory lock + deferred non-negative trigger; overdraw
  throws `OverdrawException` → rollback (nothing posted).
- Every movement's entries sum to zero (FR-003) — `CoinLedgerPolicy` builds balanced plans; DB
  constraint trigger enforces it.
- Ledger is append-only (FR-004) — UPDATE/DELETE triggers reject mutation; balance is derived, not
  stored (FR-005/020).
- Cap enforced per server, default 12, excess forfeited and recorded (FR-007/018/019) — policy
  computes `credited`/`forfeited`; forfeiture posted as TREASURY→FORFEIT.
- At-most-once (FR-008/021) — `interaction_id` UNIQUE + `ON CONFLICT DO NOTHING`; duplicate
  returns the original outcome.
- Per-server isolation (FR-023) — every table and the balance `SUM` is keyed by `guild_id`.
- Non-transferable (FR-006) — there is simply no posting path between two `MEMBER` accounts; only
  MEMBER↔TREASURY and TREASURY↔FORFEIT postings exist.
