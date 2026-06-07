---
description: "Task list for Foundation Skeleton implementation"
---

# Tasks: Foundation Skeleton

**Input**: Design documents from `/specs/001-foundation-skeleton/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: INCLUDED. The project constitution (Principle VI — Real-Postgres Testing Discipline)
and the plan require JUnit 5 / Mockito / AssertJ / Testcontainers tests, and CI (`./mvnw verify`)
gates every change. Test tasks are therefore first-class, not optional, for this feature.

**Organization**: Tasks are grouped by user story. The runtime platform that every story depends
on lives in Setup + Foundational; each story phase is an independently testable increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US4, mapping to the spec's prioritized user stories
- All paths are repository-relative; base Java package is `bot` (per CLAUDE.md)

## Path Conventions

- Main: `src/main/java/bot/...`, resources: `src/main/resources/...`
- Tests: `src/test/java/bot/...`
- Repo root: `pom.xml`, `Dockerfile`, `compose.yaml`, `compose.prod.yaml`, `.env.example`,
  `.github/workflows/ci.yml`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and build tooling

- [X] T001 Create the Maven project at `pom.xml` (+ `mvnw`/`mvnw.cmd`/`.mvn/`): Java 21, Spring Boot
  3.3.x parent, dependencies `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`,
  `net.dv8tion:JDA:5.0.x`, `flyway-core`, `flyway-database-postgresql`, `org.postgresql:postgresql`;
  test dependencies `spring-boot-starter-test` (JUnit 5 + Mockito + AssertJ),
  `org.testcontainers:postgresql` + `org.testcontainers:junit-jupiter`
- [X] T002 [P] Create the hexagonal package skeleton under `src/main/java/bot/`: `domain/liveness`,
  `application/liveness`, `discord/command`, `infrastructure/discord`, `infrastructure/persistence`
  (empty `package-info.java` placeholders are acceptable)
- [X] T003 [P] Configure the Spotless Maven plugin (google-java-format) in `pom.xml` so
  `./mvnw spotless:check` runs in `verify`
- [X] T004 [P] Create `.gitignore` (ignore `target/`, `.env`, IDE files) and `.env.example`
  documenting `DISCORD_TOKEN`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SPRING_PROFILES_ACTIVE`
  per `contracts/configuration.md`
- [X] T005 [P] Create the Spring Boot entry point `src/main/java/bot/CoinyBotApplication.java`
  (`@SpringBootApplication`, base package `bot`)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The runtime platform every user story builds on — data store, config, schema, Discord
gateway, command routing, and the test harness.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T006 Create `compose.yaml` for dev: a single `postgres:17` service with a dev port and a
  **dev-only** named volume, password from `DB_PASSWORD` (app runs on the host)
- [X] T007 Create `src/main/resources/application.yml`: DataSource from `${DB_URL}`/`${DB_USERNAME}`/
  `${DB_PASSWORD}` (no committed secret defaults), Hibernate `ddl-auto: validate`, Flyway enabled,
  fail-fast on missing required config — per `contracts/configuration.md`
- [X] T008 [P] Create `src/main/resources/application-dev.yml` for the `dev` profile (host →
  dockerized Postgres from `compose.yaml`, SQL logging as desired)
- [X] T009 [P] Create the immutable Flyway migration
  `src/main/resources/db/migration/V1__init_liveness.sql`: create `health_check (id smallint PK,
  label text not null, created_at timestamptz not null default now())` and seed
  `(1, 'ok') ON CONFLICT (id) DO NOTHING` — per `data-model.md`
- [X] T010 [P] Create `src/main/java/bot/infrastructure/discord/JdaConfig.java`: build the `JDA`
  bean from `DISCORD_TOKEN` (fail fast with a clear error if missing), enable required intents,
  register the interaction router as an event listener
- [X] T011 Create the handler SPI `src/main/java/bot/discord/command/SlashCommandHandler.java`
  (command name + `CommandData` + `handle(SlashCommandInteractionEvent)`)
- [X] T012 Create `src/main/java/bot/infrastructure/discord/InteractionRouter.java` (JDA listener)
  that dispatches each `SlashCommandInteractionEvent` to the matching `SlashCommandHandler` bean
  (depends on T011)
- [X] T013 Create `src/main/java/bot/infrastructure/discord/SlashCommandRegistrar.java` that, on
  `ReadyEvent`, upserts all `SlashCommandHandler` beans' command data to **every** guild the bot is
  in (`jda.getGuilds()`), and on `GuildJoinEvent` registers to a newly-joined guild (idempotent
  upsert; JDA receives guild/join events by default, no extra intent needed). No single configured
  guild id — the bot is multi-server and each interaction is handled in its originating server
  (depends on T011)
- [X] T014 [P] Create `src/test/java/bot/support/AbstractPostgresIntegrationTest.java`: a
  Testcontainers `postgres:17` base wiring Spring's DataSource (e.g. `@DynamicPropertySource`) so
  integration tests run against a real throwaway Postgres on the host
- [X] T015 Create `src/test/java/bot/StartupIntegrationTest.java` (startup + restart-safety): boots
  the context against the Testcontainers Postgres and asserts (a) Flyway applied `V1` and the seeded
  `health_check` row exists; and (b) **restart idempotency** — re-invoking `Flyway.migrate()` applies
  **0** new migrations, `flyway.info()` shows `V1` = Success with **0 pending**, and
  `count(health_check) == 1` (validates fail-fast startup + migration + restart safety; spec
  FR-004/FR-008/FR-009, SC-003) (depends on T007, T009, T014)

**Checkpoint**: Application starts, connects to Postgres, migrates, connects the gateway, and can
route/register commands. User stories can now proceed.

---

## Phase 3: User Story 1 — Confirm the bot is alive end to end (Priority: P1) 🎯 MVP

**Goal**: A `/ping` command that defers fast, reads the seeded row through the domain port, and
replies with a success message proving the full path works.

**Independent Test**: With the bot running and in a test server, invoke `/ping` and observe a
success response confirming data-store reachability within the interaction-response window.

### Tests for User Story 1 ⚠️ (write first, ensure they fail before implementation)

- [X] T016 [P] [US1] Unit test `src/test/java/bot/application/liveness/LivenessServiceTest.java`:
  mock `LivenessProbePort` with Mockito, assert (AssertJ) that `check(...)` maps `up`/`down`
  `LivenessStatus` to the correct `CheckLivenessResult`
- [X] T017 [P] [US1] Integration test
  `src/test/java/bot/infrastructure/persistence/JpaLivenessProbeAdapterTest.java` (extends the
  Testcontainers base): asserts the adapter reads the seeded row and returns `LivenessStatus.up("ok")`

### Implementation for User Story 1

- [X] T018 [P] [US1] Create the domain value object
  `src/main/java/bot/domain/liveness/LivenessStatus.java` (record: `boolean reachable`,
  `String detail`; factories `up`/`down`) — pure Java, no framework imports
- [X] T019 [P] [US1] Create the domain port
  `src/main/java/bot/domain/liveness/LivenessProbePort.java` (`LivenessStatus probe()`) — pure Java
- [X] T020 [P] [US1] Create the JPA entity
  `src/main/java/bot/infrastructure/persistence/HealthCheckEntity.java` mapping table `health_check`
  (`id`, `label`, `created_at` as `Instant`)
- [X] T021 [US1] Create the Spring Data repository
  `src/main/java/bot/infrastructure/persistence/HealthCheckJpaRepository.java` (depends on T020)
- [X] T022 [US1] Create the **non-transactional** adapter
  `src/main/java/bot/infrastructure/persistence/JpaLivenessProbeAdapter.java` implementing
  `LivenessProbePort` (no `@Transactional`). Inject the `DataSource` and `HealthCheckJpaRepository`.
  In `probe()`: first verify connectivity with `try (Connection c = dataSource.getConnection()) {…}`
  and **catch `SQLException` → `LivenessStatus.down(...)`**; on a healthy connection read the
  singleton row via the repository → `LivenessStatus.up(label)`, defensively mapping a missing row
  or `DataAccessException` to `down(...)`. Never throws for a down/unreachable store, so no
  transaction or `SQLException` can leak to `PingCommand` (resolves analyze finding U1)
  (depends on T019, T021)
- [X] T023 [P] [US1] Create request/result records
  `src/main/java/bot/application/liveness/CheckLivenessRequest.java` and
  `CheckLivenessResult.java` (`boolean reachable`, `String detail`) per `contracts/application-services.md`
- [X] T024 [US1] Create the application service
  `src/main/java/bot/application/liveness/LivenessService.java` (`@Service`, **NOT** `@Transactional`):
  depends on `LivenessProbePort`; pure delegation + mapping of `LivenessStatus` →
  `CheckLivenessResult`. It must not be transactional — a transactional proxy would acquire the
  connection before the method body and a down database would throw before mapping (analyze finding
  U1); connectivity/error handling lives in the adapter (T022) (depends on T019, T023)
- [X] T025 [US1] Create the thin handler `src/main/java/bot/discord/command/PingCommand.java`
  implementing `SlashCommandHandler`: `event.deferReply()` first, delegate to `LivenessService`,
  then `getHook().editOriginal(...)` rendering success/non-success from the always-present
  `CheckLivenessResult` (`reachable=false` yields the non-success reply — no exception path) — no
  business logic (depends on T011, T024) per `contracts/slash-commands.md`

**Checkpoint**: `/ping` works end to end; T016/T017 pass; manual verification per quickstart §4.

---

## Phase 4: User Story 2 — Reproducible local setup (Priority: P2)

**Goal**: A developer goes from clean checkout to a running, online bot using documented steps only.

**Independent Test**: On a clean checkout, follow only the README steps and reach a running bot that
answers `/ping`.

- [X] T026 [US2] Create repo-root `README.md` with a "Local setup" section: prerequisites (Docker,
  JDK 21), `Copy-Item .env.example .env` + fill secrets, `docker compose up -d`,
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, invite bot, run `/ping` — mirroring
  `quickstart.md` §1–§4
- [X] T027 [US2] Cross-check `.env.example` against `contracts/configuration.md` and document the
  reset/stop commands (`docker compose down`, `docker compose down -v`) in the README

**Checkpoint**: A second machine reaches a running bot from the README alone (spec SC-001).

---

## Phase 5: User Story 3 — Automated checks gate every change (Priority: P3)

**Goal**: Every pull request is built and tested automatically; a broken build or failing test is
reported and blocks merge.

**Independent Test**: Open a PR with a deliberately failing test → CI reports failure and merge is
blocked; a healthy PR → CI passes.

- [X] T028 [US3] Create `.github/workflows/ci.yml`: on `pull_request`, checkout, set up JDK 21,
  run `./mvnw -q verify` (host Docker backs Testcontainers; no Docker-in-Docker)
- [X] T029 [US3] Document in `README.md` the required-status-check / branch-protection expectation
  (CI must pass before merge) per spec FR-012/SC-004

**Checkpoint**: PR checks report clear pass/fail and gate merges (spec SC-004).

---

## Phase 6: User Story 4 — Single self-contained artifact (Priority: P4)

**Goal**: Produce one runnable artifact/image that runs the same bot wherever launched.

**Independent Test**: Build the image, launch via `compose.prod.yaml`, and confirm the bot is online
and answers `/ping` identically to the host run.

- [X] T030 [US4] Create the single multi-stage `Dockerfile`: build stage runs
  `./mvnw -q -DskipTests package`; runtime stage on a slim JRE copies and runs the Spring Boot jar
- [X] T031 [P] [US4] Create `.dockerignore` (exclude `target/` build noise, `.git`, `.env`)
- [X] T032 [US4] Create `compose.prod.yaml`: app (built from the Dockerfile) + `postgres:17` with
  **separate** ports and a **separate** named volume from `compose.yaml` (never shared), all values
  from env

**Checkpoint**: `docker compose -f compose.prod.yaml up --build` yields an online bot answering
`/ping` identically to the local run (spec SC-005).

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T033 [P] Run `./mvnw spotless:apply` and commit formatting
- [X] T034 [P] Verify fail-fast error messages are clear and secret-free for missing `DISCORD_TOKEN`/
  `DB_PASSWORD` and unreachable Postgres (spec FR-005/FR-007, SC-006)
- [ ] T035 Run the full `quickstart.md` validation checklist end to end (SC-001..SC-006) and fix any
  gaps

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — BLOCKS all user stories
- **US1 (Phase 3)**: depends on Foundational — the MVP
- **US2 (Phase 4)**: depends on Foundational + a runnable bot (US1) to document/verify
- **US3 (Phase 5)**: depends on a passing test suite existing (Setup/Foundational + US1 tests)
- **US4 (Phase 6)**: depends on Foundational + US1 (needs the runnable app to package)
- **Polish (Phase 7)**: depends on all desired stories being complete

### User Story Dependencies

- **US1 (P1)**: independent once Foundational is done — delivers the MVP
- **US2 (P2)**: documents/validates the US1 run; no code dependency beyond a runnable app
- **US3 (P3)**: CI runs whatever tests exist; most valuable after US1 tests land
- **US4 (P4)**: packages the US1 app; independent of US2/US3

### Within User Story 1

- Tests (T016, T017) written first and expected to fail → then implementation
- Domain (T018, T019) before adapter (T020–T022) and service (T024)
- Service before handler (T025)

### Parallel Opportunities

- Setup: T002, T003, T004, T005 in parallel after T001
- Foundational: T008, T009, T010, T014 in parallel; T011 before T012/T013
- US1: T016/T017 (tests) in parallel; T018/T019/T020/T023 in parallel; then T021→T022, T024→T025
- US4: T031 in parallel with T030
- Polish: T033, T034 in parallel

---

## Parallel Example: User Story 1

```text
# Tests first (different files, no deps):
Task: T016 LivenessServiceTest (unit, mocked port)
Task: T017 JpaLivenessProbeAdapterTest (integration, Testcontainers)

# Then the independent building blocks together:
Task: T018 LivenessStatus (domain record)
Task: T019 LivenessProbePort (domain port)
Task: T020 HealthCheckEntity (JPA entity)
Task: T023 CheckLivenessRequest/Result (application records)
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 → **STOP & VALIDATE** `/ping`
   end to end per quickstart §4 → demo. This is the calibration slice's proof of life.

### Incremental Delivery

1. Setup + Foundational → platform ready
2. US1 → `/ping` works → MVP demo (SC-002)
3. US2 → reproducible setup documented/verified (SC-001)
4. US3 → CI gates merges (SC-004)
5. US4 → single artifact parity (SC-005)
6. Polish → fail-fast/secret hygiene + full quickstart validation (SC-003, SC-006)

---

## Notes

- [P] = different files, no dependency on an incomplete task
- [Story] labels map tasks to spec user stories for traceability
- Constitution gates to respect while implementing: domain stays framework-free; handler defers
  before any work; transactions (when a slice needs them) are opened only by `bot.application`, never
  by handlers or adapters — this liveness slice needs none; Flyway `V1` is immutable; secrets only
  from env; tests run on the host against Testcontainers Postgres — never inside the app container
- Run `./mvnw -q verify` after each task and surface failures verbatim (per CLAUDE.md)
- Commit after each task or logical group
