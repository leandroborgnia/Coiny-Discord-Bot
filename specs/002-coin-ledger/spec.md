# Feature Specification: Coin Economy & Append-Only Audit Trail

**Feature Branch**: `002-coin-ledger`

**Created**: 2026-06-09

**Status**: Draft

**Input**: User description: "Add a coin economy that records every coin movement in a tamper-evident, append-only audit trail. Coins represent participation in the group and are the currency members spend to influence which game gets played. Members can check their balance and recent history; coins are theirs alone and non-transferable; moderators can grant or deduct coins as a corrective action attributed to them. Coins are whole numbers (smallest unit 1), balances never go negative, every movement produces a balanced auditable record that can be appended but never edited or deleted, balances are capped at a configurable maximum with excess forfeited at earning time, and the same logical operation is never applied twice. Out of scope: earning from gameplay, spending on queue actions, the skip jar, real-money conversion — for now coins enter and leave only via moderator adjustments."

## Clarifications

### Session 2026-06-09

- Q: What is the identity scope of a member's coin balance (per server vs global)? → A: Per server/guild — a member holds a separate, independent balance and audit trail in each server; account identity = (member, server).
- Q: How is a "moderator" authorized to grant/deduct coins? → A: A configured moderator role per server authorizes adjustments (the role is designated per server and stored).
- Q: How is the "same logical operation" identified for at-most-once application? → A: The platform's unique command-invocation (interaction) id is the idempotency key; a repeated processing of the same invocation is honored once, while a deliberate re-issue is a new operation.
- Q: How is the configurable balance cap scoped and defaulted? → A: Per-server configurable cap with an out-of-the-box default of 12 coins until a server changes it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Moderator grants or deducts coins as a corrective action (Priority: P1)

A moderator awards or removes coins from a specific member as a deliberate corrective
action. The change is applied atomically and produces a permanent, auditable record that
names the acting moderator, the affected member, the amount, the direction, and the
reason. This is the only path by which coins currently enter or leave the economy, so it
is what exercises the ledger and audit trail end to end.

**Why this priority**: Without a way to move coins into and out of accounts, there is no
economy to view and no audit trail to inspect. The moderator adjustment is the engine that
populates the ledger; it is the minimum slice that proves the append-only, balanced,
capped, non-negative, at-most-once guarantees actually hold.

**Independent Test**: From an empty state, have a moderator grant coins to a member, then
deduct some, and confirm: the member's balance reflects exactly the net of the two
movements, two immutable audit records exist each naming the moderator, and no record can
be altered or removed afterward.

**Acceptance Scenarios**:

1. **Given** a member with a balance of 0, **When** a moderator grants them 50 coins with a
   reason, **Then** the member's balance becomes 50 and an immutable audit record is created
   showing the moderator's identity, the affected member, +50, the movement type, the
   reason, and a timestamp.
2. **Given** a member with a balance of 50, **When** a moderator deducts 20 coins, **Then**
   the member's balance becomes 30 and a second immutable audit record is appended; the
   first record is unchanged.
3. **Given** a member with a balance of 30, **When** a moderator attempts to deduct 100
   coins, **Then** the operation fails atomically, the balance stays at 30, and no audit
   record is created.
4. **Given** the configured maximum balance is 100 and a member holds 80, **When** a
   moderator grants 50 coins, **Then** the member is credited only the 20 that fit under the
   cap, the remaining 30 are forfeited, the balance becomes 100, and the audit record(s)
   show both the 20 credited and the 30 forfeited.
5. **Given** a moderator submits an adjustment, **When** the very same logical adjustment is
   submitted again (a retry or double-click), **Then** the adjustment is applied at most
   once — the balance and audit trail reflect a single movement, and the repeat returns the
   original outcome.
6. **Given** any member, **When** a moderator attempts to grant or deduct 0 or a negative
   amount, **Then** the operation is rejected and nothing changes.
7. **Given** a member who does not hold the server's configured moderator role, **When** they
   attempt to grant or deduct coins, **Then** the action is refused and no coins move and no
   record is created.

---

### User Story 2 - Member checks balance and recent history (Priority: P2)

A member views how many coins they currently hold and a list of their most recent coin
movements — earnings, spends, and corrective adjustments — so they understand their standing
and can see how it changed.

**Why this priority**: Visibility is the member-facing value of the economy, but it depends
on movements existing first (US1). It is independently demonstrable once any movement has
been recorded.

**Independent Test**: Seed a member with a few moderator adjustments, then have that member
request their balance and history, and confirm the balance equals the sum of their recorded
movements and the history lists those movements newest-first with their amounts, directions,
and reasons.

**Acceptance Scenarios**:

1. **Given** a member with recorded movements, **When** they check their balance, **Then**
   they see a single whole-number balance equal to the sum of all their movements.
2. **Given** a member with several movements, **When** they view their history, **Then** they
   see their most recent movements in reverse chronological order, each showing amount,
   direction, type, and reason.
3. **Given** a member who has never had any coin movement, **When** they check their balance
   and history, **Then** they see a balance of 0 and an empty history without error.
4. **Given** a member, **When** they request balance or history, **Then** they see only their
   own balance and history, not anyone else's.

---

### User Story 3 - Coins are mine alone (non-transferable) (Priority: P3)

A member's coins belong to that member only. There is no gift, trade, or peer-to-peer
transfer capability — a member cannot move coins to another member by any path.

**Why this priority**: This is a guardrail that protects the integrity of the economy. It is
a guarantee about what the system must *never* allow, verifiable independently of the
adjustment and viewing flows.

**Independent Test**: Confirm there exists no action, command, or workflow that lets a member
move, gift, or trade coins to another member, and that the only way coins change hands is a
moderator adjustment recorded in the audit trail.

**Acceptance Scenarios**:

1. **Given** two members each holding coins, **When** either member attempts to give, trade,
   or transfer coins to the other, **Then** no such capability exists and no coins move.
2. **Given** the full set of available member actions, **When** they are enumerated, **Then**
   none of them results in coins moving from one member to another.

---

### Edge Cases

- **Overdraw**: A deduction larger than the member's current balance fails atomically and
  leaves the balance and audit trail unchanged (no negative balances ever).
- **Deduct to exactly zero**: Deducting the member's entire balance is allowed and results in
  a balance of 0.
- **Grant at or above the cap**: A grant to a member already at the cap credits nothing; the
  entire granted amount is forfeited, yet an audit record is still produced documenting the
  forfeiture.
- **Partial cap overflow**: A grant that would cross the cap credits only the portion that
  fits and forfeits the remainder, with both amounts visible in the audit trail.
- **Duplicate / double-submitted operation**: A repeated submission of the same logical
  operation is honored at most once; the second submission does not create a second movement.
- **Zero or negative magnitude**: Adjustments of 0 or a negative magnitude are rejected.
- **Fractional amounts**: Any non-whole amount is rejected; the smallest unit is 1.
- **Member with no history**: Balance is 0 and history is empty; neither errors.
- **Cap lowered below an existing balance**: Lowering the configured maximum does not
  retroactively confiscate coins a member already holds; the cap is enforced only at the
  moment coins would be earned.
- **Concurrent adjustments to the same member**: Simultaneous adjustments are each resolved
  atomically so the balance never goes negative and never exceeds the cap.
- **Same member across servers**: A member's balance, history, cap, and moderator-role
  configuration in one server are fully independent of any other server; an adjustment in one
  server never affects the member's standing in another.
- **No moderator role configured yet**: Until a server designates its moderator role, no
  member can perform adjustments (the feature fails closed rather than open).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Coins MUST be whole numbers with a smallest unit of 1; any fractional amount is
  rejected.
- **FR-002**: A member's balance MUST never be negative; any operation that would overdraw the
  balance MUST fail atomically and change nothing.
- **FR-003**: Every coin movement MUST produce a single balanced, auditable record such that
  the books reconcile (what leaves one place arrives in another).
- **FR-004**: The audit trail MUST be append-only — records can be added but MUST NEVER be
  edited or deleted after they are written.
- **FR-005**: A member's balance MUST be derived from their recorded movements, never stored
  as an independently mutable value that can drift from the audit trail.
- **FR-006**: Coins MUST be non-transferable between members; the system MUST NOT provide any
  gift, trade, or peer-to-peer transfer path.
- **FR-007**: A configurable maximum balance (the cap) MUST be enforced **per server**, with a
  default of **12 coins** applied until that server changes it; coins that would be earned
  beyond the cap MUST be forfeited at the moment they would be earned, and the forfeiture MUST
  be recorded in the audit trail.
- **FR-008**: The same logical operation MUST be applied at most once; a repeated or
  double-submitted operation MUST be honored exactly once and the repeat MUST return the
  original outcome without creating a second movement.
- **FR-009**: A member holding the server's **configured moderator role** MUST be able to grant
  coins to a member as a corrective action.
- **FR-010**: A member holding the server's **configured moderator role** MUST be able to deduct
  coins from a member as a corrective action.
- **FR-011**: Every moderator adjustment MUST permanently record the acting moderator's
  identity, visible in the audit trail.
- **FR-011a**: Each server MUST be able to designate which role authorizes coin adjustments; a
  member lacking that role MUST be unable to grant or deduct coins.
- **FR-012**: A member MUST be able to view their own current balance.
- **FR-013**: A member MUST be able to view their own most recent coin history, ordered newest
  first, each entry showing the amount, direction, movement type, and reason/context.
- **FR-014**: Each movement record MUST capture, at minimum: the affected member, the amount,
  the direction (credit or debit), the movement type, the reason/context, the timestamp, and
  the initiating actor where one applies.
- **FR-015**: Members without the server's configured moderator role MUST NOT be able to grant,
  deduct, or otherwise adjust coin balances; only holders of that role may perform adjustments.
- **FR-016**: Adjustments of zero or a negative magnitude MUST be rejected.
- **FR-017**: A deduction equal to the member's full balance (bringing it to exactly 0) MUST
  be allowed.
- **FR-018**: When a grant would partially exceed the cap, the member MUST be credited only up
  to the cap and the remainder MUST be recorded as forfeited.
- **FR-019**: When a grant is made to a member already at the cap, no coins are credited, the
  full amount is recorded as forfeited, and an audit record is still produced.
- **FR-020**: The system MUST be able to reconcile any member's balance as the exact sum of
  their recorded movements at any time.
- **FR-021**: Each adjustment MUST be keyed by the platform's unique command-invocation
  (interaction) id to enable at-most-once application; re-processing the same invocation MUST
  return the original outcome without creating a second movement, while a deliberately
  re-issued command (a new invocation) is a distinct operation.
- **FR-022**: A member MUST only be able to view their own balance and history, not another
  member's.
- **FR-023**: Coin balances and audit trails MUST be scoped **per server**: a member's balance,
  history, cap, and moderator-role configuration in one server are independent of every other
  server. Account identity is (member, server).

### Key Entities *(include if feature involves data)*

- **Coin Account**: Represents a member's coin standing **within one server**. Has a current
  balance that is *derived*, not independently editable. Identified by (member, server); the
  same member has independent accounts across different servers.
- **Coin Movement (Ledger Entry)**: An immutable record of a single coin change, scoped to a
  server. Attributes: server, affected member, amount, direction (credit/debit), movement type
  (moderator grant, moderator deduction, forfeiture), reason/context, timestamp, initiating
  actor (the moderator, for adjustments), the command-invocation (interaction) id used as the
  idempotency key, and the balanced counterpart that keeps the books reconciled.
- **Audit Trail**: The append-only collection of all coin movements (per server). Supports
  reading a member's most recent history; never supports edit or delete.
- **Balance Cap Configuration**: The configurable maximum balance a member may hold in a
  server, defaulting to **12 coins** until the server changes it; enforced at the moment coins
  would be earned.
- **Moderator Role Configuration**: The per-server designation of which role authorizes coin
  adjustments. Only members holding that role may grant or deduct coins in that server.
- **Forfeiture**: The portion of an earning that exceeds the cap; recorded as a movement so the
  excess leaves the member's account without disappearing from the audit trail.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Across all operations, a member's balance never falls below 0 and never exceeds
  the configured cap (0 violations).
- **SC-002**: 100% of coin movements have a corresponding immutable audit record; no audit
  record is ever modified or removed after creation.
- **SC-003**: For any member at any time, the reported balance equals the exact sum of their
  recorded movements (100% reconciliation).
- **SC-004**: Every moderator adjustment is attributable to the acting moderator in the audit
  trail (100% attribution).
- **SC-005**: A repeated submission of the **same operation** — the same command invocation,
  identified by its interaction id — results in exactly one applied movement (at most once, 0
  duplicate applications). Per the Clarifications, a *deliberately re-issued* command is a new
  invocation and therefore a distinct operation by design; "double-submitted" here means a retry
  or redelivery of the same invocation, not a fresh one.
- **SC-006**: There exists no member-accessible path that moves coins from one member to
  another (0 transfer paths).
- **SC-007**: An overdraw attempt leaves the balance and audit trail completely unchanged in
  100% of cases.
- **SC-008**: A member can retrieve their current balance and recent history in a single
  interaction whose response is acknowledged in **under 3 seconds** (in practice the handler
  acknowledges in well under 2.5 seconds), and the returned history makes clear how the balance
  was reached.

## Assumptions

- **Coin scope is per server** *(resolved in Clarifications)*: Consistent with the multi-server
  foundation (`001-foundation-skeleton`), a member's coins, history, cap, and moderator-role
  configuration are scoped to the server; a member holds a separate, independent balance in
  each server. Account identity is (member, server).
- **Cap is per server with a default of 12** *(resolved in Clarifications)*: Each server may set
  its own cap; until it does, a default of 12 coins applies. Setting or changing the cap is a
  per-server moderator/administrator configuration action. Lowering the cap does not
  retroactively confiscate coins already held above the new cap; the cap is enforced only at
  earning time.
- **"Most recent history" is a bounded list**: The history view returns a bounded number of
  the latest movements (default: the last 10), newest first; full ledger export is not part of
  this feature.
- **Moderator authorization is a per-server configured role** *(resolved in Clarifications)*: A
  server designates a role that authorizes coin adjustments; holders of that role are
  "moderators" for this feature. The underlying platform's role/permission primitives are
  assumed available; bootstrapping who may set that configuration follows the platform's own
  administrator permissions.
- **Idempotency key is the command-invocation id** *(resolved in Clarifications)*: Each
  adjustment is keyed by the platform's unique interaction/invocation id; re-processing the
  same invocation is honored once. A deliberately re-issued command is a new operation.
- **Forfeited coins do not accrue to anyone**: Forfeitures leave the member's account to a
  non-member sink so the books still balance; no member or moderator receives the forfeited
  coins.
- **"Earnings" and "spends" in history are forward-looking**: The history is designed to show
  earnings, spends, and adjustments, but in this slice only moderator adjustments (and
  resulting forfeitures) occur; gameplay earnings and queue spends arrive in later features.
- **Builds on the foundation skeleton**: This feature assumes the running bot and persistence
  established by `001-foundation-skeleton`.

## Out of Scope

- Earning coins from gameplay.
- Spending coins on queue actions.
- The skip jar.
- Real-money conversion.

In this slice, coins enter and leave the economy **only** via moderator adjustments, so the
ledger and audit trail can be exercised end to end.
