[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$serviceName = 'PersonalMemoAppCloudflareTunnel'
$calendarServiceName = 'PersonalMemoCalendarCloudflareTunnel'
$installRoot = 'C:\ProgramData\PersonalMemo\AppCloudflare'
$installedExe = Join-Path $installRoot 'cloudflared.exe'
$tokenFile = Join-Path $installRoot 'tunnel-token.txt'
$metricsAddress = '127.0.0.1:49313'

function Assert-Administrator {
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run from an elevated Windows PowerShell session.' }
}
function Get-AllowedPids {
    $result = @()
    $calendar = Get-CimInstance Win32_Service -Filter "Name='$calendarServiceName'" -ErrorAction SilentlyContinue
    if ($null -ne $calendar -and $calendar.State -eq 'Running' -and $calendar.ProcessId -gt 0) { $result += [int]$calendar.ProcessId }
    return $result
}
function Assert-NoUnexpectedCloudflared {
    $allowed = @(Get-AllowedPids)
    $all = @([Diagnostics.Process]::GetProcessesByName('cloudflared'))
    try {
        if (@($all | Where-Object { $allowed -notcontains $_.Id }).Count -gt 0) { throw 'A cloudflared process other than the known calendar connector remains running.' }
    }
    finally { foreach ($process in $all) { $process.Dispose() } }
}
function Test-PinnedServiceDefinition([object] $Service) {
    $expectedPath = ('"{0}" tunnel --no-autoupdate --loglevel warn --transport-loglevel warn --grace-period 30s --metrics {1} run --token-file "{2}"' -f $installedExe, $metricsAddress, $tokenFile)
    return $Service.StartName -eq 'LocalSystem' -and $Service.PathName -ceq $expectedPath
}

Assert-Administrator
$svc = Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction SilentlyContinue
if ($null -eq $svc) { Assert-NoUnexpectedCloudflared; Write-Host 'The app connector service is not installed.'; return }
$originalPid = [int]$svc.ProcessId
if (-not $PSCmdlet.ShouldProcess($serviceName, 'Stop the exact-name app connector and converge it to Manual startup before edge rollback')) { return }
if ($svc.State -ne 'Stopped') {
    Stop-Service -Name $serviceName -Force
}
$deadline = [DateTime]::UtcNow.AddSeconds(20)
do {
    $svc = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    if ($svc.State -eq 'Stopped') { break }
    Start-Sleep -Milliseconds 400
} while ([DateTime]::UtcNow -lt $deadline)
if ($svc.State -ne 'Stopped') { throw 'The app connector did not stop within the bounded window.' }
Set-Service -Name $serviceName -StartupType Manual
$svc = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
if ($svc.State -ne 'Stopped' -or $svc.StartMode -ne 'Manual') { throw 'The app connector did not converge to stopped/manual state.' }
if ($originalPid -gt 0 -and $null -ne (Get-Process -Id $originalPid -ErrorAction SilentlyContinue)) { throw 'The app connector process remains after service stop.' }
Assert-NoUnexpectedCloudflared
if (-not (Test-PinnedServiceDefinition $svc)) {
    throw 'The app connector is safely stopped/manual, but its path or LocalSystem identity drifted. Reinstall it before activation.'
}
Write-Host 'The app connector is stopped; the known calendar connector, if running, was left unchanged.'
