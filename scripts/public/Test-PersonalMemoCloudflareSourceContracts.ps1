[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$publicScripts = [IO.Path]::GetFullPath($PSScriptRoot)
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path (Join-Path $publicScripts '..') '..'))

function Read-ContractFile {
    param([Parameter(Mandatory = $true)][string] $Path)

    $resolved = [IO.Path]::GetFullPath($Path)
    if (-not [IO.File]::Exists($resolved)) {
        throw "Required Cloudflare source file was not found: $resolved"
    }
    return [IO.File]::ReadAllText($resolved)
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Needle,
        [Parameter(Mandatory = $true)][string] $Contract
    )

    if ($Source.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) {
        throw "Cloudflare source contract failed ($Contract)."
    }
}

function Assert-Excludes {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Needle,
        [Parameter(Mandatory = $true)][string] $Contract
    )

    if ($Source.IndexOf($Needle, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "Cloudflare source exclusion contract failed ($Contract)."
    }
}

function Assert-Ordered {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Earlier,
        [Parameter(Mandatory = $true)][string] $Later,
        [Parameter(Mandatory = $true)][string] $Contract
    )

    $earlierIndex = $Source.IndexOf($Earlier, [StringComparison]::Ordinal)
    $laterIndex = $Source.IndexOf($Later, [StringComparison]::Ordinal)
    if ($earlierIndex -lt 0 -or $laterIndex -lt 0 -or $earlierIndex -ge $laterIndex) {
        throw "Cloudflare source ordering contract failed ($Contract)."
    }
}

function Assert-ExactJsonProperties {
    param(
        [Parameter(Mandatory = $true)] $Value,
        [Parameter(Mandatory = $true)][string[]] $Expected,
        [Parameter(Mandatory = $true)][string] $Contract
    )

    $actualNames = @($Value.PSObject.Properties.Name | Sort-Object)
    $expectedNames = @($Expected | Sort-Object)
    if ($actualNames.Count -ne $expectedNames.Count) {
        throw "Cloudflare JSON property contract failed ($Contract)."
    }
    for ($index = 0; $index -lt $expectedNames.Count; $index++) {
        if ($actualNames[$index] -cne $expectedNames[$index]) {
            throw "Cloudflare JSON property contract failed ($Contract)."
        }
    }
}

$installPath = Join-Path $publicScripts 'Install-PersonalMemoCloudflareTunnel.ps1'
$startPath = Join-Path $publicScripts 'Start-PersonalMemoCloudflareConnector.ps1'
$stopPath = Join-Path $publicScripts 'Stop-PersonalMemoCloudflareConnector.ps1'
$externalPath = Join-Path $publicScripts 'Test-PersonalMemoCloudflareExternal.ps1'
$examplePath = Join-Path $repositoryRoot '.env.cloudflare-tunnel.example'
$externalComposePath = Join-Path $repositoryRoot 'compose.public-feed.cloudflare-test.yaml'
$probeFixtureSchemaPath = Join-Path $repositoryRoot 'contracts/cloudflare-external-synthetic-fixture.schema.json'
$receiptSchemaPath = Join-Path $repositoryRoot 'contracts/cloudflare-external-qualification-receipt.schema.json'
$probeFixturePath = Join-Path $repositoryRoot 'fixtures/cloudflare-external-synthetic-probes.json'
$gitignorePath = Join-Path $repositoryRoot '.gitignore'
$installSource = Read-ContractFile -Path $installPath
$startSource = Read-ContractFile -Path $startPath
$stopSource = Read-ContractFile -Path $stopPath
$externalSource = Read-ContractFile -Path $externalPath
$exampleSource = Read-ContractFile -Path $examplePath
$externalComposeSource = Read-ContractFile -Path $externalComposePath
$probeFixtureSchemaSource = Read-ContractFile -Path $probeFixtureSchemaPath
$receiptSchemaSource = Read-ContractFile -Path $receiptSchemaPath
$probeFixtureSource = Read-ContractFile -Path $probeFixturePath
$gitignoreSource = Read-ContractFile -Path $gitignorePath

foreach ($scriptPath in @($installPath, $startPath, $stopPath, $externalPath)) {
    $tokens = $null
    $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile(
        $scriptPath,
        [ref] $tokens,
        [ref] $parseErrors
    )
    if ($parseErrors.Count -ne 0) {
        throw "Cloudflare PowerShell parse contract failed: ${scriptPath}: $($parseErrors[0].Message)"
    }
}

foreach ($requiredExternalComposeFragment in @(
    'image: nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6',
    'source: ./calendar-edge/test/upstream-nginx.conf',
    'target: /etc/nginx/nginx.conf',
    '- "127.0.0.1:8787:8080"',
    'read_only: true',
    'no-new-privileges:true'
)) {
    Assert-Contains `
        -Source $externalComposeSource `
        -Needle $requiredExternalComposeFragment `
        -Contract "disposable loopback synthetic topology $requiredExternalComposeFragment"
}
foreach ($forbiddenExternalComposeFragment in @(
    'postgres',
    'compose.yaml',
    'env_file',
    'PERSONAL_MEMO_',
    '0.0.0.0:'
)) {
    Assert-Excludes `
        -Source $externalComposeSource `
        -Needle $forbiddenExternalComposeFragment `
        -Contract "disposable topology cannot reach product state $forbiddenExternalComposeFragment"
}

try {
    $probeFixtureSchema = $probeFixtureSchemaSource | ConvertFrom-Json
    $receiptSchema = $receiptSchemaSource | ConvertFrom-Json
    $probeFixture = $probeFixtureSource | ConvertFrom-Json
} catch {
    throw 'Cloudflare synthetic JSON Schema or fixture parsing failed.'
}
$fixtureProperties = @(
    'schemaVersion',
    'fixtureId',
    'dataClass',
    'localProbes',
    'externalProbes',
    'rateLimitProbe'
)
Assert-ExactJsonProperties -Value $probeFixture -Expected $fixtureProperties -Contract 'strict synthetic fixture root'
Assert-ExactJsonProperties -Value $probeFixtureSchema.properties -Expected $fixtureProperties -Contract 'strict synthetic fixture schema properties'
if ($probeFixtureSchema.'$schema' -cne 'https://json-schema.org/draft/2020-12/schema' -or
    $probeFixtureSchema.'$id' -cne 'https://personal-memo.local/schemas/cloudflare-external-synthetic-fixture-v1.json' -or
    [string]::Join('|', @($probeFixtureSchema.required)) -cne [string]::Join('|', $fixtureProperties) -or
    $probeFixtureSchema.additionalProperties -ne $false -or
    $probeFixtureSchema.'$defs'.probe.additionalProperties -ne $false -or
    $probeFixture.schemaVersion -ne 1 -or
    $probeFixture.fixtureId -cne 'cloudflare-external-synthetic-v1' -or
    $probeFixture.dataClass -cne 'PUBLIC_SYNTHETIC_ONLY') {
    throw 'Cloudflare synthetic fixture identity, strictness, or public-only classification failed.'
}
$expectedLocalProbeNames = @(
    'local-fixture-proof',
    'local-rejected-proof',
    'local-method-proof',
    'local-unknown-proof',
    'local-upstream-failure-proof',
    'local-noncanonical-proof'
)
$expectedExternalProbeNames = @(
    'external-valid-head',
    'external-valid-get-first',
    'external-canonical-unknown',
    'external-upstream-failure',
    'external-noncanonical-token',
    'external-valid-get-repeat',
    'wrong-method',
    'missing-token',
    'extra-query',
    'duplicate-token',
    'get-with-body',
    'wrong-path',
    'suffix-path',
    'encoded-dot',
    'encoded-slash',
    'double-slash'
)
if ([string]::Join('|', @($probeFixture.localProbes.name)) -cne
        [string]::Join('|', $expectedLocalProbeNames) -or
    [string]::Join('|', @($probeFixture.externalProbes.name)) -cne
        [string]::Join('|', $expectedExternalProbeNames) -or
    $probeFixture.rateLimitProbe.maximumAttempts -ne 30 -or
    $probeFixture.rateLimitProbe.expected -cne 'OPTIONAL_BODYLESS_429') {
    throw 'Cloudflare synthetic probe coverage or bounded rate observation contract failed.'
}

$receiptProperties = @(
    'schemaVersion',
    'status',
    'classification',
    'decision',
    'recordedAt',
    'hostnameSha256',
    'probeFixtureId',
    'probeFixtureSha256',
    'exactPositiveProbeCount',
    'emptyNoStoreOriginDenyProbeCount',
    'boundedRemoteCatchAllDenyProbeCount',
    'rateLimitAttemptCount',
    'rateLimitObservation',
    'bodylessRateLimitProbeCount',
    'totalExternalProbeCount',
    'cloudflareCacheStatusCounts',
    'cloudflareCacheHitCount',
    'maximumObservedLatencyMilliseconds',
    'ownedLogSentinel',
    'externalArtifactReflectionSentinel',
    'cloudflaredConnectorLogSentinel',
    'cloudflareCustomerProviderLogSentinel',
    'tunnelReplicaVerification'
)
Assert-ExactJsonProperties -Value $receiptSchema.properties -Expected $receiptProperties -Contract 'strict qualification receipt schema properties'
Assert-ExactJsonProperties -Value $receiptSchema.properties.cloudflareCacheStatusCounts.properties -Expected @(
    'BYPASS',
    'DYNAMIC'
) -Contract 'strict receipt cache allow-list'
if ($receiptSchema.'$schema' -cne 'https://json-schema.org/draft/2020-12/schema' -or
    $receiptSchema.'$id' -cne 'https://personal-memo.local/schemas/cloudflare-external-qualification-receipt-v1.json' -or
    [string]::Join('|', @($receiptSchema.required)) -cne [string]::Join('|', $receiptProperties) -or
    $receiptSchema.additionalProperties -ne $false -or
    $receiptSchema.properties.cloudflareCacheStatusCounts.additionalProperties -ne $false -or
    $receiptSchema.properties.decision.const -cne 'NO_GO' -or
    $receiptSchema.properties.cloudflareCacheHitCount.const -ne 0) {
    throw 'Cloudflare qualification receipt schema strictness or NO_GO boundary failed.'
}

foreach ($requiredExternalFragment in @(
    '#Requires -Version 5.1',
    "[CmdletBinding(DefaultParameterSetName = 'SourceOnly')]",
    '[switch] $PrepareSyntheticOrigin',
    '[switch] $SyntheticOriginQualification',
    '[switch] $CleanupSyntheticOrigin',
    '[switch] $ConnectorStoppedVerified',
    '[switch] $TunnelReplicasStoppedVerified',
    '[switch] $DryRun',
    '[switch] $SourceOnly',
    "`$composeProject = 'personal-memo-cloudflare-synthetic'",
    "`$syntheticOriginBaseUri = 'http://127.0.0.1:8787'",
    "`$externalBaseUri = 'https://' + `$PublicHostname",
    "[ValidateSet('LocalSyntheticOrigin', 'CloudflareExternal')]",
    '$protocol = if ($TargetKind -ceq ''LocalSyntheticOrigin'') { ''=http'' } else { ''=https'' }',
    'New-CanonicalSyntheticBearerSet',
    '[Security.Cryptography.RandomNumberGenerator]::Create()',
    "'A' + `$value.Substring(1)",
    "'^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$'",
    'cloudflare-external-synthetic-probes.json',
    'cloudflare-external-synthetic-fixture.schema.json',
    'cloudflare-external-qualification-receipt.schema.json',
    '--proto "' + '$protocol' + '"',
    '--max-filesize 4096',
    '--max-redirs 0',
    '--path-as-is',
    '--config -',
    '$process.StartInfo.RedirectStandardInput = $true',
    '$process.StartInfo.RedirectStandardError = $true',
    '$process.StandardInput.Write($configText)',
    "Get-HeaderValues -HeaderFile `$HeaderFile -Name 'CF-Cache-Status'",
    'Assert-CloudflareBypassStatus',
    "`$cacheStatus -cne 'BYPASS' -and `$cacheStatus -cne 'DYNAMIC'",
    "-Expected 'no-store'",
    "`$Probe.Status -cne '404'",
    "'external-canonical-unknown|GET|EXACT|UNKNOWN|NONE|ORIGIN_EMPTY_404'",
    "'external-upstream-failure|GET|EXACT|FAILURE|NONE|ORIGIN_EMPTY_404'",
    "'external-valid-get-repeat|GET|EXACT|VALID|NONE|POSITIVE_BODY'",
    "'duplicate-token|GET|DUPLICATE_TOKEN|VALID|NONE|ORIGIN_EMPTY_404'",
    "'double-slash|GET|DOUBLE_SLASH|VALID|NONE|REMOTE_BOUNDED_404'",
    'Assert-SyntheticCalendarBody',
    'Assert-OriginEdgeEmpty404',
    'Assert-OriginEdgeEmpty429',
    'Assert-RemoteCatchAllSafe404',
    '$Probe.DownloadBytes -gt 1024',
    "`$bodyText.IndexOf(`$bearer, [StringComparison]::Ordinal)",
    'Assert-OwnedSyntheticLogs -Bearers $bearerValues -RateLimitObserved $rateLimitObserved',
    'Assert-NoExternalArtifactReflection -TempDirectory $tempDirectory -Bearers $bearerValues',
    'An external response header or body reflected the synthetic bearer or query.',
    "'logs', '--no-color', 'backend', 'calendar-feed-edge'",
    "`$ownedLogs.IndexOf('?token=', [StringComparison]::OrdinalIgnoreCase)",
    "'method=GET route=calendar-feed status=200'",
    "'method=GET route=calendar-feed status=500'",
    "'method=HEAD route=calendar-feed status=200'",
    "'up', '-d', '--build', '--wait', '--force-recreate'",
    "'method=GET route=rejected status=404'",
    'Write-PartialQualificationReceipt',
    'Assert-PartialQualificationReceipt',
    'Test-PartialQualificationReceiptValidator',
    "status = 'TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED'",
    "classification = 'SOLO_PROVISIONAL/REPORT_ONLY'",
    "decision = 'NO_GO'",
    "rateLimitObservation = if (`$RateLimitObserved)",
    "'OBSERVED_BODYLESS_429'",
    "'NOT_OBSERVED_WITHIN_BOUNDED_ATTEMPTS'",
    '} elseif ($Probe.DownloadBytes -ne 0) {',
    'curl --head writes the response header block to its output file',
    'totalExternalProbeCount = @($fixture.externalProbes).Count + $RateLimitAttemptCount',
    "cloudflaredConnectorLogSentinel = 'REQUIRED_NOT_VERIFIED'",
    "cloudflareCustomerProviderLogSentinel = 'REQUIRED_NOT_VERIFIED'",
    "tunnelReplicaVerification = 'REQUIRED_NOT_VERIFIED'",
    '$prepareAttempted = $true',
    'if ($prepareAttempted)',
    "'This is not overall qualification: Cloudflare customer/provider log sentinel and '",
    "if (-not `$SyntheticOriginQualification)",
    "if (-not `$ConnectorStoppedVerified -or -not `$TunnelReplicasStoppedVerified)",
    'Assert-ConnectorStoppedIfInstalled',
    "Get-Process -Name 'cloudflared'",
    "'down', '--volumes', '--remove-orphans', '--rmi', 'local'"
)) {
    Assert-Contains `
        -Source $externalSource `
        -Needle $requiredExternalFragment `
        -Contract "external synthetic-only qualification $requiredExternalFragment"
}
foreach ($forbiddenExternalFragment in @(
    '[string] $Token',
    '[string] $BaseUri',
    '[string] $Url',
    '[string] $Session',
    '[pscredential]',
    'Invoke-WebRequest',
    'Invoke-RestMethod',
    'Start-Service',
    'Stop-Service',
    '--verbose',
    '--trace',
    'Authorization:',
    'Cookie:',
    'qualification passed',
    "`$cacheStatus.Equals('HIT'",
    '} elseif ($Probe.DownloadBytes -ne 0 -or',
    "'method=other route=calendar-feed status=404'",
    'emptyNoStoreOriginDenyProbeCount = 4',
    'boundedRemoteCatchAllDenyProbeCount = 5',
    'totalExternalProbeCount = 11',
    '/api/',
    'postgres'
)) {
    Assert-Excludes `
        -Source $externalSource `
        -Needle $forbiddenExternalFragment `
        -Contract "external qualification cannot accept personal authority or control the connector $forbiddenExternalFragment"
}
Assert-Ordered `
    -Source $externalSource `
    -Earlier 'Assert-SyntheticTopologyRunning' `
    -Later "`$externalBaseUri = 'https://' + `$PublicHostname" `
    -Contract 'local disposable topology proof precedes external authority initialization'
Assert-Ordered `
    -Source $externalSource `
    -Earlier "if (-not `$SyntheticOriginQualification)" `
    -Later "`$externalBaseUri = 'https://' + `$PublicHostname" `
    -Contract 'explicit synthetic qualification gate precedes every external request'
Assert-Ordered `
    -Source $externalSource `
    -Earlier '$prepareAttempted = $true' `
    -Later "Invoke-SyntheticCompose -Arguments @(" `
    -Contract 'prepare records an attempted startup before Compose can partially fail'
Assert-Ordered `
    -Source $externalSource `
    -Earlier "'up', '-d', '--build', '--wait', '--force-recreate'" `
    -Later 'if ($prepareAttempted)' `
    -Contract 'prepare failure cleanup is armed for partial Compose startup'

foreach ($requiredExampleFragment in @(
    'PERSONAL_MEMO_CLOUDFLARE_PUBLIC_HOSTNAME=calendar.example.com',
    'PERSONAL_MEMO_CLOUDFLARE_REMOTE_PATH=^/calendar/v1/feed\.ics$',
    'PERSONAL_MEMO_CLOUDFLARE_ORIGIN_SERVICE=http://127.0.0.1:8787',
    'PERSONAL_MEMO_CLOUDFLARE_CATCH_ALL_SERVICE=http_status:404',
    'PERSONAL_MEMO_CLOUDFLARE_METRICS_ADDRESS=127.0.0.1:49312',
    'The final ingress rule has no hostname or path',
    'Keep the connector stopped'
)) {
    Assert-Contains `
        -Source $exampleSource `
        -Needle $requiredExampleFragment `
        -Contract "remote dashboard record $requiredExampleFragment"
}
foreach ($forbiddenExampleFragment in @(
    'TUNNEL_TOKEN=',
    'API_TOKEN=',
    'credentials-file=',
    'eyJ'
)) {
    Assert-Excludes `
        -Source $exampleSource `
        -Needle $forbiddenExampleFragment `
        -Contract "example env cannot carry secret material $forbiddenExampleFragment"
}

foreach ($requiredInstallFragment in @(
    '#Requires -Version 5.1',
    '#Requires -RunAsAdministrator',
    "`$serviceName = 'PersonalMemoCalendarCloudflareTunnel'",
    "`$installRoot = 'C:\ProgramData\PersonalMemo\Cloudflare'",
    "`$binaryPath = Join-Path `$binaryDirectory 'cloudflared.exe'",
    "`$tokenPath = Join-Path `$secretDirectory 'tunnel.token'",
    "`$manifestPath = Join-Path `$installRoot 'installation-manifest.json'",
    "`$metricsAddress = '127.0.0.1:49312'",
    "`$minimumTokenFileVersion = [Version]'2025.4.0'",
    'Get-FileHash -LiteralPath $Path -Algorithm SHA256',
    'Get-AuthenticodeSignature -LiteralPath $Path',
    '[Management.Automation.SignatureStatus]::Valid',
    "-notmatch '(?i)(?:CN|O)\s*=\s*`"?Cloudflare,\s*Inc\.`"?'",
    '$versionLines = @(& $Path --version 2>&1)',
    "'(?i)\bcloudflared\s+version\s+(?<version>\d{4}\.\d+\.\d+)\b'",
    'function ConvertFrom-CloudflareTunnelSecretInput',
    "`$tokenPattern = '[A-Za-z0-9._~+/-]{20,4094}={0,2}'",
    "'\A(?i:cloudflared\.exe service install )(?<token>{0})\z'",
    'Read-Host',
    '-AsSecureString',
    '[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)',
    '$secretInputText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)',
    '$tokenText = ConvertFrom-CloudflareTunnelSecretInput -SecretInput $secretInputText',
    '[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)',
    '$security.SetAccessRuleProtection($true, $false)',
    "New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')",
    "New-Object Security.Principal.SecurityIdentifier('S-1-5-18')",
    'Assert-RestrictedAcl -Path $tokenPath',
    'Assert-RestrictedAcl -Path $manifestPath',
    'schemaVersion = 1',
    'cloudflaredSha256 = $installedArtifact.Sha256',
    'cloudflaredVersion = $installedArtifact.Version',
    '$installRootCreated = $true',
    '$tokenPersisted = $true',
    'Remove-Item -LiteralPath $manifestPath -Force',
    'Remove-Item -LiteralPath $installRoot -Recurse -Force',
    'token in Cloudflare before retrying.',
    '"{0}" tunnel --no-autoupdate --loglevel warn --transport-loglevel warn --grace-period 30s --metrics {1} run --token-file "{2}"',
    '-StartupType Manual',
    '[ServiceProcess.ServiceControllerStatus]::Stopped',
    'No public route or Personal Memo publication capability was activated.'
)) {
    Assert-Contains `
        -Source $installSource `
        -Needle $requiredInstallFragment `
        -Contract "secure stopped installer $requiredInstallFragment"
}
foreach ($forbiddenInstallFragment in @(
    'Start-Service',
    'Invoke-WebRequest',
    'Invoke-RestMethod',
    'https://api.cloudflare.com',
    '$env:TUNNEL_TOKEN',
    'TUNNEL_TOKEN=',
    '--token ',
    '--token='
)) {
    Assert-Excludes `
        -Source $installSource `
        -Needle $forbiddenInstallFragment `
        -Contract "installer cannot activate, download, call Cloudflare, or expose tokens $forbiddenInstallFragment"
}
Assert-Ordered `
    -Source $installSource `
    -Earlier '$installedArtifact = Assert-CloudflaredArtifact -Path $binaryPath -Sha256 $ExpectedSha256' `
    -Later 'Read-Host' `
    -Contract 'copied binary is reverified before secret capture'
Assert-Ordered `
    -Source $installSource `
    -Earlier 'Assert-RestrictedAcl -Path $manifestPath' `
    -Later 'Read-Host' `
    -Contract 'protected non-secret artifact manifest is committed before secret capture'
Assert-Ordered `
    -Source $installSource `
    -Earlier 'Assert-RestrictedAcl -Path $tokenPath' `
    -Later '$null = New-Service' `
    -Contract 'token file ACL is verified before service creation'

$installTokens = $null
$installParseErrors = $null
$installAst = [Management.Automation.Language.Parser]::ParseFile(
    $installPath,
    [ref] $installTokens,
    [ref] $installParseErrors
)
if ($installParseErrors.Count -ne 0) {
    throw "Cloudflare installer behavior contract could not parse the installer: $($installParseErrors[0].Message)"
}
$secretInputFunction = @($installAst.FindAll(
    {
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq 'ConvertFrom-CloudflareTunnelSecretInput'
    },
    $true
))
if ($secretInputFunction.Count -ne 1) {
    throw 'Cloudflare installer behavior contract requires exactly one secret-input parser.'
}
$secretInputFunctionSource = $secretInputFunction[0].Extent.Text
foreach ($forbiddenSecretParserFragment in @(
    'Write-Host',
    'Write-Output',
    'Write-Verbose',
    'Write-Debug',
    'Write-Warning',
    'Invoke-Expression',
    'Start-Process',
    '& $SecretInput'
)) {
    Assert-Excludes `
        -Source $secretInputFunctionSource `
        -Needle $forbiddenSecretParserFragment `
        -Contract "secret input parser cannot execute or log input $forbiddenSecretParserFragment"
}

. ([scriptblock]::Create($secretInputFunctionSource))
$syntheticTunnelToken = 'eyJsynthetic_contract_only_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef'
$rawTokenResult = ConvertFrom-CloudflareTunnelSecretInput -SecretInput $syntheticTunnelToken
if ($rawTokenResult -cne $syntheticTunnelToken) {
    throw 'Cloudflare installer behavior contract failed to preserve a raw Tunnel token.'
}
$installCommandResult = ConvertFrom-CloudflareTunnelSecretInput -SecretInput (
    'cloudflared.exe service install ' + $syntheticTunnelToken
)
if ($installCommandResult -cne $syntheticTunnelToken) {
    throw 'Cloudflare installer behavior contract failed to extract the Tunnel token from the Windows command.'
}
$caseInsensitiveCommandResult = ConvertFrom-CloudflareTunnelSecretInput -SecretInput (
    'CLOUDFLARED.EXE SERVICE INSTALL ' + $syntheticTunnelToken
)
if ($caseInsensitiveCommandResult -cne $syntheticTunnelToken) {
    throw 'Cloudflare installer behavior contract failed Windows command case handling.'
}
foreach ($invalidSecretInput in @(
    '',
    'short',
    (' ' + $syntheticTunnelToken),
    ($syntheticTunnelToken + ' '),
    ($syntheticTunnelToken + ';whoami'),
    ('cloudflared.exe service install  ' + $syntheticTunnelToken),
    ('cloudflared.exe service install ' + $syntheticTunnelToken + ' extra'),
    ('cmd.exe /c cloudflared.exe service install ' + $syntheticTunnelToken),
    ('cloudflared.exe tunnel run ' + $syntheticTunnelToken),
    ('cloudflared.exe service install ' + $syntheticTunnelToken + "`r`nwhoami")
)) {
    $invalidInputRejected = $false
    try {
        $null = ConvertFrom-CloudflareTunnelSecretInput -SecretInput $invalidSecretInput
    } catch {
        $invalidInputRejected = $true
        if ($_.Exception.Message.IndexOf($syntheticTunnelToken, [StringComparison]::Ordinal) -ge 0) {
            throw 'Cloudflare installer behavior contract found secret input reflected in an error.'
        }
    }
    if (-not $invalidInputRejected) {
        throw 'Cloudflare installer behavior contract accepted malformed or multi-line secret input.'
    }
}

foreach ($requiredStartFragment in @(
    '[ValidateLength(1, 253)]',
    "[ValidatePattern('^calendar\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$')]",
    '$PublicHostname -cne $PublicHostname.ToLowerInvariant()',
    "[string] `$RemoteRoutePath = '^/calendar/v1/feed\.ics$'",
    "[string] `$OriginService = 'http://127.0.0.1:8787'",
    "[string] `$CatchAllService = 'http_status:404'",
    '[switch] $RemoteRouteVerified',
    '[switch] $RemoteCatchAllVerified',
    '[switch] $PublicationCapabilityVerified',
    '[switch] $ExternalTokenLogSentinelVerified',
    '[ServiceProcess.ServiceStartMode]::Manual',
    '[ServiceProcess.ServiceControllerStatus]::Stopped',
    '127.0.0.1',
    "Get-Process -Name 'cloudflared'",
    'Assert-NotReparsePoint -Path $protectedFile',
    'Assert-RestrictedAcl -Path $protectedFile',
    "`$manifestPath = Join-Path `$installRoot 'installation-manifest.json'",
    'Get-FileHash -LiteralPath $Path -Algorithm SHA256',
    'Get-AuthenticodeSignature -LiteralPath $Path',
    '$versionLines = @(& $Path --version 2>&1)',
    "`$serviceObjectName -cne 'LocalSystem'",
    'GetActiveTcpListeners()',
    "[regex]::IsMatch(`$imagePath, '(?i)--token(?:\s|=)')",
    'Start-Service -Name $serviceName',
    "[Net.HttpWebRequest]::Create('http://127.0.0.1:49312/diag/tunnel')",
    'Stop-Service -Name $serviceName -Force',
    '[TimeSpan]::FromSeconds(45)'
)) {
    Assert-Contains `
        -Source $startSource `
        -Needle $requiredStartFragment `
        -Contract "explicit final connector switch $requiredStartFragment"
}
Assert-Ordered `
    -Source $startSource `
    -Earlier 'ExternalTokenLogSentinelVerified' `
    -Later 'Start-Service -Name $serviceName' `
    -Contract 'external token-log proof precedes final switch'
Assert-Ordered `
    -Source $startSource `
    -Earlier "BeginConnect('127.0.0.1', 8787" `
    -Later 'Start-Service -Name $serviceName' `
    -Contract 'loopback edge readiness precedes final switch'
Assert-Ordered `
    -Source $startSource `
    -Earlier 'Assert-CloudflaredArtifact `' `
    -Later 'Start-Service -Name $serviceName' `
    -Contract 'artifact identity is revalidated before final switch'
Assert-Ordered `
    -Source $startSource `
    -Earlier 'GetActiveTcpListeners()' `
    -Later 'Start-Service -Name $serviceName' `
    -Contract 'metrics port collision check precedes final switch'
Assert-Ordered `
    -Source $startSource `
    -Earlier 'Start-Service -Name $serviceName' `
    -Later "[Net.HttpWebRequest]::Create('http://127.0.0.1:49312/diag/tunnel')" `
    -Contract 'local Cloudflare connection diagnostics follow service start'
Assert-Ordered `
    -Source $startSource `
    -Earlier 'Start-Service -Name $serviceName' `
    -Later 'Stop-Service -Name $serviceName -Force' `
    -Contract 'service start failure has a compensating local stop path'

foreach ($requiredStopFragment in @(
    '#Requires -RunAsAdministrator',
    'Stop-Service -Name $serviceName -Force',
    '[ServiceProcess.ServiceControllerStatus]::Stopped',
    '[TimeSpan]::FromSeconds(45)',
    "Get-Process -Name 'cloudflared'",
    'Verify that no successful feed response remains through Cloudflare.'
)) {
    Assert-Contains `
        -Source $stopSource `
        -Needle $requiredStopFragment `
        -Contract "connector-first rollback $requiredStopFragment"
}

$signerPattern = '(?i)(?:CN|O)\s*=\s*"?Cloudflare,\s*Inc\."?'
foreach ($acceptedSignerSubject in @(
    'CN="Cloudflare, Inc.", O="Cloudflare, Inc.", C=US',
    'CN=Cloudflare, Inc., O=Cloudflare, Inc., C=US'
)) {
    if ($acceptedSignerSubject -notmatch $signerPattern) {
        throw "The Cloudflare signer subject variation was rejected: $acceptedSignerSubject"
    }
}
Assert-Contains `
    -Source $gitignoreSource `
    -Needle 'artifacts/cloudflare/' `
    -Contract 'partial external receipts stay outside source control'
if ('CN=Cloudflared Example Publisher, O=Example' -match $signerPattern) {
    throw 'The Cloudflare signer contract accepted an unrelated publisher.'
}

& $externalPath -SourceOnly
& $externalPath -PublicHostname 'calendar.example.com' -DryRun

Write-Host 'Personal Memo Cloudflare connector source contracts are valid.'
