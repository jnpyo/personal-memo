#Requires -Version 5.1
#Requires -RunAsAdministrator

[CmdletBinding(
    DefaultParameterSetName = 'LiveActivation',
    SupportsShouldProcess = $true,
    ConfirmImpact = 'High'
)]
param(
    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 253)]
    [ValidatePattern('^calendar\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$')]
    [string] $PublicHostname,

    [string] $RemoteRoutePath = '^/calendar/v1/feed\.ics$',
    [string] $OriginService = 'http://127.0.0.1:8787',
    [string] $CatchAllService = 'http_status:404',

    [Parameter(Mandatory = $true)]
    [switch] $RemoteRouteVerified,

    [Parameter(Mandatory = $true)]
    [switch] $RemoteCatchAllVerified,

    [Parameter(Mandatory = $true, ParameterSetName = 'LiveActivation')]
    [switch] $PublicationCapabilityVerified,

    [Parameter(Mandatory = $true, ParameterSetName = 'LiveActivation')]
    [switch] $ExternalTokenLogSentinelVerified,

    [Parameter(Mandatory = $true, ParameterSetName = 'SyntheticQualification')]
    [switch] $SyntheticQualification,

    [Parameter(Mandatory = $true, ParameterSetName = 'SyntheticQualification')]
    [switch] $DisposableSyntheticOriginVerified,

    [Parameter(Mandatory = $true, ParameterSetName = 'SyntheticQualification')]
    [switch] $CacheBypassRuleVerified,

    [Parameter(Mandatory = $true, ParameterSetName = 'SyntheticQualification')]
    [switch] $CustomerLogExportUnavailableVerified
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$serviceName = 'PersonalMemoCalendarCloudflareTunnel'
$installRoot = 'C:\ProgramData\PersonalMemo\Cloudflare'
$binaryDirectory = Join-Path $installRoot 'bin'
$secretDirectory = Join-Path $installRoot 'secrets'
$binaryPath = Join-Path $binaryDirectory 'cloudflared.exe'
$tokenPath = Join-Path $secretDirectory 'tunnel.token'
$manifestPath = Join-Path $installRoot 'installation-manifest.json'
$metricsAddress = '127.0.0.1:49312'
$minimumTokenFileVersion = [Version]'2025.4.0'
$administratorsSid = New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')
$systemSid = New-Object Security.Principal.SecurityIdentifier('S-1-5-18')

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this connector-start script from an elevated Windows PowerShell session.'
    }
}

function Assert-NoUnknownCloudflaredProcess {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string[]] $AllowedRunningServiceNames
    )

    $reviewedServiceNames = @(
        'PersonalMemoCalendarCloudflareTunnel',
        'PersonalMemoAppCloudflareTunnel'
    )
    $allowedProcessIds = New-Object Collections.Generic.List[int]
    foreach ($allowedServiceName in $AllowedRunningServiceNames) {
        if ($reviewedServiceNames -cnotcontains $allowedServiceName) {
            throw "Unreviewed Cloudflare service identity: $allowedServiceName"
        }
        $controller = Get-Service -Name $allowedServiceName -ErrorAction SilentlyContinue
        if ($null -eq $controller) {
            continue
        }
        if ($controller.Status -eq [ServiceProcess.ServiceControllerStatus]::Stopped) {
            continue
        }
        if ($controller.Status -ne [ServiceProcess.ServiceControllerStatus]::Running) {
            throw "A reviewed Cloudflare service is in a transitional state: $allowedServiceName"
        }
        $serviceInstance = Get-CimInstance `
            -ClassName Win32_Service `
            -Filter "Name='$allowedServiceName'" `
            -ErrorAction Stop
        if ($null -eq $serviceInstance -or [int]$serviceInstance.ProcessId -le 0) {
            throw "The running Cloudflare service has no verifiable process: $allowedServiceName"
        }
        $allowedProcessIds.Add([int]$serviceInstance.ProcessId)
    }

    $cloudflaredProcesses = @(Get-Process -Name 'cloudflared' -ErrorAction SilentlyContinue)
    try {
        foreach ($process in $cloudflaredProcesses) {
            if ($allowedProcessIds -cnotcontains [int]$process.Id) {
                throw 'An unknown cloudflared process is running outside the reviewed services.'
            }
        }
        foreach ($allowedProcessId in $allowedProcessIds) {
            if (@($cloudflaredProcesses | Where-Object { [int]$_.Id -eq $allowedProcessId }).Count -ne 1) {
                throw 'A reviewed running Cloudflare service process could not be matched exactly.'
            }
        }
    } finally {
        foreach ($process in $cloudflaredProcesses) {
            $process.Dispose()
        }
    }
}

function Assert-NotReparsePoint {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [switch] $Directory
    )

    $exists = if ($Directory) { [IO.Directory]::Exists($Path) } else { [IO.File]::Exists($Path) }
    if (-not $exists) {
        throw "Required protected Cloudflare path is missing: $Path"
    }
    if (([IO.File]::GetAttributes($Path) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Protected Cloudflare paths cannot be reparse points: $Path"
    }
}

function Assert-RestrictedAcl {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [switch] $Directory
    )

    $security = if ($Directory) {
        [IO.Directory]::GetAccessControl($Path)
    } else {
        [IO.File]::GetAccessControl($Path)
    }
    if (-not $security.AreAccessRulesProtected -or
        $security.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $administratorsSid.Value) {
        throw "Cloudflare path ownership or inheritance protection changed: $Path"
    }
    $allowedSids = @($administratorsSid.Value, $systemSid.Value)
    $rules = @($security.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($rules.Count -ne 2) {
        throw "Cloudflare path must have exactly two explicit access rules: $Path"
    }
    foreach ($rule in $rules) {
        if ($rule.IsInherited -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $allowedSids -notcontains $rule.IdentityReference.Value -or
            $rule.FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) {
            throw "Cloudflare path grants unexpected access: $Path"
        }
    }
}

function Assert-CloudflaredArtifact {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Sha256,
        [Parameter(Mandatory = $true)][Version] $RecordedVersion
    )

    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if (-not $actualHash.Equals($Sha256, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The installed cloudflared binary no longer matches its protected installation manifest.'
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    if ($signature.Status -ne [Management.Automation.SignatureStatus]::Valid -or
        $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -notmatch '(?i)(?:CN|O)\s*=\s*"?Cloudflare,\s*Inc\."?') {
        throw 'The installed cloudflared signature is not currently valid for Cloudflare, Inc.'
    }
    $versionLines = @(& $Path --version 2>&1)
    $versionExitCode = $LASTEXITCODE
    $versionMatch = [regex]::Match(
        [string] ($versionLines -join ' '),
        '(?i)\bcloudflared\s+version\s+(?<version>\d{4}\.\d+\.\d+)\b'
    )
    if ($versionExitCode -ne 0 -or -not $versionMatch.Success) {
        throw 'The installed cloudflared binary did not report a supported version.'
    }
    $actualVersion = [Version]$versionMatch.Groups['version'].Value
    if ($actualVersion -lt $minimumTokenFileVersion -or $actualVersion -ne $RecordedVersion) {
        throw 'The installed cloudflared version is below 2025.4.0 or differs from its manifest.'
    }
}

Assert-Administrator

if ($PublicHostname -cne $PublicHostname.ToLowerInvariant() -or
    [Uri]::CheckHostName($PublicHostname) -ne [UriHostNameType]::Dns) {
    throw 'The public hostname must be a canonical lower-case DNS name under calendar.<zone>.'
}
$publicAuthority = New-Object Uri(('https://' + $PublicHostname + '/'), [UriKind]::Absolute)
if ($publicAuthority.Host -cne $PublicHostname -or $publicAuthority.Port -ne 443 -or
    $publicAuthority.AbsolutePath -cne '/' -or $publicAuthority.UserInfo.Length -ne 0) {
    throw 'Only an exact HTTPS calendar.<zone> authority is accepted.'
}

if ($RemoteRoutePath -cne '^/calendar/v1/feed\.ics$' -or
    $OriginService -cne 'http://127.0.0.1:8787' -or
    $CatchAllService -cne 'http_status:404') {
    throw 'The reviewed remote ingress contract must not be widened when starting the connector.'
}
if (-not $RemoteRouteVerified -or -not $RemoteCatchAllVerified) {
    throw 'Remote exact-route and catch-all verification are required for every connector start.'
}
$isSyntheticQualification = $PSCmdlet.ParameterSetName -ceq 'SyntheticQualification'
if ($isSyntheticQualification) {
    if (-not $SyntheticQualification -or
        -not $DisposableSyntheticOriginVerified -or
        -not $CacheBypassRuleVerified -or
        -not $CustomerLogExportUnavailableVerified) {
        throw (
            'Synthetic qualification requires a disposable origin, cache-bypass rule, and an ' +
            'explicit assertion that customer log export is unavailable for this account.'
        )
    }
} elseif (-not $PublicationCapabilityVerified -or
    -not $ExternalTokenLogSentinelVerified) {
    throw 'Live activation requires publication capability and external token-log sentinel verification.'
}

foreach ($protectedDirectory in @($installRoot, $binaryDirectory, $secretDirectory)) {
    Assert-NotReparsePoint -Path $protectedDirectory -Directory
    Assert-RestrictedAcl -Path $protectedDirectory -Directory
}
foreach ($protectedFile in @($binaryPath, $tokenPath, $manifestPath)) {
    Assert-NotReparsePoint -Path $protectedFile
    Assert-RestrictedAcl -Path $protectedFile
}

$manifestInfo = Get-Item -LiteralPath $manifestPath
if ($manifestInfo.Length -le 0 -or $manifestInfo.Length -gt 4096) {
    throw 'The protected Cloudflare installation manifest has an invalid size.'
}
$manifest = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($manifestPath))
$manifestProperties = @($manifest.PSObject.Properties.Name | Sort-Object)
if (($manifestProperties -join ',') -cne 'cloudflaredSha256,cloudflaredVersion,schemaVersion' -or
    -not ($manifest.schemaVersion -is [int]) -or $manifest.schemaVersion -ne 1 -or
    [string]$manifest.cloudflaredSha256 -cnotmatch '^[A-F0-9]{64}$' -or
    [string]$manifest.cloudflaredVersion -notmatch '^\d{4}\.\d+\.\d+$') {
    throw 'The protected Cloudflare installation manifest does not match schema version 1.'
}
$recordedVersion = [Version][string]$manifest.cloudflaredVersion
Assert-CloudflaredArtifact `
    -Path $binaryPath `
    -Sha256 ([string]$manifest.cloudflaredSha256) `
    -RecordedVersion $recordedVersion

$service = Get-Service -Name $serviceName -ErrorAction Stop
if ($service.StartType -ne [ServiceProcess.ServiceStartMode]::Manual) {
    throw 'The Cloudflare connector service must remain Manual-start.'
}
if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
    throw 'The connector is not stopped; this script cannot establish a clean start boundary.'
}
Assert-NoUnknownCloudflaredProcess `
    -AllowedRunningServiceNames @('PersonalMemoAppCloudflareTunnel')

$imagePath = (Get-ItemProperty -LiteralPath (
    'HKLM:\SYSTEM\CurrentControlSet\Services\{0}' -f $serviceName
) -Name ImagePath).ImagePath
$serviceObjectName = (Get-ItemProperty -LiteralPath (
    'HKLM:\SYSTEM\CurrentControlSet\Services\{0}' -f $serviceName
) -Name ObjectName).ObjectName
$expectedImagePath = '"{0}" tunnel --no-autoupdate --loglevel warn --transport-loglevel warn --grace-period 30s --metrics {1} run --token-file "{2}"' -f `
    $binaryPath,
    $metricsAddress,
    $tokenPath
if (-not $imagePath.Equals($expectedImagePath, [StringComparison]::Ordinal) -or
    [regex]::IsMatch($imagePath, '(?i)--token(?:\s|=)') -or
    $serviceObjectName -cne 'LocalSystem') {
    throw 'The connector service command no longer matches the reviewed token-file-only contract.'
}

$metricsListeners = @(
    [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
        Where-Object { $_.Port -eq 49312 }
)
if ($metricsListeners.Count -ne 0) {
    throw 'The dedicated cloudflared metrics port is already bound before connector start.'
}

$edgeClient = New-Object Net.Sockets.TcpClient
try {
    $connect = $edgeClient.BeginConnect('127.0.0.1', 8787, $null, $null)
    if (-not $connect.AsyncWaitHandle.WaitOne(2000, $false)) {
        throw 'The loopback-only calendar edge did not accept a connection within two seconds.'
    }
    $edgeClient.EndConnect($connect)
} finally {
    $edgeClient.Dispose()
}

$startAction = if ($isSyntheticQualification) {
    'Start the connector temporarily against the verified disposable synthetic origin'
} else {
    'Start the connector as the final reviewed public publication switch'
}
if (-not $PSCmdlet.ShouldProcess('The dedicated calendar connector', $startAction)) {
    return
}

# Start-Service is deliberately the final connector-opening mutation after the selected parameter
# set's reviewed gates. Any startup or local connection-health failure is compensated by stopping
# this connector again.
try {
    Start-Service -Name $serviceName
    $service.WaitForStatus(
        [ServiceProcess.ServiceControllerStatus]::Running,
        [TimeSpan]::FromSeconds(20)
    )
    Assert-NoUnknownCloudflaredProcess -AllowedRunningServiceNames @(
        'PersonalMemoCalendarCloudflareTunnel',
        'PersonalMemoAppCloudflareTunnel'
    )

    $connectionDeadline = [DateTime]::UtcNow.AddSeconds(30)
    $connected = $false
    while (-not $connected -and [DateTime]::UtcNow -lt $connectionDeadline) {
        try {
            $request = [Net.HttpWebRequest]::Create('http://127.0.0.1:49312/diag/tunnel')
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
            $connected = @($diagnostic.connections | Where-Object { $_.isConnected -eq $true }).Count -gt 0
        } catch {
            $connected = $false
        }
        if (-not $connected) {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $connected) {
        throw 'The local connector did not report a connected Cloudflare edge session in time.'
    }
} catch {
    $startupError = $_
    try {
        $currentService = Get-Service -Name $serviceName -ErrorAction Stop
        if ($currentService.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
            Stop-Service -Name $serviceName -Force
            $currentService.WaitForStatus(
                [ServiceProcess.ServiceControllerStatus]::Stopped,
                [TimeSpan]::FromSeconds(45)
            )
        }
    } catch {
        throw (
            'Cloudflare connector startup failed and automatic rollback could not prove the local ' +
            'service stopped. Run Stop-PersonalMemoCloudflareConnector.ps1 immediately.'
        )
    }
    throw $startupError
}

if ($isSyntheticQualification) {
    Write-Host 'The dedicated connector is temporarily connected to the disposable synthetic origin.'
} else {
    Write-Host 'The dedicated calendar connector has a live edge connection for reviewed activation.'
}
