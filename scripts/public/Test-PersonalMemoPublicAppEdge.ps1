[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path (Join-Path $PSScriptRoot '..') '..'))
$composeFile = Join-Path $repositoryRoot 'compose.public-app.test.yaml'
$syntheticHost = 'memo.synthetic.test'
$trustedOrigin = 'https://' + $syntheticHost
$testPort = 18788
$baseUri = 'http://127.0.0.1:' + $testPort
$projectName = 'personal-memo-public-app-edge-test-' + [Guid]::NewGuid().ToString('N')
if (-not $projectName.StartsWith('personal-memo-public-app-edge-test-', [StringComparison]::Ordinal)) {
    throw 'Refusing an unexpected public-app edge test project name.'
}

function Invoke-TestCompose {
    param(
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [switch] $Capture
    )

    $output = & docker compose --project-directory $repositoryRoot -p $projectName -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Public-app edge test Compose command failed: $($Arguments -join ' ')"
    }
    if ($Capture) {
        return [string] ($output -join "`n")
    }
}

function Invoke-DockerCapture {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)

    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker cleanup verification failed: $($Arguments -join ' ')"
    }
    return @($output | ForEach-Object { [string] $_ })
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

function Assert-ExactHeader {
    param(
        [Parameter(Mandatory = $true)] $Probe,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Expected
    )

    $values = @(Get-HeaderValues -HeaderFile $Probe.HeaderFile -Name $Name)
    if ($values.Count -ne 1 -or $values[0] -cne $Expected) {
        throw "$($Probe.Name) did not return one authoritative $Name header (actual=$($values -join ','))."
    }
}

function Assert-NoCorsHeaders {
    param([Parameter(Mandatory = $true)] $Probe)

    $corsLines = @(
        [IO.File]::ReadAllLines($Probe.HeaderFile) |
            Where-Object { $_ -match '^Access-Control-' }
    )
    if ($corsLines.Count -ne 0) {
        throw "$($Probe.Name) exposed a CORS response header."
    }
}

function Assert-StandardSecurityHeaders {
    param([Parameter(Mandatory = $true)] $Probe)

    $expected = @(
        @{
            Name = 'Content-Security-Policy'
            Value = "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; worker-src 'self' blob:; manifest-src 'self'"
        },
        @{ Name = 'Cross-Origin-Opener-Policy'; Value = 'same-origin' },
        @{ Name = 'Cross-Origin-Resource-Policy'; Value = 'same-origin' },
        @{ Name = 'Permissions-Policy'; Value = 'camera=(), microphone=(), geolocation=(), payment=(), usb=()' },
        @{ Name = 'Referrer-Policy'; Value = 'no-referrer' },
        @{ Name = 'Strict-Transport-Security'; Value = 'max-age=86400' },
        @{ Name = 'X-Content-Type-Options'; Value = 'nosniff' },
        @{ Name = 'X-Frame-Options'; Value = 'DENY' }
    )
    foreach ($header in $expected) {
        Assert-ExactHeader -Probe $Probe -Name $header.Name -Expected $header.Value
    }
    Assert-NoCorsHeaders -Probe $Probe
}

function Assert-NoStore {
    param([Parameter(Mandatory = $true)] $Probe)

    Assert-ExactHeader -Probe $Probe -Name 'Cache-Control' -Expected 'no-store'
}

function Assert-BodylessNotFound {
    param([Parameter(Mandatory = $true)] $Probe)

    $contentType = @(Get-HeaderValues -HeaderFile $Probe.HeaderFile -Name 'Content-Type')
    if ($Probe.Status -cne '404' -or $Probe.DownloadBytes -ne 0 -or $contentType.Count -ne 0) {
        throw (
            "$($Probe.Name) was not reduced to the generic bodyless 404 " +
            "(status=$($Probe.Status), bytes=$($Probe.DownloadBytes), contentTypeCount=$($contentType.Count))."
        )
    }
    Assert-NoStore -Probe $Probe
    Assert-StandardSecurityHeaders -Probe $Probe
}

function Invoke-Probe {
    param(
        [Parameter(Mandatory = $true)][string] $RelativeTarget,
        [Parameter(Mandatory = $true)][string] $Method,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $TempDirectory,
        [string[]] $Headers = @(),
        [string] $Body = '',
        [string] $UploadFile = ''
    )

    if ($Body.Length -gt 0 -and $UploadFile.Length -gt 0) {
        throw 'A probe cannot use both an inline body and an upload file.'
    }
    $headerFile = Join-Path $TempDirectory ($Name + '.headers')
    $bodyFile = Join-Path $TempDirectory ($Name + '.body')
    $curlArguments = @(
        '--silent', '--show-error', '--noproxy', '*', '--http1.1',
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
        $curlArguments += @('--header', 'Expect:', '--data-binary', $Body)
    } elseif ($UploadFile.Length -gt 0) {
        $curlArguments += @('--header', 'Expect:', '--data-binary', ('@' + $UploadFile))
    }
    $curlArguments += ($baseUri + $RelativeTarget)

    $metadataOutput = & $script:CurlExecutable @curlArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Public-app edge $Name probe failed before an HTTP response was verified."
    }
    $metadata = [string] ($metadataOutput -join '')
    if ($metadata -notmatch '^(?<status>[0-9]{3})\|(?<download>[0-9]+(?:\.[0-9]+)?)$') {
        throw "Public-app edge $Name probe returned unexpected curl metadata."
    }
    return [PSCustomObject]@{
        Name = $Name
        Status = $Matches['status']
        DownloadBytes = [decimal]::Parse(
            $Matches['download'],
            [Globalization.CultureInfo]::InvariantCulture
        )
        HeaderFile = $headerFile
        BodyFile = $bodyFile
    }
}

function Assert-ApiMarkerResponse {
    param(
        [Parameter(Mandatory = $true)] $Probe,
        [Parameter(Mandatory = $true)][string] $ExpectedMethod,
        [Parameter(Mandatory = $true)][bool] $ExpectedBodyPreserved,
        [Parameter(Mandatory = $true)][string[]] $SecretMarkers
    )

    if ($Probe.Status -cne '200') {
        throw "$($Probe.Name) API marker probe returned $($Probe.Status) instead of 200."
    }
    $contentTypes = @(Get-HeaderValues -HeaderFile $Probe.HeaderFile -Name 'Content-Type')
    if ($contentTypes.Count -ne 1 -or $contentTypes[0] -notmatch '^application/json(?:;\s*charset=.*)?$') {
        throw "$($Probe.Name) API marker response was not one JSON response."
    }
    $raw = [IO.File]::ReadAllText($Probe.BodyFile)
    foreach ($marker in $SecretMarkers) {
        if ($raw.IndexOf($marker, [StringComparison]::Ordinal) -ge 0) {
            throw "$($Probe.Name) API response echoed generated request material."
        }
    }
    try {
        $payload = $raw | ConvertFrom-Json
    } catch {
        throw "$($Probe.Name) API marker response was not valid JSON."
    }
    $propertyNames = @($payload.PSObject.Properties.Name | Sort-Object)
    $expectedNames = @(
        'allowedHeadersPreserved',
        'bodyPreserved',
        'canonicalForwarding',
        'fixture',
        'forbiddenCookiesRemoved',
        'method',
        'sessionCookiePreserved',
        'untrustedHeadersSeen',
        'xsrfCookiePreserved'
    )
    if (($propertyNames -join ',') -cne ($expectedNames -join ',')) {
        throw "$($Probe.Name) API response did not contain the exact finite marker contract."
    }
    if ($payload.fixture -cne 'public-app-synthetic-v1' -or
        $payload.method -cne $ExpectedMethod -or
        $payload.canonicalForwarding -isnot [bool] -or
        $payload.untrustedHeadersSeen -isnot [bool] -or
        $payload.sessionCookiePreserved -isnot [bool] -or
        $payload.xsrfCookiePreserved -isnot [bool] -or
        $payload.forbiddenCookiesRemoved -isnot [bool] -or
        $payload.allowedHeadersPreserved -isnot [bool] -or
        $payload.bodyPreserved -isnot [bool] -or
        -not $payload.canonicalForwarding -or
        $payload.untrustedHeadersSeen -or
        -not $payload.sessionCookiePreserved -or
        -not $payload.xsrfCookiePreserved -or
        -not $payload.forbiddenCookiesRemoved -or
        -not $payload.allowedHeadersPreserved -or
        $payload.bodyPreserved -ne $ExpectedBodyPreserved) {
        throw (
            "$($Probe.Name) API marker mismatch: " +
            "fixture=$($payload.fixture), method=$($payload.method), " +
            "canonicalForwarding=$($payload.canonicalForwarding), " +
            "untrustedHeadersSeen=$($payload.untrustedHeadersSeen), " +
            "sessionCookiePreserved=$($payload.sessionCookiePreserved), " +
            "xsrfCookiePreserved=$($payload.xsrfCookiePreserved), " +
            "forbiddenCookiesRemoved=$($payload.forbiddenCookiesRemoved), " +
            "allowedHeadersPreserved=$($payload.allowedHeadersPreserved), " +
            "bodyPreserved=$($payload.bodyPreserved), expectedBodyPreserved=$ExpectedBodyPreserved."
        )
    }
    $setCookie = @(Get-HeaderValues -HeaderFile $Probe.HeaderFile -Name 'Set-Cookie')
    if ($setCookie.Count -ne 1 -or $setCookie[0] -notmatch '^synthetic-upstream-cookie=1(?:;|$)') {
        throw "$($Probe.Name) did not preserve the single synthetic upstream response cookie."
    }
    Assert-NoStore -Probe $Probe
    Assert-StandardSecurityHeaders -Probe $Probe
}

function Test-LoopbackPortOpen {
    param([Parameter(Mandatory = $true)][int] $Port)

    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(250)) {
            return $false
        }
        try {
            $client.EndConnect($pending)
            return $true
        } catch {
            return $false
        }
    } finally {
        $client.Dispose()
    }
}

if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker is required for the isolated public-app edge test.'
}
$curlCommand = Get-Command curl.exe -ErrorAction SilentlyContinue
if ($null -eq $curlCommand) {
    $curlCommand = Get-Command curl -ErrorAction SilentlyContinue
}
if ($null -eq $curlCommand -or $curlCommand.CommandType -ne 'Application') {
    throw 'The curl executable is required for the isolated public-app edge test.'
}
$script:CurlExecutable = $curlCommand.Source

if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
    throw 'The isolated public-app Compose file is missing.'
}
if (Test-LoopbackPortOpen -Port $testPort) {
    throw "The fixed synthetic loopback port $testPort is already in use."
}

$directorySeparators = [char[]] @(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
)
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd($directorySeparators)
$tempDirectory = Join-Path $tempRoot ('personal-memo-public-app-edge-' + [Guid]::NewGuid().ToString('N'))
$resolvedTempDirectory = [IO.Path]::GetFullPath($tempDirectory)
$pathComparison = if ($env:OS -eq 'Windows_NT') {
    [StringComparison]::OrdinalIgnoreCase
} else {
    [StringComparison]::Ordinal
}
$tempPrefix = $tempRoot + [IO.Path]::DirectorySeparatorChar
if (-not $resolvedTempDirectory.StartsWith($tempPrefix, $pathComparison) -or
    -not (Split-Path -Leaf $resolvedTempDirectory).StartsWith(
        'personal-memo-public-app-edge-',
        [StringComparison]::Ordinal
    )) {
    throw 'Refusing an unexpected public-app edge test temporary path.'
}

$queryMarker = 'query' + [Guid]::NewGuid().ToString('N')
$cookieMarker = 'cookie' + [Guid]::NewGuid().ToString('N')
$bodyMarker = 'body' + [Guid]::NewGuid().ToString('N')
$headerMarker = 'header' + [Guid]::NewGuid().ToString('N')
$pathMarker = 'path' + [Guid]::NewGuid().ToString('N')
$secretMarkers = @($queryMarker, $cookieMarker, $bodyMarker, $headerMarker, $pathMarker)

$previousTestPort = $env:PERSONAL_MEMO_APP_EDGE_TEST_PORT
$hadPreviousTestPort = Test-Path Env:PERSONAL_MEMO_APP_EDGE_TEST_PORT
$env:PERSONAL_MEMO_APP_EDGE_TEST_PORT = [string] $testPort
$testFailure = $null
$cleanupFailures = New-Object 'System.Collections.Generic.List[string]'
$rateObservation = 'NOT_RUN'

try {
    $null = New-Item -ItemType Directory -Path $resolvedTempDirectory

    $servicesText = Invoke-TestCompose -Arguments @('config', '--services') -Capture
    $services = @(
        $servicesText -split "`r?`n" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { $_.Trim() } |
            Sort-Object
    )
    if (($services -join ',') -cne 'app-public-edge,frontend') {
        throw 'The disposable public-app Compose topology must contain only app-public-edge and synthetic frontend.'
    }

    Invoke-TestCompose -Arguments @('up', '-d', '--build', '--wait')
    $portOutput = Invoke-TestCompose -Arguments @('port', 'app-public-edge', '8080') -Capture
    if ($portOutput -notmatch '^127\.0\.0\.1:18788\s*$') {
        throw 'The isolated public-app edge did not publish only the fixed loopback test port.'
    }

    $exactHostHeader = 'Host: ' + $syntheticHost
    $exactOriginHeader = 'Origin: ' + $trustedOrigin
    $cookieHeader = 'Cookie: SESSION=synthetic-session; XSRF-TOKEN=synthetic-cookie-xsrf; ' +
        'CF_Authorization=' + $cookieMarker + '; unknown_cookie=' + $cookieMarker
    $spoofHeaders = @(
        "Authorization: Bearer $headerMarker"
        "Forwarded: for=$headerMarker;proto=http;host=attacker.invalid"
        "X-Real-IP: $headerMarker"
        "X-Forwarded-For: $headerMarker"
        'X-Forwarded-Host: attacker.invalid'
        'X-Forwarded-Port: 81'
        'X-Forwarded-Proto: http'
        "X-Forwarded-Prefix: /$headerMarker"
        'X-Forwarded-Ssl: off'
        "X-Original-Forwarded-For: $headerMarker"
        "X-Original-URL: /$headerMarker"
        "X-Rewrite-URL: /$headerMarker"
        "True-Client-IP: $headerMarker"
        "CF-Connecting-IP: $headerMarker"
        "CF-Ray: $headerMarker"
        "CF-Access-Jwt-Assertion: $headerMarker"
        "CF-Access-Authenticated-User-Email: $headerMarker@invalid.test"
        "CF-Access-Client-Id: $headerMarker"
        "CF-Access-Client-Secret: $headerMarker"
        "X-Public-Edge-Sentinel: $headerMarker"
        "Referer: https://attacker.invalid/$headerMarker"
        "User-Agent: $headerMarker"
    )

    $shellGet = Invoke-Probe -RelativeTarget ('/?probe=' + $queryMarker) -Method 'GET' `
        -Name 'shell-get' -TempDirectory $resolvedTempDirectory -Headers @($exactHostHeader)
    if ($shellGet.Status -cne '200' -or
        [IO.File]::ReadAllText($shellGet.BodyFile).IndexOf(
            'public-app-synthetic-fixture-v1',
            [StringComparison]::Ordinal
        ) -lt 0) {
        throw 'The exact-host PWA shell GET did not reach the synthetic frontend.'
    }
    Assert-NoStore -Probe $shellGet
    Assert-StandardSecurityHeaders -Probe $shellGet

    $shellHead = Invoke-Probe -RelativeTarget '/' -Method 'HEAD' -Name 'shell-head' `
        -TempDirectory $resolvedTempDirectory -Headers @($exactHostHeader)
    if ($shellHead.Status -cne '200' -or $shellHead.DownloadBytes -ne 0) {
        throw 'The exact-host PWA shell HEAD was not bodyless and successful.'
    }
    Assert-NoStore -Probe $shellHead
    Assert-StandardSecurityHeaders -Probe $shellHead

    $hashedAsset = Invoke-Probe -RelativeTarget '/assets/app-abcdef12.js' -Method 'GET' `
        -Name 'hashed-asset' -TempDirectory $resolvedTempDirectory -Headers @($exactHostHeader)
    if ($hashedAsset.Status -cne '200' -or $hashedAsset.DownloadBytes -eq 0) {
        throw 'The exact synthetic hashed asset was not served.'
    }
    Assert-ExactHeader -Probe $hashedAsset -Name 'Cache-Control' `
        -Expected 'public, max-age=31536000, immutable'
    Assert-StandardSecurityHeaders -Probe $hashedAsset

    $missingHashedAsset = Invoke-Probe -RelativeTarget '/assets/missing-abcdef12.js' -Method 'GET' `
        -Name 'missing-hashed-asset' -TempDirectory $resolvedTempDirectory -Headers @($exactHostHeader)
    if ($missingHashedAsset.Status -cne '404') {
        throw 'A missing hash-shaped asset did not fail closed with 404.'
    }
    Assert-NoStore -Probe $missingHashedAsset
    Assert-StandardSecurityHeaders -Probe $missingHashedAsset

    $unhashedAsset = Invoke-Probe -RelativeTarget '/assets/app.js' -Method 'GET' `
        -Name 'unhashed-asset' -TempDirectory $resolvedTempDirectory -Headers @($exactHostHeader)
    if ($unhashedAsset.Status -cne '404') {
        throw 'An unhashed asset was unexpectedly served or cached as a build artifact.'
    }
    Assert-NoStore -Probe $unhashedAsset
    Assert-StandardSecurityHeaders -Probe $unhashedAsset

    foreach ($staticProbe in @(
        @{ Name = 'manifest'; Target = '/manifest.webmanifest' },
        @{ Name = 'service-worker'; Target = '/sw.js' }
    )) {
        $probe = Invoke-Probe -RelativeTarget $staticProbe.Target -Method 'GET' `
            -Name $staticProbe.Name -TempDirectory $resolvedTempDirectory -Headers @($exactHostHeader)
        if ($probe.Status -cne '200') {
            throw "$($staticProbe.Name) was not served by the synthetic frontend."
        }
        Assert-NoStore -Probe $probe
        Assert-StandardSecurityHeaders -Probe $probe
    }

    $apiTarget = '/api/v1/synthetic/echo?probe=' + $queryMarker
    foreach ($method in @('GET', 'POST', 'PATCH', 'DELETE')) {
        $apiHeaders = @(
            $exactHostHeader,
            $exactOriginHeader,
            $cookieHeader,
            'Content-Type: application/json',
            'Accept: application/json',
            'X-XSRF-TOKEN: synthetic-xsrf',
            'X-Expected-Owner-Id: 00000000-0000-0000-0000-000000000001',
            'Idempotency-Key: synthetic-idempotency',
            'X-Analysis-Proposal-Schema-Version: 3',
            'If-None-Match: "synthetic-etag"',
            'If-Modified-Since: Wed, 21 Oct 2015 07:28:00 GMT'
        ) + $spoofHeaders
        $apiBody = ''
        $expectsBody = $method -cne 'GET'
        if ($expectsBody) {
            $apiBody = '{"probe":"' + $bodyMarker + '","method":"' + $method + '"}'
        }
        $apiProbe = Invoke-Probe -RelativeTarget $apiTarget -Method $method `
            -Name ('api-' + $method.ToLowerInvariant()) -TempDirectory $resolvedTempDirectory `
            -Headers $apiHeaders -Body $apiBody
        Assert-ApiMarkerResponse -Probe $apiProbe -ExpectedMethod $method `
            -ExpectedBodyPreserved $expectsBody -SecretMarkers $secretMarkers
    }

    foreach ($hostCase in @(
        @{ Name = 'wrong-host'; Header = 'Host: wrong.synthetic.test' },
        @{ Name = 'missing-host'; Header = 'Host:' },
        @{ Name = 'case-host'; Header = 'Host: Memo.synthetic.test' },
        @{ Name = 'port-host'; Header = 'Host: memo.synthetic.test:18788' }
    )) {
        $probe = Invoke-Probe -RelativeTarget '/' -Method 'GET' -Name $hostCase.Name `
            -TempDirectory $resolvedTempDirectory -Headers @($hostCase.Header)
        Assert-BodylessNotFound -Probe $probe
    }

    foreach ($originCase in @(
        @{ Name = 'unsafe-missing-origin'; Origin = $null },
        @{ Name = 'unsafe-null-origin'; Origin = 'null' },
        @{ Name = 'unsafe-mismatch-origin'; Origin = 'https://attacker.synthetic.test' }
    )) {
        $originHeaders = @($exactHostHeader, $cookieHeader, 'Content-Type: application/json')
        if ($null -ne $originCase.Origin) {
            $originHeaders += 'Origin: ' + $originCase.Origin
        }
        $probe = Invoke-Probe -RelativeTarget $apiTarget -Method 'POST' -Name $originCase.Name `
            -TempDirectory $resolvedTempDirectory -Headers $originHeaders `
            -Body ('{"probe":"' + $bodyMarker + '"}')
        Assert-BodylessNotFound -Probe $probe
    }

    foreach ($method in @('OPTIONS', 'PUT', 'TRACE')) {
        $probe = Invoke-Probe -RelativeTarget '/api/v1/synthetic/echo' -Method $method `
            -Name ('blocked-method-' + $method.ToLowerInvariant()) -TempDirectory $resolvedTempDirectory `
            -Headers @($exactHostHeader, $exactOriginHeader)
        Assert-BodylessNotFound -Probe $probe
    }
    # CONNECT is deliberately not a browser-app success condition. It is a Fetch-forbidden,
    # authority-form method that can leave Nginx's normal app-route policy before location matching.
    # This smoke therefore makes no claim that a Cloudflare/perimeter CONNECT request is allowed or
    # denied; that separate perimeter must remain default-deny. Parsed app-route methods above still
    # prove the local edge's default-deny bodyless-404 contract.
    $connectObservation = 'NOT_PROBED_FETCH_FORBIDDEN_AUTHORITY_FORM_PERIMETER_NOT_CLAIMED'

    foreach ($blocked in @(
        @{ Name = 'blocked-actuator'; Target = '/actuator/health' },
        @{ Name = 'blocked-internal'; Target = '/_internal/health' },
        @{ Name = 'blocked-calendar'; Target = '/calendar/v1/feed.ics?token=' + $queryMarker },
        @{ Name = 'blocked-register'; Target = '/api/v1/auth/register' },
        @{ Name = 'blocked-oauth-start'; Target = '/oauth2/authorization/google' },
        @{ Name = 'blocked-oauth-callback'; Target = '/login/oauth2/code/google' }
    )) {
        $probe = Invoke-Probe -RelativeTarget $blocked.Target -Method 'GET' -Name $blocked.Name `
            -TempDirectory $resolvedTempDirectory -Headers @($exactHostHeader)
        Assert-BodylessNotFound -Probe $probe
    }

    foreach ($matrixRegister in @(
        @{ Name = 'blocked-register-matrix'; Target = '/api/v1/auth/register;matrix=synthetic' },
        @{ Name = 'blocked-register-encoded-matrix'; Target = '/api/v1/auth/register%3Bmatrix=synthetic' }
    )) {
        # The exact trusted Origin prevents the unsafe-Origin gate from masking this route test. The
        # synthetic upstream returns 201 if a Spring-style matrix registration path reaches it.
        $probe = Invoke-Probe -RelativeTarget $matrixRegister.Target -Method 'POST' `
            -Name $matrixRegister.Name -TempDirectory $resolvedTempDirectory `
            -Headers @($exactHostHeader, $exactOriginHeader, 'Content-Type: application/json') `
            -Body '{"synthetic":true}'
        Assert-BodylessNotFound -Probe $probe
    }

    $unknownSpa = Invoke-Probe -RelativeTarget ('/unknown-' + $pathMarker) -Method 'GET' `
        -Name 'unknown-spa' -TempDirectory $resolvedTempDirectory -Headers @($exactHostHeader)
    if ($unknownSpa.Status -cne '404') {
        throw 'The unknown synthetic SPA path did not remain a 404.'
    }
    Assert-NoStore -Probe $unknownSpa
    Assert-StandardSecurityHeaders -Probe $unknownSpa

    $oversizeFile = Join-Path $resolvedTempDirectory 'oversize-request.bin'
    $oversizeBytes = New-Object byte[] (1MB + 1)
    $markerBytes = [Text.Encoding]::UTF8.GetBytes($bodyMarker)
    [Array]::Copy($markerBytes, 0, $oversizeBytes, 0, $markerBytes.Length)
    [IO.File]::WriteAllBytes($oversizeFile, $oversizeBytes)
    [Array]::Clear($oversizeBytes, 0, $oversizeBytes.Length)
    [Array]::Clear($markerBytes, 0, $markerBytes.Length)
    $oversizeProbe = Invoke-Probe -RelativeTarget '/api/v1/synthetic/echo' -Method 'POST' `
        -Name 'oversize-body' -TempDirectory $resolvedTempDirectory `
        -Headers @($exactHostHeader, $exactOriginHeader, 'Content-Type: application/octet-stream') `
        -UploadFile $oversizeFile
    $oversizeContentType = @(Get-HeaderValues -HeaderFile $oversizeProbe.HeaderFile -Name 'Content-Type')
    if (@('404', '413') -cnotcontains $oversizeProbe.Status -or
        $oversizeProbe.DownloadBytes -ne 0 -or $oversizeContentType.Count -ne 0) {
        throw 'The oversized body was not reduced to a bodyless 413 or generic bodyless rejection.'
    }
    Assert-NoStore -Probe $oversizeProbe
    Assert-StandardSecurityHeaders -Probe $oversizeProbe

    $rateAttemptLimit = 120
    $rateStatusCounts = @{}
    $firstRateLimitedProbe = $null
    for ($index = 0; $index -lt $rateAttemptLimit; $index++) {
        $rateProbe = Invoke-Probe -RelativeTarget ('/?rate=' + $queryMarker) -Method 'GET' `
            -Name ('rate-' + $index.ToString('000')) -TempDirectory $resolvedTempDirectory `
            -Headers @($exactHostHeader)
        if (@('200', '404', '429') -cnotcontains $rateProbe.Status) {
            throw "The bounded rate observation returned unexpected status $($rateProbe.Status)."
        }
        if (-not $rateStatusCounts.ContainsKey($rateProbe.Status)) {
            $rateStatusCounts[$rateProbe.Status] = 0
        }
        $rateStatusCounts[$rateProbe.Status]++
        if ($rateProbe.Status -ceq '429' -and $null -eq $firstRateLimitedProbe) {
            $firstRateLimitedProbe = $rateProbe
        }
    }
    if ($null -ne $firstRateLimitedProbe) {
        if ($firstRateLimitedProbe.DownloadBytes -ne 0) {
            throw 'The observed local 429 response was not bodyless.'
        }
        Assert-NoStore -Probe $firstRateLimitedProbe
        Assert-StandardSecurityHeaders -Probe $firstRateLimitedProbe
        $rateObservation = 'OBSERVED_429'
    } else {
        $rateObservation = 'NOT_OBSERVED_WITHIN_' + $rateAttemptLimit + '_BOUNDED_ATTEMPTS'
    }

    $logs = Invoke-TestCompose -Arguments @('logs', '--no-color', 'app-public-edge', 'frontend') -Capture
    foreach ($marker in $secretMarkers) {
        if ($logs.IndexOf($marker, [StringComparison]::Ordinal) -ge 0) {
            throw 'Generated query, cookie, body, header, or path material appeared in an owned synthetic log.'
        }
    }
    $edgeLogLines = @($logs -split "`r?`n" | Where-Object { $_ -match 'method_class=' })
    $upstreamLogLines = @($logs -split "`r?`n" | Where-Object { $_ -match 'method=' })
    if ($edgeLogLines.Count -eq 0 -or $upstreamLogLines.Count -eq 0) {
        throw 'The fixed public-app edge and upstream log classifications were not observed.'
    }
    foreach ($line in $edgeLogLines) {
        if ($line -notmatch 'method_class=(?:read|write|other) route_class=(?:spa-shell|pwa|static|api|hashed-static|blocked|rejected) status=[0-9]{3} bytes=[0-9]+ duration=[0-9.]+\s*$') {
            throw 'An edge access log escaped the finite route/method classification contract.'
        }
    }
    foreach ($line in $upstreamLogLines) {
        if ($line -notmatch 'method=(?:GET|HEAD|POST|PATCH|DELETE|other) route_class=(?:fixture|hashed-static|pwa|safe-echo|auth-login|rejected) status=[0-9]{3} bytes=[0-9]+\s*$') {
            throw 'A synthetic upstream access log escaped the finite route/method classification contract.'
        }
    }
    if ($logs.IndexOf('method_class=read route_class=spa-shell', [StringComparison]::Ordinal) -lt 0 -or
        $logs.IndexOf('method_class=read route_class=hashed-static', [StringComparison]::Ordinal) -lt 0 -or
        $logs.IndexOf('method_class=read route_class=pwa', [StringComparison]::Ordinal) -lt 0 -or
        $logs.IndexOf('method_class=write route_class=api', [StringComparison]::Ordinal) -lt 0 -or
        $logs.IndexOf('route_class=blocked', [StringComparison]::Ordinal) -lt 0 -or
        $logs.IndexOf('route_class=rejected', [StringComparison]::Ordinal) -lt 0) {
        throw 'Expected fixed public-app route/method classes were absent from the owned logs.'
    }

    $rateSummary = @(
        $rateStatusCounts.Keys |
            Sort-Object |
            ForEach-Object { $_ + '=' + $rateStatusCounts[$_] }
    ) -join ','
    Write-Host (
        'Isolated public-app edge host/origin/method/header/body/cache/security/log smoke passed. ' +
        'Local rate observation: ' + $rateObservation + ' (' + $rateSummary + '). ' +
        'CONNECT observation: ' + $connectObservation + '.'
    )
} catch {
    $testFailure = $_
} finally {
    try {
        Invoke-TestCompose -Arguments @('down', '--volumes', '--remove-orphans', '--rmi', 'local', '--timeout', '10')
    } catch {
        $cleanupFailures.Add($_.Exception.Message)
    }

    try {
        $containerResidue = @(
            Invoke-DockerCapture -Arguments @(
                'ps', '-aq', '--filter', ('label=com.docker.compose.project=' + $projectName)
            ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
        $networkResidue = @(
            Invoke-DockerCapture -Arguments @(
                'network', 'ls', '-q', '--filter', ('label=com.docker.compose.project=' + $projectName)
            ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
        $volumeResidue = @(
            Invoke-DockerCapture -Arguments @(
                'volume', 'ls', '-q', '--filter', ('label=com.docker.compose.project=' + $projectName)
            ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
        $imagePrefix = $projectName + '-'
        $imageResidue = @(
            Invoke-DockerCapture -Arguments @('image', 'ls', '--format', '{{.Repository}}:{{.Tag}}') |
                Where-Object { $_.StartsWith($imagePrefix, [StringComparison]::Ordinal) }
        )
        if ($containerResidue.Count -ne 0 -or $networkResidue.Count -ne 0 -or
            $volumeResidue.Count -ne 0 -or $imageResidue.Count -ne 0) {
            $cleanupFailures.Add(
                'Exact disposable Compose cleanup left project-labeled containers, networks, volumes, or images.'
            )
        }
    } catch {
        $cleanupFailures.Add($_.Exception.Message)
    }

    try {
        if (Test-Path -LiteralPath $resolvedTempDirectory -PathType Container) {
            Remove-Item -LiteralPath $resolvedTempDirectory -Recurse -Force
        }
    } catch {
        $cleanupFailures.Add('The exact validated public-app edge temporary directory could not be removed.')
    }

    if ($hadPreviousTestPort) {
        $env:PERSONAL_MEMO_APP_EDGE_TEST_PORT = $previousTestPort
    } else {
        Remove-Item Env:PERSONAL_MEMO_APP_EDGE_TEST_PORT -ErrorAction SilentlyContinue
    }
    $queryMarker = $null
    $cookieMarker = $null
    $bodyMarker = $null
    $headerMarker = $null
    $pathMarker = $null
    $secretMarkers = @()
}

if ($null -ne $testFailure) {
    if ($cleanupFailures.Count -ne 0) {
        throw (
            'The isolated public-app edge smoke failed and exact cleanup also needs review. ' +
            'Test: ' + $testFailure.Exception.Message + ' Cleanup: ' + ($cleanupFailures -join ' | ')
        )
    }
    throw $testFailure
}
if ($cleanupFailures.Count -ne 0) {
    throw 'The isolated public-app edge smoke passed, but exact cleanup failed: ' + ($cleanupFailures -join ' | ')
}
