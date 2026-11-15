[CmdletBinding()]
param(
    [ValidateRange(0, 200)][int]$Count = 0,
    [ValidateRange(1, 30)][int]$InviteDays = 7,
    [ValidateRange(1, 90)][int]$AccessDays = 30,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9.-]*$')][string]$Server = '47.83.165.140',
    [string]$IdentityFile = (Join-Path $env:USERPROFILE '.ssh\ugly_duckling_cloudtest'),
    [string]$KnownHostsFile = (Join-Path $env:USERPROFILE '.ssh\ugly_duckling_cloudtest_known_hosts'),
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot '..\..\artifacts\cloud-test\invites'
}
if ($Count -eq 0) {
    $answer = Read-Host '请输入本批邀请码数量（直接回车生成 20 个）'
    if ([string]::IsNullOrWhiteSpace($answer)) { $Count = 20 }
    elseif ($answer -match '^\d{1,3}$' -and [int]$answer -ge 1 -and [int]$answer -le 200) { $Count = [int]$answer }
    else { throw '数量必须为 1 到 200。' }
}
if ($InviteDays -gt $AccessDays) { throw '兑换期限不能长于测试授权期限；两者均从生成时开始计算。' }
foreach ($tool in @('ssh.exe', 'scp.exe')) { Get-Command $tool -ErrorAction Stop | Out-Null }
foreach ($path in @($IdentityFile, $KnownHostsFile)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "找不到 SSH 配置文件：$path。请先配置部署用密钥及已核验的服务器主机记录。" }
}
$IdentityFile = (Resolve-Path -LiteralPath $IdentityFile).Path
$KnownHostsFile = (Resolve-Path -LiteralPath $KnownHostsFile).Path
$directory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$batch = 'test-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$remoteFile = "/root/cloud-invites/$batch.csv"
$localFile = Join-Path $directory "$batch.csv"
$options = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
    '-o', "UserKnownHostsFile=$KnownHostsFile", '-o', 'IdentitiesOnly=yes',
    '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=15', '-i', $IdentityFile)

# Fixed command plus validated integers and a locally generated ASCII batch name.
# Keep a root-only server copy so an interrupted download can be retried without issuing new codes.
$remoteCommand = "set -eu; umask 077; mkdir -p /root/cloud-invites; cd /opt/ugly-duckling-cloudtest/current; " +
    "test -r /opt/ugly-duckling-cloudtest/tools/cloud_invites.py; " +
    "set -C; docker compose -f deploy/cloud-test/compose.yaml exec -T api python - " +
    "--label-prefix $batch --count $Count --invite-days $InviteDays --access-days $AccessDays " +
    "< /opt/ugly-duckling-cloudtest/tools/cloud_invites.py > $remoteFile"
Write-Host "正在生成 $Count 个邀请码：$InviteDays 天内兑换，测试授权为生成后 $AccessDays 天。"
Write-Host "本批服务器清单：$remoteFile"
& ssh.exe @options "root@$Server" $remoteCommand
if ($LASTEXITCODE -ne 0) { throw "服务器生成失败。请检查网络或服务器版本。若连接中断，请先检查上述服务器清单，避免重复生成。" }

# Restrict the file before any invitation plaintext is downloaded (Windows PowerShell 5.1+).
New-Item -ItemType File -Path $localFile -ErrorAction Stop | Out-Null
try {
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $localFile -AclObject $acl
    & scp.exe @options "root@${Server}:$remoteFile" $localFile
    if ($LASTEXITCODE -ne 0) { throw '清单下载失败，服务器副本仍保留；不要重新生成，请从上述路径重新下载。' }
    $rows = @(Import-Csv -LiteralPath $localFile -Encoding UTF8)
    if ($rows.Count -ne $Count) { throw '清单数量不一致，请保留文件并联系维护者核验。' }
    foreach ($row in $rows) {
        if ($row.invite_code -notmatch '^[A-F0-9]{20}$' -or !$row.label.StartsWith("$batch-")) {
            throw '清单格式异常，请保留文件并联系维护者核验。'
        }
    }
} catch {
    Write-Host "已保留本批服务器文件：$remoteFile"
    throw
}
Write-Host ''
Write-Host "已完成，清单：$localFile"
Write-Host '用 Excel 打开，将每个邀请码单独发给一名新测试者；recipient 和 note 列可填写领取人及备注。'
Write-Host "兑换截止：$($rows[0].redeem_before)；授权到期：$($rows[0].access_until)（均为北京时间）。"
Write-Host '请保管完整清单，避免整份转发。已有云测数据的设备恢复授权仍需生成设备专用邀请码。'
