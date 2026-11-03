param(
    [ValidateSet('check', 'dev', 'test', 'http', 'database', 'browser-install', 'e2e')]
    [string]$Action = 'dev',
    [string]$Distribution = 'Ubuntu'
)

# Pass paths as separate arguments: checkout names can contain spaces and Chinese.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw 'Install WSL 2 and Ubuntu before running this launcher.'
}
& wsl.exe --distribution $Distribution --cd $repoRoot -- bash ./scripts/wsl-dev.sh $Action
exit $LASTEXITCODE
