[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $BackupFile,
    [string] $EnvFile,
    [switch] $RemoveAfterVerification
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

function Assert-PersonalMemoRestoredScalar {
    param(
        [Parameter(Mandatory = $true)][PSCustomObject] $Layout,
        [Parameter(Mandatory = $true)][PSCustomObject] $DatabaseIdentity,
        [Parameter(Mandatory = $true)][string] $Query,
        [Parameter(Mandatory = $true)][string] $Expected,
        [Parameter(Mandatory = $true)][string] $Invariant
    )

    $result = Invoke-PersonalMemoCompose -Layout $Layout -Capture -CommandArguments @(
        'exec', '-T', 'postgres', 'psql',
        "--username=$($DatabaseIdentity.Username)",
        "--dbname=$($DatabaseIdentity.Database)",
        '--set=ON_ERROR_STOP=1', '--tuples-only', '--no-align', '--command', $Query
    )
    $actual = $result.Trim()
    if ($actual -cne $Expected) {
        throw "Restored database invariant failed ($Invariant): expected $Expected, got $actual."
    }
}

$resolvedBackup = (Resolve-Path -LiteralPath $BackupFile -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedBackup -PathType Leaf) -or
    [IO.Path]::GetExtension($resolvedBackup) -cne '.dump') {
    throw 'BackupFile must be an existing .dump file.'
}
$checksumFile = "$resolvedBackup.sha256"
if (-not (Test-Path -LiteralPath $checksumFile -PathType Leaf)) {
    throw "Required checksum file was not found: $checksumFile"
}
$checksumLine = ([IO.File]::ReadAllText($checksumFile)).Trim()
if ($checksumLine -notmatch '^([A-Fa-f0-9]{64})\s+\*?(.+)$') {
    throw 'The checksum file has an invalid format.'
}
$expectedHash = $Matches[1].ToUpperInvariant()
$expectedName = $Matches[2]
if ($expectedName -cne [IO.Path]::GetFileName($resolvedBackup)) {
    throw 'The checksum file names a different backup.'
}
$actualHash = (Get-FileHash -LiteralPath $resolvedBackup -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actualHash -cne $expectedHash) {
    throw 'Backup checksum verification failed.'
}

$restoreProject = 'personal-memo-restore-{0}-{1}' -f [DateTime]::UtcNow.ToString('yyyyMMddHHmmss'), ([Guid]::NewGuid().ToString('N').Substring(0, 8))
$layoutArguments = @{ ProjectName = $restoreProject; RestoreProject = $true }
if (-not [string]::IsNullOrWhiteSpace($EnvFile)) {
    $layoutArguments.EnvFile = $EnvFile
}
$layout = New-PersonalMemoLayout @layoutArguments
$null = Assert-PersonalMemoRestoreComposeContract -Layout $layout
$databaseIdentity = Get-PersonalMemoDatabaseIdentity -Layout $layout
$operationId = [Guid]::NewGuid().ToString('N')
$containerDump = "/tmp/personal-memo-restore-$operationId.dump"
Assert-PersonalMemoContainerDumpPath -Path $containerDump
$verified = $false

try {
    Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @('up', '-d', '--wait', 'postgres')
    $restorePostgres = Get-PersonalMemoServiceContainerId -Layout $layout -Service 'postgres'
    Invoke-PersonalMemoDocker -Arguments @('cp', $resolvedBackup, "${restorePostgres}:$containerDump")
    $null = Invoke-PersonalMemoCompose -Layout $layout -Capture -CommandArguments @(
        'exec', '-T', 'postgres', 'pg_restore', '--list', $containerDump
    )
    Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @(
        'exec', '-T', 'postgres', 'pg_restore',
        "--username=$($databaseIdentity.Username)",
        "--dbname=$($databaseIdentity.Database)",
        '--clean', '--if-exists', '--no-owner', '--no-acl', '--exit-on-error', $containerDump
    )
    Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @('up', '-d', '--build', '--wait', 'backend')
    Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @(
        'exec', '-T', 'postgres', 'psql',
        "--username=$($databaseIdentity.Username)",
        "--dbname=$($databaseIdentity.Database)",
        '--set=ON_ERROR_STOP=1', '--command=TRUNCATE TABLE spring_session CASCADE;'
    )
    Assert-PersonalMemoRestoredScalar `
        -Layout $layout `
        -DatabaseIdentity $databaseIdentity `
        -Query 'select count(*) from initial_account_provisioning;' `
        -Expected '1' `
        -Invariant 'exactly one initial-account provisioning row'
    Assert-PersonalMemoRestoredScalar `
        -Layout $layout `
        -DatabaseIdentity $databaseIdentity `
        -Query @"
select count(*)
from initial_account_provisioning gate
where gate.status = 'AVAILABLE'
  and exists (
    select 1 from users claimed where claimed.status <> 'LEGACY_UNCLAIMED'
  );
"@ `
        -Expected '0' `
        -Invariant 'a claimed user cannot coexist with an available bootstrap gate'
    Assert-PersonalMemoRestoredScalar `
        -Layout $layout `
        -DatabaseIdentity $databaseIdentity `
        -Query @"
select count(*)
from local_credentials credential
left join users owner on owner.id = credential.user_id
where owner.id is null;
"@ `
        -Expected '0' `
        -Invariant 'local credentials cannot be orphaned'
    Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @(
        'exec', '-T', 'backend', 'wget', '-q', '-O', '-', 'http://127.0.0.1:8080/actuator/health'
    )

    $countQuery = @(
        "select 'users' as table_name,count(*)::bigint as row_count from users",
        "union all select 'memos',count(*)::bigint from memos",
        "union all select 'memo_revisions',count(*)::bigint from memo_revisions",
        "union all select 'analysis_runs',count(*)::bigint from analysis_runs",
        "union all select 'analysis_applications',count(*)::bigint from analysis_applications",
        "union all select 'tags',count(*)::bigint from tags",
        "union all select 'task_details',count(*)::bigint from task_details",
        "union all select 'flyway_schema_history',count(*)::bigint from flyway_schema_history",
        'order by table_name;'
    ) -join ' '
    Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @(
        'exec', '-T', 'postgres', 'psql',
        "--username=$($databaseIdentity.Username)",
        "--dbname=$($databaseIdentity.Database)",
        '--set=ON_ERROR_STOP=1', '--command', $countQuery
    )
    $verified = $true
    Write-Host "Restore verification passed in isolated project $restoreProject."
} finally {
    try {
        $restorePostgres = Get-PersonalMemoServiceContainerId -Layout $layout -Service 'postgres'
        Assert-PersonalMemoContainerDumpPath -Path $containerDump
        Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @(
            'exec', '-T', 'postgres', 'rm', '-f', '--', $containerDump
        )
    } catch {
        Write-Warning 'The isolated restore container temporary dump could not be removed.'
    }

    if ($verified -and $RemoveAfterVerification) {
        $null = Assert-PersonalMemoRestoreComposeContract -Layout $layout
        Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @('down', '--volumes', '--remove-orphans')
        Write-Host "Removed verified disposable restore project $restoreProject."
    } else {
        Write-Host "Preserved isolated restore project for inspection: $restoreProject"
        Write-Host 'No personal project or personal PostgreSQL volume was removed.'
    }
}
