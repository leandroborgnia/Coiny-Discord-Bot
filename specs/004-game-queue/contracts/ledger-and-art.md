# Contract: Coin-Pot Ledger Reuse & Cover-Art Resolution

## A. Coin postings (reuse the V2 append-only double-entry ledger)

Queue coin movement goes through the **existing** `CoinLedgerPort` and lands in `coin_ledger_entry`,
so member balances stay one derived `SUM(MEMBER)` shared with `/balance`. The balanced counter-party
is the new **`POT`** account (added to `LedgerAccount` and to the `coin_ledger_entry` CHECK in V3).

| Event | Movement `type` | Posting lines (sum = 0) | `coins_spent` effect |
|-------|-----------------|--------------------------|----------------------|
| Propose | `QUEUE_PROPOSE` | `MEMBER −proposeCost`, `POT +proposeCost` | slot `coins_spent = proposeCost` |
| Bump | `QUEUE_BUMP` | `MEMBER −bumpCost`, `POT +bumpCost` | slot `coins_spent += bumpCost` |
| Withdraw refund | `QUEUE_REFUND` | `MEMBER +coinsSpent`, `POT −coinsSpent` | slot removed |
| Replace | *(none)* | *(free — no movement)* | unchanged |
| Bootstrap instant-pop | `QUEUE_PROPOSE` | `MEMBER −proposeCost`, `POT +proposeCost` | slot `coins_spent = proposeCost` |

Rules:
- `NewMovement` reuses the V2 header; `moderator_id` carries the **acting member's own id**
  (self-initiated). `interaction_id` is the slash interaction id (idempotency, `ON CONFLICT DO NOTHING`).
- Affordability is checked **after** `lockAccount`; `QueueLedgerPolicy.planSpend` throws
  `InsufficientCoinsException` if `balance < cost` → rollback (nothing posted).
- Refund is a **new reversing movement**, never an edit/delete of posted rows (Principle III).
- POT is never non-negativity-checked (only ever receives spends and reverses them) — same as
  TREASURY/FORFEIT; the V2 non-negative trigger guards MEMBER only.
- The V2 deferred **balanced-movement** trigger enforces `SUM = 0` for every queue movement.

`QueueLedgerPolicy` (pure, `bot.domain.queue`) builds these `PostingPlan`s from `bot.domain.coin`
types — no I/O, unit-tested without a DB.

## B. Cover-art resolution chain (render time only — never blocks/fails propose)

Resolve a slot's image when rendering a view or announcement, in order:

```
1. Rich-Presence asset:  slot.rp_large_image present?            → use it.
2. Cache lookup:         ArtCachePort.lookup(identity)
                           hit (source RICH_PRESENCE|IGDB, url)  → use url
                           hit (source NONE)                     → name-only (cached miss; no IGDB)
                           miss                                  ↓
3. IGDB:                 ArtResolverPort.resolveCover(identity, name)
                           Optional present → store(identity, url, IGDB);   use url
                           Optional empty   → store(identity, null, NONE);  name-only
4. Failure/disabled:     any exception or no credentials         → name-only (never propagate)
```

- `game identity` = `applicationId` when present else `normalize(name)` — the cache key, so IGDB is
  queried **at most once per game** (a miss is cached as `NONE`).
- The whole chain is wrapped so **any** failure degrades to name-only; it is invoked only from
  read/announce paths, never from `ProposeGameService` (FR-027).
- Announcement **edits on upvote** read **cached** art only (no IGDB on the hot path).

### `ArtResolverPort` implementation — `IgdbArtResolver` (`bot.infrastructure.art`)
- JDK `java.net.http.HttpClient`. Twitch OAuth client-credentials → bearer token cached until expiry.
- Query IGDB `/v4/games` + `/covers` by name (or external id); return the best cover URL.
- **Credentials** `IGDB_CLIENT_ID` / `IGDB_CLIENT_SECRET` are `${...}` env vars (Compose), never
  committed. **Blank/absent ⇒ the resolver is a disabled no-op** returning `Optional.empty()` — so
  `./mvnw verify` and credential-less dev runs work, and the art chain falls to name-only.
- Configurable via `application.yml` `queue.art.igdb.*` (base url, enabled). Unit-tested against a
  **stubbed** `HttpClient` (no network, no secrets).

## C. Announcement live surface — `AnnouncementPort` (`bot.infrastructure.discord`)
- `post(...)` on each non-empty weekly advance (when a channel is configured): current game + key art
  + "up next" 5 (thumbnails/names/current counts). Returns the message id; the service stores it as
  the guild's `latest_announcement_message_id` (FR-036).
- `edit(...)` on every registered upvote change, targeting **only** that latest message (FR-038).
  Older announcements and all ephemeral views are never edited (snapshots).
- Downtime catch-up posts **only the final** current game, once (no per-missed-week backlog).
- All sends/edits are async JDA REST calls performed **after** the transaction commits, off the
  interaction ack — keeping handlers thin and fast (Principle V).
