# Discord Bot — Claude Code Project Memory

## Prerequisites (Docker must be running)
- Docker Desktop must be running before you run the app OR the tests.
- Postgres 17 runs in Docker for BOTH dev and prod (no native Postgres install).
  Dev and prod use the SAME image but SEPARATE compose configs, ports, and volumes —
  their data is never shared.
- Local dev database:
  - Start: `docker compose up -d`
  - Stop: `docker compose down`
  - Reset data (wipe volume): `docker compose down -v`
- The DB password and Discord token come from env vars (`DB_PASSWORD`, `DISCORD_TOKEN`);
  never hardcode them or write them into committed properties files.

## Docker strategy (read before adding any Dockerfile or compose service)
- ONE multi-stage Dockerfile at repo root: build stage runs Maven to produce the jar,
  runtime stage is a slim JRE image. The same image serves dev-container runs and prod.
- Environment differences (dev vs prod) come from Spring profiles + env vars + a prod
  compose override file — NOT from separate Dockerfiles per environment.
- Do NOT run the test suite inside a container. Tests use Testcontainers, which needs the
  host Docker daemon; running them in a container means Docker-in-Docker. Tests run via
  Maven on the host.

## Build / Test / Run
- Build the jar (no tests): `./mvnw -q -DskipTests package`
- Build the app image: `docker build -t coiny-bot .`
- Run all tests (unit + integration): `./mvnw -q verify`
  - Testcontainers starts its OWN throwaway Postgres for integration tests; it does NOT
    use the docker compose dev database. Docker just needs to be running on the host.
- Fast dev run (recommended inner loop — needs `docker compose up -d` first):
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- Containerized run (app + Postgres both in Docker, parity check): `docker compose up --build`
- Format: `./mvnw spotless:apply`

## Source Layout
- `bot.discord.command` — JDA slash-command handlers (thin)
- `bot.application` — @Transactional services, the only place that talks to repositories
- `bot.domain` — pure Java; entities, value objects, domain services
- `bot.infrastructure` — Spring Data repositories, Flyway, external clients

## Conventions
- Java 21, records for DTOs/value objects, no Lombok on domain entities
- Postgres 17 is the ONLY database engine, in every environment. Do not introduce H2,
  SQLite, or any in-memory substitute, and do not write DB-portable workarounds — code
  may rely on Postgres-specific behavior (sequences, ON CONFLICT, MVCC).
- The application is containerized via a single multi-stage Dockerfile; environments
  differ by Spring profile + env vars, never by forking the Dockerfile.
- All public application-service methods take a request record and return a result record
- Time: `java.time.Instant` for storage, `java.time.Duration` for cooldowns; never `long ms`
- IDs: `UUID` for externally-visible, `Long` for internal sequence-backed
- Error model: typed `DomainException`s with i18n keys; never throw `RuntimeException` directly

## What I Always Want You to Do
- Read `.specify/memory/constitution.md` before generating any plan or code
- Read the active `specs/NNN-*/spec.md` and `tasks.md` before editing files
- Run `./mvnw -q verify` after each task and surface failures verbatim
- Use `@./specs/<active>/data-model.md` mentions instead of re-reading the file

## What I Never Want You to Do
- Add a dependency without writing it into `plan.md` first
- Touch Flyway migrations that have already shipped — write a new V<n+1> file
- Generate JDA handlers that do work synchronously before `event.deferReply()`
- Introduce a second database engine or in-memory DB — Postgres 17 only
- Run tests inside a container, or let dev and prod share a Postgres volume

<!-- SPECKIT START -->
Active feature: **002-coin-ledger**. For technologies, project structure,
shell commands, and other important context, read the current plan and its design
artifacts:
- Plan: `specs/002-coin-ledger/plan.md`
- Research: `specs/002-coin-ledger/research.md`
- Data model: `specs/002-coin-ledger/data-model.md`
- Contracts: `specs/002-coin-ledger/contracts/`
- Quickstart: `specs/002-coin-ledger/quickstart.md`
<!-- SPECKIT END -->
