# Quickstart & Validation: Configuration Consolidation & Containerized Dev/Prod Runtime

Runnable scenarios that prove the feature works end to end. Each maps to spec acceptance criteria.
See [`contracts/environment.md`](./contracts/environment.md) and
[`contracts/launch-scripts.md`](./contracts/launch-scripts.md) for the interfaces, and
[`data-model.md`](./data-model.md) for the config/env inventory.

## Prerequisites
- Docker running (Docker Desktop with WSL integration on Windows; Docker Engine on Linux).
- On Windows: a WSL bash-capable distro; scripts must have **LF** endings.
- A Discord bot token and a chosen DB password to type when prompted.

## Scenario 1 — Tests pass with NO secrets (FR-011, SC-003)
```bash
# Ensure no secrets are exported
unset DB_PASSWORD DISCORD_TOKEN DB_URL DB_USERNAME
./mvnw -q verify
```
**Expected**: full suite green. Discord is disabled (`discord.enabled=false` in the test profile)
and Postgres comes from Testcontainers — no secrets required.

## Scenario 2 — Config inspection (FR-001, FR-002, FR-003, SC-005, SC-002)
```bash
# All Spring config is YAML; no .properties, no .env
ls src/main/resources/application.yml src/main/resources/application-dev.yml src/test/resources/application.yml
test ! -e application.properties && test ! -e src/main/resources/application.properties && echo "no .properties OK"
test ! -e .env && test ! -e .env.example && echo "no dotenv OK"
# Secrets appear only as ${...} placeholders
grep -nE '\$\{DB_URL|\$\{DB_USERNAME|\$\{DB_PASSWORD|\$\{DISCORD_TOKEN' src/main/resources/application.yml
# No real token/password committed anywhere
git grep -nE 'MT[A-Za-z0-9._-]{20,}|password\s*[:=]\s*\S' -- ':!*.md' || echo "no committed secrets"
```
**Expected**: the three YAML files exist; no `.properties`; no `.env`/`.env.example`; secret values
are only `${...}`; no real secret committed.

## Scenario 3 — Dev one-command start, decline reset (US1-1, US2-1, FR-009)
```bash
./scripts/up-dev.sh            # Linux/macOS
# Windows: .\scripts\up-dev.ps1
```
- Answer `reset database?` → **N**.
- Enter the existing DB password; enter the token (input hidden).

**Expected**: image builds, `postgres` becomes healthy, then `app` boots, applies Flyway migrations,
connects via `jdbc:postgresql://postgres:5432/coiny`, and the bot comes online. App + DB started
together; nothing prompted you for Docker commands.

## Scenario 4 — Forgotten-password recovery via reset (US2-2, FR-006, FR-013)
```bash
./scripts/up-dev.sh
```
- Answer `reset database?` → **y**.
- Enter a **new** DB password; enter the token (hidden).

**Expected**: the dev volume (`coiny-pgdata-dev`) is wiped (`down -v`), Postgres re-initializes with
the new password, and the stack starts cleanly — without you typing any Docker command.

## Scenario 5 — Password mismatch without reset fails fast (US2-4, FR-014)
```bash
./scripts/up-dev.sh
```
- Answer `reset database?` → **N**.
- Enter a password that does **not** match the existing volume.

**Expected**: `postgres` stays on its original password; `app` fails to authenticate and exits
non-zero with a clear DB authentication error. Nothing is left half-started.

## Scenario 6 — Blank secret rejected (Edge Cases)
- Run a launch script and press Enter at a password/token prompt.

**Expected**: the script rejects the empty value and re-prompts; the stack does not start with an
invalid value.

## Scenario 7 — Windows wrapper parity (US2-3, FR-008)
```powershell
.\scripts\up-dev.ps1
```
**Expected**: the same prompts/reset/result as the shell flow, executed through WSL. If WSL or the
Docker daemon is unavailable, a clear error is printed and nothing hangs.

## Scenario 8 — Prod start, no reset offered (US3-1, FR-007)
```bash
./scripts/up-prod.sh           # or .\scripts\up-prod.ps1
```
**Expected**: prompts for password + token (token hidden), then app + DB start on the prod port
(`5433`) and volume (`coiny-pgdata-prod`). **No** reset/wipe prompt appears.

## Scenario 9 — Dev + prod coexist (US3-2, SC-007)
```bash
./scripts/up-dev.sh    # one terminal
./scripts/up-prod.sh   # another terminal
docker ps              # both stacks running
```
**Expected**: both run simultaneously — dev on `5432`/`coiny-pgdata-dev`, prod on
`5433`/`coiny-pgdata-prod` — with no port or volume collision.

## Scenario 10 — Behavior unchanged (FR-012, SC-006)
With a stack up, exercise `/ping` and the coin/ledger commands (`/balance`, `/coins-adjust`,
`/coins-config`).

**Expected**: identical behavior to before the config/runtime change.
