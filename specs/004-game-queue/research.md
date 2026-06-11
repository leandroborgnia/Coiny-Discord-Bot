# Phase 0 Research: Game Queue & Weekly Rotation

Resolves every unknown the spec deferred to the plan. Each entry is **Decision / Rationale /
Alternatives considered**.

## 1. Capturing the game — Rich Presence reality for *other* members

**Decision**: At propose time the thin handler **fetches the proposer's presence on demand** (see §2)
and takes the **first activity with `ActivityType.PLAYING`**. From it we capture a `CapturedGame`
snapshot: `applicationId` (`Activity.asRichPresence()` is usually `null` for non-self users, so this
is often absent), `name` (always present), and any available `details`, `state`, large/small image
references. If no PLAYING activity exists → `NoGameActivityException` (rejected, no charge, FR-035).
The full snapshot is stored as `jsonb` so future matching can improve. **Anyone currently playing a
game may propose** — there is no voice-channel requirement in this feature (voice-scoped capture is a
deliberately deferred later spec).

**Rationale**: JDA exposes activity **name and type** for any member with the `GUILD_PRESENCES`
intent, but full `RichPresence` detail (application id, asset keys) is generally only populated for
the self-user. The design therefore treats the **name** as the reliable identity input and image
assets as a best-effort bonus — which is exactly why the art chain has an IGDB fallback (most slots
will have no usable Rich-Presence image).

**Alternatives considered**: (a) Free-text title — rejected by the user (no game-ID catalog/autocomplete
for bots). (b) Requiring `asRichPresence()` assets — rejected: usually null for other members, would
make most proposals artless or rejected.

## 2. Sourcing presence — on-demand fetch, minimal retention

**Decision**: Build JDA with `GatewayIntent.GUILD_PRESENCES` + `GatewayIntent.GUILD_MEMBERS` and
`CacheFlag.ACTIVITY`, but a **non-retaining** member cache (`MemberCachePolicy.NONE`) and **no eager
member chunking** — the bot does **not** hold a roster of members/presences in memory. Instead, the
propose handler reads the proposer's activity **on demand, after deferring**, via
`guild.retrieveMembersByIds(true /* includePresence */, memberId)` (verified present in JDA 5.2.1:
`Task<List<Member>> retrieveMembersByIds(boolean, long...)`). The returned `Member` carries
`getActivities()`; we read it once, capture the snapshot, persist it, and keep nothing. Both intents
are **privileged** and must be enabled in the Discord Developer Portal.

**Rationale**: The misconception worth recording — `GUILD_PRESENCES` is an **all-or-nothing
connection-level subscription**; once on, Discord streams *every* member's `PRESENCE_UPDATE` and JDA
parses all of them, and there is **no REST endpoint** to fetch a single member's activity. The only
thing the bot controls is **retention**, not reception. A non-retaining policy lets the unavoidable
presence stream be processed and immediately GC'd (no roster held), while
`retrieveMembersByIds(true, …)` performs a targeted gateway member request *with presence* at the one
moment we need it — propose time. Because the interaction is already deferred (≤ 15 min window), the
extra gateway round-trip is not a latency problem, and member-request rate limits are a non-issue at
propose volume. Memory stays flat regardless of how many thousands are online — which was the user's
explicit concern.

**Alternatives considered**: (a) `MemberCachePolicy.ONLINE`/`ALL` retaining online/all members and
reading from cache — works, but retains presence for every online member (memory grows with
membership); rejected in favor of the lazy fetch. (b) `MemberCachePolicy.VOICE` + requiring the
proposer to be in a (preconfigured) voice channel — viable and aligns with the future audio-channel
spec, but it restricts *who may propose*; the user chose to keep propose open to anyone playing and
defer voice-scoping. (c) REST `retrieveMemberById` — returns roles/profile but **no** presence, so it
cannot source the activity. The privileged intents + on-demand fetch are recorded as a
Complexity-Tracking deviation (privilege + privacy surface).

## 3. Reusing the coin ledger (no second economy)

**Decision**: All queue coin movement posts into the **existing** `coin_ledger_entry` via the
existing `CoinLedgerPort.append(NewMovement, PostingPlan)`, so member balances remain a single
derived `SUM(MEMBER)` shared with `/balance`. A new **`POT`** ledger account is the balanced
counter-party: a spend posts `MEMBER −cost` / `POT +cost`; a refund posts `MEMBER +amount` /
`POT −amount` as a new reversing movement (posted rows are never mutated). The `coin_movement`
header is reused with new `type` values `QUEUE_PROPOSE`, `QUEUE_BUMP`, `QUEUE_REFUND`;
`moderator_id` carries the **actor = the member themselves** (a self-initiated spend), so no column
nullability change is needed.

**V3 changes to the shipped ledger (additive only)**:
`ALTER TABLE coin_ledger_entry DROP CONSTRAINT coin_ledger_entry_account_check, ADD CONSTRAINT
coin_ledger_entry_account_check CHECK (account IN ('MEMBER','TREASURY','FORFEIT','POT'));` and the
analogous extension of `coin_movement_type_check` to include the three queue types. V2's file is
untouched — these run in V3.

**Rationale**: The user requirement is explicit: "do not build a second economy." Posting into the
same table keeps balances correct everywhere and inherits V2's deferred **balanced-movement** and
**non-negative MEMBER** constraint triggers for free. POT is not checked for non-negativity (it only
ever receives spends and reverses them), matching the existing TREASURY/FORFEIT treatment.

**Alternatives considered**: (a) A separate queue ledger table — rejected: `/balance` would not see
queue spends, splitting the economy. (b) Recording spends as the existing `DEDUCTION`/`GRANT` types
with a POT counter-account — rejected: muddies audit/history semantics; explicit queue types render
clearly and filter cleanly. (c) Adding a `slot_id` FK to `coin_movement` for refund math — rejected
in favor of a `coins_spent` running total on the slot (simpler, no header change). The constraint
names (`coin_ledger_entry_account_check`, `coin_movement_type_check`) are Postgres's deterministic
auto-names for those single inline column checks in V2.

## 4. Affordability + queue mutation atomicity, and lock ordering

**Decision**: Each mutating service runs in one `@Transactional` unit and acquires, **in this order**:
(1) the **queue** advisory lock `pg_advisory_xact_lock(hash(guildId, QUEUE_SALT))`, then (2) the
existing per-member **account** advisory lock `CoinLedgerPort.lockAccount(guildId, memberId)`.
Affordability is read after locking (`currentBalance`), the spend is built by the pure
`QueueLedgerPolicy` (throws `InsufficientCoinsException` if `balance < cost`, rolling back → nothing
posted), and the queue row mutation + ledger append commit together.

**Rationale**: The queue lock serializes structural changes (tail position, swaps, pop) per guild;
the account lock prevents double-spend on the member balance. A fixed acquire order (queue→account)
prevents deadlock between concurrent propose/bump/withdraw and rotation. Postgres advisory xact locks
auto-release at commit/rollback (Principle IV: one atomic source of truth, no read-then-write race).

**Alternatives considered**: A single combined lock — rejected: rotation needs the queue lock without
a specific member; keeping them separate lets the scheduler lock only the queue.

## 5. Idempotency keys

**Decision**:
- **Propose / bump / withdraw** → the Discord `interactionId`. The reused `coin_movement.interaction_id`
  is `UNIQUE` (`ON CONFLICT DO NOTHING`); the `queue_entry.propose_interaction_id` is also `UNIQUE`.
  A duplicate returns the existing outcome without re-mutating.
- **Weekly advance** → per-guild monotonic `week_number`; `weekly_designation` has
  `UNIQUE(guild_id, week_number)` with `ON CONFLICT DO NOTHING`, so a double tick / catch-up overlap
  is a no-op.
- **Upvote toggle** → `(slot_id, member_id)` is the `PRIMARY KEY` of `queue_upvote`; insert
  `ON CONFLICT DO NOTHING`, remove is a delete; a duplicate press across multiple ephemeral renders
  causes no state change (Principle IV; FR-031).

**Rationale**: Mirrors the proven V2 idempotency pattern (`interaction_id` UNIQUE + ON CONFLICT).

## 6. "Wait N games" cooldown mechanics

**Decision**: `queue_cooldown(guild_id, member_id, games_remaining)`. When a member's slot is popped,
record `N` = count of **other** still-QUEUED slots for that guild at that instant; set their
`games_remaining = N`. On **every** real pop (a game actually played) decrement all of that guild's
rows: `UPDATE … SET games_remaining = games_remaining − 1 WHERE guild_id = ? AND games_remaining > 0`.
Empty weeks (no designation) do **not** decrement. A member may propose when they have no QUEUED slot
**and** (`games_remaining = 0` or no row). Bootstrap instant-pop sets `N = 0` → immediately eligible.

**Rationale**: Directly encodes FR-011/FR-012 and SC-005/SC-006 — fixed at pop, counts only played
games, equalizes active vs. weekly participants.

**Alternatives considered**: Deriving the cooldown from rotation-log positions — rejected: more
fragile across empty weeks than a decrement counter.

## 7. Rolling-7-day rotation & downtime catch-up

**Decision**: `queue_rotation_state(guild_id, current_slot_id, current_week_number, last_pop_at)`.
`RotationPolicy.advancesDue(lastPopAt, now)` = `floor((now − lastPopAt) / 7d)` (0 if `lastPopAt`
null/bootstrap not started). A `@Scheduled` tick (hourly) and an `ApplicationReadyEvent` startup
hook both call `AdvanceRotationService` which, under the queue lock, applies **each** due advance
exactly once (pop top → designate `week_number+1` via `ON CONFLICT DO NOTHING` → decrement cooldowns
→ set `last_pop_at += 7d` per applied period). After catch-up, **only the final** current game is
announced once (FR-036).

**Rationale**: Elapsed-time derivation makes the bot safe to be offline at the boundary (FR-032,
SC-014). Advancing `last_pop_at` by exactly 7 d per applied period (not to `now`) preserves the
schedule phase.

**Alternatives considered**: Fixed calendar/timezone boundary — rejected by the spec clarification
(rolling 7 days, no calendar alignment). A cron at a wall-clock time — rejected: doesn't survive
downtime cleanly.

## 8. Bootstrap instant-pop

**Decision**: In `ProposeGameService`, if there is **no current game** (rotation_state absent or
`current_slot_id` null and queue empty), the proposed slot is **immediately designated** week 0's
game instead of being queued: it is created already PLAYED, written to `weekly_designation`, sets
`last_pop_at = now`, and records the proposer's cooldown with `N = 0`. The coin spend still happens
(propose cost). Same path applies whenever an advance found the queue empty and a later proposal is
the first to arrive.

**Rationale**: FR-024 / SC-010 — never a dead first week. `N = 0` because no others were waiting.

## 9. Cover-art resolution chain & cache keying

**Decision**: Resolution happens **at render time** (view/announcement), never at propose. Per slot,
in order: (1) if the captured Rich-Presence snapshot carries an image asset URL, use it; else
(2) look up `game_art_cache` by **game identity** = `applicationId` when present, else
`normalize(name)` (lowercase, trim, collapse whitespace, strip trailing launcher tags) — cache hit →
use it; (3) cache miss → query IGDB, **store the result (hit or miss) in the cache**, use it;
(4) IGDB miss/failure → render **name-only** (no thumbnail). A miss is cached as `source = NONE` so
IGDB is queried **at most once per identity**. Art lookup is wrapped so any exception degrades to
name-only and **never** blocks/fails the interaction.

**Rationale**: Matches the user's decided chain. Caching keeps IGDB calls bounded and makes the
live-announcement upvote edits cheap (they read cached art, never IGDB).

**Alternatives considered**: Resolving art at propose time — rejected: would put a network call on the
propose path (violates "never block propose"). TTL re-validation of misses — deferred (optional later;
not needed for correctness).

## 10. IGDB integration, auth & secrets

**Decision**: `IgdbArtResolver` (in `bot.infrastructure.art`) uses the JDK `java.net.http.HttpClient`.
Auth is **Twitch OAuth client-credentials**: POST `id.twitch.tv/oauth2/token` with
`IGDB_CLIENT_ID`/`IGDB_CLIENT_SECRET`, cache the bearer token until expiry, then POST the IGDB
`/v4/games` (+ `/covers`) query with `Client-ID` + `Authorization: Bearer`. Returns
`Optional<String>` cover URL. **Credentials are `${...}` env vars** injected by Compose (added to
`compose.yaml` and `compose.prod.yaml`), never committed. When either credential is **blank/absent**,
the resolver is a no-op returning empty → art chain falls to name-only. JSON is parsed with Jackson
supplied by `spring-boot-starter-json` — the **one** new compile dependency, because JDA's
`jackson-databind` is **runtime**-scope only and therefore not on the compile classpath (see
§Dependencies).

**Rationale**: JDK `HttpClient` adds **no Maven dependency**; only compile-time JSON parsing needs one
(`spring-boot-starter-json`, recorded in plan.md per Constitution). Disabling without creds keeps
`./mvnw verify` green and secret-free (Principle VI/VII).

**Alternatives considered**: Spring `RestClient` — needs `spring-web`; avoided to not pull a web stack.
A dedicated IGDB Java SDK — unnecessary weight for two endpoints.

## 11. Scheduling & startup catch-up

**Decision**: A `SchedulingConfig` with `@EnableScheduling`; `RotationScheduler` runs a `@Scheduled`
fixed-delay tick (interval from `queue.rotation.tick`, default hourly) iterating guilds, and an
`@EventListener(ApplicationReadyEvent)` runs the same catch-up once at boot. Both are guarded by
`discord.enabled` so tests don't schedule. No new dependency (spring-context).

**Rationale**: Time-derived advance + idempotent designation make frequent ticks safe and cheap.

## 12. Button interaction routing

**Decision**: Add a `ButtonHandler` interface (`String prefix()`, `void handle(ButtonInteractionEvent)`)
and a `ButtonInteractionRouter extends ListenerAdapter` (mirrors `InteractionRouter`) that dispatches
`onButtonInteraction` by `componentId` prefix. The upvote button id encodes the slot:
`upvote:{slotId}`. The handler `deferEdit()`-acks (no ephemeral re-render), calls `UpvoteService`,
and the service result drives the **announcement** edit (FR-038), not the ephemeral message.

**Rationale**: The existing `InteractionRouter` only handles slash commands; a parallel router keeps
handlers thin and discoverable as Spring beans.

## 13. i18n bundle merge

**Decision**: Add `messages/queue-messages.properties` and set
`spring.messages.basename: messages/coin-messages,messages/queue-messages` in `application.yml`.
A `QueueMessages` helper mirrors `CoinMessages`.

**Rationale**: Spring `MessageSource` supports a comma-separated basename list; keeps copy out of
handlers and `DomainException` keys resolvable, consistent with feature 002.

## 14. Authorization (reuse the existing Discord-permission check)

**Decision**: `/queue-config` (set costs, announcement channel) authorizes on the **Manage Server**
permission — `member.hasPermission(Permission.MANAGE_SERVER)`, the same `hasPermission` mechanism
feature 002's `/coins-config` already uses (it checks `ADMINISTRATOR`). Manage Server is both the
Discord-layer `DefaultMemberPermissions` filter **and** the authoritative in-service check, so the two
never disagree (resolves analysis finding I1). No custom role/permission model is introduced; queue
config has no coin-moderator-role coupling and no role-not-configured precondition. Manage Server (not
Administrator/owner) is the intended bar — lighter than `/coins-config`, which stays Administrator
because it governs *who controls the economy*.

**Rationale**: Honors "do not build a second authorization mechanism" — a built-in Discord permission
check is the *same* mechanism already in use, not a new one — while giving queue configuration an
appropriately light, big-server-friendly bar.

## Dependencies (recorded per Constitution before any build change)

| Dependency | Status | Notes |
|------------|--------|-------|
| `@EnableScheduling` (spring-context) | Already on classpath | Weekly tick + startup catch-up. No new artifact. |
| JDK `java.net.http.HttpClient` | JDK built-in | IGDB + Twitch OAuth. No new artifact. |
| `spring-boot-starter-json` (Jackson) | **New compile dependency** (BOM-managed version) | Parse IGDB responses. JDA's `jackson-databind` is **runtime**-scope only, so a compile-scope binding is required; added in T005, no web server. |
| `GUILD_PRESENCES` + `GUILD_MEMBERS` intents | Config + dev-portal toggle | Privileged; Complexity Tracking item. |
| IGDB / Twitch credentials | Env-var secrets via Compose | `IGDB_CLIENT_ID`, `IGDB_CLIENT_SECRET`; optional (art disabled when blank). |

Exactly one new Maven coordinate is required — `spring-boot-starter-json` (above). It is reflected in
`pom.xml` only after this record, per the Development Workflow gate.
