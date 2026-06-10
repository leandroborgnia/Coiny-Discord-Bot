<!--
SYNC IMPACT REPORT
==================
Version change: 1.1.0 → 1.1.1
Bump rationale: PATCH — concision / editorial pass. Removed duplicated restatements and trimmed
                rationales; no principle, rule, or normative meaning changed.

Editorial notes:
  - The app-run rule (Docker Compose, not host Maven) is now stated once authoritatively in
    "Containerization & Environment Topology" and only cross-referenced from Principle VI.
  - The Development Workflow code-review bullet no longer re-enumerates each principle (that
    list duplicated Principles I-VII); it now references the Core Principles as a whole.
  - Principle III no longer repeats the Postgres-reliance allowance already granted by I.

Added sections: none. Removed sections: none.

Templates / artifacts reviewed:
  ✅ .specify/templates/{plan,spec,tasks}-template.md — generic Constitution Check gate; no edit.
  ⚠ CLAUDE.md (Build/Test/Run) + src/main/resources/application.yml — still pre-amendment;
      reconciled by the active configuration & runtime feature (specify → plan → implement).

Follow-up TODOs: none. RATIFICATION_DATE remains 2026-06-06.
-->

# Coiny Discord Bot Constitution

## Core Principles

### I. Postgres-Only Persistence

Postgres 17 is the ONLY database engine in every environment — development, CI, and
production. H2, SQLite, and any in-memory or embedded substitute are forbidden, including in
tests. Code MUST NOT contain database-portability workarounds and MAY rely on Postgres-specific
behavior (sequences, `ON CONFLICT`, MVCC, advisory locks, partial indexes).

**Rationale**: One identical engine everywhere eliminates "works on H2, breaks on Postgres"
defects and lets the ledger and cooldown engine depend on guarantees only the real engine gives.

### II. Hexagonal Architecture & Domain Purity

Dependencies point inward toward the domain. `bot.domain` (entities, value objects, domain
services) is pure Java and MUST NOT import Spring, JDA, Jakarta Persistence, or any other
framework/transport type. `bot.application` orchestrates use cases and is the ONLY layer that
opens transactions or calls repositories. `bot.infrastructure` holds Spring Data repositories,
Flyway, and external clients; `bot.discord.command` is the inbound adapter. Every layer depends
inward only.

**Rationale**: A framework-free domain is unit-testable without a container and keeps transport
and persistence concerns out of business rules.

### III. Append-Only, Double-Entry Coin Ledger

The coin ledger MUST be append-only and double-entry: every economic event records balanced
entries, and posted rows are NEVER updated or deleted. Balances MUST be DERIVED from ledger
entries, never stored as a mutable column.

**Rationale**: An immutable double-entry log is auditable and self-reconciling; a stored balance
inevitably drifts from its source of truth.

### IV. Atomic Cooldown Engine

The cooldown engine MUST be the single, atomic source of truth for whether an action is
permitted. Claim/consume decisions MUST resolve in one atomic database operation (e.g. a
state-guarded conditional insert/update) with no read-then-write race. Cooldown state MUST NOT be
duplicated in memory, caches, or per-shard structures.

**Rationale**: Cooldowns gate the economy; any non-atomic check or second source of truth is
exploitable for double-spend or rapid-fire abuse.

### V. Thin, Fast Discord Handlers

Handlers MUST defer the interaction within 2.5 seconds and do NO work before deferring. They hold
NO business logic: they parse and validate the interaction, delegate to a `bot.application`
service (request record in, result record out), and render the reply.

**Rationale**: Discord invalidates unacknowledged interactions; thin handlers meet that deadline
and preserve the inward dependency direction.

### VI. Real-Postgres Testing Discipline

Tests use JUnit 5, Mockito, AssertJ, and Testcontainers. Integration and persistence tests MUST
run against a real, throwaway Postgres provisioned by Testcontainers — never a substitute engine
and never the shared dev compose database. Tests run via Maven on the host (never inside a
container: Testcontainers needs the host Docker daemon, and Docker-in-Docker is prohibited) and
require no secrets (Discord is disabled; the database comes from Testcontainers). Host Maven is
for the build and tests only — the app is run via Docker Compose (see Containerization &
Environment Topology).

**Rationale**: Only the production engine validates Postgres-specific ledger and cooldown
behavior; running on the host avoids Docker-in-Docker and isolates the test database from dev data.

### VII. Immutable Migrations, Configuration & Secret Hygiene

Schema is owned by Flyway; an applied migration is NEVER edited, renumbered, or deleted — changes
ship as a new `V<n+1>`. Spring configuration lives in `application.properties` (plus
`application-{profile}.properties`); a dotenv library and Spring-side `.env` loading are FORBIDDEN
(Docker Compose's native variable substitution is permitted). Secrets (Discord token, database
passwords, any credential) MUST be `${...}` placeholders supplied as environment variables —
injected by Docker Compose and prompted by the launch scripts each run — and MUST NEVER be
committed, baked into an image layer, or cached to an on-disk file.

**Rationale**: Editing an applied migration corrupts checksum history; a committed or file-cached
secret is leaked permanently. One `application.properties` surface with env-injected,
never-persisted secrets stays reviewable while keeping no credential at rest.

## Containerization & Environment Topology

The application is packaged as a SINGLE multi-stage Docker image at the repository root (Maven
build stage → slim-JRE runtime stage) serving both dev and production; there MUST NOT be
per-environment Dockerfiles. Postgres also runs in Docker in both environments (no native
install). Dev and prod use the same images but SEPARATE compose configurations, ports, and named
volumes, and their data volumes MUST NEVER be shared. All dev-vs-prod differences MUST come from
Spring profiles and environment variables (optionally a prod compose override), never from
forking the Dockerfile.

The application MUST be run via Docker Compose in both environments — app and database brought up
together through the project's launch scripts (Linux shell with PowerShell/WSL wrappers) that
prompt for secrets and inject them as environment variables. Running the app on the host via the
Spring Boot Maven plugin is not the intended path (not prohibited, but unsupported); host Maven is
reserved for the build and the test suite.

## Development Workflow & Quality Gates

- Dependencies MUST be recorded in the feature `plan.md` before being added to the build.
- `./mvnw -q verify` MUST pass after each task; failures are surfaced verbatim.
- Code review MUST reject any change that violates a Core Principle; any intentional deviation
  MUST be justified in the plan's Complexity Tracking table alongside the rejected simpler
  alternative.

## Governance

This constitution supersedes conflicting practices; `CLAUDE.md` and `.specify/templates/*` are
subordinate and MUST be kept consistent with it.

**Amendment procedure**: An amendment is a pull request that edits this file, states the
motivation, and updates every dependent artifact in the same change so the repository never holds
a contradictory state; merge requires the maintainer's approval.

**Versioning** (semantic): MAJOR — backward-incompatible governance change or removal/redefinition
of a principle; MINOR — a principle or section added, or guidance materially expanded; PATCH —
clarifications and non-semantic refinements.

**Compliance review**: Every plan's Constitution Check gate MUST be evaluated before Phase 0 and
again after Phase 1. Pull requests touching the ledger, cooldown engine, migrations, Docker
topology, or secret handling MUST receive principle-by-principle sign-off.

**Version**: 1.1.1 | **Ratified**: 2026-06-06 | **Last Amended**: 2026-06-10
