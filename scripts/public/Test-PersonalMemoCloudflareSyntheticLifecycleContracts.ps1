#Requires -Version 5.1

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$publicScripts = [IO.Path]::GetFullPath($PSScriptRoot)
$startPath = Join-Path $publicScripts 'Start-PersonalMemoCloudflareConnector.ps1'
$lifecyclePath = Join-Path $publicScripts 'Invoke-PersonalMemoCloudflareSyntheticQualification.ps1'

function Get-ParsedScript {
    param([Parameter(Mandatory = $true)][string] $Path)

    if (-not [IO.File]::Exists($Path)) {
        throw "Required synthetic lifecycle source is missing: $Path"
    }
    $tokens = $null
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        $Path,
        [ref] $tokens,
        [ref] $parseErrors
    )
    if ($parseErrors.Count -ne 0) {
        throw "Synthetic lifecycle source did not parse under Windows PowerShell syntax: $Path"
    }
    return $ast
}

function Get-ParameterAst {
    param(
        [Parameter(Mandatory = $true)] $ScriptAst,
        [Parameter(Mandatory = $true)][string] $Name
    )

    $matches = @(
        $ScriptAst.ParamBlock.Parameters |
            Where-Object { $_.Name.VariablePath.UserPath -ceq $Name }
    )
    if ($matches.Count -ne 1) {
        throw "Expected exactly one parameter named $Name."
    }
    return $matches[0]
}

function Get-AttributeNamedArgumentText {
    param(
        [Parameter(Mandatory = $true)] $Attribute,
        [Parameter(Mandatory = $true)][string] $Name
    )

    $matches = @(
        $Attribute.NamedArguments |
            Where-Object { $_.ArgumentName -ceq $Name }
    )
    if ($matches.Count -eq 0) {
        return $null
    }
    if ($matches.Count -ne 1) {
        throw "Attribute argument $Name was duplicated."
    }
    return $matches[0].Argument.Extent.Text.Trim([char[]] @([char]39, [char]34))
}

function Assert-ParameterSetContract {
    param(
        [Parameter(Mandatory = $true)] $ScriptAst,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $ExpectedParameterSet
    )

    $parameter = Get-ParameterAst -ScriptAst $ScriptAst -Name $Name
    $parameterAttributes = @(
        $parameter.Attributes |
            Where-Object { $_.TypeName.Name -ceq 'Parameter' }
    )
    if ($parameterAttributes.Count -ne 1) {
        throw "$Name must have exactly one Parameter attribute."
    }
    $actualSet = Get-AttributeNamedArgumentText `
        -Attribute $parameterAttributes[0] `
        -Name 'ParameterSetName'
    $mandatory = Get-AttributeNamedArgumentText `
        -Attribute $parameterAttributes[0] `
        -Name 'Mandatory'
    if ($actualSet -cne $ExpectedParameterSet -or $mandatory -cne '$true') {
        throw "$Name is not mandatory only in parameter set $ExpectedParameterSet."
    }
}

function Assert-CommonMandatorySwitch {
    param(
        [Parameter(Mandatory = $true)] $ScriptAst,
        [Parameter(Mandatory = $true)][string] $Name
    )

    $parameter = Get-ParameterAst -ScriptAst $ScriptAst -Name $Name
    $parameterAttributes = @(
        $parameter.Attributes |
            Where-Object { $_.TypeName.Name -ceq 'Parameter' }
    )
    if ($parameterAttributes.Count -ne 1 -or
        $null -ne (Get-AttributeNamedArgumentText `
            -Attribute $parameterAttributes[0] `
            -Name 'ParameterSetName') -or
        (Get-AttributeNamedArgumentText `
            -Attribute $parameterAttributes[0] `
            -Name 'Mandatory') -cne '$true') {
        throw "$Name must be mandatory in every connector-start parameter set."
    }
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Needle,
        [Parameter(Mandatory = $true)][string] $Contract
    )

    if ($Source.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) {
        throw "Synthetic lifecycle contract missing: $Contract"
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
        throw "Synthetic lifecycle ordering contract failed: $Contract"
    }
}

$startAst = Get-ParsedScript -Path $startPath
$lifecycleAst = Get-ParsedScript -Path $lifecyclePath
$null = Get-ParsedScript -Path $PSCommandPath
$startSource = [IO.File]::ReadAllText($startPath)
$lifecycleSource = [IO.File]::ReadAllText($lifecyclePath)

Assert-Contains `
    -Source $startSource `
    -Needle "DefaultParameterSetName = 'LiveActivation'" `
    -Contract 'live activation remains the default connector-start set'
foreach ($name in @('RemoteRouteVerified', 'RemoteCatchAllVerified')) {
    Assert-CommonMandatorySwitch -ScriptAst $startAst -Name $name
}
foreach ($name in @('PublicationCapabilityVerified', 'ExternalTokenLogSentinelVerified')) {
    Assert-ParameterSetContract `
        -ScriptAst $startAst `
        -Name $name `
        -ExpectedParameterSet 'LiveActivation'
}
foreach ($name in @(
    'SyntheticQualification',
    'DisposableSyntheticOriginVerified',
    'CacheBypassRuleVerified',
    'CustomerLogExportUnavailableVerified'
)) {
    Assert-ParameterSetContract `
        -ScriptAst $startAst `
        -Name $name `
        -ExpectedParameterSet 'SyntheticQualification'
}

Assert-Contains `
    -Source $startSource `
    -Needle "`$isSyntheticQualification = `$PSCmdlet.ParameterSetName -ceq 'SyntheticQualification'" `
    -Contract 'runtime branch is bound to the selected parameter set'
Assert-Contains `
    -Source $startSource `
    -Needle '-not $PublicationCapabilityVerified -or' `
    -Contract 'live publication capability gate is preserved'
Assert-Contains `
    -Source $startSource `
    -Needle '-not $ExternalTokenLogSentinelVerified' `
    -Contract 'live external token-log sentinel gate is preserved'
Assert-Ordered `
    -Source $startSource `
    -Earlier '-not $ExternalTokenLogSentinelVerified' `
    -Later 'Start-Service -Name $serviceName' `
    -Contract 'live post-request log gate precedes service start'

Assert-Ordered `
    -Source $lifecycleSource `
    -Earlier '& $externalScript -PrepareSyntheticOrigin' `
    -Later '& $startScript `' `
    -Contract 'prepare precedes temporary connector start'
Assert-Ordered `
    -Source $lifecycleSource `
    -Earlier '& $startScript `' `
    -Later '-SyntheticOriginQualification' `
    -Contract 'temporary connector start precedes external qualification'

$lifecycleTryContracts = @(
    $lifecycleAst.FindAll({
        param($node)
        if (-not ($node -is [Management.Automation.Language.TryStatementAst]) -or
            $null -eq $node.Finally) {
            return $false
        }
        $trySource = $node.Body.Extent.Text
        $finallySource = $node.Finally.Extent.Text
        return $trySource.IndexOf(
            '& $externalScript -PrepareSyntheticOrigin',
            [StringComparison]::Ordinal
        ) -ge 0 -and
        $trySource.IndexOf('& $startScript', [StringComparison]::Ordinal) -ge 0 -and
        $trySource.IndexOf('-SyntheticOriginQualification', [StringComparison]::Ordinal) -ge 0 -and
        $finallySource.IndexOf('& $stopScript -Confirm:$false', [StringComparison]::Ordinal) -ge 0 -and
        $finallySource.IndexOf(
            'Assert-ExternalNoSuccessAfterStop -Hostname $PublicHostname',
            [StringComparison]::Ordinal
        ) -ge 0
    }, $true)
)
if ($lifecycleTryContracts.Count -ne 1) {
    throw 'Exactly one qualification try/finally must guarantee connector stop and post-stop proof.'
}
$qualificationFinallySource = $lifecycleTryContracts[0].Finally.Extent.Text
Assert-Ordered `
    -Source $qualificationFinallySource `
    -Earlier '& $stopScript -Confirm:$false' `
    -Later 'Assert-ExternalNoSuccessAfterStop -Hostname $PublicHostname' `
    -Contract 'external non-success proof follows local connector stop'

foreach ($requiredLifecycleFragment in @(
    '-SyntheticQualification',
    '-DisposableSyntheticOriginVerified',
    '-CacheBypassRuleVerified',
    '-CustomerLogExportUnavailableVerified',
    "`$request.Method = 'HEAD'",
    '$request.Proxy = $null',
    '$request.AllowAutoRedirect = $false',
    '$request.UseDefaultCredentials = $false',
    '$request.Credentials = $null',
    '$request.MaximumResponseHeadersLength = 16',
    '$request.Timeout = 20000',
    'A connection-level failure is also a bounded non-success result.',
    '$statusCode -ge 200 -and $statusCode -lt 300',
    '[Array]::Clear($bearer.Bytes, 0, $bearer.Bytes.Length)'
)) {
    Assert-Contains `
        -Source $lifecycleSource `
        -Needle $requiredLifecycleFragment `
        -Contract "bounded no-secret lifecycle fragment $requiredLifecycleFragment"
}

foreach ($forbiddenLifecycleFragment in @(
    '-CleanupSyntheticOrigin',
    '-PublicationCapabilityVerified',
    '-ExternalTokenLogSentinelVerified',
    'compose.personal',
    '.env.personal',
    'Start-PersonalMemo.ps1',
    'Stop-PersonalMemo.ps1',
    'postgres',
    'ollama',
    '/api/'
)) {
    if ($lifecycleSource.IndexOf(
            $forbiddenLifecycleFragment,
            [StringComparison]::OrdinalIgnoreCase
        ) -ge 0) {
        throw "Synthetic lifecycle crossed a forbidden personal/product boundary: $forbiddenLifecycleFragment"
    }
}

$outputCommands = @(
    $lifecycleAst.FindAll({
        param($node)
        if (-not ($node -is [Management.Automation.Language.CommandAst])) {
            return $false
        }
        return @(
            'Write-Host',
            'Write-Output',
            'Write-Warning',
            'Write-Verbose',
            'Write-Debug',
            'Write-Information'
        ) -contains $node.GetCommandName()
    }, $true)
)
foreach ($command in $outputCommands) {
    if ($command.Extent.Text -match '(?i)\$(?:bearer|requestUri)|token=') {
        throw 'Synthetic lifecycle output may not contain or interpolate the generated bearer.'
    }
}

$throwStatements = @(
    $lifecycleAst.FindAll({
        param($node)
        return $node -is [Management.Automation.Language.ThrowStatementAst]
    }, $true)
)
foreach ($throwStatement in $throwStatements) {
    if ($throwStatement.Extent.Text -match '(?i)\$(?:bearer|requestUri)|token=') {
        throw 'Synthetic lifecycle exceptions may not contain or interpolate the generated bearer.'
    }
}
Assert-Contains `
    -Source $lifecycleSource `
    -Needle 'echoed because a generated bearer must never reach console output.' `
    -Contract 'child errors are replaced with a bounded non-secret phase failure'
Assert-Contains `
    -Source $lifecycleSource `
    -Needle 'function Get-SafeWorkflowFailureCode' `
    -Contract 'child failures are reduced to a reviewed static code'
Assert-Contains `
    -Source $lifecycleSource `
    -Needle '$workflowError = $_' `
    -Contract 'the caught child failure is retained only for safe-code classification'
Assert-Contains `
    -Source $lifecycleSource `
    -Needle "'Synthetic qualification failed during the bounded ' + `$workflowPhase +" `
    -Contract 'phase is appended from a bounded internal literal assignment'
Assert-Contains `
    -Source $lifecycleSource `
    -Needle "'Safe code: ' + `$safeFailureCode + '.'" `
    -Contract 'only the reviewed static safe code is appended to the failure'
Assert-Contains `
    -Source $lifecycleSource `
    -Needle 'throw $boundedFailureMessage' `
    -Contract 'the fully composed bounded message is thrown without format precedence'
if ($lifecycleSource.IndexOf(
        'bounded {0}',
        [StringComparison]::Ordinal
    ) -ge 0) {
    throw 'Synthetic lifecycle must not retain an unexpanded phase placeholder.'
}
if ($lifecycleSource.IndexOf(
        '$workflowError.Exception.Message',
        [StringComparison]::Ordinal
    ) -ge 0) {
    throw 'Synthetic lifecycle must never echo the raw child failure message.'
}

$allowedWorkflowPhases = @(
    'prepare-synthetic-origin',
    'temporary-synthetic-connector-start',
    'external-synthetic-probes',
    'complete'
)
$workflowPhaseAssignments = @(
    $lifecycleAst.FindAll({
        param($node)
        return $node -is [Management.Automation.Language.AssignmentStatementAst] -and
            $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and
            $node.Left.VariablePath.UserPath -ceq 'workflowPhase'
    }, $true)
)
if ($workflowPhaseAssignments.Count -ne 4) {
    throw 'Synthetic lifecycle workflowPhase must have exactly four literal assignments.'
}
foreach ($assignment in $workflowPhaseAssignments) {
    if (-not ($assignment.Right -is [Management.Automation.Language.CommandExpressionAst]) -or
        -not ($assignment.Right.Expression -is [Management.Automation.Language.StringConstantExpressionAst]) -or
        $allowedWorkflowPhases -cnotcontains $assignment.Right.Expression.Value) {
        throw 'Synthetic lifecycle workflowPhase escaped its fixed literal allow-list.'
    }
}

Write-Host 'Personal Memo Cloudflare synthetic lifecycle source contracts are valid.'
