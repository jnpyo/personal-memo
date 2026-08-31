#Requires -Version 5.1
#Requires -RunAsAdministrator

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $CloudflaredExe,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string] $ExpectedSha256
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$serviceName = 'PersonalMemoAppCloudflareTunnel'
$installRoot = 'C:\ProgramData\PersonalMemo\AppCloudflare'
$installedExe = Join-Path $installRoot 'cloudflared.exe'
$tokenFile = Join-Path $installRoot 'tunnel-token.txt'
$manifestFile = Join-Path $installRoot 'install-manifest.json'
$minimumVersion = [version]'2025.4.0'
$metricsAddress = '127.0.0.1:49313'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this installer from an elevated Windows PowerShell session.'
    }
}

function Assert-CloudflaredArtifact([string] $Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $item = Get-Item -LiteralPath $resolved
    if ($item.PSIsContainer -or $item.Name -ne 'cloudflared.exe') {
        throw 'CloudflaredExe must identify cloudflared.exe.'
    }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'The reviewed cloudflared artifact cannot be a reparse point.'
    }
    $signature = Get-AuthenticodeSignature -FilePath $resolved
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid -or
        $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -notmatch 'Cloudflare, Inc\.') {
        throw 'cloudflared.exe must have a valid Cloudflare, Inc. Authenticode signature.'
    }
    $sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash
    if (-not $sha256.Equals($ExpectedSha256, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The cloudflared SHA-256 does not match the separately reviewed release hash.'
    }
    $versionLines = @(& $resolved --version 2>&1)
    $versionExitCode = $LASTEXITCODE
    $versionMatch = [regex]::Match(
        [string]($versionLines -join ' '),
        '(?i)\bcloudflared\s+version\s+(?<version>\d{4}\.\d+\.\d+)\b'
    )
    if ($versionExitCode -ne 0 -or -not $versionMatch.Success) {
        throw 'The reviewed cloudflared artifact did not report a supported version.'
    }
    $version = [version]$versionMatch.Groups['version'].Value
    if ($version -lt $minimumVersion) { throw "cloudflared $minimumVersion or newer is required." }
    return [pscustomobject]@{
        Path = $resolved
        Version = $version.ToString()
        Sha256 = $sha256.ToLowerInvariant()
    }
}

function ConvertTo-PlainText([Security.SecureString] $SecureValue) {
    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
}

function ConvertFrom-CloudflareTunnelSecretInput {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $SecretInput
    )

    if ([string]::IsNullOrWhiteSpace($SecretInput) -or
        $SecretInput.Length -gt 4140 -or
        $SecretInput.IndexOf("`r", [StringComparison]::Ordinal) -ge 0 -or
        $SecretInput.IndexOf("`n", [StringComparison]::Ordinal) -ge 0) {
        throw 'Tunnel credential input must be one supported, non-empty line.'
    }

    # Remotely managed Tunnel tokens are opaque values rather than guaranteed
    # three-segment JWTs. Keep the accepted alphabet non-executable while allowing
    # the base64/base64url forms emitted by Cloudflare.
    $tokenPattern = '[A-Za-z0-9._~+/-]{20,4094}={0,2}'
    $rawTokenMatch = [regex]::Match(
        $SecretInput,
        ('\A(?<token>{0})\z' -f $tokenPattern),
        [Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    if ($rawTokenMatch.Success) {
        return $rawTokenMatch.Groups['token'].Value
    }

    $windowsInstallCommandMatch = [regex]::Match(
        $SecretInput,
        ('\A(?i:cloudflared\.exe service install )(?<token>{0})\z' -f $tokenPattern),
        [Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    if ($windowsInstallCommandMatch.Success) {
        return $windowsInstallCommandMatch.Groups['token'].Value
    }

    throw 'Tunnel credential input must be a raw token or the exact Cloudflare Windows install command.'
}

function Get-TunnelToken {
    $secure = Read-Host 'Paste the app tunnel token (input is hidden)' -AsSecureString
    $value = ConvertTo-PlainText $secure
    try {
        return ConvertFrom-CloudflareTunnelSecretInput -SecretInput $value
    }
    finally {
        $value = $null
        $secure.Dispose()
    }
}

function Set-ProtectedAcl([string] $Path) {
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner((New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')))
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sidText in @('S-1-5-18', 'S-1-5-32-544')) {
        $sid = New-Object Security.Principal.SecurityIdentifier($sidText)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule(
            $sid, [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        [void]$acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Set-ProtectedFileAcl([string] $Path) {
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetOwner((New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')))
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sidText in @('S-1-5-18', 'S-1-5-32-544')) {
        $sid = New-Object Security.Principal.SecurityIdentifier($sidText)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule(
            $sid, [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.AccessControlType]::Allow)
        [void]$acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Write-Utf8NoBom([string] $Path, [string] $Value) {
    [IO.File]::WriteAllText($Path, $Value, (New-Object Text.UTF8Encoding($false)))
}

Assert-Administrator
if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
    throw "Service $serviceName already exists. Remove it using an explicit rollback procedure before reinstalling."
}
if (Test-Path -LiteralPath $installRoot) {
    throw "$installRoot already exists. Review and remove it explicitly before reinstalling."
}
$artifact = Assert-CloudflaredArtifact $CloudflaredExe
$token = $null
$createdRoot = $false
$serviceCreated = $false
$tokenPersisted = $false
try {
    if (-not $PSCmdlet.ShouldProcess($serviceName, 'Install a stopped, manual app tunnel service')) { return }
    $token = Get-TunnelToken
    New-Item -ItemType Directory -Path $installRoot | Out-Null
    $createdRoot = $true
    Set-ProtectedAcl $installRoot
    Copy-Item -LiteralPath $artifact.Path -Destination $installedExe
    Write-Utf8NoBom $tokenFile ($token + "`r`n")
    $tokenPersisted = $true
    $manifest = [ordered]@{ schemaVersion = 1; cloudflaredVersion = $artifact.Version; cloudflaredSha256 = $artifact.Sha256 }
    Write-Utf8NoBom $manifestFile (($manifest | ConvertTo-Json -Compress) + "`r`n")
    foreach ($protectedFile in @($installedExe, $tokenFile, $manifestFile)) { Set-ProtectedFileAcl $protectedFile }
    $imagePath = ('"{0}" tunnel --no-autoupdate --loglevel warn --transport-loglevel warn --grace-period 30s --metrics {1} run --token-file "{2}"' -f $installedExe, $metricsAddress, $tokenFile)
    New-Service -Name $serviceName -BinaryPathName $imagePath -DisplayName 'Personal Memo App Cloudflare Tunnel' -Description 'Cloudflare connector for the Access-protected Personal Memo application.' -StartupType Manual | Out-Null
    $serviceCreated = $true
    $service = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    if ($service.StartMode -ne 'Manual' -or $service.State -ne 'Stopped' -or $service.StartName -ne 'LocalSystem') {
        throw 'The installed service did not retain the required manual/stopped/LocalSystem contract.'
    }
    Write-Host "Installed $serviceName in the stopped/manual state. No token was printed."
}
catch {
    $installFailure = $_
    $cleanupFailures = New-Object Collections.Generic.List[string]
    $serviceDeletionProven = $false
    $serviceObserved = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if (-not $serviceCreated -and $null -eq $serviceObserved) {
        $serviceDeletionProven = $true
    }
    else {
        & sc.exe delete $serviceName | Out-Null
        $serviceDeleteExitCode = $LASTEXITCODE
        if ($serviceDeleteExitCode -ne 0) {
            $cleanupFailures.Add('sc.exe could not accept deletion of the app connector service')
        }
        else {
            for ($attempt = 0; $attempt -lt 20; $attempt++) {
                if ($null -eq (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)) {
                    $serviceDeletionProven = $true
                    break
                }
                Start-Sleep -Milliseconds 250
            }
            if (-not $serviceDeletionProven) {
                $cleanupFailures.Add('the app connector service is still pending deletion after the bounded wait')
            }
        }
    }

    if ($createdRoot -and (Test-Path -LiteralPath $installRoot)) {
        if ($serviceDeletionProven) {
            try {
                Remove-Item -LiteralPath $installRoot -Recurse -Force
            }
            catch {
                $cleanupFailures.Add('the exact protected app connector root could not be removed')
            }
        }
        else {
            Write-Warning (
                "Protected app connector artifacts were preserved at $installRoot because Windows " +
                'service deletion was not proven. Do not remove that directory while the service may exist.'
            )
            $cleanupFailures.Add('protected app connector artifacts were preserved because service deletion was not proven')
        }
    }
    if ($tokenPersisted) {
        Write-Warning 'The app Tunnel token reached local disk before provisioning failed. Rotate it in Cloudflare before retrying.'
    }
    if ($cleanupFailures.Count -ne 0) {
        throw (
            'App Tunnel provisioning failed and cleanup is incomplete. SAFE_CODE: ' +
            'APP_TUNNEL_INSTALL_CLEANUP_INCOMPLETE. From an elevated Windows PowerShell session, ' +
            "verify that Get-Service -Name '$serviceName' -ErrorAction SilentlyContinue returns no " +
            "service before removing only $installRoot. Rotate the app Tunnel token in Cloudflare " +
            'before retrying if this run accepted it. Details: ' + ($cleanupFailures -join '; ')
        )
    }
    throw $installFailure
}
finally { $token = $null }
