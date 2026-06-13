# Feature Specification: Participation Earning

**Feature Branch**: `005-participation-earning`

**Created**: 2026-06-12

**Status**: Draft

**Input**: User description: "Add participation earning: members earn coins by actually playing the
current week's game together. This is the whole point of the economy — influence over what gets
played is earned by showing up and playing. Admins configure the voice channels participation is
registered on (add and reset-to-none) and a setting that lets the first person propose a game for
free when there is no current game and the queue is empty. Members earn coins at a fixed,
configurable flat rate per unit of time spent playing the current week's designated game while
connected to a designated voice channel; only the current week's game earns; earnings respect the
existing balance cap (excess forfeited); participation earnings are visible in coin history. Out of
scope: penalizing skippers, manual self-reporting, the skip jar, retroactive correction of missed
earnings."

## Clarifications

### Session 2026-06-12

- Q: Does earning require co-presence ("playing together"), or does a member alone in a designated
  voice channel still earn? → A: No co-presence minimum — a member alone in a designated channel while
  playing the current week's game still earns. "Together" describes the channel's purpose, not a hard
  gate; this avoids a cold-start deadlock and partial-credit complexity.
- Q: How far does sub-unit (partial) qualifying playtime persist before it is credited? → A: Persist
  indefinitely per member per server — qualifying time accrues toward the next **drop** and carries
  across disconnects, game-switches, and week changes until a drop is minted; only then is a ledger
  credit produced. This is what makes "100% of qualifying time credited" (SC-001) hold.
- Q: What is the shape of the configurable rate knob? → A: **Two** positive-integer knobs per server
  forming a "drop": **minutes-per-drop** (qualifying minutes needed to mint one drop) and
  **coins-per-drop** (whole coins awarded per drop). Every `minutes-per-drop` minutes of qualifying
  play mints one drop worth `coins-per-drop` coins. Defaults: 60 minutes-per-drop and 1 coin-per-drop
  (= one coin per hour), both changeable by the admin role; coins-per-drop may be set above 1 to award
  a multi-coin drop. No fractional coins are ever credited. (Supersedes an earlier single
  "minutes-per-coin" knob.)
- Q: When the free-first-proposal setting is ON and the bootstrap state holds, is the proposal free
  for everyone or only for members who can't afford it? And is "bootstrap" only at server start? → A:
  Free for everyone (cost waived, no balance check, regardless of balance). "Bootstrap" is NOT a
  one-time server-start event — the waiver applies **every time** the condition recurs (no current
  week's game AND empty queue), e.g., after a play lull drains the queue, or when coin-holders leave
  and only coinless members remain. The empty state is the trigger, whenever it occurs.

### Session 2026-06-13

- Q: While a member stays continuously connected (no Discord events firing), how does the system
  accrue qualifying time and mint drops? → A: **Hybrid.** A **recurring background tick** samples the
  members who are still connected to a designated channel playing the current game; for each, it banks
  the qualifying time elapsed since that member's last sample (bounded by a max-gap so downtime / a
  fresh session accrues nothing) and mints a drop whenever banked time reaches `minutes-per-drop`.
  Voice/presence state changes determine **who qualifies** at each sample; the per-member banked time
  and last-sample timestamp are **persisted** so accrual survives restarts and the same span is never
  credited twice. The cap is re-checked each tick, so accrual pauses while a member is at the cap.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Earn coins by playing the current week's game in a designated voice channel (Priority: P1)

A member spends time playing the current week's designated game while connected to one of the
server's designated voice channels. For that qualifying time, they earn coins at the server's flat
participation rate (for example, a drop of one coin for every hour played). This is the whole point of the economy: the
coins that buy influence over what gets played are earned by showing up and playing.

**Why this priority**: This is the core value of the feature and the reason the economy exists —
without it, coins only enter via moderator adjustments and the participation loop is closed. It is
independently valuable: once a server has designated a voice channel and has a current week's game,
members can start earning simply by playing.

**Independent Test**: With a designated voice channel configured and a current week's game set, have
a member connect to that channel while their activity is the current week's game; confirm they earn
coins at the configured flat rate for the qualifying time, that a member playing a different game (or
sitting in a non-designated channel) earns nothing, and that earnings stop when they leave the
channel or change games.

**Acceptance Scenarios**:

1. **Given** a server with a designated voice channel and a current week's game, **When** a member
   is connected to that channel while playing the current week's game for a qualifying span of time,
   **Then** they are credited coins at the server's configured flat rate for that time.
2. **Given** a member connected to a designated voice channel, **When** their active game is NOT the
   current week's designated game (or they are playing nothing readable), **Then** they earn no coins
   for that time.
3. **Given** a member playing the current week's game, **When** they are NOT connected to any
   designated voice channel (no channel connected, or only a non-designated channel), **Then** they
   earn no coins for that time.
4. **Given** a member who is earning, **When** they disconnect from voice, switch to a non-designated
   channel, or stop playing the current week's game, **Then** earning stops at that moment and only
   the time that qualified is credited.
5. **Given** a member who is at or near the server's balance cap, **When** they would earn coins
   from participation, **Then** they are credited only up to the cap and any excess is forfeited and
   recorded (the existing cap rule applies at earning time).
6. **Given** a server with no current week's game (none designated), **When** members play in a
   designated voice channel, **Then** no one earns participation coins (there is no game to match).

---

### User Story 2 - Admin configures the participation voice channels (Priority: P2)

A member holding the server's configured admin role designates which voice channels participation is
registered on. They can add voice channels to the set and reset the set to none. Only time spent in
one of these designated channels can earn participation coins.

**Why this priority**: Participation earning (US1) cannot happen until at least one voice channel is
designated, and the server must be able to control where "playing together" counts. It is the
enabling control surface for the core loop, but it delivers no value on its own, so it ranks below
the earning mechanic it serves.

**Independent Test**: As a member with the admin role, add a voice channel to the designated set and
confirm it is registered; add a second and confirm both are registered; reset to none and confirm no
channels remain; confirm a member without the admin role cannot change the set.

**Acceptance Scenarios**:

1. **Given** a member with the server's configured admin role, **When** they add a voice channel to
   the participation set, **Then** that channel becomes a designated channel for the server.
2. **Given** a server that already has one or more designated channels, **When** the admin adds
   another, **Then** the new channel is added to the set without removing the existing ones.
3. **Given** a server with one or more designated channels, **When** the admin resets the set to
   none, **Then** no voice channels are designated and participation earning is no longer possible
   until a channel is added again.
4. **Given** a member who does NOT hold the server's configured admin role, **When** they attempt to
   add or reset the participation channels, **Then** the action is refused and the set is unchanged.

---

### User Story 3 - Participation earnings are visible in coin history (Priority: P3)

A member views their coin history and can see that specific earnings came from participation —
distinct from moderator adjustments and from coins spent on the queue — so they understand that
showing up and playing is what grew their balance.

**Why this priority**: Visibility makes the earning loop legible and motivating, but it depends on
earnings existing first (US1) and reuses the existing history view, so it is the smallest
incremental slice.

**Independent Test**: Have a member earn participation coins, then view their coin history; confirm
the earning appears as a clearly labelled participation entry showing the amount and that it is an
earning (credit), distinguishable from moderator adjustments and queue spends.

**Acceptance Scenarios**:

1. **Given** a member who has earned participation coins, **When** they view their coin history,
   **Then** the earning appears as a participation entry showing the amount and direction (credit),
   labelled distinctly from moderator adjustments and queue spends.
2. **Given** a participation earning that was partially or fully forfeited at the cap, **When** the
   member views their history, **Then** the credited amount and the forfeited amount are both visible
   (consistent with the existing cap-forfeiture record).
3. **Given** a member who has never earned participation coins, **When** they view their history,
   **Then** no participation entries appear and the view does not error.

---

### User Story 4 - Admin enables a free first proposal when the queue is empty (Priority: P4)

A member holding the server's configured admin role turns on a setting that lets the first person to
propose a game do so **for free** — without spending coins — but only while there is no current
week's game and the queue is therefore empty. This is a bootstrap escape hatch: in the absence of
any earned coins, members can still get the rotation started. The empty state is not just the very
first moment of a server's life — it recurs whenever there is no current game and the queue is empty
(for example, after a play lull drains the queue, or after coin-holding members leave and only
coinless members remain), and the waiver applies each time that state holds.

**Why this priority**: It removes a cold-start (and re-cold-start) deadlock — no coins exist until
people play, but people need a current game to play to earn — yet it is a narrow, optional bootstrap
concern rather than the core earning loop, so it ranks last.

**Independent Test**: With the setting ON and a server that has no current game and an empty queue,
have a member propose a game and confirm no coins are charged and the proposal proceeds (becoming the
current game per the instant-pop rule); with the setting OFF, confirm the normal propose cost
applies; confirm that once a current game exists, the next proposal is charged normally even with the
setting ON.

**Acceptance Scenarios**:

1. **Given** the free-first-proposal setting is ON and the server has no current week's game and an
   empty queue, **When** a member proposes a game, **Then** the proposal is accepted with no coins
   charged.
2. **Given** the free-first-proposal setting is OFF, **When** a member proposes a game in the same
   empty state, **Then** the normal propose cost applies (the proposal is charged or rejected for
   insufficient balance as usual).
3. **Given** the free-first-proposal setting is ON but the server already has a current week's game
   (or a non-empty queue), **When** a member proposes a game, **Then** the normal propose cost applies
   — the waiver applies only in the no-current-game-and-empty-queue state.
4. **Given** a member who does NOT hold the server's configured admin role, **When** they attempt to
   turn the free-first-proposal setting on or off, **Then** the action is refused and the setting is
   unchanged.

---

### Edge Cases

- **No designated channels**: until a server designates at least one voice channel, no participation
  coins can be earned anywhere in that server.
- **No current week's game**: when no game is designated (initial state, or after a weekly advance
  found the queue empty), no participation earning occurs because there is nothing to match.
- **Wrong game in a designated channel**: a member in a designated channel playing something other
  than the current week's game earns nothing for that time.
- **Right game, wrong (or no) channel**: a member playing the current week's game but not connected
  to a designated channel earns nothing.
- **Activity hidden / unreadable**: if the member's game activity (Rich Presence) cannot be read
  (they are hiding it, or it is otherwise unavailable), the system cannot confirm they are playing the
  current game, so no participation coins are earned for that time.
- **Game match is best-effort**: whether the member's live game equals the current week's designated
  game is decided by the captured game identity (application/game ID when available, otherwise the
  activity name), consistent with how the queue captures games; cross-launcher matching is best-effort.
- **Current game changes mid-session**: if the weekly rotation advances while a member is playing,
  the member's qualifying status is re-evaluated against the new current game — they keep earning only
  if they are playing the new current game; otherwise earning stops.
- **Member already at the cap**: while a member sits at the balance cap, accrual of qualifying time
  toward the next drop pauses (time is not banked), so they earn nothing further and the system does
  not emit a continuous stream of zero/forfeiture records; accrual resumes if they later drop below the
  cap (e.g., by spending coins on the queue).
- **Partial earning across the cap**: when an earning event would credit coins that cross the cap,
  only the portion under the cap is credited and the remainder is forfeited and recorded.
- **Bot offline during play**: time a member spends playing while the bot is not observing is not
  earned and is never retroactively corrected (out of scope); earning resumes from the current
  observed state when the bot is available again.
- **No double-credit**: the same span of qualifying playtime is never credited more than once, even
  across a restart of the observing system.
- **Multiple designated channels**: a member earns while in any one of the designated channels;
  moving between two designated channels does not interrupt earning.
- **Free-first-proposal narrowness and recurrence**: the free waiver applies only to a proposal made
  while there is no current game AND the queue is empty; as soon as a game is current (including the
  instant-pop the free proposal itself triggers), subsequent proposals are charged normally. The empty
  state can recur after server start (a play lull draining the queue, or coin-holders leaving), and the
  waiver applies each time it does.
- **Per-server scope**: designated channels, the participation rate, the free-first-proposal setting,
  the current game, and earned balances are all scoped per server and never shared across servers.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A member MUST earn coins for time spent playing the **current week's designated game**
  while connected to one of the server's **designated voice channels**, credited at the server's
  configured flat participation rate.
- **FR-002**: Coins MUST be earned in fixed, flat **drops** defined by two per-server positive
  integers: **minutes-per-drop** (the qualifying minutes required to mint one drop) and
  **coins-per-drop** (the whole coins awarded per drop). Every `minutes-per-drop` minutes of
  qualifying play MUST mint exactly one drop worth `coins-per-drop` coins (defaults: 60 and 1, i.e.
  one coin per hour). The rate is deliberately simple and flat: it is never tiered, never multiplied
  by participants, and never credits a fractional coin (a partial drop credits nothing until complete).
- **FR-003**: Only time spent playing the **current week's designated game** MUST earn coins;
  time spent playing any other game (or no readable game) MUST earn nothing.
- **FR-004**: Only time spent while connected to a **designated voice channel** MUST earn coins; time
  spent in a non-designated channel, or not connected to voice, MUST earn nothing.
- **FR-005**: Participation earnings MUST respect the existing per-server balance cap: when minting a
  drop would cross the cap, only the portion of that drop's coins that fit under the cap is credited
  and the remainder is forfeited and recorded at earning time, consistent with the existing cap rule.
  While a member is already at the cap, accrual of qualifying time toward the next drop PAUSES (time is
  not banked) so the member earns nothing further and no continuous stream of forfeiture records is
  produced; accrual resumes if the member later drops below the cap (e.g., after spending coins on the
  queue).
- **FR-006**: Every participation earning MUST produce a balanced, append-only ledger record
  consistent with the existing coin economy (the member is credited; the balanced counter-entry comes
  from the economy's earning source), and MUST NOT edit or delete any prior record.
- **FR-007**: Participation earnings MUST be recorded as a **distinct movement type** so they are
  distinguishable in coin history from moderator adjustments and from coins spent on the queue.
- **FR-008**: A member MUST be able to see their participation earnings in their coin history (amount,
  direction = credit, and a label/context identifying it as participation), using the existing history
  view.
- **FR-009**: The system MUST NOT credit the same span of qualifying playtime more than once, even
  across a restart of the observing system (at-most-once earning).
- **FR-010**: Earning MUST start when a member begins to satisfy both conditions (current game + in a
  designated channel) and MUST stop when they cease to satisfy either condition (disconnecting,
  moving to a non-designated channel, stopping or switching the game, or the current game changing to
  one they are not playing).
- **FR-011**: When there is no current week's game for a server, no member in that server earns
  participation coins.
- **FR-012**: A member holding the server's **configured admin role** (the role designated through
  the bot's configuration commands) MUST be able to **add** a voice channel to the server's set of
  designated participation channels.
- **FR-013**: A member holding the server's **configured admin role** MUST be able to **reset** the
  server's designated participation channels to **none**.
- **FR-014**: A member who does NOT hold the server's configured admin role MUST NOT be able to add
  or reset the designated participation channels.
- **FR-015**: With no designated channels configured, participation earning MUST be impossible for
  that server until at least one channel is added.
- **FR-016**: Both participation-rate knobs (minutes-per-drop and coins-per-drop, each a positive
  integer) MUST be configurable per server by a member holding the configured admin role; a change
  applies to subsequent drops only (it does not retroactively re-price already-credited drops, and
  qualifying time already accrued toward the next drop is measured against the rate in effect when that
  drop completes).
- **FR-017**: A member holding the server's **configured admin role** MUST be able to turn a
  **free-first-proposal** setting on or off for the server.
- **FR-018**: Whenever the free-first-proposal setting is ON **and** the server has no current week's
  game **and** the queue is empty, a member's proposal MUST be accepted with the propose cost waived
  (no coins charged, no balance check, for any member regardless of balance). This is not limited to a
  server's first-ever proposal: it applies every time that empty state recurs. The proposal otherwise
  proceeds exactly as a normal proposal (including becoming the current week's game via the instant-pop
  rule).
- **FR-019**: When the free-first-proposal setting is OFF, or when there is already a current week's
  game, or when the queue is non-empty, the normal propose cost MUST apply (no waiver).
- **FR-020**: A member who does NOT hold the server's configured admin role MUST NOT be able to change
  the free-first-proposal setting.
- **FR-021**: All participation state — designated channels, the participation rate, the
  free-first-proposal setting, and earned balances — MUST be scoped **per server** and never shared
  across servers.
- **FR-022**: Whether a member's live game equals the current week's designated game MUST be decided
  by the captured game identity (application/game ID when available, otherwise the activity name),
  consistent with how the queue captures and identifies games; exact cross-launcher matching is
  best-effort.
- **FR-023**: Time a member spends playing while the system is not observing (e.g., the bot is
  offline) MUST NOT be earned and MUST NOT be retroactively credited when observation resumes.

### Key Entities *(include if feature involves data)*

- **Designated Voice Channel Set**: per server, the set of voice channels on which participation is
  registered. Empty by default; mutated only by add and reset-to-none. Earning is possible only while
  a member is connected to a channel in this set.
- **Participation Rate Configuration**: per server, the flat emission rate as two positive integers —
  **minutes-per-drop** and **coins-per-drop** (defaults 60 and 1, i.e. one coin per hour) — until
  changed. A "drop" is one earning event: `coins-per-drop` whole coins minted per `minutes-per-drop`
  minutes of qualifying play.
- **Free-First-Proposal Setting**: per server, an on/off flag (off by default) that waives the propose
  cost for a proposal made while there is no current week's game and the queue is empty.
- **Participation Earning (Ledger Entry)**: an immutable, balanced coin movement crediting a member
  for qualifying playtime, of a distinct movement type ("participation"), scoped per server, recorded
  in the existing append-only ledger; subject to the cap (with forfeiture recorded when exceeded).
- **Qualifying Participation Time (toward the next drop)**: the accrued time during which a member
  simultaneously satisfies both earning conditions (playing the current week's game AND connected to a
  designated channel), maintained per member per server as time banked toward the next drop. A
  **recurring background tick** samples members who are still qualifying and banks the qualifying time
  elapsed since each member's last sample (the persisted `last-sample` timestamp; a gap larger than a
  configured max-gap, or a first sample, banks nothing — covering downtime and fresh sessions). Each
  time the banked time reaches `minutes-per-drop`, a drop of `coins-per-drop` coins is minted and that
  much time is consumed. The remaining banked time and the last-sample timestamp are persisted, so
  accrual persists indefinitely across disconnects/game-switches/weeks and the same span is never
  credited twice.
- **Drop**: one participation earning event — `coins-per-drop` whole coins minted when a member's
  banked qualifying time reaches `minutes-per-drop`. Each drop produces one ledger credit (subject to
  the cap).
- **Current Week's Designated Game**: owned by the game-queue feature; this feature only reads it to
  decide what counts as "the current game" for matching.
- **Member Coin Balance**: the member's per-server balance (owned by the existing economy); this
  feature credits earnings into it, subject to the cap.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A member playing the current week's game in a designated voice channel earns coins at
  exactly the server's configured drop rate for their qualifying time (e.g., with the default 60
  minutes-per-drop / 1 coin-per-drop, one coin per hour of qualifying play) — 100% of qualifying time
  is credited at the configured rate, in whole drops.
- **SC-002**: Time spent playing any game other than the current week's designated game earns zero
  participation coins in 100% of cases.
- **SC-003**: Time spent outside a designated voice channel (including not connected to voice) earns
  zero participation coins in 100% of cases.
- **SC-004**: Participation earnings never push a member's balance above the server's cap; any excess
  is forfeited and recorded at earning time, with zero cap violations.
- **SC-005**: The same span of qualifying playtime is never credited more than once, even across a
  restart — zero double-credits.
- **SC-006**: Every participation earning appears in the member's coin history as a credit, clearly
  distinguishable from moderator adjustments and queue spends — 100% labelled.
- **SC-007**: With no designated voice channels configured, no participation coins are earned in that
  server (0 earnings) until a channel is added.
- **SC-008**: Only a member with the server's configured admin role can add or reset designated
  channels, change the participation rate, or toggle the free-first-proposal setting — unauthorized
  attempts change nothing in 100% of cases.
- **SC-009**: When the free-first-proposal setting is ON and the server has no current game and an
  empty queue, the first proposal is accepted with zero coins charged; in every other state the normal
  propose cost applies.
- **SC-010**: Time played while the bot is not observing produces zero participation earnings and is
  never retroactively credited (0 retroactive corrections).

## Assumptions

- **Builds on the existing economy and queue**: this feature reuses the per-server coin economy
  (balance, append-only double-entry ledger, per-server balance cap defaulting to 12, forfeiture
  recorded at earning time) from the coin-ledger feature and the current-week's-game concept from the
  game-queue feature. It adds a new credit path; it does not change how balances, caps, or history are
  computed.
- **Admin authorization is the server's configured role**: "admin" here means a member holding the
  server's configured economy-administration role (the role designated through the bot's configuration
  commands — the same authorization used to administer the coin economy), not Discord's generic
  Manage-Server permission used for queue costs. Configuring designated channels, the participation
  rate, and the free-first-proposal setting all use this role.
- **No co-presence minimum**: "playing together" describes the intent of designated voice channels as
  gathering places; a member alone in a designated channel while playing the current game still earns.
  Earning does not require a second person in the channel.
- **Crediting granularity**: qualifying time accrues continuously, banked per member per server toward
  the next drop; each time the banked time reaches `minutes-per-drop`, a drop of `coins-per-drop` coins
  is credited and that much time is consumed. The leftover banked time persists indefinitely — across
  disconnects, game-switches, and week changes — until it completes the next drop (it is never
  discarded). Earning events (ledger credits) are therefore relatively infrequent (one per completed
  drop, on the order of `minutes-per-drop`), keeping ledger volume low.
- **Default participation rate**: the rate is two positive integers — **minutes-per-drop** (default
  **60**) and **coins-per-drop** (default **1**), i.e. one coin per hour — both changeable per server
  by the admin role. A larger minutes-per-drop earns more slowly (e.g., 120 = one drop per two hours);
  a smaller one earns faster (e.g., 30 = a drop every half hour); coins-per-drop > 1 awards a multi-coin
  drop each time (e.g., 60 / 3 = a 3-coin drop every hour).
- **Default designated-channel set is empty** and the **free-first-proposal setting defaults to OFF**;
  both are opt-in per server.
- **Free proposal means cost = 0**: when the waiver applies, the proposal records no coin movement
  (no member debit, no pot credit) and bypasses the balance check; all other propose behavior
  (instant-pop, eligibility, single-queued-game rule) is unchanged from the game-queue feature.
- **Designated-channel mutations are add and reset-only**: the only operations on the designated set
  are adding a channel and resetting the whole set to none; removing a single channel individually is
  not required by this feature.
- **Reading game activity requires shared presence**: determining the member's current game relies on
  their platform presence/Rich Presence being readable; if it is hidden or unavailable, the member is
  treated as not playing the current game for earning purposes.
- **Observation is live, not historical**: earning is driven by the system observing the member's
  voice and presence state in real time; there is no playtime import, log scan, or self-report.
- **Minting is a hybrid of presence/voice gating and a periodic sampling tick**: voice/presence state
  determines which members qualify at each sample, while a recurring background task banks the time
  elapsed since each qualifying member's last sample (bounded by a max-gap that discards downtime /
  fresh sessions) and mints drops from the banked time for members who are still qualifying — pausing
  accrual for members at the cap, re-checked each tick. Drops are therefore minted during a continuous
  session, not only at disconnect. Banked time and the last-sample timestamp are persisted, so accrual
  survives restarts and no span is credited twice; the tick cadence affects only sub-tick boundary
  precision, not the long-run credited total.

## Dependencies

- The existing per-server coin economy: balance, append-only double-entry ledger, movement types,
  coin history view, and the per-server balance cap with forfeiture-at-earning-time (coin-ledger
  feature).
- The game-queue feature's **current week's designated game** (read to decide what counts as the
  current game) and its **propose** flow (whose cost the free-first-proposal setting waives in the
  empty-queue bootstrap state).
- The per-server configured admin role used to administer the economy (designated through the bot's
  configuration commands).
- The platform's **voice-state** data (to know which voice channel a member is connected to) and
  **presence / Rich Presence** data (to know which game a member is playing) — the same presence
  capability the queue already relies on.

## Out of Scope

- Penalizing members who skip the current game or play something else (this feature only rewards
  participation; it never deducts for non-participation).
- Manual self-reporting or manual import of playtime; earning is observed live only.
- The collective early-skip ("skip jar").
- Retroactive correction of earnings missed while the bot was offline or not observing.
- Tiered, multiplied, or co-presence-scaled rates; the emission rate is a single flat drop
  (minutes-per-drop + coins-per-drop), applied uniformly.
- Removing an individual designated voice channel (only add and reset-to-none are provided).
- Changing how balances, caps, forfeiture, or coin history are computed (reused unchanged from the
  coin-ledger feature).
