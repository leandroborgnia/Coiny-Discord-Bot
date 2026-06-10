#!/usr/bin/env bash
# Dev launcher (003-config-runtime). Prompts for secrets, optionally resets the dev DB, and brings
# the app + Postgres up together via Docker Compose. This bash script is the SINGLE SOURCE OF TRUTH
# for the dev prompt+up flow; the PowerShell wrapper (up-dev.ps1) only invokes it through WSL.
# Requires Docker running. MUST use LF line endings (enforced by .gitattributes) to run under WSL.
set -euo pipefail

COMPOSE_FILE="compose.yaml"
cd "$(dirname "$0")/.."   # repo root (scripts/ is one level down)

# --- preflight: Docker daemon reachable ---
if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker not found on PATH. Install Docker / enable Docker Desktop WSL integration." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon not reachable. Start Docker Desktop / the Docker service and retry." >&2
  exit 1
fi

# --- reset prompt (BEFORE the password prompt: forgotten-password escape hatch) ---
read -r -p "Reset database? This WIPES the dev volume and its password. (y/N) " reset_ans
case "${reset_ans}" in
  y|Y|yes|YES)
    echo "Wiping the dev database volume..."
    # `down -v` only tears down, but compose still parses the file's ${...:?} guards, which demand
    # values at interpolation time — before the prompts have run (reset is intentionally asked
    # first). Pass throwaway values for THIS teardown only; the wiped volume's real password is
    # destroyed anyway and these are never used to authenticate.
    DB_PASSWORD=reset DISCORD_TOKEN=reset docker compose -f "$COMPOSE_FILE" down -v
    ;;
  *)
    echo "Keeping the existing dev database (enter its current password below)."
    ;;
esac

# --- secret prompts (reject blanks, re-prompt; token entered hidden) ---
prompt_secret() {  # $1=var name  $2=label  $3=hidden(1/0)
  local _val=""
  while true; do
    if [ "$3" = "1" ]; then
      read -r -s -p "$2: " _val; echo
    else
      read -r -p "$2: " _val
    fi
    [ -n "$_val" ] && break
    echo "  $2 cannot be blank — try again." >&2
  done
  printf -v "$1" '%s' "$_val"
}

prompt_secret DB_PASSWORD   "Database password"      0
prompt_secret DISCORD_TOKEN "Discord token (hidden)" 1
export DB_PASSWORD DISCORD_TOKEN

# --- bring up app + database together ---
echo "Starting dev stack (app + Postgres)..."
exec docker compose -f "$COMPOSE_FILE" up --build
