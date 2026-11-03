param(
    [string]$ExpectedRemoteUrl = 'git@github.com:NICERTYU/campus-resource-platform.git',
    [string]$ExpectedBranch = 'snj-m3-business'
)

# Run manually after configuring this checkout; this does not contact the network.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$checks = @(
    @{ Name = 'origin fetch URL'; Args = @('remote', 'get-url', 'origin'); Expected = $ExpectedRemoteUrl },
    @{ Name = 'origin push URL'; Args = @('remote', 'get-url', '--push', 'origin'); Expected = $ExpectedRemoteUrl },
    @{ Name = 'current branch'; Args = @('branch', '--show-current'); Expected = $ExpectedBranch },
    @{ Name = 'tracking branch'; Args = @('rev-parse', '--abbrev-ref', '@{upstream}'); Expected = "origin/$ExpectedBranch" },
    @{ Name = 'default push remote'; Args = @('config', '--local', '--get', 'remote.pushDefault'); Expected = 'origin' },
    @{ Name = 'branch push remote'; Args = @('config', '--local', '--get', "branch.$ExpectedBranch.pushRemote"); Expected = 'origin' }
)

foreach ($check in $checks) {
    $gitArgs = $check.Args
    $output = @(& git -C $repoRoot @gitArgs)
    if ($LASTEXITCODE -ne 0) {
        throw "$($check.Name): git failed with exit code $LASTEXITCODE"
    }
    $actual = $output -join "`n"
    if ($actual -cne $check.Expected) {
        throw "$($check.Name): expected '$($check.Expected)', got '$actual'"
    }
    Write-Output "PASS: $($check.Name)"
}
