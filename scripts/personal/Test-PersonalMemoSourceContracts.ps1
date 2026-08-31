[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$personalScripts = [IO.Path]::GetFullPath($PSScriptRoot)
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $personalScripts '..\..'))
$commonScript = Join-Path $personalScripts 'PersonalMemo.Common.ps1'
. $commonScript

function Read-SourceContractFile {
    param([Parameter(Mandatory = $true)][string] $Path)
    return [IO.File]::ReadAllText([IO.Path]::GetFullPath($Path))
}

function Assert-SourceContains {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Needle,
        [Parameter(Mandatory = $true)][string] $Contract
    )
    if ($Source.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) {
        throw "Source contract failed ($Contract)."
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
        throw "Source ordering contract failed ($Contract)."
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
        throw "Source block marker failed ($Contract)."
    }
    $openingBrace = $Source.IndexOf('{', $markerIndex)
    if ($openingBrace -lt 0) {
        throw "Source block opening brace failed ($Contract)."
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
    throw "Source block closing brace failed ($Contract)."
}

foreach ($scriptFile in Get-ChildItem -LiteralPath $personalScripts -Filter '*.ps1' -File) {
    $tokens = $null
    $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile(
        $scriptFile.FullName,
        [ref] $tokens,
        [ref] $parseErrors
    )
    if ($parseErrors.Count -ne 0) {
        throw "PowerShell parse contract failed: $($scriptFile.Name): $($parseErrors[0].Message)"
    }
}

$expectedBootstrap = @('run', '--build', '--rm', 'backend', 'bootstrap-account')
$actualBootstrap = @(Get-PersonalMemoInitialAccountComposeArguments)
if (($actualBootstrap -join '|') -cne ($expectedBootstrap -join '|')) {
    throw 'Initial-account Compose command must build the current backend and preserve its TTY.'
}
if ($actualBootstrap -contains '-T' -or $actualBootstrap -contains '--no-TTY') {
    throw 'Initial-account Compose command must not disable its interactive TTY.'
}

$accountSource = Read-SourceContractFile -Path (Join-Path $personalScripts 'Initialize-PersonalAccount.ps1')
Assert-SourceContains `
    -Source $accountSource `
    -Needle '$bootstrapArguments = @(Get-PersonalMemoInitialAccountComposeArguments)' `
    -Contract 'the account initializer uses the fixed bootstrap command'
Assert-SourceContains `
    -Source $accountSource `
    -Needle '-CommandArguments $bootstrapArguments' `
    -Contract 'the account initializer passes only the fixed bootstrap command'

$initializerSource = Read-SourceContractFile -Path (Join-Path $personalScripts 'Initialize-PersonalMemo.ps1')
Assert-SourceOrder `
    -Source $initializerSource `
    -Earlier 'Set-PersonalMemoPrivateDirectoryAcl -Path $personalDirectory' `
    -Later '$null = New-Item -ItemType Directory -Path $stageDirectory' `
    -Contract 'the dedicated parent is private before the TLS staging directory is created'
Assert-SourceOrder `
    -Source $initializerSource `
    -Earlier 'Set-PersonalMemoPrivateDirectoryAcl -Path $stageDirectory' `
    -Later "Invoke-GitOpenSsl -OpenSsl `$openSsl -OpenSslArguments @('genrsa'" `
    -Contract 'the empty TLS staging directory is private before key generation'
Assert-SourceOrder `
    -Source $initializerSource `
    -Earlier 'Set-PersonalMemoPrivateDirectoryAcl -Path $backupDirectory' `
    -Later '$databasePassword = New-PersonalMemoHexSecret' `
    -Contract 'the backup directory is private before configuration commit'
Assert-SourceOrder `
    -Source $initializerSource `
    -Earlier 'Set-PersonalMemoPrivateFileAcl -Path $temporaryEnv' `
    -Later '[IO.File]::WriteAllLines($temporaryEnv' `
    -Contract 'the empty temporary environment file is private before secret content is written'
Assert-SourceOrder `
    -Source $initializerSource `
    -Earlier 'Move-Item -LiteralPath $temporaryEnv -Destination $envFile' `
    -Later 'Assert-PersonalMemoPrivateAcl -Path $envFile' `
    -Contract 'the committed environment file ACL is rechecked'
Assert-SourceContains `
    -Source $initializerSource `
    -Needle 'if ($HttpsPort -ne 8443)' `
    -Contract 'the initializer rejects a TLS host port other than 8443'

$backupSource = Read-SourceContractFile -Path (Join-Path $personalScripts 'Backup-PersonalMemo.ps1')
foreach ($requiredBackupFragment in @(
    '--exclude-table-data=spring_session',
    '--exclude-table-data=spring_session_attributes',
    'TABLE DATA\s+\S+\s+spring_session',
    'Set-PersonalMemoPrivateFileAcl -Path $partialDump',
    'Set-PersonalMemoPrivateFileAcl -Path $partialChecksum',
    'Set-PersonalMemoPrivateFileAcl -Path $finalDump',
    'Set-PersonalMemoPrivateFileAcl -Path $finalChecksum'
)) {
    Assert-SourceContains `
        -Source $backupSource `
        -Needle $requiredBackupFragment `
        -Contract "backup privacy fragment $requiredBackupFragment"
}

$commonSource = Read-SourceContractFile -Path $commonScript
$startSource = Read-SourceContractFile -Path (Join-Path $personalScripts 'Start-PersonalMemo.ps1')
$stopSource = Read-SourceContractFile -Path (Join-Path $personalScripts 'Stop-PersonalMemo.ps1')
$publicTopologyGuardSource = Get-SourceBraceBlock `
    -Source $commonSource `
    -Marker 'function Assert-PersonalMemoCloudflarePublicTopologyInactive' `
    -Contract 'the private stack excludes every active Cloudflare public topology'
foreach ($requiredPublicTopologyGuardFragment in @(
    "'PersonalMemoCalendarCloudflareTunnel'",
    "'PersonalMemoAppCloudflareTunnel'",
    'Add-Type -AssemblyName System.ServiceProcess -ErrorAction Stop',
    '[System.ServiceProcess.ServiceController]::GetServices()',
    '[System.ServiceProcess.ServiceControllerStatus]::Stopped',
    "[Diagnostics.Process]::GetProcessesByName('cloudflared')",
    "'ps', '--quiet'",
    '"label=com.docker.compose.project=$($Layout.ProjectName)"',
    "@('calendar-feed-edge', 'app-public-edge')",
    '"label=com.docker.compose.service=$edgeService"',
    'Invoke-PersonalMemoDocker -Capture',
    '[string]::IsNullOrWhiteSpace($edgeIds)',
    '$runningEdgeIds.Count -ne 0'
)) {
    Assert-SourceContains `
        -Source $publicTopologyGuardSource `
        -Needle $requiredPublicTopologyGuardFragment `
        -Contract "private/public topology exclusion fragment $requiredPublicTopologyGuardFragment"
}
foreach ($forbiddenPublicTopologyGuardFragment in @(
    "'inspect'",
    "'logs'",
    "'exec'",
    'Get-Content',
    'Get-ItemProperty',
    'postgres'
)) {
    if ($publicTopologyGuardSource.IndexOf(
        $forbiddenPublicTopologyGuardFragment,
        [StringComparison]::OrdinalIgnoreCase
    ) -ge 0) {
        throw "The private/public topology guard must not inspect $forbiddenPublicTopologyGuardFragment."
    }
}
Assert-SourceOrder `
    -Source $startSource `
    -Earlier 'Assert-PersonalMemoCloudflarePublicTopologyInactive -Layout $layout' `
    -Later "Invoke-PersonalMemoCompose -Layout `$layout -IncludePersonal -CommandArguments @('up'" `
    -Contract 'the private start guard precedes its first Compose mutation'
Assert-SourceOrder `
    -Source $stopSource `
    -Earlier 'Assert-PersonalMemoCloudflarePublicTopologyInactive -Layout $layout' `
    -Later "Invoke-PersonalMemoCompose -Layout `$layout -IncludePersonal -CommandArguments @('stop')" `
    -Contract 'the private stop guard precedes its Compose mutation'

$rotationSource = Read-SourceContractFile -Path (Join-Path $personalScripts 'Rotate-PersonalMemoDatabasePassword.ps1')
foreach ($requiredRotationFragment in @(
    "New-PersonalMemoHexSecret -ByteCount 32",
    '[IO.File]::Replace($stagedPath, $layout.EnvFile, $rollbackPath, $true)',
    '[IO.FileShare]::None',
    'Invoke-PersonalMemoForwardOnlyPostgresInput',
    "'up', '-d', '--no-build', '--force-recreate', '--wait', 'postgres', 'backend', 'frontend'",
    'if (($alterSucceeded -or $alterInputMayHaveReachedServer) -and',
    'Get-PersonalMemoPostgresVolumeName -ContainerId $postgresAfter',
    "'http://127.0.0.1:5173/api/v1/health'"
)) {
    Assert-SourceContains `
        -Source $rotationSource `
        -Needle $requiredRotationFragment `
        -Contract "database credential rotation fragment $requiredRotationFragment"
}
if ($rotationSource.Contains("'down'") -or $rotationSource.Contains("'--volumes'")) {
    throw 'Database credential rotation must not stop the project or delete its canonical volume.'
}
foreach ($requiredProtectedInputFragment in @(
    "'exec', '-i', `$ContainerId",
    'New-Object Diagnostics.ProcessStartInfo',
    '$startInfo.RedirectStandardError = $true',
    '$process.StandardInput.Write($Sql)'
)) {
    Assert-SourceContains `
        -Source $commonSource `
        -Needle $requiredProtectedInputFragment `
        -Contract "protected PostgreSQL input fragment $requiredProtectedInputFragment"
}
if ($rotationSource.Contains('2>&1') -or $commonSource.Contains('2>&1')) {
    throw 'Secret-bearing PostgreSQL diagnostics must not enter PowerShell native error history.'
}
Assert-SourceOrder `
    -Source $rotationSource `
    -Earlier '$rotationLockStream = [IO.File]::Open(' `
    -Later '$layoutArguments = @{ ProjectName = $ProjectName }' `
    -Contract 'the cross-session rotation lock is acquired before reading the environment layout'

$restoreSource = Read-SourceContractFile -Path (Join-Path $personalScripts 'Test-PersonalMemoRestore.ps1')
foreach ($requiredRestoreFragment in @(
    'TRUNCATE TABLE spring_session CASCADE',
    'select count(*) from initial_account_provisioning',
    "claimed.status <> 'LEGACY_UNCLAIMED'",
    'left join users owner on owner.id = credential.user_id',
    'ExpectedFlywayVersion',
    'ExpectedBackupFlywayVersion',
    'RequireZeroCalendarBackfill',
    'RequireV23LocalOnlyConsentBackfill',
    "`$ExpectedBackupFlywayVersion -cne '20' -or `$ExpectedFlywayVersion -cne '22'",
    "`$ExpectedBackupFlywayVersion -cne '22' -or `$ExpectedFlywayVersion -cne '23'",
    "'--no-psqlrc'",
    'select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1',
    'select count(*) from flyway_schema_history where not success',
    "to_regclass('public.event_details')",
    "to_regclass('public.calendar_feeds')",
    "to_regclass('public.calendar_feed_entries')",
    "('publication_scope')",
    "('public_consent_policy_version')",
    "('public_consent_granted_at')",
    "publication_scope <> 'LOCAL_ONLY'",
    'V23 keeps every restored feed local-only without a consent pin',
    '(select count(*) from event_details)',
    '(select count(*) from calendar_feeds)',
    '(select count(*) from calendar_feed_entries)'
)) {
    Assert-SourceContains `
        -Source $restoreSource `
        -Needle $requiredRestoreFragment `
        -Contract "restore invariant fragment $requiredRestoreFragment"
}
Assert-SourceOrder `
    -Source $restoreSource `
    -Earlier "-Invariant 'restored backup Flyway version before migration'" `
    -Later "Invoke-PersonalMemoCompose -Layout `$layout -CommandArguments @('up', '-d', '--build', '--wait', 'backend')" `
    -Contract 'the backup Flyway version is verified before the backend can migrate it'
Assert-SourceOrder `
    -Source $restoreSource `
    -Earlier "-Invariant 'latest successful Flyway version'" `
    -Later '$verified = $true' `
    -Contract 'the migrated Flyway target is verified before cleanup eligibility'
Assert-SourceOrder `
    -Source $restoreSource `
    -Earlier "-Invariant 'V21 and V22 migrations do not backfill calendar data'" `
    -Later '$verified = $true' `
    -Contract 'zero calendar backfill is verified before cleanup eligibility'
Assert-SourceOrder `
    -Source $restoreSource `
    -Earlier "-Invariant 'V23 keeps every restored feed local-only without a consent pin'" `
    -Later '$verified = $true' `
    -Contract 'V23 local-only consent backfill is verified before cleanup eligibility'

$privateFeedSmokeSource = Read-SourceContractFile -Path (Join-Path $personalScripts 'Test-PersonalMemoPrivateFeedRoute.ps1')
foreach ($requiredPrivateFeedSmokeFragment in @(
    '[Security.Cryptography.RandomNumberGenerator]::Create()',
    '^[A-Za-z0-9_-]{43}$',
    '/calendar/v1/feed.ics?token=$token',
    "'--noproxy', '*'",
    "'--proto', '=https'",
    "'--cacert', `$CaFile",
    "'--ssl-revoke-best-effort'",
    "`$ErrorActionPreference = 'Continue'",
    "-cne '404|0'",
    "-Name 'Cache-Control'",
    "-Name 'Referrer-Policy'",
    "-Name 'Set-Cookie'",
    "-Name 'Content-Type'",
    "@('logs', '--no-color', '--since', `$probeStartedAt, 'frontend')",
    "@('logs', '--no-color', '--since', `$probeStartedAt, 'backend')",
    '[DateTime]::UtcNow.AddSeconds(5)',
    '(Get-Item -LiteralPath $CurlErrorFile).Length',
    '$frontendLogs.IndexOf($token, [StringComparison]::Ordinal)',
    '$backendLogs.IndexOf($token, [StringComparison]::Ordinal)',
    "'method=GET route=calendar-feed status=404 bytes=0'",
    "'method=HEAD route=calendar-feed status=404 bytes=0'",
    "IndexOf('?token=', [StringComparison]::OrdinalIgnoreCase)",
    "StartsWith('personal-memo-feed-route-')"
)) {
    Assert-SourceContains `
        -Source $privateFeedSmokeSource `
        -Needle $requiredPrivateFeedSmokeFragment `
        -Contract "private feed route smoke fragment $requiredPrivateFeedSmokeFragment"
}
Assert-SourceOrder `
    -Source $privateFeedSmokeSource `
    -Earlier "`$outputTarget = 'NUL'" `
    -Later "if (`$Method -ceq 'GET')" `
    -Contract 'HEAD output defaults to NUL before GET opts into a body file'
if ($privateFeedSmokeSource.Contains("'--insecure'") -or
    $privateFeedSmokeSource.Contains("'-k'") -or
    $privateFeedSmokeSource.Contains("'--location'")) {
    throw 'The private feed route smoke must not weaken TLS verification or follow redirects.'
}

Assert-SourceContains `
    -Source $commonSource `
    -Needle "if (`$expectedTlsPort -cne '8443')" `
    -Contract 'the personal Compose contract fixes the TLS host port to 8443'
$personalCompose = Read-SourceContractFile -Path (Join-Path $repositoryRoot 'compose.personal.yaml')
Assert-SourceContains `
    -Source $personalCompose `
    -Needle ':8443:8443"' `
    -Contract 'the private Compose overlay publishes fixed port 8443'
if ($personalCompose.Contains('${PERSONAL_MEMO_HTTPS_PORT')) {
    throw 'The private Compose overlay must not interpolate a different TLS host port.'
}
foreach ($forbiddenPublicFeedSetting in @(
    'APP_CALENDAR_FEED_PUBLICATION_ENABLED',
    'APP_CALENDAR_FEED_PUBLIC_ORIGIN',
    'APP_CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION'
)) {
    if ($personalCompose.Contains($forbiddenPublicFeedSetting)) {
        throw "The personal overlay must remain LOCAL_ONLY and must not pass $forbiddenPublicFeedSetting."
    }
}

$frontendDockerfile = Read-SourceContractFile -Path (Join-Path $repositoryRoot 'frontend\Dockerfile')
Assert-SourceContains `
    -Source $frontendDockerfile `
    -Needle 'NGINX_ENVSUBST_FILTER=^API_PROXY_TARGET$' `
    -Contract 'Nginx template rendering substitutes only the backend proxy target'
$nginxTemplate = Read-SourceContractFile -Path (Join-Path $repositoryRoot 'frontend\nginx\default.conf')
$renderedNginx = $nginxTemplate.Replace('${API_PROXY_TARGET}', 'http://backend:8080')
if ($renderedNginx.Contains('${API_PROXY_TARGET}')) {
    throw 'The production Nginx source contract must inspect a fully rendered proxy target.'
}
$safeLogFormat = [regex]::Match(
    $renderedNginx,
    '(?m)^\s*log_format\s+personal_memo_safe\s+(?<value>[^;]+);\s*$'
)
if (-not $safeLogFormat.Success) {
    throw 'Production Nginx must define the personal_memo_safe access-log format.'
}
$loggedVariables = @(
    [regex]::Matches($safeLogFormat.Groups['value'].Value, '\$[A-Za-z0-9_]+') |
        ForEach-Object { $_.Value }
)
$allowedLogVariables = @(
    '$personal_memo_safe_method',
    '$personal_memo_safe_route',
    '$status',
    '$body_bytes_sent',
    '$request_time'
)
foreach ($requiredLogVariable in $allowedLogVariables) {
    if ($loggedVariables -cnotcontains $requiredLogVariable) {
        throw "Production Nginx safe access log is missing $requiredLogVariable."
    }
}
$unexpectedLogVariables = @($loggedVariables | Where-Object { $allowedLogVariables -cnotcontains $_ })
if ($unexpectedLogVariables.Count -ne 0) {
    throw "Production Nginx safe access log has non-allow-listed variables: $($unexpectedLogVariables -join ', ')."
}
Assert-SourceContains `
    -Source $renderedNginx `
    -Needle 'access_log /var/log/nginx/access.log personal_memo_safe;' `
    -Contract 'the production server selects the fixed-class access-log format'
Assert-SourceContains `
    -Source $renderedNginx `
    -Needle 'error_log /dev/stderr emerg;' `
    -Contract 'the production server suppresses request-scoped error details including raw targets'
foreach ($requiredSafeLogFragment in @(
    'map $request_method $personal_memo_safe_method {',
    'map $uri $personal_memo_safe_route {',
    'default "app";',
    '/calendar/v1/feed.ics "calendar-feed";',
    '/api/v1/events/calendar.ics "calendar-export";',
    '~^/api/v1/auth(?:/|$) "auth";',
    '~^/api(?:/|$) "api";',
    '~^/(?:oauth2|login/oauth2)(?:/|$) "oauth";',
    'method=$personal_memo_safe_method route=$personal_memo_safe_route'
)) {
    Assert-SourceContains `
        -Source $renderedNginx `
        -Needle $requiredSafeLogFragment `
        -Contract "fixed-class access log fragment $requiredSafeLogFragment"
}
foreach ($forbiddenSafeLogVariable in @(
    '$remote_addr',
    '$time_local',
    '$request_method',
    '$uri',
    '$request_uri',
    '$args',
    '$query_string',
    '$http_referer',
    '$http_cookie'
)) {
    if ($safeLogFormat.Groups['value'].Value.Contains($forbiddenSafeLogVariable)) {
        throw "Production Nginx safe access log contains $forbiddenSafeLogVariable."
    }
}
foreach ($requiredNoStoreFragment in @(
    'map $uri $personal_memo_cache_control {',
    '/calendar/v1/feed.ics "no-store";',
    '~^/(?:api|oauth2)(?:/|$) "no-store";',
    '~^/login/oauth2(?:/|$) "no-store";',
    'add_header Cache-Control $personal_memo_cache_control always;',
    'proxy_hide_header Cache-Control;'
)) {
    Assert-SourceContains `
        -Source $renderedNginx `
        -Needle $requiredNoStoreFragment `
        -Contract "Nginx API no-store fragment $requiredNoStoreFragment"
}
$calendarFeedLocation = Get-SourceBraceBlock `
    -Source $renderedNginx `
    -Marker 'location = /calendar/v1/feed.ics' `
    -Contract 'the private same-origin calendar feed proxy'
if ([regex]::Matches(
    $renderedNginx,
    '(?m)^\s*location\s+=\s+/calendar/v1/feed\.ics\s*\{\s*$'
).Count -ne 1) {
    throw 'The private listener must define exactly one exact calendar feed proxy location.'
}
foreach ($requiredCalendarFeedFragment in @(
    'limit_except GET {',
    'deny all;',
    'error_log /dev/null emerg;',
    'proxy_pass http://backend:8080;',
    'proxy_pass_request_headers off;',
    'proxy_pass_request_body off;',
    'proxy_set_header Content-Length "";',
    'proxy_set_header Referer "";',
    'proxy_set_header X-Forwarded-For $remote_addr;',
    'proxy_hide_header Cache-Control;',
    'proxy_hide_header Expires;',
    'proxy_hide_header Referrer-Policy;',
    'proxy_hide_header Set-Cookie;',
    'proxy_redirect off;'
)) {
    Assert-SourceContains `
        -Source $calendarFeedLocation `
        -Needle $requiredCalendarFeedFragment `
        -Contract "calendar feed proxy fragment $requiredCalendarFeedFragment"
}
if ([regex]::Matches(
    $calendarFeedLocation,
    '(?m)^\s*proxy_pass\s+http://backend:8080;\s*$'
).Count -ne 1) {
    throw 'The calendar feed proxy must preserve the original fixed path and query implicitly.'
}
if ([regex]::IsMatch(
    $calendarFeedLocation,
    '\$(?:args|arg_[A-Za-z0-9_]+|http_referer|is_args|query_string|request_uri)'
)) {
    throw 'The calendar feed proxy must not copy a query-bearing value into a URI or header.'
}
if ([regex]::IsMatch($calendarFeedLocation, '(?m)^\s*(?:access_log|rewrite)\b')) {
    throw 'The calendar feed proxy must inherit the fixed-uri safe access log and must not rewrite its target.'
}
foreach ($requiredCalendarFeedResponseFragment in @(
    'map $uri $personal_memo_referrer_policy {',
    '/calendar/v1/feed.ics "no-referrer";',
    'add_header Referrer-Policy $personal_memo_referrer_policy always;'
)) {
    Assert-SourceContains `
        -Source $renderedNginx `
        -Needle $requiredCalendarFeedResponseFragment `
        -Contract "calendar feed response privacy fragment $requiredCalendarFeedResponseFragment"
}
foreach ($staticLocation in @('/assets/', '/icons/', '/sw.js', '/registerSW.js', '/manifest.webmanifest')) {
    if (-not [regex]::IsMatch(
        $renderedNginx,
        "(?ms)location\s+(?:\^~\s+|=\s+)?$([regex]::Escape($staticLocation))\s*\{.*?access_log\s+off;"
    )) {
        throw "Production Nginx must keep static access logging disabled for $staticLocation."
    }
}
$accessLogValues = @(
    [regex]::Matches(
        $renderedNginx,
        '(?m)^\s*access_log\s+(?<value>[^;]+);\s*$'
    ) | ForEach-Object { $_.Groups['value'].Value.Trim() }
)
$disabledAccessLogCount = @($accessLogValues | Where-Object { $_ -ceq 'off' }).Count
$safeAccessLogCount = @(
    $accessLogValues |
        Where-Object { $_ -ceq '/var/log/nginx/access.log personal_memo_safe' }
).Count
if ($accessLogValues.Count -ne 7 -or $safeAccessLogCount -ne 1 -or $disabledAccessLogCount -ne 6) {
    throw 'Production Nginx access-log directives must be one safe server log and six disabled asset logs.'
}
if ([regex]::Matches(
    $renderedNginx,
    '(?m)^\s*access_log\s+[^;]*(?:\$request(?:\s|"|;)|\$request_uri|\$args|\$query_string|\$is_args)[^;]*;\s*$'
).Count -ne 0) {
    throw 'Production Nginx access-log directives must not reference a query-bearing request variable.'
}

$viteConfig = Read-SourceContractFile -Path (Join-Path $repositoryRoot 'frontend\vite.config.ts')
$networkOnlyPatterns = [regex]::Match(
    $viteConfig,
    '(?s)export const BACKEND_NETWORK_ONLY_PATH_PATTERNS\s*=\s*\[(?<value>.*?)\];'
)
if (-not $networkOnlyPatterns.Success -or
    $networkOnlyPatterns.Groups['value'].Value.IndexOf(
        '/^\/calendar\/v1\/feed\.ics(?:\?.*)?$/',
        [StringComparison]::Ordinal
    ) -lt 0) {
    throw 'The query-bearing calendar feed target must bypass the service-worker app shell.'
}
foreach ($requiredPwaNetworkOnlyFragment in @(
    "export const CALENDAR_FEED_DEV_PROXY_CONTEXT = '^/calendar/v1/feed\\.ics(?:\\?.*)?$';",
    'navigateFallbackDenylist: BACKEND_NETWORK_ONLY_PATH_PATTERNS',
    'urlPattern: ({ url }) => isBackendNetworkOnlyPath(url.pathname)',
    '[CALENDAR_FEED_DEV_PROXY_CONTEXT]: backendProxy'
)) {
    Assert-SourceContains `
        -Source $viteConfig `
        -Needle $requiredPwaNetworkOnlyFragment `
        -Contract "calendar feed browser network-only fragment $requiredPwaNetworkOnlyFragment"
}

$applicationConfiguration = Read-SourceContractFile -Path (
    Join-Path $repositoryRoot 'backend\src\main\resources\application.yml'
)
foreach ($requiredPublicationDefault in @(
    'enabled: ${APP_CALENDAR_FEED_PUBLICATION_ENABLED:false}',
    'public-origin: ${APP_CALENDAR_FEED_PUBLIC_ORIGIN:}',
    'consent-policy-version: ${APP_CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION:}'
)) {
    Assert-SourceContains `
        -Source $applicationConfiguration `
        -Needle $requiredPublicationDefault `
        -Contract "calendar feed publication fail-closed default $requiredPublicationDefault"
}
$publicationPropertiesSource = Read-SourceContractFile -Path (
    Join-Path $repositoryRoot (
        'backend\src\main\java\local\personalmemo\calendar\application\' +
        'CalendarFeedPublicationProperties.java'
    )
)
foreach ($requiredPublicationValidationFragment in @(
    'if (!enabled)',
    'requireCanonicalHttpsOrigin(publicOrigin)',
    'requireCanonicalConsentPolicyVersion(consentPolicyVersion)',
    'value.length() > 255',
    '!"https".equals(origin.getScheme())',
    'origin.getRawUserInfo() != null',
    'origin.getRawQuery() != null',
    'origin.getRawFragment() != null',
    'DNS_TOP_LEVEL_LABEL.matcher(labels[labels.length - 1]).matches()',
    'host.endsWith(".localhost")',
    'IPV4_SHAPED.matcher(host).matches()',
    'if (!value.equals(canonical))'
)) {
    Assert-SourceContains `
        -Source $publicationPropertiesSource `
        -Needle $requiredPublicationValidationFragment `
        -Contract "calendar feed publication validation fragment $requiredPublicationValidationFragment"
}
$calendarFeedManagementControllerSource = Read-SourceContractFile -Path (
    Join-Path $repositoryRoot (
        'backend\src\main\java\local\personalmemo\calendar\api\' +
        'CalendarFeedManagementController.java'
    )
)
foreach ($requiredPublicationCapabilityFragment in @(
    '@GetMapping("/capabilities")',
    'publication.enabled() ? "PUBLIC_HTTPS" : "LOCAL_ONLY"',
    'publication.enabled() ? publication.publicOrigin() : null',
    'publication.enabled() ? publication.consentPolicyVersion() : null',
    'CacheControl.noStore()'
)) {
    Assert-SourceContains `
        -Source $calendarFeedManagementControllerSource `
        -Needle $requiredPublicationCapabilityFragment `
        -Contract "calendar feed publication capability fragment $requiredPublicationCapabilityFragment"
}

$calendarFeedDecoderSource = Read-SourceContractFile -Path (
    Join-Path $repositoryRoot 'frontend\src\shared\api\calendarFeedDecoder.ts'
)
foreach ($requiredPublicOriginDecoderFragment in @(
    "['LOCAL_ONLY', 'PUBLIC_HTTPS'] as const",
    "parsed.protocol !== 'https:'",
    'parsed.origin !== origin',
    "parsed.hostname === 'localhost'",
    "source.publicOrigin !== null"
)) {
    Assert-SourceContains `
        -Source $calendarFeedDecoderSource `
        -Needle $requiredPublicOriginDecoderFragment `
        -Contract "public feed origin decoder fragment $requiredPublicOriginDecoderFragment"
}
$calendarFeedClientSource = Read-SourceContractFile -Path (
    Join-Path $repositoryRoot 'frontend\src\shared\api\client.ts'
)
Assert-SourceContains `
    -Source $calendarFeedClientSource `
    -Needle "'/api/v1/calendar-feeds/capabilities'" `
    -Contract 'the PWA reads the authenticated server-owned publication capability'
$calendarSharingModelSource = Read-SourceContractFile -Path (
    Join-Path $repositoryRoot 'frontend\src\features\events\calendarSharingModel.ts'
)
foreach ($requiredPublicOriginModelFragment in @(
    "publicationScope === 'PUBLIC_HTTPS' && capability.mode === 'PUBLIC_HTTPS'",
    'capability.consentPolicyVersion === CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION',
    'publicHttpsOrigin(capability.publicOrigin)',
    "publicationScope === 'LOCAL_ONLY' && capability.mode === 'LOCAL_ONLY'",
    'capability.publicOrigin === null && capability.consentPolicyVersion === null',
    'origin = exactHttpOrigin(localOrigin)'
)) {
    Assert-SourceContains `
        -Source $calendarSharingModelSource `
        -Needle $requiredPublicOriginModelFragment `
        -Contract "public feed URL authority fragment $requiredPublicOriginModelFragment"
}
$calendarSharingViewSource = Read-SourceContractFile -Path (
    Join-Path $repositoryRoot 'frontend\src\features\events\EventCalendarSharing.tsx'
)
foreach ($requiredPublicOriginViewFragment in @(
    'publicationCapability: null',
    'setOverview(createUnavailableCalendarFeedOverview())',
    'api.calendarFeedPublicationCapability(controller.signal)',
    'publicationCapability === null',
    'prepareCalendarFeedSubscription(',
    'prepareCreatedCalendarFeedSubscription('
)) {
    Assert-SourceContains `
        -Source $calendarSharingViewSource `
        -Needle $requiredPublicOriginViewFragment `
        -Contract "public feed capability UI fragment $requiredPublicOriginViewFragment"
}
$createFeedSource = Get-SourceBraceBlock `
    -Source $calendarSharingViewSource `
    -Marker 'function createFeed(' `
    -Contract 'calendar feed creation prepares authority before mutation'
Assert-SourceOrder `
    -Source $createFeedSource `
    -Earlier 'subscription = prepareCreatedCalendarFeedSubscription(' `
    -Later 'api.createCalendarFeed(body, idempotencyKey)' `
    -Contract 'calendar feed creation validates URL authority before server mutation'
$rotateFeedSource = Get-SourceBraceBlock `
    -Source $calendarSharingViewSource `
    -Marker 'function rotateFeed()' `
    -Contract 'calendar feed rotation prepares authority before mutation'
Assert-SourceOrder `
    -Source $rotateFeedSource `
    -Earlier 'subscription = prepareCalendarFeedSubscription(' `
    -Later 'api.rotateCalendarFeed(detail.id, body, idempotencyKey)' `
    -Contract 'calendar feed rotation validates URL authority before server mutation'
if ($calendarSharingViewSource.IndexOf(
    'setPublicationCapability(LOCAL_ONLY_PUBLICATION_CAPABILITY)',
    [StringComparison]::Ordinal
) -ge 0) {
    throw 'Capability loading or failure must not silently become LOCAL_ONLY.'
}

if ($env:OS -eq 'Windows_NT') {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    $aclTestDirectory = Join-Path $tempRoot ('personal-memo-acl-contract-' + [Guid]::NewGuid().ToString('N'))
    try {
        $null = New-Item -ItemType Directory -Path $aclTestDirectory
        Set-PersonalMemoPrivateDirectoryAcl -Path $aclTestDirectory
        $aclTestFile = Join-Path $aclTestDirectory 'private.txt'
        $null = New-Item -ItemType File -Path $aclTestFile
        Set-PersonalMemoPrivateFileAcl -Path $aclTestFile
        [IO.File]::WriteAllText($aclTestFile, 'contract-only')
        Assert-PersonalMemoPrivateAcl -Path $aclTestDirectory -Directory
        Assert-PersonalMemoPrivateAcl -Path $aclTestFile
    } finally {
        if (Test-Path -LiteralPath $aclTestDirectory -PathType Container) {
            $resolvedTestDirectory = [IO.Path]::GetFullPath($aclTestDirectory)
            if ($resolvedTestDirectory.StartsWith($tempRoot + '\', [StringComparison]::OrdinalIgnoreCase) -and
                (Split-Path -Leaf $resolvedTestDirectory).StartsWith('personal-memo-acl-contract-')) {
                Remove-Item -LiteralPath $resolvedTestDirectory -Recurse -Force
            }
        }
    }
}

Write-Host 'Personal Memo PowerShell and private-deployment source contracts are valid.'
