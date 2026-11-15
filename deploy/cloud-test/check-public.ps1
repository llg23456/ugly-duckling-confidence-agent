[CmdletBinding()]
param([string]$ReportPath = '')

# Readiness and authentication checks only: no credentials, user data or AI calls.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.AllowAutoRedirect = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)
$origin = 'https://api.yaligo-growth.top'
$checks = @()
$health = $null
try {
    $cases = @(
        @{ Path='/health'; Expected=200 },
        @{ Path='/'; Expected=200 },
        @{ Path='/docs'; Expected=404 },
        @{ Path='/openapi.json'; Expected=404 },
        @{ Path='/api/v1/conversations/readiness-no-auth/messages'; Expected=401 }
    )
    foreach ($case in $cases) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $response = $client.GetAsync($origin + $case.Path).GetAwaiter().GetResult()
        try {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $watch.Stop()
            $status = [int]$response.StatusCode
            $passed = $status -eq $case.Expected
            if ($case.Path -eq '/health' -and $status -eq 200) {
                $health = $body | ConvertFrom-Json
                $passed = $passed -and $health.status -eq 'ok' -and
                    $health.environment -eq 'production' -and
                    $health.authentication_required -eq $true -and
                    $health.ai_configured -eq $true -and $health.mock -eq $false
            }
            $checks += [pscustomobject]@{
                path=$case.Path; expected=$case.Expected; status=$status
                passed=$passed; elapsed_ms=$watch.ElapsedMilliseconds
            }
        } finally { $response.Dispose() }
    }
} finally { $client.Dispose(); $handler.Dispose() }

$report = [pscustomobject]@{
    checked_at=[DateTimeOffset]::UtcNow.ToString('o')
    origin=$origin; checks=$checks; health=$health
    passed=(@($checks | Where-Object { -not $_.passed }).Count -eq 0)
    scope='Public HTTPS and unauthenticated boundary only; no SSH, authenticated business request or real model call.'
}
$json = $report | ConvertTo-Json -Depth 5
if ($ReportPath) {
    $target = [IO.Path]::GetFullPath($ReportPath)
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    [IO.File]::WriteAllText($target, $json + [Environment]::NewLine,
        (New-Object System.Text.UTF8Encoding($false)))
}
Write-Output $json
if (-not $report.passed) { throw 'A public readiness check failed; inspect the status codes above.' }
