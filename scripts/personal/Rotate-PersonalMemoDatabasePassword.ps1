[CmdletBinding()]
param(
    [string] $ProjectName = 'personal-memo-private-win',
    [string] $EnvFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

function Get-PersonalMemoUtf8Sha256 {
    param([Parameter(Mandatory = $true)][string] $Value)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Get-PersonalMemoContainerEnvironmentHash {
    param(
        [Parameter(Mandatory = $true)][string] $ContainerId,
        [Parameter(Mandatory = $true)]
        [ValidateSet('POSTGRES_PASSWORD', 'SPRING_DATASOURCE_PASSWORD')]
        [string] $Name
    )

    # Expand the variable inside the container. The secret itself is never placed in this process's
    # environment, Docker arguments, or output; only its one-way SHA-256 digest is captured.
    $shellCommand = 'printf "%s" "${' + $Name + '}" | sha256sum'
    $hashLine = Invoke-PersonalMemoDocker -Capture -Arguments @(
        'exec', $ContainerId, 'sh', '-c', $shellCommand
    )
    $hash = (($hashLine.Trim()) -split '\s+')[0].ToLowerInvariant()
    if ($hash -cnotmatch '^[a-f0-9]{64}$') {
        throw "Container did not return a valid environment digest: $Name"
    }
    return $hash
}

function Get-PersonalMemoPostgresVolumeName {
    param([Parameter(Mandatory = $true)][string] $ContainerId)

    $mountsJson = Invoke-PersonalMemoDocker -Capture -Arguments @(
        'inspect', '--format', '{{json .Mounts}}', $ContainerId
    )
    $mounts = @(ConvertFrom-PersonalMemoJson `
        -Json $mountsJson `
        -Context 'PostgreSQL container mount inspection')
    $dataMounts = @($mounts | Where-Object {
        [string] $_.Destination -ceq '/var/lib/postgresql/data'
    })
    if ($dataMounts.Count -ne 1 -or [string] $dataMounts[0].Type -cne 'volume') {
        throw 'PostgreSQL must have exactly one named-volume data mount.'
    }
    return [string] $dataMounts[0].Name
}

function Restore-PersonalMemoEnvironmentFile {
    param(
        [Parameter(Mandatory = $true)][string] $EnvironmentPath,
        [Parameter(Mandatory = $true)][string] $RollbackPath,
        [Parameter(Mandatory = $true)][string] $DiscardPath,
        [Parameter(Mandatory = $true)][string] $ExpectedPassword
    )

    if (Test-Path -LiteralPath $DiscardPath) {
        throw 'Refusing to overwrite a password-rotation recovery file.'
    }
    [IO.File]::Replace($RollbackPath, $EnvironmentPath, $DiscardPath, $true)
    Set-PersonalMemoPrivateFileAcl -Path $EnvironmentPath
    Set-PersonalMemoPrivateFileAcl -Path $DiscardPath

    $restoredValues = Read-PersonalMemoEnvFile -Path $EnvironmentPath
    $restoredPassword = Get-PersonalMemoEnvValue -Values $restoredValues -Name 'POSTGRES_PASSWORD'
    if ($restoredPassword -cne $ExpectedPassword) {
        throw 'The protected environment rollback could not be verified.'
    }
    $restoredPassword = $null
    Remove-Item -LiteralPath $DiscardPath -Force
}

Assert-PersonalMemoProjectName -ProjectName $ProjectName
$resolvedEnvironmentPath = if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    [IO.Path]::GetFullPath((Join-Path $script:PersonalMemoRepositoryRoot '.env.personal'))
} else {
    [IO.Path]::GetFullPath($EnvFile)
}
$rotationLockPath = "$resolvedEnvironmentPath.rotation.lock"
if (-not (Test-Path -LiteralPath $rotationLockPath -PathType Leaf)) {
    $lockCandidate = "$rotationLockPath.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        $null = New-Item -ItemType File -Path $lockCandidate -ErrorAction Stop
        Set-PersonalMemoPrivateFileAcl -Path $lockCandidate
        try {
            [IO.File]::Move($lockCandidate, $rotationLockPath)
        } catch [IO.IOException] {
            if (-not (Test-Path -LiteralPath $rotationLockPath -PathType Leaf)) {
                throw
            }
        }
    } finally {
        if (Test-Path -LiteralPath $lockCandidate -PathType Leaf) {
            Remove-Item -LiteralPath $lockCandidate -Force
        }
    }
}
Assert-PersonalMemoPrivateAcl -Path $rotationLockPath
$rotationLockStream = $null
try {
    $rotationLockStream = [IO.File]::Open(
        $rotationLockPath,
        [IO.FileMode]::Open,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None
    )
} catch [IO.IOException] {
    throw 'Another database password rotation is already running for this environment file.'
}

try {
$layoutArguments = @{ ProjectName = $ProjectName }
if (-not [string]::IsNullOrWhiteSpace($EnvFile)) {
    $layoutArguments.EnvFile = $EnvFile
}
$layout = New-PersonalMemoLayout @layoutArguments

Assert-PersonalMemoPrivateAcl -Path $layout.EnvFile
$null = Assert-PersonalMemoComposeContract -Layout $layout
$databaseIdentity = Get-PersonalMemoDatabaseIdentity -Layout $layout

$environmentValues = Read-PersonalMemoEnvFile -Path $layout.EnvFile
$oldPassword = Get-PersonalMemoEnvValue -Values $environmentValues -Name 'POSTGRES_PASSWORD'
$environmentValues = $null

$environmentLines = [IO.File]::ReadAllLines($layout.EnvFile)
$passwordLineIndexes = @()
for ($index = 0; $index -lt $environmentLines.Length; $index++) {
    if ($environmentLines[$index] -cmatch '^POSTGRES_PASSWORD=') {
        $passwordLineIndexes += $index
    }
}
if ($passwordLineIndexes.Count -ne 1) {
    throw '.env.personal must contain exactly one canonical POSTGRES_PASSWORD setting.'
}

$newPassword = New-PersonalMemoHexSecret -ByteCount 32
while ($newPassword -ceq $oldPassword) {
    $newPassword = New-PersonalMemoHexSecret -ByteCount 32
}
if ($newPassword -cnotmatch '^[a-f0-9]{64}$') {
    throw 'The generated database password did not satisfy the 64-hex contract.'
}
$newPasswordHash = Get-PersonalMemoUtf8Sha256 -Value $newPassword
$environmentLines[$passwordLineIndexes[0]] = "POSTGRES_PASSWORD=$newPassword"

$postgresBefore = Get-PersonalMemoServiceContainerId -Layout $layout -Service 'postgres' -IncludePersonal
$backendBefore = Get-PersonalMemoServiceContainerId -Layout $layout -Service 'backend' -IncludePersonal
$frontendBefore = Get-PersonalMemoServiceContainerId -Layout $layout -Service 'frontend' -IncludePersonal
$expectedVolume = "$($layout.ProjectName)_personal-memo-postgres"
$mountedVolumeBefore = Get-PersonalMemoPostgresVolumeName -ContainerId $postgresBefore
if ($mountedVolumeBefore -cne $expectedVolume) {
    throw 'The running PostgreSQL container is not using the exact personal data volume.'
}

# Verify local-socket administrative access before changing the protected environment file.
$preflightInputMayHaveReachedServer = $false
Invoke-PersonalMemoPostgresInput `
    -ContainerId $postgresBefore `
    -DatabaseIdentity $databaseIdentity `
    -Sql 'SELECT 1;' `
    -InputMayHaveReachedServer ([ref] $preflightInputMayHaveReachedServer)

$operationId = [Guid]::NewGuid().ToString('N')
$environmentDirectory = [IO.Path]::GetDirectoryName($layout.EnvFile)
$environmentLeaf = [IO.Path]::GetFileName($layout.EnvFile)
$stagedPath = Join-Path $environmentDirectory ".$environmentLeaf.rotate-$operationId.tmp"
$rollbackPath = Join-Path $environmentDirectory ".$environmentLeaf.rotate-$operationId.rollback"
$discardPath = Join-Path $environmentDirectory ".$environmentLeaf.rotate-$operationId.discard"

foreach ($path in @($stagedPath, $rollbackPath, $discardPath)) {
    if (Test-Path -LiteralPath $path) {
        throw 'Refusing to overwrite a password-rotation recovery file.'
    }
}

$utf8NoBom = New-Object Text.UTF8Encoding($false)
$environmentReplaced = $false
$alterSucceeded = $false
$alterInputMayHaveReachedServer = $false
$rotationSucceeded = $false

try {
    # Protect an empty staging file before placing either password in it.
    $null = New-Item -ItemType File -Path $stagedPath -ErrorAction Stop
    Set-PersonalMemoPrivateFileAcl -Path $stagedPath
    [IO.File]::WriteAllLines($stagedPath, $environmentLines, $utf8NoBom)
    Assert-PersonalMemoPrivateAcl -Path $stagedPath

    # File.Replace is atomic on the same NTFS volume and leaves a private rollback copy containing
    # the old value until the database ALTER succeeds.
    [IO.File]::Replace($stagedPath, $layout.EnvFile, $rollbackPath, $true)
    $environmentReplaced = $true
    try {
        Set-PersonalMemoPrivateFileAcl -Path $layout.EnvFile
        Set-PersonalMemoPrivateFileAcl -Path $rollbackPath

        $updatedValues = Read-PersonalMemoEnvFile -Path $layout.EnvFile
        $updatedPassword = Get-PersonalMemoEnvValue -Values $updatedValues -Name 'POSTGRES_PASSWORD'
        if ($updatedPassword -cne $newPassword) {
            throw 'The protected environment replacement could not be verified.'
        }
        $updatedValues = $null
        $updatedPassword = $null

        # Both the role name and generated password have already been constrained to safe ASCII.
        $alterSql = 'ALTER ROLE "{0}" WITH PASSWORD ''{1}'';' -f `
            $databaseIdentity.Username, $newPassword
        Invoke-PersonalMemoForwardOnlyPostgresInput `
            -ContainerId $postgresBefore `
            -DatabaseIdentity $databaseIdentity `
            -Sql $alterSql `
            -InputMayHaveReachedServer ([ref] $alterInputMayHaveReachedServer)
        $alterSql = $null
        $alterSucceeded = $true
    } catch {
        $alterSql = $null
        if ($alterInputMayHaveReachedServer) {
            throw
        }
        try {
            Restore-PersonalMemoEnvironmentFile `
                -EnvironmentPath $layout.EnvFile `
                -RollbackPath $rollbackPath `
                -DiscardPath $discardPath `
                -ExpectedPassword $oldPassword
            $environmentReplaced = $false
        } catch {
            throw (
                'Database password rotation failed before ALTER completed, and the protected ' +
                'environment rollback also failed. Do not restart the stack; inspect the private ' +
                'rotation recovery files without printing their contents.'
            )
        }
        throw 'Database password rotation failed before ALTER completed; the environment file was rolled back.'
    }

    # Recreate the frontend with the credential-bearing containers so Nginx resolves the new
    # backend container address. The named PostgreSQL volume remains untouched.
    Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments @(
        'up', '-d', '--no-build', '--force-recreate', '--wait', 'postgres', 'backend', 'frontend'
    )

    $postgresAfter = Get-PersonalMemoServiceContainerId -Layout $layout -Service 'postgres' -IncludePersonal
    $backendAfter = Get-PersonalMemoServiceContainerId -Layout $layout -Service 'backend' -IncludePersonal
    $frontendAfter = Get-PersonalMemoServiceContainerId -Layout $layout -Service 'frontend' -IncludePersonal
    if ($postgresAfter -ceq $postgresBefore -or
        $backendAfter -ceq $backendBefore -or
        $frontendAfter -ceq $frontendBefore) {
        throw 'The scoped personal containers were not all recreated.'
    }

    $mountedVolumeAfter = Get-PersonalMemoPostgresVolumeName -ContainerId $postgresAfter
    if ($mountedVolumeAfter -cne $expectedVolume -or $mountedVolumeAfter -cne $mountedVolumeBefore) {
        throw 'The PostgreSQL data volume changed during credential rotation.'
    }

    $postgresPasswordHash = Get-PersonalMemoContainerEnvironmentHash `
        -ContainerId $postgresAfter -Name 'POSTGRES_PASSWORD'
    $backendPasswordHash = Get-PersonalMemoContainerEnvironmentHash `
        -ContainerId $backendAfter -Name 'SPRING_DATASOURCE_PASSWORD'
    if ($postgresPasswordHash -cne $newPasswordHash -or $backendPasswordHash -cne $newPasswordHash) {
        throw 'A recreated container did not receive the rotated credential.'
    }
    $postgresPasswordHash = $null
    $backendPasswordHash = $null

    $healthJson = Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -Capture -CommandArguments @(
        'exec', '-T', 'backend', 'wget', '-q', '-O', '-',
        'http://127.0.0.1:8080/actuator/health'
    )
    try {
        $health = ConvertFrom-Json -InputObject $healthJson -ErrorAction Stop
    } catch {
        throw 'The backend health response could not be parsed after credential rotation.'
    }
    if ([string] $health.status -cne 'UP') {
        throw 'The backend and PostgreSQL were not healthy after credential rotation.'
    }
    $healthJson = $null
    $health = $null

    $frontendHealthJson = Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -Capture -CommandArguments @(
        'exec', '-T', 'frontend', 'wget', '-q', '-O', '-',
        'http://127.0.0.1:5173/api/v1/health'
    )
    $frontendHealth = ConvertFrom-PersonalMemoJson `
        -Json $frontendHealthJson `
        -Context 'Frontend-proxied health response'
    if ([string] $frontendHealth.status -cne 'UP') {
        throw 'The frontend could not reach the recreated backend after credential rotation.'
    }
    $frontendHealthJson = $null
    $frontendHealth = $null

    Assert-PersonalMemoPrivateAcl -Path $layout.EnvFile
    $rotationSucceeded = $true
    Write-Host 'Database password rotation completed; the personal data volume was preserved.'
    Write-Host 'PostgreSQL, backend, and frontend were recreated and the proxied API is healthy.'
} finally {
    # After ALTER succeeds, never restore the exposed old password. A later container-health failure
    # is recoverable by rerunning the scoped Compose up command with the already-consistent new value.
    if (($alterSucceeded -or $alterInputMayHaveReachedServer) -and
        (Test-Path -LiteralPath $rollbackPath -PathType Leaf)) {
        Remove-Item -LiteralPath $rollbackPath -Force
    }
    if (Test-Path -LiteralPath $stagedPath -PathType Leaf) {
        Remove-Item -LiteralPath $stagedPath -Force
    }
    if (Test-Path -LiteralPath $discardPath -PathType Leaf) {
        Remove-Item -LiteralPath $discardPath -Force
    }

    $environmentLines = $null
    $oldPassword = $null
    $newPassword = $null
    $newPasswordHash = $null
    $alterSql = $null

    if (($alterSucceeded -or $alterInputMayHaveReachedServer) -and -not $rotationSucceeded) {
        Write-Warning (
            'The new credential may already be active and the protected environment keeps its new ' +
            'value, but rotation verification did not complete. Do not restore the exposed old ' +
            'credential; rerun this rotation to converge forward.'
        )
    } elseif ($environmentReplaced -and -not $alterSucceeded) {
        Write-Warning (
            'The database ALTER did not complete and the environment file may require recovery. ' +
            'Do not restart the stack until the private recovery state is resolved.'
        )
    }
}
} finally {
    if ($null -ne $rotationLockStream) {
        $rotationLockStream.Dispose()
    }
}
