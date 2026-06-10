# Implementation Plan: Configuration Consolidation & Containerized Dev/Prod Runtime

**Branch**: `003-config-runtime` | **Date**: 2026-06-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-config-runtime/spec.md`

## Summary

Consolidate the bot's runtime configuration and make Docker Compose the intended way to run the
app in **both** dev and prod, while preserving product behavior exactly. Spring configuration
**stays in YAML** — `application.yml` (base = production-safe defaults and the production config),
`application-dev.yml` (the only profile overlay), and the test `application.yml` — with **no**
`application.properties` introduced and **no** new dependencies. The repo's on-disk `.env` (which
currently holds a real token + password) and `.env.example` are deleted; the app never reads a
dotenv file. Every secret and every non-secret connection value is supplied as an **environment
variable injected by Docker Compose**, with each value owning a **single source of truth** in the
compose files (referenced from `application.yml` only via `${...}`), so Docker and Spring cannot
drift.

Technical approach: add a dev `app` service to `compose.yaml` (built from the existing single
multi-stage Dockerfile, `SPRING_PROFILES_ACTIVE=dev`, `depends_on: postgres healthy`,
`DB_URL=jdbc:postgresql://postgres:5432/<db>` using the compose **service name**, not localhost),
keeping dev's separate port (5432) and volume (`coiny-pgdata-dev`); `compose.prod.yaml` already has
its `app` service. Author the prompt-and-reset logic **once** in two POSIX bash scripts
(`scripts/up-dev.sh`, `scripts/up-prod.sh`, LF endings, executable) that prompt for secrets
(`read -s` for the token), export them, and run `docker compose -f <file> up --build`; `up-dev.sh`
first asks `reset database? (y/N)` and on yes runs `down -v` before starting. Two **thin**
PowerShell wrappers (`scripts/up-dev.ps1`, `scripts/up-prod.ps1`) invoke the matching `.sh` through
`wsl` so prompted secrets never cross the PowerShell→WSL boundary as variables. Update
`CLAUDE.md` so Docker-Compose-via-scripts is the documented run path and host Maven is the build +
test path only. No Flyway, domain, application, or handler code changes.

## Technical Context

**Language/Version**: Java 21 (unchanged); tooling in POSIX **bash** + **PowerShell 5.1** wrappers.

**Primary Dependencies**: Spring Boot 3.3.x (Data JPA, Validation), JDA 5.0.x, Hibernate (via Data
JPA), Flyway (`flyway-core` + `flyway-database-postgresql`), PostgreSQL JDBC driver. **No new
runtime/build dependencies.** Tooling relies on Docker Compose v2, a WSL bash distro on Windows, and
`docker`/`wsl` CLIs — not Maven/Java libraries.

**Storage**: PostgreSQL 17 in Docker (dev volume `coiny-pgdata-dev`, prod volume
`coiny-pgdata-prod`; never shared). No schema change.

**Testing**: JUnit 5 + Mockito + AssertJ + Testcontainers on the host (unchanged). The full suite
MUST stay green with **zero** secrets set (`discord.enabled=false` in the test profile; Postgres
from Testcontainers `@ServiceConnection`).

**Target Platform**: Linux containers (the app image). Developer hosts: Linux/macOS (native bash)
and Windows (Docker Desktop + WSL integration). Operators run the same image in prod.

**Project Type**: Single backend service (Discord bot). Infra/config-only feature — no source tree
additions beyond `scripts/`.

**Performance Goals**: N/A (behavior-preserving). Operational: `app` waits for `postgres` healthy
before starting; a wrong password fails fast at boot rather than retry-looping.

**Constraints**: Behavior-preserving (FR-012/SC-006); no secret committed or cached on disk
(FR-004/SC-002); bash scripts use **LF** endings so they run under WSL (Edge Cases); single source
of truth — no config value duplicated across Docker and Spring (FR-015/SC-008); config format stays
**YAML** (FR-001/SC-005); tests need no secrets (FR-011/SC-003).

**Scale/Scope**: ~2 compose edits, 4 scripts, delete 2 files (`.env`, `.env.example`), doc edits
(CLAUDE.md, README). Config files are retained as-is in format; only `compose.yaml` gains the dev
`app` service and (if needed) a single-source declaration of non-secret DB values.

## Constitution Check

*GATE: evaluated against constitution v1.1.2. Re-checked after Phase 1 design — still passing.*

| Principle / Section | Gate | Status |
|---|---|---|
| I. Postgres-Only Persistence | No substitute engine; Postgres 17 in every env | ✅ Unchanged — Postgres 17 only, dev + prod + Testcontainers |
| II. Hexagonal Architecture & Domain Purity | No layer/dependency-direction changes | ✅ N/A — no `domain`/`application`/`infrastructure`/`discord` code touched |
| III. Append-Only Double-Entry Ledger | No ledger changes | ✅ Behavior-preserving (SC-006) |
| IV. Atomic Cooldown Engine | No cooldown changes | ✅ Untouched |
| V. Thin, Fast Discord Handlers | No handler changes | ✅ Untouched |
| VI. Real-Postgres Testing Discipline | Tests on host via Testcontainers, no secrets | ✅ Reinforced — plan verifies `./mvnw verify` green with zero secrets; tests never run in a container |
| VII. Immutable Migrations, Config & Secret Hygiene | Config in `application.yml`; no dotenv/`.env`; secrets `${...}` env vars, never committed/cached; migrations untouched | ✅ **Directly implements** — deletes `.env`, keeps YAML, prompts + env-injects secrets; no Flyway file touched |
| Containerization & Environment Topology | Single Dockerfile; Docker-run both envs; separate ports/volumes; differences via profiles + env | ✅ Reinforced — adds dev `app` service to the existing Dockerfile/compose; dev 5432 / prod 5433, separate volumes |
| Dev Workflow & Quality Gates | Deps recorded in plan before adding; `./mvnw verify` green | ✅ **No new dependencies**; verify stays green |

**Result**: PASS, no violations. Complexity Tracking is intentionally empty.

## Project Structure

### Documentation (this feature)

```text
specs/003-config-runtime/
├── plan.md              # This file (/speckit-plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output — config-key & env-var contract (no domain entities)
├── quickstart.md        # Phase 1 output — runnable validation scenarios
├── contracts/           # Phase 1 output
│   ├── environment.md   #   env-var contract (names, owner, consumers, secret?)
│   └── launch-scripts.md#   launch-script CLI contract (prompts, reset, exit codes)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

This feature is configuration/runtime only — it adds **no** Java source. Touched paths:

```text
.
├── compose.yaml                         # EDIT: add dev `app` service; single-source non-secret DB values
├── compose.prod.yaml                    # REVIEW: already has `app`; align single-source values
├── Dockerfile                           # UNCHANGED (reused by the new dev `app` service)
├── .env                                 # DELETE (currently holds a real token + password)
├── .env.example                         # DELETE (no dotenv workflow remains)
├── .dockerignore / .gitignore           # REVIEW: keep `.env` ignored; drop stale dotenv guidance if any
├── scripts/                             # NEW
│   ├── up-dev.sh                        #   bash: reset? prompt → secret prompts → compose up --build (LF, +x)
│   ├── up-prod.sh                       #   bash: secret prompts → compose up --build (no reset) (LF, +x)
│   ├── up-dev.ps1                       #   thin PowerShell wrapper → invokes up-dev.sh via wsl
│   └── up-prod.ps1                      #   thin PowerShell wrapper → invokes up-prod.sh via wsl
├── src/main/resources/application.yml       # KEEP (base = prod config; verify it only references ${...})
├── src/main/resources/application-dev.yml   # KEEP (sole overlay; dev conveniences)
├── src/test/resources/application.yml       # KEEP (discord.enabled=false; no secrets)
├── CLAUDE.md                            # EDIT: Build/Test/Run → scripts are the run path; Maven = build+test only
└── README.md                            # EDIT: align run instructions with the scripts
```

**Structure Decision**: Reuse the existing single-project layout and the single multi-stage
Dockerfile. The only new directory is `scripts/`. The dev/prod split stays expressed through the two
compose files (separate ports + named volumes) and Spring profiles — never a forked Dockerfile.

## Complexity Tracking

> No Constitution Check violations — this section is intentionally empty.
