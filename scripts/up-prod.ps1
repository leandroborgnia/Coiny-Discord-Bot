# Thin Windows wrapper (003-config-runtime): runs scripts/up-prod.sh INSIDE WSL so every prompt —
# and every secret — stays in WSL and never crosses the PowerShell boundary as a variable.
# Prerequisites:
#   - WSL installed with a bash-capable distro.
#   - Docker Desktop running with WSL integration enabled.
#   - up-prod.sh has LF line endings (.gitattributes enforces this).
$ErrorActionPreference = 'Stop'

# --- preflight: WSL present ---
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
  Write-Error "WSL not found. Install WSL + a bash distro and enable Docker Desktop WSL integration."
  exit 1
}

# Resolve repo root (parent of scripts/) and translate to a WSL path.
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $scriptDir
$wslRepo   = (& wsl.exe wslpath -a "$repoRoot")
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslRepo)) {
  Write-Error "Could not translate '$repoRoot' to a WSL path. Is WSL working?"
  exit 1
}

# --- preflight: Docker reachable from inside WSL ---
& wsl.exe docker info *> $null
if ($LASTEXITCODE -ne 0) {
  Write-Error "Docker daemon not reachable from WSL. Start Docker Desktop and enable WSL integration."
  exit 1
}

# --- run the bash launcher inside WSL (prompts happen there) ---
& wsl.exe bash -lc "cd '$wslRepo' && ./scripts/up-prod.sh"
exit $LASTEXITCODE
