[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$publicScripts = [IO.Path]::GetFullPath($PSScriptRoot)
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path (Join-Path $publicScripts '..') '..'))

function Read-SourceContractFile {
    param([Parameter(Mandatory = $true)][string] $Path)
    $resolved = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "Required public-feed source file was not found: $resolved"
    }
    return [IO.File]::ReadAllText($resolved)
}

function Assert-SourceContains {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Needle,
        [Parameter(Mandatory = $true)][string] $Contract
    )
    if ($Source.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) {
        throw "Public-feed source contract failed ($Contract)."
    }
}

function Assert-SourceExcludes {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Needle,
        [Parameter(Mandatory = $true)][string] $Contract
    )
    if ($Source.IndexOf($Needle, [StringComparison]::Ordinal) -ge 0) {
        throw "Public-feed source exclusion contract failed ($Contract)."
    }
}

function Assert-SourceOrder {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Earlier,
        [Parameter(Mandatory = $true)][string] $Later,
        [Parameter(Mandatory = $true)][string] $Contract
    )
    $earlierIndex = $Source.IndexOf($Earlier, [StringComparison]::Ordinal)
    $laterIndex = $Source.IndexOf($Later, [StringComparison]::Ordinal)
    if ($earlierIndex -lt 0 -or $laterIndex -lt 0 -or $earlierIndex -ge $laterIndex) {
        throw "Public-feed source ordering contract failed ($Contract)."
    }
}

function Get-SourceBraceBlock {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Marker,
        [Parameter(Mandatory = $true)][string] $Contract
    )
    $markerIndex = $Source.IndexOf($Marker, [StringComparison]::Ordinal)
    if ($markerIndex -lt 0) {
        throw "Public-feed source block marker failed ($Contract)."
    }
    $openingBrace = $Source.IndexOf('{', $markerIndex)
    if ($openingBrace -lt 0) {
        throw "Public-feed source block opening brace failed ($Contract)."
    }
    $depth = 0
    for ($index = $openingBrace; $index -lt $Source.Length; $index++) {
        if ($Source[$index] -ceq '{') {
            $depth++
        } elseif ($Source[$index] -ceq '}') {
            $depth--
            if ($depth -eq 0) {
                return $Source.Substring($markerIndex, $index - $markerIndex + 1)
            }
        }
    }
    throw "Public-feed source block closing brace failed ($Contract)."
}

foreach ($scriptFile in Get-ChildItem -LiteralPath $publicScripts -Filter '*.ps1' -File) {
    $tokens = $null
    $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile(
        $scriptFile.FullName,
        [ref] $tokens,
        [ref] $parseErrors
    )
    if ($parseErrors.Count -ne 0) {
        throw "Public PowerShell parse contract failed: $($scriptFile.Name): $($parseErrors[0].Message)"
    }
}

$preflightCompose = Read-SourceContractFile -Path (Join-Path $repositoryRoot 'compose.public-feed.yaml')
$activationCompose = Read-SourceContractFile -Path (
    Join-Path $repositoryRoot 'compose.public-feed-activation.yaml'
)
$testCompose = Read-SourceContractFile -Path (Join-Path $repositoryRoot 'compose.public-feed.test.yaml')
$edgeSource = Read-SourceContractFile -Path (Join-Path $repositoryRoot 'calendar-edge\nginx.conf')
$edgeDockerfile = Read-SourceContractFile -Path (Join-Path $repositoryRoot 'calendar-edge\Dockerfile')
$smokeSource = Read-SourceContractFile -Path (
    Join-Path $publicScripts 'Test-PersonalMemoPublicFeedEdge.ps1'
)

foreach ($requiredPreflightFragment in @(
    'calendar-feed-edge:',
    '      - default',
    '127.0.0.1:${PERSONAL_MEMO_CALENDAR_EDGE_PORT:-8787}:8080',
    'calendar-publication:',
    'internal: true',
    'read_only: true',
    'no-new-privileges:true',
    'cap_drop:',
    '- ALL'
)) {
    Assert-SourceContains `
        -Source $preflightCompose `
        -Needle $requiredPreflightFragment `
        -Contract "loopback-only preflight fragment $requiredPreflightFragment"
}
foreach ($forbiddenPreflightFragment in @(
    'APP_CALENDAR_FEED_PUBLICATION_ENABLED',
    'APP_CALENDAR_FEED_PUBLIC_ORIGIN',
    'APP_CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION',
    '0.0.0.0:',
    '8443:8443'
)) {
    Assert-SourceExcludes `
        -Source $preflightCompose `
        -Needle $forbiddenPreflightFragment `
        -Contract "preflight cannot activate or widen publication $forbiddenPreflightFragment"
}

foreach ($requiredActivationFragment in @(
    'APP_CALENDAR_FEED_PUBLICATION_ENABLED: "true"',
    'APP_CALENDAR_FEED_PUBLIC_ORIGIN: ${APP_CALENDAR_FEED_PUBLIC_ORIGIN:?Set a reviewed canonical public HTTPS origin}',
    'APP_CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION: ${APP_CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION:?Set the reviewed public calendar consent policy version}'
)) {
    Assert-SourceContains `
        -Source $activationCompose `
        -Needle $requiredActivationFragment `
        -Contract "explicit activation fragment $requiredActivationFragment"
}
foreach ($forbiddenActivationFragment in @('ports:', 'build:', 'calendar-feed-edge:', 'postgres:')) {
    Assert-SourceExcludes `
        -Source $activationCompose `
        -Needle $forbiddenActivationFragment `
        -Contract "activation overlay changes backend authority only $forbiddenActivationFragment"
}

if ([regex]::Matches(
    $edgeSource,
    '(?m)^\s*location\s+=\s+/calendar/v1/feed\.ics\s*\{\s*$'
).Count -ne 1) {
    throw 'The public edge must define exactly one exact calendar feed location.'
}
$feedLocation = Get-SourceBraceBlock `
    -Source $edgeSource `
    -Marker 'location = /calendar/v1/feed.ics' `
    -Contract 'the exact public calendar feed location'
foreach ($requiredEdgeFragment in @(
    'map $uri $calendar_feed_safe_route {',
    'default "rejected";',
    '/calendar/v1/feed.ics "calendar-feed";',
    'map $request_method $calendar_feed_safe_method {',
    'default "other";',
    'GET "GET";',
    'HEAD "HEAD";',
    'method=$calendar_feed_safe_method route=$calendar_feed_safe_route',
    'error_log /dev/null emerg;',
    'error_page 400 401 403 404 405 408 413 414 431 500 501 502 503 504 =404 /_internal/errors/not-found;',
    'error_page 429 =429 /_internal/errors/rate-limited;',
    'location = /_internal/errors/not-found {',
    'location = /_internal/errors/rate-limited {',
    'internal;',
    'limit_req_zone $calendar_feed_limit_key',
    'limit_conn_zone $calendar_feed_limit_key',
    'client_header_timeout 5s;',
    'client_body_timeout 5s;',
    'client_max_body_size 1k;',
    'keepalive_requests 20;',
    'send_timeout 10s;',
    'location / {',
    'return 404;'
)) {
    Assert-SourceContains `
        -Source $edgeSource `
        -Needle $requiredEdgeFragment `
        -Contract "edge rejection/bound/log fragment $requiredEdgeFragment"
}
foreach ($requiredFeedFragment in @(
    'if ($request_method !~ ^(?:GET|HEAD)$)',
    'if ($request_uri !~ "^/calendar/v1/feed\\.ics\\?token=[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")',
    'if ($content_length != "")',
    'if ($http_transfer_encoding != "")',
    'limit_req zone=calendar_feed_rate burst=20 nodelay;',
    'limit_conn calendar_feed_connections 8;',
    'proxy_pass http://backend:8080;',
    'proxy_pass_request_headers off;',
    'proxy_pass_request_body off;',
    'proxy_set_header Content-Length "";',
    'proxy_set_header Host "calendar-feed-internal";',
    'proxy_set_header Referer "";',
    'proxy_connect_timeout 2s;',
    'proxy_send_timeout 5s;',
    'proxy_read_timeout 10s;',
    'proxy_next_upstream off;',
    'proxy_intercept_errors on;',
    'proxy_max_temp_file_size 0;',
    'proxy_hide_header Set-Cookie;',
    'proxy_redirect off;'
)) {
    Assert-SourceContains `
        -Source $feedLocation `
        -Needle $requiredFeedFragment `
        -Contract "exact feed route fragment $requiredFeedFragment"
}
foreach ($errorLocationMarker in @(
    'location = /_internal/errors/not-found',
    'location = /_internal/errors/rate-limited'
)) {
    $errorLocation = Get-SourceBraceBlock `
        -Source $edgeSource `
        -Marker $errorLocationMarker `
        -Contract "bodyless internal error handler $errorLocationMarker"
    foreach ($requiredErrorFragment in @('internal;', 'default_type "";', 'return 204;')) {
        Assert-SourceContains `
            -Source $errorLocation `
            -Needle $requiredErrorFragment `
            -Contract "bodyless error fragment $errorLocationMarker $requiredErrorFragment"
    }
}

$safeLogFormat = [regex]::Match(
    $edgeSource,
    '(?ms)^\s*log_format\s+calendar_feed_edge_safe\s+(?<value>.*?);\s*$'
)
if (-not $safeLogFormat.Success) {
    throw 'The public edge safe log format was not found.'
}
foreach ($forbiddenLogValue in @(
    '$uri',
    '$request_method',
    '$request_uri',
    '$args',
    '$query_string',
    '$http_referer'
)) {
    Assert-SourceExcludes `
        -Source $safeLogFormat.Groups['value'].Value `
        -Needle $forbiddenLogValue `
        -Contract "safe logs exclude client-controlled target $forbiddenLogValue"
}
if ([regex]::IsMatch(
    $feedLocation,
    '(?m)^\s*(?:rewrite|access_log)\b|proxy_pass\s+[^;]*\$(?:args|request_uri|query_string)'
)) {
    throw 'The exact feed location must not rewrite or separately log a query-bearing target.'
}

foreach ($requiredContainerFragment in @(
    'FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6',
    'COPY --chown=101:101 nginx.conf /etc/nginx/nginx.conf',
    'HEALTHCHECK',
    'http://127.0.0.1:8080/_internal/health'
)) {
    Assert-SourceContains `
        -Source $edgeDockerfile `
        -Needle $requiredContainerFragment `
        -Contract "edge container fragment $requiredContainerFragment"
}
foreach ($requiredTestComposeFragment in @(
    '127.0.0.1::8080',
    'condition: service_healthy',
    'read_only: true',
    'no-new-privileges:true'
)) {
    Assert-SourceContains `
        -Source $testCompose `
        -Needle $requiredTestComposeFragment `
        -Contract "isolated edge Compose fragment $requiredTestComposeFragment"
}

foreach ($requiredSmokeFragment in @(
    "Get-Command curl.exe -ErrorAction SilentlyContinue",
    "Get-Command curl -ErrorAction SilentlyContinue",
    '[IO.Path]::DirectorySeparatorChar',
    "'--noproxy', '127.0.0.1'",
    "'--path-as-is'",
    "'--head'",
    "Name = 'put'",
    "Name = 'patch'",
    "Name = 'delete'",
    "Name = 'options'",
    "Name = 'trace'",
    "Name = 'bearer-as-method'",
    "Name = 'bearer-in-path'",
    "Name = 'internal-health'",
    "Name = 'upstream-failure'",
    "Name = 'noncanonical-token'",
    "Name = 'encoded-slash'",
    "Name = 'double-slash'",
    "Name = 'get-with-body'",
    "Name = 'chunked-body'",
    '"Authorization: Bearer $token"',
    '"Cookie: caller=$token"',
    '"User-Agent: $token"',
    "-Name 'Content-Type'",
    "-Name 'Set-Cookie'",
    "-Name 'Cache-Control'",
    "-Name 'Referrer-Policy'",
    "-cne '404'",
    "-ceq '429'",
    "IndexOf('?token=', [StringComparison]::OrdinalIgnoreCase)",
    "@('down', '--volumes', '--remove-orphans', '--rmi', 'local')"
)) {
    Assert-SourceContains `
        -Source $smokeSource `
        -Needle $requiredSmokeFragment `
        -Contract "isolated smoke fragment $requiredSmokeFragment"
}
Assert-SourceOrder `
    -Source $smokeSource `
    -Earlier "Invoke-TestCompose -Arguments @('up', '-d', '--build', '--wait')" `
    -Later "Invoke-TestCompose -Arguments @('down', '--volumes', '--remove-orphans', '--rmi', 'local')" `
    -Contract 'isolated edge cleanup follows startup'

Write-Host 'Personal Memo public-feed edge source contracts are valid.'
