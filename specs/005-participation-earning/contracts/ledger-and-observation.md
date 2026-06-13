# Contract — Ledger Posting, Idempotency & Observation

## A. Participation credit posting (reuses feature 002 ledger)

A minted drop is a `coin_movement` of type **`PARTICIPATION`** with balanced `coin_ledger_entry`
lines produced by the **existing** `CoinLedgerPolicy.planGrant(guildId, memberId, balance,
coinsPerDrop, cap)`:

```
under cap (full credit):     TREASURY  -coinsPerDrop ; MEMBER +coinsPerDrop
crossing cap (partial):      TREASURY  -credited     ; MEMBER +credited
                             TREASURY  -forfeited    ; FORFEIT +forfeited      (credited+forfeited = coinsPerDrop)
at cap (handled upstream):   no drop minted — accrual paused (see application-services §1 step 4/8)
```

- `moderator_id` = the earning member's own id (self-initiated, mirroring queue spends).
- Lines sum to zero and never contain two MEMBER lines — the V2 `coin_assert_balanced`,
  `coin_assert_non_negative`, and append-only triggers all apply unchanged.
- Balance stays derived (`SUM` of MEMBER entries). No new ledger table, no balance column.

`AdjustmentType` gains `PARTICIPATION` (additive enum value); the `coin_movement` type CHECK gains
`'PARTICIPATION'` in V4 (see [data-model.md](../data-model.md)).

## B. Idempotency / at-most-once (FR-009)

- **Ledger uniqueness**: `coin_movement.interaction_id` is `NOT NULL UNIQUE`. Participation has no
  Discord interaction, so each drop uses `interaction_id = -nextval('participation_drop_seq')`.
  Snowflakes are always positive, so the negative range is collision-free. Exposed via
  `ParticipationAccrualPort.nextDropId()`.
- **The real guard** is the transactional **banked-seconds decrement** + `last_sampled_at`
  advancement under the per-account advisory lock: a re-run cannot re-credit a consumed span. The
  negative id only satisfies the UNIQUE column; it is not the dedup mechanism.

## C. Domain ports (new, in `bot.domain.participation`)

```java
interface ParticipationConfigPort {
  GuildParticipationConfig get(long guildId);                 // defaults when absent
  boolean freeFirstProposalEnabled(long guildId);             // read by ProposeGameService
  void setRate(long guildId, int minutesPerDrop, int coinsPerDrop);
  void setFreeFirstProposal(long guildId, boolean enabled);
}

interface DesignatedChannelPort {
  void add(long guildId, long channelId);                     // idempotent (ON CONFLICT DO NOTHING)
  void resetAll(long guildId);                                // reset-to-none
  java.util.List<Long> list(long guildId);
  boolean contains(long guildId, long channelId);
  java.util.List<Long> guildsWithChannels();                  // the set the sweep iterates
}

interface ParticipationAccrualPort {
  ParticipationAccrual get(long guildId, long memberId);      // (0, null) when absent
  void upsert(long guildId, long memberId, long bankedSeconds, java.time.Instant lastSampledAt);
  long nextDropId();                                          // -nextval('participation_drop_seq')
}

interface CurrentGamePort {
  java.util.Optional<GameIdentity> currentGameIdentity(long guildId);  // join rotation_state→queue_entry
}
```

Account locking + balance + cap + history reuse the existing `CoinLedgerPort` and
`GuildCoinConfigPort` — no new locking port. `GameIdentity` is feature 004's pure type.

## D. JDA cache & intents (`JdaConfig` change)

Change exactly two things in the `JDABuilder`:

- Add intent `GatewayIntent.GUILD_VOICE_STATES` (non-privileged) so voice states are received.
- `setMemberCachePolicy(MemberCachePolicy.NONE)` → `MemberCachePolicy.VOICE` so members connected to
  voice are retained **with** their activities (`CacheFlag.ACTIVITY` and `GUILD_PRESENCES` already
  enabled).

Unchanged: `GUILD_PRESENCES`, `GUILD_MEMBERS`, `CacheFlag.ACTIVITY`, `ChunkingFilter.NONE`,
`PresenceReader`'s on-demand propose-time capture. Recorded in plan **Complexity Tracking**.

## E. The sweep (`ParticipationScheduler`, infrastructure)

`@Component`, gated by `@ConditionalOnProperty(discord.enabled)` (so tests schedule nothing), driven
by `@Scheduled(fixedDelayString = "${participation.sweep.tick}")` plus an `ApplicationReadyEvent`
primer (mirrors `RotationScheduler`).

Per tick (`now = Instant.now()`):

```
for guildId in designatedChannelPort.guildsWithChannels():
    identity = currentGamePort.currentGameIdentity(guildId)
    if identity is empty:  continue                      # no current game → no earning (FR-011)
    channelIds = designatedChannelPort.list(guildId)
    qualifying = voiceParticipantsReader.qualifyingMembers(guildId, channelIds, identity)
    for memberId in qualifying:
        try: accrueParticipationService.accrue(new AccrueParticipationRequest(guildId, memberId, now))
        catch RuntimeException e: log.warn(...)          # one member's failure never aborts the sweep
```

- Reads JDA's **in-memory** cache only — no REST, off the gateway threads (Principle V).
- Each `accrue` runs in its own transaction (per-bean call), like `RotationScheduler.advanceDue`.

### `VoiceParticipantsReader` (infrastructure)

```java
Set<Long> qualifyingMembers(long guildId, List<Long> channelIds, GameIdentity currentGame);
```

For each `channelId`: `guild.getVoiceChannelById(channelId).getMembers()`; for each member, find the
first `PLAYING` activity via the shared **`GameActivities`** mapper → `GameIdentity`; include the
member iff it equals `currentGame`. Holds no per-call JDA reference beyond the supplied `Guild`
lookup; returns empty on any miss (channel uncached, member activity unreadable → not qualifying,
matching the "activity hidden" edge).

### `GameActivities` (infrastructure, shared)

Extract the pure `Activity → CapturedGame`/`GameIdentity` mapping currently private in
`PresenceReader.toCapturedGame` into a shared helper used by **both** `PresenceReader` (propose
capture) and `VoiceParticipantsReader` (sweep matching), so capture and matching agree byte-for-byte.

## F. Configuration (`application.yml`, new `participation:` block)

```yaml
participation:
  sweep:
    tick: PT1M        # how often the sweep samples qualifying members (fixed delay)
    max-gap: PT2M     # gaps larger than this accrue 0 (downtime / session re-entry) — FR-023
```

No secrets. Read with `@Value`/`@ConfigurationProperties` in the scheduler/service. Tests run with
`discord.enabled=false`, so neither the sweep nor JDA is active.
