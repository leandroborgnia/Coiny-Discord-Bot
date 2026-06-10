# Contract: Environment-Variable Interface

The runtime contract between Docker Compose (the **owner/injector** of values) and the Spring
application (the **consumer**). This replaces the deleted `.env` / `.env.example` as the
authoritative list of what the app needs at run time. No value here is ever committed or cached to
disk; secrets exist only as exported environment in a launch script's shell.

## Variables

### `DB_URL` — non-secret
- **Owner**: the `app` service in the compose file.
- **Value**: `jdbc:postgresql://postgres:5432/<db>` — host is the **compose service name**
  `postgres` on the **internal** port `5432` (never `localhost`, never the published host port).
- **Consumed by**: app → `spring.datasource.url`.
- **Default**: present in compose (built from the single-source DB name).

### `DB_USERNAME` + DB name — non-secret
- **Owner**: declared **once** per compose file and injected into **both** services.
- **Consumed by**: `postgres` (`POSTGRES_USER` / `POSTGRES_DB`) **and** app (`DB_USERNAME`, and the
  name embedded in `DB_URL`).
- **Default**: `coiny` / `coiny`.
- **Rule**: the two services MUST receive the same value from the same declaration (no second copy).

### `DB_PASSWORD` — **secret**
- **Owner**: **prompted by the launch script** each run; exported into the compose invocation.
- **Consumed by**: `postgres` (`POSTGRES_PASSWORD`, set only when the volume is first created) **and**
  app (`spring.datasource.password`).
- **Default**: **none.** Compose MUST NOT supply a default. A blank value is rejected by the script.
- **Note**: on a "no-reset" dev run or any prod run, the entered value must match the password baked
  into the existing volume; a mismatch fails fast at app boot (see auth-error behavior).

### `DISCORD_TOKEN` — **secret**
- **Owner**: **prompted by the launch script** each run (entered hidden, `read -s`).
- **Consumed by**: app → `discord.token` (only read when `discord.enabled=true`).
- **Default**: **none.** Never committed, never written to a file, never passed as a process argument.

### `SPRING_PROFILES_ACTIVE` — non-secret
- **Owner**: the `app` service.
- **Value**: `dev` in `compose.yaml`; **unset** in `compose.prod.yaml` (base `application.yml` is the
  prod configuration).

## Resolution & failure rules

| Situation | Required behavior |
|---|---|
| All vars present, correct password | App boots, applies Flyway migrations, connects. *(US1-1)* |
| No vars set, running `./mvnw verify` | Test suite passes — Discord disabled, Postgres from Testcontainers. *(FR-011, SC-003)* |
| Wrong `DB_PASSWORD` vs existing volume (no reset) | Fail fast with a clear DB authentication error; nothing left half-started. *(FR-014, US2-4)* |
| Blank `DB_PASSWORD` or `DISCORD_TOKEN` entered | Script rejects and re-prompts; the stack is not started. *(Edge Cases)* |
| Secret in repo or on-disk file | Forbidden — verifiable by inspection. *(FR-004, SC-002)* |

## Single-source-of-truth rule (FR-015 / SC-008)

Each non-secret connection value (DB name, `DB_USERNAME`, in-cluster host/port) has exactly **one**
definition in the compose file, injected into both `postgres` and `app`. `application.yml`
**references** these via `${...}` and never restates them. No value is maintained in two
independently-editable places, so Docker and Spring cannot drift.
