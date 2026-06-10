# Quickstart & Validation: Coin Economy & Append-Only Audit Trail

This guide proves the coin ledger works end to end on top of the running foundation
(`001-foundation-skeleton`). It references the data model, contracts, and invariants rather than
duplicating them; implementation detail belongs in `tasks.md` and the implementation phase.

## Prerequisites

- Everything from `001-foundation-skeleton` working: Docker running, dev Postgres up
  (`docker compose up -d`), `.env` populated (`DISCORD_TOKEN`, `DB_*`).
- The bot invited to a test server where you have **Administrator** (to run `/coins-config`).
- No new environment variables and no new dependencies are introduced by this feature.

## Run

```powershell
# from repo root
docker compose up -d                                   # dev Postgres (if not already up)
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" # boots, applies Flyway V2 automatically
```

On startup Flyway applies `V2__coin_ledger.sql` (tables + append-only/balanced/non-negative
triggers). The three commands are upserted to every guild the bot is in (001's registrar).

## Validate (maps to spec Success Criteria)

### 1. Configure the server (SC — enables US1)

- Run `/coins-config role:@Mods cap:100`.
- **Expect**: `✅ Coin-moderator role: @Mods · cap: 100.`
- Before this, `/coins-adjust` MUST refuse with "No coin-moderator role is configured" (fails
  closed — edge case).

### 2. Grant and deduct, with attribution (SC-002, SC-004 — US1)

- As a member holding `@Mods`, run `/coins-adjust grant member:@Alice amount:50 reason:"event win"`.
  - **Expect**: success, `New balance: 50/100`.
- Run `/coins-adjust deduct member:@Alice amount:20 reason:"penalty"`.
  - **Expect**: success, `New balance: 30/100`.
- Run `/balance` as Alice.
  - **Expect**: balance `30`, history newest-first showing the deduction then the grant, each with
    the moderator and reason (FR-011/013).

### 3. Overdraw fails atomically (SC-001, SC-007 — US1 scenario 3)

- Run `/coins-adjust deduct member:@Alice amount:100`.
- **Expect**: `❌ That would overdraw …; nothing changed.` Alice's `/balance` is still `30` and
  **no** new history entry exists.

### 4. Cap forfeiture at earning time (FR-007/018/019 — US1 scenario 4)

- With Alice at `30/100`, run `/coins-adjust grant member:@Alice amount:90`.
- **Expect**: credited `70`, forfeited `20`, `New balance: 100/100`. `/balance` shows the grant
  with `forfeited 20`.
- Grant again `amount:10` (already at cap): **Expect** credited `0`, forfeited `10`, balance stays
  `100/100`, and a movement is still recorded (audit shows the forfeiture).

### 5. At-most-once (SC-005)

- This is asserted by integration tests (`CoinIdempotencyConcurrencyTest`): submitting the same
  `interactionId` twice (and concurrently) yields exactly one movement. Manually, a Discord
  double-click produces distinct interaction ids, so manual repetition is expected to apply twice
  — the guarantee is about retries of the *same* invocation. Trust the test here.

### 6. Append-only & derived balance (SC-002, SC-003)

- Asserted by `CoinLedgerTriggersTest` / `JpaCoinLedgerAdapterTest`:
  - `UPDATE`/`DELETE` on `coin_movement` / `coin_ledger_entry` are rejected by triggers.
  - An unbalanced movement is rejected at commit; a balanced one commits.
  - `currentBalance(...)` always equals an independent `SUM` of the member's entries.

### 7. Per-server isolation (FR-023)

- Asserted by integration test: the same member adjusted in two different guild ids keeps
  independent balances and histories.

## Automated validation

```powershell
docker info                 # Docker must be running (host daemon backs Testcontainers)
./mvnw -q verify            # unit (policy/service) + integration (ledger, triggers, idempotency, concurrency)
```

`./mvnw -q verify` MUST pass (Constitution VI; CI gate from 001). Key tests:

| Test | Proves |
|------|--------|
| `CoinLedgerPolicyTest` (unit) | grant/cap/forfeit/deduct/overdraw arithmetic; balanced plans |
| `AdjustCoinsServiceTest` (unit, Mockito) | authorization (role/admin/unset), idempotency mapping |
| `JpaCoinLedgerAdapterTest` (integration) | append, derived `SUM` balance, history order, find-by-interaction |
| `CoinLedgerTriggersTest` (integration) | append-only (I1), balanced (I2), non-negative (I3) |
| `CoinIdempotencyConcurrencyTest` (integration) | at-most-once (I4) + race-free overdraw/cap (I5) |

## Done when

- Steps 1–4 behave as described live, and `./mvnw -q verify` is green (SC-001..SC-007 covered by
  the test matrix above).
