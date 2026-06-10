# Contract: Ledger Invariants (Postgres-enforced)

These invariants are the tamper-evident guarantees of the coin ledger. They are enforced **in the
database** by `V2__coin_ledger.sql` (not only in application code), so they hold even against an
out-of-band writer — satisfying Constitution Principle III and spec FR-002/003/004 and
SC-001/002/007. Integration tests (`CoinLedgerTriggersTest`,
`CoinIdempotencyConcurrencyTest`) assert each one against real Postgres.

## I1 — Append-only (no UPDATE, no DELETE)

- Any `UPDATE` or `DELETE` on `coin_movement` or `coin_ledger_entry` MUST fail.
- Mechanism: `BEFORE UPDATE OR DELETE FOR EACH ROW` trigger calling `coin_forbid_mutation()`,
  which `RAISE EXCEPTION`s.
- Test: insert a movement + entries, then attempt `UPDATE coin_ledger_entry SET amount = …` and
  `DELETE FROM coin_movement …` → both raise; the rows are unchanged.

## I2 — Balanced movements (double-entry)

- For every `movement_id`, `SUM(coin_ledger_entry.amount) = 0`.
- Mechanism: a `DEFERRABLE INITIALLY DEFERRED` constraint trigger checks the sum at commit (after
  all of a movement's lines are inserted).
- Test: attempting to commit a movement whose entries do not sum to zero raises at commit; a
  balanced movement commits.

## I3 — Non-negative balances

- For every `(guild_id, member_id)` `MEMBER` account, `SUM(amount) ≥ 0` after any movement.
- Mechanism: a deferred constraint trigger; on violation it raises, rolling back the movement.
- Application path: `CoinLedgerPolicy.planDeduction` throws `OverdrawException` *before* posting,
  so the trigger is defense-in-depth. A deduction larger than the balance leaves the account and
  audit trail completely unchanged (SC-007).
- Note: the **cap** is intentionally **not** a DB constraint — a server may lower its cap below an
  existing balance (allowed; no retroactive confiscation). The cap is enforced at earning time in
  `CoinLedgerPolicy` (credit up to the cap, forfeit the remainder).

## I4 — At-most-once (idempotent application)

- `coin_movement.interaction_id` is `UNIQUE`; a second movement with the same interaction id MUST
  NOT be created.
- Mechanism: `INSERT … ON CONFLICT (interaction_id) DO NOTHING`; the service detects the no-op and
  returns the original outcome (`DUPLICATE`).
- Test: applying the same `AdjustCoinsRequest` (same `interactionId`) twice — and concurrently —
  results in exactly one movement and one set of entries; balance reflects a single application
  (SC-005).

## I5 — Atomic, race-free adjustments

- Concurrent adjustments to the **same** member are serialized by `pg_advisory_xact_lock` on a
  hash of `(guild_id, member_id)`, taken inside the application transaction.
- Test: two concurrent grants that together would exceed the cap, or a grant racing a deduct,
  resolve so that the final balance is `0 ≤ balance ≤ cap` and the ledger sums reconcile — never a
  negative balance, never an over-cap balance produced by interleaving.

## I6 — Balance is derived, never stored

- A member's balance is always `SUM(coin_ledger_entry.amount)` over their `MEMBER` rows; there is
  no authoritative stored-balance column. The `credited/forfeited/requested` columns on
  `coin_movement` are write-once event facts and are not summed to produce balances.
- Test: reconcile `currentBalance(...)` against an independent `SUM` query after a series of mixed
  movements — they always match (SC-003).

## I7 — Per-server isolation

- Every ledger read/write and the balance `SUM` is keyed by `guild_id`. A movement in one guild
  never affects another guild's balances, history, cap, or moderator-role configuration.
- Test: identical members adjusted in two guilds keep independent balances and histories (FR-023).

## I8 — Non-transferable

- The only posting shapes are MEMBER↔TREASURY (grant/deduct) and TREASURY↔FORFEIT (forfeiture).
  No code path posts from one `MEMBER` account to another, and no command exposes a transfer.
- Test/Review: the command surface contains no transfer/gift/trade action, and no
  `PostingPlan` produced by `CoinLedgerPolicy` contains two `MEMBER` lines (FR-006, SC-006).
