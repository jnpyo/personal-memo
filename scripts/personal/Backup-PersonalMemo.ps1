[CmdletBinding()]
param(
    [string] $ProjectName = 'personal-memo-private-win',
    [string] $EnvFile,
    [string] $BackupDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

$layoutArguments = @{ ProjectName = $ProjectName }
if (-not [string]::IsNullOrWhiteSpace($EnvFile)) {
    $layoutArguments.EnvFile = $EnvFile
}
$layout = New-PersonalMemoLayout @layoutArguments
$null = Assert-PersonalMemoComposeContract -Layout $layout
$databaseIdentity = Get-PersonalMemoDatabaseIdentity -Layout $layout
$postgresContainer = Get-PersonalMemoServiceContainerId -Layout $layout -Service 'postgres' -IncludePersonal

if ([string]::IsNullOrWhiteSpace($BackupDirectory)) {
    $BackupDirectory = Join-Path (Get-PersonalMemoDocumentsDirectory) 'PersonalMemo\Backups'
}
$backupRoot = Assert-PersonalMemoBackupDirectory -Path $BackupDirectory
if (Test-Path -LiteralPath $backupRoot) {
    if (-not (Test-Path -LiteralPath $backupRoot -PathType Container)) {
        throw "Backup path is not a directory: $backupRoot"
    }
} else {
    $null = New-Item -ItemType Directory -Path $backupRoot
}
Set-PersonalMemoPrivateDirectoryAcl -Path $backupRoot

$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfffZ')
$finalDump = Join-Path $backupRoot "personal-memo-$stamp.dump"
$finalChecksum = "$finalDump.sha256"
$operationId = [Guid]::NewGuid().ToString('N')
$partialDump = Join-Path $backupRoot ".$([IO.Path]::GetFileName($finalDump)).$operationId.partial"
$partialChecksum = "$partialDump.sha256"
$containerDump = "/tmp/personal-memo-backup-$operationId.dump"
Assert-PersonalMemoContainerDumpPath -Path $containerDump

foreach ($target in @($finalDump, $finalChecksum, $partialDump, $partialChecksum)) {
    if (Test-Path -LiteralPath $target) {
        throw "Refusing to overwrite backup output: $target"
    }
}

$finalDumpCreated = $false
$finalChecksumCreated = $false
$backupCommitted = $false

try {
    Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments @(
        'exec', '-T', 'postgres', 'pg_dump',
        "--username=$($databaseIdentity.Username)",
        "--dbname=$($databaseIdentity.Database)",
        '--format=custom', '--no-owner', '--no-acl',
        '--exclude-table-data=spring_session',
        '--exclude-table-data=spring_session_attributes',
        "--file=$containerDump"
    )
    $dumpContents = Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -Capture -CommandArguments @(
        'exec', '-T', 'postgres', 'pg_restore', '--list', $containerDump
    )
    if ($dumpContents -match '(?im)\bTABLE DATA\s+\S+\s+spring_session(?:_attributes)?\b') {
        throw 'The backup unexpectedly contains live Spring Session table data.'
    }
    $containerHashLine = Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -Capture -CommandArguments @(
        'exec', '-T', 'postgres', 'sha256sum', $containerDump
    )
    $containerHash = ($containerHashLine -split '\s+')[0].ToUpperInvariant()
    if ($containerHash -notmatch '^[A-F0-9]{64}$') {
        throw 'The PostgreSQL container did not return a valid SHA-256 checksum.'
    }

    Invoke-PersonalMemoDocker -Arguments @('cp', "${postgresContainer}:$containerDump", $partialDump)
    Set-PersonalMemoPrivateFileAcl -Path $partialDump
    $hostHash = (Get-FileHash -LiteralPath $partialDump -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($hostHash -cne $containerHash) {
        throw 'The copied backup checksum does not match the container dump.'
    }

    $null = New-Item -ItemType File -Path $partialChecksum -ErrorAction Stop
    Set-PersonalMemoPrivateFileAcl -Path $partialChecksum
    [IO.File]::WriteAllText(
        $partialChecksum,
        "$hostHash *$([IO.Path]::GetFileName($finalDump))$([Environment]::NewLine)",
        (New-Object Text.ASCIIEncoding)
    )
    Assert-PersonalMemoPrivateAcl -Path $partialChecksum
    Move-Item -LiteralPath $partialDump -Destination $finalDump
    $finalDumpCreated = $true
    Set-PersonalMemoPrivateFileAcl -Path $finalDump
    Move-Item -LiteralPath $partialChecksum -Destination $finalChecksum
    $finalChecksumCreated = $true
    Set-PersonalMemoPrivateFileAcl -Path $finalChecksum
    $backupCommitted = $true

    Write-Host "Backup created: $finalDump"
    Write-Host "Checksum created: $finalChecksum"
    Write-Host 'The dump includes private memo and account data; live session table data is excluded.'
} finally {
    if (Test-Path -LiteralPath $partialDump -PathType Leaf) {
        Remove-Item -LiteralPath $partialDump -Force
    }
    if (Test-Path -LiteralPath $partialChecksum -PathType Leaf) {
        Remove-Item -LiteralPath $partialChecksum -Force
    }
    if (-not $backupCommitted -and $finalDumpCreated -and
        (Test-Path -LiteralPath $finalDump -PathType Leaf)) {
        Remove-Item -LiteralPath $finalDump -Force
    }
    if (-not $backupCommitted -and $finalChecksumCreated -and
        (Test-Path -LiteralPath $finalChecksum -PathType Leaf)) {
        Remove-Item -LiteralPath $finalChecksum -Force
    }
    Assert-PersonalMemoContainerDumpPath -Path $containerDump
    try {
        Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments @(
            'exec', '-T', 'postgres', 'rm', '-f', '--', $containerDump
        )
    } catch {
        Write-Warning 'The unique temporary dump could not be removed from the PostgreSQL container.'
    }
}
