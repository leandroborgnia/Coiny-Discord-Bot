# Quickstart & Validation: Game Queue & Weekly Rotation

A run/validation guide proving the feature end-to-end. Implementation detail lives in
[data-model.md](./data-model.md) and [contracts/](./contracts/); this file shows how to **verify**.

## Prerequisites
- Docker Desktop running (Postgres + app come up together via the launch scripts).
- A Discord application/bot **with the privileged `GUILD_PRESENCES` and `GUILD_MEMBERS` intents
  enabled** in the Developer Portal (required to read members' Rich Presence — see plan §Complexity).
- Coins exist (feature 002): grant test members coins via `/coins-adjust grant`.
- *(Optional)* IGDB cover art: set `IGDB_CLIENT_ID` / `IGDB_CLIENT_SECRET` (Twitch app
  client-credentials) in the environment the launch script injects. **Without them, art falls back to
  name-only — everything else works.**

## Build & test (host, no secrets)
```bash
./mvnw -q verify          # unit (domain policies) + Testcontainers integration; must stay green
./mvnw spotless:apply     # format
```
`verify` needs Docker running but **no** Discord/IGDB secrets: the IGDB resolver is a disabled no-op
without credentials, and presence isn't exercised in tests.

## Run (dev)
```bash
scripts/up-dev.sh         # or scripts/up-dev.ps1 on Windows — prompts for DB_PASSWORD + DISCORD_TOKEN
```

## Validation scenarios (mapped to user stories)

### US1 — Propose (P1, MVP)
1. While **playing a game**, run `/queue-propose`. → Slot added at the tail; **1 coin** deducted
   (check `/balance`). *(FR-001, SC-002.)*
2. From an **empty** server (no current game), the **first** `/queue-propose` → **instant-pop**: it
   becomes the current week's game immediately, not queued. *(FR-024, SC-010.)*
3. With **activity sharing off / not playing**, `/queue-propose` → rejected, **no charge**, guidance
   to check activity settings. *(FR-035, SC-011.)*
4. Run `/queue-propose` again while already queued (now playing a different game) → existing slot's
   game **replaced**, **free**, same position, upvotes reset. *(FR-034.)*
5. Spam the same propose (duplicate interaction) → added **at most once**, charged **at most once**.
   *(FR-015, SC-003.)*
6. With balance **below** the propose cost → fails completely (no slot, no deduction). *(FR-002.)*
7. `/queue-withdraw` a still-queued slot → slot removed, coins **refunded**, pot reduced by the same.
   *(FR-033, SC-015 — verify via `/balance` and the ledger sum.)*

### US2 — Weekly rotation (P2, MVP)
1. With a populated queue, let the rolling-7-day tick fire (or invoke the advance in an integration
   test) → top slot designated, removed, remaining shift up by one. *(FR-008, SC-001/004.)*
2. Advance again for the **same** week → no-op (designation unchanged). *(FR-016, SC-004.)*
3. Empty queue at advance → no designation that week; cooldowns **not** decremented. *(FR-009/012.)*
4. Stop the bot, wait past one or more 7-day boundaries (or simulate `last_pop_at` in the past),
   restart → catch-up applies **each missed advance exactly once**; only the **final** game is
   announced once. *(FR-032/036, SC-014.)*

### US3 — View (P3)
1. `/queue-view` → **ephemeral** embed: current game with key art, next 5 (key art, proposer,
   position, upvote count), your own entry always shown/marked even beyond top 5. *(FR-028, SC-012.)*
2. While in a "wait N games" cooldown, the view shows you're not yet eligible and how many games
   remain. *(FR-013, US3 sc.4.)*
3. Empty state → "no current game, queue empty." *(US3 sc.5.)*

### US4 — Bump (P4)
1. `/queue-bump` your non-top slot → swaps up exactly one; **1 coin** deducted; displaced game now one
   lower, still queued. *(FR-004/007, SC-009.)*
2. Bump at the **top** / **unaffordable** → fails completely, no reorder, no deduction. *(FR-006.)*

### US5 — Upvote (P4)
1. Press a slot's **upvote button** in `/queue-view` → count rises by one; press again → returns.
   *(FR-029, US5 sc.1–2.)*
2. Run `/queue-view` twice, press upvote in both renders → counted **once**; the second is a no-op.
   *(FR-031, SC-013.)*
3. With an announcement channel configured, each upvote edits **only the latest** announcement
   message's counts; ephemeral/older messages stay snapshots. *(FR-038, SC-017.)*

### Cover art (cross-cutting)
1. Propose a game whose Rich Presence carries an image → that asset shows. *(FR-027 step 1.)*
2. Propose a game with no RP image, IGDB configured → IGDB cover shown and **cached**; a second slot
   of the same game does **not** re-query IGDB. *(FR-027 steps 2–3.)*
3. Propose with no RP image and IGDB unavailable/unconfigured → **name only**, no thumbnail, and the
   propose still succeeds (art never blocks it). *(FR-027 step 4.)*

### "Wait N games" cooldown (cross-cutting, the subtle rule)
1. Member's game pops with N others waiting → they cannot propose until **N more games are played**;
   `N = 0` ⇒ eligible immediately. *(FR-011, SC-005.)*
2. Proposals made after the pop don't change the remaining count; empty weeks don't count it down.
   *(FR-012, SC-006.)*

## Constitution self-check at validation time
- `/balance` reflects every propose/bump/withdraw (one shared ledger; no second economy). ✓
- All-or-nothing on every unaffordable/ineligible/duplicate action (advisory locks + idempotency). ✓
- `./mvnw verify` green with **no** secrets; tests on the host, Testcontainers Postgres. ✓
- Only V3 added; V2 unchanged. Config in `application.yml`; IGDB creds are env vars. ✓
