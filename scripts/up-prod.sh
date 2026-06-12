#!/usr/bin/env bash
# Prod launcher (003-config-runtime). Prompts for secrets and brings the app + Postgres up together
# via Docker Compose. NEVER offers a reset/wipe option. This bash script is the SINGLE SOURCE OF
# TRUTH for the prod prompt+up flow; the PowerShell wrapper (up-prod.ps1) only invokes it via WSL.
# Requires Docker running. MUST use LF line endings (enforced by .gitattributes) to run under WSL.
set -euo pipefail

COMPOSE_FILE="compose.prod.yaml"
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

# --- secret prompts (reject blanks, re-prompt; token entered hidden). No reset/wipe in prod. ---
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

# Optional secret: blank/Enter is accepted (the feature degrades gracefully when unset).
prompt_optional() {  # $1=var name  $2=label  $3=hidden(1/0)
  local _val=""
  if [ "$3" = "1" ]; then
    read -r -s -p "$2 (optional — Enter to skip): " _val; echo
  else
    read -r -p "$2 (optional — Enter to skip): " _val
  fi
  printf -v "$1" '%s' "$_val"
}

prompt_secret DB_PASSWORD   "Database password"      0
prompt_secret DISCORD_TOKEN "Discord token (hidden)" 1
# IGDB cover-art credentials (Twitch OAuth). Blank => the art resolver is a disabled no-op
# (name-only rendering) — everything else works.
prompt_optional IGDB_CLIENT_ID     "IGDB client id"              0
prompt_optional IGDB_CLIENT_SECRET "IGDB client secret (hidden)" 1
export DB_PASSWORD DISCORD_TOKEN IGDB_CLIENT_ID IGDB_CLIENT_SECRET

# --- bring up app + database together ---
echo "Starting prod stack (app + Postgres)..."
exec docker compose -f "$COMPOSE_FILE" up --build
