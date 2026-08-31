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
$baseComposeFiles = @('compose.yaml', 'compose.prod.yaml', 'compose.personal.yaml') | ForEach-Object { Join-Path $repoRoot $_ }
$composeFiles = @($baseComposeFiles) + @(Join-Path $repoRoot 'compose.public-app.yaml')
$connectorService = 'PersonalMemoAppCloudflareTunnel'
$calendarService = 'PersonalMemoCalendarCloudflareTunnel'

function Assert-Hostname([string] $Value) {
    if ($Value -cne $Value.ToLowerInvariant() -or $Value -notmatch '^(?!calendar\.)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.[a-z]{2,63}$') { throw 'PublicAppHostname must be an exact lower-case, non-calendar, single-label app hostname.' }
}
function Assert-ConnectorStopped {
    $svc = Get-CimInstance Win32_Service -Filter "Name='$connectorService'" -ErrorAction SilentlyContinue
    if ($null -ne $svc -and $svc.State -ne 'Stopped') { throw 'Stop the app connector before changing the app edge lifecycle.' }
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
function Invoke-ComposeFor([string[]] $Files, [string[]] $Tail) {
    $args = @('compose', '--env-file', $EnvFile, '-p', $ProjectName)
    foreach ($file in $Files) { $args += @('-f', $file) }
    $args += $Tail
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed; its output was intentionally not repeated.' }
}
function Invoke-Compose([string[]] $Tail) {
    Invoke-ComposeFor -Files $composeFiles -Tail $Tail
}
function Invoke-BaseCompose([string[]] $Tail) {
    Invoke-ComposeFor -Files $baseComposeFiles -Tail $Tail
}
function Invoke-DockerCapture([string[]] $Tail) {
    $output = @(& docker @Tail)
    if ($LASTEXITCODE -ne 0) { throw 'A bounded Docker inspection failed; its output was intentionally not repeated.' }
    return (@($output) -join "`n").Trim()
}
function Invoke-ComposeCapture([string[]] $Tail) {
    $args = @('compose', '--env-file', $EnvFile, '-p', $ProjectName)
    foreach ($file in $composeFiles) { $args += @('-f', $file) }
    $args += $Tail
    return Invoke-DockerCapture -Tail $args
}
function Get-OnlyLine([string] $Text, [string] $Description) {
    $lines = @($Text -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -ne 1) { throw "Expected exactly one $Description." }
    return $lines[0].Trim()
}
function Get-ContainerNetworkNames([string] $ContainerId) {
    $text = Invoke-DockerCapture @('inspect', '--format', '{{range $name, $value := .NetworkSettings.Networks}}{{println $name}}{{end}}', $ContainerId)
    return @($text -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() } | Sort-Object -Unique)
}
function Get-ExistingPublicationNetworks {
    $allNetworks = @((Invoke-DockerCapture @('network', 'ls', '--format', '{{.Name}}')) -split '\r?\n')
    $expected = @('personal-memo-private-win_app-publication', 'personal-memo-private-win_app-loopback')
    return @($expected | Where-Object { $allNetworks -contains $_ })
}
function Get-FrontendSnapshot {
    $containerId = Get-OnlyLine (Invoke-ComposeCapture @('ps', '--all', '-q', 'frontend')) 'existing personal frontend container'
    $state = Get-OnlyLine (Invoke-DockerCapture @('inspect', '--format', '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}', $containerId)) 'frontend state'
    if ($state -cne 'running|healthy') { throw 'The existing personal frontend must be running and healthy before publication.' }
    $imageReference = Get-OnlyLine (Invoke-DockerCapture @('inspect', '--format', '{{.Config.Image}}', $containerId)) 'frontend image reference'
    $imageId = Get-OnlyLine (Invoke-DockerCapture @('inspect', '--format', '{{.Image}}', $containerId)) 'frontend image ID'
    if ($imageReference -notmatch '^personal-memo-private-win-frontend(?::[A-Za-z0-9_.-]+)?$' -or $imageId -notmatch '^sha256:[0-9a-f]{64}$') {
        throw 'The existing frontend image does not belong to the exact reviewed personal project.'
    }
    return [pscustomobject]@{
        ContainerId = $containerId
        ImageReference = $imageReference
        ImageId = $imageId
        Networks = @(Get-ContainerNetworkNames $containerId)
        ExistingPublicationNetworks = @(Get-ExistingPublicationNetworks)
    }
}
function Test-BuiltEdgeConfiguration {
    $imageReference = 'personal-memo-private-win-app-public-edge:latest'
    $imageId = Get-OnlyLine (Invoke-DockerCapture @('image', 'inspect', '--format', '{{.Id}}', $imageReference)) 'built public edge image'
    if ($imageId -notmatch '^sha256:[0-9a-f]{64}$') { throw 'The built public edge image ID was not exact.' }
    & docker run --rm --network none --read-only `
        --tmpfs '/etc/nginx/conf.d:rw,uid=101,gid=101,mode=0750' `
        --tmpfs '/tmp:rw,uid=101,gid=101' --tmpfs '/var/cache/nginx:rw,uid=101,gid=101' `
        --tmpfs '/var/run:rw,uid=101,gid=101' --add-host 'frontend:127.0.0.1' `
        --env "PUBLIC_APP_HOSTNAME=$PublicAppHostname" $imageId nginx -t
    if ($LASTEXITCODE -ne 0) { throw 'The built public edge configuration failed isolated validation.' }
}
function Invoke-LocalEdgeProbe([string] $HostName, [bool] $ReadBody) {
    $request = [Net.HttpWebRequest]::Create('http://127.0.0.1:8788/')
    $request.Method = 'GET'
    $request.Host = $HostName
    $request.Proxy = $null
    $request.KeepAlive = $false
    $request.AllowAutoRedirect = $false
    $request.Timeout = 3000
    $response = $null
    try {
        try { $response = $request.GetResponse() }
        catch [Net.WebException] {
            if ($null -eq $_.Exception.Response) { throw 'The local app edge probe did not return an HTTP response.' }
            $response = $_.Exception.Response
        }
        $bodyBytes = 0
        if ($ReadBody) {
            $stream = $response.GetResponseStream()
            try {
                $buffer = New-Object byte[] 1
                $bodyBytes = $stream.Read($buffer, 0, 1)
            } finally {
                $stream.Dispose()
            }
        }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            BodyBytes = $bodyBytes
            CacheControl = [string]$response.Headers['Cache-Control']
            ContentSecurityPolicy = [string]$response.Headers['Content-Security-Policy']
            Hsts = [string]$response.Headers['Strict-Transport-Security']
            NoSniff = [string]$response.Headers['X-Content-Type-Options']
            FrameOptions = [string]$response.Headers['X-Frame-Options']
        }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
    }
}
function Assert-LocalEdgeReady {
    $shell = Invoke-LocalEdgeProbe -HostName $PublicAppHostname -ReadBody $false
    if ($shell.Status -ne 200 -or $shell.CacheControl -cne 'no-store' -or
        $shell.Hsts -cne 'max-age=86400' -or $shell.NoSniff -cne 'nosniff' -or
        $shell.FrameOptions -cne 'DENY' -or $shell.ContentSecurityPolicy -notlike "default-src 'self';*") {
        throw 'The exact-host local app edge shell probe failed its status or authoritative security-header contract.'
    }
    $wrongHost = Invoke-LocalEdgeProbe -HostName 'wrong.invalid' -ReadBody $true
    if ($wrongHost.Status -ne 404 -or $wrongHost.BodyBytes -ne 0 -or $wrongHost.CacheControl -cne 'no-store') {
        throw 'The wrong-Host local app edge probe was not a bodyless, non-cacheable 404.'
    }
}
function Remove-NewEmptyPublicationNetworks([object] $Snapshot) {
    foreach ($network in @('personal-memo-private-win_app-publication', 'personal-memo-private-win_app-loopback')) {
        if ($Snapshot.ExistingPublicationNetworks -contains $network) { continue }
        if (@(Get-ExistingPublicationNetworks) -notcontains $network) { continue }
        $endpointCount = Get-OnlyLine (Invoke-DockerCapture @('network', 'inspect', '--format', '{{len .Containers}}', $network)) 'publication network endpoint count'
        if ($endpointCount -cne '0') { throw 'A newly created publication network still has an unexpected endpoint during rollback.' }
        & docker network rm $network | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'A newly created empty publication network could not be removed during rollback.' }
    }
}
function Restore-FrontendSnapshot([object] $Snapshot, [bool] $RecreateContainer) {
    & docker image tag $Snapshot.ImageId $Snapshot.ImageReference
    if ($LASTEXITCODE -ne 0) { throw 'The previous frontend image tag could not be restored.' }
    if ($RecreateContainer) {
        Invoke-BaseCompose @('up', '-d', '--no-deps', '--no-build', '--force-recreate', '--wait', 'frontend')
    }
    $restoredId = Get-OnlyLine (Invoke-ComposeCapture @('ps', '--all', '-q', 'frontend')) 'restored personal frontend container'
    $restoredImageId = Get-OnlyLine (Invoke-DockerCapture @('inspect', '--format', '{{.Image}}', $restoredId)) 'restored frontend image ID'
    if ($restoredImageId -cne $Snapshot.ImageId) { throw 'The previous frontend image was not restored exactly.' }
    $currentNetworks = @(Get-ContainerNetworkNames $restoredId)
    foreach ($network in @($currentNetworks | Where-Object { $Snapshot.Networks -notcontains $_ })) {
        & docker network disconnect -f $network $restoredId | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'An added frontend network could not be removed during rollback.' }
    }
    foreach ($network in @($Snapshot.Networks | Where-Object { $currentNetworks -notcontains $_ })) {
        & docker network connect --alias frontend $network $restoredId | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'A previous frontend network could not be restored during rollback.' }
    }
    $finalNetworks = @(Get-ContainerNetworkNames $restoredId)
    if (@(Compare-Object -ReferenceObject @($Snapshot.Networks) -DifferenceObject $finalNetworks).Count -ne 0) {
        throw 'The previous frontend network topology was not restored exactly.'
    }
    Remove-NewEmptyPublicationNetworks $Snapshot
}

Assert-Hostname $PublicAppHostname
if ($ProjectName -cne 'personal-memo-private-win') { throw 'ProjectName must be exactly personal-memo-private-win.' }
foreach ($file in $composeFiles + @($EnvFile)) { if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required deployment file is missing: $file" } }
Assert-ConnectorStopped
if (Test-LoopbackListener 8788) { throw 'Port 127.0.0.1:8788 is already in use; stop and inspect the prior app edge first.' }
if (-not $PSCmdlet.ShouldProcess($PublicAppHostname, 'Build and start the loopback-only public app edge')) { return }
$previous = [Environment]::GetEnvironmentVariable('PUBLIC_APP_HOSTNAME', 'Process')
$frontendSnapshot = $null
$frontendMutationAttempted = $false
try {
    [Environment]::SetEnvironmentVariable('PUBLIC_APP_HOSTNAME', $PublicAppHostname, 'Process')
    Invoke-Compose @('config', '--services') | Out-Null
    $frontendSnapshot = Get-FrontendSnapshot
    # Build and validate both images before changing the running frontend. The previous image ID and
    # network set are retained so any later failure restores the exact pre-publication frontend.
    Invoke-Compose @('build', 'frontend', 'app-public-edge')
    Test-BuiltEdgeConfiguration
    $frontendMutationAttempted = $true
    Invoke-Compose @('up', '-d', '--no-build', '--no-deps', '--wait', 'frontend')
    Invoke-Compose @('up', '-d', '--no-build', '--no-deps', '--wait', 'app-public-edge')
    if (-not (Test-LoopbackListener 8788)) { throw 'The public app edge did not bind 127.0.0.1:8788.' }
    Assert-LocalEdgeReady
    Assert-ConnectorStopped
    Write-Host "The loopback-only app edge is ready for $PublicAppHostname; the connector remains stopped."
}
catch {
    $startupError = $_
    try { Invoke-Compose @('rm', '-f', '-s', 'app-public-edge') | Out-Null } catch { }
    if ($null -ne $frontendSnapshot) {
        try { Restore-FrontendSnapshot -Snapshot $frontendSnapshot -RecreateContainer $frontendMutationAttempted }
        catch { throw 'Public edge startup failed and the exact frontend rollback also failed. Keep the connector stopped and inspect Docker state.' }
    }
    throw $startupError
}
finally { [Environment]::SetEnvironmentVariable('PUBLIC_APP_HOSTNAME', $previous, 'Process') }
