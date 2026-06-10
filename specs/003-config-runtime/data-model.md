# Phase 1 Data Model: Configuration Consolidation & Containerized Dev/Prod Runtime

This feature introduces **no domain entities** and **no schema change** (no Flyway migration). Its
"data model" is the *configuration surface*: the Spring config keys, the environment variables that
resolve them, and where each value's single source of truth lives. This document is the canonical
inventory the implementation and tasks validate against.

## A. Configuration key inventory (`application.yml` family — YAML, retained)

Every key below already exists in the YAML files and is **kept as-is**. The only change is the
runtime topology that supplies the `${...}` values. No key may move to `application.properties`.

| Key | File (layer) | Value / source | Secret? |
|---|---|---|---|
| `spring.application.name` | `application.yml` (base) | literal `coiny-bot` | no |
| `spring.datasource.url` | base | `${DB_URL}` | no |
| `spring.datasource.username` | base | `${DB_USERNAME}` | no |
| `spring.datasource.password` | base | `${DB_PASSWORD}` | **yes** |
| `spring.jpa.hibernate.ddl-auto` | base | literal `validate` (prod-safe) | no |
| `spring.jpa.open-in-view` | base | literal `false` | no |
| `spring.flyway.enabled` / `locations` | base | literal `true` / `classpath:db/migration` | no |
| `spring.messages.basename` / `encoding` | base | literal `messages/coin-messages` / `UTF-8` | no |
| `discord.enabled` | base | literal `true` | no |
| `discord.token` | base | `${DISCORD_TOKEN}` | **yes** |
| `coin.history.default-limit` | base | literal `10` | no |
| `spring.jpa.show-sql` | `application-dev.yml` (overlay) | literal `true` (dev only) | no |
| `logging.level.org.flywaydb` / `net.dv8tion.jda` | overlay | literal `INFO` (dev only) | no |
| `discord.enabled` | `src/test/resources/application.yml` (test) | literal `false` (no gateway in tests) | no |
| `spring.jpa.hibernate.ddl-auto`, `spring.messages.*` | test | literals (Testcontainers supplies the datasource) | no |

**Invariants**
- **INV-1 (format)**: all keys live in `application.yml` / `application-{profile}.yml`; no
  `application.properties` exists. *(FR-001, SC-005)*
- **INV-2 (placeholders only)**: the four secret/connection values appear in config **only** as
  `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`, `${DISCORD_TOKEN}` — never as literals. *(FR-003)*
- **INV-3 (base = prod)**: `application.yml` is the production config; prod activates no extra
  profile; `dev` is the only overlay. *(FR-001, clarify Q2)*
- **INV-4 (no second copy)**: no connection value is restated in `application-dev.yml` or anywhere
  in Spring config — it is referenced, not duplicated. *(FR-015, SC-008)*

## B. Environment-variable contract (the resolution boundary)

These are the variables the app process expects at runtime. **Owner** = the single place the value
is defined; **Consumers** = who reads it. Full contract in
[`contracts/environment.md`](./contracts/environment.md).

| Variable | Owner (single source) | Consumers | Secret? | Has compose default? |
|---|---|---|---|---|
| `DB_URL` | compose `app` service | app (`spring.datasource.url`) | no | yes — `jdbc:postgresql://postgres:5432/<db>` |
| `DB_USERNAME` | compose (one decl, both services) | postgres (`POSTGRES_USER`), app | no | yes — `coiny` |
| DB name (`<db>`) | compose (one decl, both services) | postgres (`POSTGRES_DB`), app (inside `DB_URL`) | no | yes — `coiny` |
| `DB_PASSWORD` | **prompted by launch script** | postgres (`POSTGRES_PASSWORD`), app (`spring.datasource.password`) | **yes** | **no** (must be supplied) |
| `DISCORD_TOKEN` | **prompted by launch script** | app (`discord.token`) | **yes** | **no** (must be supplied) |
| `SPRING_PROFILES_ACTIVE` | compose `app` service | Spring | no | dev: `dev`; prod: unset (base = prod) |

**Invariants**
- **INV-5 (single source)**: non-secret connection values are declared once per compose file and
  injected into **both** `postgres` and `app`; the app never re-derives them from a different place.
  *(FR-015, SC-008)*
- **INV-6 (in-cluster host)**: `DB_URL` host is the compose service name `postgres:5432`, not
  `localhost` and not the published host port. *(research R4)*
- **INV-7 (secrets have no default)**: `DB_PASSWORD` and `DISCORD_TOKEN` are never given a compose
  default and never committed/cached — they exist only as exported env in the launch script's shell.
  *(FR-004, SC-002)*
- **INV-8 (tests need none)**: with **no** variable set, `./mvnw verify` passes (Discord disabled;
  Postgres from Testcontainers). *(FR-011, SC-003)*

## C. Runtime topology (the two compose configurations)

| Aspect | Dev (`compose.yaml`) | Prod (`compose.prod.yaml`) |
|---|---|---|
| Services | `postgres` + **new** `app` | `postgres` + `app` (exists) |
| App profile | `SPRING_PROFILES_ACTIVE=dev` | none (base = prod) |
| Published DB port | `5432` | `5433` |
| Data volume | `coiny-pgdata-dev` | `coiny-pgdata-prod` |
| Start ordering | `app depends_on postgres (healthy)` | same (exists) |
| Reset/wipe | offered by `up-dev.sh` (`down -v`) | never offered |

**Invariants**
- **INV-9 (no collision)**: dev and prod use separate ports and separate volumes and run together
  without colliding. *(FR-005, SC-007)*
- **INV-10 (one Dockerfile)**: the dev `app` service builds from the existing single multi-stage
  Dockerfile; no per-environment Dockerfile. *(constitution — Containerization)*

## D. Out of model
- No domain entity, value object, repository, or migration is added or changed.
- Product behavior (coin/ledger commands, `/ping`) is identical before and after. *(FR-012, SC-006)*
