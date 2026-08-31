#requires -Version 5.1

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [ValidateRange(1, 65535)]
    [int] $OllamaPort = 11435,

    [ValidateRange(1, 65535)]
    [int] $FakeBackendPort = 18080,

    [ValidateRange(1, 65535)]
    [int] $LiquidBackendPort = 18081,

    [string] $ReceiptDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$script:ExpectedOllamaPort = 11435
$script:ExpectedOllamaVersion = '0.32.7'
$script:ExpectedModelTag = 'hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0'
$script:ExpectedModelDigest = '677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822'
$script:ExpectedGateway = 'ollama-local-gateway-v2+local-semantic-patch-v2'
$script:ExpectedContextLength = 4096
$script:PersonalComposeProject = 'personal-memo-private-win'
$script:FailurePrefix = 'AI_PRODUCT_SMOKE_SAFE_CODE_'

function Stop-WithSafeCode {
    param([Parameter(Mandatory = $true)][string] $Code)
    throw [InvalidOperationException]::new($script:FailurePrefix + $Code)
}

function Convert-ExceptionToSafeCode {
    param([Parameter(Mandatory = $true)] $Exception)
    $message = [string] $Exception.Message
    if ($message.StartsWith($script:FailurePrefix, [StringComparison]::Ordinal)) {
        return $message.Substring($script:FailurePrefix.Length)
    }
    return 'UNEXPECTED_FAILURE'
}

function Require-CommandPath {
    param([Parameter(Mandatory = $true)][string] $Name)
    $command = Get-Command -Name $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        Stop-WithSafeCode ('MISSING_COMMAND_' + ($Name -replace '[^A-Za-z0-9]', '_').ToUpperInvariant())
    }
    return [IO.Path]::GetFullPath($command.Source)
}

function Invoke-NativeResult {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [Parameter(Mandatory = $true)][string[]] $ArgumentList,
        [string] $WorkingDirectory
    )
    $previousLocation = Get-Location
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        if (-not [string]::IsNullOrWhiteSpace($WorkingDirectory)) {
            Set-Location -LiteralPath $WorkingDirectory
        }
        # Windows PowerShell 5.1 promotes a native process' redirected stderr records according to
        # ErrorActionPreference. Docker writes normal progress to stderr, so capture it without
        # turning a successful native command into a terminating PowerShell error; the exact native
        # exit code remains authoritative below.
        $ErrorActionPreference = 'Continue'
        $lines = @(& $FilePath @ArgumentList 2>&1 | ForEach-Object { [string] $_ })
        $exitCode = $LASTEXITCODE
        return [pscustomobject]@{
            ExitCode = [int] $exitCode
            Text = (($lines -join "`n").Trim())
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Set-Location -LiteralPath $previousLocation.Path
    }
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [Parameter(Mandatory = $true)][string[]] $ArgumentList,
        [string] $WorkingDirectory,
        [Parameter(Mandatory = $true)][string] $FailureCode
    )
    $result = Invoke-NativeResult -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory
    if ($result.ExitCode -ne 0) {
        $boundedSuffix = 'NATIVE_EXIT'
        if ($result.Text -match '(?i)unhealthy|health check') {
            $boundedSuffix = 'UNHEALTHY'
        }
        elseif ($result.Text -match '(?i)no such image|unable to find image') {
            $boundedSuffix = 'NO_IMAGE'
        }
        elseif ($result.Text -match '(?i)port is already allocated|address already in use|bind:') {
            $boundedSuffix = 'PORT_BIND'
        }
        elseif ($result.Text -match '(?i)env file|failed to parse|invalid interpolation') {
            $boundedSuffix = 'ENV_PARSE'
        }
        elseif ($result.Text -match '(?i)exited|dependency failed to start') {
            $boundedSuffix = 'EXITED'
        }
        elseif ($result.Text -match '(?i)pull access denied|manifest unknown|failed to resolve reference') {
            $boundedSuffix = 'IMAGE_PULL'
        }
        elseif ($result.Text -match '(?i)permission denied|access is denied') {
            $boundedSuffix = 'PERMISSION'
        }
        elseif ($result.Text -match '(?i)failed to create|error response from daemon') {
            $boundedSuffix = 'DAEMON_RESPONSE'
        }
        elseif ($result.Text -match '(?i)cannot connect|error during connect|daemon is not running') {
            $boundedSuffix = 'DAEMON_CONNECTION'
        }
        elseif ($result.Text -match '(?i)unknown flag|unknown command|unknown shorthand') {
            $boundedSuffix = 'CLI_OPTION'
        }
        elseif ($result.Text -match '(?i)required variable|must be set|is required') {
            $boundedSuffix = 'REQUIRED_VARIABLE'
            foreach ($allowedVariableName in @(
                'AI_PRODUCT_SMOKE_DATABASE_PASSWORD',
                'AI_PRODUCT_SMOKE_BACKEND_IMAGE',
                'AI_PRODUCT_SMOKE_BACKEND_PORT',
                'AI_PRODUCT_SMOKE_LOCAL_MODEL_ENABLED',
                'AI_PRODUCT_SMOKE_INVOCATION_MODE',
                'AI_PRODUCT_SMOKE_APPROVED_CORRECTIONS_ENABLED'
            )) {
                if ($result.Text.IndexOf($allowedVariableName, [StringComparison]::Ordinal) -ge 0) {
                    $boundedSuffix = 'REQUIRED_' + $allowedVariableName
                    break
                }
            }
        }
        elseif ($result.Text -match '(?i)already exists|conflict') {
            $boundedSuffix = 'CONFLICT'
        }
        Stop-WithSafeCode ($FailureCode + '_' + $boundedSuffix)
    }
    return $result.Text
}

function Assert-ExactLoopbackPortFree {
    param([Parameter(Mandatory = $true)][int] $Port)
    $listener = $null
    try {
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
        $listener.Start()
    }
    catch {
        Stop-WithSafeCode ('PORT_NOT_FREE_' + $Port)
    }
    finally {
        if ($null -ne $listener) {
            $listener.Stop()
        }
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string] $Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Stop-WithSafeCode 'SOURCE_ARTIFACT_MISSING'
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Value
    )
    [IO.File]::WriteAllText($Path, $Value, (New-Object Text.UTF8Encoding($false)))
}

function ConvertTo-CompactJson {
    param([Parameter(Mandatory = $true)] $Value)
    return ($Value | ConvertTo-Json -Depth 40 -Compress)
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string] $Method,
        [Parameter(Mandatory = $true)][string] $Uri,
        [Parameter(Mandatory = $true)] $WebSession,
        [hashtable] $Headers = @{},
        $Body,
        [int] $ExpectedStatus = 200,
        [int] $TimeoutSeconds = 65
    )
    try {
        $arguments = @{
            Method = $Method
            Uri = $Uri
            WebSession = $WebSession
            Headers = $Headers
            TimeoutSec = $TimeoutSeconds
            UseBasicParsing = $true
            ErrorAction = 'Stop'
        }
        if ($PSBoundParameters.ContainsKey('Body')) {
            $encoded = [Text.Encoding]::UTF8.GetBytes((ConvertTo-CompactJson -Value $Body))
            $arguments.Body = $encoded
            $arguments.ContentType = 'application/json; charset=utf-8'
        }
        $response = Invoke-WebRequest @arguments
        if ([int] $response.StatusCode -ne $ExpectedStatus) {
            Stop-WithSafeCode 'PRODUCT_API_STATUS_INVALID'
        }
        $rawStream = $response.RawContentStream
        if ($null -eq $rawStream) {
            Stop-WithSafeCode 'PRODUCT_API_BODY_MISSING'
        }
        if ($rawStream.CanSeek) {
            $rawStream.Position = 0
        }
        $memory = New-Object IO.MemoryStream
        try {
            $buffer = New-Object byte[] 4096
            $total = 0
            while ($true) {
                $read = $rawStream.Read($buffer, 0, $buffer.Length)
                if ($read -le 0) { break }
                $total += $read
                if ($total -gt 262144) {
                    Stop-WithSafeCode 'PRODUCT_API_BODY_TOO_LARGE'
                }
                $memory.Write($buffer, 0, $read)
            }
            if ($total -lt 2) {
                Stop-WithSafeCode 'PRODUCT_API_BODY_MISSING'
            }
            $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
            $jsonText = $strictUtf8.GetString($memory.ToArray())
            try {
                return ($jsonText | ConvertFrom-Json)
            }
            finally {
                $jsonText = $null
            }
        }
        finally {
            $memory.Dispose()
        }
    }
    catch {
        if ((Convert-ExceptionToSafeCode -Exception $_.Exception) -ne 'UNEXPECTED_FAILURE') {
            throw
        }
        Stop-WithSafeCode 'PRODUCT_API_REQUEST_FAILED'
    }
}

function Wait-HttpHealth {
    param([Parameter(Mandatory = $true)][string] $BaseUri)
    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Method GET -Uri ($BaseUri + '/actuator/health') -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
            if ([int] $response.StatusCode -eq 200) {
                return
            }
        }
        catch {
        }
        Start-Sleep -Milliseconds 500
    }
    Stop-WithSafeCode 'BACKEND_HEALTH_TIMEOUT'
}

function Get-ComposeContainerCount {
    param(
        [Parameter(Mandatory = $true)][string] $DockerPath,
        [Parameter(Mandatory = $true)][string] $ProjectName
    )
    $result = Invoke-NativeResult -FilePath $DockerPath -ArgumentList @(
        'ps', '-a', '--filter', ('label=com.docker.compose.project=' + $ProjectName), '--format', '{{.ID}}'
    )
    if ($result.ExitCode -ne 0) {
        Stop-WithSafeCode 'DOCKER_METADATA_FAILED'
    }
    if ([string]::IsNullOrWhiteSpace($result.Text)) { return 0 }
    return @($result.Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
}

function Get-ComposeResourceCount {
    param(
        [Parameter(Mandatory = $true)][string] $DockerPath,
        [Parameter(Mandatory = $true)][ValidateSet('network', 'volume')][string] $Kind,
        [Parameter(Mandatory = $true)][string] $ProjectName
    )
    $result = Invoke-NativeResult -FilePath $DockerPath -ArgumentList @(
        $Kind, 'ls', '--filter', ('label=com.docker.compose.project=' + $ProjectName), '--format', '{{.Name}}'
    )
    if ($result.ExitCode -ne 0) {
        Stop-WithSafeCode 'DOCKER_METADATA_FAILED'
    }
    if ([string]::IsNullOrWhiteSpace($result.Text)) { return 0 }
    return @($result.Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)][string] $DockerPath,
        [Parameter(Mandatory = $true)][string] $ComposePath,
        [Parameter(Mandatory = $true)][string] $ProjectName,
        [Parameter(Mandatory = $true)][string] $EnvFile,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $FailureCode
    )
    if ($ProjectName -notmatch '^pm-ai-product-smoke-[fl]-[0-9a-f]{12}$') {
        Stop-WithSafeCode 'COMPOSE_PROJECT_NAME_INVALID'
    }
    return Invoke-NativeChecked -FilePath $DockerPath -ArgumentList (@(
        'compose', '--project-name', $ProjectName, '--file', $ComposePath, '--env-file', $EnvFile
    ) + $Arguments) -FailureCode $FailureCode
}

function Write-ArmEnvironment {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Password,
        [Parameter(Mandatory = $true)][string] $ImageTag,
        [Parameter(Mandatory = $true)][int] $BackendPort,
        [Parameter(Mandatory = $true)][ValidateSet('UNCERTAINTY_ONLY', 'AI_PREFERRED')][string] $InvocationMode
    )
    $isLiquid = $InvocationMode -eq 'AI_PREFERRED'
    $localModelEnabled = if ($isLiquid) { 'true' } else { 'false' }
    $approvedCorrectionsEnabled = if ($isLiquid) { 'true' } else { 'false' }
    $localModelName = if ($isLiquid) { $script:ExpectedModelTag } else { '' }
    $localModelDigest = if ($isLiquid) { $script:ExpectedModelDigest } else { '' }
    $lines = @(
        ('AI_PRODUCT_SMOKE_DATABASE_PASSWORD=' + $Password)
        ('AI_PRODUCT_SMOKE_BACKEND_IMAGE=' + $ImageTag)
        ('AI_PRODUCT_SMOKE_BACKEND_PORT=' + $BackendPort)
        ('AI_PRODUCT_SMOKE_LOCAL_MODEL_ENABLED=' + $localModelEnabled)
        ('AI_PRODUCT_SMOKE_INVOCATION_MODE=' + $InvocationMode)
        ('AI_PRODUCT_SMOKE_APPROVED_CORRECTIONS_ENABLED=' + $approvedCorrectionsEnabled)
        ('AI_PRODUCT_SMOKE_LOCAL_MODEL_NAME=' + $localModelName)
        ('AI_PRODUCT_SMOKE_LOCAL_MODEL_DIGEST=' + $localModelDigest)
    )
    Write-Utf8NoBom -Path $Path -Value (($lines -join "`r`n") + "`r`n")
}

function Invoke-DatabaseText {
    param(
        [Parameter(Mandatory = $true)][string] $DockerPath,
        [Parameter(Mandatory = $true)][string] $ComposePath,
        [Parameter(Mandatory = $true)][string] $ProjectName,
        [Parameter(Mandatory = $true)][string] $EnvFile,
        [Parameter(Mandatory = $true)][string] $Sql
    )
    return Invoke-Compose -DockerPath $DockerPath -ComposePath $ComposePath -ProjectName $ProjectName -EnvFile $EnvFile -Arguments @(
        'exec', '-T', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'personal_memo_ai_smoke',
        '-d', 'personal_memo_ai_smoke', '-At', '-F', '|', '-c', $Sql
    ) -FailureCode 'SYNTHETIC_DATABASE_QUERY_FAILED'
}

function Get-CanonicalCounts {
    param(
        [Parameter(Mandatory = $true)][string] $DockerPath,
        [Parameter(Mandatory = $true)][string] $ComposePath,
        [Parameter(Mandatory = $true)][string] $ProjectName,
        [Parameter(Mandatory = $true)][string] $EnvFile,
        [Parameter(Mandatory = $true)][string] $OwnerId
    )
    if ($OwnerId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
        Stop-WithSafeCode 'SYNTHETIC_OWNER_ID_INVALID'
    }
    $sql = @"
select
  (select count(*) from analysis_applications where owner_id = '$OwnerId'),
  (select count(*) from memo_items where owner_id = '$OwnerId'),
  (select count(*) from task_details where owner_id = '$OwnerId'),
  (select count(*) from event_details where owner_id = '$OwnerId'),
  (select count(*) from item_tags where owner_id = '$OwnerId'),
  (select count(*) from memo_item_relations where owner_id = '$OwnerId'),
  (select count(*) from tags where owner_id = '$OwnerId'),
  (select count(*) from tag_aliases where owner_id = '$OwnerId'),
  (select count(*) from calendar_feeds where owner_id = '$OwnerId'),
  (select count(*) from calendar_feed_entries where owner_id = '$OwnerId');
"@
    $text = Invoke-DatabaseText -DockerPath $DockerPath -ComposePath $ComposePath -ProjectName $ProjectName -EnvFile $EnvFile -Sql $sql
    $parts = @($text.Trim() -split '\|')
    if ($parts.Count -ne 10) {
        Stop-WithSafeCode 'CANONICAL_COUNT_SHAPE_INVALID'
    }
    return [ordered]@{
        applications = [int] $parts[0]
        memoItems = [int] $parts[1]
        taskDetails = [int] $parts[2]
        eventDetails = [int] $parts[3]
        itemTags = [int] $parts[4]
        relations = [int] $parts[5]
        tags = [int] $parts[6]
        tagAliases = [int] $parts[7]
        calendarFeeds = [int] $parts[8]
        calendarFeedEntries = [int] $parts[9]
    }
}

function Get-ZeroCanonicalDelta {
    param(
        [Parameter(Mandatory = $true)] $Before,
        [Parameter(Mandatory = $true)] $After
    )
    $delta = [ordered]@{}
    foreach ($name in @('applications', 'memoItems', 'taskDetails', 'eventDetails', 'itemTags', 'relations', 'tags', 'tagAliases', 'calendarFeeds', 'calendarFeedEntries')) {
        $value = [int] $After[$name] - [int] $Before[$name]
        if ($value -ne 0) {
            Stop-WithSafeCode 'CANONICAL_WRITE_DETECTED'
        }
        $delta[$name] = 0
    }
    return $delta
}

function Assert-RequiredProperties {
    param(
        [Parameter(Mandatory = $true)] $Value,
        [Parameter(Mandatory = $true)][string[]] $Names,
        [Parameter(Mandatory = $true)][string] $FailureCode
    )
    $available = @($Value.PSObject.Properties.Name)
    foreach ($name in $Names) {
        if ($available -notcontains $name) {
            Stop-WithSafeCode $FailureCode
        }
    }
}

function Test-ExactMemoSubstring {
    param([string] $Content, $Value)
    if ($null -eq $Value) { return $true }
    $text = [string] $Value
    if ([string]::IsNullOrEmpty($text)) { return $false }
    return $Content.IndexOf($text, [StringComparison]::Ordinal) -ge 0
}

function Assert-ProposalSafety {
    param(
        [Parameter(Mandatory = $true)] $Case,
        [Parameter(Mandatory = $true)] $Proposal,
        [Parameter(Mandatory = $true)][ValidateSet('Fake', 'Liquid')][string] $Arm
    )
    Assert-RequiredProperties -Value $Proposal -Names @(
        'schemaVersion', 'memoId', 'memoRevision', 'suggestedTitle', 'typeCandidates', 'dateCandidates',
        'tagCandidates', 'itemCandidates', 'relationCandidates', 'ambiguityReasons', 'providerMetadata'
    ) -FailureCode 'PROPOSAL_CONTRACT_INVALID'
    if ([string] $Proposal.schemaVersion -ne '2' -or [int] $Proposal.memoRevision -ne 1) {
        Stop-WithSafeCode 'PROPOSAL_CONTRACT_INVALID'
    }
    $types = @($Proposal.typeCandidates)
    $items = @($Proposal.itemCandidates)
    $dates = @($Proposal.dateCandidates)
    if ($types.Count -lt 1 -or $types.Count -gt 5 -or $items.Count -gt 3 -or $dates.Count -gt 5) {
        Stop-WithSafeCode 'PROPOSAL_DOMAIN_INVALID'
    }
    foreach ($item in $items) {
        Assert-RequiredProperties -Value $item -Names @('candidateId', 'kind', 'title', 'sourceSpan', 'action', 'object', 'confidence', 'dueDateCandidateId') -FailureCode 'PROPOSAL_DOMAIN_INVALID'
        if (-not (Test-ExactMemoSubstring -Content ([string] $Case.content) -Value $item.action) -or
            -not (Test-ExactMemoSubstring -Content ([string] $Case.content) -Value $item.object)) {
            Stop-WithSafeCode 'UNRESOLVED_HALLUCINATION_DETECTED'
        }
    }
    foreach ($date in $dates) {
        Assert-RequiredProperties -Value $date -Names @('candidateId', 'surfaceText', 'value', 'precision', 'timeSpecified', 'confidence', 'ambiguityReasons') -FailureCode 'PROPOSAL_DOMAIN_INVALID'
        if (-not (Test-ExactMemoSubstring -Content ([string] $Case.content) -Value $date.surfaceText)) {
            Stop-WithSafeCode 'UNRESOLVED_HALLUCINATION_DETECTED'
        }
        if ($null -ne $date.value -or @('EXACT_TIME', 'DATE_ONLY', 'RELATIVE_EXACT') -contains [string] $date.precision) {
            Stop-WithSafeCode 'INVENTED_PRECISE_DATE_DETECTED'
        }
    }

    if ([string] $Case.expectation -eq 'AFFIRMATIVE_TASK_UNKNOWN_TIME') {
        $parts = @(([string] $Case.content) -split ' ')
        if ($parts.Count -ne 3) {
            Stop-WithSafeCode 'FIXTURE_CASE_SHAPE_INVALID'
        }
        $taskItems = @($items | Where-Object { [string] $_.kind -eq 'TASK' })
        $timeDates = @($dates | Where-Object { [string] $_.surfaceText -ceq $parts[0] })
        if ([string] $types[0].value -ne 'TASK' -or $taskItems.Count -ne 1 -or $timeDates.Count -ne 1) {
            Stop-WithSafeCode 'AFFIRMATIVE_TASK_EXPECTATION_FAILED'
        }
        $task = $taskItems[0]
        $expectedTitle = $parts[1] + ' ' + $parts[2]
        if ([string] $task.title -cne $expectedTitle -or [string] $task.action -cne $parts[2] -or
            [string] $task.object -cne $parts[1] -or $null -ne $task.dueDateCandidateId -or
            $null -ne $timeDates[0].value -or [string] $timeDates[0].precision -ne 'UNKNOWN' -or
            [bool] $timeDates[0].timeSpecified) {
            Stop-WithSafeCode 'AFFIRMATIVE_TASK_EXPECTATION_FAILED'
        }
    }
    else {
        $actionableItems = @($items | Where-Object { [string] $_.kind -in @('TASK', 'EVENT') })
        $populatedNonTaskFields = @($items | Where-Object {
            $null -ne $_.action -or $null -ne $_.object -or $null -ne $_.dueDateCandidateId
        })
        if ([string] $types[0].value -ne 'UNKNOWN' -or $actionableItems.Count -ne 0 -or
            $dates.Count -ne 0 -or $populatedNonTaskFields.Count -ne 0) {
            Stop-WithSafeCode 'NON_TASK_EXPECTATION_FAILED'
        }
    }

    $metadata = $Proposal.providerMetadata
    Assert-RequiredProperties -Value $metadata -Names @(
        'toolCalls', 'cloudTransferMode', 'cloudGatewayVersion', 'cloudProviderId', 'cloudModelVersion',
        'cloudOutcome', 'cloudToolCalls', 'cloudMutationCalls'
    ) -FailureCode 'PROVIDER_METADATA_INVALID'
    if ([int] $metadata.toolCalls -ne 0 -or [int] $metadata.cloudToolCalls -ne 0 -or [int] $metadata.cloudMutationCalls -ne 0) {
        Stop-WithSafeCode 'SIDE_EFFECT_COUNTER_NONZERO'
    }
    if ($Arm -eq 'Fake') {
        if ([string] $metadata.cloudTransferMode -ne 'NO_NETWORK' -or
            [string] $metadata.cloudGatewayVersion -ne 'fake-cloud-v2' -or
            [string] $metadata.cloudProviderId -ne 'fake' -or
            [string] $metadata.cloudModelVersion -ne 'none' -or
            [string] $metadata.cloudOutcome -ne 'SUCCESS') {
            Stop-WithSafeCode 'FAKE_PROVIDER_EVIDENCE_INVALID'
        }
    }
    else {
        if ([string] $metadata.cloudTransferMode -ne 'LOCAL_MACHINE_MEMO_CONTENT' -or
            [string] $metadata.cloudGatewayVersion -ne $script:ExpectedGateway -or
            [string] $metadata.cloudProviderId -ne ('ollama-local@' + $script:ExpectedModelTag) -or
            [string] $metadata.cloudModelVersion -ne $script:ExpectedModelDigest) {
            Stop-WithSafeCode 'LIQUID_PROVIDER_EVIDENCE_INVALID'
        }
        if ([string] $Case.expectation -eq 'AFFIRMATIVE_TASK_UNKNOWN_TIME' -and [string] $metadata.cloudOutcome -ne 'SUCCESS') {
            Stop-WithSafeCode 'LIQUID_AFFIRMATIVE_MODEL_CALL_FAILED'
        }
    }
}

function Get-LatencyStats {
    param(
        [Parameter(Mandatory = $true)][long[]] $Values,
        [int] $MinimumCount = 3,
        [int] $MaximumCount = 3
    )
    if ($Values.Count -lt $MinimumCount -or $Values.Count -gt $MaximumCount) {
        Stop-WithSafeCode 'LATENCY_SAMPLE_COUNT_INVALID'
    }
    $sorted = @($Values | Sort-Object)
    foreach ($value in $sorted) {
        if ($value -lt 0 -or $value -gt 60000) {
            Stop-WithSafeCode 'LATENCY_OUT_OF_RANGE'
        }
    }
    $middle = [int] [Math]::Floor($sorted.Count / 2)
    $median = 0
    if (($sorted.Count % 2) -eq 1) {
        $median = [int] $sorted[$middle]
    }
    else {
        $median = [int] [Math]::Round((([double] $sorted[$middle - 1] + [double] $sorted[$middle]) / 2.0), 0, [MidpointRounding]::AwayFromZero)
    }
    return [ordered]@{
        min = [int] $sorted[0]
        median = $median
        max = [int] $sorted[$sorted.Count - 1]
        mean = [int] [Math]::Round((($sorted | Measure-Object -Average).Average), 0, [MidpointRounding]::AwayFromZero)
    }
}

function Get-ArmDatabaseEvidence {
    param(
        [Parameter(Mandatory = $true)][string] $DockerPath,
        [Parameter(Mandatory = $true)][string] $ComposePath,
        [Parameter(Mandatory = $true)][string] $ProjectName,
        [Parameter(Mandatory = $true)][string] $EnvFile,
        [Parameter(Mandatory = $true)][string] $OwnerId,
        [Parameter(Mandatory = $true)][ValidateSet('Fake', 'Liquid')][string] $Arm
    )
    $runSql = @"
select r.route, r.status, r.schema_version, r.cloud_transfer_mode, r.cloud_gateway_version,
       r.cloud_provider_id, r.cloud_model_version, r.cloud_outcome,
       d.invocation_mode, d.model_contribution_status, d.state
  from analysis_runs r
  join analysis_run_dispatches d on d.analysis_run_id = r.id and d.owner_id = r.owner_id
 where r.owner_id = '$OwnerId'
 order by r.created_at;
"@
    $runText = Invoke-DatabaseText -DockerPath $DockerPath -ComposePath $ComposePath -ProjectName $ProjectName -EnvFile $EnvFile -Sql $runSql
    $runLines = @($runText -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($runLines.Count -ne 3) {
        Stop-WithSafeCode 'DURABLE_EVIDENCE_COUNT_INVALID'
    }
    $cloudSuccess = 0
    $changed = 0
    $unchanged = 0
    $fallback = 0
    foreach ($line in $runLines) {
        $p = @($line -split '\|', 11)
        if ($p.Count -ne 11 -or $p[0] -ne 'HYBRID' -or $p[1] -ne 'REVIEW_REQUIRED' -or
            $p[2] -ne '2' -or $p[10] -ne 'FINALIZED') {
            Stop-WithSafeCode 'DURABLE_EVIDENCE_INVALID'
        }
        if ($p[7] -eq 'SUCCESS') { $cloudSuccess++ }
        switch ($p[9]) {
            'ACCEPTED_CHANGED' { $changed++ }
            'ACCEPTED_UNCHANGED' { $unchanged++ }
            'LOCAL_FALLBACK' { $fallback++ }
            default { Stop-WithSafeCode 'MODEL_CONTRIBUTION_INVALID' }
        }
        if ($Arm -eq 'Fake') {
            if ($p[3] -ne 'NO_NETWORK' -or $p[4] -ne 'fake-cloud-v2' -or $p[5] -ne 'fake' -or
                $p[6] -ne 'none' -or $p[7] -ne 'SUCCESS' -or $p[8] -ne 'UNCERTAINTY_ONLY' -or
                $p[9] -notin @('ACCEPTED_CHANGED', 'ACCEPTED_UNCHANGED', 'LOCAL_FALLBACK')) {
                Stop-WithSafeCode 'FAKE_DURABLE_EVIDENCE_INVALID'
            }
        }
        else {
            if ($p[3] -ne 'LOCAL_MACHINE_MEMO_CONTENT' -or $p[4] -ne $script:ExpectedGateway -or
                $p[5] -ne ('ollama-local@' + $script:ExpectedModelTag) -or $p[6] -ne $script:ExpectedModelDigest -or
                $p[8] -ne 'AI_PREFERRED' -or $p[9] -notin @('ACCEPTED_CHANGED', 'ACCEPTED_UNCHANGED', 'LOCAL_FALLBACK')) {
                Stop-WithSafeCode 'LIQUID_DURABLE_EVIDENCE_INVALID'
            }
        }
    }
    $attemptSql = @"
select a.duration_status, coalesce(a.duration_ms::text, ''), a.model_token_status, a.cost_status
  from analysis_run_dispatch_attempts a
  join analysis_runs r on r.id = a.analysis_run_id and r.owner_id = a.owner_id
 where r.owner_id = '$OwnerId'
 order by r.created_at, a.fence_token;
"@
    $attemptText = Invoke-DatabaseText -DockerPath $DockerPath -ComposePath $ComposePath -ProjectName $ProjectName -EnvFile $EnvFile -Sql $attemptSql
    $attemptLines = @($attemptText -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($attemptLines.Count -lt 3 -or $attemptLines.Count -gt 6) {
        Stop-WithSafeCode 'ATTEMPT_EVIDENCE_COUNT_INVALID'
    }
    $durations = New-Object 'System.Collections.Generic.List[long]'
    foreach ($line in $attemptLines) {
        $p = @($line -split '\|', 4)
        if ($p.Count -ne 4 -or $p[0] -ne 'MEASURED' -or
            ($Arm -eq 'Fake' -and ($p[2] -ne 'NOT_APPLICABLE' -or $p[3] -ne 'NOT_APPLICABLE')) -or
            ($Arm -eq 'Liquid' -and ($p[2] -ne 'NOT_REPORTED' -or $p[3] -ne 'NOT_REPORTED'))) {
            Stop-WithSafeCode 'ATTEMPT_EVIDENCE_INVALID'
        }
        $duration = 0L
        if (-not [long]::TryParse($p[1], [ref] $duration) -or $duration -lt 0 -or $duration -gt 60000) {
            Stop-WithSafeCode 'ATTEMPT_LATENCY_INVALID'
        }
        $durations.Add($duration)
    }
    return [pscustomobject]@{
        CloudSuccessCount = $cloudSuccess
        AcceptedChangedCount = $changed
        AcceptedUnchangedCount = $unchanged
        LocalFallbackCount = $fallback
        AttemptLatency = Get-LatencyStats -Values $durations.ToArray() -MinimumCount 3 -MaximumCount 6
    }
}

function Invoke-ArmProductFlow {
    param(
        [Parameter(Mandatory = $true)][string] $DockerPath,
        [Parameter(Mandatory = $true)][string] $ComposePath,
        [Parameter(Mandatory = $true)][string] $ProjectName,
        [Parameter(Mandatory = $true)][string] $EnvFile,
        [Parameter(Mandatory = $true)][int] $BackendPort,
        [Parameter(Mandatory = $true)] $Fixture,
        [Parameter(Mandatory = $true)][ValidateSet('Fake', 'Liquid')][string] $Arm
    )
    $baseUri = 'http://127.0.0.1:' + $BackendPort
    Wait-HttpHealth -BaseUri $baseUri
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $anonymousCsrf = Invoke-JsonRequest -Method GET -Uri ($baseUri + '/api/v1/auth/csrf') -WebSession $session
    Assert-RequiredProperties -Value $anonymousCsrf -Names @('headerName', 'token') -FailureCode 'CSRF_CONTRACT_INVALID'
    $registerHeaders = @{}
    $registerHeaders[[string] $anonymousCsrf.headerName] = [string] $anonymousCsrf.token
    $registration = Invoke-JsonRequest -Method POST -Uri ($baseUri + '/api/v1/auth/register') -WebSession $session -Headers $registerHeaders -ExpectedStatus 201 -Body ([ordered]@{
        email = ('smoke-' + $Arm.ToLowerInvariant() + '-' + $ProjectName.Substring($ProjectName.Length - 12) + '@example.test')
        password = ('Synthetic-' + $ProjectName.Substring($ProjectName.Length - 12) + '-Only!')
        displayName = 'Synthetic Smoke'
        timeZone = [string] $Fixture.timeZone
    })
    Assert-RequiredProperties -Value $registration -Names @('userId') -FailureCode 'REGISTRATION_CONTRACT_INVALID'
    $ownerId = ([Guid]::Parse([string] $registration.userId)).ToString('D')
    $authenticatedCsrf = Invoke-JsonRequest -Method GET -Uri ($baseUri + '/api/v1/auth/csrf') -WebSession $session
    $commonHeaders = @{
        'X-Expected-Owner-Id' = $ownerId
    }
    $commonHeaders[[string] $authenticatedCsrf.headerName] = [string] $authenticatedCsrf.token

    $canonicalBefore = Get-CanonicalCounts -DockerPath $DockerPath -ComposePath $ComposePath -ProjectName $ProjectName -EnvFile $EnvFile -OwnerId $ownerId
    $wallValues = New-Object 'System.Collections.Generic.List[long]'
    $reviewRequired = 0
    $schemaDomainAccepted = 0
    $caseNumber = 0
    foreach ($case in @($Fixture.cases)) {
        $caseNumber++
        $memoId = [Guid]::NewGuid().ToString('D')
        $stopwatch = [Diagnostics.Stopwatch]::StartNew()
        $memoHeaders = @{} + $commonHeaders
        $memoHeaders['Idempotency-Key'] = 'smoke-memo-' + $Arm.ToLowerInvariant() + '-' + $caseNumber + '-' + $memoId
        $memo = Invoke-JsonRequest -Method POST -Uri ($baseUri + '/api/v1/memos') -WebSession $session -Headers $memoHeaders -ExpectedStatus 201 -Body ([ordered]@{
            id = $memoId
            content = [string] $case.content
            clientCreatedAt = [string] $Fixture.clientRecordedAt
            timeZone = [string] $Fixture.timeZone
        })
        if ([string] $memo.id -ne $memoId -or [int] $memo.currentRevision -ne 1) {
            Stop-WithSafeCode 'MEMO_CREATE_CONTRACT_INVALID'
        }
        $analysisHeaders = @{} + $commonHeaders
        $analysisHeaders['Idempotency-Key'] = 'smoke-analysis-' + $Arm.ToLowerInvariant() + '-' + $caseNumber + '-' + $memoId
        $run = Invoke-JsonRequest -Method POST -Uri ($baseUri + '/api/v1/memos/' + $memoId + '/analysis-runs') -WebSession $session -Headers $analysisHeaders -Body ([ordered]@{
            memoRevision = 1
            policy = 'AUTO'
        })
        Assert-RequiredProperties -Value $run -Names @('id', 'memoId', 'memoRevision', 'status', 'proposalId') -FailureCode 'ANALYSIS_RUN_CONTRACT_INVALID'
        if ([string] $run.memoId -ne $memoId -or [int] $run.memoRevision -ne 1 -or [string] $run.status -ne 'REVIEW_REQUIRED') {
            Stop-WithSafeCode 'ANALYSIS_RUN_CONTRACT_INVALID'
        }
        $proposalHeaders = @{
            'X-Expected-Owner-Id' = $ownerId
            'X-Analysis-Proposal-Schema-Version' = '2'
        }
        $proposal = Invoke-JsonRequest -Method GET -Uri ($baseUri + '/api/v1/analysis-proposals/' + [string] $run.proposalId) -WebSession $session -Headers $proposalHeaders
        $stopwatch.Stop()
        if ($stopwatch.ElapsedMilliseconds -gt 60000) {
            Stop-WithSafeCode 'PRODUCT_WALL_LATENCY_OUT_OF_RANGE'
        }
        $wallValues.Add([long] $stopwatch.ElapsedMilliseconds)
        Assert-ProposalSafety -Case $case -Proposal $proposal -Arm $Arm
        if ([string] $proposal.memoId -ne $memoId) {
            Stop-WithSafeCode 'PROPOSAL_MEMO_BINDING_INVALID'
        }
        $reviewRequired++
        $schemaDomainAccepted++
    }
    if ($caseNumber -ne 3 -or $reviewRequired -ne 3 -or $schemaDomainAccepted -ne 3) {
        Stop-WithSafeCode 'PRODUCT_CASE_COUNT_INVALID'
    }
    $databaseEvidence = Get-ArmDatabaseEvidence -DockerPath $DockerPath -ComposePath $ComposePath -ProjectName $ProjectName -EnvFile $EnvFile -OwnerId $ownerId -Arm $Arm
    $canonicalAfter = Get-CanonicalCounts -DockerPath $DockerPath -ComposePath $ComposePath -ProjectName $ProjectName -EnvFile $EnvFile -OwnerId $ownerId
    $canonicalDelta = Get-ZeroCanonicalDelta -Before $canonicalBefore -After $canonicalAfter
    return [ordered]@{
        invocationMode = $(if ($Arm -eq 'Fake') { 'UNCERTAINTY_ONLY' } else { 'AI_PREFERRED' })
        transferMode = $(if ($Arm -eq 'Fake') { 'NO_NETWORK' } else { 'LOCAL_MACHINE_MEMO_CONTENT' })
        caseCount = 3
        reviewRequiredCount = 3
        schemaDomainAcceptedCount = 3
        cloudSuccessCount = [int] $databaseEvidence.CloudSuccessCount
        acceptedChangedCount = [int] $databaseEvidence.AcceptedChangedCount
        acceptedUnchangedCount = [int] $databaseEvidence.AcceptedUnchangedCount
        localFallbackCount = [int] $databaseEvidence.LocalFallbackCount
        toolCallCount = 0
        mutationCallCount = 0
        automaticApplyRequestCount = 0
        wallLatencyMilliseconds = Get-LatencyStats -Values $wallValues.ToArray()
        attemptLatencyMilliseconds = $databaseEvidence.AttemptLatency
        safety = [ordered]@{
            affirmativeTaskPassCount = 1
            negativeTaskPromotionCount = 0
            inventedPreciseDateCount = 0
            unresolvedHallucinationCount = 0
        }
        canonicalWriteDelta = $canonicalDelta
        modelTokenEvidence = $(if ($Arm -eq 'Fake') { 'NOT_APPLICABLE_ONLY' } else { 'NOT_REPORTED_OR_FALLBACK' })
    }
}

function Wait-OllamaApi {
    param([Parameter(Mandatory = $true)][string] $Endpoint)
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Method GET -Uri ($Endpoint + '/api/version') -TimeoutSec 3 -ErrorAction Stop
            if ([string] $response.version -eq $script:ExpectedOllamaVersion) {
                return
            }
            Stop-WithSafeCode 'OLLAMA_VERSION_INVALID'
        }
        catch {
            if ((Convert-ExceptionToSafeCode -Exception $_.Exception) -eq 'OLLAMA_VERSION_INVALID') {
                throw
            }
        }
        Start-Sleep -Milliseconds 400
    }
    Stop-WithSafeCode 'OWNED_OLLAMA_START_TIMEOUT'
}

function Get-OllamaModels {
    param(
        [Parameter(Mandatory = $true)][string] $Endpoint,
        [Parameter(Mandatory = $true)][ValidateSet('tags', 'ps')][string] $Kind
    )
    try {
        $response = Invoke-RestMethod -Method GET -Uri ($Endpoint + '/api/' + $Kind) -TimeoutSec 5 -ErrorAction Stop
        if (@($response.PSObject.Properties.Name) -notcontains 'models') {
            Stop-WithSafeCode 'OLLAMA_RESPONSE_INVALID'
        }
        return @($response.models)
    }
    catch {
        if ((Convert-ExceptionToSafeCode -Exception $_.Exception) -ne 'UNEXPECTED_FAILURE') { throw }
        Stop-WithSafeCode 'OLLAMA_REQUEST_FAILED'
    }
}

function Assert-ExactInstalledModel {
    param([Parameter(Mandatory = $true)][string] $Endpoint)
    $matches = @()
    foreach ($model in @(Get-OllamaModels -Endpoint $Endpoint -Kind tags)) {
        $nameMatch = ([string] $model.name -ceq $script:ExpectedModelTag)
        $modelMatch = ([string] $model.model -ceq $script:ExpectedModelTag)
        if ($nameMatch -or $modelMatch) {
            if (($null -ne $model.name -and -not $nameMatch) -or ($null -ne $model.model -and -not $modelMatch) -or
                [string] $model.digest -cne $script:ExpectedModelDigest) {
                Stop-WithSafeCode 'MODEL_IDENTITY_INVALID'
            }
            $matches += $model
        }
    }
    if ($matches.Count -ne 1) {
        Stop-WithSafeCode 'EXACT_MODEL_NOT_INSTALLED_ON_OWNED_ENDPOINT'
    }
}

function Get-OwnedListenerCount {
    param([Parameter(Mandatory = $true)][int] $ProcessId)
    try {
        $connections = @(Get-NetTCPConnection -State Listen -LocalPort $script:ExpectedOllamaPort -ErrorAction Stop)
    }
    catch {
        Stop-WithSafeCode 'LISTENER_VERIFICATION_FAILED'
    }
    foreach ($connection in $connections) {
        if ([string] $connection.LocalAddress -notin @('127.0.0.1', '::ffff:127.0.0.1') -or [int] $connection.OwningProcess -ne $ProcessId) {
            Stop-WithSafeCode 'OWNED_LISTENER_IDENTITY_INVALID'
        }
    }
    return $connections.Count
}

function Wait-OllamaUnloaded {
    param([Parameter(Mandatory = $true)][string] $Endpoint)
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (@(Get-OllamaModels -Endpoint $Endpoint -Kind ps).Count -eq 0) { return 0 }
        Start-Sleep -Milliseconds 300
    }
    Stop-WithSafeCode 'MODEL_UNLOAD_TIMEOUT'
}

function Assert-OwnedProcessIdentity {
    param(
        [Parameter(Mandatory = $true)] $Process,
        [Parameter(Mandatory = $true)][string] $ExecutablePath,
        [Parameter(Mandatory = $true)][DateTime] $StartedAfterUtc
    )
    $current = Get-Process -Id $Process.Id -ErrorAction SilentlyContinue
    if ($null -eq $current -or [IO.Path]::GetFullPath($current.Path) -cne [IO.Path]::GetFullPath($ExecutablePath) -or
        $current.StartTime.ToUniversalTime() -lt $StartedAfterUtc.AddSeconds(-2)) {
        Stop-WithSafeCode 'OWNED_PROCESS_IDENTITY_INVALID'
    }
}

function Start-OwnedOllama {
    param(
        [Parameter(Mandatory = $true)][string] $ExecutablePath,
        [Parameter(Mandatory = $true)][string] $Endpoint
    )
    $startedAfter = [DateTime]::UtcNow
    $process = $null
    $previousHost = [Environment]::GetEnvironmentVariable('OLLAMA_HOST', 'Process')
    $previousKeepAlive = [Environment]::GetEnvironmentVariable('OLLAMA_KEEP_ALIVE', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('OLLAMA_HOST', ('127.0.0.1:' + $script:ExpectedOllamaPort), 'Process')
        [Environment]::SetEnvironmentVariable('OLLAMA_KEEP_ALIVE', '0', 'Process')
        $process = Start-Process -FilePath $ExecutablePath -ArgumentList @('serve') -WindowStyle Hidden -PassThru
    }
    finally {
        [Environment]::SetEnvironmentVariable('OLLAMA_HOST', $previousHost, 'Process')
        [Environment]::SetEnvironmentVariable('OLLAMA_KEEP_ALIVE', $previousKeepAlive, 'Process')
    }
    try {
        Wait-OllamaApi -Endpoint $Endpoint
        Assert-OwnedProcessIdentity -Process $process -ExecutablePath $ExecutablePath -StartedAfterUtc $startedAfter
        if ((Get-OwnedListenerCount -ProcessId $process.Id) -lt 1) {
            Stop-WithSafeCode 'OWNED_LISTENER_MISSING'
        }
        Assert-ExactInstalledModel -Endpoint $Endpoint
        if (@(Get-OllamaModels -Endpoint $Endpoint -Kind ps).Count -ne 0) {
            Stop-WithSafeCode 'OWNED_ENDPOINT_NOT_COLD'
        }
        return [pscustomobject]@{
            Process = $process
            ExecutablePath = $ExecutablePath
            StartedAfterUtc = $startedAfter
        }
    }
    catch {
        if ($null -ne $process) {
            try {
                Assert-OwnedProcessIdentity -Process $process -ExecutablePath $ExecutablePath -StartedAfterUtc $startedAfter
                Stop-Process -Id $process.Id -Force -ErrorAction Stop
                $process.WaitForExit(10000) | Out-Null
            }
            catch {
                Stop-WithSafeCode 'OWNED_OLLAMA_START_CLEANUP_FAILED'
            }
        }
        throw
    }
}

function Start-GpuSampler {
    param(
        [Parameter(Mandatory = $true)][string] $PowerShellPath,
        [Parameter(Mandatory = $true)][string] $SamplerPath,
        [Parameter(Mandatory = $true)][string] $StopMarkerPath,
        [Parameter(Mandatory = $true)][string] $OutputPath,
        [Parameter(Mandatory = $true)][string] $Endpoint
    )
    return Start-Process -FilePath $PowerShellPath -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $SamplerPath,
        '-StopMarkerPath', $StopMarkerPath, '-OutputPath', $OutputPath, '-Endpoint', $Endpoint
    ) -WindowStyle Hidden -PassThru
}

function Stop-AndReadGpuSampler {
    param(
        [Parameter(Mandatory = $true)] $Process,
        [Parameter(Mandatory = $true)][string] $StopMarkerPath,
        [Parameter(Mandatory = $true)][string] $OutputPath
    )
    Write-Utf8NoBom -Path $StopMarkerPath -Value 'stop'
    if (-not $Process.WaitForExit(20000)) {
        Stop-WithSafeCode 'GPU_SAMPLER_STOP_TIMEOUT'
    }
    if ($Process.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $OutputPath -PathType Leaf)) {
        Stop-WithSafeCode 'GPU_SAMPLER_FAILED'
    }
    try {
        $sample = Get-Content -Raw -Encoding UTF8 -LiteralPath $OutputPath | ConvertFrom-Json
    }
    catch {
        Stop-WithSafeCode 'GPU_SAMPLER_OUTPUT_INVALID'
    }
    Assert-RequiredProperties -Value $sample -Names @(
        'sampleCount', 'sampleMissCount', 'baselineUsedMiB', 'maxUsedMiB', 'postUsedMiB',
        'maxUtilizationPercent', 'loadedModelObserved', 'maxOllamaVramBytes', 'contextLength'
    ) -FailureCode 'GPU_SAMPLER_OUTPUT_INVALID'
    if ([int] $sample.sampleCount -lt 1 -or -not [bool] $sample.loadedModelObserved -or
        [long] $sample.maxOllamaVramBytes -lt 1 -or [int] $sample.contextLength -ne $script:ExpectedContextLength) {
        Stop-WithSafeCode 'GPU_EVIDENCE_INSUFFICIENT'
    }
    return [ordered]@{
        scope = 'DEVICE_WIDE_NON_EXCLUSIVE'
        sampleCount = [int] $sample.sampleCount
        sampleMissCount = [int] $sample.sampleMissCount
        baselineUsedMiB = [int] $sample.baselineUsedMiB
        maxUsedMiB = [int] $sample.maxUsedMiB
        postUsedMiB = [int] $sample.postUsedMiB
        maxUtilizationPercent = [int] $sample.maxUtilizationPercent
        loadedModelObserved = $true
        maxOllamaVramBytes = [long] $sample.maxOllamaVramBytes
        contextLength = $script:ExpectedContextLength
    }
}

function Remove-ExactTempDirectory {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $ExpectedLeaf
    )
    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if ([IO.Path]::GetFileName($fullPath) -cne $ExpectedLeaf -or
        -not $fullPath.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Stop-WithSafeCode 'TEMP_PATH_BOUNDARY_INVALID'
    }
    if (Test-Path -LiteralPath $fullPath) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$composePath = Join-Path $repoRoot 'compose.ai-product-smoke.yaml'
$backendDockerfilePath = Join-Path $repoRoot 'backend\Dockerfile'
$fixturePath = Join-Path $repoRoot 'fixtures\ai-preferred-product-smoke-cases.json'
$fixtureSchemaPath = Join-Path $repoRoot 'contracts\ai-preferred-product-smoke-fixture.schema.json'
$receiptSchemaPath = Join-Path $repoRoot 'contracts\ai-preferred-product-smoke-receipt.schema.json'
$samplerPath = Join-Path $repoRoot 'scripts\analysis\Measure-PersonalMemoAiProductSmokeGpu.ps1'
$sourceContractPath = Join-Path $repoRoot 'scripts\analysis\Test-PersonalMemoAiProductSmokeSourceContracts.ps1'
$orchestratorPath = $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($ReceiptDirectory)) {
    $ReceiptDirectory = Join-Path $repoRoot 'backend\target\evaluation'
}
$ReceiptDirectory = [IO.Path]::GetFullPath($ReceiptDirectory)
$allowedReceiptRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'backend\target'))
if (-not $ReceiptDirectory.StartsWith($allowedReceiptRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    Stop-WithSafeCode 'RECEIPT_PATH_BOUNDARY_INVALID'
}

foreach ($requiredPath in @($composePath, $backendDockerfilePath, $fixturePath, $fixtureSchemaPath, $receiptSchemaPath, $samplerPath, $sourceContractPath, $orchestratorPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        Stop-WithSafeCode 'REQUIRED_SOURCE_ARTIFACT_MISSING'
    }
}
if ($OllamaPort -ne $script:ExpectedOllamaPort -or
    $FakeBackendPort -eq $LiquidBackendPort -or $FakeBackendPort -eq $OllamaPort -or $LiquidBackendPort -eq $OllamaPort) {
    Stop-WithSafeCode 'PORT_ASSIGNMENT_INVALID'
}
Assert-ExactLoopbackPortFree -Port $OllamaPort
Assert-ExactLoopbackPortFree -Port $FakeBackendPort
Assert-ExactLoopbackPortFree -Port $LiquidBackendPort

$dockerPath = Require-CommandPath -Name 'docker.exe'
$gitPath = Require-CommandPath -Name 'git.exe'
$ollamaPath = Require-CommandPath -Name 'ollama.exe'
$powerShellPath = Require-CommandPath -Name 'powershell.exe'
$null = Require-CommandPath -Name 'nvidia-smi.exe'
$sourcePreflight = Invoke-NativeResult -FilePath $powerShellPath -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $sourceContractPath
) -WorkingDirectory $repoRoot
if ($sourcePreflight.ExitCode -ne 0) {
    Stop-WithSafeCode 'SOURCE_CONTRACT_PREFLIGHT_FAILED'
}
$dockerInfo = Invoke-NativeResult -FilePath $dockerPath -ArgumentList @('info', '--format', '{{.ServerVersion}}')
if ($dockerInfo.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($dockerInfo.Text)) {
    Stop-WithSafeCode 'DOCKER_NOT_READY'
}

try {
    $fixture = Get-Content -Raw -Encoding UTF8 -LiteralPath $fixturePath | ConvertFrom-Json
}
catch {
    Stop-WithSafeCode 'FIXTURE_PARSE_FAILED'
}
Assert-RequiredProperties -Value $fixture -Names @('schemaVersion', 'fixtureId', 'dataClass', 'clientRecordedAt', 'timeZone', 'cases') -FailureCode 'FIXTURE_CONTRACT_INVALID'
if ([int] $fixture.schemaVersion -ne 1 -or [string] $fixture.fixtureId -ne 'ai-preferred-product-smoke-v1' -or
    [string] $fixture.dataClass -ne 'PUBLIC_SYNTHETIC_ONLY' -or @($fixture.cases).Count -ne 3) {
    Stop-WithSafeCode 'FIXTURE_CONTRACT_INVALID'
}

if (-not $PSCmdlet.ShouldProcess(
        'isolated tmpfs Compose arms and a dedicated loopback Ollama process',
        'Run aggregate-only Fake versus LiquidAI product qualification')) {
    return
}

$runToken = ([Guid]::NewGuid().ToString('N')).Substring(0, 12)
$tempLeaf = 'personal-memo-ai-product-smoke-' + $runToken
$tempPath = Join-Path ([IO.Path]::GetTempPath()) $tempLeaf
$fakeProject = 'pm-ai-product-smoke-f-' + $runToken
$liquidProject = 'pm-ai-product-smoke-l-' + $runToken
$imageTag = 'personal-memo-ai-product-smoke:' + $runToken
$fakeEnv = Join-Path $tempPath 'fake.env'
$liquidEnv = Join-Path $tempPath 'liquid.env'
$samplerStopMarker = Join-Path $tempPath 'sampler.stop'
$samplerOutput = Join-Path $tempPath 'gpu.json'
$ownedEndpoint = 'http://127.0.0.1:' + $script:ExpectedOllamaPort
$ownedOllama = $null
$samplerProcess = $null
$imageBuilt = $false
$fakeStarted = $false
$liquidStarted = $false
$runSucceeded = $false
$failureCode = $null
$cleanupFailed = $false
$cleanupFailureCode = $null
$fakeResult = $null
$liquidResult = $null
$gpuResult = $null
$postloadedModelCount = $null
$backendImageId = $null
$personalBefore = $null
$cleanupEvidence = $null
$runPhase = 'TEMP_PREPARE'

try {
    if (Test-Path -LiteralPath $tempPath) {
        Stop-WithSafeCode 'TEMP_PATH_COLLISION'
    }
    $null = New-Item -ItemType Directory -Path $tempPath
    $runPhase = 'PERSONAL_METADATA'
    $personalBefore = Get-ComposeContainerCount -DockerPath $dockerPath -ProjectName $script:PersonalComposeProject
    $passwordFake = ([Guid]::NewGuid().ToString('N')) + ([Guid]::NewGuid().ToString('N'))
    $passwordLiquid = ([Guid]::NewGuid().ToString('N')) + ([Guid]::NewGuid().ToString('N'))
    $runPhase = 'ARM_ENVIRONMENT'
    Write-ArmEnvironment -Path $fakeEnv -Password $passwordFake -ImageTag $imageTag -BackendPort $FakeBackendPort -InvocationMode UNCERTAINTY_ONLY
    Write-ArmEnvironment -Path $liquidEnv -Password $passwordLiquid -ImageTag $imageTag -BackendPort $LiquidBackendPort -InvocationMode AI_PREFERRED

    $runPhase = 'BACKEND_BUILD'
    $null = Invoke-NativeChecked -FilePath $dockerPath -ArgumentList @(
        'build', '--file', $backendDockerfilePath, '--tag', $imageTag, $repoRoot
    ) -WorkingDirectory $repoRoot -FailureCode 'BACKEND_IMAGE_BUILD_FAILED'
    $imageBuilt = $true
    $runPhase = 'BACKEND_IMAGE_ID'
    $backendImageId = Invoke-NativeChecked -FilePath $dockerPath -ArgumentList @('image', 'inspect', '--format', '{{.Id}}', $imageTag) -FailureCode 'BACKEND_IMAGE_ID_FAILED'
    if ($backendImageId -notmatch '^sha256:[0-9a-f]{64}$') {
        Stop-WithSafeCode 'BACKEND_IMAGE_ID_INVALID'
    }

    $runPhase = 'FAKE_ARM_START'
    $null = Invoke-Compose -DockerPath $dockerPath -ComposePath $composePath -ProjectName $fakeProject -EnvFile $fakeEnv -Arguments @('up', '-d', '--wait', '--no-build') -FailureCode 'FAKE_ARM_START_FAILED'
    $fakeStarted = $true
    $runPhase = 'FAKE_PRODUCT_FLOW'
    $fakeResult = Invoke-ArmProductFlow -DockerPath $dockerPath -ComposePath $composePath -ProjectName $fakeProject -EnvFile $fakeEnv -BackendPort $FakeBackendPort -Fixture $fixture -Arm Fake

    $runPhase = 'OWNED_OLLAMA_START'
    $ownedOllama = Start-OwnedOllama -ExecutablePath $ollamaPath -Endpoint $ownedEndpoint
    $runPhase = 'LIQUID_ARM_START'
    $null = Invoke-Compose -DockerPath $dockerPath -ComposePath $composePath -ProjectName $liquidProject -EnvFile $liquidEnv -Arguments @('up', '-d', '--wait', '--no-build') -FailureCode 'LIQUID_ARM_START_FAILED'
    $liquidStarted = $true
    Wait-HttpHealth -BaseUri ('http://127.0.0.1:' + $LiquidBackendPort)
    $runPhase = 'GPU_SAMPLER_START'
    $samplerProcess = Start-GpuSampler -PowerShellPath $powerShellPath -SamplerPath $samplerPath -StopMarkerPath $samplerStopMarker -OutputPath $samplerOutput -Endpoint $ownedEndpoint
    Start-Sleep -Milliseconds 500
    $runPhase = 'LIQUID_PRODUCT_FLOW'
    $liquidResult = Invoke-ArmProductFlow -DockerPath $dockerPath -ComposePath $composePath -ProjectName $liquidProject -EnvFile $liquidEnv -BackendPort $LiquidBackendPort -Fixture $fixture -Arm Liquid
    $runPhase = 'GPU_SAMPLER_STOP'
    $gpuResult = Stop-AndReadGpuSampler -Process $samplerProcess -StopMarkerPath $samplerStopMarker -OutputPath $samplerOutput
    $samplerProcess = $null
    $runPhase = 'MODEL_UNLOAD'
    $postloadedModelCount = Wait-OllamaUnloaded -Endpoint $ownedEndpoint
    $runSucceeded = $true
}
catch {
    $failureCode = Convert-ExceptionToSafeCode -Exception $_.Exception
    if ($failureCode -eq 'UNEXPECTED_FAILURE') {
        $failureCode = 'UNEXPECTED_' + $runPhase
    }
}
finally {
    if ($null -ne $samplerProcess) {
        try {
            if (-not (Test-Path -LiteralPath $samplerStopMarker)) {
                Write-Utf8NoBom -Path $samplerStopMarker -Value 'stop'
            }
            if (-not $samplerProcess.WaitForExit(10000)) {
                Stop-Process -Id $samplerProcess.Id -Force -ErrorAction Stop
            }
        }
        catch {
            $cleanupFailed = $true
            if ($null -eq $cleanupFailureCode) { $cleanupFailureCode = 'CLEANUP_SAMPLER_FAILED' }
        }
    }
    foreach ($arm in @(
        [pscustomobject]@{ Phase = 'LIQUID'; Project = $liquidProject; Env = $liquidEnv; Started = [bool] $liquidStarted },
        [pscustomobject]@{ Phase = 'FAKE'; Project = $fakeProject; Env = $fakeEnv; Started = [bool] $fakeStarted }
    )) {
        try {
            $shouldDown = [bool] $arm.Started
            if (-not $shouldDown) {
                $ownedResourceCount =
                    (Get-ComposeContainerCount -DockerPath $dockerPath -ProjectName $arm.Project) +
                    (Get-ComposeResourceCount -DockerPath $dockerPath -Kind network -ProjectName $arm.Project) +
                    (Get-ComposeResourceCount -DockerPath $dockerPath -Kind volume -ProjectName $arm.Project)
                $shouldDown = $ownedResourceCount -gt 0
            }
            if ($shouldDown -and (Test-Path -LiteralPath $arm.Env)) {
                $down = Invoke-NativeResult -FilePath $dockerPath -ArgumentList @(
                    'compose', '--project-name', $arm.Project, '--file', $composePath, '--env-file', $arm.Env,
                    'down', '--volumes', '--remove-orphans', '--timeout', '10'
                )
                if ($down.ExitCode -ne 0) {
                    $cleanupFailed = $true
                    if ($null -eq $cleanupFailureCode) { $cleanupFailureCode = 'CLEANUP_COMPOSE_' + $arm.Phase + '_FAILED' }
                }
            }
        }
        catch {
            $cleanupFailed = $true
            if ($null -eq $cleanupFailureCode) { $cleanupFailureCode = 'CLEANUP_COMPOSE_' + $arm.Phase + '_FAILED' }
        }
    }
    if ($null -ne $ownedOllama) {
        try {
            Assert-OwnedProcessIdentity -Process $ownedOllama.Process -ExecutablePath $ownedOllama.ExecutablePath -StartedAfterUtc $ownedOllama.StartedAfterUtc
            Stop-Process -Id $ownedOllama.Process.Id -Force -ErrorAction Stop
            $ownedOllama.Process.WaitForExit(10000) | Out-Null
        }
        catch {
            $cleanupFailed = $true
            if ($null -eq $cleanupFailureCode) { $cleanupFailureCode = 'CLEANUP_OLLAMA_FAILED' }
        }
    }
    if ($imageBuilt) {
        try {
            $imageRemoved = $false
            for ($removeAttempt = 0; $removeAttempt -lt 5; $removeAttempt++) {
                $removeImage = Invoke-NativeResult -FilePath $dockerPath -ArgumentList @('image', 'rm', '--force', $imageTag)
                if ($removeImage.ExitCode -eq 0) {
                    $imageRemoved = $true
                    break
                }
                Start-Sleep -Milliseconds 500
            }
            $imageProbe = Invoke-NativeResult -FilePath $dockerPath -ArgumentList @('image', 'inspect', $imageTag)
            if (-not $imageRemoved -or $imageProbe.ExitCode -eq 0) {
                $cleanupFailed = $true
                if ($null -eq $cleanupFailureCode) { $cleanupFailureCode = 'CLEANUP_IMAGE_FAILED' }
            }
        }
        catch {
            $cleanupFailed = $true
            if ($null -eq $cleanupFailureCode) { $cleanupFailureCode = 'CLEANUP_IMAGE_FAILED' }
        }
    }
    try {
        Remove-ExactTempDirectory -Path $tempPath -ExpectedLeaf $tempLeaf
    }
    catch {
        $cleanupFailed = $true
        if ($null -eq $cleanupFailureCode) { $cleanupFailureCode = 'CLEANUP_TEMP_FAILED' }
    }

    try {
        $personalAfter = Get-ComposeContainerCount -DockerPath $dockerPath -ProjectName $script:PersonalComposeProject
        $ownedProcessCount = 0
        if ($null -ne $ownedOllama -and $null -ne (Get-Process -Id $ownedOllama.Process.Id -ErrorAction SilentlyContinue)) {
            $ownedProcessCount = 1
        }
        $ownedListenerCount = @(Get-NetTCPConnection -State Listen -LocalPort $script:ExpectedOllamaPort -ErrorAction SilentlyContinue).Count
        $cleanupEvidence = [ordered]@{
            fakeProjectContainerCount = Get-ComposeContainerCount -DockerPath $dockerPath -ProjectName $fakeProject
            fakeProjectNetworkCount = Get-ComposeResourceCount -DockerPath $dockerPath -Kind network -ProjectName $fakeProject
            fakeProjectVolumeCount = Get-ComposeResourceCount -DockerPath $dockerPath -Kind volume -ProjectName $fakeProject
            liquidProjectContainerCount = Get-ComposeContainerCount -DockerPath $dockerPath -ProjectName $liquidProject
            liquidProjectNetworkCount = Get-ComposeResourceCount -DockerPath $dockerPath -Kind network -ProjectName $liquidProject
            liquidProjectVolumeCount = Get-ComposeResourceCount -DockerPath $dockerPath -Kind volume -ProjectName $liquidProject
            ownedOllamaProcessCount = $ownedProcessCount
            ownedOllamaListenerCount = $ownedListenerCount
            tempArtifactCount = $(if (Test-Path -LiteralPath $tempPath) { 1 } else { 0 })
            personalProjectContainerCountBefore = [int] $personalBefore
            personalProjectContainerCountAfter = [int] $personalAfter
            personalProjectContainerCountUnchanged = ([int] $personalBefore -eq [int] $personalAfter)
            defaultOllamaEndpointAccessed = $false
            restored = $false
        }
        $cleanupValues = @(
            $cleanupEvidence.fakeProjectContainerCount, $cleanupEvidence.fakeProjectNetworkCount, $cleanupEvidence.fakeProjectVolumeCount,
            $cleanupEvidence.liquidProjectContainerCount, $cleanupEvidence.liquidProjectNetworkCount, $cleanupEvidence.liquidProjectVolumeCount,
            $cleanupEvidence.ownedOllamaProcessCount, $cleanupEvidence.ownedOllamaListenerCount, $cleanupEvidence.tempArtifactCount
        )
        if (@($cleanupValues | Where-Object { [int] $_ -ne 0 }).Count -ne 0 -or -not $cleanupEvidence.personalProjectContainerCountUnchanged) {
            $cleanupFailed = $true
            if ($null -eq $cleanupFailureCode) { $cleanupFailureCode = 'CLEANUP_RESTORE_PROOF_FAILED' }
        }
        else {
            $cleanupEvidence.restored = $true
        }
    }
    catch {
        $cleanupFailed = $true
        if ($null -eq $cleanupFailureCode) { $cleanupFailureCode = 'CLEANUP_RESTORE_PROOF_FAILED' }
    }
}

if ($cleanupFailed) {
    if ([string]::IsNullOrWhiteSpace($cleanupFailureCode)) {
        $cleanupFailureCode = 'CLEANUP_OR_RESTORE_FAILED'
    }
    Stop-WithSafeCode $cleanupFailureCode
}
if (-not $runSucceeded) {
    if ([string]::IsNullOrWhiteSpace($failureCode)) { $failureCode = 'UNEXPECTED_FAILURE' }
    Stop-WithSafeCode $failureCode
}
if ($null -eq $fakeResult -or $null -eq $liquidResult -or $null -eq $gpuResult -or $postloadedModelCount -ne 0 -or $null -eq $cleanupEvidence) {
    Stop-WithSafeCode 'AGGREGATE_EVIDENCE_INCOMPLETE'
}

$gitSafeDirectory = 'safe.directory=' + $repoRoot
$gitCommit = Invoke-NativeChecked -FilePath $gitPath -ArgumentList @('-c', $gitSafeDirectory, 'rev-parse', 'HEAD') -WorkingDirectory $repoRoot -FailureCode 'GIT_COMMIT_FAILED'
if ($gitCommit -notmatch '^[0-9a-f]{40}$') {
    Stop-WithSafeCode 'GIT_COMMIT_INVALID'
}
$gitStatus = Invoke-NativeChecked -FilePath $gitPath -ArgumentList @('-c', $gitSafeDirectory, 'status', '--porcelain', '--untracked-files=normal') -WorkingDirectory $repoRoot -FailureCode 'GIT_STATUS_FAILED'
$fakeMedian = [int] $fakeResult.wallLatencyMilliseconds.median
$liquidMedian = [int] $liquidResult.wallLatencyMilliseconds.median
$ratio = 0.0
if ($fakeMedian -gt 0) {
    $ratio = [Math]::Round(([double] $liquidMedian / [double] $fakeMedian), 4, [MidpointRounding]::AwayFromZero)
}
$receipt = [ordered]@{
    schemaVersion = 1
    fixtureId = 'ai-preferred-product-smoke-v1'
    status = 'PASS_NARROW_PRODUCT_PATH'
    classification = 'SOLO_PROVISIONAL/REPORT_ONLY'
    decision = 'NO_GO'
    trainingDecision = 'NO_GO_FOR_TRAINING'
    loraDecision = 'NO_GO'
    ragStatus = 'NOT_USED'
    automaticApply = $false
    recordedAt = [DateTimeOffset]::UtcNow.ToString('o')
    scope = [ordered]@{
        dataClass = 'PUBLIC_SYNTHETIC_ONLY'
        personalMemoAccessed = $false
        personalPostgresAccessed = $false
        personalCanonicalDataAccessed = $false
        productApplyEndpointCalled = $false
        externalProductServiceAccessed = $false
        alarmReminderCalled = $false
    }
    source = [ordered]@{
        gitCommit = $gitCommit
        dirty = -not [string]::IsNullOrWhiteSpace($gitStatus)
        backendImageId = $backendImageId
        composeSha256 = Get-Sha256 -Path $composePath
        fixtureSha256 = Get-Sha256 -Path $fixturePath
        fixtureSchemaSha256 = Get-Sha256 -Path $fixtureSchemaPath
        receiptSchemaSha256 = Get-Sha256 -Path $receiptSchemaPath
        orchestratorSha256 = Get-Sha256 -Path $orchestratorPath
        samplerSha256 = Get-Sha256 -Path $samplerPath
    }
    model = [ordered]@{
        endpointClass = 'OWNED_LOOPBACK_127_0_0_1_11435'
        ollamaVersion = $script:ExpectedOllamaVersion
        tag = $script:ExpectedModelTag
        digest = $script:ExpectedModelDigest
        gateway = $script:ExpectedGateway
        contextLength = $script:ExpectedContextLength
        preloadedModelCount = 0
        postloadedModelCount = 0
    }
    fake = $fakeResult
    liquidAi = $liquidResult
    comparison = [ordered]@{
        pairedCaseCount = 3
        medianWallDeltaMilliseconds = [int] [Math]::Max(0, $liquidMedian - $fakeMedian)
        liquidToFakeMedianWallRatio = $ratio
        semanticImprovement = 'NOT_DEMONSTRATED'
    }
    gpu = $gpuResult
    cleanup = $cleanupEvidence
}

if (-not (Test-Path -LiteralPath $ReceiptDirectory)) {
    $null = New-Item -ItemType Directory -Path $ReceiptDirectory
}
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$finalReceiptPath = Join-Path $ReceiptDirectory ('ai-preferred-product-smoke-' + $timestamp + '.json')
$temporaryReceiptPath = $finalReceiptPath + '.tmp-' + $runToken
try {
    Write-Utf8NoBom -Path $temporaryReceiptPath -Value ((ConvertTo-CompactJson -Value $receipt) + "`n")
    $contractCheck = Invoke-NativeResult -FilePath $powerShellPath -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $sourceContractPath, '-ReceiptPath', $temporaryReceiptPath
    ) -WorkingDirectory $repoRoot
    if ($contractCheck.ExitCode -ne 0) {
        Stop-WithSafeCode 'RECEIPT_CONTRACT_VALIDATION_FAILED'
    }
    Move-Item -LiteralPath $temporaryReceiptPath -Destination $finalReceiptPath
}
catch {
    if (Test-Path -LiteralPath $temporaryReceiptPath) {
        Remove-Item -LiteralPath $temporaryReceiptPath -Force
    }
    if ((Convert-ExceptionToSafeCode -Exception $_.Exception) -ne 'UNEXPECTED_FAILURE') { throw }
    Stop-WithSafeCode 'RECEIPT_WRITE_FAILED'
}

$receiptHash = Get-Sha256 -Path $finalReceiptPath
Write-Output ('ReceiptPath=' + $finalReceiptPath)
Write-Output ('ReceiptSha256=' + $receiptHash)
