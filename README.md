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

The app and Postgres run together in Docker; a launch script prompts for the secrets each run.
There is **no** `.env` file and no dotenv mechanism — secrets are supplied as environment variables
injected by Docker Compose.

```powershell
# Windows (PowerShell + WSL): brings up app + Postgres together
.\scripts\up-dev.ps1
```

```bash
# Linux/macOS: same flow
./scripts/up-dev.sh
```

The script first asks **whether to reset the database** (answer "yes" to wipe the dev volume and
recover from a forgotten local password), then prompts for the **database password** and the
**Discord token** (token entered hidden). It then builds and starts the stack.

The bot connects to Postgres, applies the Flyway migration, comes **online**, and registers `/ping`
to every server it is in (and to any server it later joins). In the server, run `/ping` → it replies
with a success message confirming the data store is reachable. The reply always lands in the same
server and channel the command came from.

> Fail-fast: if a secret is blank the script re-prompts; if the entered password does not match an
> existing (non-reset) database, startup fails with a clear DB authentication error and the bot does
> not come online.

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
.\scripts\up-prod.ps1                  # app + Postgres (prod port/volume); prompts for secrets
#   Linux/macOS: ./scripts/up-prod.sh
```

Launching from the jar or image yields the same online bot answering `/ping`. The prod launch
script prompts for the secrets and brings the app + Postgres up together (separate volume/port from
dev); it never offers a reset/wipe.

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
