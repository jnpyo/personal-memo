[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)] [string] $PublicAppHostname,
    [string] $ProjectName = 'personal-memo-private-win',
    [string] $EnvFile
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $repoRoot '.env.personal' }
$composeFiles = @('compose.yaml', 'compose.prod.yaml', 'compose.personal.yaml', 'compose.public-app.yaml') | ForEach-Object { Join-Path $repoRoot $_ }
$connectorService = 'PersonalMemoAppCloudflareTunnel'
$calendarService = 'PersonalMemoCalendarCloudflareTunnel'

function Assert-Hostname([string] $Value) {
    if ($Value -cne $Value.ToLowerInvariant() -or $Value -notmatch '^(?!calendar\.)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.[a-z]{2,63}$') { throw 'PublicAppHostname must be an exact lower-case, non-calendar, single-label app hostname.' }
}
function Assert-ConnectorStopped {
    $svc = Get-CimInstance Win32_Service -Filter "Name='$connectorService'" -ErrorAction SilentlyContinue
    if ($null -ne $svc -and $svc.State -ne 'Stopped') { throw 'Rollback is connector-first. Stop the app connector before stopping the app edge.' }
    $allowed = @()
    $calendar = Get-CimInstance Win32_Service -Filter "Name='$calendarService'" -ErrorAction SilentlyContinue
    if ($null -ne $calendar -and $calendar.State -eq 'Running' -and $calendar.ProcessId -gt 0) { $allowed += [int]$calendar.ProcessId }
    $all = @([Diagnostics.Process]::GetProcessesByName('cloudflared'))
    try { if (@($all | Where-Object { $allowed -notcontains $_.Id }).Count -gt 0) { throw 'An unknown cloudflared process is running.' } }
    finally { foreach ($process in $all) { $process.Dispose() } }
}
function Test-LoopbackListener([int] $Port) {
    return @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq $Port -and $_.Address.ToString() -eq '127.0.0.1' }).Count -gt 0
}
function Invoke-Compose([string[]] $Tail) {
    $args = @('compose', '--env-file', $EnvFile, '-p', $ProjectName)
    foreach ($file in $composeFiles) { $args += @('-f', $file) }
    $args += $Tail
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed; its output was intentionally not repeated.' }
}

Assert-Hostname $PublicAppHostname
if ($ProjectName -cne 'personal-memo-private-win') { throw 'ProjectName must be exactly personal-memo-private-win.' }
foreach ($file in $composeFiles + @($EnvFile)) { if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required deployment file is missing: $file" } }
Assert-ConnectorStopped
if (-not $PSCmdlet.ShouldProcess($PublicAppHostname, 'Stop the loopback-only public app edge after connector rollback')) { return }
$previous = [Environment]::GetEnvironmentVariable('PUBLIC_APP_HOSTNAME', 'Process')
try {
    [Environment]::SetEnvironmentVariable('PUBLIC_APP_HOSTNAME', $PublicAppHostname, 'Process')
    Invoke-Compose @('stop', 'app-public-edge')
    if (Test-LoopbackListener 8788) { throw 'Port 127.0.0.1:8788 remains active after the app edge stop.' }
    Assert-ConnectorStopped
    Write-Host 'The app edge is stopped. The personal stack and calendar connector were left unchanged.'
}
finally { [Environment]::SetEnvironmentVariable('PUBLIC_APP_HOSTNAME', $previous, 'Process') }
