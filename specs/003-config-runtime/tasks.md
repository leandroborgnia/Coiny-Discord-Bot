---
description: "Task list for Configuration Consolidation & Containerized Dev/Prod Runtime"
---

# Tasks: Configuration Consolidation & Containerized Dev/Prod Runtime

**Input**: Design documents from `/specs/003-config-runtime/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: No automated test tasks are generated — none were requested and this feature is
**behavior-preserving**. The regression gate is the existing suite (`./mvnw verify`), which MUST
stay green with **zero** secrets. Per-story validation uses the runnable scenarios in
[`quickstart.md`](./quickstart.md).

**Organization**: Tasks are grouped by user story (US1 P1, US2 P2, US3 P3) for independent
implementation and testing.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (Setup, Foundational, and Polish carry no story label)
- Exact paths are repository-root relative.

## Path Conventions

Single-project layout (Discord bot). This feature adds **no** Java source — only `scripts/`,
compose edits, file deletions, and docs. Config stays in `src/main/resources/*.yml` and
`src/test/resources/application.yml` (YAML — never `.properties`).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project scaffolding and line-ending hygiene for the launch scripts.

- [X] T001 Create the `scripts/` directory at the repository root.
- [X] T002 [P] Add a `.gitattributes` rule forcing **LF** for shell scripts (e.g. `scripts/*.sh text eol=lf`) so they run correctly under WSL (Edge Case: shell-script line endings).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Config-surface and secret-hygiene work that every user story depends on.

**⚠️ CRITICAL**: No user story work should begin until this phase is complete.

- [X] T003 [P] Verify `src/main/resources/application.yml` holds production-safe defaults and references secrets + connection values **only** via `${DB_URL}`/`${DB_USERNAME}`/`${DB_PASSWORD}`/`${DISCORD_TOKEN}` — no literal connection value, no second copy (data-model INV-1..4, INV-7). Fix any literal found.
- [X] T004 [P] Verify `src/main/resources/application-dev.yml` is the **only** overlay (dev conveniences) and confirm repo-wide there is **no** `application-prod.yml` and **no** `application.properties` (FR-001, SC-005).
- [X] T005 Delete `.env` and `.env.example` from the repository root (FR-002, FR-015, SC-002). The dotenv workflow is removed; required variables are documented in `contracts/environment.md`.
- [X] T006 Update `.gitignore` and `.dockerignore`: keep `.env` ignored (defense in depth) and remove any stale "copy `.env.example` → `.env`" guidance so nothing advertises the forbidden dotenv path (research R7).

**Checkpoint**: Config is YAML-only, prod-safe, single-sourced; no dotenv artifacts remain.

---

## Phase 3: User Story 1 - Single config surface, app runs in a container (Priority: P1) 🎯 MVP

**Goal**: All Spring config is in `application.yml` (no `.properties`, no `.env`), and the app runs
as a container that resolves its secrets purely from environment variables.

**Independent Test**: With the required env vars exported, `docker compose -f compose.yaml up --build`
boots the app and connects to its DB; `./mvnw verify` passes with **no** secrets; inspection shows
no `application.properties` and no `.env`, secrets only as `${...}`.

- [X] T007 [US1] Add a dev `app` service to `compose.yaml` built from the existing `Dockerfile` (`build: .`, `image: coiny-bot`, `container_name: coiny-bot-dev`), with `depends_on: { postgres: { condition: service_healthy } }`, `SPRING_PROFILES_ACTIVE=dev`, and `DB_URL=jdbc:postgresql://postgres:5432/<db>` (where `<db>` is the single-sourced DB name `coiny` from T008; service name, **not** localhost) plus `DB_USERNAME`/`DB_PASSWORD`/`DISCORD_TOKEN` from the environment. Keep dev's existing port `5432` and volume `coiny-pgdata-dev` (data-model §C, research R4). **Also rewrite the `compose.yaml` header comment** — it currently says the app runs on the host via `./mvnw spring-boot:run`; replace it to describe the in-container dev app + the launch-scripts run path so the comment no longer contradicts FR-010 (analyze F1).
- [X] T008 [US1] Single-source the non-secret DB values in `compose.yaml` so `postgres` (`POSTGRES_DB`/`POSTGRES_USER`) and `app` (`DB_URL` db-name + `DB_USERNAME`) come from **one** declaration (YAML anchor or a single reused literal); ensure secrets (`DB_PASSWORD`, `DISCORD_TOKEN`) have **no** compose default (FR-015, SC-008, INV-5/INV-7).
- [ ] T009 [US1] Validate boot: export the four env vars, run `docker compose -f compose.yaml up --build`, and confirm `postgres` becomes healthy, then `app` boots, applies Flyway migrations, and connects (quickstart Scenario 3 manual variant; US1 Acceptance 1).
- [X] T010 [P] [US1] Validate `./mvnw -q verify` passes with **no** secrets set — Discord disabled in the test profile, Postgres from Testcontainers (quickstart Scenario 1; FR-011, SC-003, US1 Acceptance 2).
- [X] T011 [P] [US1] Validate config inspection: no `application.properties` anywhere, no `.env`/`.env.example`, and secrets appear only as `${...}` in `application.yml` (quickstart Scenario 2; US1 Acceptance 3, SC-002).

**Checkpoint**: MVP — config consolidated and the dev app container boots from env-injected secrets.

---

## Phase 4: User Story 2 - One-command local startup with reset escape hatch (Priority: P2)

**Goal**: Bring up app + DB locally with one command that prompts for secrets each run and offers a
forgotten-password reset.

**Independent Test**: On a fresh clone, run the dev launch command, decline reset, enter secrets →
app + DB come up; on a machine with an unknown local password, run again, accept reset, enter a new
password → clean start. Windows wrapper reproduces the same flow via WSL.

- [X] T012 [US2] Create `scripts/up-dev.sh` (bash, **LF**, `chmod +x`) per `contracts/launch-scripts.md`: ask `reset database? (y/N)` **first** (on `y` run `docker compose -f compose.yaml down -v`), then prompt `DB_PASSWORD` (visible, reject blank + re-prompt) and `DISCORD_TOKEN` (`read -s`, hidden, reject blank), `export` them, and run `docker compose -f compose.yaml up --build`. No secret echoed, written, or passed as an argument (FR-006, FR-013, US2-1/US2-2, FR-004).
- [X] T013 [US2] Create `scripts/up-dev.ps1` — a **thin** wrapper that translates the repo path and invokes `up-dev.sh` via `wsl` (prompts run inside WSL). Add preflight checks that print a clear error and exit non-zero (no hang) when WSL or the Docker daemon is unavailable; document the WSL-integration + LF prerequisites in the header (FR-008, US2-3, Edge Cases).
- [ ] T014 [P] [US2] Validate quickstart Scenario 3 (decline reset → app + DB up together) and Scenario 4 (accept reset → volume wiped, re-initialized with new password, clean start) (FR-006, FR-013).
- [ ] T015 [P] [US2] Validate quickstart Scenario 5 (no-reset + non-matching password → fail fast with a clear DB auth error, nothing half-started) and Scenario 6 (blank secret rejected, stack not started) (FR-014, Edge Cases).
- [ ] T016 [P] [US2] Validate quickstart Scenario 7 (`up-dev.ps1` reproduces the shell flow via WSL; clear error when WSL/Docker not ready) (FR-008, US2-3).

**Checkpoint**: Dev one-command startup + reset recovery work on Linux and Windows.

---

## Phase 5: User Story 3 - One-command production startup (Priority: P3)

**Goal**: Bring up the prod stack with one command that prompts for secrets each run and never
offers to wipe data.

**Independent Test**: Run the prod launch command, enter secrets → app + DB start on the prod port
(`5433`) and volume (`coiny-pgdata-prod`); confirm no reset/wipe prompt; dev and prod coexist.

- [X] T017 [US3] Review `compose.prod.yaml`: confirm non-secret values (`DB_NAME`/`DB_USERNAME`) are single-sourced into both `postgres` and `app`, that secrets have no default (`DB_PASSWORD`/`DISCORD_TOKEN` already `:?`-required), and align its approach with the dev `compose.yaml` from T008 (FR-015, SC-008).
- [X] T018 [US3] Create `scripts/up-prod.sh` (bash, **LF**, `chmod +x`) per `contracts/launch-scripts.md`: prompt `DB_PASSWORD` (visible, reject blank) and `DISCORD_TOKEN` (`read -s`, hidden, reject blank), `export`, run `docker compose -f compose.prod.yaml up --build`. **No** reset/wipe option is offered (FR-007, US3-1, FR-009).
- [X] T019 [US3] Create `scripts/up-prod.ps1` — a thin wrapper invoking `up-prod.sh` via `wsl` with the same preflight WSL/Docker checks as `up-dev.ps1` (FR-008).
- [ ] T020 [P] [US3] Validate quickstart Scenario 8 (no reset prompt; prod `5433` + `coiny-pgdata-prod`) and Scenario 9 (dev + prod run simultaneously with no port/volume collision) (FR-007, SC-007).

**Checkpoint**: All three user stories independently functional on Linux and Windows.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation alignment and final whole-feature validation.

- [X] T021 Update `CLAUDE.md` so Docker-Compose-via-scripts is the documented run path for dev **and** prod (FR-010). Specifically: in **Prerequisites**, replace the `Local dev database` block's `docker compose up -d` / `down` / `down -v` bullets (L8-11) with the `scripts/up-dev.*` flow (note `down -v` is now reachable via the dev reset prompt); in **Build/Test/Run**, remove the *"Fast dev run (recommended inner loop) … `./mvnw spring-boot:run`"* bullet (L30-31) and point dev/prod runs at the launch scripts; keep `./mvnw verify` (host + Testcontainers) as the test path and state Maven is **not** the intended app-run path. Leave the *"What I Never Want You to Do"* / *Docker strategy* sections intact (they remain valid).
- [X] T022 [P] Update `README.md` run instructions to match the launch scripts; remove `.env`/`.env.example` references and any "copy the example env" steps.
- [ ] T023 [P] Run the full `quickstart.md` validation end to end, including Scenario 10 (product behavior unchanged: `/ping`, `/balance`, `/coins-adjust`, `/coins-config`) (FR-012, SC-006).
- [X] T024 Final gate: confirm no real secret is committed (`git grep` for token/password patterns), `./mvnw -q verify` is green, and the repo contains no `application.properties` and no `.env` (SC-002, SC-003, SC-005).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately.
- **Foundational (Phase 2)**: depends on Setup — **BLOCKS all user stories**.
- **US1 (Phase 3)**: depends on Foundational. The MVP.
- **US2 (Phase 4)**: depends on Foundational + US1 (its `up-dev.sh` drives the dev `app` service from T007/T008).
- **US3 (Phase 5)**: depends on Foundational; independent of US1/US2 (separate compose file + scripts) — can run in parallel with US2 once Foundational is done.
- **Polish (Phase 6)**: depends on the user stories it documents/validates.

### Within Each User Story

- Creation tasks before their validation tasks (e.g., T007/T008 before T009–T011; T012/T013 before T014–T016; T018/T019 before T020).
- Scripts depend on the compose config they invoke.

### Parallel Opportunities

- T002 (Setup) is [P] alongside T001's completion.
- T003 and T004 (Foundational) are [P] (different files, read/verify).
- Within US1: T010 and T011 are [P] (independent validations).
- Within US2: T014, T015, T016 are [P] (independent scenario validations).
- **US2 and US3 can proceed in parallel** after Foundational + US1 — they touch different files
  (`compose.yaml`/`up-dev.*` vs `compose.prod.yaml`/`up-prod.*`).
- Polish: T022 and T023 are [P].

---

## Parallel Example: User Story 1 validations

```bash
# After T007/T008, run the independent US1 validations together:
Task: "T010 ./mvnw -q verify passes with no secrets set (quickstart Scenario 1)"
Task: "T011 Config inspection: no .properties, no .env, secrets only ${...} (quickstart Scenario 2)"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup → 2. Phase 2 Foundational (CRITICAL) → 3. Phase 3 US1.
4. **STOP & VALIDATE**: app boots as a dev container from env-injected secrets; `./mvnw verify`
   green with no secrets; no `.properties`/`.env`.

### Incremental Delivery

1. Setup + Foundational → config consolidated, dotenv removed.
2. US1 → containerized dev runtime (MVP).
3. US2 → one-command dev startup + reset (the daily driver).
4. US3 → one-command prod startup.
5. Polish → docs aligned, full quickstart validated.

### Parallel Team Strategy

After Foundational + US1: Developer A finishes US2 (dev scripts) while Developer B does US3 (prod
review + scripts) — different files, no cross-story conflicts.

---

## Notes

- [P] = different files, no dependency on an incomplete task.
- No automated test tasks: the regression gate is the existing `./mvnw verify` (T010, T024).
- **Behavior-preserving**: no `domain`/`application`/`infrastructure`/`discord` code, no Flyway
  migration, and no `pom.xml` dependency is added or changed.
- Bash scripts MUST use **LF** endings (T002 enforces it) and never echo/write/pass secrets as args.
- ⚠️ The deleted `.env` held a **real** token + password — rotate the Discord token and DB password
  out of band (not a repo task).
- Commit after each task or logical group.
