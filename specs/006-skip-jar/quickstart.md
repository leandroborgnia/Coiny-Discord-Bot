# Quickstart — Skip Jar Validation

Runnable validation that the skip jar works end-to-end. Prerequisites and the contract details live in
the linked artifacts; this guide is the run/validate path, not implementation code.

## Prerequisites

- Docker Desktop running (Postgres 17 + Testcontainers; CLAUDE.md "Prerequisites").
- Feature 004 (game queue) and 005 (participation earning) present — the skip jar reads the current
  run ([data-model.md](./data-model.md) §"State read from existing tables") and the earner set
  ([contracts/ledger-and-rotation.md](./contracts/ledger-and-rotation.md) §C).
- For the live bot only: the privileged intents from features 004/005 (presence/voice). The **test
  suite needs no secrets** and never runs in a container (Principle VI).

## Automated validation (the primary gate)

```bash
./mvnw -q verify        # unit + Testcontainers integration; must pass after each task
```

This is the authoritative check. The scenarios below map to user stories; each has a corresponding
test (pure-domain unit tests for arithmetic; Testcontainers Postgres for persistence/ledger/rotation).

| # | Scenario (spec) | Layer | Asserts |
|---|------------------|-------|---------|
| 1 | Threshold = `max(⌊N/2⌋+1, floor)`; floor governs small earner sets (US2 AS-3, edges) | unit | `SkipThresholdPolicy` for N = 0,1,2,5,6 with floor 3 |
| 2 | Contribution posts balanced `MEMBER −1 / SKIP_POT +1`; <1 coin throws (US1 AS-1/AS-4) | unit | `SkipJarLedgerPolicy.planContribution` |
| 3 | Exactly one coin debited, recorded as `SKIP_JAR`, counted (US1 AS-1, SC-001) | integration | balance −1; movement type; jar count = 1 |
| 4 | Second contribution for same run refused, no charge, count unchanged (US1 AS-2, SC-002) | integration | `AlreadyContributedException`; PK; balance unchanged |
| 5 | Gate on + non-earner refused, no charge (US1 AS-3, SC-003) | integration | `NotEligibleToContributeException` |
| 6 | Gate off ⇒ any member may contribute (US1 AS-5) | integration | accepted without earner check |
| 7 | Zero balance refused (US1 AS-4, FR-006) | integration | `OverdrawException`; jar unchanged |
| 8 | Jar closed during dwell (edge "Dwell not yet elapsed", SC-004) | integration | `JarClosedException`; no charge |
| 9 | Threshold-meeting contribution retires the game & advances one step (US2 AS-1, SC-006) | integration | current game changes; rotation +1; same rules as weekly |
| 10 | One short of threshold ⇒ nothing advances (US2 AS-2) | integration | current game unchanged; jar accumulates |
| 11 | After a skip, new game's jar is empty; retired contributions don't count (US2 AS-4, SC-010) | integration | count(newWeek) = 0 |
| 11b | A normal weekly advance (no skip) before the jar triggers also resets it (FR-012 edge) | integration | count(newWeek) = 0; prior-run rows don't count |
| 12 | Concurrent threshold-meeting contributions ⇒ exactly one advance (US2 AS-5, FR-011) | integration | one pop; second refused (dwell reset) |
| 13 | Non-refundable across skip / weekly advance / departure (SC-007) | integration | no QUEUE_REFUND-style reversal; SKIP_POT retains coins |
| 14 | Status shows count/threshold/remaining; not-open during dwell; no-game without error (US3) | integration | `SkipJarStatus` states |
| 15 | Admin sets floor/dwell/gate; non-admin refused (US4, SC-009) | integration | config persisted; `ModeratorNotAuthorizedException` |

## Manual smoke test (live bot, optional)

After `scripts/up-dev.ps1` (or `.sh`) with a current game and the dwell elapsed:

1. **Contribute** — `/skip contribute` as a member who has earned coins from the current game →
   "Paid 1 coin… {count}/{threshold}". Run `/skip contribute` again → refused, no second charge.
2. **Gate** — as a non-earner with the gate on → refused. `/skip-config gate enabled:false`, retry →
   accepted.
3. **Status** — `/skip status` shows count, threshold, remaining. On a fresh game (within dwell) it
   shows "opens {when}".
4. **Trigger** — drive contributions to the threshold (lower it with `/skip-config floor value:2` and
   a couple of testers) → the current game is retired and the next game is announced, exactly like a
   weekly advance. `/skip status` now shows the new game's empty jar.
5. **Dwell** — `/skip-config dwell hours:48`; on a game newer than 48 h, `/skip contribute` is refused
   as not-yet-open.
6. **Non-refundable** — confirm `/balance` history shows the `SKIP_JAR` debit and no refund after a
   skip.

## What "done" looks like

- `./mvnw -q verify` green (no secrets, not in a container).
- The migration is **V5** only; V1–V4 unchanged (`git diff` touches no prior migration).
- A contribution is one balanced `SKIP_JAR` movement (`MEMBER −1 / SKIP_POT +1`), never reversed.
- An early skip reuses `AdvanceRotationService` (one pop, same deterministic rules) — no duplicated
  advance logic.
- Floor, dwell, gate, and all contributions are per-server isolated.
