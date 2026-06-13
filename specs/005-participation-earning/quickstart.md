# Quickstart — Participation Earning (validation guide)

End-to-end validation mapped to the user stories. Most assertions are **integration tests on real
Postgres** (Testcontainers) and **pure unit tests** for the accrual arithmetic; a small manual smoke
test exercises the live JDA sweep. The live sweep needs the privileged `GUILD_PRESENCES` + the
`GUILD_VOICE_STATES` + `GUILD_MEMBERS` intents enabled in the Discord Developer Portal.

## Prerequisites

- Docker running (Postgres for app + Testcontainers for tests).
- For the manual smoke test only: a bot token with the intents above, and a test guild where you can
  join a voice channel while playing a game whose Rich Presence is visible.

## Automated checks

```bash
./mvnw -q verify          # unit + integration; needs Docker, no secrets
```

Key suites this feature adds (no network, no token):

- `ParticipationAccrualPolicyTest` (pure): `elapsedToCredit` returns 0 for null/`>maxGap`, the real
  delta otherwise; `dropsReady` splits banked seconds into whole drops + remainder.
- `AccrueParticipationServiceTest` (Mockito ports): cap-pause, fresh-session, single-drop, multi-drop,
  cap-crossing forfeiture, banked-remainder persistence.
- `JpaParticipationAccrualAdapterTest` / persistence (Testcontainers): upsert round-trip, **no
  double-credit** under a replayed tick, negative-id namespacing (`interaction_id < 0`, never collides),
  cap forfeiture posts `MEMBER`+`FORFEIT` and leaves the derived balance at the cap.
- `ConfigureParticipationServiceTest`: add/reset channels, set rate, toggle free-proposal, and the
  moderator-role authorization (unauthorized changes nothing).
- `ProposeGameServiceTest` (extended): free-proposal waiver in the bootstrap state; normal cost when
  the flag is off / a current game exists / the queue is non-empty.

## US2 — Designate participation voice channels (P2)

1. As a member with the configured moderator role: `/participation-config channel-add channel:#play`.
   → reply confirms; `participation_voice_channel` has `(guild, #play)`.
2. `/participation-config channel-add channel:#play2` → both rows present (add doesn't remove).
3. `/participation-config channel-reset` → no rows for the guild.
4. As a member **without** the role (and not Administrator): any of the above → `coin.error.*`
   (not-authorized / role-not-configured), set unchanged. **(SC-007, SC-008)**

## US1 — Earn by playing the current game in a designated channel (P1)

Set rate to make a drop quick for testing: `/participation-config rate minutes-per-drop:1
coins-per-drop:1` (one coin per minute of qualifying play).

Integration/manual scenario (with a designated channel and a **current week's game** set via the
queue feature):

1. A member joins `#play` while playing the current game. After ≥ 1 minute of sweep ticks they are
   credited 1 coin; `/balance` shows the new total and a participation history line. **(SC-001)**
2. A member in `#play` playing a **different** game (or nothing readable) → no credit. **(SC-002)**
3. A member playing the current game but **not** in a designated channel → no credit. **(SC-003)**
4. Near the cap: the crossing drop credits only up to the cap and records the forfeiture; further
   ticks **pause** (no new records) while at cap; spending coins (e.g., a queue propose) lets accrual
   resume. **(SC-004)**
5. Disconnect mid-drop, rejoin later: the banked remainder persisted; a gap `> max-gap` starts a fresh
   session (no retroactive credit for the away time). **(SC-005, SC-010)**
6. No current game designated → nobody earns. **(FR-011)**

Restart safety: stop the bot mid-session, restart — time during downtime is **not** credited; the
member resumes accruing from the next observed tick. **(SC-010)**

## US3 — Participation visible in coin history (P3)

1. After earning, `/balance` lists the earning as a **credit** line clearly labelled participation
   (not "deducted"), distinct from moderator grants/deductions and queue spends. **(SC-006)**
2. A cap-truncated drop shows both credited and forfeited amounts (reuses the existing forfeiture
   suffix). A member who never earned shows no participation lines and no error.

## US4 — Free-first-proposal bootstrap (P4)

1. `/participation-config free-proposal enabled:true`. With **no** current game and an **empty
   queue**, a member with 0 coins proposes a game → accepted, **0 coins charged**, becomes the current
   game (instant-pop). `/balance` shows no propose spend. **(SC-009)**
2. With the flag **off** in the same empty state → normal propose cost applies (charged, or rejected
   for insufficient balance).
3. With the flag **on** but a current game already exists (or the queue is non-empty) → normal cost
   applies (waiver is scoped to the recurring empty state).
4. The empty state recurs (e.g., a play lull drains the queue, or coin-holders leave): the waiver
   applies again each time — not only at server start.

## Expected outcomes summary

| Story | Proof |
|-------|-------|
| US1 | drops minted at the configured rate; only current-game + designated-channel time earns; cap respected |
| US2 | add/reset channels gated by the moderator role; empty set ⇒ no earning |
| US3 | participation credit labelled distinctly in `/balance` history |
| US4 | propose cost waived only in the recurring no-current-game + empty-queue state |
