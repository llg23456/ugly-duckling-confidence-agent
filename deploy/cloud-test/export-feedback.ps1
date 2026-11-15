[CmdletBinding()]
param(
    [switch]$IncludeDeviceIds,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9.-]*$')][string]$Server = '47.83.165.140',
    [string]$IdentityFile = (Join-Path $env:USERPROFILE '.ssh\ugly_duckling_cloudtest'),
    [string]$KnownHostsFile = (Join-Path $env:USERPROFILE '.ssh\ugly_duckling_cloudtest_known_hosts'),
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot '..\..\artifacts\feedback'
}
foreach ($tool in @('ssh.exe','scp.exe')) { Get-Command $tool -ErrorAction Stop | Out-Null }
foreach ($path in @($IdentityFile,$KnownHostsFile)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "找不到 SSH 配置：$path" }
}
$IdentityFile = (Resolve-Path -LiteralPath $IdentityFile).Path
$KnownHostsFile = (Resolve-Path -LiteralPath $KnownHostsFile).Path
$directory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$name = "experience-feedback-$stamp.xlsx"
$localFile = Join-Path $directory $name
$options = @('-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes',
    '-o',"UserKnownHostsFile=$KnownHostsFile",'-o','ConnectTimeout=15','-i',$IdentityFile)
$identityFlag = if ($IncludeDeviceIds) { ' --include-device-ids' } else { '' }
$remoteCommand = 'set -eu; cd /opt/ugly-duckling-cloudtest/current; ' +
    'docker compose -f deploy/cloud-test/compose.yaml exec -T api python -m app.feedback_export ' +
    "--output /backups/feedback/$name$identityFlag"
Write-Host '正在导出云端问卷，包含填写时身份和服务器提交时间…'
& ssh.exe @options "root@$Server" $remoteCommand
if ($LASTEXITCODE -ne 0) { throw '服务器导出失败，请确认网络及已部署版本。' }
New-Item -ItemType File -Path $localFile -ErrorAction Stop | Out-Null
$acl = New-Object System.Security.AccessControl.FileSecurity
$acl.SetAccessRuleProtection($true,$false)
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$rule = New-Object System.Security.AccessControl.FileSystemAccessRule($sid,'FullControl','Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $localFile -AclObject $acl
& scp.exe @options "root@${Server}:/opt/ugly-duckling-cloudtest/backups/feedback/$name" $localFile
if ($LASTEXITCODE -ne 0) { throw "下载失败，服务器文件仍在 backups/feedback/$name；可重新下载，无需重复提交问卷。" }
if ((Get-Item -LiteralPath $localFile).Length -lt 1000) { throw '文件大小异常，请检查服务器导出结果。' }
Write-Host "已导出 Excel：$localFile"
Write-Host '明细保留所有提交，统计按每设备、每问卷版本最新一份计算。身份为填写时自述，时间以服务器为准。'
