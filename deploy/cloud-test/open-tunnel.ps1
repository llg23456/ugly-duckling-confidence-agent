param(
    [string]$Server = '47.83.165.140',
    [int]$LocalPort = 18000
)
$ErrorActionPreference = 'Stop'
$deployKey = Join-Path $env:USERPROFILE '.ssh\ugly_duckling_cloudtest'
$knownHosts = Join-Path $env:USERPROFILE '.ssh\ugly_duckling_cloudtest_known_hosts'
if (!(Test-Path -LiteralPath $deployKey) -or !(Test-Path -LiteralPath $knownHosts)) {
    throw 'Project SSH key or verified known-hosts file is missing on this computer.'
}
Write-Host "Cloud API: http://127.0.0.1:$LocalPort ; keep this terminal open. Ctrl+C stops the tunnel."
& ssh -N -T -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes `
    -o "UserKnownHostsFile=$knownHosts" -o ExitOnForwardFailure=yes `
    -o ServerAliveInterval=15 -o ServerAliveCountMax=3 `
    -i $deployKey -L "127.0.0.1:${LocalPort}:127.0.0.1:8000" "root@$Server"
if ($LASTEXITCODE -ne 0) { throw "SSH tunnel exited with code $LASTEXITCODE" }
