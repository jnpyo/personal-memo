[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $LanIPv4,
    [string] $TlsHostName = $env:COMPUTERNAME,
    [ValidateRange(1024, 65535)][int] $FrontendPort = 8080,
    [ValidateRange(1024, 65535)][int] $HttpsPort = 8443,
    [string] $BootstrapEmail = '',
    [string] $BootstrapDisplayName = '',
    [string] $BootstrapTimeZone = 'Asia/Seoul'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

function Find-GitOpenSsl {
    $candidates = New-Object 'System.Collections.Generic.List[string]'
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -ne $git) {
        $gitCmdDirectory = Split-Path -Parent $git.Source
        $gitRoot = Split-Path -Parent $gitCmdDirectory
        $candidates.Add((Join-Path $gitRoot 'usr\bin\openssl.exe'))
        $candidates.Add((Join-Path $gitRoot 'mingw64\bin\openssl.exe'))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $candidates.Add((Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe'))
        $candidates.Add((Join-Path $env:ProgramFiles 'Git\mingw64\bin\openssl.exe'))
    }
    $programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    if (-not [string]::IsNullOrWhiteSpace($programFilesX86)) {
        $candidates.Add((Join-Path $programFilesX86 'Git\usr\bin\openssl.exe'))
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    throw 'Git for Windows OpenSSL was not found. Install Git for Windows before initialization.'
}

function Invoke-GitOpenSsl {
    param(
        [Parameter(Mandatory = $true)][string] $OpenSsl,
        [Parameter(Mandatory = $true)][string[]] $OpenSslArguments,
        [switch] $Capture
    )

    if ($Capture) {
        $output = & $OpenSsl @OpenSslArguments
        if ($LASTEXITCODE -ne 0) {
            throw "OpenSSL failed with exit code $LASTEXITCODE."
        }
        return ($output -join [Environment]::NewLine)
    }
    & $OpenSsl @OpenSslArguments
    if ($LASTEXITCODE -ne 0) {
        throw "OpenSSL failed with exit code $LASTEXITCODE."
    }
}

if (-not (Test-PersonalMemoPrivateIPv4 -Address $LanIPv4)) {
    throw 'LanIPv4 must be a private RFC1918 IPv4 address assigned to this Windows PC.'
}
$assignedAddress = Get-NetIPAddress -AddressFamily IPv4 -IPAddress $LanIPv4 -ErrorAction SilentlyContinue
if ($null -eq $assignedAddress) {
    throw 'LanIPv4 is not currently assigned to a Windows network adapter on this PC.'
}
if ($FrontendPort -eq $HttpsPort) {
    throw 'FrontendPort and HttpsPort must be different.'
}
if ($HttpsPort -ne 8443) {
    throw 'HttpsPort must remain 8443 for the fixed private Nginx TLS listener.'
}
if ([string]::IsNullOrWhiteSpace($TlsHostName) -or
    $TlsHostName.Length -gt 253 -or
    $TlsHostName -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or
    $TlsHostName.Contains('..')) {
    throw 'TlsHostName must be a simple DNS-compatible hostname.'
}
if (($BootstrapEmail.Length -eq 0) -xor ($BootstrapDisplayName.Length -eq 0)) {
    throw 'BootstrapEmail and BootstrapDisplayName must both be provided or both be empty.'
}
if ($BootstrapEmail.Length -gt 254 -or $BootstrapEmail.Contains('=') -or $BootstrapEmail.Contains('#')) {
    throw 'BootstrapEmail is not safe for the private environment file.'
}
if ($BootstrapDisplayName.Length -gt 80 -or $BootstrapDisplayName.Contains("`r") -or $BootstrapDisplayName.Contains("`n")) {
    throw 'BootstrapDisplayName must be at most 80 characters on one line.'
}
if ($BootstrapTimeZone -notmatch '^[A-Za-z0-9_+\-/]{1,64}$') {
    throw 'BootstrapTimeZone must be a safe IANA time-zone identifier.'
}

$documents = Get-PersonalMemoDocumentsDirectory
$personalDirectory = Join-Path $documents 'PersonalMemo'
$tlsDirectory = Join-Path $personalDirectory 'PrivateTls'
$backupDirectory = Join-Path $personalDirectory 'Backups'
$envFile = Join-Path $script:PersonalMemoRepositoryRoot '.env.personal'
if (Test-Path -LiteralPath $envFile) {
    throw "Refusing to replace existing private configuration: $envFile"
}
if (Test-Path -LiteralPath $tlsDirectory) {
    throw "Refusing to replace existing private TLS material: $tlsDirectory"
}

Push-Location $script:PersonalMemoRepositoryRoot
try {
    # Codex and the interactive Windows user can legitimately have different SIDs for the same
    # workspace. Scope Git's ownership exception to this read-only command instead of mutating the
    # user's global safe.directory configuration.
    $safeRepository = ConvertTo-PersonalMemoDockerHostPath $script:PersonalMemoRepositoryRoot
    & git -c "safe.directory=$safeRepository" check-ignore -q -- '.env.personal'
    if ($LASTEXITCODE -ne 0) {
        throw '.env.personal is not protected by .gitignore.'
    }
} finally {
    Pop-Location
}

$openSsl = Find-GitOpenSsl
$stageDirectory = Join-Path $personalDirectory ("PrivateTls.build-" + [Guid]::NewGuid().ToString('N'))
$temporaryEnv = "$envFile.$([Guid]::NewGuid().ToString('N')).tmp"
$utf8NoBom = New-Object Text.UTF8Encoding($false)
$oldMsysConversion = [Environment]::GetEnvironmentVariable('MSYS2_ARG_CONV_EXCL', 'Process')
$personalDirectoryCreated = $false
$finalTlsCreated = $false
$backupDirectoryCreated = $false
$temporaryEnvCreated = $false
$finalEnvCreated = $false
$configurationCommitted = $false

try {
    if (Test-Path -LiteralPath $personalDirectory) {
        if (-not (Test-Path -LiteralPath $personalDirectory -PathType Container)) {
            throw "Personal Memo private path is not a directory: $personalDirectory"
        }
    } else {
        $null = New-Item -ItemType Directory -Path $personalDirectory
        $personalDirectoryCreated = $true
    }
    # Secure the dedicated parent before creating staging or backup children, then explicitly
    # secure the empty staging directory again before OpenSSL writes either private key.
    Set-PersonalMemoPrivateDirectoryAcl -Path $personalDirectory
    $null = New-Item -ItemType Directory -Path $stageDirectory
    Set-PersonalMemoPrivateDirectoryAcl -Path $stageDirectory
    [Environment]::SetEnvironmentVariable('MSYS2_ARG_CONV_EXCL', '*', 'Process')

    $caKey = Join-Path $stageDirectory 'ca-key.pem'
    $caCert = Join-Path $stageDirectory 'ca-cert.pem'
    $androidCaCert = Join-Path $stageDirectory 'personal-memo-ca.cer'
    $serverKey = Join-Path $stageDirectory 'server-key.pem'
    $serverRequest = Join-Path $stageDirectory 'server.csr'
    $serverExtensions = Join-Path $stageDirectory 'server.ext'
    $serverCert = Join-Path $stageDirectory 'server-cert.pem'

    Invoke-GitOpenSsl -OpenSsl $openSsl -OpenSslArguments @('genrsa', '-out', $caKey, '4096')
    Invoke-GitOpenSsl -OpenSsl $openSsl -OpenSslArguments @(
        'req', '-x509', '-new', '-sha256', '-days', '3650', '-key', $caKey,
        '-out', $caCert, '-subj', '/CN=Personal Memo Private CA',
        '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
        '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
        '-addext', 'subjectKeyIdentifier=hash'
    )
    Invoke-GitOpenSsl -OpenSsl $openSsl -OpenSslArguments @(
        'x509', '-in', $caCert, '-outform', 'DER', '-out', $androidCaCert
    )
    Invoke-GitOpenSsl -OpenSsl $openSsl -OpenSslArguments @('genrsa', '-out', $serverKey, '2048')
    Invoke-GitOpenSsl -OpenSsl $openSsl -OpenSslArguments @(
        'req', '-new', '-key', $serverKey, '-out', $serverRequest,
        '-subj', "/CN=$TlsHostName"
    )

    [IO.File]::WriteAllLines($serverExtensions, @(
        'basicConstraints=critical,CA:FALSE',
        'keyUsage=critical,digitalSignature,keyEncipherment',
        'extendedKeyUsage=serverAuth',
        "subjectAltName=IP:$LanIPv4,DNS:$TlsHostName,IP:127.0.0.1,DNS:localhost"
    ), $utf8NoBom)
    Invoke-GitOpenSsl -OpenSsl $openSsl -OpenSslArguments @(
        'x509', '-req', '-in', $serverRequest, '-CA', $caCert, '-CAkey', $caKey,
        '-CAcreateserial', '-out', $serverCert, '-days', '825', '-sha256',
        '-extfile', $serverExtensions
    )
    $null = Invoke-GitOpenSsl -OpenSsl $openSsl -Capture -OpenSslArguments @(
        'verify', '-CAfile', $caCert, $serverCert
    )

    foreach ($temporaryCertificateFile in @($serverRequest, $serverExtensions, (Join-Path $stageDirectory 'ca-cert.srl'))) {
        if (Test-Path -LiteralPath $temporaryCertificateFile -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryCertificateFile -Force
        }
    }

    Move-Item -LiteralPath $stageDirectory -Destination $tlsDirectory
    $finalTlsCreated = $true

    Set-PersonalMemoPrivateDirectoryAcl -Path $tlsDirectory
    foreach ($tlsFile in Get-ChildItem -LiteralPath $tlsDirectory -File) {
        Set-PersonalMemoPrivateFileAcl -Path $tlsFile.FullName
    }

    if (Test-Path -LiteralPath $backupDirectory) {
        if (-not (Test-Path -LiteralPath $backupDirectory -PathType Container)) {
            throw "Private backup path is not a directory: $backupDirectory"
        }
    } else {
        $null = New-Item -ItemType Directory -Path $backupDirectory
        $backupDirectoryCreated = $true
    }
    # New backup files inherit this single-user rule; existing directories are tightened before
    # initialization can report success.
    Set-PersonalMemoPrivateDirectoryAcl -Path $backupDirectory

    $databasePassword = New-PersonalMemoHexSecret -ByteCount 32
    $environmentLines = @(
        'POSTGRES_DB=personal_memo',
        'POSTGRES_USER=personal_memo_app',
        "POSTGRES_PASSWORD=$databasePassword",
        "PERSONAL_MEMO_FRONTEND_PORT=$FrontendPort",
        "PERSONAL_MEMO_HTTPS_BIND_ADDRESS=$LanIPv4",
        "PERSONAL_MEMO_HTTPS_PORT=$HttpsPort",
        "PERSONAL_MEMO_TLS_HOSTNAME=$TlsHostName",
        "PERSONAL_MEMO_TLS_CERT_FILE=$(ConvertTo-PersonalMemoDockerHostPath (Join-Path $tlsDirectory 'server-cert.pem'))",
        "PERSONAL_MEMO_TLS_KEY_FILE=$(ConvertTo-PersonalMemoDockerHostPath (Join-Path $tlsDirectory 'server-key.pem'))",
        "PERSONAL_MEMO_TLS_CA_FILE=$(ConvertTo-PersonalMemoDockerHostPath (Join-Path $tlsDirectory 'ca-cert.pem'))",
        'AUTH_REGISTRATION_ENABLED=false',
        'SESSION_COOKIE_SECURE=true',
        'GOOGLE_AUTH_ENABLED=false',
        'GOOGLE_REGISTRATION_ENABLED=false',
        'GOOGLE_CLIENT_ID=',
        'GOOGLE_CLIENT_SECRET=',
        'GOOGLE_REDIRECT_URI=',
        "PERSONAL_MEMO_BOOTSTRAP_EMAIL=$BootstrapEmail",
        "PERSONAL_MEMO_BOOTSTRAP_DISPLAY_NAME=$BootstrapDisplayName",
        "PERSONAL_MEMO_BOOTSTRAP_TIME_ZONE=$BootstrapTimeZone"
    )
    # Create and protect an empty file first. WriteAllLines then replaces only the contents while
    # preserving the already-private file security descriptor.
    $null = New-Item -ItemType File -Path $temporaryEnv -ErrorAction Stop
    $temporaryEnvCreated = $true
    Set-PersonalMemoPrivateFileAcl -Path $temporaryEnv
    [IO.File]::WriteAllLines($temporaryEnv, $environmentLines, $utf8NoBom)
    Assert-PersonalMemoPrivateAcl -Path $temporaryEnv
    $fingerprint = Invoke-GitOpenSsl -OpenSsl $openSsl -Capture -OpenSslArguments @(
        'x509', '-in', (Join-Path $tlsDirectory 'ca-cert.pem'), '-noout', '-fingerprint', '-sha256'
    )
    Move-Item -LiteralPath $temporaryEnv -Destination $envFile
    $finalEnvCreated = $true
    Assert-PersonalMemoPrivateAcl -Path $envFile
    $configurationCommitted = $true

    Write-Host "Created ignored private configuration: $envFile"
    Write-Host "Created private TLS material: $tlsDirectory"
    Write-Host "Created private backup directory: $backupDirectory"
    Write-Host $fingerprint
    Write-Host "Copy only $(Join-Path $tlsDirectory 'personal-memo-ca.cer') to the Galaxy device and install it as a CA certificate."
    Write-Host 'Never copy ca-key.pem or server-key.pem to the phone; both private keys must stay on this PC.'
    Write-Host 'Trust ca-cert.pem on Windows before using the HTTPS URL from this PC.'
    Write-Host 'No application account password was written to the environment file.'
} catch {
    if ($temporaryEnvCreated -and (Test-Path -LiteralPath $temporaryEnv -PathType Leaf)) {
        Remove-Item -LiteralPath $temporaryEnv -Force
    }
    if ($finalEnvCreated -and -not $configurationCommitted -and
        (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        Remove-Item -LiteralPath $envFile -Force
    }
    if (Test-Path -LiteralPath $stageDirectory -PathType Container) {
        $resolvedStage = [IO.Path]::GetFullPath($stageDirectory)
        if ((Test-PersonalMemoPathWithin -Child $resolvedStage -Parent $personalDirectory) -and
            ((Split-Path -Leaf $resolvedStage).StartsWith('PrivateTls.build-'))) {
            Remove-Item -LiteralPath $resolvedStage -Recurse -Force
        }
    }
    if ($finalTlsCreated -and -not $configurationCommitted -and
        (Test-Path -LiteralPath $tlsDirectory -PathType Container)) {
        $resolvedTlsDirectory = [IO.Path]::GetFullPath($tlsDirectory)
        if ((Test-PersonalMemoPathWithin -Child $resolvedTlsDirectory -Parent $personalDirectory) -and
            ((Split-Path -Leaf $resolvedTlsDirectory) -ceq 'PrivateTls')) {
            Remove-Item -LiteralPath $resolvedTlsDirectory -Recurse -Force
        }
    }
    if ($backupDirectoryCreated -and -not $configurationCommitted -and
        (Test-Path -LiteralPath $backupDirectory -PathType Container)) {
        $resolvedBackupDirectory = [IO.Path]::GetFullPath($backupDirectory)
        if ((Test-PersonalMemoPathWithin -Child $resolvedBackupDirectory -Parent $personalDirectory) -and
            ((Split-Path -Leaf $resolvedBackupDirectory) -ceq 'Backups')) {
            # The initializer never writes backup contents, so a non-empty directory indicates a
            # concurrent actor and is deliberately preserved instead of recursively deleted.
            $remainingBackups = @(Get-ChildItem -LiteralPath $resolvedBackupDirectory -Force)
            if ($remainingBackups.Count -eq 0) {
                Remove-Item -LiteralPath $resolvedBackupDirectory -Force
            }
        }
    }
    if ($personalDirectoryCreated -and -not $configurationCommitted -and
        (Test-Path -LiteralPath $personalDirectory -PathType Container)) {
        $resolvedPersonalDirectory = [IO.Path]::GetFullPath($personalDirectory)
        $remainingChildren = @(Get-ChildItem -LiteralPath $resolvedPersonalDirectory -Force)
        if ($remainingChildren.Count -eq 0 -and
            (Test-PersonalMemoPathWithin -Child $resolvedPersonalDirectory -Parent $documents) -and
            ((Split-Path -Leaf $resolvedPersonalDirectory) -ceq 'PersonalMemo')) {
            Remove-Item -LiteralPath $resolvedPersonalDirectory -Force
        }
    }
    throw
} finally {
    [Environment]::SetEnvironmentVariable('MSYS2_ARG_CONV_EXCL', $oldMsysConversion, 'Process')
}
