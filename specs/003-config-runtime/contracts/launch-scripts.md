# Contract: Launch-Script CLI Interface

Four scripts under `scripts/`. The **bash** scripts hold all logic (single source of truth); the
**PowerShell** wrappers are thin and invoke the matching `.sh` through WSL. All bash scripts use
**LF** line endings and are executable (`chmod +x`).

## `scripts/up-dev.sh` (bash — dev)

**Interaction sequence (order is normative):**
1. Ask `reset database? (y/N)` — default **No**.
   - **Yes** → run `docker compose -f compose.yaml down -v` (wipe the dev volume), then continue.
   - **No** → continue without wiping.
2. Prompt `DB_PASSWORD` (visible input). Reject blank → re-prompt.
3. Prompt `DISCORD_TOKEN` with `read -s` (**hidden**, no echo). Reject blank → re-prompt.
4. `export DB_PASSWORD DISCORD_TOKEN` (and any non-secret values the compose file needs).
5. `docker compose -f compose.yaml up --build`.

**Guarantees**
- The reset prompt comes **before** the password prompt (a forgotten-password recovery path). *(FR-006, US2-2, FR-013)*
- No automatic wipe ever happens (only an explicit `y`). *(FR-006)*
- Secrets are never echoed, written to a file, or passed as command arguments. *(FR-004, SC-002)*
- Brings up app **and** database together. *(FR-009)*

## `scripts/up-prod.sh` (bash — prod)

**Interaction sequence:**
1. Prompt `DB_PASSWORD` (visible). Reject blank → re-prompt.
2. Prompt `DISCORD_TOKEN` with `read -s` (hidden). Reject blank → re-prompt.
3. `export` the secrets.
4. `docker compose -f compose.prod.yaml up --build`.

**Guarantees**
- **No** reset/wipe option is ever offered. *(FR-007)*
- Same hidden-token and no-leak guarantees as dev. *(FR-004)*
- Brings up app **and** database together on the prod port/volume. *(FR-009, SC-007)*

## `scripts/up-dev.ps1` and `scripts/up-prod.ps1` (PowerShell wrappers)

- **Thin**: translate the repo path to a WSL path and invoke the matching `.sh` via `wsl` so all
  prompts run **inside** WSL — prompted secrets never cross the PowerShell→WSL boundary as variables.
  *(Assumptions, FR-008)*
- Present the **same** prompts, reset behavior, and result as the shell flow. *(FR-008, US2-3)*
- **Preflight errors** (clear message, non-zero exit, no hang):
  - WSL not installed / no default distro reachable.
  - Docker daemon not reachable from WSL.
  *(Edge Cases — "WSL/Docker not ready on Windows")*

## Exit-status contract (all scripts)

| Outcome | Exit |
|---|---|
| Stack started (compose `up` ran) | `0` (or compose's own status on shutdown) |
| Blank secret after re-prompt limit / user abort | non-zero, nothing started |
| WSL or Docker unavailable (wrappers) | non-zero, clear message, nothing started |
| Wrong password (no reset) | compose starts, then app exits non-zero with a DB auth error; nothing left half-started *(FR-014)* |

## Non-goals
- The scripts do not store, cache, or default any secret.
- The scripts do not run the test suite (tests are `./mvnw verify` on the host).
- The scripts are not the build mechanism (the image build happens inside the Dockerfile via compose `--build`).
