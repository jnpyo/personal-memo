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
    'left join users owner on owner.id = credential.user_id'
)) {
    Assert-SourceContains `
        -Source $restoreSource `
        -Needle $requiredRestoreFragment `
        -Contract "restore invariant fragment $requiredRestoreFragment"
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
