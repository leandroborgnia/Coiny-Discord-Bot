# Feature Specification: Game Queue & Weekly Rotation

**Feature Branch**: `004-game-queue`

**Created**: 2026-06-10

**Status**: Draft

**Input**: User description: "Add the game queue: the ordered list of games waiting to be played and
the weekly rotation that designates the current game. Members spend coins to influence the order, but
everything proposed is eventually played — coins buy position, never the right to keep a game out."

## Clarifications

### Session 2026-06-10

- Q: How is the weekly rotation triggered? → A: Automatically by a scheduled weekly tick — the system
  advances the rotation itself at a fixed weekly boundary (per server), with no human action required.
  There is no moderator "advance the week" command; the per-week idempotency guarantee makes a missed
  or duplicate tick safe.
- Q: Where do coins spent on propose/bump go (the double-entry counter-account)? → A: Into a
  per-server **pot** account — spending debits the member and credits the server's pot (balanced).
  The pot accrues per server; nothing consumes it in this feature (the skip-jar is out of scope), but
  the coins are retained there rather than burned out of circulation.
- Q: May a member have more than one game waiting, and what governs when they can propose again? → A:
  At most ONE un-played game per member at a time. When that game is popped from the top, the system
  records **N = the number of other games then waiting in the queue**, and the member must wait until
  **N more games have been played** before proposing again — a "wait N games" cooldown, fixed at the
  moment of the pop. This equalizes opportunity between highly-active members and those who participate
  less often but still weekly; if `N = 0` (no others were waiting), the member may propose again
  immediately. Empty weeks (no game designated) do not count down the cooldown.
- Q: If a member leaves the server while their game is still queued, what happens to it? → A: The game
  stays in the queue and is eventually played; the proposer's attribution is retained for display.
  Removal of games is never triggered by a member's departure.
- Q: What is the current game at the very start, before any weekly tick? → A: There is none at start;
  the first game proposed is instantly popped and becomes the current week's game (an "instant pop"),
  rather than waiting a week. This also applies whenever there is no current game (e.g. after a weekly
  advance found the queue empty).
- Q: What are the default propose and bump costs? → A: Propose costs **1 coin**; bump costs **1 coin
  per bump**. Both remain per-server configurable. A member already at the top cannot bump (there is
  nowhere to move) and simply waits.
- Q: Is a proposed game free text or a recognized title? → A: Neither — **superseded** by the Rich
  Presence decision below. A game is captured from the proposing member's live Discord Rich Presence
  (they must be playing it); there is no typed title and no external game-catalog lookup. Two members
  playing the same game still produce distinct queue slots.
- Q: How are the current game and queue presented? → A: Visually, with each game's key art, in a
  **private (ephemeral) view** (see the view-visibility clarification below). It shows the current game
  plus the next 5 queued games (with proposer, position, and upvote count); the viewer's own queued
  game is always shown and marked as theirs — additionally if it is outside the top 5, or in place if
  within it.
- Q: How do upvotes work? → A: Each queue slot shows an upvote count. Members upvote / remove their
  upvote via interactive buttons (not emoji reactions) — a one-or-zero toggle per member per slot. An
  upvote is bound to that specific slot (this session's appearance), not to the game forever, and is a
  social signal only: it never changes order or which game plays, and does not carry over to a future
  re-proposal. Because buttons may appear in several messages at once, the action is idempotent — it
  changes state only on a real transition and ignores duplicate presses.
- Q: What if the bot is offline at the weekly tick? → A: The advance is derived from elapsed time since
  the last pop; on restart the system catches up, applying each missed weekly advance exactly once so
  the current game is correct.
- Q: How does a member pick the exact game when proposing, given Discord has no usable game-ID lookup
  or autocomplete catalog? → A: They do NOT type it. The member must be **actively playing** the game,
  and the bot captures it from their **Discord Rich Presence** at command time — the game/application
  ID when available, otherwise the activity name — together with as much Rich Presence detail and image
  assets as possible, stored as a **full snapshot** (not just a name/ID). If the member has no readable
  game activity, the proposal is rejected. This also ensures members only propose games they can
  actually play. Because rich-presence names vary by launcher (Steam/Epic/PlayStation/etc.), reliable
  "same game" matching across members is best-effort and deferred; storing the full snapshot keeps
  future recognition options open.
- Q: Can a member withdraw or change a proposal once made? → A: Yes. (1) **Withdraw + refund**: while
  their game is still queued, a member may withdraw it; the slot is removed and the coins they spent on
  it are refunded (reversed out of the server pot). (2) **Replace the game**: running the propose
  command again while they already have a queued proposal does not create a second entry — it replaces
  that proposal's captured game with the one from their current Rich Presence, keeping their queue
  position. (3) **No-activity guard**: in every case where propose is executed and the member has no
  readable game activity, the bot performs nothing, charges nothing, and clearly tells them the action
  was not executed because no game was recognized — advising them to check whether they are hiding
  their Rich Presence activity in their Discord settings/permissions.
- Q: When a member replaces the game on their queued proposal, does it cost coins and what happens to
  its upvotes? → A: Replacing is **free** (it edits your existing single entry, not a new proposal) and
  **resets that slot's upvotes to zero**, since the prior upvotes were cast for the previous game. The
  slot keeps its queue position.
- Q: When the weekly game changes, are members notified? → A: Only once an admin has configured an
  announcement channel for the bot. With no channel configured, the rotation is silent (members use the
  view command). Once a per-server announcement channel is set, each weekly advance automatically posts
  the new week's game (with key art) to that channel; on downtime catch-up only the final current game
  is announced once (no backlog spam). Setting/clearing the channel is done by an authorized server
  role.
- Q: Is the weekly advance a rolling 7 days from the last pop, or aligned to a fixed calendar boundary?
  → A: Rolling — each designated game lasts exactly **7 days**, and the next advance is 7 days after
  the last designation (pop), measured per server. Downtime catch-up applies one advance per whole
  7-day period elapsed. The bootstrap instant-pop starts the clock; no calendar/timezone alignment is
  required.
- Q: Is the queue view public or ephemeral, and where are upvote counts shown / updated? → A: The queue
  view is **ephemeral / private** to the requester (only they see it and its upvote buttons). Ephemeral
  views and all older messages show counts as a **snapshot of when they were sent and are never
  updated** (there can be many old ones — re-run the view to see fresh numbers). The **single live count
  surface is the latest announcement message** per server: it shows the current game and an "up next"
  preview of the next 5 upcoming games (small thumbnails + names + current upvote counts) and is edited
  on **every** registered upvote change. Older announcement messages are NOT updated — only the latest.
  With no announcement channel configured, there is no live public count surface; members see snapshot
  counts in their ephemeral view.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Propose a game with coins (Priority: P1)

A member who is **currently playing a game** spends coins to propose it; the bot captures the game
from their Discord Rich Presence and adds it to the back of their server's queue of games waiting to
be played. The cost is deducted from the member's coin balance.

**Why this priority**: This is the entry point of the whole feature — without proposals there is no
queue, no rotation, and nothing to order. It is independently valuable: a server can start
collecting the list of games members want to play.

**Independent Test**: As an eligible member who is playing a game and has enough coins, propose it;
confirm the captured game appears at the back of the queue, the configured cost is deducted exactly
once, and a member who is not playing a game, cannot afford it, or is not eligible is rejected with no
change.

**Acceptance Scenarios**:

1. **Given** an eligible member who is playing a game and whose balance is at or above the propose
   cost, **When** they propose, **Then** the game captured from their Rich Presence is added to the
   back of the server's queue and exactly the propose cost is deducted from their balance.
2. **Given** a member whose balance is below the propose cost, **When** they try to propose, **Then**
   the action fails completely — no game is added and no coins are deducted.
3. **Given** a member who already has an un-played game in the queue, **When** they run propose again
   while playing a different game, **Then** their existing proposal's captured game is replaced (no
   second entry is created) and the slot keeps its queue position.
4. **Given** a member who proposes and then immediately repeats the same submission, **When** the
   duplicate arrives, **Then** the game is added at most once and the member is charged at most once.
5. **Given** an empty initial state with no current game, **When** a member proposes the first game,
   **Then** that game is instantly designated the current week's game (instant pop) rather than placed
   in the queue.
6. **Given** a member who is not currently playing any game (no readable activity), **When** they try
   to propose, **Then** the action is rejected with no charge and no queue entry, and the bot explains
   that no game was recognized and to check their activity-sharing settings.
7. **Given** a member whose proposed game is still queued, **When** they withdraw it, **Then** the slot
   is removed from the queue and the coins they spent on it are refunded.

---

### User Story 2 - Weekly rotation designates and plays the top game (Priority: P2)

Each week the game at the top of the queue becomes that week's designated game and leaves the queue;
the rotation advances automatically so the next game moves to the top. This is what guarantees that
every proposed game is eventually played.

**Why this priority**: This is the core mechanism that turns a static list into a fair rotation and
delivers the central promise ("everything proposed is eventually played"). It also records the
proposer's "wait N games" cooldown count at the moment their game is played.

**Independent Test**: With a known queue, trigger the weekly advance; confirm the top game becomes
the week's designated game, leaves the queue, the remaining games shift up preserving order, and
advancing again for the same week changes nothing.

**Acceptance Scenarios**:

1. **Given** a non-empty queue, **When** the weekly rotation advances, **Then** the top game becomes
   the current week's designated game, is removed from the queue and recorded as played, and the
   remaining games keep their relative order shifted up by one.
2. **Given** an empty queue, **When** the weekly rotation advances, **Then** no game is designated for
   that week and the queue stays empty.
3. **Given** a week whose game has already been designated, **When** the advance is triggered again
   for that same week, **Then** nothing changes (the designated game is unchanged and no extra game is
   consumed).
4. **Given** any fixed queue state, **When** the advance runs, **Then** it always designates the same
   single game (the top of the queue) — the outcome is deterministic.
5. **Given** a configured announcement channel, **When** the rotation designates a new game, **Then**
   the bot posts that game (with key art) to the channel exactly once.
6. **Given** no configured announcement channel, **When** the rotation advances, **Then** no
   announcement is posted and the change is silent.

---

### User Story 3 - View the current game and the queue, richly presented (Priority: P3)

A member privately (ephemerally) views a visually rich presentation: the current week's game shown
with its key art, and the next 5 queued games each shown with key art, proposer, position, and current
upvote count. The viewer's own queued game is always shown and marked as theirs — additionally if it
falls outside the top 5, or in place if it is within the top 5. Because the view is private to the
requester and rendered fresh, its upvote counts are always current.

**Why this priority**: Visibility makes the queue usable and coin-spending decisions meaningful, and
the rich presentation (key art, upvote counts) is what makes the feature engaging. It depends on
proposals existing (US1).

**Independent Test**: With a populated queue and a designated current game, view it; confirm the
current game and the next 5 queued games each render with key art, proposer, position, and upvote
count, that the viewer's own entry is always shown and marked (even if beyond the top 5), and that the
viewer's own eligibility / remaining cooldown is visible.

**Acceptance Scenarios**:

1. **Given** a designated current game and a populated queue, **When** a member views it, **Then** the
   current game is shown with its key art and the next 5 queued games are shown in order, each with key
   art, proposer, position, and current upvote count.
2. **Given** a member whose own queued game is beyond the top 5, **When** they view the queue, **Then**
   their entry is additionally shown and clearly marked as theirs.
3. **Given** a member whose own queued game is within the top 5, **When** they view the queue, **Then**
   that entry is marked as theirs in place and not duplicated.
4. **Given** a member still inside their "wait N games" cooldown, **When** they view the queue,
   **Then** they can see they are not yet eligible to propose and how many games remain.
5. **Given** an initial state with no current game and an empty queue, **When** a member views it,
   **Then** it clearly indicates there is no current game and the queue is empty.

---

### User Story 4 - Bump your own game one position with coins (Priority: P4)

A member spends coins to move their own un-played game exactly one position closer to the top,
swapping with the game directly above it. The game that gets outranked is not removed — it simply
plays one week later.

**Why this priority**: This is the "coins buy position" refinement on top of the base queue and
rotation. It is the least foundational: the feature is already viable and fair without it, and it
only adjusts ordering, never inclusion.

**Independent Test**: As the proposer of a game that is not already at the top, bump it; confirm it
swaps up by exactly one position, the bump cost is deducted once, the displaced game is still in the
queue one position lower, and bumping a game at the top (or someone else's game) is rejected with no
change.

**Acceptance Scenarios**:

1. **Given** a member who proposed a game that is not at the top and who can afford the bump cost,
   **When** they bump it, **Then** it swaps position with the game directly above it and exactly the
   bump cost is deducted.
2. **Given** a member's game that is already at the top, **When** they try to bump it, **Then** the
   action fails completely — no reorder and no coins deducted.
3. **Given** a game proposed by someone else, **When** a member tries to bump it, **Then** the action
   is rejected and nothing changes.
4. **Given** a member who cannot afford the bump cost, **When** they try to bump, **Then** the action
   fails completely with no reorder and no deduction.

---

### User Story 5 - Upvote a queued game for this session (Priority: P4)

A member presses a button (not an emoji reaction) on a queued game to add an upvote — signalling "I'd
really like to play this one when it comes up" — and can press again to remove it. The upvote is bound
to that specific queue slot (this session's appearance of the game), not to the game forever; it is a
one-or-zero toggle per member per slot, and the current count is shown on the entry.

**Why this priority**: Upvotes add social signal and engagement without changing who plays — coins
(via bumping) remain the only lever on order. It builds on the queue view (US3).

**Independent Test**: On a queued game, press the upvote button and confirm the count rises by one and
reflects your vote; press again and confirm it returns to the prior count; confirm that pressing the
button in a second copy of the same queue message does not double-count.

**Acceptance Scenarios**:

1. **Given** a member who has not upvoted a queue slot, **When** they press its upvote button, **Then**
   their upvote is recorded and the slot's count increases by exactly one.
2. **Given** a member who has already upvoted a slot, **When** they press the button again, **Then**
   their upvote is removed and the count decreases by exactly one (toggle).
3. **Given** the same queue slot rendered in two different messages, **When** the member presses upvote
   in one and then presses upvote again in the other, **Then** the slot is counted as upvoted exactly
   once and the second press (no state change) is a no-op.
4. **Given** any queued game, **When** a member views the queue, **Then** the slot shows its current
   total upvote count.
5. **Given** a game whose slot has been played and left the queue, **When** the same game is later
   proposed again, **Then** it starts a fresh slot with zero upvotes (upvotes do not carry over).

---

### Edge Cases

- **Not eligible to propose**: a member who already has a queued game, or whose "wait N games"
  cooldown has not elapsed, is rejected when proposing — with a clear reason and no coin change.
- **Zero cooldown**: if no other games were waiting when the member's game was popped (`N = 0`), they
  may propose again right away.
- **Cooldown is fixed**: games proposed after a member's game was popped do not change that member's
  remaining cooldown; only games actually played count it down, so empty weeks do not.
- **Bump at the top / not owner / unaffordable**: each fails completely, changing neither order nor
  balance.
- **Duplicate / double-submitted action**: a repeated propose or bump is honored at most once and
  never double-charges.
- **Weekly advance on an empty queue**: no game is designated that week.
- **Weekly advance fired twice for one week**: the second advance is a no-op.
- **Concurrent actions on the same queue**: simultaneous proposes/bumps resolve to a single consistent
  order with no lost updates and no double charges.
- **Same title proposed by two members**: allowed as two distinct entries (different proposers).
- **Proposer leaves the server**: their queued game stays and is eventually played, with attribution
  retained; departure never removes a game.
- **Member not playing a game**: if the member has no readable game activity (not playing anything, or
  activity sharing is off) when they run propose (new or replace), nothing happens and nothing is
  charged; the bot clearly says the action was not executed because no game was recognized and advises
  checking whether their Rich Presence activity is hidden in their settings/permissions.
- **Withdraw a queued proposal**: a member removes their own still-queued game; the slot is removed and
  the coins they spent on it are refunded from the pot. A played game cannot be withdrawn.
- **Replace the captured game**: re-running propose while you already have a queued proposal updates
  that proposal's game (keeping its position) rather than adding a second entry; it is free and resets
  that slot's upvotes to zero.
- **Launcher-tangled names**: the same game may surface under different rich-presence names via
  different launchers; the full snapshot is stored so matching can be improved later, but identical
  de-duplication across slots is not guaranteed.
- **Bootstrap instant-pop**: from an empty initial state (no current game), the first proposal becomes
  the current week's game immediately instead of waiting.
- **Own game beyond the top 5**: the viewer's own queued entry is always shown and marked, even when it
  is not in the displayed next-5.
- **Duplicate upvote across renders**: pressing upvote (or remove) in multiple ephemeral renders of the
  queue counts at most once; an action that does not change the member's upvote state is ignored.
- **Redundant upvote action**: upvoting a slot already upvoted, or removing an upvote that is not
  present, is a no-op — the count does not change.
- **Stale ephemeral counts**: ephemeral views and older messages show counts as of when they were sent
  and are never updated; members re-run the view to see fresh numbers. The only live count surface is
  the latest announcement message.
- **Announcement "up next"**: when a channel is configured, the weekly announcement lists the next 5
  upcoming games (small thumbnails + names + upvote counts); only this latest announcement message is
  updated live on each vote — older announcements are not.
- **Downtime spanning multiple weeks**: on restart, each missed weekly advance is applied exactly once
  based on elapsed time since the last pop; no week is skipped or double-applied. If an announcement
  channel is configured, only the final current game is announced once (no backlog of missed-week
  posts).
- **No announcement channel configured**: weekly advances happen silently; members learn the current
  game via the view command until an admin sets an announcement channel.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A member MUST be able to spend coins to propose a game, which is added to the **back**
  of their server's queue; exactly the configured propose cost is deducted from the member's balance.
- **FR-002**: If the member's balance is below the propose cost, proposing MUST fail completely — no
  game added and no coins deducted.
- **FR-003**: A member MUST have at most one un-played game in the queue at a time. Running the propose
  command while they already have a queued proposal MUST NOT create a second entry; instead it replaces
  that proposal's captured game (see FR-034). A member may also withdraw their queued proposal (see
  FR-033).
- **FR-004**: A member MUST be able to spend coins to move their **own** un-played game exactly one
  position closer to the top, swapping with the game directly above it; exactly the configured bump
  cost is deducted.
- **FR-005**: A member MUST NOT be able to move a game they did not propose.
- **FR-006**: A bump on a game that is already at the top of the queue MUST fail completely — no
  reorder and no coins deducted.
- **FR-007**: Reordering MUST NOT remove any game; every proposed game remains in the queue until it
  is played. Being outranked only makes a game play later, never excludes it.
- **FR-008**: On each weekly rotation, the game at the **top** of the server's queue MUST be
  designated the current week's game, removed from the queue, and recorded as played; the remaining
  games MUST keep their relative order, shifted up by one.
- **FR-009**: If the queue is empty at the weekly rotation, no game is designated for that week and
  the queue remains empty.
- **FR-010**: The queue MUST be a total order with a single unambiguous top; the next game to be
  played is always exactly that top game, so the same queue state always yields the same designated
  game (deterministic advance).
- **FR-011**: When a member's game is played (popped from the top), the system MUST record **N**, the
  number of other games then remaining in the queue, and MUST NOT allow that member to propose again
  until **N more games have been played** (N subsequent weekly designations). If `N = 0`, the member
  may propose again immediately. This "wait N games" cooldown equalizes opportunity between
  highly-active members and those who participate less often but still weekly.
- **FR-012**: The cooldown count N MUST be fixed at the moment the member's game is played; games
  proposed afterward MUST NOT change it (absence or later proposals neither lengthen nor shorten the
  cooldown). Only games actually played count down the cooldown — empty weeks (no designation) do not.
- **FR-013**: A member MUST be able to determine whether they are currently eligible to propose.
- **FR-014**: A member MUST be able to view the queue and the current week's designated game (or that
  there is none), including each shown entry's position and proposer; the presentation detail (which
  entries are shown, key art, upvote counts) is defined in FR-027 and FR-028.
- **FR-015**: A repeated or double-submitted propose or bump (the same logical action) MUST be honored
  at most once and MUST NOT deduct coins more than once.
- **FR-016**: Triggering the weekly advance more than once for the same week MUST be a no-op — the
  week's designated game does not change and no additional game is consumed.
- **FR-017**: The propose cost and the bump cost MUST be configurable per server; a change applies to
  subsequent actions only.
- **FR-018**: Only an authorized server role MAY change the propose or bump cost.
- **FR-019**: Spending coins MUST deduct from the member's per-server balance, using the cost
  configured at the time the action is performed.
- **FR-023**: Every coin amount spent on a propose or bump MUST be credited to the server's **pot**
  account as the balanced counter-entry (the member is debited, the per-server pot is credited by the
  same amount). The pot is not consumed by this feature.
- **FR-020**: The queue, the rotation, the costs, and proposal eligibility MUST all be scoped per
  server and never shared across servers.
- **FR-021**: At any time the current week's game MUST be either exactly one designated game or
  explicitly "none"; it is never ambiguous.
- **FR-022**: The system MUST record each weekly designation (which game played, in which week) so the
  rotation is auditable and the "eventually played" guarantee is verifiable.
- **FR-024**: When there is no current week's game (the initial state before any game has been
  designated, or immediately after a weekly advance found the queue empty), the next game proposed MUST
  be designated the current week's game immediately ("instant pop") instead of waiting in the queue.
  For cooldown purposes this counts as the proposer's game being played with `N = 0`.
- **FR-025**: The default propose cost MUST be 1 coin and the default bump cost MUST be 1 coin per
  bump; both remain per-server configurable (FR-017). A member whose game is already at the top cannot
  bump and simply waits (per FR-006).
- **FR-026**: A game is proposed by **capturing the proposing member's current game activity (Discord
  Rich Presence) at the time of the command** — the member MUST be actively playing the game they wish
  to propose. The system MUST capture the game's identity (its application/game ID when available,
  otherwise the activity name) together with as much of the available Rich Presence detail as possible
  (name, details, state, timestamps, image-asset references, etc.) and store that **snapshot** as the
  proposed game. If the member has no readable game activity when they run the command, the proposal
  MUST be rejected with no charge.
- **FR-027**: When presenting the current game and queued games, the system MUST display each game's
  key art / cover image, sourced from the captured Rich Presence image assets when available
  (otherwise from a best-effort lookup by the captured name/ID); if no art is available, the entry is
  shown without a thumbnail (name only).
- **FR-028**: The queue view MUST be presented **privately (ephemeral) to the requesting member**. It
  presents the current week's game and the next 5 queued games (in order, each with key art, proposer,
  position, and current upvote count). If the requester has their own un-played queued game outside the
  next 5, the view MUST additionally show that entry marked as theirs; if it is within the next 5, that
  entry MUST be marked as theirs in place and never duplicated. Upvote counts shown in the ephemeral
  view are a **snapshot at render time** and are not updated afterward; the live count surface is the
  latest announcement message (see FR-038).
- **FR-029**: Each queued slot in the view MUST display its upvote count (the slot's server-wide total
  at render time), and members MUST be able to add and remove their upvote through interactive buttons
  (not emoji reactions) shown in their ephemeral view. Upvoting is a one-or-zero toggle (at most one
  upvote per member per slot). Clicking a button registers the member's vote and is acknowledged, but
  MUST NOT re-render the ephemeral message's counts (ephemeral messages stay snapshots); the registered
  change is reflected on the latest announcement message instead (FR-038).
- **FR-030**: An upvote MUST be bound to the specific queue slot (this session's appearance of the
  game), not to the game in general or to the server permanently; upvotes MUST NOT carry over when a
  played game is later proposed again (a new slot starts at zero). Upvotes are a social signal only and
  MUST NOT change the queue order or which game is played.
- **FR-031**: Because a member may have the upvote buttons open in several ephemeral renders at once
  (e.g., they ran the view twice), upvote and remove-upvote actions MUST be idempotent: the system
  records the member's upvote state for the slot and changes it only on an actual transition
  (not-upvoted → upvoted, or upvoted → not-upvoted); a duplicate action that does not change state MUST
  be a no-op and MUST NOT double-count.
- **FR-032**: After being unavailable, on resuming the system MUST bring the rotation up to date by
  computing how many whole 7-day periods elapsed since the last designation and applying each missed
  weekly advance exactly once, so the current week's game is correct even if a scheduled tick was
  missed.
- **FR-033**: While their proposed game is still queued (un-played), a member MAY withdraw it.
  Withdrawing MUST remove the slot from the queue and refund the coins the member spent on that slot
  (the propose cost and any bump costs), reversed out of the server pot as a balanced counter-entry. A
  game that has already been played cannot be withdrawn.
- **FR-034**: Running the propose command while the member already has a queued proposal MUST replace
  that proposal's captured game with the game from the member's current Rich Presence, keeping the
  slot's queue position. The replace MUST NOT charge any coins, and MUST reset that slot's upvotes to
  zero (the prior upvotes were cast for the previous game).
- **FR-035**: Whenever the propose command is executed (creating a new proposal OR replacing an
  existing one) and the member has no readable game activity (Rich Presence), the system MUST perform
  no action, charge nothing, and clearly inform the member that the action was not executed because no
  game was recognized — suggesting they check whether their activity / Rich Presence sharing is
  disabled in their Discord settings or permissions.
- **FR-036**: If a per-server announcement channel is configured, the system MUST automatically post
  the newly designated week's game (with its key art) to that channel on each weekly advance, and MUST
  include an "up next" preview listing the next 5 upcoming queued games with small thumbnails, names,
  and their current upvote counts. This newest announcement message is the live count surface kept up
  to date per FR-038. If no announcement channel is configured, no announcement is posted (the rotation
  is silent). On downtime catch-up, only the final current game MUST be announced, exactly once — not
  one message per missed week.
- **FR-037**: An authorized server role MAY set or clear the per-server announcement channel. Until one
  is set, no rotation announcements are posted.
- **FR-038**: The **single most-recent announcement message per server is the live upvote-count
  surface**: whenever an upvote is added or removed, the system MUST update that latest announcement
  message to show the current per-slot counts for its "up next" entries. Older announcement messages,
  all ephemeral views, and any other message MUST NOT be updated — they retain the counts they had when
  sent (a snapshot); members re-run the view to see fresh counts privately. If no announcement channel
  is configured, there is no live public count surface (snapshot ephemeral counts only).

### Key Entities *(include if feature involves data)*

- **Queue**: per server, the ordered list of proposed games that are waiting to be played. Has a
  single unambiguous top.
- **Proposed Game (Queue Entry / Slot)**: a game waiting in, or already played out of, the queue.
  Attributes: the server it belongs to, the proposing member, the captured game snapshot it was created
  from (Rich Presence identity + metadata + art references), its position while queued, its status
  (queued or played), its set of per-slot upvotes, and — once played — the week it was designated. Each
  entry is a distinct "slot".
- **Captured Game (Rich Presence Snapshot)**: the game identity and associated metadata captured from
  the proposing member's Discord Rich Presence at propose time — the game/application ID when
  available, otherwise the activity name, plus as much additional Rich Presence detail and image-asset
  references as were available. Stored in full so later logic can decide whether two slots refer to the
  same game; exact cross-launcher matching is best-effort.
- **Upvote**: a member's one-or-zero signal on a specific queue slot — at most one per member per slot,
  toggleable, bound to that slot only. Upvotes are a social signal and never affect order or rotation.
- **Weekly Designation (Rotation Record)**: per server and per week, the single game designated as
  that week's game (or "none"). The ordered history of these records is the rotation log.
- **Re-proposal Cooldown / Eligibility**: per member per server, whether the member may propose,
  derived from whether they already have a queued game and how many of the N games captured when their
  last game was popped remain to be played (the cooldown counts down only as games are played).
- **Queue Configuration**: per server, the propose cost, the bump cost, and the optional announcement
  channel (unset by default; when set, weekly-advance announcements are posted there).
- **Member Coin Balance**: the member's per-server coin balance (owned by the existing economy); this
  feature only spends from it.
- **Server Pot**: a per-server account that receives the coins spent on proposing and bumping (the
  balanced counter-entry). It accrues but is not spent by this feature.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of proposed games are eventually designated as a week's game — no game can be
  permanently kept out of play by others' coin spending.
- **SC-002**: An action a member cannot afford (propose or bump) results in zero change to their
  balance and to the queue — no partial effects — in 100% of cases.
- **SC-003**: A duplicated or double-submitted action is charged at most once and applied at most once
  (zero double-charges, zero duplicate entries).
- **SC-004**: For any fixed queue state, the weekly advance designates the same game every time
  (100% reproducible).
- **SC-005**: When a member's game is played with N games then waiting, that member becomes eligible
  to propose again only after exactly N further games have been played — the "wait N games" cooldown
  holds in 100% of cases.
- **SC-006**: The cooldown is fixed at play time and counts only games actually played: proposals made
  afterward never lengthen or shorten it, and empty weeks never count it down — so a member who
  participates weekly gets the same opportunity as a highly-active member.
- **SC-007**: The current week's game is unambiguously identifiable (exactly one, or explicitly none)
  at all times.
- **SC-008**: A member can view the exact queue order, the current week's game, and their own
  eligibility to propose, with no ambiguity.
- **SC-009**: A bump moves the member's game up by exactly one position and never adds, removes, or
  reorders any unrelated entry beyond that single swap.
- **SC-010**: From an empty initial state, the first proposed game becomes the current week's game
  immediately — there is never a dead first week.
- **SC-011**: 100% of accepted proposals are created from the proposing member's live game activity
  (Rich Presence) captured at propose time; a member with no readable game activity cannot propose
  (rejected, no charge).
- **SC-012**: The private (ephemeral) queue view always shows the current game and the next 5 queued
  games with key art and current upvote counts, and always includes the viewer's own queued entry
  (marked), even when it is beyond the top 5.
- **SC-013**: A member's upvote on a slot is counted at most once no matter how many message copies
  they interact with; the count changes only on a real state transition (zero double-counts).
- **SC-014**: After any downtime, the current week's game reflects exactly the number of weekly
  advances due since the last pop — no week is missed or double-applied.
- **SC-015**: Withdrawing a still-queued proposal removes the slot and returns exactly the coins the
  member spent on it, with the server pot reduced by the same amount (verifiable, fully balanced).
- **SC-016**: When an announcement channel is configured, each weekly advance posts exactly one
  announcement of the new current game to that channel; with none configured, no announcement is posted.
- **SC-017**: A configured announcement includes the current game (with key art) and an "up next"
  preview of the next 5 upcoming games (thumbnails + names + upvote counts); only the latest
  announcement message is updated on each upvote change, and no other message (ephemeral or older) is
  updated.

## Assumptions

- This feature builds on the existing per-server coin economy: members already hold coins, and
  proposing/bumping **spends** from that balance. Spent coins are credited to a per-server pot account
  (the double-entry counter-entry); the pot accrues but is not consumed by this feature.
- A member may hold at most one un-played proposed game in the queue at a time; combined with the
  "wait N games" cooldown, this is what equalizes opportunity across members regardless of how often
  they are online, and makes the cooldown well-defined ("their game").
- A newly proposed game joins at the **back** (tail) of the queue.
- The weekly rotation advances automatically — no moderator command triggers it. Each designated game
  lasts exactly 7 days: the next advance is 7 days after the last designation (pop), measured per
  server, with the bootstrap instant-pop starting the clock. No calendar/timezone alignment is required.
- Propose and bump costs are whole, positive numbers; the defaults are **1 coin to propose** and
  **1 coin per bump**, changed by the same authorized server role that manages other coin settings.
- A game's identity comes from the proposing member's live Discord Rich Presence at propose time (they
  must be playing the game). The system stores the captured ID-or-name plus as much Rich Presence
  detail/art as available. Rich-presence names can vary by launcher (Steam/Epic/PlayStation/etc.), so
  reliably matching "the same game" across members/launchers is best-effort and not guaranteed here.
  Two members playing the same game produce distinct queue slots.
- Reading a member's game activity requires the platform's presence data and the member sharing their
  activity status; if it is unavailable or hidden, proposing is not possible until they share it.
- If a member has multiple simultaneous activities, the system captures the first/primary game-type
  activity and ignores non-game activities (music, streaming, custom status); if none is a game, the
  proposal is rejected.
- Upvotes are non-binding social signals tied to a specific queue slot; they never change queue order
  or rotation, and do not persist across a game's future re-proposals.
- The weekly advance is derived from elapsed time since the last pop, so the bot may be offline at the
  exact boundary and still catch up on restart (applying each missed week exactly once).
- Rotation announcements are opt-in per server: nothing is posted until an authorized role configures
  an announcement channel; thereafter announcements are automatic in that channel.
- If a member leaves the server, their already-queued game remains in the rotation and is eventually
  played; their eligibility tracking is retained.

## Dependencies

- The existing per-server coin economy and balance (members earn and hold coins elsewhere; this
  feature only spends them).
- The existing per-server configuration mechanism and authorized moderator role used to set costs.
- The platform's rich-presence / activity data for members (requires the presence gateway intent and
  the member sharing game activity). Proposing reads the member's current activity; an optional
  external art source may enrich cover art by captured name/ID.
- Interactive message buttons for the upvote toggle (distinct from emoji reactions).

## Out of Scope

- Earning coins by playing the current week's game.
- The collective early-skip ("skip jar").
- Per-game time limits beyond the weekly advance.
- Moderator queue overrides (manually reordering, inserting, or removing games).
- Upvotes influencing queue order or rotation — upvotes are signal-only; coins (bumping) are the sole
  lever on position.
- Consuming or spending the per-server pot (it only accrues in this feature).
- Reliable cross-launcher "same game" de-duplication or matching — rich-presence names vary by
  launcher; stored snapshots enable best-effort handling only, not guaranteed matching.
