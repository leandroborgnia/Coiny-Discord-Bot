# Contract: Runtime Configuration & Startup

Defines the environment-variable contract and the fail-fast startup behavior. No secret has a
committed default; `application.yml` references these via `${...}` placeholders only.

## Required environment variables

| Variable | Required | Used by | Description |
|----------|----------|---------|-------------|
| `DISCORD_TOKEN` | Yes | JDA | Bot authentication token. Never committed. Missing → fail fast. |
| `DISCORD_GUILD_ID` | Yes (dev/verify) | `SlashCommandRegistrar` | Test guild id for instant guild-command registration. |
| `DB_URL` | Yes | DataSource | JDBC URL to Postgres 17, e.g. `jdbc:postgresql://localhost:5432/coiny`. |
| `DB_USERNAME` | Yes | DataSource | Database user. |
| `DB_PASSWORD` | Yes | DataSource | Database password. Never committed. Missing → fail fast. |
| `SPRING_PROFILES_ACTIVE` | No | Spring | `dev` for local host run; unset/`prod` for container parity. |

- `.env.example` lists every variable above with placeholder values and is committed; the real
  `.env` is git-ignored. Compose files read from the environment / `.env`.

## Startup contract (fail-fast, no partial init)

The application MUST, in this order, before serving any command:

1. **Validate required secrets/config** — a missing or empty required value aborts startup with a
   clear message naming the variable (spec FR-007). The bot does not come online.
2. **Connect to Postgres** — if the DataSource cannot connect, startup aborts with an actionable
   error (spec FR-005). No command is served.
3. **Run Flyway migrations to current** — Flyway applies any pending `V<n>` migrations
   automatically. If migration fails, startup aborts (spec FR-004/FR-005). Applied migrations are
   immutable (Principle VII).
4. **Connect the Discord gateway and register commands** — only after the above succeed does the
   bot announce itself online (spec FR-010) and upsert `/ping`.

If any of steps 1–3 fail, the process exits non-zero and the bot never appears online — there is no
partially-initialized state (spec FR-005, edge cases).

## Restart contract

- Restarting against an existing, already-current data store succeeds with **no manual schema
  steps** and **no duplicate/corrupt history** — Flyway sees all migrations applied and proceeds
  (spec FR-009, SC-003). The idempotent `V1` seed (`ON CONFLICT DO NOTHING`) keeps the seed row
  singular across restarts.

## Profile / environment contract

- Differences between dev and prod are expressed **only** through `SPRING_PROFILES_ACTIVE`, env
  vars, and `compose.prod.yaml` — never through a second Dockerfile (Constitution Containerization).
- Dev: app runs on the host (`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`) against the
  dockerized Postgres from `compose.yaml`.
- Prod/parity: app + Postgres both via `compose.prod.yaml`, with a **separate** named volume and
  ports from dev (never shared).
