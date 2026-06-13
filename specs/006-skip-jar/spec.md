# Feature Specification: Skip Jar

**Feature Branch**: `006-skip-jar`

**Created**: 2026-06-13

**Status**: Draft

**Input**: User description: "Add the skip jar: a collective, opt-in way for the people actually
playing the current week's game to retire it early and advance to the next game, funded by coins so
it has a real cost and cannot be triggered casually. User stories: a member who has been earning
coins from the current week's game can pay one coin into the skip jar to vote to move on early; a
member can see how full the skip jar is and how many more contributions are needed to trigger a
skip; once enough participants have paid into the jar, the current game is retired early and the
rotation advances to the next game. Requirements: contributing costs each member exactly one coin,
and a member can contribute at most once per active game; the jar triggers a skip when contributions
reach a threshold equal to a majority of the distinct members who have earned coins from the current
game, with a floor never less than a configurable minimum (default three); the skip jar cannot
activate until the current game has been the week's game for at least a configurable minimum dwell
time; when a skip triggers, the rotation advances using the same deterministic rules as the normal
weekly advance; only members who have earned coins from the current game can contribute, configurable
by admins (if set to no, it does not check earning). Out of scope: skipping a game that has not yet
started, moderator-forced skips."

## Clarifications

### Session 2026-06-13

- Q: Where does a contributed coin's balanced counter-entry go in the ledger? → A: A **dedicated
  per-game "skip jar" pot** account, separate from the queue propose pot. Each contribution debits the
  member and credits this skip-jar pot; the pot is associated with the current game run and is not
  shared with the propose pot or any other sink. Coins in it remain non-refundable.
- Q: What makes a member count as having "earned coins from the current game" (for the threshold base
  and gate-on eligibility)? → A: At least **one credited participation drop** for the current game run
  — a coin actually landed in their balance. Accrued-but-not-yet-credited qualifying time (a partial
  drop) does NOT make a member an earner.
- Q: What is the default minimum dwell time before a game's skip jar opens? → A: **24 hours** (1 day),
  changeable per server by the admin role. A game must have been the current week's game for at least
  24 hours before its jar accepts contributions or can trigger a skip.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Pay a coin into the skip jar to vote to move on (Priority: P1)

A member who has been earning coins by playing the current week's game decides it has run its course.
They pay **one coin** into the skip jar as a vote to retire the game early and move the rotation on.
The coin is a real, non-refundable cost, so the vote carries weight and cannot be cast casually. The
same member can contribute at most once for the current game.

**Why this priority**: This is the core action of the feature — the coin-funded vote. Without it
there is no skip jar. It is independently valuable: even before any skip ever triggers, a server can
see participants registering their wish to move on, and the cost gives that signal teeth.

**Independent Test**: With a current week's game that has passed its minimum dwell time and a member
who has earned coins from it, have that member contribute; confirm exactly one coin is debited and
recorded, the contribution is counted toward the jar, a second contribution by the same member for
the same game is refused with no further charge, and a member who has not earned coins from the
current game (while the participation gate is on) cannot contribute.

**Acceptance Scenarios**:

1. **Given** a current week's game past its minimum dwell time, the participation gate on, and a
   member who has earned coins from that game with at least one coin in their balance, **When** they
   contribute to the skip jar, **Then** exactly one coin is debited from them, recorded as a distinct
   movement, and their contribution is counted toward the jar.
2. **Given** a member who has already contributed to the skip jar for the current game, **When** they
   attempt to contribute again for the same game, **Then** the attempt is refused, no further coin is
   charged, and the jar count is unchanged.
3. **Given** the participation gate is on and a member who has **not** earned any coins from the
   current game, **When** they attempt to contribute, **Then** the attempt is refused and no coin is
   charged.
4. **Given** an eligible member whose balance is zero, **When** they attempt to contribute, **Then**
   the attempt is refused for insufficient balance and the jar count is unchanged.
5. **Given** the participation gate is **off**, **When** any member of the server contributes, **Then**
   the contribution is accepted (subject only to the one-coin cost, sufficient balance, and the
   once-per-game limit) without checking whether they earned coins from the current game.

---

### User Story 2 - The group retires the game early and the rotation advances (Priority: P2)

Once enough participants have paid into the jar, the current game is retired early and the rotation
advances to the next game — using exactly the same deterministic rules as the normal weekly advance.
The threshold that "enough" means is a majority of the distinct members who have earned coins from
the current game, but never fewer than a configurable minimum (default three).

**Why this priority**: This is the payoff that makes the contributions matter — the collective skip.
It depends on contributions existing (US1) but is independently testable by driving contributions to
the threshold and confirming the game changes.

**Independent Test**: With contributions accumulating for a current game past its dwell time, drive
the count to the computed threshold and confirm that on the threshold-meeting contribution the game
is retired and the rotation advances to the next game exactly as a normal weekly advance would; with
contributions one short of the threshold, confirm nothing advances.

**Acceptance Scenarios**:

1. **Given** a skip jar whose contribution count is one short of the current threshold, **When** the
   threshold-meeting contribution is made, **Then** the current game is retired early and the
   rotation advances to the next game using the same deterministic rules as the weekly advance.
2. **Given** a skip jar below the threshold, **When** a contribution leaves it still below threshold,
   **Then** the current game is unchanged and the jar continues accumulating.
3. **Given** there are N distinct members who have earned coins from the current game, **When** the
   threshold is computed, **Then** it equals a majority of N (more than half) but never less than the
   configured minimum (default three).
4. **Given** a skip has just triggered and the rotation has advanced to a new game, **When** members
   look at the skip jar, **Then** it is empty for the new game and contributions for the retired game
   no longer count.
5. **Given** two eligible members contribute at nearly the same instant and either would meet the
   threshold, **When** both contributions are processed, **Then** the game advances exactly once (no
   double-advance) and the rotation moves on by a single step.

---

### User Story 3 - See how full the skip jar is and how many more votes are needed (Priority: P3)

A member can check the skip jar for the current game: how many contributions it holds, the threshold
needed to trigger a skip, and therefore how many more contributions are required. If the game has not
yet passed its minimum dwell time, they can see that the jar is not open yet and roughly how long
remains.

**Why this priority**: Visibility makes the collective vote legible and coordinated, but it depends
on the jar existing (US1/US2). It is the smallest incremental slice and reuses a simple read.

**Independent Test**: With a current game and some contributions in the jar, have a member view the
skip jar and confirm it shows the current count, the threshold, and the remaining number needed;
during the dwell period, confirm it shows the jar is not yet open.

**Acceptance Scenarios**:

1. **Given** a current game past its dwell time with some contributions, **When** a member views the
   skip jar, **Then** they see the current contribution count, the threshold, and how many more
   contributions are needed to trigger a skip.
2. **Given** a current game still within its minimum dwell time, **When** a member views the skip
   jar, **Then** they see that the jar is not open for contributions yet (and may see when it opens).
3. **Given** a server with no current week's game, **When** a member views the skip jar, **Then** the
   view reports there is no game to skip rather than erroring.

---

### User Story 4 - Admin configures the skip jar (Priority: P4)

A member holding the server's configured admin role tunes the skip jar for the server: the minimum
threshold floor (default three), the minimum dwell time a game must run before it can be skipped, and
whether the participation gate is on (only earners may contribute) or off (any member may contribute).

**Why this priority**: The skip jar works on sensible defaults without any configuration, so tuning
ranks last; but servers need to adjust the floor, dwell, and gate to fit their size and culture.

**Independent Test**: As a member with the admin role, change the threshold floor, the dwell time,
and toggle the participation gate, and confirm each takes effect on subsequent evaluations; confirm a
member without the admin role cannot change any of them.

**Acceptance Scenarios**:

1. **Given** a member with the server's configured admin role, **When** they set the minimum
   threshold floor, **Then** subsequent skip-threshold computations use the new floor.
2. **Given** a member with the admin role, **When** they set the minimum dwell time, **Then** the
   skip jar for the current game opens only after the game has been current for at least that long.
3. **Given** a member with the admin role, **When** they turn the participation gate off, **Then**
   any member may contribute without having earned coins from the current game; **When** they turn it
   on, **Then** only members who have earned coins from the current game may contribute.
4. **Given** a member who does **not** hold the configured admin role, **When** they attempt to change
   any skip-jar setting, **Then** the action is refused and the settings are unchanged.

---

### Edge Cases

- **Dwell not yet elapsed**: while the current game is still within its minimum dwell time, the skip
  jar is closed — contributions are refused (no coin charged) and the game cannot be skipped, so every
  game gets its fair minimum run.
- **No current game**: with no current week's game, there is nothing to skip; contributions are
  refused and the status view reports there is no game.
- **No earners yet, gate on**: if no member has earned coins from the current game and the
  participation gate is on, no one is eligible to contribute, so the jar cannot fill or trigger until
  someone earns.
- **No earners yet, gate off**: with the gate off the threshold floor still applies, so the configured
  minimum number of any members (default three) can trigger a skip once dwell has elapsed, even if
  no one has earned yet.
- **Threshold rises as more members earn**: the earner set only grows while a game is current, so the
  majority threshold can increase over time; a jar that was one short of an earlier threshold simply
  stays un-triggered, and the next contribution is evaluated against the current (possibly higher)
  threshold. A skip is only ever evaluated at the moment a contribution is made.
- **Threshold floor exceeds majority**: when a majority of the earner set is smaller than the floor
  (e.g., one or two earners), the floor governs and that many contributions are required.
- **Duplicate contribution**: a member who has already contributed for the current game cannot
  contribute again; the attempt is refused with no additional charge.
- **Insufficient balance**: a member without at least one coin cannot contribute; the jar is
  unchanged.
- **Contribution is non-refundable**: a contributed coin is spent immediately and is never returned —
  not when the game is later skipped, not when the game advances normally on the weekly schedule
  before the threshold is met, and not when the contributor leaves the server.
- **Normal weekly advance before a skip**: if the weekly rotation advances the game before the jar
  triggers, the jar for the retired game is discarded (contributions already spent, non-refundable)
  and a fresh empty jar applies to the new current game.
- **Game re-appears later**: if the same game becomes the current week's game again in a future run,
  it starts with a fresh, empty jar; eligibility and the threshold are computed for the new run only.
- **Concurrent threshold-meeting contributions**: simultaneous contributions that each would cross the
  threshold result in exactly one early skip and a single rotation step, with no double-advance.
- **Empty rotation on skip**: when a skip triggers, the rotation advances under the same deterministic
  rules as the weekly advance — including how those rules handle an empty queue (the game-queue
  feature owns that outcome; the skip jar does not invent new advance behavior).
- **Per-server scope**: the skip jar, its contributions, the threshold floor, the dwell time, and the
  participation-gate setting are all scoped per server and never shared across servers.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A member MUST be able to contribute to the current game's skip jar at a fixed cost of
  **exactly one coin**, debited at contribution time. The contribution cost is fixed and not
  configurable.
- **FR-002**: A member MUST be able to contribute **at most once** per current game; a second
  contribution attempt for the same current game MUST be refused with no additional charge and no
  change to the jar count.
- **FR-003**: A contributed coin MUST be spent immediately and is **non-refundable** — it is never
  returned whether the skip triggers, the game advances normally before the threshold is met, or the
  contributor later leaves the server.
- **FR-004**: While the **participation gate is on** (the default), only members who have **earned
  coins from the current game** MAY contribute; a member who has not earned from the current game MUST
  be refused with no charge.
- **FR-005**: A member holding the server's **configured admin role** MUST be able to turn the
  participation gate **off**, in which case any member of the server MAY contribute without the earning
  check (subject only to the one-coin cost, sufficient balance, and the once-per-game limit); and back
  **on** again.
- **FR-006**: A contribution MUST be refused (no coin charged, jar unchanged) when the contributor has
  insufficient balance (fewer than one coin).
- **FR-007**: The skip jar MUST be **closed** (contributions refused, no coin charged, no skip
  possible) until the current game has been the current week's game for at least the configured
  **minimum dwell time** (default **24 hours**), so every game gets a fair minimum run before it can
  be cut short.
- **FR-008**: The skip threshold MUST equal a **majority** (more than half) of the count of **distinct
  members who have earned coins from the current game**, but MUST never be less than a configurable
  **minimum floor** (default **three**).
- **FR-009**: The skip threshold MUST be evaluated at the moment each contribution is made, against
  the current distinct-earner count; the system is NOT required to re-evaluate or trigger a skip at any
  other time (e.g., when the earner set grows).
- **FR-010**: When a contribution causes the jar's count to **reach or exceed the threshold** (and the
  dwell time has elapsed), the current game MUST be retired early and the rotation MUST advance to the
  next game **using the same deterministic rules as the normal weekly advance**, with no new advance
  behavior introduced by this feature.
- **FR-011**: An early skip MUST advance the rotation by exactly **one step** and MUST NOT
  double-advance, even when multiple threshold-meeting contributions are processed concurrently.
- **FR-012**: When the current game changes — whether by an early skip or by the normal weekly advance
  — the skip jar MUST reset: the new current game starts with an **empty jar**, and contributions made
  toward the retired game no longer count and cannot trigger a skip.
- **FR-013**: Each contribution MUST produce a balanced, append-only ledger record consistent with the
  existing coin economy: the member is debited and the balanced counter-entry credits a **dedicated
  per-game "skip jar" pot** account (separate from the queue propose pot and from any other sink),
  recorded as a **distinct movement type** so it is distinguishable in coin history from participation
  earnings, moderator adjustments, and queue spends; it MUST NOT edit or delete any prior record.
- **FR-014**: A member MUST be able to view the current game's skip jar status: the current
  contribution count, the threshold needed, and how many more contributions are required; when the
  game is still within its dwell time the status MUST indicate the jar is not yet open; when there is
  no current game the status MUST report that there is nothing to skip without erroring.
- **FR-015**: A member holding the server's **configured admin role** MUST be able to set the minimum
  threshold **floor** (a positive integer, default three) per server.
- **FR-016**: A member holding the server's **configured admin role** MUST be able to set the minimum
  **dwell time** per server.
- **FR-017**: A member who does **not** hold the server's configured admin role MUST NOT be able to
  change the threshold floor, the dwell time, or the participation-gate setting.
- **FR-018**: All skip-jar state — contributions, the threshold floor, the dwell time, and the
  participation-gate setting — MUST be scoped **per server** and never shared across servers.
- **FR-019**: The skip jar MUST NOT be usable to skip a game that has not yet started (there is no
  current game to skip), and it MUST NOT provide any moderator-forced or unilateral skip path; a skip
  occurs only when contributions reach the threshold.
- **FR-020**: "Members who have earned coins from the current game" MUST be interpreted as the distinct
  members credited **at least one completed participation drop** attributed to the **current run** of
  the current week's game (consistent with the participation-earning feature). Accrued qualifying time
  that has not yet completed a drop does NOT count, and a future re-run of the same game starts a fresh
  earner set.

### Key Entities *(include if feature involves data)*

- **Skip Jar**: per server, the collection of contributions toward retiring the **current** week's
  game early. Empty when a game becomes current; reset whenever the current game changes (by skip or
  weekly advance). Holds at most one contribution per member per current game.
- **Skip Contribution**: a single member's one-coin, non-refundable vote to skip the current game,
  recorded once per member per current-game run. Backed by a balanced, append-only ledger entry of a
  distinct movement type: a member debit balanced by a credit to the **dedicated skip-jar pot** for
  the current game run.
- **Skip-Jar Pot**: a dedicated per-server, per-game-run account that receives the balanced credit for
  each contribution, separate from the queue propose pot. Coins it holds are non-refundable and the
  pot is scoped to the current game run.
- **Skip Threshold**: the computed number of contributions required to trigger a skip — a majority of
  the distinct earners of the current game, floored at the configurable minimum (default three).
  Evaluated at each contribution.
- **Skip-Jar Configuration**: per server — the minimum threshold **floor** (positive integer, default
  three), the minimum **dwell time** a game must be current before its jar opens, and the
  **participation-gate** flag (on by default; off lets any member contribute).
- **Current Week's Designated Game (run)**: owned by the game-queue feature; this feature reads which
  game is current and when it became current (to measure dwell), and invokes the queue's deterministic
  advance when a skip triggers.
- **Distinct Earner Set (current game)**: owned by the participation-earning feature; the set of
  members credited a participation earning for the current run of the current game, read to size the
  threshold and (when the gate is on) to decide contribution eligibility.
- **Member Coin Balance / Ledger**: owned by the existing economy; this feature debits one coin per
  contribution into it as a distinct, append-only movement.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Each contribution costs the contributor exactly one coin and never more; in 100% of
  contributions exactly one coin is debited and recorded.
- **SC-002**: A member can contribute at most once per current game; repeat attempts for the same game
  are refused with zero additional charge in 100% of cases.
- **SC-003**: With the participation gate on, members who have not earned coins from the current game
  contribute zero times (every such attempt is refused).
- **SC-004**: No game can be skipped before it has been current for at least the configured minimum
  dwell time; zero early skips occur during the dwell window.
- **SC-005**: A skip triggers exactly when contributions reach a majority of the distinct earners of
  the current game, never below the configured floor (default three); the threshold matches this rule
  in 100% of evaluations.
- **SC-006**: When a skip triggers, the rotation advances by exactly one step using the same
  deterministic rules as the weekly advance, with zero double-advances even under concurrent
  threshold-meeting contributions.
- **SC-007**: Contributed coins are never refunded — zero refunds across triggered skips, normal
  weekly advances, and contributor departures.
- **SC-008**: A member viewing the skip jar sees an accurate count, threshold, and remaining-needed
  for the current game (or a clear "not open yet" / "no game" state) in 100% of views, without error.
- **SC-009**: Only a member with the server's configured admin role can change the threshold floor,
  the dwell time, or the participation gate; unauthorized attempts change nothing in 100% of cases.
- **SC-010**: When the current game changes (by skip or weekly advance), the new game's skip jar
  starts empty and retired-game contributions never count toward it (0 carry-over).

## Assumptions

- **Builds on the existing economy, queue, and participation earning**: this feature reuses the
  per-server coin economy (balance, append-only double-entry ledger, movement types, coin history,
  per-server balance cap) from the coin-ledger feature, the **current week's game** and its
  **deterministic weekly advance** from the game-queue feature, and the **distinct earners of the
  current game** from the participation-earning feature. It adds a new spend path and an early-skip
  trigger; it does not change how balances, the queue, or earnings are computed.
- **Admin authorization is the server's configured economy-administration role** (the role designated
  through the bot's configuration commands), the same role used to administer participation earning —
  not Discord's generic Manage-Server permission. Concretely this is the **coin-moderator role**
  (`GuildCoinConfigPort`); where this spec says "configured admin role" the implementation reads that
  same role. Consistent with the existing `/coins-config` and `/participation-config` commands, a
  Discord **Administrator** also passes the check (an explicit bypass of the configured role — distinct
  from the generic Manage-Server permission, which does **not** grant access), and the check **fails
  closed** when no moderator role is configured.
- **"Majority" means strictly more than half**: for N distinct earners the majority is `floor(N/2) + 1`,
  and the threshold is the larger of that majority and the configured floor (default three).
- **Contribution cost is fixed at one coin** and is not configurable; only the floor, dwell time, and
  gate are configurable.
- **The contributed coin credits a dedicated per-game "skip jar" pot** (separate from the queue
  propose pot), recorded as a distinct "skip-jar contribution" movement; it is non-refundable, and
  what becomes of the pot's accumulated coins after a skip is out of scope here (the coins simply
  remain in the pot — no distribution, refund, or burn is defined by this feature).
- **Default minimum dwell time is 24 hours** per server, changeable by the admin role — a sensible
  "fair minimum run" for a weekly game.
- **The participation gate defaults to ON**, matching the feature's framing that the people *playing*
  the game decide to move on; admins can switch it off so any member may contribute.
- **The skip jar is evaluated only on contribution**: because the earner set only grows while a game
  is current, the threshold never falls during a run, so the only moment a skip can be reached is when
  a contribution is made — no background re-evaluation is required.
- **Dwell is measured from when the game became current** (its instant-pop or weekly-advance moment,
  as recorded by the game-queue feature), per current-game run.
- **The skip jar is closed during dwell**: contributions are refused (no coin taken) until dwell
  elapses, rather than accepted-and-held, so no member spends a coin on a jar that cannot yet act.

## Dependencies

- The game-queue feature's **current week's designated game**, the timestamp of when it became
  current (for dwell), and its **deterministic advance** routine (invoked when a skip triggers,
  including its handling of an empty queue).
- The participation-earning feature's notion of **distinct members who have earned coins from the
  current game** (for threshold sizing and, when the gate is on, contribution eligibility).
- The existing per-server coin economy: balance, append-only double-entry ledger, movement types, and
  coin history view (a contribution is a balanced debit of a distinct movement type).
- The per-server configured admin role used to administer the economy.

## Out of Scope

- Skipping a game that has not yet started (there must be a current game to skip).
- Moderator-forced, admin-forced, or any unilateral skip path; a skip happens only by reaching the
  contribution threshold.
- Refunding contributed coins under any circumstance.
- Removing or changing an individual contribution after it is made.
- Changing how the rotation advances; the early skip reuses the queue's existing deterministic advance
  unchanged.
- Changing how balances, caps, forfeiture, coin history, or participation earnings are computed
  (reused unchanged from the prior features).
- A master on/off switch for the whole skip-jar feature (only the floor, dwell, and participation gate
  are configurable).
