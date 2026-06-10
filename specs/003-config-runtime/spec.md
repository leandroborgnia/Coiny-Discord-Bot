# Feature Specification: Configuration Consolidation & Containerized Dev/Prod Runtime

**Feature Branch**: `003-config-runtime`

**Created**: 2026-06-10

**Status**: Draft

**Input**: User description: "Configuration consolidation & containerized dev/prod runtime — move all Spring configuration into application.properties, eliminate any reliance on a .env file/dotenv for the app, supply secrets as environment variables injected by Docker Compose, and provide separate dev/prod launch scripts (Linux shell + PowerShell/WSL wrappers) that prompt for secrets and bring up the app and its database together. Maven is for building and the host test suite only — not a way to run the app."

## Clarifications

### Session 2026-06-10

- Q: Should the repo keep a `.env` file, and how is configuration shared between Docker and Spring
  so there is a single source of truth? → A: Delete `.env` entirely. Non-secret connection values
  (database username, database name, host/port) are defined **once** in the Docker Compose
  configuration and injected as environment into **both** the database container and the
  application container; `application.properties` only *references* them via `${...}` placeholders
  (never a second, independently-edited copy). Secrets are prompted by the launch scripts and
  injected the same way. App-only settings (JPA, Flyway, message bundle, history limit, Discord
  toggle) live solely in `application.properties`.
- Q: How should Spring profiles be structured across dev and prod containers? → A: The base
  `application.properties` holds production-safe defaults and IS the production configuration — there
  is no separate `application-prod.properties` and the prod container activates no extra profile.
  `dev` is the only profile overlay (`application-dev.properties`), adding developer conveniences
  (e.g. `show-sql`, verbose logging) on top of the base; the dev container sets
  `SPRING_PROFILES_ACTIVE=dev`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Single config surface, app runs in a container (Priority: P1)

A developer (or operator) needs the application to read all of its configuration from one place
and to start as a container that gets its secrets from the environment — with no `.env` file and
no dotenv mechanism involved. The application's configuration lives entirely in
`application.properties` (plus profile-specific variants), and secrets appear there only as
`${...}` placeholders resolved from environment variables at run time.

**Why this priority**: This is the foundation the launch tooling builds on. Until configuration is
consolidated and resolves secrets purely from the environment, neither the dev nor the prod
one-command startup can work. It is independently valuable: it removes the fragile host-run env
gap and aligns the repo with constitution v1.1.1.

**Independent Test**: Start the app as a container with the required environment variables present
and confirm it boots and connects to its database; confirm the host test suite (`./mvnw verify`)
passes with **no** secrets set; confirm no `application.yml` remains and no `.env`/dotenv is read.

**Acceptance Scenarios**:

1. **Given** the required secrets are present as environment variables, **When** the application
   container starts, **Then** it boots, applies migrations, and connects to its database with no
   `.env` file present.
2. **Given** a checkout with no secrets configured on the host, **When** `./mvnw verify` runs,
   **Then** the full test suite passes (tests require no secrets).
3. **Given** the repository, **When** its configuration files are inspected, **Then** all Spring
   configuration is in `application.properties`/`application-{profile}.properties`, no
   `application.yml` exists, and secrets appear only as `${...}` placeholders.

---

### User Story 2 - One-command local startup with a forgotten-password escape hatch (Priority: P2)

A developer wants to bring the whole stack (application + database) up locally with a single
command. The launch tooling prompts for the required secrets each run (the token entered hidden),
and — because a local database password is only set when its data volume is first created — it
first asks whether to reset the database, so a developer who has forgotten the local password can
recover without typing any Docker commands.

**Why this priority**: This is the primary day-to-day developer experience and the most visible
value of the feature. It depends on User Story 1.

**Independent Test**: On a fresh clone, run the dev launch command, decline the reset, enter
secrets, and confirm the app + database come up together; then on a machine where the local
password is unknown, run it again, accept the reset, enter a new password, and confirm a clean
start.

**Acceptance Scenarios**:

1. **Given** a fresh local environment, **When** the developer runs the dev launch command and
   answers "no" to reset, **Then** they are prompted for the database password and Discord token
   (token hidden) and the app + database start together.
2. **Given** a local database whose password the developer no longer knows, **When** they run the
   dev launch command and answer "yes" to reset, **Then** the local database volume is wiped and
   re-initialized with the newly entered password and the stack starts cleanly.
3. **Given** the dev launch command on Windows, **When** the developer runs the PowerShell wrapper,
   **Then** it executes the same shell flow through WSL with the same prompts and result.
4. **Given** an existing local database, **When** the developer answers "no" to reset but enters a
   password that does not match the existing volume, **Then** startup fails fast with a clear
   database authentication error and nothing is left half-started.

---

### User Story 3 - One-command production startup (Priority: P3)

An operator wants to bring the production stack (application + database) up with a single command
that prompts for the required secrets each run and never offers to wipe data.

**Why this priority**: Production parity matters but is exercised far less often than the dev loop;
it reuses the same image and patterns as User Stories 1–2.

**Independent Test**: Run the prod launch command, enter secrets, and confirm the app + database
start together using the production port and volume; confirm there is no reset/wipe prompt.

**Acceptance Scenarios**:

1. **Given** a production host, **When** the operator runs the prod launch command, **Then** they
   are prompted for the database password and Discord token (token hidden) and the app + database
   start together with no reset option offered.
2. **Given** both dev and prod are run on the same machine, **When** each is started, **Then**
   they use separate ports and separate data volumes and do not collide.

---

### Edge Cases

- **Forgotten local DB password**: recoverable via the dev reset prompt (US2-2); no manual Docker
  commands required.
- **Password mismatch without reset**: startup fails fast with a clear DB auth error and no
  partially-started state (US2-4).
- **WSL/Docker not ready on Windows**: the PowerShell wrapper surfaces a clear error when WSL is
  unavailable or the Docker daemon is not reachable, rather than hanging or failing cryptically.
- **Blank secret entered**: the script rejects an empty database password or token and re-prompts
  rather than starting with an invalid value.
- **Shell-script line endings**: the Linux scripts must run correctly under WSL (no CRLF breakage).
- **Secret leakage**: the token is entered hidden; no secret is echoed, written to a file, or
  committed.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: All Spring application configuration MUST reside in `application.properties` and any
  profile-specific `application-{profile}.properties`; the existing `application.yml` files (main,
  `dev`, and test) MUST be removed. The base `application.properties` MUST hold production-safe
  defaults and serve as the production configuration directly (no `application-prod.properties`,
  and the prod container activates no extra profile). `dev` MUST be the only profile overlay
  (`application-dev.properties`), adding developer conveniences on top of the base, with the dev
  container setting `SPRING_PROFILES_ACTIVE=dev`.
- **FR-002**: The application MUST NOT depend on any `.env` file or dotenv mechanism to load its
  configuration.
- **FR-003**: Secrets (database password, Discord token) MUST appear in configuration only as
  `${...}` placeholders and MUST be supplied at run time as environment variables injected by
  Docker Compose.
- **FR-004**: The tooling MUST NOT commit any secret to the repository or cache any secret to an
  on-disk file.
- **FR-005**: The application MUST run as a container via Docker Compose in BOTH dev and prod; a dev
  application service MUST be added (production already has one). Dev and prod MUST use separate
  ports and separate data volumes.
- **FR-006**: A dev launch script MUST, on every run, prompt for both the database password and the
  Discord token (token entered hidden); BEFORE prompting for the password it MUST ask whether to
  reset the database. "Yes" MUST wipe the dev database volume and initialize it fresh with the
  entered password; "no" MUST use the entered (existing) password. There MUST be no automatic wipe.
- **FR-007**: A prod launch script MUST, on every run, prompt for both secrets (token hidden) and
  bring the stack up; it MUST NOT offer any reset/wipe option.
- **FR-008**: Each launch script MUST have a PowerShell wrapper that runs the corresponding shell
  script through WSL, presenting the same prompts and behavior.
- **FR-009**: Each launch script MUST bring up the application AND its database together.
- **FR-010**: Host Maven MUST remain available for the image build (inside the Dockerfile) and the
  test suite only; running the app via the Spring Boot Maven plugin is NOT the supported run path,
  and project run documentation (e.g., CLAUDE.md) MUST reflect Docker-Compose-via-scripts as the
  intended way to run the app.
- **FR-011**: `./mvnw verify` MUST pass on the host with no secrets set (the test suite requires no
  secrets).
- **FR-012**: This feature MUST be behavior-preserving: the application's product features behave
  identically before and after the configuration move.
- **FR-013**: A developer who has forgotten the local database password MUST be able to recover and
  start cleanly via the reset prompt, without issuing any Docker command directly.
- **FR-014**: When a developer declines the reset but enters a database password that does not match
  the existing volume, startup MUST fail fast with a clear database authentication error and MUST
  NOT leave the application partially started.
- **FR-015**: The repository MUST NOT contain a `.env` file. Each non-secret connection value
  (database username, database name, host/port) MUST have a single source of truth in the Docker
  Compose configuration and be supplied from there to BOTH the database container and the
  application container; the application's configuration MUST only *reference* these values (via
  `${...}` placeholders or fixed profile defaults) and MUST NOT keep a second, independently-edited
  copy. No configuration value may be duplicated across Docker and Spring in a way that lets the two
  drift.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer with a fresh clone can bring up the full application + database locally
  with a single command after entering the prompted secrets — no manual Docker or environment-setup
  steps required.
- **SC-002**: No secret value exists anywhere in the repository or in any on-disk file written by
  the tooling (verifiable by inspection).
- **SC-003**: The test suite passes on the host with zero secrets configured.
- **SC-004**: A developer who has forgotten the local database password can recover and start
  cleanly through the reset prompt without typing any Docker command.
- **SC-005**: 100% of the application's Spring configuration resides in `application.properties`
  files; no `application.yml` and no `.env` is required for the application to run.
- **SC-006**: Product behavior is unchanged — the existing coin/ledger commands work exactly as
  before the change.
- **SC-007**: Dev and prod can run on the same machine simultaneously without port or data-volume
  collisions.
- **SC-008**: No configuration value is maintained in two independently-editable places (Docker vs
  Spring) — each value has exactly one source, verifiable by inspection, so Docker and Spring cannot
  drift out of agreement.

## Assumptions

- Docker is installed and running in every target environment (Docker Desktop with WSL integration
  on Windows; Docker Engine on Linux).
- Windows users have WSL available with a bash-capable distribution; the Linux shell scripts use
  LF line endings so they run correctly under WSL.
- The local/dev database is disposable: wiping it to recover from a forgotten password is
  acceptable.
- A given environment's database password is consistent across runs (the operator remembers it, or
  the developer resets in dev) — the database only sets its password when its data volume is first
  created.
- Maven remains available on the host for the image build and the test suite (constitution
  Principle VI); the test suite needs no secrets.
- The production compose configuration already defines an application service; the dev compose
  configuration currently defines only the database and gains an application service in this work.
- The prompt-and-reset logic is authored once in the shell scripts; the PowerShell wrappers are
  thin and invoke the shell scripts via WSL (so prompted secrets do not need to cross the
  PowerShell→WSL boundary as variables).

## Dependencies

- Builds on the existing single multi-stage Dockerfile and the existing dev/prod compose
  configurations.
- Implements the runtime topology and configuration mandate introduced in constitution v1.1.1
  (Principle VII and the Containerization & Environment Topology section).

## Out of Scope

- Any change to coin/ledger behavior or any other product feature.
- CI/CD pipeline changes.
- A secret manager / vault integration (secrets are prompted at run time, not stored).
