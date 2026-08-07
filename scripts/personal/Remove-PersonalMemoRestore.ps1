[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ProjectName,
    [string] $EnvFile,
    [switch] $ConfirmCleanup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

if (-not $ConfirmCleanup) {
    throw 'Pass -ConfirmCleanup only after verifying the exact disposable restore project name.'
}

$layoutArguments = @{ ProjectName = $ProjectName; RestoreProject = $true }
if (-not [string]::IsNullOrWhiteSpace($EnvFile)) {
    $layoutArguments.EnvFile = $EnvFile
}
$layout = New-PersonalMemoLayout @layoutArguments
$null = Assert-PersonalMemoRestoreComposeContract -Layout $layout

Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @('ps')
Invoke-PersonalMemoCompose -Layout $layout -CommandArguments @('down', '--volumes', '--remove-orphans')
Write-Host "Removed disposable restore project and its project-scoped volume: $ProjectName"
Write-Host 'No personal project or personal PostgreSQL volume was targeted.'
