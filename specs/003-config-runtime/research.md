# Phase 0 Research: Configuration Consolidation & Containerized Dev/Prod Runtime

No `NEEDS CLARIFICATION` markers remained after the spec's clarify session. The decisions below
record the rationale and rejected alternatives for the choices baked into `plan.md`.

## R1. Config file format — keep YAML, do not move to `.properties`

- **Decision**: Spring configuration stays in `application.yml` / `application-{profile}.yml`. No
  `application.properties` is introduced.
- **Rationale**: The config is hierarchical (`spring.datasource.*`, `spring.jpa.hibernate.*`,
  `discord.*`, `coin.history.*`); YAML expresses nesting without the repeated flat key prefixes
  `.properties` forces. The files already exist in YAML, so "consolidation" is a *retain + verify*,
  not a rewrite — lowest-risk path to the behavior-preserving guarantee (FR-012). Aligns with
  constitution v1.1.2 (Principle VII).
- **Alternatives considered**: Convert to `.properties` (earlier clarify decision, reversed by the
  maintainer) — rejected: more churn, flattens hierarchy, no benefit. Split per-key files —
  rejected: over-engineering for ~12 keys.

## R2. Profile strategy — base-as-prod, single `dev` overlay

- **Decision**: `application.yml` holds production-safe defaults and **is** the prod config (no
  `application-prod.yml`; prod activates no extra profile). `application-dev.yml` is the **only**
  overlay; the dev container sets `SPRING_PROFILES_ACTIVE=dev`.
- **Rationale**: Spring always loads the base; a profile file overlays only deltas. Prod-safe
  defaults already live in `application.yml` (`ddl-auto: validate`, `discord.enabled: true`, Flyway
  on); `application-dev.yml` only adds `show-sql` + verbose logging. A near-empty `application-prod.yml`
  would add a file without adding information.
- **Alternatives considered**: Explicit symmetric `dev`/`prod` profiles — rejected by the maintainer
  (Q2) as unnecessary ceremony for one overlay.

## R3. Single source of truth for non-secret connection values

- **Decision**: Each non-secret connection value (DB name, DB username, in-cluster host/port) is
  declared **once** in the compose file and injected as environment into **both** the `postgres`
  container (`POSTGRES_DB`/`POSTGRES_USER`) and the `app` container (`DB_URL`/`DB_USERNAME`).
  `application.yml` references them only via `${DB_URL}`/`${DB_USERNAME}` and never restates a
  literal.
- **Rationale**: Prevents Docker/Spring drift (FR-015/SC-008). Today `compose.yaml` (dev) hardcodes
  `POSTGRES_DB: coiny` / `POSTGRES_USER: coiny` as literals while `compose.prod.yaml` parameterizes
  them — the dev `app` service must consume the *same* values the dev `postgres` uses. Use a single
  literal/anchor per value within each compose file so the two services cannot disagree.
- **Implementation note**: `compose.prod.yaml` already routes `DB_NAME`/`DB_USERNAME` through both
  services with defaults. For dev, define the db name/user once (YAML anchor or a single literal
  reused by both services) and build the app's `DB_URL` from it as
  `jdbc:postgresql://postgres:5432/<db>`. **Secrets** (`DB_PASSWORD`, `DISCORD_TOKEN`) are *not*
  given compose defaults — they must come from the exported environment the scripts set.
- **Alternatives considered**: A committed `.env` shared by compose + app — rejected: forbidden by
  the spec/constitution and the very drift/leak risk this feature removes. Restating the URL inside
  `application-dev.yml` — rejected: creates a second editable copy (violates FR-015).

## R4. In-container DB host — service name, not `localhost`

- **Decision**: The app container reaches Postgres at `jdbc:postgresql://postgres:5432/<db>` (the
  compose service name) on the internal network port `5432`, **regardless** of the host-published
  port (dev `5432`, prod `5433`).
- **Rationale**: Inside the compose network, `localhost` is the app container itself. The published
  host port differs per environment, but the internal service port is always `5432`. The current
  `.env` sets `DB_URL=...localhost:5432...` because the app used to run on the host — that premise is
  gone now that the app runs as a container.
- **Alternatives considered**: `host.docker.internal` — rejected: routes out to the host instead of
  the sibling container; wrong topology and platform-fragile.

## R5. Startup ordering & fail-fast on wrong password

- **Decision**: The dev `app` service uses `depends_on: { postgres: { condition: service_healthy } }`
  (matching prod). With a non-matching password, the JDBC connection fails at boot and the app exits
  non-zero — surfacing a clear DB authentication error and leaving nothing half-started (FR-014).
- **Rationale**: The existing healthcheck (`pg_isready`) gates app start on DB readiness; an auth
  failure is then a clean, immediate boot failure rather than a hang. Postgres only sets its password
  when its data volume is first created, so a "no-reset" run with a new password authenticates
  against the *old* volume password and fails fast — exactly the desired behavior.
- **Alternatives considered**: App-side connection retry loop — rejected: would mask the wrong-password
  case the spec wants to fail fast (US2-4).

## R6. Launch scripts — bash is the single source; PowerShell wrappers are thin

- **Decision**: All prompt/reset/up logic lives in `scripts/up-dev.sh` and `scripts/up-prod.sh`
  (POSIX bash, **LF** endings, `chmod +x`). `up-dev.sh` asks `reset database? (y/N)` **before**
  prompting for the password; "yes" runs `docker compose -f compose.yaml down -v` then up. Both
  scripts prompt for `DB_PASSWORD` (visible) and `DISCORD_TOKEN` (`read -s`, hidden), reject blanks
  and re-prompt, `export` them, then `docker compose -f <file> up --build`. PowerShell wrappers
  (`up-dev.ps1`, `up-prod.ps1`) only translate the path and invoke the `.sh` via `wsl`.
- **Rationale**: One authored flow avoids dev/prod and bash/PowerShell drift. Running the prompts
  *inside* WSL means secrets never cross the PowerShell→WSL boundary as variables (Assumptions). LF
  endings are mandatory or `bash` under WSL fails on CRLF.
- **Failure surfaces**: wrappers detect missing WSL / unreachable Docker daemon and print a clear
  error instead of hanging (Edge Cases). Secrets are never echoed, written to a file, or passed as
  argv (which would leak via process listings) — they are exported env in the script's own shell.
- **Alternatives considered**: Re-implement the flow natively in PowerShell — rejected: duplicates
  logic and risks behavioral drift. Pass secrets as `wsl` command arguments — rejected: visible in
  process tables / shell history.

## R7. `.env` / `.env.example` removal & ignore hygiene

- **Decision**: Delete `.env` **and** `.env.example`. Keep `.env` in `.gitignore` (defense in depth)
  and `.dockerignore`. Remove any remaining instruction that tells a user to "copy `.env.example` to
  `.env`".
- **Rationale**: The spec forbids a `.env` in the repo (FR-002/FR-015/SC-002) and the dotenv
  workflow is gone. `.env` was verified **never committed** (git-ignored), so no history scrub is
  needed — but the on-disk file holds a **real** token + password and must be deleted; the maintainer
  should also rotate that token/password since it existed in plaintext on disk.
- **Alternatives considered**: Keep `.env.example` as documentation — rejected: it advertises the
  forbidden dotenv path and risks re-creating `.env`; the scripts + `contracts/environment.md` now
  document required variables instead.

## R8. No new dependencies; tests unchanged

- **Decision**: Add nothing to `pom.xml`. Tooling uses Docker Compose, `docker`, `wsl`, and bash —
  not Java/Maven libraries. The test profile already disables Discord and sources Postgres from
  Testcontainers, so `./mvnw verify` stays green with zero secrets.
- **Rationale**: Constitution Principle VI + Dev Workflow gate; the feature is infra/config only.
- **Alternatives considered**: A dotenv library for the app — explicitly forbidden (Principle VII).
