param(
    [int]$Port = 18010,
    [string]$Key = "$env:USERPROFILE\.ssh\ugly_duckling_cloudtest",
    [string]$KnownHosts = "$env:USERPROFILE\.ssh\ugly_duckling_cloudtest_known_hosts"
)
$ErrorActionPreference = 'Stop'
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Invalid local port' }
if (!(Test-Path -LiteralPath $Key) -or !(Test-Path -LiteralPath $KnownHosts)) {
    throw 'The project SSH key and verified known_hosts file are required.'
}
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "Local port $Port is occupied. Choose a different -Port; no existing process was changed."
}
$arguments = @('-N', '-T', '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
    '-o', 'StrictHostKeyChecking=yes', '-o', 'ExitOnForwardFailure=yes',
    '-o', 'ServerAliveInterval=30', '-o', "UserKnownHostsFile=`"$KnownHosts`"",
    '-i', "`"$Key`"", '-L', "127.0.0.1:${Port}:127.0.0.1:8000", 'root@47.83.165.140')
$process = Start-Process -FilePath 'ssh.exe' -ArgumentList $arguments -WindowStyle Hidden -PassThru
$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if ($process.HasExited) { throw 'SSH tunnel could not start. Check SSH access.' }
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { $ready = $true; break }
    Start-Sleep -Milliseconds 200
}
if (!$ready) { Stop-Process -Id $process.Id; throw 'Tunnel startup timed out.' }
$url = "http://127.0.0.1:$Port/community-admin/"
Write-Host "Private admin: $url"
Write-Host "Tunnel PID: $($process.Id). Close it with: Stop-Process -Id $($process.Id)"
Start-Process $url
