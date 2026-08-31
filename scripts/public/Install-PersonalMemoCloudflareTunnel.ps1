#Requires -Version 5.1
#Requires -RunAsAdministrator

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $CloudflaredSourcePath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string] $ExpectedSha256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$serviceName = 'PersonalMemoCalendarCloudflareTunnel'
$installRoot = 'C:\ProgramData\PersonalMemo\Cloudflare'
$binaryDirectory = Join-Path $installRoot 'bin'
$secretDirectory = Join-Path $installRoot 'secrets'
$binaryPath = Join-Path $binaryDirectory 'cloudflared.exe'
$tokenPath = Join-Path $secretDirectory 'tunnel.token'
$manifestPath = Join-Path $installRoot 'installation-manifest.json'
$metricsAddress = '127.0.0.1:49312'
$minimumTokenFileVersion = [Version]'2025.4.0'
$administratorsSid = New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')
$systemSid = New-Object Security.Principal.SecurityIdentifier('S-1-5-18')

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this provisioning script from an elevated Windows PowerShell session.'
    }
}

function New-RestrictedRule {
    param(
        [Parameter(Mandatory = $true)]
        [Security.Principal.SecurityIdentifier] $Identity,
        [Parameter(Mandatory = $true)]
        [Security.AccessControl.FileSystemRights] $Rights,
        [Security.AccessControl.InheritanceFlags] $Inheritance =
            [Security.AccessControl.InheritanceFlags]::None
    )

    return New-Object Security.AccessControl.FileSystemAccessRule -ArgumentList @(
        $Identity,
        $Rights,
        $Inheritance,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow
    )
}

function Set-RestrictedDirectoryAcl {
    param([Parameter(Mandatory = $true)][string] $Path)

    $inheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
        [Security.AccessControl.InheritanceFlags]::ObjectInherit
    $security = New-Object Security.AccessControl.DirectorySecurity
    $security.SetOwner($administratorsSid)
    $security.SetAccessRuleProtection($true, $false)
    $null = $security.AddAccessRule((New-RestrictedRule `
        -Identity $administratorsSid `
        -Rights ([Security.AccessControl.FileSystemRights]::FullControl) `
        -Inheritance $inheritance))
    $null = $security.AddAccessRule((New-RestrictedRule `
        -Identity $systemSid `
        -Rights ([Security.AccessControl.FileSystemRights]::FullControl) `
        -Inheritance $inheritance))
    [IO.Directory]::SetAccessControl($Path, $security)
}

function Set-RestrictedFileAcl {
    param([Parameter(Mandatory = $true)][string] $Path)

    $security = New-Object Security.AccessControl.FileSecurity
    $security.SetOwner($administratorsSid)
    $security.SetAccessRuleProtection($true, $false)
    $null = $security.AddAccessRule((New-RestrictedRule `
        -Identity $administratorsSid `
        -Rights ([Security.AccessControl.FileSystemRights]::FullControl)))
    $null = $security.AddAccessRule((New-RestrictedRule `
        -Identity $systemSid `
        -Rights ([Security.AccessControl.FileSystemRights]::FullControl)))
    [IO.File]::SetAccessControl($Path, $security)
}

function Assert-RestrictedAcl {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [switch] $Directory
    )

    $security = if ($Directory) {
        [IO.Directory]::GetAccessControl($Path)
    } else {
        [IO.File]::GetAccessControl($Path)
    }
    if (-not $security.AreAccessRulesProtected) {
        throw "Cloudflare private path still inherits access rules: $Path"
    }
    if ($security.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $administratorsSid.Value) {
        throw "Cloudflare private path must be owned by Administrators: $Path"
    }
    $allowedSids = @($administratorsSid.Value, $systemSid.Value)
    $rules = @($security.GetAccessRules(
        $true,
        $true,
        [Security.Principal.SecurityIdentifier]
    ))
    if ($rules.Count -ne 2) {
        throw "Cloudflare private path must have exactly two explicit access rules: $Path"
    }
    foreach ($rule in $rules) {
        if ($rule.IsInherited -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $allowedSids -notcontains $rule.IdentityReference.Value -or
            $rule.FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) {
            throw "Cloudflare private path grants unexpected access: $Path"
        }
    }
}

function Assert-CloudflaredArtifact {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Sha256
    )

    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if (-not $actualHash.Equals($Sha256, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The cloudflared SHA-256 does not match the separately reviewed release hash.'
    }

    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    if ($signature.Status -ne [Management.Automation.SignatureStatus]::Valid -or
        $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -notmatch '(?i)(?:CN|O)\s*=\s*"?Cloudflare,\s*Inc\."?') {
        throw 'cloudflared must have a valid Authenticode signature issued to Cloudflare, Inc.'
    }

    $versionLines = @(& $Path --version 2>&1)
    $versionExitCode = $LASTEXITCODE
    $versionSource = $versionLines -join ' '
    $versionMatch = [regex]::Match(
        [string] $versionSource,
        '(?i)\bcloudflared\s+version\s+(?<version>\d{4}\.\d+\.\d+)\b'
    )
    if ($versionExitCode -ne 0 -or -not $versionMatch.Success) {
        throw 'The signed cloudflared artifact did not report a supported cloudflared version.'
    }
    $artifactVersion = [Version]$versionMatch.Groups['version'].Value
    if ($artifactVersion -lt $minimumTokenFileVersion) {
        throw 'cloudflared 2025.4.0 or later is required for --token-file.'
    }

    return [PSCustomObject]@{
        Sha256 = $actualHash.ToUpperInvariant()
        Version = $artifactVersion.ToString()
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

    # Remotely-managed Tunnel tokens are opaque. Restrict the accepted alphabet to
    # common base64/base64url/JWT token characters so copied shell syntax can never
    # be confused with a raw token.
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

Assert-Administrator

$resolvedSourcePath = [IO.Path]::GetFullPath($CloudflaredSourcePath)
if (-not [IO.File]::Exists($resolvedSourcePath)) {
    throw "The reviewed cloudflared artifact was not found: $resolvedSourcePath"
}
if (([IO.File]::GetAttributes($resolvedSourcePath) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'The reviewed cloudflared artifact cannot be a reparse point.'
}
$null = Assert-CloudflaredArtifact -Path $resolvedSourcePath -Sha256 $ExpectedSha256

if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
    throw "The stopped connector service already exists: $serviceName"
}
if (Test-Path -LiteralPath $installRoot) {
    throw "The Cloudflare install root already exists; review it instead of overwriting it: $installRoot"
}

if (-not $PSCmdlet.ShouldProcess(
    $installRoot,
    'Provision a signed, stopped, manual-start Cloudflare calendar connector'
)) {
    return
}

$secureToken = $null
$tokenPointer = [IntPtr]::Zero
$secretInputText = $null
$tokenText = $null
$serviceCreated = $false
$installRootCreated = $false
$tokenPersisted = $false
try {
    $null = New-Item -ItemType Directory -Path $installRoot
    $installRootCreated = $true
    Set-RestrictedDirectoryAcl -Path $installRoot
    Assert-RestrictedAcl -Path $installRoot -Directory

    $null = New-Item -ItemType Directory -Path $binaryDirectory
    $null = New-Item -ItemType Directory -Path $secretDirectory
    Set-RestrictedDirectoryAcl -Path $binaryDirectory
    Set-RestrictedDirectoryAcl -Path $secretDirectory
    Assert-RestrictedAcl -Path $binaryDirectory -Directory
    Assert-RestrictedAcl -Path $secretDirectory -Directory

    Copy-Item -LiteralPath $resolvedSourcePath -Destination $binaryPath
    Set-RestrictedFileAcl -Path $binaryPath
    Assert-RestrictedAcl -Path $binaryPath
    $installedArtifact = Assert-CloudflaredArtifact -Path $binaryPath -Sha256 $ExpectedSha256

    $manifestJson = [ordered]@{
        schemaVersion = 1
        cloudflaredSha256 = $installedArtifact.Sha256
        cloudflaredVersion = $installedArtifact.Version
    } | ConvertTo-Json -Compress
    $manifestStream = New-Object IO.FileStream(
        $manifestPath,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try {
        $manifestWriter = New-Object IO.StreamWriter(
            $manifestStream,
            (New-Object Text.UTF8Encoding($false))
        )
        try {
            $manifestWriter.Write($manifestJson)
            $manifestWriter.Flush()
        } finally {
            $manifestWriter.Dispose()
        }
    } finally {
        if ($null -ne $manifestStream) {
            $manifestStream.Dispose()
        }
    }
    Set-RestrictedFileAcl -Path $manifestPath
    Assert-RestrictedAcl -Path $manifestPath

    $secureToken = Read-Host `
        -Prompt 'Paste the Tunnel token or exact cloudflared.exe service install command (input is hidden)' `
        -AsSecureString
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    $secretInputText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
    $tokenText = ConvertFrom-CloudflareTunnelSecretInput -SecretInput $secretInputText

    $stream = New-Object IO.FileStream(
        $tokenPath,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try {
        $writer = New-Object IO.StreamWriter(
            $stream,
            (New-Object Text.UTF8Encoding($false))
        )
        try {
            $writer.Write($tokenText)
            $writer.Flush()
            $tokenPersisted = $true
        } finally {
            $writer.Dispose()
        }
    } finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }
    Set-RestrictedFileAcl -Path $tokenPath
    Assert-RestrictedAcl -Path $tokenPath

    $binaryPathName = '"{0}" tunnel --no-autoupdate --loglevel warn --transport-loglevel warn --grace-period 30s --metrics {1} run --token-file "{2}"' -f `
        $binaryPath,
        $metricsAddress,
        $tokenPath
    $null = New-Service `
        -Name $serviceName `
        -BinaryPathName $binaryPathName `
        -DisplayName 'Personal Memo Calendar Cloudflare Connector' `
        -Description 'Stopped-by-default connector for the dedicated public calendar feed.' `
        -StartupType Manual
    $serviceCreated = $true

    $imagePath = (Get-ItemProperty -LiteralPath (
        'HKLM:\SYSTEM\CurrentControlSet\Services\{0}' -f $serviceName
    ) -Name ImagePath).ImagePath
    if ($imagePath.IndexOf('--token-file', [StringComparison]::Ordinal) -lt 0 -or
        [regex]::IsMatch($imagePath, '(?i)--token(?:\s|=)')) {
        throw 'The service ImagePath must reference only the protected token file.'
    }
    $service = Get-Service -Name $serviceName
    if ($service.StartType -ne [ServiceProcess.ServiceStartMode]::Manual -or
        $service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        throw 'The connector must remain stopped and Manual-start after provisioning.'
    }

    Write-Host 'Provisioned the signed Cloudflare connector as a stopped Manual-start service.'
    Write-Host 'No public route or Personal Memo publication capability was activated.'
} catch {
    $installFailure = $_
    $cleanupFailures = New-Object Collections.Generic.List[string]
    if ($serviceCreated) {
        $null = & sc.exe delete $serviceName
        if ($LASTEXITCODE -ne 0) {
            $cleanupFailures.Add('the stopped Windows service could not be deleted')
        } else {
            for ($attempt = 0; $attempt -lt 20; $attempt++) {
                if ($null -eq (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)) {
                    break
                }
                Start-Sleep -Milliseconds 250
            }
            if ($null -ne (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)) {
                $cleanupFailures.Add('the Windows service is still pending deletion')
            }
        }
    }
    if ([IO.File]::Exists($tokenPath)) {
        try {
            Remove-Item -LiteralPath $tokenPath -Force
        } catch {
            $cleanupFailures.Add('the protected Tunnel token file could not be removed')
        }
    }
    if ([IO.File]::Exists($manifestPath)) {
        try {
            Remove-Item -LiteralPath $manifestPath -Force
        } catch {
            $cleanupFailures.Add('the protected non-secret installation manifest could not be removed')
        }
    }
    if ([IO.File]::Exists($binaryPath)) {
        try {
            Remove-Item -LiteralPath $binaryPath -Force
        } catch {
            $cleanupFailures.Add('the copied cloudflared binary could not be removed')
        }
    }
    if ($installRootCreated -and [IO.Directory]::Exists($installRoot)) {
        try {
            Remove-Item -LiteralPath $installRoot -Recurse -Force
        } catch {
            $cleanupFailures.Add('the exact Cloudflare install root could not be removed')
        }
    }
    if ($tokenPersisted) {
        Write-Warning (
            'The Tunnel token reached local disk before provisioning failed. Rotate the Tunnel ' +
            'token in Cloudflare before retrying.'
        )
    }
    if ($cleanupFailures.Count -ne 0) {
        throw (
            'Cloudflare connector provisioning failed and cleanup is incomplete: ' +
            ($cleanupFailures -join '; ') + '. Original failure: ' +
            $installFailure.Exception.Message
        )
    }
    throw $installFailure
} finally {
    $secretInputText = $null
    $tokenText = $null
    if ($tokenPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
    }
    if ($null -ne $secureToken) {
        $secureToken.Dispose()
    }
}
