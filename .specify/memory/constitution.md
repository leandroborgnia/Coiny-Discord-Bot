<!--
SYNC IMPACT REPORT
==================
Version change: (template) → 1.0.0
Bump rationale: Initial ratification of a concrete constitution from the template.
                MINOR/PATCH not applicable; first adopted version is 1.0.0.

Principles defined (template slot → ratified name):
  [PRINCIPLE_1_NAME] → I. Postgres-Only Persistence
  [PRINCIPLE_2_NAME] → II. Hexagonal Architecture & Domain Purity
  [PRINCIPLE_3_NAME] → III. Append-Only, Double-Entry Coin Ledger
  [PRINCIPLE_4_NAME] → IV. Atomic Cooldown Engine
  [PRINCIPLE_5_NAME] → V. Thin, Fast Discord Handlers
  (added)           → VI. Real-Postgres Testing Discipline
  (added)           → VII. Immutable Migrations & Secret Hygiene

Sections defined:
  [SECTION_2_NAME] → Containerization & Environment Topology
  [SECTION_3_NAME] → Development Workflow & Quality Gates
  Governance      → Governance

Added sections: none beyond the two template section slots (expanded from 5 to 7 principles).
Removed sections: none.

Templates / artifacts reviewed for consistency:
  ✅ .specify/templates/plan-template.md   — Constitution Check gate is generic; principles below map to it. No edit required.
  ✅ .specify/templates/spec-template.md   — No principle-specific mandatory sections to add. No edit required.
  ✅ .specify/templates/tasks-template.md  — Testing/migration/observability task types compatible. No edit required.
  ✅ CLAUDE.md                             — Already aligned (Postgres-only, single Dockerfile, Testcontainers, Flyway, env secrets).

Follow-up TODOs: none. RATIFICATION_DATE set to first adoption date 2026-06-06.
-->

# Coiny Discord Bot Constitution

## Core Principles

### I. Postgres-Only Persistence

Postgres 17 is the ONLY database engine, in every environment — local development,
CI, and production. H2, SQLite, and any in-memory or embedded substitute are
forbidden, including for tests. Because there is exactly one engine, code MUST NOT
contain database-portability workarounds; it MAY and SHOULD rely on Postgres-specific
behavior (sequences, `ON CONFLICT`, MVCC, advisory locks, partial indexes).

**Rationale**: A single, identical engine across all environments eliminates an entire
class of "works on H2, breaks on Postgres" defects and lets the ledger and cooldown
engine depend on transactional guarantees that only the real engine provides.

### II. Hexagonal Architecture & Domain Purity

The system is layered/hexagonal with a strict dependency direction toward the domain.
The `bot.domain` layer (entities, value objects, domain services) MUST NOT import
Spring, JDA, Jakarta Persistence, or any framework/transport type — it is pure Java.
`bot.application` orchestrates use cases and is the only layer permitted to open
transactions and call repositories. `bot.infrastructure` holds Spring Data
repositories, Flyway, and external clients. `bot.discord.command` is the inbound
adapter and depends inward only.

**Rationale**: Keeping the domain free of framework imports makes business rules
unit-testable without a container and prevents transport or persistence concerns from
leaking into core logic.

### III. Append-Only, Double-Entry Coin Ledger

The coin ledger MUST be append-only and double-entry: every economic event records
balanced entries, and posted ledger rows are NEVER updated or deleted. Balances MUST
be DERIVED from ledger entries, never stored as a mutable column that can drift.
Ledger correctness MAY rely on Postgres-specific behavior such as sequences for
monotonic ordering and MVCC for consistent reads.

**Rationale**: An immutable, double-entry log is auditable and self-reconciling; a
stored balance is a denormalization that inevitably diverges from its source of truth.

### IV. Atomic Cooldown Engine

The cooldown engine MUST be the single, atomic source of truth for whether an action
is permitted. Claim/consume decisions MUST be resolved in one atomic database
operation (e.g. a conditional insert/update guarded by the row's current state), with
no read-then-write race window. Cooldown state MUST NOT be duplicated in memory,
caches, or per-shard structures that could disagree.

**Rationale**: Cooldowns gate the economy; any non-atomic check is exploitable for
double-spend or rapid-fire abuse, and any second source of truth can drift.

### V. Thin, Fast Discord Handlers

Discord slash-command handlers MUST acknowledge interactions by deferring within 2.5
seconds of receipt and MUST do no work synchronously before deferring. Handlers
contain NO business logic: they parse and validate the interaction, delegate to an
`bot.application` service (request record in, result record out), and render the
reply. Domain rules, ledger writes, and cooldown checks live below the handler.

**Rationale**: Discord invalidates interactions that are not acknowledged in time;
keeping handlers thin both meets that deadline and preserves the architecture's
dependency direction.

### VI. Real-Postgres Testing Discipline

Tests use JUnit 5, Mockito, AssertJ, and Testcontainers. Integration and persistence
tests MUST run against a real, throwaway Postgres provisioned by Testcontainers — never
against a substitute engine and never against the shared dev compose database. Tests
run via Maven on the host and MUST NOT be executed inside an application container
(Testcontainers requires the host Docker daemon; Docker-in-Docker is prohibited).

**Rationale**: Testing against the production engine is the only way to validate
Postgres-specific ledger and cooldown behavior; running on the host avoids
Docker-in-Docker and keeps the test database isolated from dev data.

### VII. Immutable Migrations & Secret Hygiene

Schema is owned by Flyway. A migration that has been applied to any shared environment
MUST NEVER be edited, renumbered, or deleted — changes ship as a new `V<n+1>`
migration. Secrets (the Discord token, database passwords, and any credential) MUST be
supplied via environment variables and MUST NEVER be committed to the repository or
baked into a committed properties file or image layer.

**Rationale**: Editing an applied migration corrupts checksum history and desyncs
environments; committed secrets are effectively leaked permanently in Git history.

## Containerization & Environment Topology

The application is packaged as a SINGLE multi-stage Docker image at the repository
root: a build stage runs Maven to produce the jar, and a slim-JRE runtime stage serves
both dev-container runs and production. There MUST NOT be per-environment Dockerfiles.

Postgres runs in Docker for BOTH local development and production — no native Postgres
install. Dev and prod use the SAME Postgres image but SEPARATE compose configurations,
ports, and named volumes; their data volumes MUST NEVER be shared.

All dev-vs-prod differences MUST be expressed through Spring profiles and environment
variables (optionally a prod compose override file), never by forking the Dockerfile.
For fast inner-loop iteration, the application MAY run on the host via the Spring Boot
Maven plugin against the Dockerized Postgres.

## Development Workflow & Quality Gates

- Dependencies MUST be recorded in the feature `plan.md` before being added to the
  build.
- `./mvnw -q verify` MUST pass after each task; failures are surfaced verbatim, not
  summarized away.
- Code review MUST verify compliance with the Core Principles above; a reviewer MUST
  reject changes that import framework types into `bot.domain`, store derived balances,
  introduce a second source of truth for cooldowns, perform synchronous work before an
  interaction deferral, add a second database engine, edit an applied migration, or
  commit a secret.
- Any deviation from a principle MUST be documented in the plan's Complexity Tracking
  table with a justification and the rejected simpler alternative.

## Governance

This constitution supersedes other practices and conventions where they conflict. The
CLAUDE.md project memory and the `.specify/templates/*` files are subordinate to it and
MUST be kept consistent with it.

**Amendment procedure**: Proposed amendments MUST be submitted as a pull request that
edits this file, states the motivation, and updates every dependent artifact
(templates, CLAUDE.md, runtime guidance) in the same change so the repository never
holds a contradictory state. An amendment requires explicit approval from the project
maintainer before merge.

**Versioning policy**: This constitution is versioned with semantic versioning:
- MAJOR — backward-incompatible governance changes or removal/redefinition of a
  principle.
- MINOR — a new principle or section is added, or guidance is materially expanded.
- PATCH — clarifications, wording, and non-semantic refinements.

**Compliance review**: Every plan's Constitution Check gate MUST be evaluated against
the principles above before Phase 0 and again after Phase 1 design. Pull requests that
touch the ledger, cooldown engine, migrations, Docker topology, or secret handling MUST
receive explicit principle-by-principle sign-off.

**Version**: 1.0.0 | **Ratified**: 2026-06-06 | **Last Amended**: 2026-06-06
