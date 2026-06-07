# Coiny Discord Bot

A Discord bot on Java 21, Spring Boot 3, JDA 5, Spring Data JPA / Hibernate, and Postgres 17.
This is the **foundation skeleton** slice: a single `/ping` liveness command that proves the whole
stack is wired (interaction → data store → reply), with fail-fast startup, automatic Flyway
migrations, env-sourced secrets, CI, and a single runnable artifact. No coin economy yet.

See the active spec under [`specs/001-foundation-skeleton/`](specs/001-foundation-skeleton/) and the
project rules in [`.specify/memory/constitution.md`](.specify/memory/constitution.md).

## Prerequisites

- **Docker Desktop running** (for the dev Postgres and for Testcontainers)
- **JDK 21** (the `./mvnw` wrapper manages Maven itself)
- A Discord application + bot token, and a test server you've invited the bot to

## Local setup (clean checkout → running bot)

```powershell
# 1. Configure secrets (never committed)
Copy-Item .env.example .env
#    Edit .env: DISCORD_TOKEN, DB_PASSWORD (DB_URL/DB_USERNAME already default to local)

# 2. Start the local data store (Postgres 17 in Docker)
docker compose up -d

# 3. Run the bot on the host (fast inner loop)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The bot connects to Postgres, applies the Flyway migration, comes **online**, and registers `/ping`
to every server it is in (and to any server it later joins). In the server, run `/ping` → it replies
with a success message confirming the data store is reachable. The reply always lands in the same
server and channel the command came from.

Stop the database with `docker compose down`; wipe its volume with `docker compose down -v`.

> Fail-fast: if `DISCORD_TOKEN` or `DB_PASSWORD` is missing, or Postgres is not running, startup
> aborts with a clear error and the bot does not come online.

## Tests

```powershell
./mvnw -q verify
```

Unit tests use JUnit 5 + Mockito + AssertJ. Integration tests use **Testcontainers**, which starts
its own throwaway Postgres 17 on the host Docker daemon — it does not use the compose dev database,
and tests are never run inside a container.

## Build the single artifact / image

```powershell
./mvnw -q -DskipTests package          # executable Spring Boot jar in target/
docker build -t coiny-bot .            # single multi-stage image
docker compose -f compose.prod.yaml up --build   # app + Postgres parity (separate volume/port)
```

Launching from the jar or image yields the same online bot answering `/ping`.

## Continuous integration

Every pull request runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml): JDK 21 +
`./mvnw -B -q verify` with the runner's Docker daemon backing Testcontainers. **Enable branch
protection on `main` requiring the `Build & test` check to pass** so a broken build or failing test
blocks merge.

## Architecture (hexagonal)

- `bot.discord.command` — thin JDA slash-command handlers (defer first, no business logic)
- `bot.application` — use-case services (request record in, result record out)
- `bot.domain` — pure Java; entities, value objects, ports (no Spring/JDA imports)
- `bot.infrastructure` — JDA wiring, Spring Data repositories, Flyway, adapters
