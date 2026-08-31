[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $BackupFile,
    [string] $EnvFile,
    [ValidatePattern('^[0-9]+$')][string] $ExpectedBackupFlywayVersion,
    [ValidatePattern('^[0-9]+$')][string] $ExpectedFlywayVersion,
    [switch] $RequireZeroCalendarBackfill,
    [switch] $RequireV23LocalOnlyConsentBackfill,
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
        '--no-psqlrc', '--set=ON_ERROR_STOP=1', '--tuples-only', '--no-align', '--command', $Query
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
if ($RequireZeroCalendarBackfill -and
    ([string]::IsNullOrWhiteSpace($ExpectedBackupFlywayVersion) -or
        [string]::IsNullOrWhiteSpace($ExpectedFlywayVersion))) {
    throw 'RequireZeroCalendarBackfill requires both ExpectedBackupFlywayVersion and ExpectedFlywayVersion.'
}
if ($RequireZeroCalendarBackfill -and
    ($ExpectedBackupFlywayVersion -cne '20' -or $ExpectedFlywayVersion -cne '22')) {
    throw 'The V22 zero-backfill gate requires source Flyway 20 and target Flyway 22.'
}
if ($RequireV23LocalOnlyConsentBackfill -and
    ([string]::IsNullOrWhiteSpace($ExpectedBackupFlywayVersion) -or
        [string]::IsNullOrWhiteSpace($ExpectedFlywayVersion))) {
    throw 'RequireV23LocalOnlyConsentBackfill requires both ExpectedBackupFlywayVersion and ExpectedFlywayVersion.'
}
if ($RequireV23LocalOnlyConsentBackfill -and
    ($ExpectedBackupFlywayVersion -cne '22' -or $ExpectedFlywayVersion -cne '23')) {
    throw 'The V23 local-only consent gate requires source Flyway 22 and target Flyway 23.'
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
    Assert-PersonalMemoRestoredScalar `
        -Layout $layout `
        -DatabaseIdentity $databaseIdentity `
        -Query 'select count(*) from flyway_schema_history where not success;' `
        -Expected '0' `
        -Invariant 'the restored backup has no failed Flyway migration'
    if (-not [string]::IsNullOrWhiteSpace($ExpectedBackupFlywayVersion)) {
        Assert-PersonalMemoRestoredScalar `
            -Layout $layout `
            -DatabaseIdentity $databaseIdentity `
            -Query 'select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1;' `
            -Expected $ExpectedBackupFlywayVersion `
            -Invariant 'restored backup Flyway version before migration'
    }
    Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @('up', '-d', '--build', '--wait', 'backend')
    Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @(
        'exec', '-T', 'postgres', 'psql',
        "--username=$($databaseIdentity.Username)",
        "--dbname=$($databaseIdentity.Database)",
        '--no-psqlrc', '--set=ON_ERROR_STOP=1', '--command=TRUNCATE TABLE spring_session CASCADE;'
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

    if (-not [string]::IsNullOrWhiteSpace($ExpectedFlywayVersion)) {
        Assert-PersonalMemoRestoredScalar `
            -Layout $layout `
            -DatabaseIdentity $databaseIdentity `
            -Query 'select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1;' `
            -Expected $ExpectedFlywayVersion `
            -Invariant 'latest successful Flyway version'
    }
    Assert-PersonalMemoRestoredScalar `
        -Layout $layout `
        -DatabaseIdentity $databaseIdentity `
        -Query 'select count(*) from flyway_schema_history where not success;' `
        -Expected '0' `
        -Invariant 'the migrated restore has no failed Flyway migration'
    if ($RequireZeroCalendarBackfill) {
        Assert-PersonalMemoRestoredScalar `
            -Layout $layout `
            -DatabaseIdentity $databaseIdentity `
            -Query @"
select count(*)
from (values
  (to_regclass('public.event_details')),
  (to_regclass('public.calendar_feeds')),
  (to_regclass('public.calendar_feed_entries'))
) required(relation)
where required.relation is null;
"@ `
            -Expected '0' `
            -Invariant 'all V21 and V22 calendar tables exist'
        Assert-PersonalMemoRestoredScalar `
            -Layout $layout `
            -DatabaseIdentity $databaseIdentity `
            -Query @"
select
  (select count(*) from event_details)
  + (select count(*) from calendar_feeds)
  + (select count(*) from calendar_feed_entries);
"@ `
            -Expected '0' `
            -Invariant 'V21 and V22 migrations do not backfill calendar data'
    }
    if ($RequireV23LocalOnlyConsentBackfill) {
        Assert-PersonalMemoRestoredScalar `
            -Layout $layout `
            -DatabaseIdentity $databaseIdentity `
            -Query @"
select count(*)
from (values
  ('publication_scope'),
  ('public_consent_policy_version'),
  ('public_consent_granted_at')
) required(column_name)
where not exists (
  select 1
  from information_schema.columns actual
  where actual.table_schema = 'public'
    and actual.table_name = 'calendar_feeds'
    and actual.column_name = required.column_name
);
"@ `
            -Expected '0' `
            -Invariant 'all V23 calendar feed publication-consent columns exist'
        Assert-PersonalMemoRestoredScalar `
            -Layout $layout `
            -DatabaseIdentity $databaseIdentity `
            -Query @"
select count(*)
from calendar_feeds
where publication_scope <> 'LOCAL_ONLY'
   or public_consent_policy_version is not null
   or public_consent_granted_at is not null;
"@ `
            -Expected '0' `
            -Invariant 'V23 keeps every restored feed local-only without a consent pin'
    }
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
