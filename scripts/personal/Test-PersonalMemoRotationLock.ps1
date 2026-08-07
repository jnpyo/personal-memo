[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

if ($env:OS -ne 'Windows_NT') {
    Write-Host 'Cross-process password-rotation lock contract is Windows-only; skipped.'
    exit 0
}

$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $tempParent ('personal-memo-rotation-lock-contract-' + [Guid]::NewGuid().ToString('N'))
$lockStream = $null
$child = $null
$standardOutput = $null
$standardError = $null
$standardOutputTask = $null
$standardErrorTask = $null

try {
    $null = New-Item -ItemType Directory -Path $tempRoot
    Set-PersonalMemoPrivateDirectoryAcl -Path $tempRoot
    $fakeEnvironment = Join-Path $tempRoot 'missing.env'
    $lockPath = "$fakeEnvironment.rotation.lock"
    $null = New-Item -ItemType File -Path $lockPath
    Set-PersonalMemoPrivateFileAcl -Path $lockPath
    $lockStream = [IO.File]::Open(
        $lockPath,
        [IO.FileMode]::Open,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None
    )

    $powerShellCommand = @(Get-Command powershell.exe -CommandType Application -ErrorAction Stop)[0].Source
    $rotationScript = Join-Path $PSScriptRoot 'Rotate-PersonalMemoDatabasePassword.ps1'
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $powerShellCommand
    $startInfo.Arguments = (
        '-NoProfile -ExecutionPolicy Bypass -File "{0}" ' +
        '-ProjectName personal-memo-private-lock-contract -EnvFile "{1}"'
    ) -f $rotationScript, $fakeEnvironment
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $child = New-Object Diagnostics.Process
    $child.StartInfo = $startInfo
    if (-not $child.Start()) {
        throw 'The rotation-lock contract child process could not start.'
    }
    $standardOutputTask = $child.StandardOutput.ReadToEndAsync()
    $standardErrorTask = $child.StandardError.ReadToEndAsync()
    $child.WaitForExit()
    $standardOutput = $standardOutputTask.GetAwaiter().GetResult()
    $standardError = $standardErrorTask.GetAwaiter().GetResult()
    $combined = $standardOutput + [Environment]::NewLine + $standardError
    if ($child.ExitCode -eq 0) {
        throw 'A concurrent password-rotation process unexpectedly passed the exclusive lock.'
    }
    if (-not $combined.Contains(
        'Another database password rotation is already running for this environment file.'
    )) {
        throw 'The concurrent password-rotation process did not fail at the exclusive lock boundary.'
    }
} finally {
    if ($null -ne $child) {
        $child.Dispose()
    }
    if ($null -ne $lockStream) {
        $lockStream.Dispose()
    }
    $standardOutput = $null
    $standardError = $null
    $standardOutputTask = $null
    $standardErrorTask = $null
    if (Test-Path -LiteralPath $tempRoot -PathType Container) {
        $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
        if ($resolvedTemp.StartsWith($tempParent, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedTemp).StartsWith('personal-memo-rotation-lock-contract-')) {
            Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
        }
    }
}

Write-Host 'Personal Memo cross-process password-rotation lock contract is valid.'
