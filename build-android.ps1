param([ValidateSet('arm64','armv7','universal','all')][string]$Target='all')
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try { python scripts/build.py $Target; if ($LASTEXITCODE -ne 0) { throw 'Build failed' } } finally { Pop-Location }
