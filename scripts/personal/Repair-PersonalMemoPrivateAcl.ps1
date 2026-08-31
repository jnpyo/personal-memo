[CmdletBinding()]
param(
    [string] $EnvFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

$personalDirectory = Join-Path (Get-PersonalMemoDocumentsDirectory) 'PersonalMemo'
$backupDirectory = Join-Path $personalDirectory 'Backups'
$tlsDirectory = Join-Path $personalDirectory 'PrivateTls'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $script:PersonalMemoRepositoryRoot '.env.personal'
}
$resolvedEnvFile = [IO.Path]::GetFullPath($EnvFile)

foreach ($requiredDirectory in @($personalDirectory, $backupDirectory, $tlsDirectory)) {
    if (-not (Test-Path -LiteralPath $requiredDirectory -PathType Container)) {
        throw "Private directory was not found: $requiredDirectory"
    }
}
if (-not (Test-Path -LiteralPath $resolvedEnvFile -PathType Leaf)) {
    throw "Private environment file was not found: $resolvedEnvFile"
}

Set-PersonalMemoPrivateDirectoryAcl -Path $personalDirectory
Set-PersonalMemoPrivateDirectoryAcl -Path $backupDirectory
foreach ($directory in Get-ChildItem -LiteralPath $backupDirectory -Directory -Recurse) {
    Set-PersonalMemoPrivateDirectoryAcl -Path $directory.FullName
}
foreach ($file in Get-ChildItem -LiteralPath $backupDirectory -File -Recurse) {
    Set-PersonalMemoPrivateFileAcl -Path $file.FullName
}
Set-PersonalMemoPrivateDirectoryAcl -Path $tlsDirectory
foreach ($file in Get-ChildItem -LiteralPath $tlsDirectory -File) {
    Set-PersonalMemoPrivateFileAcl -Path $file.FullName
}
Set-PersonalMemoPrivateFileAcl -Path $resolvedEnvFile

Assert-PersonalMemoPrivateAcl -Path $personalDirectory -Directory
Assert-PersonalMemoPrivateAcl -Path $backupDirectory -Directory
Assert-PersonalMemoPrivateAcl -Path $tlsDirectory -Directory
Assert-PersonalMemoPrivateAcl -Path $resolvedEnvFile

Write-Host "Private ACL repair passed for $([Security.Principal.WindowsIdentity]::GetCurrent().Name)."
