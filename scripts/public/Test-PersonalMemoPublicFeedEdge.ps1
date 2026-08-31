[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path (Join-Path $PSScriptRoot '..') '..'))
$composeFile = Join-Path $repositoryRoot 'compose.public-feed.test.yaml'
$projectName = 'personal-memo-public-edge-test-' + [Guid]::NewGuid().ToString('N')
if (-not $projectName.StartsWith('personal-memo-public-edge-test-', [StringComparison]::Ordinal)) {
    throw 'Refusing an unexpected public-edge test project name.'
}

function Invoke-TestCompose {
    param(
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [switch] $Capture
    )
    $output = & docker compose -p $projectName -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Public-edge test Compose command failed: $($Arguments -join ' ')"
    }
    if ($Capture) {
        return [string] ($output -join "`n")
    }
}

function Get-HeaderValues {
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

function Invoke-Probe {
    param(
        [Parameter(Mandatory = $true)][string] $BaseUri,
        [Parameter(Mandatory = $true)][string] $RelativeTarget,
        [Parameter(Mandatory = $true)][string] $Method,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $TempDirectory,
        [string[]] $Headers = @(),
        [string] $Body = ''
    )
    $headerFile = Join-Path $TempDirectory ($Name + '.headers')
    $bodyFile = Join-Path $TempDirectory ($Name + '.body')
    $curlArguments = @(
        '--silent', '--show-error', '--noproxy', '*',
        '--connect-timeout', '3', '--max-time', '15',
        '--proto', '=http', '--max-redirs', '0', '--path-as-is',
        '--dump-header', $headerFile,
        '--output', $bodyFile,
        '--write-out', '%{http_code}|%{size_download}'
    )
    if ($Method -ceq 'HEAD') {
        $curlArguments += '--head'
    } else {
        $curlArguments += @('--request', $Method)
    }
    foreach ($header in $Headers) {
        $curlArguments += @('--header', $header)
    }
    if ($Body.Length -gt 0) {
        $curlArguments += @('--data-binary', $Body)
    }
    $curlArguments += ($BaseUri + $RelativeTarget)
    $status = & $script:CurlExecutable @curlArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Public-edge $Name probe failed before an HTTP response was verified."
    }
    $metadata = [string] ($status -join '')
    if ($metadata -notmatch '^(?<status>[0-9]{3})\|(?<download>[0-9]+(?:\.[0-9]+)?)$') {
        throw "Public-edge $Name probe returned unexpected curl metadata."
    }
    return [PSCustomObject]@{
        Status = $Matches['status']
        DownloadBytes = [decimal]::Parse(
            $Matches['download'],
            [Globalization.CultureInfo]::InvariantCulture
        )
        HeaderFile = $headerFile
        BodyFile = $bodyFile
    }
}

if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker is required for the isolated public-edge test.'
}
$curlCommand = Get-Command curl.exe -ErrorAction SilentlyContinue
if ($null -eq $curlCommand) {
    $curlCommand = Get-Command curl -ErrorAction SilentlyContinue
}
if ($null -eq $curlCommand -or $curlCommand.CommandType -ne 'Application') {
    throw 'The curl executable is required for the isolated public-edge test.'
}
$script:CurlExecutable = $curlCommand.Source

$directorySeparators = [char[]] @(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
)
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd($directorySeparators)
$tempDirectory = Join-Path $tempRoot ('personal-memo-public-edge-' + [Guid]::NewGuid().ToString('N'))
$resolvedTempDirectory = [IO.Path]::GetFullPath($tempDirectory)
$pathComparison = if ($env:OS -eq 'Windows_NT') {
    [StringComparison]::OrdinalIgnoreCase
} else {
    [StringComparison]::Ordinal
}
$tempPrefix = $tempRoot + [IO.Path]::DirectorySeparatorChar
if (-not $resolvedTempDirectory.StartsWith($tempPrefix, $pathComparison) -or
    -not (Split-Path -Leaf $resolvedTempDirectory).StartsWith('personal-memo-public-edge-')) {
    throw 'Refusing an unexpected public-edge test temporary path.'
}

$tokenBytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($tokenBytes)
} finally {
    $random.Dispose()
}
$token = [Convert]::ToBase64String($tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$token = 'A' + $token.Substring(1)
if ($token -cnotmatch '^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$') {
    throw 'The public-edge synthetic token is not canonical.'
}
$unknownToken = 'B' + $token.Substring(1)
$failureToken = 'C' + $token.Substring(1)
$nonCanonicalToken = $token.Substring(0, 42) + 'B'

try {
    $null = New-Item -ItemType Directory -Path $resolvedTempDirectory
    Invoke-TestCompose -Arguments @('up', '-d', '--build', '--wait')
    $portOutput = Invoke-TestCompose -Arguments @('port', 'calendar-feed-edge', '8080') -Capture
    if ($portOutput -notmatch '127\.0\.0\.1:(?<port>[0-9]+)') {
        throw 'The isolated public edge did not publish a loopback test port.'
    }
    $baseUri = "http://127.0.0.1:$($Matches['port'])"
    $target = "/calendar/v1/feed.ics?token=$token"

    $get = Invoke-Probe -BaseUri $baseUri -RelativeTarget $target -Method 'GET' `
        -Name 'valid-get' -TempDirectory $resolvedTempDirectory `
        -Headers @(
            "Authorization: Bearer $token",
            "Cookie: caller=$token",
            "Referer: https://referrer.invalid/$token",
            'X-Forwarded-For: 203.0.113.10',
            "User-Agent: $token",
            "X-Forwarded-Proto: $token"
        )
    if ($get.Status -cne '200' -or (Get-Item -LiteralPath $get.BodyFile).Length -eq 0) {
        throw 'The public edge did not proxy the exact synthetic GET.'
    }
    $cacheControl = @(Get-HeaderValues -HeaderFile $get.HeaderFile -Name 'Cache-Control')
    $referrerPolicy = @(Get-HeaderValues -HeaderFile $get.HeaderFile -Name 'Referrer-Policy')
    $setCookie = @(Get-HeaderValues -HeaderFile $get.HeaderFile -Name 'Set-Cookie')
    if ($cacheControl.Count -ne 1 -or $cacheControl[0] -cne 'no-store' -or
        $referrerPolicy.Count -ne 1 -or $referrerPolicy[0] -cne 'no-referrer' -or
        $setCookie.Count -ne 0) {
        throw 'The public edge did not replace cache/referrer policy or suppress Set-Cookie.'
    }

    $head = Invoke-Probe -BaseUri $baseUri -RelativeTarget $target -Method 'HEAD' `
        -Name 'valid-head' -TempDirectory $resolvedTempDirectory
    if ($head.Status -cne '200' -or $head.DownloadBytes -ne 0) {
        throw 'The public edge HEAD response was not bodyless and successful.'
    }

    $negativeTargets = @(
        @{ Name = 'post'; Method = 'POST'; Target = $target },
        @{ Name = 'put'; Method = 'PUT'; Target = $target },
        @{ Name = 'patch'; Method = 'PATCH'; Target = $target },
        @{ Name = 'delete'; Method = 'DELETE'; Target = $target },
        @{ Name = 'options'; Method = 'OPTIONS'; Target = $target },
        @{ Name = 'trace'; Method = 'TRACE'; Target = $target },
        @{ Name = 'bearer-as-method'; Method = $token; Target = $target },
        @{ Name = 'missing-token'; Method = 'GET'; Target = '/calendar/v1/feed.ics' },
        @{ Name = 'unknown-token'; Method = 'GET'; Target = '/calendar/v1/feed.ics?token=' + $unknownToken },
        @{ Name = 'upstream-failure'; Method = 'GET'; Target = '/calendar/v1/feed.ics?token=' + $failureToken },
        @{ Name = 'extra-query'; Method = 'GET'; Target = $target + '&extra=1' },
        @{ Name = 'duplicate-token'; Method = 'GET'; Target = $target + '&token=' + $token },
        @{ Name = 'noncanonical-token'; Method = 'GET'; Target = '/calendar/v1/feed.ics?token=' + $nonCanonicalToken },
        @{ Name = 'encoded-path'; Method = 'GET'; Target = '/calendar/v1/feed%2eics?token=' + $token },
        @{ Name = 'encoded-slash'; Method = 'GET'; Target = '/calendar%2fv1/feed.ics?token=' + $token },
        @{ Name = 'double-slash'; Method = 'GET'; Target = '/calendar//v1/feed.ics?token=' + $token },
        @{ Name = 'case-variant'; Method = 'GET'; Target = '/Calendar/v1/feed.ics?token=' + $token },
        @{ Name = 'path-parameter'; Method = 'GET'; Target = '/calendar/v1/feed.ics;x?token=' + $token },
        @{ Name = 'suffix'; Method = 'GET'; Target = '/calendar/v1/feed.ics/extra?token=' + $token },
        @{ Name = 'bearer-in-path'; Method = 'GET'; Target = '/' + $token },
        @{ Name = 'get-with-body'; Method = 'GET'; Target = $target; Body = 'x' },
        @{
            Name = 'chunked-body'
            Method = 'GET'
            Target = $target
            Body = 'x'
            Headers = @('Transfer-Encoding: chunked')
        },
        @{ Name = 'api'; Method = 'GET'; Target = '/api/v1/health' },
        @{ Name = 'internal-health'; Method = 'GET'; Target = '/_internal/health' },
        @{ Name = 'pwa'; Method = 'GET'; Target = '/' }
    )
    foreach ($negative in $negativeTargets) {
        $probe = Invoke-Probe -BaseUri $baseUri -RelativeTarget $negative.Target `
            -Method $negative.Method -Name $negative.Name -TempDirectory $resolvedTempDirectory `
            -Body $(if ($negative.ContainsKey('Body')) { $negative.Body } else { '' }) `
            -Headers $(if ($negative.ContainsKey('Headers')) { $negative.Headers } else { @() })
        $negativeContentType = @(Get-HeaderValues -HeaderFile $probe.HeaderFile -Name 'Content-Type')
        $negativeSetCookie = @(Get-HeaderValues -HeaderFile $probe.HeaderFile -Name 'Set-Cookie')
        $negativeCacheControl = @(Get-HeaderValues -HeaderFile $probe.HeaderFile -Name 'Cache-Control')
        $negativeReferrerPolicy = @(Get-HeaderValues -HeaderFile $probe.HeaderFile -Name 'Referrer-Policy')
        if ($probe.Status -cne '404' -or $probe.DownloadBytes -ne 0 -or
            $negativeContentType.Count -ne 0 -or $negativeSetCookie.Count -ne 0 -or
            $negativeCacheControl.Count -ne 1 -or $negativeCacheControl[0] -cne 'no-store' -or
            $negativeReferrerPolicy.Count -ne 1 -or $negativeReferrerPolicy[0] -cne 'no-referrer') {
            throw (
                "The public edge did not reduce $($negative.Name) to the generic empty 404 " +
                "(status=$($probe.Status), bytes=$($probe.DownloadBytes), " +
                "contentTypeCount=$($negativeContentType.Count), cookieCount=$($negativeSetCookie.Count), " +
                "cacheControl=$($negativeCacheControl -join ','), " +
                "referrerPolicy=$($negativeReferrerPolicy -join ','))."
            )
        }
    }

    $rateStatuses = @()
    $rateLimitedProbe = $null
    for ($index = 0; $index -lt 30; $index++) {
        $rateProbe = Invoke-Probe -BaseUri $baseUri -RelativeTarget $target -Method 'GET' `
            -Name ("rate-" + $index) -TempDirectory $resolvedTempDirectory `
            -Headers @('X-Forwarded-For: 198.51.100.' + (($index % 200) + 1))
        $rateStatuses += $rateProbe.Status
        if ($rateProbe.Status -ceq '429' -and $null -eq $rateLimitedProbe) {
            $rateLimitedProbe = $rateProbe
        }
    }
    if ($null -eq $rateLimitedProbe) {
        throw 'The public edge did not enforce its global request-rate bound.'
    }
    $rateContentType = @(Get-HeaderValues -HeaderFile $rateLimitedProbe.HeaderFile -Name 'Content-Type')
    $rateCacheControl = @(Get-HeaderValues -HeaderFile $rateLimitedProbe.HeaderFile -Name 'Cache-Control')
    $rateReferrerPolicy = @(Get-HeaderValues -HeaderFile $rateLimitedProbe.HeaderFile -Name 'Referrer-Policy')
    if ($rateLimitedProbe.DownloadBytes -ne 0 -or $rateContentType.Count -ne 0 -or
        $rateCacheControl.Count -ne 1 -or $rateCacheControl[0] -cne 'no-store' -or
        $rateReferrerPolicy.Count -ne 1 -or $rateReferrerPolicy[0] -cne 'no-referrer') {
        throw 'The public edge rate-limit response was not bodyless and privacy-preserving.'
    }

    $logs = Invoke-TestCompose -Arguments @('logs', '--no-color', 'calendar-feed-edge', 'backend') -Capture
    if ($logs.IndexOf($token, [StringComparison]::Ordinal) -ge 0 -or
        $logs.IndexOf('?token=', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'The synthetic bearer appeared in an owned edge or upstream log.'
    }
    if ($logs.IndexOf('method=GET route=calendar-feed', [StringComparison]::Ordinal) -lt 0 -or
        $logs.IndexOf('route=rejected', [StringComparison]::Ordinal) -lt 0) {
        throw 'The fixed safe route classifications were not observed in the owned logs.'
    }

    Write-Host 'Isolated public-feed edge path, headers, bounds, and token-free logs passed.'
} finally {
    $token = $null
    $unknownToken = $null
    $failureToken = $null
    $nonCanonicalToken = $null
    [Array]::Clear($tokenBytes, 0, $tokenBytes.Length)
    try {
        Invoke-TestCompose -Arguments @('down', '--volumes', '--remove-orphans', '--rmi', 'local')
    } catch {
        Write-Warning 'The isolated public-edge Docker cleanup needs manual verification.'
    }
    if (Test-Path -LiteralPath $resolvedTempDirectory -PathType Container) {
        Remove-Item -LiteralPath $resolvedTempDirectory -Recurse -Force
    }
}
