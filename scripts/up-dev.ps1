# Thin Windows wrapper (003-config-runtime): runs scripts/up-dev.sh INSIDE WSL so every prompt —
# and every secret — stays in WSL and never crosses the PowerShell boundary as a variable.
# WSL inherits this script's working directory (auto-translated to /mnt/...), so the bash launcher
# and the compose files resolve normally — no path juggling needed.
# Prerequisites: WSL + a bash distro, Docker Desktop with WSL integration. up-dev.sh must use LF
# line endings (.gitattributes enforces this). Docker readiness is checked by up-dev.sh itself.
$ErrorActionPreference = 'Stop'

if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
  Write-Error "WSL not found. Install WSL + a bash distro and enable Docker Desktop WSL integration."
  exit 1
}

# Start from the repo root (parent of scripts/) so WSL begins there and ./scripts/up-dev.sh resolves.
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Push-Location $repoRoot
try {
  wsl.exe bash ./scripts/up-dev.sh
  $code = $LASTEXITCODE
} finally {
  Pop-Location
}
exit $code
