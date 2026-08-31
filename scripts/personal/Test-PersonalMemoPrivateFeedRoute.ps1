[CmdletBinding()]
param(
    [string] $ProjectName = 'personal-memo-private-win',
    [string] $EnvFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

function Get-PersonalMemoResponseHeaderValues {
    param(
        [Parameter(Mandatory = $true)][string] $HeaderFile,
        [Parameter(Mandatory = $true)][string] $Name
    )

    $escapedName = [regex]::Escape($Name)
    return @(
        [IO.File]::ReadAllLines($HeaderFile) |
            ForEach-Object {
                if ($_ -match "^${escapedName}:\s*(.*?)\s*$") {
                    $Matches[1]
                }
            }
    )
}

function Invoke-PersonalMemoFeedProbe {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'HEAD')][string] $Method,
        [Parameter(Mandatory = $true)][string] $Uri,
        [Parameter(Mandatory = $true)][string] $CaFile,
        [Parameter(Mandatory = $true)][string] $HeaderFile,
        [Parameter(Mandatory = $true)][string] $BodyFile,
        [Parameter(Mandatory = $true)][string] $CurlErrorFile
    )

    $null = New-Item -ItemType File -Path $HeaderFile
    $null = New-Item -ItemType File -Path $CurlErrorFile
    $outputTarget = 'NUL'
    if ($Method -ceq 'GET') {
        $null = New-Item -ItemType File -Path $BodyFile
        $outputTarget = $BodyFile
    }
    $curlArguments = @(
        '--silent', '--show-error',
        '--noproxy', '*',
        '--proto', '=https',
        '--cacert', $CaFile,
        '--ssl-revoke-best-effort',
        '--connect-timeout', '5',
        '--max-time', '15',
        '--dump-header', $HeaderFile,
        '--output', $outputTarget,
        '--write-out', '%{http_code}|%{size_download}'
    )
    if ($Method -ceq 'HEAD') {
        $curlArguments += '--head'
    } else {
        $curlArguments += @('--request', 'GET')
    }
    $curlArguments += $Uri

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $probeSummary = & curl.exe @curlArguments 2> $CurlErrorFile
        $curlExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($curlExitCode -ne 0) {
        throw "Private calendar feed $Method probe failed before an HTTP response was verified."
    }
    if ((Get-Item -LiteralPath $CurlErrorFile).Length -ne 0) {
        throw "Private calendar feed $Method probe emitted unexpected curl diagnostics."
    }
    if ([string] ($probeSummary -join '') -cne '404|0') {
        throw "Private calendar feed $Method probe did not return the generic empty 404 response."
    }
    if ($Method -ceq 'GET' -and (Get-Item -LiteralPath $BodyFile).Length -ne 0) {
        throw "Private calendar feed $Method probe returned a non-empty body."
    }

    $cacheControl = @(Get-PersonalMemoResponseHeaderValues -HeaderFile $HeaderFile -Name 'Cache-Control')
    if ($cacheControl.Count -ne 1 -or $cacheControl[0] -cne 'no-store') {
        throw "Private calendar feed $Method probe did not return exactly one no-store cache policy."
    }
    $referrerPolicy = @(Get-PersonalMemoResponseHeaderValues -HeaderFile $HeaderFile -Name 'Referrer-Policy')
    if ($referrerPolicy.Count -ne 1 -or $referrerPolicy[0] -cne 'no-referrer') {
        throw "Private calendar feed $Method probe did not return exactly one no-referrer policy."
    }
    $setCookie = @(Get-PersonalMemoResponseHeaderValues -HeaderFile $HeaderFile -Name 'Set-Cookie')
    if ($setCookie.Count -ne 0) {
        throw "Private calendar feed $Method probe unexpectedly returned Set-Cookie."
    }
    $contentType = @(Get-PersonalMemoResponseHeaderValues -HeaderFile $HeaderFile -Name 'Content-Type')
    if ($contentType.Count -ne 0) {
        throw "Private calendar feed $Method probe unexpectedly returned Content-Type."
    }
}

Assert-PersonalMemoCommand -Name 'curl.exe'
$layoutArguments = @{ ProjectName = $ProjectName }
if (-not [string]::IsNullOrWhiteSpace($EnvFile)) {
    $layoutArguments.EnvFile = $EnvFile
}
$layout = New-PersonalMemoLayout @layoutArguments
$null = Assert-PersonalMemoComposeContract -Layout $layout
Assert-PersonalMemoTlsFiles -Layout $layout

$values = Read-PersonalMemoEnvFile -Path $layout.EnvFile
$address = Get-PersonalMemoEnvValue -Values $values -Name 'PERSONAL_MEMO_HTTPS_BIND_ADDRESS'
$port = Get-PersonalMemoEnvValue -Values $values -Name 'PERSONAL_MEMO_HTTPS_PORT'
$caFile = [IO.Path]::GetFullPath(
    (Get-PersonalMemoEnvValue -Values $values -Name 'PERSONAL_MEMO_TLS_CA_FILE')
)

$tokenBytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($tokenBytes)
} finally {
    $random.Dispose()
}
$token = [Convert]::ToBase64String($tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
if ($token -cnotmatch '^[A-Za-z0-9_-]{43}$') {
    throw 'The synthetic calendar feed probe token is not canonical.'
}

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$tempDirectory = Join-Path $tempRoot ('personal-memo-feed-route-' + [Guid]::NewGuid().ToString('N'))
$resolvedTempDirectory = [IO.Path]::GetFullPath($tempDirectory)
if (-not $resolvedTempDirectory.StartsWith($tempRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    -not (Split-Path -Leaf $resolvedTempDirectory).StartsWith('personal-memo-feed-route-')) {
    throw 'Refusing an unexpected private feed smoke temporary path.'
}

try {
    $null = New-Item -ItemType Directory -Path $resolvedTempDirectory
    $probeStartedAt = [DateTime]::UtcNow.ToString('o')
    $feedUri = "https://${address}:${port}/calendar/v1/feed.ics?token=$token"
    foreach ($method in @('GET', 'HEAD')) {
        $lowerMethod = $method.ToLowerInvariant()
        Invoke-PersonalMemoFeedProbe `
            -Method $method `
            -Uri $feedUri `
            -CaFile $caFile `
            -HeaderFile (Join-Path $resolvedTempDirectory "$lowerMethod.headers") `
            -BodyFile (Join-Path $resolvedTempDirectory "$lowerMethod.body") `
            -CurlErrorFile (Join-Path $resolvedTempDirectory "$lowerMethod.stderr")
    }

    $expectedLogFragments = @(
        'method=GET route=calendar-feed status=404 bytes=0',
        'method=HEAD route=calendar-feed status=404 bytes=0'
    )
    $logDeadline = [DateTime]::UtcNow.AddSeconds(5)
    $observedSafeLogs = $false
    do {
        $frontendLogs = Invoke-PersonalMemoCompose `
            -Layout $layout `
            -IncludePersonal `
            -Capture `
            -CommandArguments @('logs', '--no-color', '--since', $probeStartedAt, 'frontend')
        $observedSafeLogs = $true
        foreach ($expectedLogFragment in $expectedLogFragments) {
            if ($frontendLogs.IndexOf($expectedLogFragment, [StringComparison]::Ordinal) -lt 0) {
                $observedSafeLogs = $false
            }
        }
        if (-not $observedSafeLogs) {
            Start-Sleep -Milliseconds 250
        }
    } while (-not $observedSafeLogs -and [DateTime]::UtcNow -lt $logDeadline)
    if (-not $observedSafeLogs) {
        throw 'A private calendar feed probe was not observed in the query-free frontend log.'
    }
    Start-Sleep -Milliseconds 250
    $backendLogs = Invoke-PersonalMemoCompose `
        -Layout $layout `
        -IncludePersonal `
        -Capture `
        -CommandArguments @('logs', '--no-color', '--since', $probeStartedAt, 'backend')
    if ($frontendLogs.IndexOf($token, [StringComparison]::Ordinal) -ge 0 -or
        $backendLogs.IndexOf($token, [StringComparison]::Ordinal) -ge 0 -or
        $frontendLogs.IndexOf('?token=', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
        $backendLogs.IndexOf('?token=', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'A private calendar feed probe token appeared in service logs.'
    }
    Write-Host 'Private calendar feed GET/HEAD route smoke passed without logging the synthetic token.'
} finally {
    $token = $null
    [Array]::Clear($tokenBytes, 0, $tokenBytes.Length)
    if (Test-Path -LiteralPath $resolvedTempDirectory -PathType Container) {
        Remove-Item -LiteralPath $resolvedTempDirectory -Recurse -Force
    }
}
