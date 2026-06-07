# Implementation Plan: Foundation Skeleton

**Branch**: `001-foundation-skeleton` | **Date**: 2026-06-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-foundation-skeleton/spec.md`

## Summary

Stand up the end-to-end working skeleton for the Coiny Discord bot: a single trivial
liveness slash command (`/ping`) that proves the whole foundation is wired — interaction
received, data store reached, reply returned — plus the surrounding machinery that makes the
foundation reproducible and shippable (fail-fast startup with automatic Flyway migration,
env-sourced secrets, Testcontainers-backed CI on every pull request, and a single runnable
artifact). No coin economy, ledger, cooldown, auction, or moderation behavior is included.

Technical approach: a layered/hexagonal Spring Boot 3 application on Java 21, using JDA 5 for
Discord, Spring Data JPA / Hibernate over Postgres 17 (the only engine), Flyway for schema. The
`/ping` handler defers within Discord's window and delegates to an application service, which
reads a seeded row through a domain port implemented by an infrastructure JPA adapter — touching
the real database to prove reachability. Packaged as one multi-stage Docker image; dev Postgres
runs via docker compose.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.3.x (Data JPA, Validation, Actuator-optional), JDA 5.0.x
(`net.dv8tion:JDA`), Hibernate ORM (via Spring Data JPA), Flyway (`flyway-core` +
`flyway-database-postgresql`), PostgreSQL JDBC driver

**Storage**: PostgreSQL 17 — the ONLY database engine in every environment (no H2/SQLite/in-memory).
Schema owned by Flyway. Dev Postgres runs in Docker via docker compose.

**Testing**: JUnit 5 (Jupiter), Mockito, AssertJ, Testcontainers (`postgresql`) against a real
throwaway Postgres 17. Tests run via Maven on the host (no Docker-in-Docker, never inside the app
container).

**Target Platform**: JVM (Linux container in production; local host for development). Runs as a
long-lived process connected to the Discord gateway.

**Project Type**: Single backend service (Discord bot) — layered/hexagonal, single Maven module.

**Performance Goals**: `/ping` interaction acknowledged (deferred) within 2.5s to satisfy
Discord's 3s interaction-response window even if the data-store check is slightly slow.

**Constraints**: Domain layer (`bot.domain`) imports no Spring/JDA/Jakarta. Handlers contain no
business logic and defer before doing work. Secrets (`DISCORD_TOKEN`, `DB_PASSWORD`) come only
from environment variables. Applied Flyway migrations are immutable. Single multi-stage Dockerfile;
dev and prod differ only by Spring profile + env vars + compose override, never separate
Dockerfiles; dev and prod Postgres never share a volume.

**Scale/Scope**: Foundation slice only — one command, one seeded table, one migration. Single test
guild for verification. Throughput is irrelevant at this stage.

**Build & Tooling**: Maven via `./mvnw` wrapper; Spotless (google-java-format) for formatting.
CI provider: GitHub Actions (runs `./mvnw -q verify` with host Docker for Testcontainers on every
PR). Artifact: executable Spring Boot jar, also packaged into the multi-stage Docker image.

**Base package**: `bot` (per CLAUDE.md), with `bot.discord.command`, `bot.application`,
`bot.domain`, `bot.infrastructure`. Maven coordinates: `com.coiny:coiny-bot`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Gate | Status |
|---|-----------|------|--------|
| I | Postgres-Only Persistence | Only Postgres 17 used; Testcontainers runs real Postgres; no H2/in-memory; Postgres-specific behavior allowed | PASS |
| II | Hexagonal Architecture & Domain Purity | `bot.domain` is pure Java; application owns transactions; infrastructure holds JPA/JDA adapters; handlers depend inward | PASS |
| III | Append-Only, Double-Entry Coin Ledger | No ledger in this slice | N/A |
| IV | Atomic Cooldown Engine | No cooldown in this slice | N/A |
| V | Thin, Fast Discord Handlers | `PingCommand` defers <2.5s, no business logic, delegates to `LivenessService` | PASS |
| VI | Real-Postgres Testing Discipline | JUnit 5 + Mockito + AssertJ + Testcontainers Postgres on host; not in app container | PASS |
| VII | Immutable Migrations & Secret Hygiene | Single `V1` Flyway migration, never edited; secrets only via env vars, `.env` git-ignored | PASS |
| — | Containerization & Environment Topology | One multi-stage Dockerfile; dev/prod via profile + env + compose override; separate Postgres volumes | PASS |

**Result**: All gates pass. No deviations to justify; Complexity Tracking left empty.
Re-checked after Phase 1 design — design introduces no new violations (see post-design note below).

## Project Structure

### Documentation (this feature)

```text
specs/001-foundation-skeleton/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── slash-commands.md
│   ├── application-services.md
│   └── configuration.md
├── checklists/
│   └── requirements.md  # From /speckit-specify
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
pom.xml
mvnw / mvnw.cmd / .mvn/
Dockerfile                       # single multi-stage: build (Maven) → runtime (slim JRE)
compose.yaml                     # dev: Postgres 17 only (app runs on host)
compose.prod.yaml                # prod parity: app + Postgres, separate volume/ports
.env.example                     # documents required env vars; real .env is git-ignored
.github/workflows/ci.yml         # build + test on every PR (host Docker for Testcontainers)

src/main/java/bot/
├── CoinyBotApplication.java                 # Spring Boot entry point
├── domain/
│   └── liveness/
│       ├── LivenessStatus.java              # value object (record) — pure Java
│       └── LivenessProbePort.java           # outbound port — pure Java, no framework imports
├── application/
│   └── liveness/
│       ├── LivenessService.java             # @Service (NOT transactional) — delegate + map
│       ├── CheckLivenessRequest.java        # request record
│       └── CheckLivenessResult.java         # result record
├── discord/
│   └── command/
│       ├── SlashCommandHandler.java         # handler SPI (name + handle)
│       └── PingCommand.java                 # thin handler: defer → delegate → reply
└── infrastructure/
    ├── discord/
    │   ├── JdaConfig.java                   # builds JDA bean from DISCORD_TOKEN
    │   ├── SlashCommandRegistrar.java       # upserts slash commands on ready
    │   └── InteractionRouter.java           # routes interaction events to handlers
    └── persistence/
        ├── HealthCheckEntity.java           # @Entity mapping the seeded health_check row
        ├── HealthCheckJpaRepository.java     # Spring Data JpaRepository
        └── JpaLivenessProbeAdapter.java     # implements LivenessProbePort; non-transactional,
                                             # DataSource probe catches SQLException → down

src/main/resources/
├── application.yml                          # base config (env placeholders, fail-fast)
├── application-dev.yml                      # dev profile (host → dockerized Postgres)
└── db/migration/
    └── V1__init_liveness.sql                # creates + seeds health_check; immutable

src/test/java/bot/
├── support/
│   └── AbstractPostgresIntegrationTest.java # Testcontainers Postgres 17 base
├── application/liveness/
│   └── LivenessServiceTest.java             # unit (Mockito port)
├── infrastructure/persistence/
│   └── JpaLivenessProbeAdapterTest.java     # integration (real Postgres)
└── StartupIntegrationTest.java              # context loads + migration applied + fail-fast
```

**Structure Decision**: Single Maven module with a hexagonal package layout under base package
`bot` (matching CLAUDE.md). Dependencies point inward to `bot.domain`; only `bot.application` opens
transactions and calls ports; `bot.infrastructure` and `bot.discord.command` are adapters. This
satisfies Constitution Principle II and keeps the domain framework-free and unit-testable.

## Post-Design Constitution Re-Check

After Phase 1 design (data model, contracts, quickstart): no new violations. The domain port
`LivenessProbePort` and `LivenessStatus` are pure Java; the only DB touch is a non-transactional
connectivity probe in the infrastructure adapter (opens a `DataSource` connection, catches
`SQLException` → `down`) followed by the seeded-row read, so a down database becomes a clean
`reachable = false` rather than a leaked exception; `/ping` defers before any work; the single
`V1` migration is immutable and seeds the row the liveness read depends on; no secret appears in
any tracked file. Principle II still holds — the application orchestrates and infrastructure owns
the database access; a read-only liveness check legitimately needs no transaction. Gates remain
green.

## Complexity Tracking

> No Constitution Check violations. Table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
