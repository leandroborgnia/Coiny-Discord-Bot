# Feature Specification: Foundation Skeleton

**Feature Branch**: `001-foundation-skeleton`

**Created**: 2026-06-06

**Status**: Draft

**Input**: User description: "Establish the project's foundational, end-to-end working skeleton for the Discord bot — the calibration slice whose only purpose is a foundation that provably runs, persists, and ships."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Confirm the bot is alive end to end (Priority: P1)

A server member, in a Discord server the bot has joined, runs a single trivial liveness
command (e.g. `/ping`). The bot — visibly online — replies with a success message. That
single reply proves the entire foundation is wired: the bot received the interaction,
reached its data store, and answered. "It works" therefore means the whole path works,
not merely that the Discord connection is up.

**Why this priority**: This is the calibration slice's entire reason to exist. Without a
single command that exercises receive → persist → reply, there is no proof the foundation
runs and persists. It is the smallest shippable increment that delivers verifiable value.

**Independent Test**: With the bot running and added to a test server, invoke the liveness
command and observe a success response that confirms data-store reachability, returned
within the interaction-response window.

**Acceptance Scenarios**:

1. **Given** the bot is running and online in a test server, **When** a member invokes the
   liveness command, **Then** the bot replies with a success message within the
   interaction-response window.
2. **Given** the bot is running, **When** the liveness command runs, **Then** the response
   reflects that the data store was reached (a reply that could only be produced after a
   successful round-trip to the store).
3. **Given** the data store is briefly slow to respond, **When** a member invokes the
   liveness command, **Then** the interaction is still acknowledged in time and the final
   result is delivered when the check completes.

---

### User Story 2 - Reproducible local setup from a clean checkout (Priority: P2)

A developer joining the project clones the repository and, following documented setup steps
only, starts the local data store the documented way and reaches a locally running, online
bot. No undocumented tribal knowledge is required, so the path is reproducible across
machines and iteration is fast.

**Why this priority**: A foundation that only its author can run is not a foundation. Fast,
reproducible local setup is what lets every later slice be built and exercised, but it
depends on the liveness slice (P1) existing to confirm "running" means something.

**Independent Test**: On a machine with a clean checkout and no prior project state, follow
only the documented steps and confirm the bot comes online and answers the liveness command.

**Acceptance Scenarios**:

1. **Given** a clean checkout and the documented prerequisites, **When** the developer
   follows the documented steps, **Then** the local data store starts and the bot reaches a
   running, online state.
2. **Given** the documented steps were followed, **When** the developer invokes the liveness
   command, **Then** it succeeds — confirming the local setup is fully wired.

---

### User Story 3 - Automated checks gate every change (Priority: P3)

A maintainer needs confidence the foundation stays healthy. Every proposed change is
automatically built and tested, and the result is reported as a clear pass or fail. A change
that fails to build or breaks the tests cannot be merged.

**Why this priority**: Protects the foundation from regressing as later slices are added. It
is valuable from day one but is a guard rail around the running system rather than the
running system itself, so it follows the slices that produce something to guard.

**Independent Test**: Open a pull request containing a deliberately broken build or a failing
test and confirm the automated checks report a failure and the change is blocked from merge;
open a healthy pull request and confirm the checks pass.

**Acceptance Scenarios**:

1. **Given** a pull request with a healthy change, **When** the automated checks run, **Then**
   they report a clear pass.
2. **Given** a pull request with a deliberately broken build or failing test, **When** the
   automated checks run, **Then** they report a clear failure and the change cannot be merged.

---

### User Story 4 - Produce and run a single self-contained artifact (Priority: P4)

An operator produces a single self-contained, runnable artifact of the bot from the project
and launches it. The bot runs the same way wherever it is launched, matching the behavior of
the local run.

**Why this priority**: "Ships" is the third promise of the slice (runs, persists, ships). It
matters for parity and portability but depends on the running, persisting bot from the
earlier slices already existing.

**Independent Test**: Build the single artifact, launch it against a reachable data store and
valid secrets, and confirm the bot appears online and answers the liveness command exactly as
the local run did.

**Acceptance Scenarios**:

1. **Given** valid runtime secrets and a reachable data store, **When** the operator launches
   the produced single artifact, **Then** the bot appears online and answers the liveness
   command identically to the local run.

---

### Edge Cases

- **Data store unreachable at startup**: The application MUST fail fast with a clear,
  actionable error and MUST NOT start in a partially-initialized state or serve any command.
- **Schema cannot be brought up to date at startup**: The application MUST fail fast with a
  clear error and MUST NOT serve any command.
- **Required secret missing or empty**: Startup MUST fail with a clear error naming the missing
  secret; the bot MUST NOT come online.
- **Restart against an existing, already-current data store**: Startup MUST succeed with no
  manual steps and MUST NOT duplicate or corrupt prior schema history.
- **Data store slow (not failed) during a liveness check**: The interaction MUST still be
  acknowledged within the response window, with the final result delivered once the check
  completes.
- **Liveness command invoked while the data store is down after a healthy startup**: The
  command MUST surface a non-success result rather than appear to succeed.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose a single trivial liveness command that any member of a
  server the bot has joined can invoke.
- **FR-002**: The liveness command MUST exercise the full path end to end — receive the
  interaction, reach the data store, and reply — so that a success response proves the whole
  foundation is wired, not only the Discord connection.
- **FR-003**: The liveness command MUST acknowledge the invoking user within the platform's
  interaction-response window even when the underlying data-store check is slightly slow.
- **FR-004**: On startup, the application MUST connect to its data store and bring the schema
  fully up to date before serving any command.
- **FR-005**: If the application cannot connect to the data store or cannot bring the schema up
  to date, it MUST fail fast with a clear, actionable error and MUST NOT start in a
  partially-initialized state.
- **FR-006**: The bot's authentication credential and the data store's password MUST be supplied
  at runtime from the environment and MUST NOT be stored in the repository or any tracked
  configuration file.
- **FR-007**: A missing required secret MUST produce a clear startup error that identifies the
  missing value, and the bot MUST NOT come online.
- **FR-008**: Schema changes MUST be applied automatically on startup and treated as immutable
  history once applied; the foundation MUST support adding new schema changes over time without
  rewriting or altering past ones.
- **FR-009**: Restarting the application against an existing data store MUST succeed without any
  manual schema steps and MUST NOT duplicate or corrupt prior schema history.
- **FR-010**: While running with a healthy startup, the bot MUST appear online in the server.
- **FR-011**: A developer MUST be able to go from a clean checkout to a locally running, online
  bot using only documented setup steps, including starting the local data store the documented
  way.
- **FR-012**: Every proposed change MUST be automatically built and tested with a clear pass/fail
  result, and a change that fails to build or breaks the tests MUST be blocked from merge.
- **FR-013**: The project MUST be able to produce a single self-contained, runnable artifact, and
  launching the bot from that artifact MUST yield the same running, online behavior as the local
  run.

### Key Entities *(include if feature involves data)*

- **Schema Change History**: The immutable, ordered record of schema changes that have been
  applied to a data store. Used to determine, on each startup, which changes still need applying
  and to guarantee past changes are never rewritten. New changes are only ever appended.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From a clean checkout, a developer who has never run the project reaches a locally
  running, online bot using only the documented steps, with no undocumented manual intervention.
- **SC-002**: With the bot running in a test server, the liveness command returns a success
  response confirming data-store reachability within the interaction-response window (acknowledged
  within 3 seconds) in 100% of attempts under normal conditions.
- **SC-003**: Restarting the application against an existing data store succeeds with zero manual
  schema steps and zero instances of duplicated or corrupted schema history across repeated
  restarts.
- **SC-004**: Automated checks run on every pull request and report a clear pass/fail; a
  deliberately broken build or a failing test is reported as a failure and blocks merge in 100%
  of cases.
- **SC-005**: Launching from the single produced artifact yields a bot that appears online and
  answers the liveness command identically to the local run.
- **SC-006**: A startup with a missing required secret or an unreachable data store fails fast
  with a clear, actionable error, and the bot does not come online or serve any command.

## Out of Scope

The following are explicitly NOT part of this slice and belong to later slices:

- Any coin or economy behavior, balances, or transactions.
- The coin ledger.
- Cooldowns and the cooldown engine.
- The auction queue and any auction behavior.
- Moderator / admin tooling.
- Real business logic of any kind beyond the trivial liveness check.
- Production hosting and deployment automation — for now the bot runs from a local machine.

## Assumptions

- The interaction-response window is treated as 3 seconds for the purpose of SC-002; the
  acknowledgement must occur within it even if the full result arrives slightly later.
- A test Discord server and a bot application/token are available to maintainers for manual
  verification of the liveness scenarios.
- "Single self-contained artifact" means one runnable build output that carries everything the
  bot needs to run given valid runtime secrets and a reachable data store.
- The data store runs the same documented way for local development as it is expected to run when
  the bot is launched from the artifact (parity), supplied with runtime secrets from the
  environment.
- Concrete technology choices (frameworks, libraries, database engine, container tooling, build
  tool, and CI provider) are intentionally deferred to this slice's plan, not fixed here.
