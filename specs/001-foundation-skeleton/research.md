# Phase 0 Research: Foundation Skeleton

The technology space for this slice is fixed by the project constitution and CLAUDE.md, so there
are no open `NEEDS CLARIFICATION` items. This document records the decisions that turn those
constraints into concrete, buildable choices, with rationale and rejected alternatives.

## Decision 1 — Discord library: JDA 5 as a Spring-managed bean

- **Decision**: Use JDA 5 (`net.dv8tion:JDA:5.0.x`). Build a single `JDA` instance as a Spring
  `@Bean` in `bot.infrastructure.discord.JdaConfig`, reading `DISCORD_TOKEN` from the environment.
  Register event listeners (the interaction router) as the JDA is built, and `await`/log readiness.
- **Rationale**: Constitution and CLAUDE.md mandate JDA 5. Managing the gateway connection as a
  Spring bean lets the app context own its lifecycle (graceful shutdown) and lets handlers be
  ordinary beans injected into the router, keeping `bot.discord.command` free of wiring concerns.
- **Alternatives considered**: Discord4J (reactive) — rejected, not the mandated library and adds a
  reactive model the rest of the stack does not use. Running JDA outside Spring — rejected, loses
  dependency injection and coordinated shutdown.

## Decision 2 — Slash-command registration: guild commands against a test guild

- **Decision**: On JDA ready, upsert the `/ping` command. For fast iteration register it as a
  **guild** command against a configured `DISCORD_GUILD_ID` (instant availability) rather than a
  global command (up to ~1h propagation). `SlashCommandRegistrar` upserts the set of
  `SlashCommandHandler` beans' command data.
- **Rationale**: The spec's verification uses a single test server; guild commands appear
  immediately, making manual verification of the liveness scenario reliable and fast.
- **Alternatives considered**: Global commands — rejected for the foundation due to propagation
  delay; can be revisited when the bot ships to many servers.

## Decision 3 — Thin handler pattern: defer-first, delegate, edit reply

- **Decision**: `PingCommand.handle(event)` calls `event.deferReply()` immediately (before any
  business work), then delegates to `LivenessService.check(...)`, then edits the deferred reply with
  the result via `event.getHook().editOriginal(...)`. The handler contains no business logic and
  opens no transaction.
- **Rationale**: Constitution Principle V requires deferral within 2.5s and handlers free of
  business logic; deferring first guarantees the 3s interaction window is met even if the DB read is
  slightly slow (spec FR-003, edge case "data store slow").
- **Alternatives considered**: Replying directly without deferral — rejected, risks blowing the 3s
  window and violates the principle. Doing the DB read in the handler — rejected, puts logic and a
  transaction in the adapter layer.

## Decision 4 — Liveness persistence touch: read a seeded `health_check` row

- **Decision**: Flyway `V1` creates a small `health_check` table and seeds exactly one row. The
  liveness path reads that row through `LivenessProbePort` → `JpaLivenessProbeAdapter`. A successful
  read proves both that migrations ran and that the database is reachable *now*.
- **Rationale**: Gives an honest end-to-end persistence touch (spec FR-002) without inventing any
  business data, and stays within scope (no ledger/economy). Reading a seeded row requires a live
  connection, so it is a genuine reachability check.
- **Alternatives considered**: `SELECT 1` health query — rejected as it does not exercise the
  JPA/entity mapping the later slices will rely on. Writing a heartbeat row per ping — rejected as
  needless write traffic and noise.

## Decision 5 — Schema management: Flyway, auto-migrate on startup, fail-fast

- **Decision**: Flyway runs automatically at startup (Spring Boot auto-configuration) and must
  bring the schema fully up to date before the app serves commands. Use `flyway-core` plus
  `flyway-database-postgresql`. Migrations live in `src/main/resources/db/migration` as
  `V<n>__desc.sql` and are never edited once applied.
- **Rationale**: Constitution Principle VII and spec FR-004/FR-008/FR-009. Spring Boot fails
  startup if migration fails, satisfying the fail-fast requirement and avoiding partial init.
- **Alternatives considered**: Hibernate `ddl-auto` — rejected outright (mutable, non-auditable,
  forbidden by the constitution's immutable-history rule). Liquibase — rejected, CLAUDE.md mandates
  Flyway.

## Decision 6 — Fail-fast startup ordering (DB/schema before gateway)

- **Decision**: Ensure the DataSource connects and Flyway completes before the bot announces
  itself online. Rely on Spring's startup ordering (DataSource + Flyway are part of context
  refresh); the JDA gateway connection is established during/after context refresh, so a failed
  migration aborts startup before commands are served. A missing required secret (`DISCORD_TOKEN`,
  `DB_PASSWORD`) is validated at startup and aborts with a clear message.
- **Rationale**: Spec FR-005/FR-007 and edge cases require no partially-initialized state and clear,
  actionable errors. Spring Boot's ordered context refresh provides this without custom plumbing.
- **Alternatives considered**: Lazy DB connection / connecting after gateway online — rejected,
  could serve `/ping` before the schema is ready and would surface DB errors at command time
  instead of startup.

## Decision 7 — Secret & config strategy: env vars + Spring profiles

- **Decision**: `DISCORD_TOKEN`, `DISCORD_GUILD_ID`, `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` come from
  environment variables, referenced in `application.yml` via `${ENV}` placeholders with no committed
  defaults for secrets. Dev vs prod differ only by Spring profile (`dev`) + env values + a compose
  override. `.env.example` documents the variables; the real `.env` is git-ignored.
- **Rationale**: Constitution Principle VII and Containerization section; spec FR-006. Profiles +
  env vars keep one Dockerfile and one codebase across environments.
- **Alternatives considered**: Committed `application-prod.yml` with secrets — rejected, leaks
  secrets into git history. Per-environment Dockerfiles — rejected, violates the constitution.

## Decision 8 — Testing strategy: Testcontainers Postgres on the host

- **Decision**: A shared `AbstractPostgresIntegrationTest` base starts a Testcontainers
  `postgres:17` container and wires Spring's DataSource to it (e.g. `@DynamicPropertySource` or
  JDBC URL container). Flyway runs against it so integration tests exercise the real schema.
  Unit tests (e.g. `LivenessService`) mock `LivenessProbePort` with Mockito and assert with AssertJ.
  Tests run via `./mvnw verify` on the host; they never run inside the app container.
- **Rationale**: Constitution Principle VI; spec SC-002/SC-003. Real Postgres validates
  Postgres-specific behavior and migration idempotency across restarts.
- **Alternatives considered**: H2/in-memory for speed — forbidden by the constitution.
  Sharing the docker-compose dev database — rejected, tests must use a throwaway instance to stay
  isolated and repeatable.

## Decision 9 — Packaging & environments: single multi-stage Docker image

- **Decision**: One `Dockerfile` at repo root: a build stage runs Maven (`./mvnw -q -DskipTests
  package`) to produce the Spring Boot jar; a runtime stage on a slim JRE copies and runs the jar.
  `compose.yaml` provides dev Postgres only (app runs on host for fast iteration via
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`). `compose.prod.yaml` provides parity
  (app + Postgres) with separate ports and a separate named volume.
- **Rationale**: Constitution Containerization section; spec FR-013/SC-005. The same image serves
  dev-container parity checks and prod; environments differ only by profile/env/override.
- **Alternatives considered**: Jib / buildpacks — viable but a plain multi-stage Dockerfile is the
  explicit constitutional choice and keeps the build transparent. Separate dev/prod Dockerfiles —
  forbidden.

## Decision 10 — CI provider: GitHub Actions

- **Decision**: A GitHub Actions workflow runs on every pull request: checkout, set up JDK 21, run
  `./mvnw -q verify`. The runner's host Docker daemon backs Testcontainers (no Docker-in-Docker).
  A failing build or test fails the check; branch protection requires the check to pass before merge.
- **Rationale**: Spec FR-012/SC-004. GitHub Actions runners include a usable Docker daemon, which
  Testcontainers needs, and integrate with PR status checks and branch protection. The spec left CI
  provider to the plan; this is that decision.
- **Alternatives considered**: Other CI providers — equivalent in capability; GitHub Actions chosen
  for native PR integration assuming the repo is hosted on GitHub. Revisit if hosting differs.
