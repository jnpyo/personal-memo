[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)] [string] $PublicAppHostname,
    [Parameter(Mandatory = $true)] [switch] $AccessExactOwnerVerified,
    [Parameter(Mandatory = $true)] [switch] $AccessDefaultDenyVerified,
    [Parameter(Mandatory = $true)] [switch] $ProtectWithAccessVerified,
    [Parameter(Mandatory = $true)] [switch] $CacheBypassRuleVerified,
    [Parameter(Mandatory = $true)] [switch] $RemoteRouteVerified,
    [Parameter(Mandatory = $true)] [switch] $RemoteCatchAllVerified,
    [Parameter(Mandatory = $true)] [switch] $PrivacyBoundaryAccepted,
    [string] $OriginService = 'http://127.0.0.1:8788',
    [string] $RemoteRoutePath = '^/.*$',
    [string] $CatchAllService = 'http_status:404'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$serviceName = 'PersonalMemoAppCloudflareTunnel'
$calendarServiceName = 'PersonalMemoCalendarCloudflareTunnel'
$installRoot = 'C:\ProgramData\PersonalMemo\AppCloudflare'
$exe = Join-Path $installRoot 'cloudflared.exe'
$tokenFile = Join-Path $installRoot 'tunnel-token.txt'
$manifestFile = Join-Path $installRoot 'install-manifest.json'
$metricsAddress = '127.0.0.1:49313'

function Assert-Administrator {
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run from an elevated Windows PowerShell session.' }
}
function Assert-Hostname([string] $Value) {
    if ($Value -cne $Value.ToLowerInvariant() -or $Value -notmatch '^(?!calendar\.)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.[a-z]{2,63}$') {
        throw 'PublicAppHostname must be an exact lower-case, non-calendar, single-label app hostname.'
    }
}
function Get-KnownPids {
    $result = @()
    foreach ($name in @($serviceName, $calendarServiceName)) {
        $svc = Get-CimInstance Win32_Service -Filter "Name='$name'" -ErrorAction SilentlyContinue
        if ($null -ne $svc -and $svc.State -eq 'Running' -and $svc.ProcessId -gt 0) { $result += [int]$svc.ProcessId }
    }
    return @($result | Select-Object -Unique)
}
function Assert-NoUnknownCloudflared {
    $known = @(Get-KnownPids)
    $all = @([Diagnostics.Process]::GetProcessesByName('cloudflared'))
    try {
        $unknown = @($all | Where-Object { $known -notcontains $_.Id } | ForEach-Object { $_.Id })
        if ($unknown.Count -gt 0) { throw 'An unknown cloudflared process is running. Stop and investigate it before continuing.' }
    }
    finally { foreach ($process in $all) { $process.Dispose() } }
}
function Assert-ProtectedPath([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Required protected artifact is missing: $Path" }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Reparse points are forbidden: $Path" }
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected) { throw "ACL inheritance must be disabled: $Path" }
    $administratorsSid = 'S-1-5-32-544'
    $systemSid = 'S-1-5-18'
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $administratorsSid) {
        throw "Protected artifacts must be owned by Administrators: $Path"
    }
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($rules.Count -ne 2) { throw "Protected artifacts must have exactly two ACL entries: $Path" }
    foreach ($rule in $rules) {
        if ($rule.IsInherited -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            @($administratorsSid, $systemSid) -cnotcontains $rule.IdentityReference.Value -or
            $rule.FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) {
            throw "Protected artifact ACL is wider than Administrators and SYSTEM: $Path"
        }
    }
}
function Assert-InstalledContract {
    foreach ($path in @($exe, $tokenFile, $manifestFile)) { Assert-ProtectedPath $path }
    $manifest = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
    $manifestProperties = @($manifest.PSObject.Properties.Name | Sort-Object)
    if (($manifestProperties -join ',') -cne 'cloudflaredSha256,cloudflaredVersion,schemaVersion' -or
        -not ($manifest.schemaVersion -is [int]) -or [int]$manifest.schemaVersion -ne 1 -or
        [string]$manifest.cloudflaredSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$manifest.cloudflaredVersion -notmatch '^\d{4}\.\d+\.\d+$') {
        throw 'Unsupported app connector manifest.'
    }
    $signature = Get-AuthenticodeSignature -FilePath $exe
    if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or $signature.SignerCertificate.Subject -notmatch 'Cloudflare, Inc\.') { throw 'The installed cloudflared signature is invalid.' }
    if ((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant() -ne [string]$manifest.cloudflaredSha256) { throw 'The installed cloudflared hash differs from its manifest.' }
    if ([version]$manifest.cloudflaredVersion -lt [version]'2025.4.0') { throw 'cloudflared 2025.4 or newer is required.' }
    $versionOutput = @(& $exe --version 2>&1)
    $versionExitCode = $LASTEXITCODE
    $versionMatch = [regex]::Match(
        [string]($versionOutput -join ' '),
        '(?i)\bcloudflared\s+version\s+(?<version>\d{4}\.\d+\.\d+)\b'
    )
    if ($versionExitCode -ne 0 -or -not $versionMatch.Success -or
        [version]$versionMatch.Groups['version'].Value -ne [version]$manifest.cloudflaredVersion) {
        throw 'The installed cloudflared version differs from its manifest.'
    }
    $svc = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    if ($null -eq $svc -or $svc.StartMode -ne 'Manual' -or $svc.State -ne 'Stopped') { throw 'The app connector must exist in the manual/stopped state.' }
    $expected = ('"{0}" tunnel --no-autoupdate --loglevel warn --transport-loglevel warn --grace-period 30s --metrics {1} run --token-file "{2}"' -f $exe, $metricsAddress, $tokenFile)
    if ($svc.PathName -cne $expected -or $svc.StartName -ne 'LocalSystem') { throw 'The app connector service contract differs from the approved token-file-only definition.' }
}
function Test-LoopbackListener([int] $Port) {
    return @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq $Port -and $_.Address.ToString() -eq '127.0.0.1' }).Count -gt 0
}
function Invoke-DockerCapture([string[]] $Tail) {
    $previousErrorActionPreference = $ErrorActionPreference
    $output = @()
    $dockerExitCode = -1
    try {
        # Windows PowerShell 5.1 promotes native stderr to NativeCommandError
        # under Stop before LASTEXITCODE can be checked. Suppress it locally.
        $ErrorActionPreference = 'Continue'
        $output = @(& docker.exe @Tail 2>$null)
        $dockerExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($dockerExitCode -ne 0) { throw 'A bounded Docker edge inspection failed.' }
    return (@($output) -join "`n").Trim()
}
function Get-OnlyLine([string] $Text, [string] $Description) {
    $lines = @($Text -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -ne 1) { throw "Expected exactly one $Description." }
    return $lines[0].Trim()
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
function Assert-ReviewedAppEdge {
    $containerId = Get-OnlyLine (Invoke-DockerCapture @(
        'ps', '-q', '--filter', 'label=com.docker.compose.project=personal-memo-private-win',
        '--filter', 'label=com.docker.compose.service=app-public-edge', '--filter', 'status=running'
    )) 'running reviewed app-public-edge container'
    $state = Get-OnlyLine (Invoke-DockerCapture @(
        'inspect', '--format', '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}', $containerId
    )) 'app-public-edge state'
    if ($state -cne 'running|healthy') { throw 'The reviewed app-public-edge container is not healthy.' }

    $environment = @((Invoke-DockerCapture @('inspect', '--format', '{{range .Config.Env}}{{println .}}{{end}}', $containerId)) -split '\r?\n')
    $hostnameValues = @($environment | Where-Object { $_ -like 'PUBLIC_APP_HOSTNAME=*' })
    if ($hostnameValues.Count -ne 1 -or $hostnameValues[0] -cne "PUBLIC_APP_HOSTNAME=$PublicAppHostname") {
        throw 'The running app-public-edge hostname differs from the approved exact hostname.'
    }

    # Windows PowerShell 5.1 removes embedded quotes from native command arguments,
    # which makes a Go-template index expression such as index ... "8080/tcp"
    # invalid. Read the whole bounded ports object and select the exact key here.
    $ports = ConvertFrom-Json (Invoke-DockerCapture @(
        'inspect', '--format', '{{json .NetworkSettings.Ports}}', $containerId
    ))
    if ($null -eq $ports) { throw 'The reviewed app-public-edge has no published ports.' }
    $portProperties = @($ports.PSObject.Properties)
    # Keep this as a separate array assignment. Windows PowerShell 5.1 enumerates
    # arrays returned from an if expression, turning zero/one items into null/scalar.
    $bindings = @()
    if ($portProperties.Count -eq 1 -and $portProperties[0].Name -ceq '8080/tcp' -and
        $null -ne $portProperties[0].Value) {
        $bindings = @($portProperties[0].Value)
    }
    if ($portProperties.Count -ne 1 -or $bindings.Count -ne 1 -or
        [string]$bindings[0].HostIp -cne '127.0.0.1' -or
        [string]$bindings[0].HostPort -cne '8788') {
        throw 'The reviewed app-public-edge port publication is not exactly 127.0.0.1:8788 to 8080/tcp.'
    }

    $networkText = Invoke-DockerCapture @('inspect', '--format', '{{range $name, $value := .NetworkSettings.Networks}}{{println $name}}{{end}}', $containerId)
    $networks = @($networkText -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() } | Sort-Object -Unique)
    $expectedNetworks = @('personal-memo-private-win_app-loopback', 'personal-memo-private-win_app-publication')
    if (@(Compare-Object -ReferenceObject $expectedNetworks -DifferenceObject $networks).Count -ne 0) {
        throw 'The reviewed app-public-edge network topology is not the exact two-network publication boundary.'
    }

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

function Test-ConnectedTunnel {
    try {
        $request = [Net.HttpWebRequest]::Create('http://127.0.0.1:49313/diag/tunnel')
        $request.Method = 'GET'
        $request.Proxy = $null
        $request.KeepAlive = $false
        $request.Timeout = 2000
        $response = $request.GetResponse()
        try {
            $reader = New-Object IO.StreamReader($response.GetResponseStream())
            try {
                $diagnostic = ConvertFrom-Json -InputObject $reader.ReadToEnd()
            } finally {
                $reader.Dispose()
            }
        } finally {
            $response.Dispose()
        }
        return @($diagnostic.connections | Where-Object { $_.isConnected -eq $true }).Count -gt 0
    } catch {
        return $false
    }
}

Assert-Administrator
Assert-Hostname $PublicAppHostname
if (-not ($AccessExactOwnerVerified -and $AccessDefaultDenyVerified -and $ProtectWithAccessVerified -and $CacheBypassRuleVerified -and $RemoteRouteVerified -and $RemoteCatchAllVerified -and $PrivacyBoundaryAccepted)) { throw 'All Access, route, cache, catch-all, and privacy-boundary confirmations are required.' }
if ($OriginService -cne 'http://127.0.0.1:8788' -or $RemoteRoutePath -cne '^/.*$' -or $CatchAllService -cne 'http_status:404') { throw 'The approved exact route is the app hostname, all paths, to http://127.0.0.1:8788 followed by http_status:404.' }
Assert-NoUnknownCloudflared
Assert-InstalledContract
Assert-ReviewedAppEdge
if (Test-LoopbackListener 49313) { throw 'The app connector metrics port is already in use.' }
if (-not $PSCmdlet.ShouldProcess($PublicAppHostname, 'Start the Access-protected app Cloudflare connector')) { return }
try {
    Start-Service -Name $serviceName
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 500
        $svc = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
        if ($svc.State -eq 'Running' -and (Test-LoopbackListener 49313) -and (Test-ConnectedTunnel)) { break }
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($svc.State -ne 'Running' -or -not (Test-LoopbackListener 49313) -or -not (Test-ConnectedTunnel)) { throw 'The app connector did not become healthy within the bounded startup window.' }
    Assert-NoUnknownCloudflared
    Write-Host "App connector is running for $PublicAppHostname."
}
catch {
    $startupFailure = $_
    try {
        $currentService = Get-Service -Name $serviceName -ErrorAction Stop
        if ($currentService.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
            Stop-Service -Name $serviceName -Force
            $currentService.WaitForStatus(
                [ServiceProcess.ServiceControllerStatus]::Stopped,
                [TimeSpan]::FromSeconds(45)
            )
        }
        Assert-NoUnknownCloudflared
    } catch {
        throw (
            'The app connector failed to start and automatic rollback could not prove that only ' +
            'the reviewed calendar connector remains. Run Stop-PersonalMemoAppCloudflareConnector.ps1.'
        )
    }
    throw $startupFailure
}
