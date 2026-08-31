#Requires -Version 5.1
#Requires -RunAsAdministrator

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 253)]
    [ValidatePattern('^calendar\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$')]
    [string] $PublicHostname,

    [Parameter(Mandatory = $true)]
    [switch] $RemoteRouteVerified,

    [Parameter(Mandatory = $true)]
    [switch] $RemoteCatchAllVerified,

    [Parameter(Mandatory = $true)]
    [switch] $CacheBypassRuleVerified,

    [Parameter(Mandatory = $true)]
    [switch] $CustomerLogExportUnavailableVerified
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$publicScripts = [IO.Path]::GetFullPath($PSScriptRoot)
$externalScript = [IO.Path]::GetFullPath(
    (Join-Path $publicScripts 'Test-PersonalMemoCloudflareExternal.ps1')
)
$startScript = [IO.Path]::GetFullPath(
    (Join-Path $publicScripts 'Start-PersonalMemoCloudflareConnector.ps1')
)
$stopScript = [IO.Path]::GetFullPath(
    (Join-Path $publicScripts 'Stop-PersonalMemoCloudflareConnector.ps1')
)

function Assert-ExactScriptPath {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $ExpectedFileName
    )

    if (-not [IO.File]::Exists($Path) -or
        [IO.Path]::GetFileName($Path) -cne $ExpectedFileName -or
        -not [IO.Path]::GetDirectoryName($Path).Equals(
            $publicScripts,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'A required public qualification script path is not exact.'
    }
}

function Assert-PublicHostname {
    param([Parameter(Mandatory = $true)][string] $Hostname)

    if ($Hostname -cne $Hostname.ToLowerInvariant() -or
        [Uri]::CheckHostName($Hostname) -ne [UriHostNameType]::Dns) {
        throw 'The public hostname must be a canonical lower-case DNS name under calendar.<zone>.'
    }
    $candidate = New-Object Uri(('https://' + $Hostname + '/'), [UriKind]::Absolute)
    if ($candidate.Scheme -cne 'https' -or
        $candidate.Host -cne $Hostname -or
        $candidate.Port -ne 443 -or
        $candidate.AbsolutePath -cne '/' -or
        $candidate.Query.Length -ne 0 -or
        $candidate.Fragment.Length -ne 0 -or
        $candidate.UserInfo.Length -ne 0) {
        throw 'Only an exact HTTPS calendar.<zone> authority is accepted.'
    }
}

function New-CanonicalSyntheticBearer {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    $value = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $value = 'A' + $value.Substring(1)
    if ($value -cnotmatch '^[A-Za-z0-9_-]{43}$') {
        [Array]::Clear($bytes, 0, $bytes.Length)
        throw 'Synthetic bearer generation did not produce the canonical test shape.'
    }
    return [PSCustomObject]@{
        Bytes = $bytes
        Value = $value
    }
}

function Assert-ExternalNoSuccessAfterStop {
    param([Parameter(Mandatory = $true)][string] $Hostname)

    $bearer = $null
    $request = $null
    $response = $null
    $requestUri = $null
    try {
        $bearer = New-CanonicalSyntheticBearer
        $requestUri = New-Object Uri(
            ('https://' + $Hostname + '/calendar/v1/feed.ics?token=' + $bearer.Value),
            [UriKind]::Absolute
        )
        $request = [Net.HttpWebRequest]::Create($requestUri)
        $request.Method = 'HEAD'
        $request.Proxy = $null
        $request.AllowAutoRedirect = $false
        $request.KeepAlive = $false
        $request.UseDefaultCredentials = $false
        $request.Credentials = $null
        $request.MaximumResponseHeadersLength = 16
        $request.Timeout = 20000
        $request.ReadWriteTimeout = 5000

        try {
            $response = $request.GetResponse()
        } catch [Net.WebException] {
            if ($null -eq $_.Exception.Response) {
                # A connection-level failure is also a bounded non-success result. It does not
                # prove remote replica state; that remains a separate manual/dashboard gate.
                return
            }
            $response = $_.Exception.Response
        }

        $statusCode = [int] $response.StatusCode
        if ($statusCode -ge 200 -and $statusCode -lt 300) {
            throw 'The stopped connector still returned a successful external feed response.'
        }
    } finally {
        if ($null -ne $response) {
            $response.Dispose()
        }
        if ($null -ne $request) {
            $request.Abort()
        }
        $requestUri = $null
        if ($null -ne $bearer) {
            $bearer.Value = $null
            [Array]::Clear($bearer.Bytes, 0, $bearer.Bytes.Length)
        }
    }
}

function Get-SafeWorkflowFailureCode {
    param(
        [Parameter(Mandatory = $true)][string] $Phase,
        [Parameter(Mandatory = $true)] $Failure
    )

    if ($Phase -ceq 'prepare-synthetic-origin') {
        return 'SYNTHETIC_ORIGIN_PREPARE_FAILED'
    }
    if ($Phase -ceq 'temporary-synthetic-connector-start') {
        return 'CONNECTOR_START_FAILED'
    }
    if ($Phase -cne 'external-synthetic-probes') {
        return 'UNCLASSIFIED_SAFE_FAILURE'
    }

    # The child harness uses fixed exception messages. Translate only reviewed messages to a
    # bounded code; never echo the child message because it may later gain unsafe context.
    $message = [string] $Failure.Exception.Message
    switch -CaseSensitive ($message) {
        'A synthetic probe failed before an HTTP response was safely verified.' {
            return 'EXTERNAL_PROBE_TRANSPORT_FAILED'
        }
        'A synthetic probe returned unexpected non-secret metadata.' {
            return 'EXTERNAL_PROBE_METADATA_INVALID'
        }
        'The exact synthetic positive probe did not return 200.' {
            return 'EXACT_POSITIVE_STATUS_INVALID'
        }
        'A synthetic response did not contain the required fixed Cache-Control policy.' {
            return 'CACHE_CONTROL_POLICY_INVALID'
        }
        'A synthetic response did not contain the required fixed Referrer-Policy policy.' {
            return 'REFERRER_POLICY_INVALID'
        }
        'A synthetic response did not contain the required fixed X-Content-Type-Options policy.' {
            return 'CONTENT_TYPE_OPTIONS_POLICY_INVALID'
        }
        'A synthetic response did not contain the required fixed Content-Length policy.' {
            return 'CONTENT_LENGTH_POLICY_INVALID'
        }
        'A synthetic response exposed a forbidden Set-Cookie header.' {
            return 'SET_COOKIE_EXPOSED'
        }
        'A synthetic response exposed a forbidden Content-Type header.' {
            return 'DENY_CONTENT_TYPE_EXPOSED'
        }
        'The external response lacked Cloudflare cache or request evidence.' {
            return 'CLOUDFLARE_EVIDENCE_HEADER_MISSING'
        }
        'The Cloudflare cache status was outside the reviewed BYPASS or DYNAMIC allow-list.' {
            return 'CLOUDFLARE_CACHE_STATUS_INVALID'
        }
        'The exact synthetic HEAD response was not bodyless.' {
            return 'EXACT_HEAD_BODY_INVALID'
        }
        'The exact synthetic GET response was unexpectedly empty.' {
            return 'EXACT_GET_BODY_EMPTY'
        }
        'The positive response was not the fixed disposable synthetic calendar fixture.' {
            return 'SYNTHETIC_CALENDAR_BODY_INVALID'
        }
        'An external deny probe was not reduced to a generic empty 404.' {
            return 'ORIGIN_DENY_RESPONSE_INVALID'
        }
        'A remote catch-all probe was not reduced to a bounded 404.' {
            return 'REMOTE_CATCH_ALL_RESPONSE_INVALID'
        }
        'The bounded external rate observation returned neither the synthetic fixture nor a bodyless origin 429.' {
            return 'RATE_OBSERVATION_RESPONSE_INVALID'
        }
        'An expected fixed safe-route marker was absent from owned synthetic logs.' {
            return 'OWNED_LOG_MARKER_MISSING'
        }
        default {
            return 'UNCLASSIFIED_SAFE_FAILURE'
        }
    }
}

foreach ($scriptContract in @(
    @{ Path = $externalScript; Name = 'Test-PersonalMemoCloudflareExternal.ps1' },
    @{ Path = $startScript; Name = 'Start-PersonalMemoCloudflareConnector.ps1' },
    @{ Path = $stopScript; Name = 'Stop-PersonalMemoCloudflareConnector.ps1' }
)) {
    Assert-ExactScriptPath -Path $scriptContract.Path -ExpectedFileName $scriptContract.Name
}
Assert-PublicHostname -Hostname $PublicHostname
if (-not $RemoteRouteVerified -or
    -not $RemoteCatchAllVerified -or
    -not $CacheBypassRuleVerified -or
    -not $CustomerLogExportUnavailableVerified) {
    throw (
        'Remote route, catch-all, cache-bypass, and customer-log-export-unavailable assertions ' +
        'are required before disposable synthetic qualification.'
    )
}

if (-not $PSCmdlet.ShouldProcess(
    'The disposable Cloudflare synthetic qualification lifecycle',
    'Prepare the synthetic origin, start temporarily, qualify externally, and always stop locally'
)) {
    return
}

$workflowFailed = $false
$workflowPhase = 'prepare-synthetic-origin'
$workflowError = $null
$stopError = $null
$stopProofError = $null
try {
    & $externalScript -PrepareSyntheticOrigin

    $workflowPhase = 'temporary-synthetic-connector-start'
    & $startScript `
        -PublicHostname $PublicHostname `
        -RemoteRouteVerified `
        -RemoteCatchAllVerified `
        -SyntheticQualification `
        -DisposableSyntheticOriginVerified `
        -CacheBypassRuleVerified `
        -CustomerLogExportUnavailableVerified `
        -Confirm:$false

    $workflowPhase = 'external-synthetic-probes'
    & $externalScript `
        -PublicHostname $PublicHostname `
        -SyntheticOriginQualification
    $workflowPhase = 'complete'
} catch {
    $workflowFailed = $true
    $workflowError = $_
} finally {
    try {
        & $stopScript -Confirm:$false
    } catch {
        $stopError = $_
    }

    if ($null -eq $stopError) {
        try {
            Assert-ExternalNoSuccessAfterStop -Hostname $PublicHostname
        } catch {
            $stopProofError = $_
        }
    }
}

if ($null -ne $stopError) {
    throw (
        'Synthetic qualification could not prove the local connector stopped. Keep the disposable ' +
        'origin and evidence intact, and perform connector-first emergency containment.'
    )
}
if ($null -ne $stopProofError) {
    throw (
        'The local connector stopped, but the required external non-2xx proof did not complete. ' +
        'Keep the disposable origin and evidence intact until remote state is reviewed.'
    )
}
if ($workflowFailed) {
    Write-Warning (
        'Synthetic qualification did not complete. The connector is stopped and external non-success ' +
        'was proven; keep the disposable origin for remote replica review. Customer/provider log ' +
        'evidence remains unavailable and must stay report-only.'
    )
    $safeFailureCode = Get-SafeWorkflowFailureCode `
        -Phase $workflowPhase `
        -Failure $workflowError
    $boundedFailureMessage = (
        'Synthetic qualification failed during the bounded ' + $workflowPhase +
        ' phase. Underlying errors are not echoed because a generated bearer must never reach console output. ' +
        'Safe code: ' + $safeFailureCode + '.'
    )
    throw $boundedFailureMessage
}

Write-Host (
    'Synthetic external probes completed and connector-first rollback was proven. The disposable ' +
    'origin remains intentionally running until remote replica review permits the separate cleanup ' +
    'step. Customer/provider log evidence remains REQUIRED_NOT_VERIFIED.'
)
