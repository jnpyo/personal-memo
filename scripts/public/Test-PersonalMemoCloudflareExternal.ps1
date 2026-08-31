#Requires -Version 5.1

[CmdletBinding(DefaultParameterSetName = 'SourceOnly')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'DryRun')]
    [Parameter(Mandatory = $true, ParameterSetName = 'SyntheticOriginQualification')]
    [ValidatePattern('^calendar\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$')]
    [string] $PublicHostname,

    [Parameter(Mandatory = $true, ParameterSetName = 'DryRun')]
    [switch] $DryRun,

    [Parameter(Mandatory = $true, ParameterSetName = 'PrepareSyntheticOrigin')]
    [switch] $PrepareSyntheticOrigin,

    [Parameter(Mandatory = $true, ParameterSetName = 'SyntheticOriginQualification')]
    [switch] $SyntheticOriginQualification,

    [Parameter(Mandatory = $true, ParameterSetName = 'CleanupSyntheticOrigin')]
    [switch] $CleanupSyntheticOrigin,

    [Parameter(Mandatory = $true, ParameterSetName = 'CleanupSyntheticOrigin')]
    [switch] $ConnectorStoppedVerified,

    [Parameter(Mandatory = $true, ParameterSetName = 'CleanupSyntheticOrigin')]
    [switch] $TunnelReplicasStoppedVerified,

    [Parameter(Mandatory = $true, ParameterSetName = 'SourceOnly')]
    [switch] $SourceOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$publicScripts = [IO.Path]::GetFullPath($PSScriptRoot)
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path (Join-Path $publicScripts '..') '..'))
$composeFile = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'compose.public-feed.cloudflare-test.yaml'))
$composeProject = 'personal-memo-cloudflare-synthetic'
$connectorServiceName = 'PersonalMemoCalendarCloudflareTunnel'
$syntheticOriginBaseUri = 'http://127.0.0.1:8787'
$probeFixturePath = [IO.Path]::GetFullPath(
    (Join-Path $repositoryRoot 'fixtures/cloudflare-external-synthetic-probes.json')
)
$probeFixtureSchemaPath = [IO.Path]::GetFullPath(
    (Join-Path $repositoryRoot 'contracts/cloudflare-external-synthetic-fixture.schema.json')
)
$receiptSchemaPath = [IO.Path]::GetFullPath(
    (Join-Path $repositoryRoot 'contracts/cloudflare-external-qualification-receipt.schema.json')
)
$externalBaseUri = $null
$script:CurlExecutable = $null

function Assert-ScriptSource {
    $tokens = $null
    $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile(
        $PSCommandPath,
        [ref] $tokens,
        [ref] $parseErrors
    )
    if ($parseErrors.Count -ne 0) {
        throw 'The Cloudflare external qualification script did not parse.'
    }
    if ([IO.Path]::GetFileName($PSCommandPath) -cne 'Test-PersonalMemoCloudflareExternal.ps1') {
        throw 'The source-only check was invoked from an unexpected script path.'
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

function Assert-ConnectorStoppedIfInstalled {
    $connectorService = Get-Service -Name $connectorServiceName -ErrorAction SilentlyContinue
    if ($null -ne $connectorService -and
        $connectorService.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        throw 'The Cloudflare connector must be stopped for this synthetic-origin lifecycle operation.'
    }
    if (@(Get-Process -Name 'cloudflared' -ErrorAction SilentlyContinue).Count -ne 0) {
        throw 'A cloudflared process is running; inspect and stop every connector before lifecycle changes.'
    }
}

function Assert-ComposeBoundary {
    $expectedCompose = [IO.Path]::GetFullPath(
        (Join-Path $repositoryRoot 'compose.public-feed.cloudflare-test.yaml')
    )
    if (-not $composeFile.Equals($expectedCompose, [StringComparison]::OrdinalIgnoreCase) -or
        $composeProject -cne 'personal-memo-cloudflare-synthetic' -or
        -not [IO.File]::Exists($composeFile)) {
        throw 'The disposable Cloudflare qualification Compose boundary is not exact.'
    }
}

function Assert-ExactObjectProperties {
    param(
        [Parameter(Mandatory = $true)] $Value,
        [Parameter(Mandatory = $true)][string[]] $Expected,
        [Parameter(Mandatory = $true)][string] $Context
    )

    if ($null -eq $Value) {
        throw "The $Context object was absent."
    }
    $actualNames = @($Value.PSObject.Properties.Name | Sort-Object)
    $expectedNames = @($Expected | Sort-Object)
    if ($actualNames.Count -ne $expectedNames.Count) {
        throw "The $Context object did not have the exact property set."
    }
    for ($index = 0; $index -lt $expectedNames.Count; $index++) {
        if ($actualNames[$index] -cne $expectedNames[$index]) {
            throw "The $Context object did not have the exact property set."
        }
    }
}

function Assert-ExactStringSequence {
    param(
        [Parameter(Mandatory = $true)][string[]] $Actual,
        [Parameter(Mandatory = $true)][string[]] $Expected,
        [Parameter(Mandatory = $true)][string] $Context
    )

    if ($Actual.Count -ne $Expected.Count) {
        throw "The $Context sequence length was not exact."
    }
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        if ($Actual[$index] -cne $Expected[$index]) {
            throw "The $Context sequence did not match the reviewed fixture."
        }
    }
}

function Assert-JsonInteger {
    param(
        [Parameter(Mandatory = $true)] $Value,
        [Parameter(Mandatory = $true)][int64] $Minimum,
        [Parameter(Mandatory = $true)][int64] $Maximum,
        [Parameter(Mandatory = $true)][string] $Context
    )

    $isInteger = $Value -is [byte] -or
        $Value -is [sbyte] -or
        $Value -is [int16] -or
        $Value -is [uint16] -or
        $Value -is [int32] -or
        $Value -is [uint32] -or
        $Value -is [int64] -or
        $Value -is [uint64]
    if (-not $isInteger -or $Value -lt $Minimum -or $Value -gt $Maximum) {
        throw "The $Context value was not an integer inside its fixed boundary."
    }
}

function Assert-JsonNumber {
    param(
        [Parameter(Mandatory = $true)] $Value,
        [Parameter(Mandatory = $true)][double] $Minimum,
        [Parameter(Mandatory = $true)][double] $Maximum,
        [Parameter(Mandatory = $true)][string] $Context
    )

    $isNumber = $Value -is [byte] -or
        $Value -is [sbyte] -or
        $Value -is [int16] -or
        $Value -is [uint16] -or
        $Value -is [int32] -or
        $Value -is [uint32] -or
        $Value -is [int64] -or
        $Value -is [uint64] -or
        $Value -is [single] -or
        $Value -is [double] -or
        $Value -is [decimal]
    $number = if ($isNumber) { [double] $Value } else { [double]::NaN }
    if (-not $isNumber -or
        [double]::IsNaN($number) -or
        [double]::IsInfinity($number) -or
        $number -lt $Minimum -or
        $number -gt $Maximum) {
        throw "The $Context value was not a finite number inside its fixed boundary."
    }
}

function Read-BoundedJsonObject {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][int64] $MaximumBytes,
        [Parameter(Mandatory = $true)][string] $Context
    )

    if (-not [IO.File]::Exists($Path)) {
        throw "The required $Context JSON file was absent."
    }
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -le 0 -or $file.Length -gt $MaximumBytes) {
        throw "The required $Context JSON file exceeded its fixed size boundary."
    }
    $source = [IO.File]::ReadAllText($Path)
    try {
        $value = $source | ConvertFrom-Json
    } catch {
        throw "The required $Context JSON file did not parse."
    } finally {
        $source = $null
    }
    if ($null -eq $value) {
        throw "The required $Context JSON document was empty."
    }
    return $value
}

function Get-ProbeDescriptor {
    param([Parameter(Mandatory = $true)] $Probe)

    Assert-ExactObjectProperties -Value $Probe -Expected @(
        'name',
        'method',
        'target',
        'tokenRole',
        'body',
        'expected'
    ) -Context 'synthetic probe'
    return [string]::Join('|', @(
        [string] $Probe.name,
        [string] $Probe.method,
        [string] $Probe.target,
        [string] $Probe.tokenRole,
        [string] $Probe.body,
        [string] $Probe.expected
    ))
}

function Get-ValidatedSyntheticFixture {
    $fixtureSchema = Read-BoundedJsonObject -Path $probeFixtureSchemaPath -MaximumBytes 32768 -Context 'synthetic fixture schema'
    $receiptSchema = Read-BoundedJsonObject -Path $receiptSchemaPath -MaximumBytes 32768 -Context 'qualification receipt schema'
    $fixture = Read-BoundedJsonObject -Path $probeFixturePath -MaximumBytes 32768 -Context 'synthetic probe fixture'

    Assert-ExactObjectProperties -Value $fixtureSchema -Expected @(
        '$schema',
        '$id',
        'title',
        'type',
        'additionalProperties',
        'required',
        'properties',
        '$defs'
    ) -Context 'synthetic fixture schema'
    Assert-ExactObjectProperties -Value $receiptSchema -Expected @(
        '$schema',
        '$id',
        'title',
        'type',
        'additionalProperties',
        'required',
        'properties',
        '$defs'
    ) -Context 'qualification receipt schema'
    if ($fixtureSchema.'$schema' -cne 'https://json-schema.org/draft/2020-12/schema' -or
        $fixtureSchema.'$id' -cne 'https://personal-memo.local/schemas/cloudflare-external-synthetic-fixture-v1.json' -or
        $fixtureSchema.type -cne 'object' -or
        $fixtureSchema.additionalProperties -ne $false) {
        throw 'The synthetic fixture schema identity or strictness was not exact.'
    }
    if ($receiptSchema.'$schema' -cne 'https://json-schema.org/draft/2020-12/schema' -or
        $receiptSchema.'$id' -cne 'https://personal-memo.local/schemas/cloudflare-external-qualification-receipt-v1.json' -or
        $receiptSchema.type -cne 'object' -or
        $receiptSchema.additionalProperties -ne $false) {
        throw 'The qualification receipt schema identity or strictness was not exact.'
    }

    $fixturePropertyNames = @(
        'schemaVersion',
        'fixtureId',
        'dataClass',
        'localProbes',
        'externalProbes',
        'rateLimitProbe'
    )
    Assert-ExactStringSequence -Actual @($fixtureSchema.required) -Expected $fixturePropertyNames -Context 'synthetic fixture schema required'
    Assert-ExactObjectProperties -Value $fixtureSchema.properties -Expected $fixturePropertyNames -Context 'synthetic fixture schema properties'
    Assert-ExactObjectProperties -Value $fixture -Expected $fixturePropertyNames -Context 'synthetic fixture'
    Assert-JsonInteger -Value $fixture.schemaVersion -Minimum 1 -Maximum 1 -Context 'synthetic fixture schemaVersion'
    if ($fixture.fixtureId -cne 'cloudflare-external-synthetic-v1' -or
        $fixture.dataClass -cne 'PUBLIC_SYNTHETIC_ONLY') {
        throw 'The synthetic fixture identity or public-only data classification was not exact.'
    }

    $expectedLocalDescriptors = @(
        'local-fixture-proof|GET|EXACT|VALID|NONE|POSITIVE_BODY',
        'local-rejected-proof|GET|WRONG_PATH|VALID|NONE|ORIGIN_EMPTY_404',
        'local-method-proof|POST|EXACT|VALID|NONE|ORIGIN_EMPTY_404',
        'local-unknown-proof|GET|EXACT|UNKNOWN|NONE|ORIGIN_EMPTY_404',
        'local-upstream-failure-proof|GET|EXACT|FAILURE|NONE|ORIGIN_EMPTY_404',
        'local-noncanonical-proof|GET|EXACT_NONCANONICAL|NONCANONICAL|NONE|ORIGIN_EMPTY_404'
    )
    $expectedExternalDescriptors = @(
        'external-valid-head|HEAD|EXACT|VALID|NONE|POSITIVE_BODYLESS',
        'external-valid-get-first|GET|EXACT|VALID|NONE|POSITIVE_BODY',
        'external-canonical-unknown|GET|EXACT|UNKNOWN|NONE|ORIGIN_EMPTY_404',
        'external-upstream-failure|GET|EXACT|FAILURE|NONE|ORIGIN_EMPTY_404',
        'external-noncanonical-token|GET|EXACT_NONCANONICAL|NONCANONICAL|NONE|ORIGIN_EMPTY_404',
        'external-valid-get-repeat|GET|EXACT|VALID|NONE|POSITIVE_BODY',
        'wrong-method|POST|EXACT|VALID|NONE|ORIGIN_EMPTY_404',
        'missing-token|GET|MISSING_TOKEN|NONE|NONE|ORIGIN_EMPTY_404',
        'extra-query|GET|EXTRA_QUERY|VALID|NONE|ORIGIN_EMPTY_404',
        'duplicate-token|GET|DUPLICATE_TOKEN|VALID|NONE|ORIGIN_EMPTY_404',
        'get-with-body|GET|EXACT|VALID|ONE_BYTE|ORIGIN_EMPTY_404',
        'wrong-path|GET|WRONG_PATH|VALID|NONE|REMOTE_BOUNDED_404',
        'suffix-path|GET|SUFFIX_PATH|VALID|NONE|REMOTE_BOUNDED_404',
        'encoded-dot|GET|ENCODED_DOT|VALID|NONE|REMOTE_BOUNDED_404',
        'encoded-slash|GET|ENCODED_SLASH|VALID|NONE|REMOTE_BOUNDED_404',
        'double-slash|GET|DOUBLE_SLASH|VALID|NONE|REMOTE_BOUNDED_404'
    )
    $localDescriptors = @(@($fixture.localProbes) | ForEach-Object { Get-ProbeDescriptor -Probe $_ })
    $externalDescriptors = @(@($fixture.externalProbes) | ForEach-Object { Get-ProbeDescriptor -Probe $_ })
    Assert-ExactStringSequence -Actual $localDescriptors -Expected $expectedLocalDescriptors -Context 'local synthetic probes'
    Assert-ExactStringSequence -Actual $externalDescriptors -Expected $expectedExternalDescriptors -Context 'external synthetic probes'

    Assert-ExactObjectProperties -Value $fixture.rateLimitProbe -Expected @(
        'namePrefix',
        'method',
        'target',
        'tokenRole',
        'body',
        'expected',
        'maximumAttempts'
    ) -Context 'rate-limit probe'
    $rateDescriptor = [string]::Join('|', @(
        [string] $fixture.rateLimitProbe.namePrefix,
        [string] $fixture.rateLimitProbe.method,
        [string] $fixture.rateLimitProbe.target,
        [string] $fixture.rateLimitProbe.tokenRole,
        [string] $fixture.rateLimitProbe.body,
        [string] $fixture.rateLimitProbe.expected,
        [string] $fixture.rateLimitProbe.maximumAttempts
    ))
    if ($rateDescriptor -cne 'rate-limit|GET|EXACT|VALID|NONE|OPTIONAL_BODYLESS_429|30') {
        throw 'The rate-limit probe did not match the reviewed bounded fixture.'
    }
    Assert-JsonInteger -Value $fixture.rateLimitProbe.maximumAttempts -Minimum 1 -Maximum 30 -Context 'rate-limit maximumAttempts'

    $probeNames = @(
        @($fixture.localProbes).name
        @($fixture.externalProbes).name
        $fixture.rateLimitProbe.namePrefix
    )
    if (@($probeNames | Sort-Object -Unique).Count -ne $probeNames.Count) {
        throw 'Synthetic probe names were not unique.'
    }

    $receiptPropertyNames = @(
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
    Assert-ExactStringSequence -Actual @($receiptSchema.required) -Expected $receiptPropertyNames -Context 'qualification receipt schema required'
    Assert-ExactObjectProperties -Value $receiptSchema.properties -Expected $receiptPropertyNames -Context 'qualification receipt schema properties'
    if ($receiptSchema.properties.decision.const -cne 'NO_GO' -or
        $receiptSchema.properties.cloudflareCacheHitCount.const -ne 0 -or
        $receiptSchema.properties.cloudflareCacheStatusCounts.additionalProperties -ne $false) {
        throw 'The qualification receipt schema did not preserve its fail-closed decision or cache boundary.'
    }

    return [PSCustomObject]@{
        Document = $fixture
        Sha256 = (Get-FileHash -LiteralPath $probeFixturePath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Assert-DockerAvailable {
    if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker is required for the disposable synthetic origin.'
    }
}

function Invoke-SyntheticCompose {
    param(
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [switch] $Capture
    )

    Assert-ComposeBoundary
    $output = & docker compose -p $composeProject -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'The exact disposable Cloudflare qualification Compose command failed.'
    }
    if ($Capture) {
        return [string] ($output -join "`n")
    }
}

function Assert-SyntheticTopologyRunning {
    $runningText = Invoke-SyntheticCompose `
        -Arguments @('ps', '--services', '--status', 'running') `
        -Capture
    $runningServices = @(
        $runningText -split "`r?`n" |
            Where-Object { $_.Length -gt 0 } |
            Sort-Object
    )
    if ($runningServices.Count -ne 2 -or
        $runningServices[0] -cne 'backend' -or
        $runningServices[1] -cne 'calendar-feed-edge') {
        throw 'The exact two-service disposable synthetic origin is not running.'
    }
}

function Resolve-CurlExecutable {
    $curlCommand = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($null -eq $curlCommand) {
        $curlCommand = Get-Command curl -ErrorAction SilentlyContinue
    }
    if ($null -eq $curlCommand -or $curlCommand.CommandType -ne 'Application') {
        throw 'The curl executable is required for synthetic qualification.'
    }
    $script:CurlExecutable = $curlCommand.Source
}

function New-CanonicalSyntheticBearerSet {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    $value = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $value = 'A' + $value.Substring(1)
    $unknown = 'B' + $value.Substring(1)
    $failure = 'C' + $value.Substring(1)
    $nonCanonical = $value.Substring(0, 42) + 'B'
    foreach ($canonical in @($value, $unknown, $failure)) {
        if ($canonical -cnotmatch '^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$') {
            [Array]::Clear($bytes, 0, $bytes.Length)
            throw 'Synthetic bearer generation did not produce the canonical test shape.'
        }
    }
    if ($nonCanonical -cnotmatch '^[A-Za-z0-9_-]{43}$' -or
        $nonCanonical -cmatch '^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$') {
        [Array]::Clear($bytes, 0, $bytes.Length)
        throw 'Synthetic bearer generation did not produce the reviewed noncanonical shape.'
    }
    return [PSCustomObject]@{
        Bytes = $bytes
        Valid = $value
        Unknown = $unknown
        Failure = $failure
        NonCanonical = $nonCanonical
    }
}

function Clear-SyntheticBearerSet {
    param($BearerSet)

    if ($null -eq $BearerSet) {
        return
    }
    foreach ($property in @('Valid', 'Unknown', 'Failure', 'NonCanonical')) {
        $BearerSet.$property = $null
    }
    [Array]::Clear($BearerSet.Bytes, 0, $BearerSet.Bytes.Length)
}

function Get-SyntheticBearerForRole {
    param(
        [Parameter(Mandatory = $true)] $BearerSet,
        [Parameter(Mandatory = $true)][string] $Role
    )

    switch -CaseSensitive ($Role) {
        'VALID' { return $BearerSet.Valid }
        'UNKNOWN' { return $BearerSet.Unknown }
        'FAILURE' { return $BearerSet.Failure }
        'NONCANONICAL' { return $BearerSet.NonCanonical }
        'NONE' { return $null }
        default { throw 'The fixture selected an unsupported synthetic token role.' }
    }
}

function Resolve-SyntheticRequestTarget {
    param(
        [Parameter(Mandatory = $true)] $ProbeDefinition,
        [Parameter(Mandatory = $true)] $BearerSet
    )

    $token = Get-SyntheticBearerForRole -BearerSet $BearerSet -Role ([string] $ProbeDefinition.tokenRole)
    switch -CaseSensitive ([string] $ProbeDefinition.target) {
        'EXACT' { return '/calendar/v1/feed.ics?token=' + $token }
        'EXACT_NONCANONICAL' { return '/calendar/v1/feed.ics?token=' + $token }
        'MISSING_TOKEN' { return '/calendar/v1/feed.ics' }
        'EXTRA_QUERY' { return '/calendar/v1/feed.ics?token=' + $token + '&extra=1' }
        'DUPLICATE_TOKEN' { return '/calendar/v1/feed.ics?token=' + $token + '&token=' + $token }
        'WRONG_PATH' { return '/not-calendar?token=' + $token }
        'SUFFIX_PATH' { return '/calendar/v1/feed.ics/extra?token=' + $token }
        'ENCODED_DOT' { return '/calendar/v1/feed%2eics?token=' + $token }
        'ENCODED_SLASH' { return '/calendar%2fv1/feed.ics?token=' + $token }
        'DOUBLE_SLASH' { return '/calendar//v1/feed.ics?token=' + $token }
        default { throw 'The fixture selected an unsupported synthetic request target.' }
    }
}

function New-SafeTempDirectory {
    $separators = [char[]] @(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd($separators)
    $candidate = [IO.Path]::GetFullPath((Join-Path $tempRoot (
        'personal-memo-cloudflare-external-' + [Guid]::NewGuid().ToString('N')
    )))
    $comparison = if ($env:OS -eq 'Windows_NT') {
        [StringComparison]::OrdinalIgnoreCase
    } else {
        [StringComparison]::Ordinal
    }
    if (-not $candidate.StartsWith(
            $tempRoot + [IO.Path]::DirectorySeparatorChar,
            $comparison
        ) -or
        -not (Split-Path -Leaf $candidate).StartsWith(
            'personal-memo-cloudflare-external-',
            [StringComparison]::Ordinal
        )) {
        throw 'Refusing an unexpected external qualification temporary path.'
    }
    $null = New-Item -ItemType Directory -Path $candidate
    return $candidate
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
                if ($_ -match "(?i)^${escapedName}:\s*(.*?)\s*$") {
                    $Matches[1]
                }
            }
    )
}

function Assert-CurlConfigValue {
    param([Parameter(Mandatory = $true)][string] $Value)

    if ($Value.IndexOf('"', [StringComparison]::Ordinal) -ge 0 -or
        $Value.IndexOf("`r", [StringComparison]::Ordinal) -ge 0 -or
        $Value.IndexOf("`n", [StringComparison]::Ordinal) -ge 0) {
        throw 'A generated curl configuration value was unsafe.'
    }
}

function Invoke-SyntheticProbe {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('LocalSyntheticOrigin', 'CloudflareExternal')]
        [string] $TargetKind,

        [Parameter(Mandatory = $true)][string] $RequestTarget,

        [Parameter(Mandatory = $true)]
        [ValidateSet('GET', 'HEAD', 'POST')]
        [string] $Method,

        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $TempDirectory,
        [string] $Body = ''
    )

    if ($RequestTarget -cnotmatch '^/[A-Za-z0-9_./;?%=&-]+$' -or
        $RequestTarget.Length -gt 256 -or
        $Name -cnotmatch '^[a-z0-9-]+$') {
        throw 'A generated synthetic probe target was outside the fixed test shape.'
    }
    $baseUri = if ($TargetKind -ceq 'LocalSyntheticOrigin') {
        $syntheticOriginBaseUri
    } else {
        if ($null -eq $externalBaseUri) {
            throw 'The validated external HTTPS authority was not initialized.'
        }
        $externalBaseUri
    }
    $protocol = if ($TargetKind -ceq 'LocalSyntheticOrigin') { '=http' } else { '=https' }
    $absoluteTarget = $baseUri + $RequestTarget
    $headerFile = Join-Path $TempDirectory ($Name + '.headers')
    $bodyFile = Join-Path $TempDirectory ($Name + '.body')
    $curlHeaderFile = [IO.Path]::GetFullPath($headerFile).Replace('\', '/')
    $curlBodyFile = [IO.Path]::GetFullPath($bodyFile).Replace('\', '/')
    foreach ($configValue in @($absoluteTarget, $curlHeaderFile, $curlBodyFile)) {
        Assert-CurlConfigValue -Value $configValue
    }

    $configLines = New-Object Collections.Generic.List[string]
    $configLines.Add(('url = "{0}"' -f $absoluteTarget))
    $configLines.Add(('dump-header = "{0}"' -f $curlHeaderFile))
    $configLines.Add(('output = "{0}"' -f $curlBodyFile))
    $configLines.Add('write-out = "%{http_code}|%{size_download}|%{time_total}"')
    if ($Method -ceq 'HEAD') {
        $configLines.Add('head')
    } else {
        $configLines.Add(('request = "{0}"' -f $Method))
    }
    if ($Body.Length -gt 0) {
        Assert-CurlConfigValue -Value $Body
        $configLines.Add(('data-binary = "{0}"' -f $Body))
    }
    $configText = [string]::Join("`n", $configLines) + "`n"

    $process = New-Object Diagnostics.Process
    $process.StartInfo = New-Object Diagnostics.ProcessStartInfo
    $process.StartInfo.FileName = $script:CurlExecutable
    $process.StartInfo.Arguments = (
        '--silent --show-error --noproxy "*" --connect-timeout 5 --max-time 20 ' +
        '--max-filesize 4096 --proto "' + $protocol + '" --max-redirs 0 --path-as-is --config -'
    )
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true

    $standardError = $null
    try {
        $null = $process.Start()
        $process.StandardInput.Write($configText)
        $process.StandardInput.Close()
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        if (-not $process.WaitForExit(25000)) {
            $process.Kill()
            throw 'A synthetic probe exceeded its fixed process deadline.'
        }
        if ($process.ExitCode -ne 0) {
            throw 'A synthetic probe failed before an HTTP response was safely verified.'
        }
    } finally {
        $configText = $null
        $absoluteTarget = $null
        $standardError = $null
        $process.Dispose()
    }

    $metadata = [string] $standardOutput
    if ($metadata -notmatch '^(?<status>[0-9]{3})\|(?<download>[0-9]+(?:\.[0-9]+)?)\|(?<duration>[0-9]+(?:\.[0-9]+)?)$') {
        throw 'A synthetic probe returned unexpected non-secret metadata.'
    }
    return [PSCustomObject]@{
        Status = $Matches['status']
        DownloadBytes = [decimal]::Parse(
            $Matches['download'],
            [Globalization.CultureInfo]::InvariantCulture
        )
        DurationSeconds = [decimal]::Parse(
            $Matches['duration'],
            [Globalization.CultureInfo]::InvariantCulture
        )
        HeaderFile = $headerFile
        BodyFile = $bodyFile
    }
}

function Assert-ExactHeader {
    param(
        [Parameter(Mandatory = $true)][string] $HeaderFile,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Expected
    )

    $values = @(Get-HeaderValues -HeaderFile $HeaderFile -Name $Name)
    if ($values.Count -ne 1 -or
        -not $values[0].Equals($Expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw "A synthetic response did not contain the required fixed $Name policy."
    }
}

function Assert-NoHeader {
    param(
        [Parameter(Mandatory = $true)][string] $HeaderFile,
        [Parameter(Mandatory = $true)][string] $Name
    )

    if (@(Get-HeaderValues -HeaderFile $HeaderFile -Name $Name).Count -ne 0) {
        throw "A synthetic response exposed a forbidden $Name header."
    }
}

function Assert-CloudflareBypassStatus {
    param([Parameter(Mandatory = $true)][string] $HeaderFile)

    $cacheStatuses = @(Get-HeaderValues -HeaderFile $HeaderFile -Name 'CF-Cache-Status')
    $rayValues = @(Get-HeaderValues -HeaderFile $HeaderFile -Name 'CF-Ray')
    if ($cacheStatuses.Count -ne 1 -or $rayValues.Count -lt 1) {
        throw 'The external response lacked Cloudflare cache or request evidence.'
    }
    $cacheStatus = $cacheStatuses[0].ToUpperInvariant()
    if ($cacheStatus -cne 'BYPASS' -and $cacheStatus -cne 'DYNAMIC') {
        throw 'The Cloudflare cache status was outside the reviewed BYPASS or DYNAMIC allow-list.'
    }
    return $cacheStatus
}

function Assert-SyntheticCalendarBody {
    param([Parameter(Mandatory = $true)][string] $BodyFile)

    $expected = [Text.Encoding]::ASCII.GetBytes(
        "BEGIN:VCALENDAR`r`nVERSION:2.0`r`nEND:VCALENDAR`r`n"
    )
    $actual = [IO.File]::ReadAllBytes($BodyFile)
    if ($actual.Length -ne $expected.Length) {
        throw 'The positive response was not the fixed disposable synthetic calendar fixture.'
    }
    for ($index = 0; $index -lt $expected.Length; $index++) {
        if ($actual[$index] -ne $expected[$index]) {
            throw 'The positive response was not the fixed disposable synthetic calendar fixture.'
        }
    }
}

function Assert-PositiveSyntheticResponse {
    param(
        [Parameter(Mandatory = $true)] $Probe,
        [Parameter(Mandatory = $true)][bool] $ExpectBody,
        [Parameter(Mandatory = $true)][bool] $RequireCloudflare
    )

    if ($Probe.Status -cne '200') {
        throw 'The exact synthetic positive probe did not return 200.'
    }
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'Cache-Control' -Expected 'no-store'
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'Referrer-Policy' -Expected 'no-referrer'
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'X-Content-Type-Options' -Expected 'nosniff'
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'Content-Length' -Expected '45'
    Assert-NoHeader -HeaderFile $Probe.HeaderFile -Name 'Set-Cookie'
    $cacheStatus = $null
    if ($RequireCloudflare) {
        $cacheStatus = Assert-CloudflareBypassStatus -HeaderFile $Probe.HeaderFile
    }
    if ($ExpectBody) {
        if ($Probe.DownloadBytes -le 0) {
            throw 'The exact synthetic GET response was unexpectedly empty.'
        }
        Assert-SyntheticCalendarBody -BodyFile $Probe.BodyFile
    } elseif ($Probe.DownloadBytes -ne 0) {
        # curl --head writes the response header block to its output file even though no response
        # body was downloaded. size_download is the bodyless signal; response headers are already
        # captured and verified independently through dump-header.
        throw 'The exact synthetic HEAD response was not bodyless.'
    }
    return $cacheStatus
}

function Assert-OriginEdgeEmpty404 {
    param(
        [Parameter(Mandatory = $true)] $Probe,
        [Parameter(Mandatory = $true)][bool] $RequireCloudflare
    )

    if ($Probe.Status -cne '404' -or
        $Probe.DownloadBytes -ne 0 -or
        (Get-Item -LiteralPath $Probe.BodyFile).Length -ne 0) {
        throw 'An external deny probe was not reduced to a generic empty 404.'
    }
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'Cache-Control' -Expected 'no-store'
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'Referrer-Policy' -Expected 'no-referrer'
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'X-Content-Type-Options' -Expected 'nosniff'
    Assert-NoHeader -HeaderFile $Probe.HeaderFile -Name 'Content-Type'
    Assert-NoHeader -HeaderFile $Probe.HeaderFile -Name 'Set-Cookie'
    if ($RequireCloudflare) {
        return Assert-CloudflareBypassStatus -HeaderFile $Probe.HeaderFile
    }
    return $null
}

function Assert-RemoteCatchAllSafe404 {
    param(
        [Parameter(Mandatory = $true)] $Probe,
        [Parameter(Mandatory = $true)][string[]] $Bearers
    )

    if ($Probe.Status -cne '404' -or
        $Probe.DownloadBytes -gt 1024 -or
        (Get-Item -LiteralPath $Probe.BodyFile).Length -gt 1024) {
        throw 'A remote catch-all probe was not reduced to a bounded 404.'
    }
    Assert-NoHeader -HeaderFile $Probe.HeaderFile -Name 'Set-Cookie'
    $cacheStatus = Assert-CloudflareBypassStatus -HeaderFile $Probe.HeaderFile
    $bodyBytes = [IO.File]::ReadAllBytes($Probe.BodyFile)
    try {
        $bodyText = [Text.Encoding]::UTF8.GetString($bodyBytes)
        foreach ($bearer in $Bearers) {
            if ($bodyText.IndexOf($bearer, [StringComparison]::Ordinal) -ge 0) {
                throw 'A remote catch-all response reflected a bearer, query, or synthetic fixture body.'
            }
        }
        if ($bodyText.IndexOf('BEGIN:VCALENDAR', [StringComparison]::Ordinal) -ge 0 -or
            $bodyText.IndexOf('token=', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'A remote catch-all response reflected a bearer, query, or synthetic fixture body.'
        }
    } finally {
        $bodyText = $null
        [Array]::Clear($bodyBytes, 0, $bodyBytes.Length)
    }
    return $cacheStatus
}

function Assert-OriginEdgeEmpty429 {
    param([Parameter(Mandatory = $true)] $Probe)

    if ($Probe.Status -cne '429' -or
        $Probe.DownloadBytes -ne 0 -or
        (Get-Item -LiteralPath $Probe.BodyFile).Length -ne 0) {
        throw 'The external rate-limit probe was not reduced to a bodyless 429.'
    }
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'Cache-Control' -Expected 'no-store'
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'Referrer-Policy' -Expected 'no-referrer'
    Assert-ExactHeader -HeaderFile $Probe.HeaderFile -Name 'X-Content-Type-Options' -Expected 'nosniff'
    Assert-NoHeader -HeaderFile $Probe.HeaderFile -Name 'Content-Type'
    Assert-NoHeader -HeaderFile $Probe.HeaderFile -Name 'Set-Cookie'
    return Assert-CloudflareBypassStatus -HeaderFile $Probe.HeaderFile
}

function Assert-OwnedSyntheticLogs {
    param(
        [Parameter(Mandatory = $true)][string[]] $Bearers,
        [Parameter(Mandatory = $true)][bool] $RateLimitObserved
    )

    $ownedLogs = Invoke-SyntheticCompose `
        -Arguments @('logs', '--no-color', 'backend', 'calendar-feed-edge') `
        -Capture
    try {
        foreach ($bearer in $Bearers) {
            if ($ownedLogs.IndexOf($bearer, [StringComparison]::Ordinal) -ge 0) {
                throw 'A synthetic bearer or raw query appeared in an owned disposable-origin log.'
            }
        }
        if ($ownedLogs.IndexOf('?token=', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'The synthetic bearer or raw query appeared in an owned disposable-origin log.'
        }
        $safeMarkers = @(
            'method=GET route=calendar-feed status=200',
            'method=GET route=calendar-feed status=404',
            'method=GET route=calendar-feed status=500',
            'method=HEAD route=calendar-feed status=200',
            'method=GET route=rejected status=404'
        )
        if ($RateLimitObserved) {
            $safeMarkers += 'method=GET route=calendar-feed status=429'
        }
        foreach ($safeMarker in $safeMarkers) {
            if ($ownedLogs.IndexOf($safeMarker, [StringComparison]::Ordinal) -lt 0) {
                throw 'An expected fixed safe-route marker was absent from owned synthetic logs.'
            }
        }
    } finally {
        $ownedLogs = $null
    }
}

function Assert-NoExternalArtifactReflection {
    param(
        [Parameter(Mandatory = $true)][string] $TempDirectory,
        [Parameter(Mandatory = $true)][string[]] $Bearers
    )

    foreach ($artifact in @(Get-ChildItem -LiteralPath $TempDirectory -File)) {
        $artifactBytes = [IO.File]::ReadAllBytes($artifact.FullName)
        try {
            $artifactText = [Text.Encoding]::UTF8.GetString($artifactBytes)
            foreach ($bearer in $Bearers) {
                if ($artifactText.IndexOf($bearer, [StringComparison]::Ordinal) -ge 0) {
                    throw 'An external response header or body reflected the synthetic bearer or query.'
                }
            }
            if ($artifactText.IndexOf('token=', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw 'An external response header or body reflected the synthetic bearer or query.'
            }
        } finally {
            $artifactText = $null
            [Array]::Clear($artifactBytes, 0, $artifactBytes.Length)
        }
    }
}

function Get-SyntheticBearerValues {
    param([Parameter(Mandatory = $true)] $BearerSet)

    return @(
        [string] $BearerSet.Valid,
        [string] $BearerSet.Unknown,
        [string] $BearerSet.Failure,
        [string] $BearerSet.NonCanonical
    )
}

function Invoke-FixtureProbe {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('LocalSyntheticOrigin', 'CloudflareExternal')]
        [string] $TargetKind,
        [Parameter(Mandatory = $true)] $Definition,
        [Parameter(Mandatory = $true)] $BearerSet,
        [Parameter(Mandatory = $true)][string] $TempDirectory
    )

    $requestTarget = Resolve-SyntheticRequestTarget -ProbeDefinition $Definition -BearerSet $BearerSet
    $body = if ($Definition.body -ceq 'ONE_BYTE') { 'x' } else { '' }
    $probe = Invoke-SyntheticProbe -TargetKind $TargetKind -RequestTarget $requestTarget -Method $Definition.method -Name $Definition.name -TempDirectory $TempDirectory -Body $body
    $requireCloudflare = $TargetKind -ceq 'CloudflareExternal'
    $cacheStatus = $null
    switch -CaseSensitive ([string] $Definition.expected) {
        'POSITIVE_BODY' {
            $cacheStatus = Assert-PositiveSyntheticResponse -Probe $probe -ExpectBody $true -RequireCloudflare $requireCloudflare
        }
        'POSITIVE_BODYLESS' {
            $cacheStatus = Assert-PositiveSyntheticResponse -Probe $probe -ExpectBody $false -RequireCloudflare $requireCloudflare
        }
        'ORIGIN_EMPTY_404' {
            $cacheStatus = Assert-OriginEdgeEmpty404 -Probe $probe -RequireCloudflare $requireCloudflare
        }
        'REMOTE_BOUNDED_404' {
            if (-not $requireCloudflare) {
                throw 'A remote catch-all assertion was selected for a local-only probe.'
            }
            $cacheStatus = Assert-RemoteCatchAllSafe404 -Probe $probe -Bearers (Get-SyntheticBearerValues -BearerSet $BearerSet)
        }
        default {
            throw 'The fixture selected an unsupported synthetic response assertion.'
        }
    }
    return [PSCustomObject]@{
        DurationSeconds = $probe.DurationSeconds
        CacheStatus = $cacheStatus
        HttpStatus = $probe.Status
    }
}

function Add-CloudflareCacheStatusCount {
    param(
        [Parameter(Mandatory = $true)][hashtable] $Counts,
        [Parameter(Mandatory = $true)][string] $Status
    )

    if (-not $Counts.ContainsKey($Status) -or
        ($Status -cne 'BYPASS' -and $Status -cne 'DYNAMIC')) {
        throw 'A cache status escaped the strict receipt allow-list.'
    }
    $Counts[$Status] = [int] $Counts[$Status] + 1
}

function Assert-PartialQualificationReceipt {
    param(
        [Parameter(Mandatory = $true)] $Receipt,
        [Parameter(Mandatory = $true)] $Fixture,
        [Parameter(Mandatory = $true)][string] $FixtureSha256
    )

    $receiptPropertyNames = @(
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
    Assert-ExactObjectProperties -Value $Receipt -Expected $receiptPropertyNames -Context 'qualification receipt'
    Assert-ExactObjectProperties -Value $Receipt.cloudflareCacheStatusCounts -Expected @(
        'BYPASS',
        'DYNAMIC'
    ) -Context 'qualification receipt cache-status counts'
    Assert-JsonInteger -Value $Receipt.schemaVersion -Minimum 1 -Maximum 1 -Context 'qualification receipt schemaVersion'
    Assert-JsonInteger -Value $Receipt.exactPositiveProbeCount -Minimum 1 -Maximum 30 -Context 'qualification receipt exactPositiveProbeCount'
    Assert-JsonInteger -Value $Receipt.emptyNoStoreOriginDenyProbeCount -Minimum 1 -Maximum 30 -Context 'qualification receipt emptyNoStoreOriginDenyProbeCount'
    Assert-JsonInteger -Value $Receipt.boundedRemoteCatchAllDenyProbeCount -Minimum 1 -Maximum 30 -Context 'qualification receipt boundedRemoteCatchAllDenyProbeCount'
    Assert-JsonInteger -Value $Receipt.rateLimitAttemptCount -Minimum 1 -Maximum 30 -Context 'qualification receipt rateLimitAttemptCount'
    Assert-JsonInteger -Value $Receipt.bodylessRateLimitProbeCount -Minimum 0 -Maximum 1 -Context 'qualification receipt bodylessRateLimitProbeCount'
    Assert-JsonInteger -Value $Receipt.totalExternalProbeCount -Minimum 4 -Maximum 60 -Context 'qualification receipt totalExternalProbeCount'
    Assert-JsonInteger -Value $Receipt.cloudflareCacheStatusCounts.BYPASS -Minimum 0 -Maximum 60 -Context 'qualification receipt BYPASS count'
    Assert-JsonInteger -Value $Receipt.cloudflareCacheStatusCounts.DYNAMIC -Minimum 0 -Maximum 60 -Context 'qualification receipt DYNAMIC count'
    Assert-JsonInteger -Value $Receipt.cloudflareCacheHitCount -Minimum 0 -Maximum 0 -Context 'qualification receipt cache HIT count'
    Assert-JsonNumber -Value $Receipt.maximumObservedLatencyMilliseconds -Minimum 0 -Maximum 25000 -Context 'qualification receipt maximum latency'

    if ($Receipt.schemaVersion -ne 1 -or
        $Receipt.status -cne 'TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED' -or
        $Receipt.classification -cne 'SOLO_PROVISIONAL/REPORT_ONLY' -or
        $Receipt.decision -cne 'NO_GO') {
        throw 'The qualification receipt status or fail-closed decision was not exact.'
    }
    if ($Receipt.hostnameSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $Receipt.probeFixtureSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $Receipt.probeFixtureSha256 -cne $FixtureSha256 -or
        $Receipt.probeFixtureId -cne $Fixture.fixtureId) {
        throw 'The qualification receipt hash or fixture identity was invalid.'
    }
    try {
        $null = [DateTimeOffset]::ParseExact(
            [string] $Receipt.recordedAt,
            'o',
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind
        )
    } catch {
        throw 'The qualification receipt timestamp was not an exact round-trip date-time.'
    }

    $expectedPositive = @(
        @($Fixture.externalProbes) |
            Where-Object { $_.expected -ceq 'POSITIVE_BODY' -or $_.expected -ceq 'POSITIVE_BODYLESS' }
    ).Count
    $expectedOriginDeny = @(
        @($Fixture.externalProbes) |
            Where-Object { $_.expected -ceq 'ORIGIN_EMPTY_404' }
    ).Count
    $expectedRemoteDeny = @(
        @($Fixture.externalProbes) |
            Where-Object { $_.expected -ceq 'REMOTE_BOUNDED_404' }
    ).Count
    $maximumAttempts = [int] $Fixture.rateLimitProbe.maximumAttempts
    if ($Receipt.exactPositiveProbeCount -ne $expectedPositive -or
        $Receipt.emptyNoStoreOriginDenyProbeCount -ne $expectedOriginDeny -or
        $Receipt.boundedRemoteCatchAllDenyProbeCount -ne $expectedRemoteDeny -or
        $Receipt.rateLimitAttemptCount -lt 1 -or
        $Receipt.rateLimitAttemptCount -gt $maximumAttempts) {
        throw 'The qualification receipt probe category counts did not match the fixture.'
    }

    if ($Receipt.rateLimitObservation -ceq 'OBSERVED_BODYLESS_429') {
        if ($Receipt.bodylessRateLimitProbeCount -ne 1) {
            throw 'The observed rate-limit result did not bind to one bodyless 429.'
        }
    } elseif ($Receipt.rateLimitObservation -ceq 'NOT_OBSERVED_WITHIN_BOUNDED_ATTEMPTS') {
        if ($Receipt.bodylessRateLimitProbeCount -ne 0 -or
            $Receipt.rateLimitAttemptCount -ne $maximumAttempts) {
            throw 'The non-observed rate-limit result did not exhaust only the bounded fixture attempts.'
        }
    } else {
        throw 'The qualification receipt rate-limit observation was unsupported.'
    }

    $expectedTotal = @($Fixture.externalProbes).Count + [int] $Receipt.rateLimitAttemptCount
    $cacheTotal = [int] $Receipt.cloudflareCacheStatusCounts.BYPASS +
        [int] $Receipt.cloudflareCacheStatusCounts.DYNAMIC
    if ($Receipt.totalExternalProbeCount -ne $expectedTotal -or
        $cacheTotal -ne $expectedTotal -or
        $Receipt.cloudflareCacheStatusCounts.BYPASS -lt 0 -or
        $Receipt.cloudflareCacheStatusCounts.DYNAMIC -lt 0 -or
        $Receipt.cloudflareCacheHitCount -ne 0) {
        throw 'The qualification receipt total or cache-status counts were inconsistent.'
    }
    $maximumLatency = [double] $Receipt.maximumObservedLatencyMilliseconds
    if ([double]::IsNaN($maximumLatency) -or
        [double]::IsInfinity($maximumLatency) -or
        $maximumLatency -lt 0 -or
        $maximumLatency -gt 25000) {
        throw 'The qualification receipt latency was outside the probe process boundary.'
    }
    if ($Receipt.ownedLogSentinel -cne 'PASS' -or
        $Receipt.externalArtifactReflectionSentinel -cne 'PASS' -or
        $Receipt.cloudflaredConnectorLogSentinel -cne 'REQUIRED_NOT_VERIFIED' -or
        $Receipt.cloudflareCustomerProviderLogSentinel -cne 'REQUIRED_NOT_VERIFIED' -or
        $Receipt.tunnelReplicaVerification -cne 'REQUIRED_NOT_VERIFIED') {
        throw 'The qualification receipt log or replica evidence state was not fail-closed.'
    }
}

function Test-PartialQualificationReceiptValidator {
    param([Parameter(Mandatory = $true)] $FixtureInfo)

    $fixture = $FixtureInfo.Document
    $total = @($fixture.externalProbes).Count + [int] $fixture.rateLimitProbe.maximumAttempts
    $sample = [PSCustomObject][ordered]@{
        schemaVersion = 1
        status = 'TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED'
        classification = 'SOLO_PROVISIONAL/REPORT_ONLY'
        decision = 'NO_GO'
        recordedAt = [DateTimeOffset]::UtcNow.ToString('o', [Globalization.CultureInfo]::InvariantCulture)
        hostnameSha256 = ('0' * 64)
        probeFixtureId = $fixture.fixtureId
        probeFixtureSha256 = $FixtureInfo.Sha256
        exactPositiveProbeCount = @(@($fixture.externalProbes) | Where-Object { $_.expected -like 'POSITIVE_*' }).Count
        emptyNoStoreOriginDenyProbeCount = @(@($fixture.externalProbes) | Where-Object { $_.expected -ceq 'ORIGIN_EMPTY_404' }).Count
        boundedRemoteCatchAllDenyProbeCount = @(@($fixture.externalProbes) | Where-Object { $_.expected -ceq 'REMOTE_BOUNDED_404' }).Count
        rateLimitAttemptCount = [int] $fixture.rateLimitProbe.maximumAttempts
        rateLimitObservation = 'NOT_OBSERVED_WITHIN_BOUNDED_ATTEMPTS'
        bodylessRateLimitProbeCount = 0
        totalExternalProbeCount = $total
        cloudflareCacheStatusCounts = [PSCustomObject][ordered]@{
            BYPASS = $total
            DYNAMIC = 0
        }
        cloudflareCacheHitCount = 0
        maximumObservedLatencyMilliseconds = 0
        ownedLogSentinel = 'PASS'
        externalArtifactReflectionSentinel = 'PASS'
        cloudflaredConnectorLogSentinel = 'REQUIRED_NOT_VERIFIED'
        cloudflareCustomerProviderLogSentinel = 'REQUIRED_NOT_VERIFIED'
        tunnelReplicaVerification = 'REQUIRED_NOT_VERIFIED'
    }
    Assert-PartialQualificationReceipt -Receipt $sample -Fixture $fixture -FixtureSha256 $FixtureInfo.Sha256
    $sample.totalExternalProbeCount++
    $tamperRejected = $false
    try {
        Assert-PartialQualificationReceipt -Receipt $sample -Fixture $fixture -FixtureSha256 $FixtureInfo.Sha256
    } catch {
        $tamperRejected = $true
    }
    if (-not $tamperRejected) {
        throw 'The qualification receipt cross-field validator accepted a tampered total.'
    }
    $sample.totalExternalProbeCount = $total
    $sample.rateLimitAttemptCount = [string] $fixture.rateLimitProbe.maximumAttempts
    $typeTamperRejected = $false
    try {
        Assert-PartialQualificationReceipt -Receipt $sample -Fixture $fixture -FixtureSha256 $FixtureInfo.Sha256
    } catch {
        $typeTamperRejected = $true
    }
    if (-not $typeTamperRejected) {
        throw 'The qualification receipt validator accepted a string in an integer field.'
    }
    $sample.rateLimitAttemptCount = [int] $fixture.rateLimitProbe.maximumAttempts
    $sample | Add-Member -NotePropertyName unreviewedEvidence -NotePropertyValue 'PASS'
    $extraPropertyRejected = $false
    try {
        Assert-PartialQualificationReceipt -Receipt $sample -Fixture $fixture -FixtureSha256 $FixtureInfo.Sha256
    } catch {
        $extraPropertyRejected = $true
    }
    if (-not $extraPropertyRejected) {
        throw 'The qualification receipt validator accepted an additional property.'
    }
}

function Write-PartialQualificationReceipt {
    param(
        [Parameter(Mandatory = $true)][string] $Hostname,
        [Parameter(Mandatory = $true)][decimal] $MaximumDurationSeconds,
        [Parameter(Mandatory = $true)] $FixtureInfo,
        [Parameter(Mandatory = $true)][int] $RateLimitAttemptCount,
        [Parameter(Mandatory = $true)][bool] $RateLimitObserved,
        [Parameter(Mandatory = $true)][hashtable] $CacheStatusCounts
    )

    $receiptRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'artifacts/cloudflare'))
    $expectedRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'artifacts/cloudflare'))
    if (-not $receiptRoot.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing an unexpected Cloudflare receipt directory.'
    }
    if (-not [IO.Directory]::Exists($receiptRoot)) {
        $null = [IO.Directory]::CreateDirectory($receiptRoot)
    }

    $hostnameBytes = [Text.Encoding]::UTF8.GetBytes($Hostname)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hostnameHash = ([BitConverter]::ToString($sha256.ComputeHash($hostnameBytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
        [Array]::Clear($hostnameBytes, 0, $hostnameBytes.Length)
    }
    $fixture = $FixtureInfo.Document
    $recordedAt = [DateTimeOffset]::UtcNow
    $receipt = [PSCustomObject][ordered]@{
        schemaVersion = 1
        status = 'TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED'
        classification = 'SOLO_PROVISIONAL/REPORT_ONLY'
        decision = 'NO_GO'
        recordedAt = $recordedAt.ToString('o', [Globalization.CultureInfo]::InvariantCulture)
        hostnameSha256 = $hostnameHash
        probeFixtureId = $fixture.fixtureId
        probeFixtureSha256 = $FixtureInfo.Sha256
        exactPositiveProbeCount = @(@($fixture.externalProbes) | Where-Object { $_.expected -like 'POSITIVE_*' }).Count
        emptyNoStoreOriginDenyProbeCount = @(@($fixture.externalProbes) | Where-Object { $_.expected -ceq 'ORIGIN_EMPTY_404' }).Count
        boundedRemoteCatchAllDenyProbeCount = @(@($fixture.externalProbes) | Where-Object { $_.expected -ceq 'REMOTE_BOUNDED_404' }).Count
        rateLimitAttemptCount = $RateLimitAttemptCount
        rateLimitObservation = if ($RateLimitObserved) {
            'OBSERVED_BODYLESS_429'
        } else {
            'NOT_OBSERVED_WITHIN_BOUNDED_ATTEMPTS'
        }
        bodylessRateLimitProbeCount = if ($RateLimitObserved) { 1 } else { 0 }
        totalExternalProbeCount = @($fixture.externalProbes).Count + $RateLimitAttemptCount
        cloudflareCacheStatusCounts = [PSCustomObject][ordered]@{
            BYPASS = [int] $CacheStatusCounts.BYPASS
            DYNAMIC = [int] $CacheStatusCounts.DYNAMIC
        }
        cloudflareCacheHitCount = 0
        maximumObservedLatencyMilliseconds = [Math]::Round(
            [double] ($MaximumDurationSeconds * 1000),
            3
        )
        ownedLogSentinel = 'PASS'
        externalArtifactReflectionSentinel = 'PASS'
        cloudflaredConnectorLogSentinel = 'REQUIRED_NOT_VERIFIED'
        cloudflareCustomerProviderLogSentinel = 'REQUIRED_NOT_VERIFIED'
        tunnelReplicaVerification = 'REQUIRED_NOT_VERIFIED'
    }
    Assert-PartialQualificationReceipt -Receipt $receipt -Fixture $fixture -FixtureSha256 $FixtureInfo.Sha256
    $receiptName = 'cloudflare-external-' + $recordedAt.ToString('yyyyMMddTHHmmssfffZ') + '.json'
    $receiptPath = [IO.Path]::GetFullPath((Join-Path $receiptRoot $receiptName))
    if (-not $receiptPath.StartsWith(
            $receiptRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'Refusing an unexpected Cloudflare receipt path.'
    }
    $json = $receipt | ConvertTo-Json -Depth 4
    try {
        $roundTrip = $json | ConvertFrom-Json
        Assert-PartialQualificationReceipt -Receipt $roundTrip -Fixture $fixture -FixtureSha256 $FixtureInfo.Sha256
        if ($json.IndexOf('token=', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $json.IndexOf('/calendar/', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $json.IndexOf('http://', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $json.IndexOf('https://', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'The qualification receipt attempted to persist a target, URL, or bearer query.'
        }
        [IO.File]::WriteAllText($receiptPath, $json + "`r`n", (New-Object Text.UTF8Encoding($false)))
    } finally {
        $json = $null
        $hostnameHash = $null
    }
}

$fixtureInfo = Get-ValidatedSyntheticFixture

if ($SourceOnly) {
    Assert-ScriptSource
    Test-PartialQualificationReceiptValidator -FixtureInfo $fixtureInfo
    Write-Host 'Cloudflare external qualification source-only validation passed; no network request was sent.'
    return
}

if ($PSCmdlet.ParameterSetName -ceq 'DryRun') {
    Assert-PublicHostname -Hostname $PublicHostname
    Write-Host 'Cloudflare external qualification dry-run validation passed; no network request was sent.'
    return
}

Assert-ComposeBoundary
Assert-DockerAvailable

if ($PrepareSyntheticOrigin) {
    Assert-ConnectorStoppedIfInstalled
    $prepareAttempted = $false
    $tempDirectory = $null
    $bearerSet = $null
    try {
        $prepareAttempted = $true
        # A fresh container log boundary prevents an earlier run from satisfying this run's
        # owned-log sentinel and resets the disposable origin-side rate limiter.
        Invoke-SyntheticCompose -Arguments @(
            'up', '-d', '--build', '--wait', '--force-recreate'
        )
        Assert-SyntheticTopologyRunning
        Resolve-CurlExecutable
        $tempDirectory = New-SafeTempDirectory
        $bearerSet = New-CanonicalSyntheticBearerSet
        foreach ($probeDefinition in @($fixtureInfo.Document.localProbes)) {
            $null = Invoke-FixtureProbe -TargetKind 'LocalSyntheticOrigin' -Definition $probeDefinition -BearerSet $bearerSet -TempDirectory $tempDirectory
        }
    } catch {
        if ($prepareAttempted) {
            try {
                Invoke-SyntheticCompose -Arguments @(
                    'down', '--volumes', '--remove-orphans', '--rmi', 'local'
                )
            } catch {
                Write-Warning 'The exact disposable synthetic project needs manual cleanup verification.'
            }
        }
        throw
    } finally {
        Clear-SyntheticBearerSet -BearerSet $bearerSet
        if ($null -ne $tempDirectory -and
            (Test-Path -LiteralPath $tempDirectory -PathType Container)) {
            Remove-Item -LiteralPath $tempDirectory -Recurse -Force
        }
    }
    Write-Host (
        'The disposable loopback synthetic origin is ready. Start the connector separately, ' +
        'then run the explicit external qualification mode.'
    )
    return
}

if ($CleanupSyntheticOrigin) {
    if (-not $ConnectorStoppedVerified -or -not $TunnelReplicasStoppedVerified) {
        throw 'Explicit local connector and remote tunnel-replica stop verification is required before cleanup.'
    }
    Assert-ConnectorStoppedIfInstalled
    Invoke-SyntheticCompose -Arguments @('down', '--volumes', '--remove-orphans', '--rmi', 'local')
    Write-Host 'The exact disposable Cloudflare synthetic project was removed.'
    return
}

if (-not $SyntheticOriginQualification) {
    throw 'Only the explicit disposable synthetic-origin qualification mode can send external requests.'
}

Assert-PublicHostname -Hostname $PublicHostname
Assert-SyntheticTopologyRunning
Resolve-CurlExecutable
$tempDirectory = $null
$bearerSet = $null
$durations = New-Object Collections.Generic.List[decimal]
$cacheStatusCounts = @{
    BYPASS = 0
    DYNAMIC = 0
}
$rateLimitAttemptCount = 0
$rateLimitObserved = $false
try {
    $externalBaseUri = 'https://' + $PublicHostname
    $tempDirectory = New-SafeTempDirectory
    $bearerSet = New-CanonicalSyntheticBearerSet

    foreach ($probeDefinition in @($fixtureInfo.Document.localProbes)) {
        $null = Invoke-FixtureProbe -TargetKind 'LocalSyntheticOrigin' -Definition $probeDefinition -BearerSet $bearerSet -TempDirectory $tempDirectory
    }
    foreach ($probeDefinition in @($fixtureInfo.Document.externalProbes)) {
        $result = Invoke-FixtureProbe -TargetKind 'CloudflareExternal' -Definition $probeDefinition -BearerSet $bearerSet -TempDirectory $tempDirectory
        $durations.Add($result.DurationSeconds)
        Add-CloudflareCacheStatusCount -Counts $cacheStatusCounts -Status $result.CacheStatus
    }

    $rateDefinition = $fixtureInfo.Document.rateLimitProbe
    $rateTarget = Resolve-SyntheticRequestTarget -ProbeDefinition $rateDefinition -BearerSet $bearerSet
    for ($attempt = 1; $attempt -le [int] $rateDefinition.maximumAttempts; $attempt++) {
        $rateProbe = Invoke-SyntheticProbe -TargetKind 'CloudflareExternal' -RequestTarget $rateTarget -Method $rateDefinition.method -Name ($rateDefinition.namePrefix + '-' + $attempt) -TempDirectory $tempDirectory
        $rateLimitAttemptCount++
        $durations.Add($rateProbe.DurationSeconds)
        if ($rateProbe.Status -ceq '200') {
            $cacheStatus = Assert-PositiveSyntheticResponse -Probe $rateProbe -ExpectBody $true -RequireCloudflare $true
            Add-CloudflareCacheStatusCount -Counts $cacheStatusCounts -Status $cacheStatus
            continue
        }
        if ($rateProbe.Status -ceq '429') {
            $cacheStatus = Assert-OriginEdgeEmpty429 -Probe $rateProbe
            Add-CloudflareCacheStatusCount -Counts $cacheStatusCounts -Status $cacheStatus
            $rateLimitObserved = $true
            break
        }
        throw 'The bounded external rate observation returned neither the synthetic fixture nor a bodyless origin 429.'
    }

    $bearerValues = Get-SyntheticBearerValues -BearerSet $bearerSet
    Assert-NoExternalArtifactReflection -TempDirectory $tempDirectory -Bearers $bearerValues
    Assert-OwnedSyntheticLogs -Bearers $bearerValues -RateLimitObserved $rateLimitObserved
    $maximumDuration = ($durations | Measure-Object -Maximum).Maximum
    Write-PartialQualificationReceipt -Hostname $PublicHostname -MaximumDurationSeconds $maximumDuration -FixtureInfo $fixtureInfo -RateLimitAttemptCount $rateLimitAttemptCount -RateLimitObserved $rateLimitObserved -CacheStatusCounts $cacheStatusCounts
    $rateSummary = if ($rateLimitObserved) {
        'OBSERVED_BODYLESS_429'
    } else {
        'NOT_OBSERVED_WITHIN_BOUNDED_ATTEMPTS'
    }
    Write-Host (
        'External transport/path/cache probes completed with owned-log sentinel PASS. ' +
        'Bounded rate observation: ' + $rateSummary + '. ' +
        'This is not overall qualification: Cloudflare customer/provider log sentinel and ' +
        'tunnel replica verification remain REQUIRED. ' +
        'A non-secret SOLO_PROVISIONAL/REPORT_ONLY NO_GO receipt was written.'
    )
} finally {
    $rateTarget = $null
    $externalBaseUri = $null
    Clear-SyntheticBearerSet -BearerSet $bearerSet
    if ($null -ne $tempDirectory -and
        (Test-Path -LiteralPath $tempDirectory -PathType Container)) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
