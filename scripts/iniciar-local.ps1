# Corre DBA Manager en esta máquina, sin Docker (Windows PowerShell).
# Requisitos: Python 3.11+ y Node.js 24.22.1+ (o 22.23.4+ / 26.12.1+, con parches de seguridad). Configuración en .env (copiar de .env.example).
#   .\scripts\iniciar-local.ps1            # instala lo necesario (la primera vez) y arranca
#   .\scripts\iniciar-local.ps1 -Build     # fuerza volver a compilar el frontend
param([switch]$Build)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-not (Test-Path ".env")) { throw "No existe .env: cópialo de .env.example y completa las variables" }
$py = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $py) { throw "Falta Python 3.11 o superior" }
& python -c "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)"
if ($LASTEXITCODE -ne 0) { throw "Se requiere Python 3.11+" }

$venvPy = "backend\.venv\Scripts\python.exe"
if (-not (Test-Path $venvPy)) {
  Write-Host ">> Creando entorno de Python (backend\.venv)"
  & python -m venv backend\.venv
}
Write-Host ">> Instalando dependencias de Python"
& $venvPy -m pip install -q --disable-pip-version-check -r backend\requirements.txt

if ($Build -or -not (Test-Path "frontend\dist\index.html")) {
  if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw "Falta Node.js 24.22.1+ (npm) para compilar el frontend" }
  & node scripts\node-version-check.js; if ($LASTEXITCODE -ne 0) { exit 1 }
  Write-Host ">> Compilando el frontend"
  Push-Location frontend; npm ci --no-audit --no-fund; npm run build; Pop-Location
}

# PORT y LOG_LEVEL se pueden definir en .env (el resto lo lee la aplicación directamente de .env)
function Get-EnvValue($name, $default) {
  $line = Select-String -Path ".env" -Pattern "^$name=" | Select-Object -Last 1
  if ($line) { return ($line.Line -split "=", 2)[1] } else { return $default }
}
$port = Get-EnvValue "PORT" "8080"
$log = Get-EnvValue "LOG_LEVEL" "info"
$proxies = Get-EnvValue "FORWARDED_ALLOW_IPS" "127.0.0.1"

Set-Location backend
$env:PATH = "$PWD\.venv\Scripts;$env:PATH"
& python -m app.check; if ($LASTEXITCODE -ne 0) { exit 1 }
& alembic upgrade head; if ($LASTEXITCODE -ne 0) { exit 1 }
& python -m app.seed
Write-Host ">> DBA Manager en http://localhost:$port"
& uvicorn app.main:app --host 0.0.0.0 --port $port --proxy-headers --forwarded-allow-ips $proxies --log-level $log --no-server-header
