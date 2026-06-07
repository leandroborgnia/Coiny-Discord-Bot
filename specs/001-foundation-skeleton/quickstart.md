# Quickstart & Validation Guide: Foundation Skeleton

How to run the foundation locally, verify the liveness slice end to end, run the test suite, and
build the single artifact. This is a validation guide — implementation details live in `tasks.md`
and the source. Commands assume Windows PowerShell (the project's shell); the `./mvnw` wrapper
works the same on other platforms.

## Prerequisites

- Docker Desktop running (required for the dev Postgres and for Testcontainers).
- JDK 21 available (the `./mvnw` wrapper manages Maven itself).
- A Discord application + bot token, and a test server you've invited the bot to.

## 1. Configure secrets (never committed)

Copy the example env file and fill in real values:

```powershell
Copy-Item .env.example .env
# Edit .env and set:
#   DISCORD_TOKEN=...           (bot token)
#   DB_URL=jdbc:postgresql://localhost:5432/coiny
#   DB_USERNAME=coiny
#   DB_PASSWORD=...             (any local dev password)
```

`.env` is git-ignored. Secrets come from the environment only (see
[contracts/configuration.md](./contracts/configuration.md)).

## 2. Start the local data store

```powershell
docker compose up -d           # starts Postgres 17 (dev volume + port)
```

Stop with `docker compose down`; wipe the dev volume with `docker compose down -v`.

## 3. Run the bot (fast inner loop, on the host)

```powershell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**Expected**: startup connects to Postgres, Flyway applies `V1__init_liveness.sql`, the bot logs
readiness and appears **online**, and `/ping` is registered to every server the bot is in.

> Fail-fast checks: with `DISCORD_TOKEN` or `DB_PASSWORD` unset, or Postgres not running, startup
> aborts with a clear error and the bot does **not** come online (validates FR-005/FR-007).

## 4. Validate the liveness command (US1 / SC-002)

In the test server, run:

```text
/ping
```

**Expected**: a success reply confirming data-store reachability (e.g. `🟢 Pong! Data store
reachable (ok).`), returned within the interaction-response window. This proves the full path:
interaction received → data store read → reply.

## 5. Validate restart safety (SC-003)

Stop the app (Ctrl+C) and re-run step 3 **without** wiping the volume.

**Expected**: startup succeeds with no manual schema steps; Flyway reports all migrations already
applied; no duplicate/corrupt history; `/ping` still succeeds.

## 6. Run the tests (real Postgres via Testcontainers)

```powershell
./mvnw -q verify
```

**Expected**: unit tests (mocked port) and integration tests (Testcontainers Postgres 17) pass.
Testcontainers starts its **own** throwaway Postgres — it does not use the compose dev database;
Docker just needs to be running on the host. Tests are never run inside the app container.

## 7. Build the single artifact / image (operator path, US4 / SC-005)

```powershell
./mvnw -q -DskipTests package          # produces the executable Spring Boot jar
docker build -t coiny-bot .            # single multi-stage image (build → slim JRE runtime)
docker compose -f compose.prod.yaml up --build   # app + Postgres parity (separate volume/ports)
```

**Expected**: launching from the produced jar/image yields the same online bot answering `/ping`
identically to the host run (parity), given valid env secrets and a reachable Postgres.

## 8. Continuous integration (US3 / SC-004)

Opening a pull request triggers GitHub Actions, which runs `./mvnw -q verify` on JDK 21 with the
runner's host Docker backing Testcontainers. A deliberately broken build or failing test reports a
**failure** and branch protection blocks the merge; a healthy PR reports a **pass**.

## Validation checklist (maps to spec success criteria)

- [ ] Clean checkout → running, online bot via documented steps only (SC-001)
- [ ] `/ping` returns success confirming DB reachability within the window (SC-002)
- [ ] Restart against existing store: no manual steps, no history corruption (SC-003)
- [ ] PR checks report clear pass/fail; broken build/test fails and blocks merge (SC-004)
- [ ] Single artifact/image launches an identical online bot (SC-005)
- [ ] Missing secret / unreachable store fails fast, bot stays offline (SC-006)
